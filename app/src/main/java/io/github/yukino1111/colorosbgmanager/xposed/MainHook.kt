package io.github.yukino1111.colorosbgmanager.xposed

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.Application
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentProvider
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.util.Log
import io.github.yukino1111.colorosbgmanager.BuildConfig
import io.github.yukino1111.colorosbgmanager.model.AppUserKey
import io.github.yukino1111.colorosbgmanager.model.BgSubsystem
import io.github.yukino1111.colorosbgmanager.model.GlobalRuleFlags
import io.github.yukino1111.colorosbgmanager.model.PkgRuntimeStatus
import io.github.yukino1111.colorosbgmanager.model.PolicyMode
import io.github.yukino1111.colorosbgmanager.model.RulesCodec
import io.github.yukino1111.colorosbgmanager.model.RuntimeEvent
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class MainHook : XposedModule() {

    @Volatile
    private var rules: Map<AppUserKey, Map<BgSubsystem, PolicyMode>> = emptyMap()

    @Volatile
    private var autoFreezePkgs: Set<AppUserKey> = emptySet()

    @Volatile
    private var globalFlags: GlobalRuleFlags = GlobalRuleFlags()

    @Volatile
    private var systemContext: Context? = null

    @Volatile
    private var systemServerClassLoader: ClassLoader? = null

    @Volatile
    private var amsInstance: Any? = null

    @Volatile
    private var deviceIdleController: Any? = null

    @Volatile
    private var appStandbyController: Any? = null

    @Volatile
    private var bootCompleted: Boolean = false

    private var remotePrefs: SharedPreferences? = null
    private val hooked = AtomicBoolean(false)
    private val settingsBridgeHooked = AtomicBoolean(false)
    private val attachWatcherHooked = AtomicBoolean(false)
    private val screenReceiverRegistered = AtomicBoolean(false)
    private val hookStatus = ConcurrentHashMap<String, Boolean>()
    private val uidPkgCache = ConcurrentHashMap<Int, List<String>>()

    private val eventLock = Any()
    private val eventBuffer = ArrayDeque<RuntimeEvent>(MAX_EVENTS)
    private val eventCooldown = ConcurrentHashMap<String, Long>()
    private val removeTaskDepth = ThreadLocal.withInitial { 0 }
    private val pendingFreezeRunnables = ConcurrentHashMap<AppUserKey, Runnable>()

    private val workerHandler: Handler by lazy {
        val thread = HandlerThread("BgManagerWorker").apply { start() }
        Handler(thread.looper)
    }

    private val syncDozeRunnable = Runnable { syncBatteryDozeState() }

    private var getHansPkgNameMethod: Method? = null
    private var getHansUidMethod: Method? = null
    private var processRecordAppInfoMethod: Method? = null
    private var processRecordHostingMethod: Method? = null
    private var hostingGetTypeMethod: Method? = null
    private var processInfoGetPkgMethod: Method? = null
    private var processInfoGetUidMethod: Method? = null
    private var serviceRecordPkgField: Field? = null
    private var serviceRecordUserField: Field? = null
    private var serviceRecordAppInfoField: Field? = null
    private var broadcastFilterPkgField: Field? = null
    private var broadcastFilterUidField: Field? = null
    private var broadcastRecordUserField: Field? = null
    private var osenseProcInfoAppInfoField: Field? = null
    private var osenseProcInfoAppField: Field? = null
    private var osenseProcInfoGroupField: Field? = null
    private var osenseProcInfoProcNameField: Field? = null
    private var osenseProcInfoUidField: Field? = null
    private var osenseProcInfoSkipField: Field? = null
    private var osenseGroupPkgField: Field? = null
    private var osenseGroupUidField: Field? = null
    private var osenseGroupSkipField: Field? = null
    private var taskGetBasePkgMethod: Method? = null
    private var taskRealActivityField: Field? = null
    private var taskUserIdField: Field? = null

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
        globalFlags = RulesCodec.readGlobalFlags(prefs)
        logMsg(Log.INFO, "Rules refreshed: ${rules.size} targets, freeze=${autoFreezePkgs.size}, globals=$globalFlags")
        if (bootCompleted) {
            scheduleBatteryDozeSync()
        }
    }

    private fun modeFor(pkg: String?, userId: Int, sub: BgSubsystem): PolicyMode {
        if (pkg.isNullOrEmpty()) return PolicyMode.FOLLOW_SYSTEM
        return rules[AppUserKey(pkg, userId)]?.get(sub) ?: PolicyMode.FOLLOW_SYSTEM
    }

    private fun modeForFallback(pkg: String?, sub: BgSubsystem): PolicyMode {
        if (pkg.isNullOrEmpty()) return PolicyMode.FOLLOW_SYSTEM
        rules[AppUserKey(pkg, 0)]?.get(sub)?.let { return it }
        for ((key, map) in rules) {
            if (key.packageName == pkg) {
                map[sub]?.let { return it }
            }
        }
        return PolicyMode.FOLLOW_SYSTEM
    }

    private fun uidHasMode(uid: Int, sub: BgSubsystem, target: PolicyMode): Boolean {
        if (MultiUserPackageHelper.appIdOfUid(uid) < 10000) return false
        val userId = MultiUserPackageHelper.userIdOfUid(uid)
        val pkgs = resolvePackagesForUid(uid)
        return pkgs.any { modeFor(it, userId, sub) == target }
    }

    private fun resolveSystemContext(): Context? {
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
        installSystemLifecycleHooks(classLoader)
        hookStatus[BgSubsystem.HANS_FREEZE.key] = installHansHooks(classLoader)
        hookStatus[BgSubsystem.AUTO_START.key] = installAutoStartHooks(classLoader)
        hookStatus[BgSubsystem.ASSOCIATE_START.key] = installAssociateStartHooks(classLoader)
        hookStatus[BgSubsystem.BATTERY_DOZE.key] = installBatteryDozeHooks(classLoader)
        hookStatus[BgSubsystem.ATHENA_RECENT.key] = installAthenaHooks(classLoader)
        hookStatus[BgSubsystem.CPU_LIMIT.key] = installCpuLimitHooks(classLoader)
        hookStatus[BgSubsystem.DOZE_NET.key] = installDozeNetHooks(classLoader)
        hookStatus[BgSubsystem.BROADCAST_RESTRICT.key] = installBroadcastRestrictHooks(classLoader)
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
                                RulesCodec.BRIDGE_METHOD -> SystemListParser.buildSnapshotBundle(
                                    hookStatus = hookStatus,
                                    systemClassLoader = systemServerClassLoader,
                                    systemContext = ctx,
                                )
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
            log(Log.ERROR, TAG, "Failed to hook SettingsProvider.call", it)
        }.getOrDefault(false)
        if (ok) {
            hookStatus["bridge"] = true
            log(Log.INFO, TAG, "SettingsProvider bridge ready")
        }
    }

    private fun installHansHooks(cl: ClassLoader): Boolean {
        var anyOk = false
        val hansPkgClass = runCatching { cl.loadClass("com.android.server.hans.OplusHansPackage") }.getOrNull()
            ?: return false

        runCatching {
            val impClass = cl.loadClass("com.android.server.hans.OplusHansImportance")
            listOf(
                impClass.getDeclaredMethod("isImportantPkg", hansPkgClass, Int::class.javaPrimitiveType),
                impClass.getDeclaredMethod("isImportantPkg", hansPkgClass, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType),
                impClass.getDeclaredMethod("isImportantPkg", hansPkgClass, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, String::class.java),
            ).forEach { m ->
                hook(m).intercept { chain ->
                    val hansPkg = chain.getArg(0)
                    val pkg = extractHansPkgName(hansPkg)
                    val userId = extractHansUserId(hansPkg)
                    if (modeFor(pkg, userId, BgSubsystem.HANS_FREEZE) == PolicyMode.FORCE_ALLOW) {
                        return@intercept true
                    }
                    chain.proceed()
                }
            }
            anyOk = true
        }.onFailure { log(Log.ERROR, TAG, "Hook OplusHansImportance failed", it) }

        runCatching {
            val cgroupClass = cl.loadClass("com.android.server.hans.freeze.HansCGroup")
            val freezeMethod = cgroupClass.getDeclaredMethod("hansFreezeLocked", hansPkgClass, String::class.java)
            hook(freezeMethod).intercept { chain ->
                val hansPkg = chain.getArg(0)
                val pkg = extractHansPkgName(hansPkg)
                val userId = extractHansUserId(hansPkg)
                if (modeFor(pkg, userId, BgSubsystem.HANS_FREEZE) == PolicyMode.FORCE_ALLOW) {
                    return@intercept false
                }
                chain.proceed()
            }
            anyOk = true
        }.onFailure { log(Log.ERROR, TAG, "Hook HansCGroup failed", it) }

        runCatching {
            val restrictClass = cl.loadClass("com.android.server.hans.OplusHansRestriction")
            val strictMethod = restrictClass.getDeclaredMethod(
                "isBlockedForStrictMode",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java,
                Int::class.javaPrimitiveType,
                String::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
            )
            hook(strictMethod).intercept { chain ->
                val uid = chain.getArg(0) as Int
                val pkg = chain.getArg(4) as? String
                val userId = MultiUserPackageHelper.userIdOfUid(uid)
                if (globalFlags.disableStrictModeFreeze ||
                    modeFor(pkg, userId, BgSubsystem.HANS_FREEZE) == PolicyMode.FORCE_ALLOW
                ) {
                    return@intercept false
                }
                chain.proceed()
            }
            anyOk = true
        }.onFailure { log(Log.ERROR, TAG, "Hook OplusHansRestriction failed", it) }

        runCatching {
            val dbClass = cl.loadClass("com.android.server.hans.OplusHansDBConfig")
            hook(dbClass.getDeclaredMethod("isHansWhitelistApp", Int::class.javaPrimitiveType)).intercept { chain ->
                val uid = chain.getArg(0) as Int
                when {
                    uidHasMode(uid, BgSubsystem.HANS_FREEZE, PolicyMode.FORCE_ALLOW) -> true
                    uidHasMode(uid, BgSubsystem.HANS_FREEZE, PolicyMode.FORCE_RESTRICT) ||
                        uidHasMode(uid, BgSubsystem.HANS_FREEZE, PolicyMode.FORCE_RESTRICT_HBG) -> false
                    else -> chain.proceed()
                }
            }
            hook(dbClass.getDeclaredMethod("isProtectApp", String::class.java)).intercept { chain ->
                val pkg = chain.getArg(0) as? String
                when (modeForFallback(pkg, BgSubsystem.HANS_FREEZE)) {
                    PolicyMode.FORCE_ALLOW -> true
                    PolicyMode.FORCE_RESTRICT,
                    PolicyMode.FORCE_RESTRICT_HBG -> false
                    PolicyMode.FOLLOW_SYSTEM -> chain.proceed()
                }
            }
            hook(dbClass.getDeclaredMethod("isBlackHansApp", String::class.java)).intercept { chain ->
                val pkg = chain.getArg(0) as? String
                when (modeForFallback(pkg, BgSubsystem.HANS_FREEZE)) {
                    PolicyMode.FORCE_ALLOW -> false
                    PolicyMode.FORCE_RESTRICT,
                    PolicyMode.FORCE_RESTRICT_HBG -> true
                    PolicyMode.FOLLOW_SYSTEM -> chain.proceed()
                }
            }
            listOf(
                "inAndroidFreezeWhiteList",
                "inAndroidFreezeKillWhiteList",
            ).forEach { methodName ->
                runCatching {
                    hook(dbClass.getDeclaredMethod(methodName, String::class.java, String::class.java)).intercept { chain ->
                        val pkg = chain.getArg(0) as? String
                        when (modeForFallback(pkg, BgSubsystem.HANS_FREEZE)) {
                            PolicyMode.FORCE_ALLOW -> true
                            PolicyMode.FORCE_RESTRICT,
                            PolicyMode.FORCE_RESTRICT_HBG -> false
                            PolicyMode.FOLLOW_SYSTEM -> chain.proceed()
                        }
                    }
                }
            }
            anyOk = true
        }.onFailure { log(Log.ERROR, TAG, "Hook OplusHansDBConfig failed", it) }

        return anyOk
    }

    private fun installAutoStartHooks(cl: ClassLoader): Boolean {
        var anyOk = false
        runCatching {
            val cfgClass = cl.loadClass("com.android.server.am.OplusAppStartupConfig")
            listOf(
                cfgClass.getDeclaredMethod("isInAutoBootWhiteList", String::class.java, Int::class.javaPrimitiveType),
                cfgClass.getDeclaredMethod("isInCustomAutoBootWhiteList", String::class.java, Int::class.javaPrimitiveType),
            ).forEach { m ->
                hook(m).intercept { chain ->
                    val pkg = chain.getArg(0) as? String
                    val userId = chain.getArg(1) as Int
                    when (modeFor(pkg, userId, BgSubsystem.AUTO_START)) {
                        PolicyMode.FORCE_ALLOW -> true
                        PolicyMode.FORCE_RESTRICT,
                        PolicyMode.FORCE_RESTRICT_HBG -> false
                        PolicyMode.FOLLOW_SYSTEM -> chain.proceed()
                    }
                }
            }
            anyOk = true
        }.onFailure { log(Log.ERROR, TAG, "Hook AutoStart config failed", it) }

        runCatching {
            val mgrClass = cl.loadClass("com.android.server.am.OplusAppStartupManager")
            val amsClass = cl.loadClass("com.android.server.am.ActivityManagerService")
            val prClass = cl.loadClass("com.android.server.am.ProcessRecord")

            hook(mgrClass.getDeclaredMethod("shouldPreventStartProcess", amsClass, prClass)).intercept { chain ->
                if (amsInstance == null) amsInstance = chain.getArg(0)
                val pr = chain.getArg(1)
                val pkg = extractProcessRecordPkg(pr)
                val userId = extractProcessRecordUserId(pr)
                val hostingType = extractHostingType(pr)
                val isActivity = hostingType?.contains("activity", ignoreCase = true) == true
                if (!isActivity) {
                    val autoMode = modeFor(pkg, userId, BgSubsystem.AUTO_START)
                    val assocMode = modeFor(pkg, userId, BgSubsystem.ASSOCIATE_START)
                    if (autoMode.isRestrict || assocMode.isRestrict) {
                        val sub = if (autoMode.isRestrict) BgSubsystem.AUTO_START else BgSubsystem.ASSOCIATE_START
                        recordEvent(pkg, sub, "prevent_start_proc", hostingType ?: "bg", userId)
                        return@intercept true
                    }
                    if (autoMode == PolicyMode.FORCE_ALLOW) {
                        return@intercept false
                    }
                }
                chain.proceed()
            }

            hook(mgrClass.getDeclaredMethod("shouldRestrictApp", String::class.java, Int::class.javaPrimitiveType)).intercept { chain ->
                val pkg = chain.getArg(0) as? String
                val userId = chain.getArg(1) as Int
                when (modeFor(pkg, userId, BgSubsystem.AUTO_START)) {
                    PolicyMode.FORCE_ALLOW -> false
                    PolicyMode.FORCE_RESTRICT,
                    PolicyMode.FORCE_RESTRICT_HBG -> true
                    PolicyMode.FOLLOW_SYSTEM -> chain.proceed()
                }
            }
            anyOk = true
        }.onFailure { log(Log.ERROR, TAG, "Hook OplusAppStartupManager process hooks failed", it) }

        runCatching {
            val activeServicesClass = cl.loadClass("com.android.server.am.ActiveServices")
            val srClass = cl.loadClass("com.android.server.am.ServiceRecord")
            hook(activeServicesClass.getDeclaredMethod("scheduleServiceRestartLocked", srClass, Boolean::class.javaPrimitiveType)).intercept { chain ->
                val sr = chain.getArg(0)
                val pkg = extractServiceRecordPkg(sr)
                val userId = extractServiceRecordUserId(sr)
                val autoMode = modeFor(pkg, userId, BgSubsystem.AUTO_START)
                val assocMode = modeFor(pkg, userId, BgSubsystem.ASSOCIATE_START)
                if (autoMode.isRestrict || assocMode.isRestrict) {
                    val sub = if (autoMode.isRestrict) BgSubsystem.AUTO_START else BgSubsystem.ASSOCIATE_START
                    recordEvent(pkg, sub, "block_service_restart", "START_STICKY", userId)
                    return@intercept false
                }
                chain.proceed()
            }
            anyOk = true
        }.onFailure { log(Log.ERROR, TAG, "Hook ActiveServices scheduleServiceRestartLocked failed", it) }

        return anyOk
    }

    private fun installAssociateStartHooks(cl: ClassLoader): Boolean {
        var anyOk = false
        runCatching {
            val cfgClass = cl.loadClass("com.android.server.am.OplusAppStartupConfig")
            listOf(
                cfgClass.getDeclaredMethod("inAssociateStartWhiteList", String::class.java, Int::class.javaPrimitiveType),
                cfgClass.getDeclaredMethod("inCustomAssociateStartWhiteList", String::class.java, Int::class.javaPrimitiveType),
            ).forEach { m ->
                hook(m).intercept { chain ->
                    val pkg = chain.getArg(0) as? String
                    val userId = chain.getArg(1) as Int
                    when (modeFor(pkg, userId, BgSubsystem.ASSOCIATE_START)) {
                        PolicyMode.FORCE_ALLOW -> true
                        PolicyMode.FORCE_RESTRICT,
                        PolicyMode.FORCE_RESTRICT_HBG -> false
                        PolicyMode.FOLLOW_SYSTEM -> chain.proceed()
                    }
                }
            }
            anyOk = true
        }.onFailure { log(Log.ERROR, TAG, "Hook OplusAppStartupConfig associate failed", it) }

        runCatching {
            val mgrClass = cl.loadClass("com.android.server.am.OplusAppStartupManager")
            hook(mgrClass.getDeclaredMethod("inAssociateStartWhiteList", String::class.java, Int::class.javaPrimitiveType)).intercept { chain ->
                val pkg = chain.getArg(0) as? String
                val userId = chain.getArg(1) as Int
                when (modeFor(pkg, userId, BgSubsystem.ASSOCIATE_START)) {
                    PolicyMode.FORCE_ALLOW -> true
                    PolicyMode.FORCE_RESTRICT,
                    PolicyMode.FORCE_RESTRICT_HBG -> false
                    PolicyMode.FOLLOW_SYSTEM -> chain.proceed()
                }
            }

            val srClass = cl.loadClass("com.android.server.am.ServiceRecord")
            val prClass = cl.loadClass("com.android.server.am.ProcessRecord")
            val bqClass = cl.loadClass("com.android.server.am.BroadcastQueue")
            val brClass = cl.loadClass("com.android.server.am.BroadcastRecord")
            val bfClass = cl.loadClass("com.android.server.am.BroadcastFilter")
            val cpClass = cl.loadClass("com.android.server.am.ContentProviderRecord")
            val intentClass = android.content.Intent::class.java

            mgrClass.declaredMethods
                .filter { it.name == "shouldPreventStartService" }
                .forEach { method ->
                    hook(method).intercept { chain ->
                        val sr = chain.getArg(0)
                        val targetPkg = extractServiceRecordPkg(sr)
                        val userId = extractServiceRecordUserId(sr)
                        when (modeFor(targetPkg, userId, BgSubsystem.ASSOCIATE_START)) {
                            PolicyMode.FORCE_ALLOW -> false
                            PolicyMode.FORCE_RESTRICT,
                            PolicyMode.FORCE_RESTRICT_HBG -> {
                                recordEvent(targetPkg, BgSubsystem.ASSOCIATE_START, "prevent_service", "startService", userId)
                                true
                            }
                            PolicyMode.FOLLOW_SYSTEM -> chain.proceed()
                        }
                    }
                }

            hook(
                mgrClass.getDeclaredMethod(
                    "isPreventFromBindService",
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    srClass,
                    intentClass,
                    Int::class.javaPrimitiveType,
                )
            ).intercept { chain ->
                val sr = chain.getArg(2)
                val targetPkg = extractServiceRecordPkg(sr)
                val userId = extractServiceRecordUserId(sr)
                when (modeFor(targetPkg, userId, BgSubsystem.ASSOCIATE_START)) {
                    PolicyMode.FORCE_ALLOW -> false
                    PolicyMode.FORCE_RESTRICT,
                    PolicyMode.FORCE_RESTRICT_HBG -> {
                        recordEvent(targetPkg, BgSubsystem.ASSOCIATE_START, "prevent_bind", chain.getArg(0) as? String ?: "", userId)
                        true
                    }
                    PolicyMode.FOLLOW_SYSTEM -> chain.proceed()
                }
            }

            hook(
                mgrClass.getDeclaredMethod(
                    "shouldPreventSendReceiver",
                    bqClass,
                    brClass,
                    ResolveInfo::class.java,
                    bfClass,
                    String::class.java,
                )
            ).intercept { chain ->
                val br = chain.getArg(1)
                val ri = chain.getArg(2) as? ResolveInfo
                val bf = chain.getArg(3)
                val resolvePkg = ri?.activityInfo?.packageName
                val filterPkg = extractBroadcastFilterPkg(bf)
                val argPkg = chain.getArg(4) as? String
                val targetPkg = resolvePkg ?: filterPkg ?: argPkg
                val userId = extractReceiverUserId(ri, bf, br)
                when (modeFor(targetPkg, userId, BgSubsystem.ASSOCIATE_START)) {
                    PolicyMode.FORCE_ALLOW -> false
                    PolicyMode.FORCE_RESTRICT,
                    PolicyMode.FORCE_RESTRICT_HBG -> {
                        recordEvent(targetPkg, BgSubsystem.ASSOCIATE_START, "prevent_receiver", "broadcast", userId)
                        true
                    }
                    PolicyMode.FOLLOW_SYSTEM -> chain.proceed()
                }
            }

            hook(
                mgrClass.getDeclaredMethod(
                    "shouldPreventStartProvider",
                    prClass,
                    cpClass,
                    ApplicationInfo::class.java,
                    String::class.java,
                    Int::class.javaPrimitiveType,
                )
            ).intercept { chain ->
                val appInfo = chain.getArg(2) as? ApplicationInfo
                val targetPkg = appInfo?.packageName ?: (chain.getArg(3) as? String)
                val userId = appInfo?.let { MultiUserPackageHelper.userIdOfUid(it.uid) } ?: (chain.getArg(4) as Int)
                when (modeFor(targetPkg, userId, BgSubsystem.ASSOCIATE_START)) {
                    PolicyMode.FORCE_ALLOW -> false
                    PolicyMode.FORCE_RESTRICT,
                    PolicyMode.FORCE_RESTRICT_HBG -> {
                        recordEvent(targetPkg, BgSubsystem.ASSOCIATE_START, "prevent_provider", chain.getArg(3) as? String ?: "", userId)
                        true
                    }
                    PolicyMode.FOLLOW_SYSTEM -> chain.proceed()
                }
            }
            anyOk = true
        }.onFailure { log(Log.ERROR, TAG, "Hook OplusAppStartupManager prevent methods failed", it) }

        return anyOk
    }

    private fun installBatteryDozeHooks(cl: ClassLoader): Boolean {
        var anyOk = false
        runCatching {
            val policyClass = cl.loadClass("com.android.server.hans.oguard.policy.AppPowerPolicy")
            listOf(
                policyClass.getDeclaredMethod("tryKillApp", Int::class.javaPrimitiveType, String::class.java, String::class.java),
                policyClass.getDeclaredMethod("tryForceStopApp", Int::class.javaPrimitiveType, String::class.java, String::class.java),
                policyClass.getDeclaredMethod("tryRemindApp", Int::class.javaPrimitiveType, String::class.java, String::class.java),
            ).forEach { m ->
                hook(m).intercept { chain ->
                    val uid = chain.getArg(0) as Int
                    val userId = MultiUserPackageHelper.userIdOfUid(uid)
                    val pkg = chain.getArg(1) as? String
                    if (modeFor(pkg, userId, BgSubsystem.BATTERY_DOZE) == PolicyMode.FORCE_ALLOW) {
                        recordEvent(pkg, BgSubsystem.BATTERY_DOZE, "skip_oguard", m.name, userId)
                        return@intercept false
                    }
                    chain.proceed()
                }
            }
            hook(
                policyClass.getDeclaredMethod(
                    "requestKillApp",
                    String::class.java,
                    String::class.java,
                    String::class.java,
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    String::class.java,
                )
            ).intercept { chain ->
                val pkg = chain.getArg(0) as? String
                if (modeForFallback(pkg, BgSubsystem.BATTERY_DOZE) == PolicyMode.FORCE_ALLOW) {
                    recordEvent(pkg, BgSubsystem.BATTERY_DOZE, "skip_oguard", "requestKillApp")
                    return@intercept false
                }
                chain.proceed()
            }
            anyOk = true
        }.onFailure { log(Log.ERROR, TAG, "Hook AppPowerPolicy failed", it) }

        runCatching {
            val dbClass = cl.loadClass("com.android.server.hans.OplusHansDBConfig")
            hook(dbClass.getDeclaredMethod("isActiveUpdateDozeWhiteList", String::class.java)).intercept { chain ->
                val pkg = chain.getArg(0) as? String
                when (modeForFallback(pkg, BgSubsystem.BATTERY_DOZE)) {
                    PolicyMode.FORCE_ALLOW -> true
                    PolicyMode.FORCE_RESTRICT,
                    PolicyMode.FORCE_RESTRICT_HBG -> false
                    PolicyMode.FOLLOW_SYSTEM -> chain.proceed()
                }
            }
            anyOk = true
        }.onFailure { log(Log.ERROR, TAG, "Hook Doze whitelist in OplusHansDBConfig failed", it) }

        runCatching {
            val idleClass = cl.loadClass("com.android.server.DeviceIdleController")
            hook(idleClass.getDeclaredMethod("onBootPhase", Int::class.javaPrimitiveType)).intercept { chain ->
                deviceIdleController = chain.thisObject
                val result = chain.proceed()
                val phase = chain.getArg(0) as Int
                if (phase >= 1000) {
                    bootCompleted = true
                    scheduleBatteryDozeSync()
                    registerScreenOffReceiver()
                }
                result
            }
            listOf(
                idleClass.getDeclaredMethod("isPowerSaveWhitelistAppInternal", String::class.java),
                idleClass.getDeclaredMethod("isPowerSaveWhitelistExceptIdleAppInternal", String::class.java),
            ).forEach { m ->
                hook(m).intercept { chain ->
                    if (deviceIdleController == null) deviceIdleController = chain.thisObject
                    val pkg = chain.getArg(0) as? String
                    when (modeForFallback(pkg, BgSubsystem.BATTERY_DOZE)) {
                        PolicyMode.FORCE_ALLOW -> true
                        PolicyMode.FORCE_RESTRICT,
                        PolicyMode.FORCE_RESTRICT_HBG -> false
                        PolicyMode.FOLLOW_SYSTEM -> chain.proceed()
                    }
                }
            }
            anyOk = true
        }.onFailure { log(Log.ERROR, TAG, "Hook DeviceIdleController failed", it) }

        runCatching {
            val standbyClass = cl.loadClass("com.android.server.usage.AppStandbyController")
            hook(standbyClass.getDeclaredMethod("onBootPhase", Int::class.javaPrimitiveType)).intercept { chain ->
                appStandbyController = chain.thisObject
                chain.proceed()
            }
            hook(
                standbyClass.getDeclaredMethod(
                    "getAppStandbyBucket",
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    Long::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                )
            ).intercept { chain ->
                if (appStandbyController == null) appStandbyController = chain.thisObject
                val pkg = chain.getArg(0) as? String
                val userId = chain.getArg(1) as Int
                when (modeFor(pkg, userId, BgSubsystem.BATTERY_DOZE)) {
                    PolicyMode.FORCE_ALLOW -> STANDBY_BUCKET_EXEMPTED
                    PolicyMode.FORCE_RESTRICT,
                    PolicyMode.FORCE_RESTRICT_HBG -> {
                        val orig = chain.proceed() as Int
                        if (orig < STANDBY_BUCKET_RARE) STANDBY_BUCKET_RARE else orig
                    }
                    PolicyMode.FOLLOW_SYSTEM -> chain.proceed()
                }
            }
            listOf(
                standbyClass.getDeclaredMethod(
                    "isAppIdleFiltered",
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    Long::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                ),
                standbyClass.getDeclaredMethod(
                    "isAppIdleFiltered",
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Long::class.javaPrimitiveType,
                ),
            ).forEach { m ->
                hook(m).intercept { chain ->
                    val pkg = chain.getArg(0) as? String
                    val rawId = chain.getArg(1) as Int
                    val userId = if (rawId >= 10000) MultiUserPackageHelper.userIdOfUid(rawId) else rawId
                    when (modeFor(pkg, userId, BgSubsystem.BATTERY_DOZE)) {
                        PolicyMode.FORCE_ALLOW -> false
                        PolicyMode.FORCE_RESTRICT,
                        PolicyMode.FORCE_RESTRICT_HBG -> true
                        PolicyMode.FOLLOW_SYSTEM -> chain.proceed()
                    }
                }
            }
            anyOk = true
        }.onFailure { log(Log.ERROR, TAG, "Hook AppStandbyController failed", it) }

        runCatching {
            val guardUtilClass = cl.loadClass("com.android.server.OplusGuardElfConfigUtil")
            hook(guardUtilClass.getDeclaredMethod("getAppCpuKillSwitch")).intercept { chain ->
                if (globalFlags.disableGuardElfCpuKill) false else chain.proceed()
            }
            anyOk = true
        }.onFailure { log(Log.ERROR, TAG, "Hook OplusGuardElfConfigUtil failed", it) }

        return anyOk
    }

    private fun installAthenaHooks(cl: ClassLoader): Boolean {
        var anyOk = false
        runCatching {
            val athenaClass = cl.loadClass("com.android.server.am.OplusAthenaAmManager")
            val prClass = cl.loadClass("com.android.server.am.ProcessRecord")

            hook(athenaClass.getDeclaredMethod("skipCacheEmptyKill", prClass)).intercept { chain ->
                val pr = chain.getArg(0)
                val pkg = extractProcessRecordPkg(pr)
                val userId = extractProcessRecordUserId(pr)
                when (modeFor(pkg, userId, BgSubsystem.ATHENA_RECENT)) {
                    PolicyMode.FORCE_ALLOW -> {
                        recordEvent(pkg, BgSubsystem.ATHENA_RECENT, "skip_athena_clean", "skipCacheEmptyKill", userId)
                        true
                    }
                    PolicyMode.FORCE_RESTRICT,
                    PolicyMode.FORCE_RESTRICT_HBG -> false
                    PolicyMode.FOLLOW_SYSTEM -> chain.proceed()
                }
            }

            hook(athenaClass.getDeclaredMethod("doProactiveClean", prClass, Int::class.javaPrimitiveType)).intercept { chain ->
                val pr = chain.getArg(0)
                val pkg = extractProcessRecordPkg(pr)
                val userId = extractProcessRecordUserId(pr)
                if (modeFor(pkg, userId, BgSubsystem.ATHENA_RECENT) == PolicyMode.FORCE_ALLOW) {
                    recordEvent(pkg, BgSubsystem.ATHENA_RECENT, "skip_athena_clean", "doProactiveClean", userId)
                    return@intercept null
                }
                chain.proceed()
            }

            hook(
                athenaClass.getDeclaredMethod(
                    "forceStopPackage",
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    String::class.java,
                    String::class.java,
                )
            ).intercept { chain ->
                val pkg = chain.getArg(0) as? String
                val userId = chain.getArg(1) as Int
                if (modeFor(pkg, userId, BgSubsystem.ATHENA_RECENT) == PolicyMode.FORCE_ALLOW) {
                    recordEvent(pkg, BgSubsystem.ATHENA_RECENT, "skip_athena_forcestop", chain.getArg(4) as? String ?: "", userId)
                    return@intercept null
                }
                chain.proceed()
            }
            anyOk = true
        }.onFailure { log(Log.ERROR, TAG, "Hook OplusAthenaAmManager failed", it) }

        runCatching {
            val osenseKillClass = cl.loadClass("com.android.server.am.OplusOsenseKillAction")
            val killCtxClass = cl.loadClass("com.android.server.am.OplusOsenseKillAction\$KillContext")
            val piClass = cl.loadClass("com.android.server.am.ProcessInfo")
            val pgClass = cl.loadClass("com.android.server.am.ProcessGroup")
            val prClass = cl.loadClass("com.android.server.am.ProcessRecord")
            val gameExtClass = cl.loadClass("com.android.server.app.IGameManagerServiceExt")

            hook(
                osenseKillClass.getDeclaredMethod(
                    "getCchEmptyCheckSkipReason",
                    killCtxClass,
                    piClass,
                    Long::class.javaPrimitiveType,
                    Set::class.java,
                    Set::class.java,
                )
            ).intercept { chain ->
                val procInfo = chain.getArg(1)
                val pkg = extractOsenseProcInfoPkg(procInfo)
                val userId = extractOsenseProcInfoUserId(procInfo)
                if (modeFor(pkg, userId, BgSubsystem.ATHENA_RECENT) == PolicyMode.FORCE_ALLOW) {
                    setOsenseSkipReason(procInfo, SKIP_REASON_ALLOW)
                    recordEvent(pkg, BgSubsystem.ATHENA_RECENT, "skip_nirvana", "cch-empty", userId)
                    return@intercept SKIP_REASON_ALLOW
                }
                chain.proceed()
            }

            hook(osenseKillClass.getDeclaredMethod("getNonUiCheckSkipReason", piClass)).intercept { chain ->
                val procInfo = chain.getArg(0)
                val pkg = extractOsenseProcInfoPkg(procInfo)
                val userId = extractOsenseProcInfoUserId(procInfo)
                if (modeFor(pkg, userId, BgSubsystem.ATHENA_RECENT) == PolicyMode.FORCE_ALLOW) {
                    setOsenseSkipReason(procInfo, SKIP_REASON_ALLOW)
                    recordEvent(pkg, BgSubsystem.ATHENA_RECENT, "skip_nirvana", "non-ui", userId)
                    return@intercept SKIP_REASON_ALLOW
                }
                chain.proceed()
            }

            hook(
                osenseKillClass.getDeclaredMethod(
                    "getAllProcCheckSkipType",
                    killCtxClass,
                    pgClass,
                    gameExtClass,
                    Long::class.javaPrimitiveType,
                )
            ).intercept { chain ->
                val group = chain.getArg(1)
                val pkg = extractOsenseGroupPkg(group)
                val userId = extractOsenseGroupUserId(group)
                if (modeFor(pkg, userId, BgSubsystem.ATHENA_RECENT) == PolicyMode.FORCE_ALLOW) {
                    setOsenseGroupSkipReason(group, SKIP_REASON_ALLOW)
                    recordEvent(pkg, BgSubsystem.ATHENA_RECENT, "skip_nirvana", "group-clean", userId)
                    return@intercept SKIP_REASON_ALLOW
                }
                chain.proceed()
            }

            hook(
                osenseKillClass.getDeclaredMethod(
                    "getAllProcCheckSkipTypeForRecentBgApp",
                    killCtxClass,
                    pgClass,
                )
            ).intercept { chain ->
                val group = chain.getArg(1)
                val pkg = extractOsenseGroupPkg(group)
                val userId = extractOsenseGroupUserId(group)
                if (modeFor(pkg, userId, BgSubsystem.ATHENA_RECENT) == PolicyMode.FORCE_ALLOW) {
                    setOsenseGroupSkipReason(group, SKIP_REASON_ALLOW)
                    recordEvent(pkg, BgSubsystem.ATHENA_RECENT, "skip_nirvana", "recent-bg", userId)
                    return@intercept SKIP_REASON_ALLOW
                }
                chain.proceed()
            }

            hook(
                osenseKillClass.getDeclaredMethod(
                    "killLocked",
                    prClass,
                    String::class.java,
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                )
            ).intercept { chain ->
                val pr = chain.getArg(0)
                val pkg = extractProcessRecordPkg(pr)
                val userId = extractProcessRecordUserId(pr)
                if (modeFor(pkg, userId, BgSubsystem.ATHENA_RECENT) == PolicyMode.FORCE_ALLOW) {
                    val reason = (chain.getArg(2) as? String) ?: (chain.getArg(1) as? String) ?: "osense"
                    recordEvent(pkg, BgSubsystem.ATHENA_RECENT, "skip_okill", reason, userId)
                    return@intercept null
                }
                chain.proceed()
            }
            anyOk = true
        }.onFailure { log(Log.ERROR, TAG, "Hook OplusOsenseKillAction failed", it) }

        runCatching {
            val hansMgrClass = cl.loadClass("com.android.server.am.OplusHansManager")
            hook(
                hansMgrClass.getDeclaredMethod(
                    "handleRemoveTask",
                    Boolean::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    String::class.java,
                )
            ).intercept { chain ->
                val userId = chain.getArg(1) as Int
                val pkg = chain.getArg(2) as? String
                if (modeFor(pkg, userId, BgSubsystem.ATHENA_RECENT) == PolicyMode.FORCE_ALLOW) {
                    recordEvent(pkg, BgSubsystem.ATHENA_RECENT, "skip_remove_task", "hans", userId)
                    return@intercept null
                }
                chain.proceed()
            }
            anyOk = true
        }.onFailure { log(Log.ERROR, TAG, "Hook OplusHansManager.handleRemoveTask failed", it) }

        runCatching {
            val listMgrClass = cl.loadClass("com.android.server.OplusListManagerImpl")
            hook(listMgrClass.getDeclaredMethod("getRemoveTaskFilterPkgList", Context::class.java)).intercept { chain ->
                val raw = chain.proceed() as? List<*> ?: return@intercept null
                val filtered = ArrayList<String>(raw.size)
                for (item in raw) {
                    val pkg = item as? String ?: continue
                    if (!modeFor(pkg, 0, BgSubsystem.ATHENA_RECENT).isRestrict) {
                        filtered.add(pkg)
                    }
                }
                filtered
            }
            anyOk = true
        }.onFailure { log(Log.ERROR, TAG, "Hook OplusListManagerImpl.getRemoveTaskFilterPkgList failed", it) }

        runCatching {
            val supervisorClass = cl.loadClass("com.android.server.wm.ActivityTaskSupervisor")
            val taskClass = cl.loadClass("com.android.server.wm.Task")
            listOf(
                supervisorClass.getDeclaredMethod(
                    "removeTask",
                    taskClass,
                    Boolean::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                    String::class.java,
                ),
                supervisorClass.getDeclaredMethod(
                    "removeTask",
                    taskClass,
                    Boolean::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    String::class.java,
                ),
            ).forEach { m ->
                hook(m).intercept { chain ->
                    val depth = (removeTaskDepth.get() ?: 0) + 1
                    removeTaskDepth.set(depth)
                    try {
                        val result = chain.proceed()
                        if (depth == 1) {
                            val task = chain.getArg(0)
                            val killProcess = chain.getArg(1) as? Boolean ?: false
                            val removeFromRecents = chain.getArg(2) as? Boolean ?: false
                            val reason = chain.getArg(3) as? String ?: "removeTask"
                            if (killProcess && removeFromRecents) {
                                val pkg = extractTaskPkg(task)
                                val userId = extractTaskUserId(task)
                                if (pkg != null) {
                                    if (modeFor(pkg, userId, BgSubsystem.ATHENA_RECENT).isRestrict) {
                                        recordEvent(pkg, BgSubsystem.ATHENA_RECENT, "remove_task_forcestop", reason, userId)
                                        workerHandler.post { forceStopPackageDirect(pkg, userId) }
                                    }
                                    if (AppUserKey(pkg, userId) in autoFreezePkgs) {
                                        scheduleAutoFreezeCheck(pkg, userId, 1500L)
                                    }
                                }
                            }
                        }
                        result
                    } finally {
                        removeTaskDepth.set(depth - 1)
                    }
                }
            }
            anyOk = true
        }.onFailure { log(Log.ERROR, TAG, "Hook ActivityTaskSupervisor.removeTask failed", it) }

        runCatching {
            val dbClass = cl.loadClass("com.android.server.hans.OplusHansDBConfig")
            hook(dbClass.getDeclaredMethod("isTrimMemWhitePkg", String::class.java)).intercept { chain ->
                val pkg = chain.getArg(0) as? String
                when (modeForFallback(pkg, BgSubsystem.ATHENA_RECENT)) {
                    PolicyMode.FORCE_ALLOW -> true
                    PolicyMode.FORCE_RESTRICT,
                    PolicyMode.FORCE_RESTRICT_HBG -> false
                    PolicyMode.FOLLOW_SYSTEM -> chain.proceed()
                }
            }
            anyOk = true
        }.onFailure { log(Log.ERROR, TAG, "Hook isTrimMemWhitePkg failed", it) }

        return anyOk
    }

    private fun installCpuLimitHooks(cl: ClassLoader): Boolean {
        var anyOk = false
        runCatching {
            val cpuClass = cl.loadClass("com.android.server.am.OplusCpuLimitManager")
            val prClass = cl.loadClass("com.android.server.am.ProcessRecord")
            val piClass = cl.loadClass("com.android.server.cpulimit.ProcessInfo")

            hook(cpuClass.getDeclaredMethod("skipSetSchedGroup", prClass, String::class.java)).intercept { chain ->
                val pr = chain.getArg(0)
                val pkg = extractProcessRecordPkg(pr)
                val userId = extractProcessRecordUserId(pr)
                when (modeFor(pkg, userId, BgSubsystem.CPU_LIMIT)) {
                    PolicyMode.FORCE_ALLOW -> true
                    PolicyMode.FORCE_RESTRICT,
                    PolicyMode.FORCE_RESTRICT_HBG -> false
                    PolicyMode.FOLLOW_SYSTEM -> chain.proceed()
                }
            }

            hook(
                cpuClass.getDeclaredMethod(
                    "processGroupCanAjustedByOcl",
                    prClass,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                )
            ).intercept { chain ->
                val pr = chain.getArg(0)
                val pkg = extractProcessRecordPkg(pr)
                val userId = extractProcessRecordUserId(pr)
                if (modeFor(pkg, userId, BgSubsystem.CPU_LIMIT) == PolicyMode.FORCE_ALLOW) {
                    return@intercept false
                }
                chain.proceed()
            }

            hook(
                cpuClass.getDeclaredMethod(
                    "adjustProcessGroup",
                    piClass,
                    Int::class.javaPrimitiveType,
                    String::class.java,
                    String::class.java,
                    Boolean::class.javaPrimitiveType,
                )
            ).intercept { chain ->
                val pi = chain.getArg(0)
                val pkg = extractProcessInfoPkg(pi)
                val userId = extractProcessInfoUserId(pi)
                if (modeFor(pkg, userId, BgSubsystem.CPU_LIMIT) == PolicyMode.FORCE_ALLOW) {
                    return@intercept null
                }
                chain.proceed()
            }
            anyOk = true
        }.onFailure { log(Log.ERROR, TAG, "Hook OplusCpuLimitManager failed", it) }

        runCatching {
            val cpuDbClass = cl.loadClass("com.android.server.cpulimit.CpuLimitDBConfig")
            hook(cpuDbClass.getDeclaredMethod("inCpuSetBlackList", String::class.java)).intercept { chain ->
                val pkg = chain.getArg(0) as? String
                when (modeForFallback(pkg, BgSubsystem.CPU_LIMIT)) {
                    PolicyMode.FORCE_ALLOW -> false
                    PolicyMode.FORCE_RESTRICT,
                    PolicyMode.FORCE_RESTRICT_HBG -> true
                    PolicyMode.FOLLOW_SYSTEM -> chain.proceed()
                }
            }
            hook(cpuDbClass.getDeclaredMethod("inVipHBGList", String::class.java)).intercept { chain ->
                val pkg = chain.getArg(0) as? String
                when (modeForFallback(pkg, BgSubsystem.CPU_LIMIT)) {
                    PolicyMode.FORCE_ALLOW,
                    PolicyMode.FORCE_RESTRICT_HBG -> true
                    PolicyMode.FORCE_RESTRICT -> false
                    PolicyMode.FOLLOW_SYSTEM -> chain.proceed()
                }
            }
            runCatching {
                hook(cpuDbClass.getDeclaredMethod("inParticularPkgList", String::class.java)).intercept { chain ->
                    val pkg = chain.getArg(0) as? String
                    when (modeForFallback(pkg, BgSubsystem.CPU_LIMIT)) {
                        PolicyMode.FORCE_ALLOW -> true
                        PolicyMode.FORCE_RESTRICT,
                        PolicyMode.FORCE_RESTRICT_HBG -> false
                        PolicyMode.FOLLOW_SYSTEM -> chain.proceed()
                    }
                }
            }
            anyOk = true
        }.onFailure { log(Log.ERROR, TAG, "Hook CpuLimitDBConfig failed", it) }

        runCatching {
            val dbClass = cl.loadClass("com.android.server.hans.OplusHansDBConfig")
            hook(dbClass.getDeclaredMethod("isInCpuCtlWhiteList", String::class.java)).intercept { chain ->
                val pkg = chain.getArg(0) as? String
                when (modeForFallback(pkg, BgSubsystem.CPU_LIMIT)) {
                    PolicyMode.FORCE_ALLOW -> true
                    PolicyMode.FORCE_RESTRICT,
                    PolicyMode.FORCE_RESTRICT_HBG -> false
                    PolicyMode.FOLLOW_SYSTEM -> chain.proceed()
                }
            }
            anyOk = true
        }.onFailure { log(Log.ERROR, TAG, "Hook OplusHansDBConfig isInCpuCtlWhiteList failed", it) }

        return anyOk
    }

    private fun installDozeNetHooks(cl: ClassLoader): Boolean {
        var anyOk = false
        runCatching {
            val dbClass = cl.loadClass("com.android.server.hans.OplusHansDBConfig")
            hook(dbClass.getDeclaredMethod("isRestrictedNetAppInDoze", Int::class.javaPrimitiveType, String::class.java)).intercept { chain ->
                val uid = chain.getArg(0) as Int
                val pkg = chain.getArg(1) as? String
                val userId = MultiUserPackageHelper.userIdOfUid(uid)
                when (modeFor(pkg, userId, BgSubsystem.DOZE_NET)) {
                    PolicyMode.FORCE_ALLOW -> false
                    PolicyMode.FORCE_RESTRICT,
                    PolicyMode.FORCE_RESTRICT_HBG -> true
                    PolicyMode.FOLLOW_SYSTEM -> chain.proceed()
                }
            }
            hook(dbClass.getDeclaredMethod("isTrafficWhitePkg", String::class.java)).intercept { chain ->
                val pkg = chain.getArg(0) as? String
                when (modeForFallback(pkg, BgSubsystem.DOZE_NET)) {
                    PolicyMode.FORCE_ALLOW -> true
                    PolicyMode.FORCE_RESTRICT,
                    PolicyMode.FORCE_RESTRICT_HBG -> false
                    PolicyMode.FOLLOW_SYSTEM -> chain.proceed()
                }
            }
            anyOk = true
        }.onFailure { log(Log.ERROR, TAG, "Hook OplusHansDBConfig Doze net failed", it) }

        runCatching {
            val policyClass = cl.loadClass("com.android.server.hans.oguard.policy.AppPowerPolicy")
            hook(policyClass.getDeclaredMethod("setUidFirewallRule", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)).intercept { chain ->
                val uid = chain.getArg(0) as Int
                val restrict = chain.getArg(1) as Boolean
                if (restrict && uidHasMode(uid, BgSubsystem.DOZE_NET, PolicyMode.FORCE_ALLOW)) {
                    return@intercept null
                }
                chain.proceed()
            }
            anyOk = true
        }.onFailure { log(Log.ERROR, TAG, "Hook setUidFirewallRule failed", it) }

        return anyOk
    }

    private fun installBroadcastRestrictHooks(cl: ClassLoader): Boolean {
        var anyOk = false
        runCatching {
            val startupCfgClass = cl.loadClass("com.android.server.am.OplusAppStartupConfig")
            hook(startupCfgClass.getDeclaredMethod("isInBroadcastRestrictionWhiteList", String::class.java, String::class.java)).intercept { chain ->
                val pkg = chain.getArg(0) as? String
                when (modeForFallback(pkg, BgSubsystem.BROADCAST_RESTRICT)) {
                    PolicyMode.FORCE_ALLOW -> true
                    PolicyMode.FORCE_RESTRICT,
                    PolicyMode.FORCE_RESTRICT_HBG -> false
                    PolicyMode.FOLLOW_SYSTEM -> chain.proceed()
                }
            }
            anyOk = true
        }.onFailure { log(Log.ERROR, TAG, "Hook isInBroadcastRestrictionWhiteList failed", it) }

        runCatching {
            val restrictionBaseClass = cl.loadClass("com.android.server.am.OplusBroadcastRestriction\$Restriction")
            val prClass = cl.loadClass("com.android.server.am.ProcessRecord")
            hook(
                restrictionBaseClass.getDeclaredMethod(
                    "isAllowByDefaultCommonCheck",
                    ApplicationInfo::class.java,
                    prClass,
                    android.content.Intent::class.java,
                )
            ).intercept { chain ->
                val appInfo = chain.getArg(0) as? ApplicationInfo
                val pr = chain.getArg(1)
                val pkg = appInfo?.packageName ?: extractProcessRecordPkg(pr)
                val userId = appInfo?.let { MultiUserPackageHelper.userIdOfUid(it.uid) } ?: extractProcessRecordUserId(pr)
                when (modeFor(pkg, userId, BgSubsystem.BROADCAST_RESTRICT)) {
                    PolicyMode.FORCE_ALLOW -> true
                    PolicyMode.FORCE_RESTRICT,
                    PolicyMode.FORCE_RESTRICT_HBG -> false
                    PolicyMode.FOLLOW_SYSTEM -> chain.proceed()
                }
            }
            anyOk = true
        }.onFailure { log(Log.ERROR, TAG, "Hook isAllowByDefaultCommonCheck failed", it) }

        runCatching {
            val brClass = cl.loadClass("com.android.server.am.OplusBroadcastRestriction")
            val prClass = cl.loadClass("com.android.server.am.ProcessRecord")
            hook(
                brClass.getDeclaredMethod(
                    "restrict",
                    android.content.Intent::class.java,
                    android.util.ArrayMap::class.java,
                    ApplicationInfo::class.java,
                    prClass,
                    Boolean::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                )
            ).intercept { chain ->
                if (globalFlags.disableBroadcastQuotaGlobal) return@intercept false
                val appInfo = chain.getArg(2) as? ApplicationInfo
                val pr = chain.getArg(3)
                val pkg = appInfo?.packageName ?: extractProcessRecordPkg(pr)
                val userId = appInfo?.let { MultiUserPackageHelper.userIdOfUid(it.uid) } ?: extractProcessRecordUserId(pr)
                if (modeFor(pkg, userId, BgSubsystem.BROADCAST_RESTRICT) == PolicyMode.FORCE_ALLOW) {
                    return@intercept false
                }
                chain.proceed()
            }
            anyOk = true
        }.onFailure { log(Log.ERROR, TAG, "Hook OplusBroadcastRestriction failed", it) }

        return anyOk
    }

    private fun installSystemLifecycleHooks(cl: ClassLoader) {
        runCatching {
            val amsClass = cl.loadClass("com.android.server.am.ActivityManagerService")
            hook(amsClass.getDeclaredMethod("finishBooting")).intercept { chain ->
                if (amsInstance == null) amsInstance = chain.thisObject
                val result = chain.proceed()
                bootCompleted = true
                scheduleBatteryDozeSync()
                registerScreenOffReceiver()
                result
            }
            amsClass.declaredMethods
                .filter { it.name == "systemReady" }
                .forEach { m ->
                    hook(m).intercept { chain ->
                        if (amsInstance == null) amsInstance = chain.thisObject
                        chain.proceed()
                    }
                }
        }.onFailure { log(Log.ERROR, TAG, "Hook ActivityManagerService lifecycle failed", it) }

        runCatching {
            val procListClass = cl.loadClass("com.android.server.am.ProcessList")
            val prClass = cl.loadClass("com.android.server.am.ProcessRecord")
            procListClass.declaredMethods
                .filter { it.name == "removeProcessLocked" && it.parameterTypes.isNotEmpty() && it.parameterTypes[0] == prClass }
                .forEach { m ->
                    hook(m).intercept { chain ->
                        val pr = chain.getArg(0)
                        val pkg = extractProcessRecordPkg(pr)
                        val userId = extractProcessRecordUserId(pr)
                        val res = chain.proceed()
                        if (pkg != null && AppUserKey(pkg, userId) in autoFreezePkgs) {
                            scheduleAutoFreezeCheck(pkg, userId, globalFlags.autoFreezeDelaySec * 1000L)
                        }
                        res
                    }
                }
        }.onFailure { logMsg(Log.WARN, "Hook ProcessList.removeProcessLocked skipped", it) }
    }

    private fun registerScreenOffReceiver() {
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

    private fun scheduleAutoFreezeCheck(pkg: String, userId: Int, delayMs: Long) {
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
        }
        return Bundle().apply { putBoolean("ok", ok) }
    }

    private fun scheduleBatteryDozeSync() {
        workerHandler.removeCallbacks(syncDozeRunnable)
        workerHandler.postDelayed(syncDozeRunnable, 350L)
    }

    private fun syncBatteryDozeState(): Int {
        val dic = deviceIdleController ?: return 0
        val currentRules = rules
        val allowSet = LinkedHashSet<String>()
        val restrictCandidate = LinkedHashSet<String>()
        for ((target, map) in currentRules) {
            when (map[BgSubsystem.BATTERY_DOZE]) {
                PolicyMode.FORCE_ALLOW -> allowSet.add(target.packageName)
                PolicyMode.FORCE_RESTRICT, PolicyMode.FORCE_RESTRICT_HBG -> restrictCandidate.add(target.packageName)
                else -> {}
            }
        }
        val allowPkgs = allowSet.toList()
        val restrictPkgs = restrictCandidate.filter { it !in allowSet }
        val token = Binder.clearCallingIdentity()
        var changed = 0
        try {
            val userMap = runCatching {
                findDeclaredField(dic.javaClass, "mPowerSaveWhitelistUserApps")?.get(dic) as? Map<*, *>
            }.getOrNull()

            val toAdd = allowPkgs.filter { userMap == null || !userMap.containsKey(it) }
            if (toAdd.isNotEmpty()) {
                runCatching {
                    val m = dic.javaClass.getDeclaredMethod("addPowerSaveWhitelistAppsInternal", List::class.java)
                    m.isAccessible = true
                    val added = (m.invoke(dic, toAdd) as? Int) ?: 0
                    changed += added
                    toAdd.forEach { recordEvent(it, BgSubsystem.BATTERY_DOZE, "sync_doze_add", "DeviceIdle") }
                }.onFailure { log(Log.ERROR, TAG, "sync addPowerSaveWhitelistAppsInternal failed", it) }
            }

            val toRemove = restrictPkgs.filter { userMap == null || userMap.containsKey(it) }
            if (toRemove.isNotEmpty()) {
                val rm = runCatching {
                    dic.javaClass.getDeclaredMethod("removePowerSaveWhitelistAppInternal", String::class.java).apply {
                        isAccessible = true
                    }
                }.getOrNull()
                if (rm != null) {
                    for (pkg in toRemove) {
                        runCatching {
                            if (rm.invoke(dic, pkg) as? Boolean == true) {
                                changed++
                                recordEvent(pkg, BgSubsystem.BATTERY_DOZE, "sync_doze_remove", "DeviceIdle")
                            }
                        }
                    }
                }
            }
        } finally {
            Binder.restoreCallingIdentity(token)
        }
        return changed
    }

    private fun forceStopPackageDirect(pkg: String, userId: Int): Boolean {
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
        val realDozeWhitelisted = if (dic != null) {
            runCatching {
                val userMap = findDeclaredField(dic.javaClass, "mPowerSaveWhitelistUserApps")?.get(dic) as? Map<*, *>
                val sysMap = findDeclaredField(dic.javaClass, "mPowerSaveWhitelistSystemApps")?.get(dic) as? Map<*, *>
                (userMap?.containsKey(pkg) == true) || (sysMap?.containsKey(pkg) == true)
            }.getOrDefault(false)
        } else {
            runCatching {
                val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
                pm?.isIgnoringBatteryOptimizations(pkg) == true
            }.getOrDefault(false)
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

    private fun recordEvent(pkg: String?, sub: BgSubsystem, action: String, detail: String = "", userId: Int = 0) {
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

    private fun findDeclaredField(clazz: Class<*>, name: String): Field? {
        var cur: Class<*>? = clazz
        while (cur != null) {
            runCatching {
                return cur.getDeclaredField(name).apply { isAccessible = true }
            }
            cur = cur.superclass
        }
        return null
    }

    private fun extractHansPkgName(obj: Any?): String? {
        if (obj == null) return null
        val m = getHansPkgNameMethod ?: runCatching {
            obj.javaClass.getMethod("getPkgName").also { getHansPkgNameMethod = it }
        }.getOrNull() ?: return null
        return runCatching { m.invoke(obj) as? String }.getOrNull()
    }

    private fun extractHansUserId(obj: Any?): Int {
        if (obj == null) return 0
        val m = getHansUidMethod ?: runCatching {
            obj.javaClass.getMethod("getUid").also { getHansUidMethod = it }
        }.getOrNull()
        val uid = runCatching { m?.invoke(obj) as? Int }.getOrNull()
            ?: MultiUserPackageHelper.extractFieldInt(obj, "mUid")
            ?: return 0
        return MultiUserPackageHelper.userIdOfUid(uid)
    }

    private fun extractProcessRecordPkg(obj: Any?): String? {
        if (obj == null) return null
        val m = processRecordAppInfoMethod ?: runCatching {
            obj.javaClass.getMethod("getApplicationInfo").also { processRecordAppInfoMethod = it }
        }.getOrNull() ?: return null
        return runCatching { (m.invoke(obj) as? ApplicationInfo)?.packageName }.getOrNull()
    }

    private fun extractProcessRecordUserId(obj: Any?): Int {
        if (obj == null) return 0
        val m = processRecordAppInfoMethod ?: runCatching {
            obj.javaClass.getMethod("getApplicationInfo").also { processRecordAppInfoMethod = it }
        }.getOrNull()
        val appUid = runCatching { (m?.invoke(obj) as? ApplicationInfo)?.uid }.getOrNull()
        if (appUid != null && appUid > 0) return MultiUserPackageHelper.userIdOfUid(appUid)
        val uid = MultiUserPackageHelper.extractFieldInt(obj, "uid") ?: return 0
        return MultiUserPackageHelper.userIdOfUid(uid)
    }

    private fun extractHostingType(pr: Any?): String? {
        if (pr == null) return null
        val hm = processRecordHostingMethod ?: runCatching {
            pr.javaClass.getMethod("getHostingRecord").also { processRecordHostingMethod = it }
        }.getOrNull() ?: return null
        val hosting = runCatching { hm.invoke(pr) }.getOrNull() ?: return null
        val tm = hostingGetTypeMethod ?: runCatching {
            hosting.javaClass.getMethod("getType").also { hostingGetTypeMethod = it }
        }.getOrNull() ?: return null
        return runCatching { tm.invoke(hosting) as? String }.getOrNull()
    }

    private fun extractProcessInfoPkg(obj: Any?): String? {
        if (obj == null) return null
        val m = processInfoGetPkgMethod ?: runCatching {
            obj.javaClass.getMethod("getPkgName").also { processInfoGetPkgMethod = it }
        }.getOrNull() ?: return null
        return runCatching { m.invoke(obj) as? String }.getOrNull()
    }

    private fun extractProcessInfoUserId(obj: Any?): Int {
        if (obj == null) return 0
        val m = processInfoGetUidMethod ?: runCatching {
            obj.javaClass.getMethod("getUid").also { processInfoGetUidMethod = it }
        }.getOrNull()
        val uid = runCatching { m?.invoke(obj) as? Int }.getOrNull()
            ?: MultiUserPackageHelper.extractFieldInt(obj, "mUid")
            ?: return 0
        return MultiUserPackageHelper.userIdOfUid(uid)
    }

    private fun extractOsenseProcInfoPkg(obj: Any?): String? {
        if (obj == null) return null
        val fInfo = osenseProcInfoAppInfoField ?: findDeclaredField(obj.javaClass, "info")?.also {
            osenseProcInfoAppInfoField = it
        }
        val appPkg = runCatching { (fInfo?.get(obj) as? ApplicationInfo)?.packageName }.getOrNull()
        if (!appPkg.isNullOrEmpty()) return appPkg

        val fGroup = osenseProcInfoGroupField ?: findDeclaredField(obj.javaClass, "mGroup")?.also {
            osenseProcInfoGroupField = it
        }
        val groupPkg = extractOsenseGroupPkg(runCatching { fGroup?.get(obj) }.getOrNull())
        if (!groupPkg.isNullOrEmpty()) return groupPkg

        val fApp = osenseProcInfoAppField ?: findDeclaredField(obj.javaClass, "mApp")?.also {
            osenseProcInfoAppField = it
        }
        val prPkg = extractProcessRecordPkg(runCatching { fApp?.get(obj) }.getOrNull())
        if (!prPkg.isNullOrEmpty()) return prPkg

        val fProc = osenseProcInfoProcNameField ?: findDeclaredField(obj.javaClass, "mProcessName")?.also {
            osenseProcInfoProcNameField = it
        }
        return runCatching { (fProc?.get(obj) as? String)?.substringBefore(':') }.getOrNull()
    }

    private fun extractOsenseProcInfoUserId(obj: Any?): Int {
        if (obj == null) return 0
        val fInfo = osenseProcInfoAppInfoField ?: findDeclaredField(obj.javaClass, "info")?.also {
            osenseProcInfoAppInfoField = it
        }
        val infoUid = runCatching { (fInfo?.get(obj) as? ApplicationInfo)?.uid }.getOrNull()
        if (infoUid != null && infoUid > 0) return MultiUserPackageHelper.userIdOfUid(infoUid)

        val fUid = osenseProcInfoUidField ?: findDeclaredField(obj.javaClass, "mUid")?.also {
            osenseProcInfoUidField = it
        }
        val fieldUid = runCatching { fUid?.getInt(obj) }.getOrNull()
        if (fieldUid != null && fieldUid > 0) return MultiUserPackageHelper.userIdOfUid(fieldUid)

        val fApp = osenseProcInfoAppField ?: findDeclaredField(obj.javaClass, "mApp")?.also {
            osenseProcInfoAppField = it
        }
        val appObj = runCatching { fApp?.get(obj) }.getOrNull()
        if (appObj != null) return extractProcessRecordUserId(appObj)

        val fGroup = osenseProcInfoGroupField ?: findDeclaredField(obj.javaClass, "mGroup")?.also {
            osenseProcInfoGroupField = it
        }
        return extractOsenseGroupUserId(runCatching { fGroup?.get(obj) }.getOrNull())
    }

    private fun setOsenseSkipReason(obj: Any?, reason: String) {
        if (obj == null) return
        val f = osenseProcInfoSkipField ?: findDeclaredField(obj.javaClass, "mSkipReason")?.also {
            osenseProcInfoSkipField = it
        } ?: return
        runCatching { f.set(obj, reason) }
    }

    private fun extractOsenseGroupPkg(obj: Any?): String? {
        if (obj == null) return null
        val f = osenseGroupPkgField ?: findDeclaredField(obj.javaClass, "mPackageName")?.also {
            osenseGroupPkgField = it
        } ?: return null
        return runCatching { f.get(obj) as? String }.getOrNull()
    }

    private fun extractOsenseGroupUserId(obj: Any?): Int {
        if (obj == null) return 0
        val fUid = osenseGroupUidField ?: findDeclaredField(obj.javaClass, "mUid")?.also {
            osenseGroupUidField = it
        }
        val uid = runCatching { fUid?.getInt(obj) }.getOrNull()
        if (uid != null && uid > 0) return MultiUserPackageHelper.userIdOfUid(uid)
        return MultiUserPackageHelper.extractFieldInt(obj, "mUserId") ?: 0
    }

    private fun setOsenseGroupSkipReason(obj: Any?, reason: String) {
        if (obj == null) return
        val f = osenseGroupSkipField ?: findDeclaredField(obj.javaClass, "mSkipReason")?.also {
            osenseGroupSkipField = it
        } ?: return
        runCatching { f.set(obj, reason) }
    }

    private fun extractTaskPkg(task: Any?): String? {
        if (task == null) return null
        val m = taskGetBasePkgMethod ?: runCatching {
            task.javaClass.getMethod("getBasePackageName").also { taskGetBasePkgMethod = it }
        }.getOrNull()
        val basePkg = runCatching { m?.invoke(task) as? String }.getOrNull()
        if (!basePkg.isNullOrEmpty()) return basePkg
        val f = taskRealActivityField ?: findDeclaredField(task.javaClass, "realActivity")?.also {
            taskRealActivityField = it
        } ?: return null
        return runCatching { (f.get(task) as? ComponentName)?.packageName }.getOrNull()
    }

    private fun extractTaskUserId(task: Any?): Int {
        if (task == null) return 0
        val f = taskUserIdField ?: findDeclaredField(task.javaClass, "mUserId")?.also {
            taskUserIdField = it
        } ?: return 0
        return runCatching { f.getInt(task) }.getOrDefault(0)
    }

    private fun extractServiceRecordPkg(obj: Any?): String? {
        if (obj == null) return null
        val f = serviceRecordPkgField ?: findDeclaredField(obj.javaClass, "packageName")?.also {
            serviceRecordPkgField = it
        } ?: return null
        return runCatching { f.get(obj) as? String }.getOrNull()
    }

    private fun extractServiceRecordUserId(obj: Any?): Int {
        if (obj == null) return 0
        val fUser = serviceRecordUserField ?: findDeclaredField(obj.javaClass, "userId")?.also {
            serviceRecordUserField = it
        }
        val userId = runCatching { fUser?.getInt(obj) }.getOrNull()
        if (userId != null) return userId
        val fAppInfo = serviceRecordAppInfoField ?: findDeclaredField(obj.javaClass, "appInfo")?.also {
            serviceRecordAppInfoField = it
        }
        val uid = runCatching { (fAppInfo?.get(obj) as? ApplicationInfo)?.uid }.getOrNull() ?: return 0
        return MultiUserPackageHelper.userIdOfUid(uid)
    }

    private fun extractBroadcastFilterPkg(obj: Any?): String? {
        if (obj == null) return null
        val f = broadcastFilterPkgField ?: findDeclaredField(obj.javaClass, "packageName")?.also {
            broadcastFilterPkgField = it
        } ?: return null
        return runCatching { f.get(obj) as? String }.getOrNull()
    }

    private fun extractReceiverUserId(ri: ResolveInfo?, bf: Any?, br: Any?): Int {
        val riUid = ri?.activityInfo?.applicationInfo?.uid
        if (riUid != null && riUid > 0) return MultiUserPackageHelper.userIdOfUid(riUid)
        if (bf != null) {
            val f = broadcastFilterUidField ?: findDeclaredField(bf.javaClass, "owningUid")?.also {
                broadcastFilterUidField = it
            }
            val uid = runCatching { f?.getInt(bf) }.getOrNull()
            if (uid != null && uid > 0) return MultiUserPackageHelper.userIdOfUid(uid)
        }
        if (br != null) {
            val f = broadcastRecordUserField ?: findDeclaredField(br.javaClass, "userId")?.also {
                broadcastRecordUserField = it
            }
            val uid = runCatching { f?.getInt(br) }.getOrNull()
            if (uid != null) return uid
        }
        return 0
    }

    private fun logMsg(priority: Int, msg: String, tr: Throwable? = null) {
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
        private const val STANDBY_BUCKET_EXEMPTED = 5
        private const val STANDBY_BUCKET_RARE = 40
        private const val SKIP_REASON_ALLOW = "bgmanager-allow"
    }
}
