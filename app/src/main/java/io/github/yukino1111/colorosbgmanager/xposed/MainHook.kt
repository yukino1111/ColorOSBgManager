package io.github.yukino1111.colorosbgmanager.xposed

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.Application
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.UserHandle
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import io.github.yukino1111.colorosbgmanager.BuildConfig
import io.github.yukino1111.colorosbgmanager.model.AppUserKey
import io.github.yukino1111.colorosbgmanager.model.BgSubsystem
import io.github.yukino1111.colorosbgmanager.model.DozePolicyHelper
import io.github.yukino1111.colorosbgmanager.model.GlobalRuleFlags
import io.github.yukino1111.colorosbgmanager.model.HookDiagnosticEntry
import io.github.yukino1111.colorosbgmanager.model.HookPointStatus
import io.github.yukino1111.colorosbgmanager.model.PkgRuntimeStatus
import io.github.yukino1111.colorosbgmanager.model.PolicyMode
import io.github.yukino1111.colorosbgmanager.model.RulesCodec
import io.github.yukino1111.colorosbgmanager.model.RuntimeEvent
import io.github.yukino1111.colorosbgmanager.xposed.hooks.AthenaHooks
import io.github.yukino1111.colorosbgmanager.xposed.hooks.BatteryDozeHooks
import io.github.yukino1111.colorosbgmanager.xposed.hooks.BroadcastHooks
import io.github.yukino1111.colorosbgmanager.xposed.hooks.CpuLimitHooks
import io.github.yukino1111.colorosbgmanager.xposed.hooks.DozeNetHooks
import io.github.yukino1111.colorosbgmanager.xposed.hooks.HansHooks
import io.github.yukino1111.colorosbgmanager.xposed.hooks.StartupHooks
import io.github.yukino1111.colorosbgmanager.xposed.hooks.SystemLifecycleHooks
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class MainHook : XposedModule(), HookHost {

    @Volatile
    private var rules: Map<AppUserKey, Map<BgSubsystem, PolicyMode>> = emptyMap()

    @Volatile
    override var autoFreezePkgs: Set<AppUserKey> = emptySet()

    @Volatile
    override var autoUnfreezeDisabledPkgs: Set<AppUserKey> = emptySet()

    @Volatile
    override var globalFlags: GlobalRuleFlags = GlobalRuleFlags()

    @Volatile
    private var systemContext: Context? = null

    @Volatile
    private var systemServerClassLoader: ClassLoader? = null

    @Volatile
    override var amsInstance: Any? = null

    @Volatile
    override var deviceIdleController: Any? = null

    @Volatile
    override var appStandbyController: Any? = null

    @Volatile
    override var bootCompleted: Boolean = false

    @Volatile
    override var powerWhitelistHooked: Boolean = false

    private var remotePrefs: SharedPreferences? = null
    private val hooked = AtomicBoolean(false)
    private val settingsBridgeHooked = AtomicBoolean(false)
    private val attachWatcherHooked = AtomicBoolean(false)
    private val screenReceiverRegistered = AtomicBoolean(false)
    private val hookStatus = ConcurrentHashMap<String, Boolean>()
    private val diagnostics = HookDiagnosticsRegistry()
    private val uidPkgCache = ConcurrentHashMap<Int, List<String>>()
    private val pkgAppIdCache = ConcurrentHashMap<String, Int>()

    private val eventLock = Any()
    private val eventBuffer = ArrayDeque<RuntimeEvent>(MAX_EVENTS)
    private val eventCooldown = ConcurrentHashMap<String, Long>()
    private val removeTaskDepth = ThreadLocal.withInitial { 0 }
    private val pendingFreezeRunnables = ConcurrentHashMap<AppUserKey, Runnable>()

    override val workerHandler: Handler by lazy {
        val thread = HandlerThread("BgManagerWorker").apply { start() }
        Handler(thread.looper)
    }

    private val syncDozeRunnable = Runnable { syncBatteryDozeState() }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, _ ->
        refreshRules(prefs)
    }

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        super.onModuleLoaded(param)
        initPreferences()
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        super.onSystemServerStarting(param)
        initPreferences()
        installAllSystemHooks(param.classLoader)
    }

    override fun onPackageReady(param: PackageReadyParam) {
        super.onPackageReady(param)
        if (param.packageName == "system" || param.packageName == "android") {
            initPreferences()
            installAllSystemHooks(param.classLoader)
        }
        if (param.packageName == "com.android.providers.settings") {
            installSettingsBridge(param.classLoader)
        }
    }

    override fun onHotReloading(param: HotReloadingParam): Boolean = true

    override fun onHotReloaded(param: HotReloadedParam) {
        super.onHotReloaded(param)
        initPreferences()
    }

    override fun interceptMethod(method: Method, block: (XposedInterface.Chain) -> Any?) {
        hook(method).intercept { chain -> block(chain) }
    }

    override fun recordDiagnostic(entry: HookDiagnosticEntry) {
        diagnostics.record(entry)
    }

    private fun initPreferences() {
        if (remotePrefs != null) return
        runCatching {
            val prefs = getRemotePreferences(RulesCodec.PREF_NAME)
            remotePrefs = prefs
            refreshRules(prefs)
            prefs.registerOnSharedPreferenceChangeListener(prefListener)
        }.onFailure {
            logMsg(Log.WARN, "Failed to init remote preferences", it)
        }
    }

    private fun refreshRules(prefs: SharedPreferences) {
        val raw = prefs.getStringSet(RulesCodec.KEY_RULES_SET, emptySet())
        rules = RulesCodec.decodeRules(raw)
        autoFreezePkgs = RulesCodec.decodeFreezePackages(prefs.getStringSet(RulesCodec.KEY_FREEZE_PKGS_SET, emptySet()))
        autoUnfreezeDisabledPkgs = RulesCodec.decodeFreezePackages(
            prefs.getStringSet(RulesCodec.KEY_AUTO_UNFREEZE_DISABLED_SET, emptySet()),
        )
        globalFlags = RulesCodec.readGlobalFlags(prefs)
        logMsg(
            Log.INFO,
            "Rules refreshed: ${rules.size} targets, freeze=${autoFreezePkgs.size}, unfreezeOff=${autoUnfreezeDisabledPkgs.size}, globals=$globalFlags",
        )
        for (key in pendingFreezeRunnables.keys) {
            if (key !in autoFreezePkgs) {
                pendingFreezeRunnables.remove(key)?.let { workerHandler.removeCallbacks(it) }
            }
        }
        if (bootCompleted) {
            scheduleBatteryDozeSync()
        }
    }

    override fun modeFor(pkg: String?, userId: Int, sub: BgSubsystem): PolicyMode {
        if (pkg.isNullOrEmpty()) return PolicyMode.FOLLOW_SYSTEM
        return rules[AppUserKey(pkg, userId)]?.get(sub) ?: PolicyMode.FOLLOW_SYSTEM
    }

    override fun modeForFallback(pkg: String?, sub: BgSubsystem): PolicyMode {
        if (pkg.isNullOrEmpty()) return PolicyMode.FOLLOW_SYSTEM
        rules[AppUserKey(pkg, 0)]?.get(sub)?.let { return it }
        for ((key, map) in rules) {
            if (key.packageName == pkg) {
                map[sub]?.let { return it }
            }
        }
        return PolicyMode.FOLLOW_SYSTEM
    }

    override fun uidHasMode(uid: Int, sub: BgSubsystem, target: PolicyMode): Boolean {
        if (MultiUserPackageHelper.appIdOfUid(uid) < 10000) return false
        val userId = MultiUserPackageHelper.userIdOfUid(uid)
        val pkgs = resolvePackagesForUid(uid)
        return pkgs.any { modeFor(it, userId, sub) == target }
    }

    override fun resolveSystemContext(): Context? {
        systemContext?.let { return it }
        return runCatching {
            val atClass = Class.forName("android.app.ActivityThread")
            val app = atClass.getMethod("currentApplication").invoke(null) as? Application
            app?.applicationContext?.also { systemContext = it }
        }.getOrNull()
    }

    private fun resolvePackagesForUid(uid: Int): List<String> {
        uidPkgCache[uid]?.let { return it }
        val ctx = resolveSystemContext() ?: return emptyList()
        val pkgs = runCatching { ctx.packageManager?.getPackagesForUid(uid)?.toList() }.getOrNull().orEmpty()
        if (pkgs.isNotEmpty()) {
            uidPkgCache[uid] = pkgs
        }
        return pkgs
    }

    private fun installAllSystemHooks(classLoader: ClassLoader) {
        systemServerClassLoader = classLoader
        if (!hooked.compareAndSet(false, true)) return
        installSettingsBridgeWatcher()
        installSettingsBridge(classLoader)
        SystemLifecycleHooks.install(this, classLoader)

        val installs = listOf(
            BgSubsystem.HANS_FREEZE.key to HansHooks.install(this, classLoader),
            BgSubsystem.AUTO_START.key to StartupHooks.installAutoStart(this, classLoader),
            BgSubsystem.ASSOCIATE_START.key to StartupHooks.installAssociateStart(this, classLoader),
            BgSubsystem.BATTERY_DOZE.key to BatteryDozeHooks.install(this, classLoader),
            BgSubsystem.ATHENA_RECENT.key to AthenaHooks.install(this, classLoader, removeTaskDepth),
            BgSubsystem.CPU_LIMIT.key to CpuLimitHooks.install(this, classLoader),
            BgSubsystem.DOZE_NET.key to DozeNetHooks.install(this, classLoader),
            BgSubsystem.BROADCAST_RESTRICT.key to BroadcastHooks.install(this, classLoader),
        )
        installs.forEach { (key, anyOk) ->
            hookStatus[key] = anyOk && diagnostics.isScopeActive(key)
        }
        log(Log.INFO, TAG, "Loaded in system_server: hooks=$hookStatus")
    }

    private fun installSettingsBridgeWatcher() {
        if (!attachWatcherHooked.compareAndSet(false, true)) return
        runCatching {
            ContentProvider::class.java.declaredMethods
                .filter { it.name == "attachInfo" }
                .forEach { method ->
                    hook(method).intercept { chain ->
                        val result = chain.proceed()
                        val provider = chain.thisObject
                        if (provider != null && provider.javaClass.name == "com.android.providers.settings.SettingsProvider") {
                            (chain.getArg(0) as? Context)?.let { systemContext = it }
                            hookSettingsProviderClass(provider.javaClass)
                        }
                        result
                    }
                }
        }.onFailure {
            attachWatcherHooked.set(false)
            log(Log.ERROR, TAG, "Failed to hook ContentProvider.attachInfo", it)
        }
    }

    private fun installSettingsBridge(classLoader: ClassLoader) {
        runCatching {
            val spClass = classLoader.loadClass("com.android.providers.settings.SettingsProvider")
            hookSettingsProviderClass(spClass)
        }
    }

    private fun isBridgeCallerAllowed(ctx: Context?): Boolean {
        val callingUid = Binder.getCallingUid()
        if (callingUid == 1000 || callingUid == 0) return true
        val allowedUid = runCatching {
            ctx?.packageManager?.getPackageUid(BuildConfig.APPLICATION_ID, 0)
        }.getOrNull() ?: return false
        return MultiUserPackageHelper.appIdOfUid(callingUid) == MultiUserPackageHelper.appIdOfUid(allowedUid)
    }

    private fun hookSettingsProviderClass(spClass: Class<*>) {
        if (!settingsBridgeHooked.compareAndSet(false, true)) return
        val ok = runCatching {
            val callMethod = spClass.getDeclaredMethod(
                "call",
                String::class.java,
                String::class.java,
                Bundle::class.java,
            )
            hook(callMethod).intercept { chain ->
                if (systemContext == null) {
                    (chain.thisObject as? ContentProvider)?.context?.let { systemContext = it }
                }
                val method = chain.getArg(0) as? String
                if (method == RulesCodec.BRIDGE_METHOD ||
                    method == RulesCodec.BRIDGE_RUNTIME_METHOD ||
                    method == RulesCodec.BRIDGE_APPS_METHOD ||
                    method == RulesCodec.BRIDGE_PROCESSES_METHOD ||
                    method == RulesCodec.BRIDGE_SET_SUSPEND_METHOD ||
                    method == RulesCodec.BRIDGE_LAUNCH_APP_METHOD
                ) {
                    val ctx = resolveSystemContext() ?: (chain.thisObject as? ContentProvider)?.context
                    if (isBridgeCallerAllowed(ctx)) {
                        val arg = chain.getArg(1) as? String
                        val extras = chain.getArg(2) as? Bundle
                        val token = Binder.clearCallingIdentity()
                        return@intercept try {
                            when (method) {
                                RulesCodec.BRIDGE_METHOD -> buildSnapshotWithDiagnostics(ctx)
                                RulesCodec.BRIDGE_RUNTIME_METHOD -> handleRuntimeQuery(ctx, arg, extras)
                                RulesCodec.BRIDGE_APPS_METHOD -> if (ctx != null) {
                                    MultiUserPackageHelper.buildAppsBundle(ctx)
                                } else {
                                    Bundle().apply { putBoolean("ok", false) }
                                }
                                RulesCodec.BRIDGE_PROCESSES_METHOD -> if (ctx != null) {
                                    SystemTelemetryHelper.buildProcessesBundle(ctx, amsInstance)
                                } else {
                                    Bundle().apply { putBoolean("ok", false) }
                                }
                                RulesCodec.BRIDGE_SET_SUSPEND_METHOD -> handleSetSuspend(extras)
                                RulesCodec.BRIDGE_LAUNCH_APP_METHOD -> handleLaunchApp(ctx, extras)
                                else -> null
                            }
                        } finally {
                            Binder.restoreCallingIdentity(token)
                        }
                    }
                }
                chain.proceed()
            }
            true
        }.onFailure {
            settingsBridgeHooked.set(false)
            recordDiagnostic(HookDiagnosticEntry("bridge", "SettingsProvider#call", true, HookPointStatus.ERROR, it.message.orEmpty()))
            log(Log.ERROR, TAG, "Failed to hook SettingsProvider.call", it)
        }.getOrDefault(false)
        if (ok) {
            hookStatus["bridge"] = true
            recordDiagnostic(HookDiagnosticEntry("bridge", "SettingsProvider#call", true, HookPointStatus.HOOKED))
            log(Log.INFO, TAG, "SettingsProvider bridge ready")
        }
    }

    private fun buildSnapshotWithDiagnostics(ctx: Context?): Bundle {
        val bundle = SystemListParser.buildSnapshotBundle(
            hookStatus = hookStatus,
            systemClassLoader = systemServerClassLoader,
            systemContext = ctx,
        )
        bundle.putStringArrayList(
            "hook_diagnostics",
            ArrayList(diagnostics.snapshot().map { it.encode() }),
        )
        bundle.putString("device_summary", buildDeviceSummary())
        return bundle
    }

    private fun buildDeviceSummary(): String {
        val oplusRom = readSystemProp("ro.build.version.oplusrom")
        val display = Build.DISPLAY.orEmpty()
        return buildString {
            append("${Build.MODEL} | Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            if (oplusRom.isNotBlank()) append(" | ColorOS $oplusRom")
            if (display.isNotBlank()) append(" | $display")
        }
    }

    private fun readSystemProp(key: String): String = runCatching {
        val sp = Class.forName("android.os.SystemProperties")
        sp.getMethod("get", String::class.java, String::class.java).invoke(null, key, "") as? String ?: ""
    }.getOrDefault("")

    override fun registerScreenOffReceiver() {
        val ctx = resolveSystemContext() ?: return
        if (!screenReceiverRegistered.compareAndSet(false, true)) return
        runCatching {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                        val delayMs = globalFlags.autoFreezeDelaySec * 1000L
                        for (target in autoFreezePkgs) {
                            scheduleAutoFreezeCheck(target.packageName, target.userId, delayMs)
                        }
                    }
                }
            }
            ctx.registerReceiver(receiver, IntentFilter(Intent.ACTION_SCREEN_OFF), null, workerHandler)
        }.onFailure {
            screenReceiverRegistered.set(false)
            log(Log.ERROR, TAG, "Failed to register SCREEN_OFF receiver", it)
        }
    }

    override fun scheduleAutoFreezeCheck(pkg: String, userId: Int, delayMs: Long) {
        if (isProtectedFromFreeze(pkg)) return
        val key = AppUserKey(pkg, userId)
        pendingFreezeRunnables.remove(key)?.let { workerHandler.removeCallbacks(it) }
        val r = Runnable {
            pendingFreezeRunnables.remove(key)
            if (key !in autoFreezePkgs) return@Runnable
            val ctx = resolveSystemContext() ?: return@Runnable
            if (MultiUserPackageHelper.isPackageSuspended(ctx, pkg, userId)) return@Runnable
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return@Runnable
            val activeFgOrSvc = runCatching {
                am.runningAppProcesses.orEmpty().any { proc ->
                    MultiUserPackageHelper.userIdOfUid(proc.uid) == userId &&
                        (proc.pkgList?.contains(pkg) == true || proc.processName.substringBefore(':') == pkg) &&
                        proc.importance < ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED
                }
            }.getOrDefault(false)
            if (!activeFgOrSvc) {
                if (MultiUserPackageHelper.setPackageSuspended(pkg, userId, true)) {
                    recordEvent(pkg, BgSubsystem.HANS_FREEZE, "auto_suspend", "no-bg", userId)
                    notifyShortcutStateChanged(ctx, pkg, userId, true)
                }
            }
        }
        pendingFreezeRunnables[key] = r
        if (delayMs <= 0L) {
            workerHandler.post(r)
        } else {
            workerHandler.postDelayed(r, delayMs)
        }
    }

    override fun cancelPendingAutoFreeze(pkg: String, userId: Int) {
        pendingFreezeRunnables.remove(AppUserKey(pkg, userId))?.let { workerHandler.removeCallbacks(it) }
    }

    private fun isProtectedFromFreeze(pkg: String): Boolean =
        pkg == BuildConfig.APPLICATION_ID ||
            pkg == "android" ||
            pkg == "com.android.systemui" ||
            pkg == "com.android.launcher" ||
            pkg == "com.android.settings" ||
            pkg == "com.android.phone"

    private fun handleSetSuspend(extras: Bundle?): Bundle {
        val pkg = extras?.getString("pkg")?.trim().orEmpty()
        val userId = extras?.getInt("user_id", 0) ?: 0
        val suspend = extras?.getBoolean("suspend", true) ?: true
        if (pkg.isEmpty() || (suspend && isProtectedFromFreeze(pkg))) {
            return Bundle().apply { putBoolean("ok", false) }
        }
        val key = AppUserKey(pkg, userId)
        if (!suspend) {
            pendingFreezeRunnables.remove(key)?.let { workerHandler.removeCallbacks(it) }
        }
        val ok = MultiUserPackageHelper.setPackageSuspended(pkg, userId, suspend)
        if (ok) {
            recordEvent(
                pkg,
                BgSubsystem.HANS_FREEZE,
                if (suspend) "manual_suspend" else "manual_unsuspend",
                if (suspend) "pm-suspend" else "pm-unsuspend",
                userId,
            )
            notifyShortcutStateChanged(resolveSystemContext(), pkg, userId, suspend)
        }
        return Bundle().apply { putBoolean("ok", ok) }
    }

    private fun handleLaunchApp(ctx: Context?, extras: Bundle?): Bundle {
        val pkg = extras?.getString("pkg")?.trim().orEmpty()
        val userId = extras?.getInt("user_id", 0) ?: 0
        if (pkg.isEmpty() || ctx == null) {
            return Bundle().apply { putBoolean("ok", false) }
        }
        val key = AppUserKey(pkg, userId)
        pendingFreezeRunnables.remove(key)?.let { workerHandler.removeCallbacks(it) }
        val ok = MultiUserPackageHelper.launchPackageAsUser(ctx, pkg, userId)
        if (ok) {
            recordEvent(pkg, BgSubsystem.HANS_FREEZE, "unsuspend_launch", "launcher", userId)
            notifyShortcutStateChanged(ctx, pkg, userId, false)
        }
        return Bundle().apply { putBoolean("ok", ok) }
    }

    override fun notifyShortcutStateChanged(ctx: Context?, pkg: String, userId: Int, suspended: Boolean) {
        // Never sendBroadcastAsUser synchronously from hooks holding WindowManagerGlobalLock (deadlocks with AMS).
        workerHandler.post {
            val targetCtx = ctx ?: resolveSystemContext() ?: return@post
            runCatching {
                val intent = Intent(RulesCodec.ACTION_SYNC_SHORTCUT).apply {
                    setClassName(BuildConfig.APPLICATION_ID, "${BuildConfig.APPLICATION_ID}.ShortcutSyncReceiver")
                    putExtra("pkg", pkg)
                    putExtra("user_id", userId)
                    putExtra("suspended", suspended)
                    addFlags(Intent.FLAG_RECEIVER_FOREGROUND or Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                }
                targetCtx.sendBroadcastAsUser(intent, UserHandle.getUserHandleForUid(0))
            }
        }
    }

    override fun scheduleBatteryDozeSync() {
        workerHandler.removeCallbacks(syncDozeRunnable)
        workerHandler.postDelayed(syncDozeRunnable, 350L)
    }

    private fun syncBatteryDozeState() {
        val dic = deviceIdleController ?: return
        val updateMethod = SystemObjectAdapters.findDeclaredMethod(dic.javaClass, "updateWhitelistAppIdsLocked") ?: return
        val reportMethod = SystemObjectAdapters.findDeclaredMethod(dic.javaClass, "reportPowerSaveWhitelistChangedLocked")
        val token = Binder.clearCallingIdentity()
        try {
            synchronized(dic) {
                runCatching { updateMethod.invoke(dic) }
                    .onFailure { log(Log.ERROR, TAG, "sync updateWhitelistAppIdsLocked failed", it) }
                runCatching { reportMethod?.invoke(dic) }
            }
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    private fun collectDozeOverrideSets(): Pair<Set<String>, Set<String>> {
        val allowPkgs = LinkedHashSet<String>()
        val restrictPkgs = LinkedHashSet<String>()
        for ((target, map) in rules) {
            when (map[BgSubsystem.BATTERY_DOZE]) {
                PolicyMode.FORCE_ALLOW -> allowPkgs.add(target.packageName)
                PolicyMode.FORCE_RESTRICT,
                PolicyMode.FORCE_RESTRICT_HBG -> restrictPkgs.add(target.packageName)
                else -> {}
            }
        }
        return allowPkgs to restrictPkgs
    }

    override fun adjustDozeAppIds(orig: IntArray?): IntArray {
        val (allowPkgs, restrictPkgs) = collectDozeOverrideSets()
        if (allowPkgs.isEmpty() && restrictPkgs.isEmpty()) {
            return orig ?: IntArray(0)
        }
        val allowIds = allowPkgs.mapNotNullTo(LinkedHashSet()) { pkg ->
            resolveAppIdForPkg(pkg).takeIf { it >= 10000 }
        }
        val restrictIds = restrictPkgs.mapNotNullTo(LinkedHashSet()) { pkg ->
            resolveAppIdForPkg(pkg).takeIf { it >= 10000 }
        }
        return DozePolicyHelper.adjustWhitelistAppIds(orig, allowIds, restrictIds)
    }

    override fun adjustDozePackages(orig: Array<*>?): Array<String> {
        val (allowPkgs, restrictPkgs) = collectDozeOverrideSets()
        val installedAllow = allowPkgs.filterTo(LinkedHashSet()) { resolveAppIdForPkg(it) >= 10000 }
        return DozePolicyHelper.adjustWhitelistPackages(orig, installedAllow, restrictPkgs)
    }

    override fun resolveAppIdForPkg(pkg: String): Int {
        pkgAppIdCache[pkg]?.let { return it }
        val ctx = resolveSystemContext() ?: return -1
        val pm = ctx.packageManager ?: return -1
        val uid = runCatching { pm.getPackageUid(pkg, 0) }.getOrNull()
            ?: runCatching { pm.getPackageUid(pkg, MATCH_ANY_USER_FLAG) }.getOrNull()
            ?: return -1
        val appId = MultiUserPackageHelper.appIdOfUid(uid)
        if (appId >= 10000) {
            pkgAppIdCache[pkg] = appId
        }
        return appId
    }

    override fun resolveDozeModeForAppId(appId: Int): PolicyMode {
        if (appId < 10000) return PolicyMode.FOLLOW_SYSTEM
        var hasRestrict = false
        for ((key, map) in rules) {
            val mode = map[BgSubsystem.BATTERY_DOZE] ?: continue
            if (resolveAppIdForPkg(key.packageName) == appId) {
                if (mode == PolicyMode.FORCE_ALLOW) return PolicyMode.FORCE_ALLOW
                if (mode.isRestrict) hasRestrict = true
            }
        }
        return if (hasRestrict) PolicyMode.FORCE_RESTRICT else PolicyMode.FOLLOW_SYSTEM
    }

    override fun forceStopPackageDirect(pkg: String, userId: Int): Boolean {
        val token = Binder.clearCallingIdentity()
        return try {
            val ams = amsInstance
            if (ams != null) {
                val ok = runCatching {
                    val m = ams.javaClass.getMethod("forceStopPackage", String::class.java, Int::class.javaPrimitiveType)
                    m.invoke(ams, pkg, userId)
                    true
                }.getOrDefault(false)
                if (ok) return true
            }
            if (userId == 0) {
                val am = resolveSystemContext()?.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                if (am != null) {
                    return runCatching {
                        val m = am.javaClass.getMethod("forceStopPackage", String::class.java)
                        m.invoke(am, pkg)
                        true
                    }.getOrDefault(false)
                }
            }
            false
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    private fun handleRuntimeQuery(ctx: Context?, pkgName: String?, extras: Bundle?): Bundle {
        val targetPkg = pkgName?.trim().takeIf { !it.isNullOrEmpty() }
        val targetUserId = extras?.getInt("user_id", 0) ?: 0
        val events = synchronized(eventLock) {
            val seq = if (targetPkg != null) {
                eventBuffer.asSequence().filter { it.pkgName == targetPkg && it.userId == targetUserId }
            } else {
                eventBuffer.asSequence()
            }
            ArrayList(seq.toList().asReversed().take(80).map { it.encode() })
        }

        val out = Bundle()
        if (targetPkg != null && ctx != null) {
            val status = buildPkgRuntimeStatus(ctx, targetPkg, targetUserId)
            out.putAll(status.toBundle())
        } else {
            out.putBoolean("ok", true)
        }
        out.putStringArrayList("events", events)
        return out
    }

    private fun buildPkgRuntimeStatus(ctx: Context, pkg: String, userId: Int): PkgRuntimeStatus {
        val uid = runCatching {
            val m = ctx.packageManager.javaClass.getMethod("getPackageUidAsUser", String::class.java, Int::class.javaPrimitiveType)
            m.invoke(ctx.packageManager, pkg, userId) as? Int
        }.getOrNull() ?: if (userId == 0) {
            runCatching { ctx.packageManager.getPackageUid(pkg, 0) }.getOrNull() ?: -1
        } else {
            -1
        }

        val standbyBucket = runCatching {
            val asc = appStandbyController
            if (asc != null) {
                val m = asc.javaClass.getDeclaredMethod(
                    "getAppStandbyBucket",
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    Long::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                ).apply { isAccessible = true }
                m.invoke(asc, pkg, userId, android.os.SystemClock.elapsedRealtime(), false) as? Int
            } else {
                val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                usm?.javaClass?.getMethod("getAppStandbyBucket", String::class.java)?.invoke(usm, pkg) as? Int
            }
        }.getOrNull() ?: -1

        val dic = deviceIdleController
        val realDozeWhitelisted = when (modeForFallback(pkg, BgSubsystem.BATTERY_DOZE)) {
            PolicyMode.FORCE_ALLOW -> true
            PolicyMode.FORCE_RESTRICT,
            PolicyMode.FORCE_RESTRICT_HBG -> false
            PolicyMode.FOLLOW_SYSTEM -> if (dic != null) {
                BatteryDozeHooks.isDeviceDozeWhitelisted(dic, pkg)
            } else {
                runCatching {
                    val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
                    pm?.isIgnoringBatteryOptimizations(pkg) == true
                }.getOrDefault(false)
            }
        }

        val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
        val runAnyInBgAllowed = if (uid >= 0 && appOps != null) {
            runCatching {
                val m = appOps.javaClass.getMethod(
                    "unsafeCheckOpNoThrow",
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    String::class.java,
                )
                (m.invoke(appOps, "android:run_any_in_background", uid, pkg) as? Int) == AppOpsManager.MODE_ALLOWED
            }.getOrDefault(true)
        } else {
            true
        }

        val suspended = MultiUserPackageHelper.isPackageSuspended(ctx, pkg, userId)
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val runningProcs = runCatching {
            am?.runningAppProcesses
                ?.filter {
                    MultiUserPackageHelper.userIdOfUid(it.uid) == userId &&
                        (it.pkgList?.contains(pkg) == true || it.processName.substringBefore(':') == pkg)
                }
                ?.map { "${it.processName} (pid=${it.pid})" }
                .orEmpty()
        }.getOrDefault(emptyList())

        val activeServices = SystemTelemetryHelper.collectAppServices(amsInstance, am, pkg, userId)

        return PkgRuntimeStatus(
            packageName = pkg,
            userId = userId,
            available = true,
            standbyBucket = standbyBucket,
            dozeWhitelisted = realDozeWhitelisted,
            runAnyInBgAllowed = runAnyInBgAllowed,
            isSuspended = suspended,
            runningProcesses = runningProcs,
            activeServices = activeServices,
        )
    }

    override fun recordEvent(pkg: String?, sub: BgSubsystem, action: String, detail: String, userId: Int) {
        if (pkg.isNullOrBlank()) return
        val now = System.currentTimeMillis()
        val key = "$userId|$pkg|${sub.key}|$action"
        val prev = eventCooldown[key]
        if (prev != null && now - prev < EVENT_COOLDOWN_MS) return
        eventCooldown[key] = now
        val ev = RuntimeEvent(
            timestampMs = now,
            pkgName = pkg,
            subsystemKey = sub.key,
            action = action,
            detail = detail,
            userId = userId,
        )
        synchronized(eventLock) {
            while (eventBuffer.size >= MAX_EVENTS) {
                eventBuffer.removeFirst()
            }
            eventBuffer.addLast(ev)
        }
        if (globalFlags.verboseLog) {
            log(Log.INFO, TAG, "Event: u$userId/$pkg [${sub.key}] $action $detail")
        }
    }

    override fun logMsg(priority: Int, msg: String, tr: Throwable?) {
        val shouldLog = globalFlags.verboseLog || priority >= Log.ERROR
        if (!shouldLog) return
        if (tr != null) {
            log(priority, TAG, msg, tr)
        } else {
            log(priority, TAG, msg)
        }
    }

    companion object {
        private const val TAG = "ColorOSBgManager"
        private const val MAX_EVENTS = 200
        private const val EVENT_COOLDOWN_MS = 3000L
        private const val MATCH_ANY_USER_FLAG = 0x00400000
    }
}
