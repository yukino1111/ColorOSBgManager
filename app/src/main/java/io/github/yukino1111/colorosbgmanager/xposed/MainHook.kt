package io.github.yukino1111.colorosbgmanager.xposed

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.Application
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.ContentProvider
import android.content.Context
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
    private var rules: Map<String, Map<BgSubsystem, PolicyMode>> = emptyMap()

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
    private val hookStatus = ConcurrentHashMap<String, Boolean>()
    private val uidPkgCache = ConcurrentHashMap<Int, List<String>>()

    private val eventLock = Any()
    private val eventBuffer = ArrayDeque<RuntimeEvent>(MAX_EVENTS)
    private val eventCooldown = ConcurrentHashMap<String, Long>()
    private val removeTaskDepth = ThreadLocal.withInitial { 0 }

    private val workerHandler: Handler by lazy {
        val thread = HandlerThread("BgManagerWorker").apply { start() }
        Handler(thread.looper)
    }

    private val syncDozeRunnable = Runnable { syncBatteryDozeState() }

    private var getHansPkgNameMethod: Method? = null
    private var processRecordAppInfoMethod: Method? = null
    private var processRecordHostingMethod: Method? = null
    private var hostingGetTypeMethod: Method? = null
    private var processInfoGetPkgMethod: Method? = null
    private var serviceRecordPkgField: Field? = null
    private var broadcastFilterPkgField: Field? = null
    private var osenseProcInfoAppInfoField: Field? = null
    private var osenseProcInfoAppField: Field? = null
    private var osenseProcInfoGroupField: Field? = null
    private var osenseProcInfoProcNameField: Field? = null
    private var osenseProcInfoSkipField: Field? = null
    private var osenseGroupPkgField: Field? = null
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
        globalFlags = RulesCodec.readGlobalFlags(prefs)
        logMsg(Log.INFO, "Rules refreshed: ${rules.size} pkgs, globals=$globalFlags")
        if (bootCompleted) {
            scheduleBatteryDozeSync()
        }
    }

    private fun modeFor(pkg: String?, sub: BgSubsystem): PolicyMode {
        if (pkg.isNullOrEmpty()) return PolicyMode.FOLLOW_SYSTEM
        return rules[pkg]?.get(sub) ?: PolicyMode.FOLLOW_SYSTEM
    }

    private fun uidHasMode(uid: Int, sub: BgSubsystem, target: PolicyMode): Boolean {
        if (uid < 10000) return false
        val pkgs = resolvePackagesForUid(uid)
        return pkgs.any { modeFor(it, sub) == target }
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
        }.getOrNull()
        return allowedUid != null && callingUid == allowedUid
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
                    method == RulesCodec.BRIDGE_RUNTIME_METHOD
                ) {
                    val ctx = resolveSystemContext() ?: (chain.thisObject as? ContentProvider)?.context
                    if (isBridgeCallerAllowed(ctx)) {
                        val arg = chain.getArg(1) as? String
                        val token = Binder.clearCallingIdentity()
                        return@intercept try {
                            when (method) {
                                RulesCodec.BRIDGE_METHOD -> SystemListParser.buildSnapshotBundle(
                                    hookStatus = hookStatus,
                                    systemClassLoader = systemServerClassLoader,
                                    systemContext = ctx,
                                )
                                RulesCodec.BRIDGE_RUNTIME_METHOD -> handleRuntimeQuery(ctx, arg)
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
                    val pkg = extractHansPkgName(chain.getArg(0))
                    if (modeFor(pkg, BgSubsystem.HANS_FREEZE) == PolicyMode.FORCE_ALLOW) {
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
                val pkg = extractHansPkgName(chain.getArg(0))
                if (modeFor(pkg, BgSubsystem.HANS_FREEZE) == PolicyMode.FORCE_ALLOW) {
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
                val pkg = chain.getArg(4) as? String
                if (globalFlags.disableStrictModeFreeze ||
                    modeFor(pkg, BgSubsystem.HANS_FREEZE) == PolicyMode.FORCE_ALLOW
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
                when (modeFor(pkg, BgSubsystem.HANS_FREEZE)) {
                    PolicyMode.FORCE_ALLOW -> true
                    PolicyMode.FORCE_RESTRICT,
                    PolicyMode.FORCE_RESTRICT_HBG -> false
                    PolicyMode.FOLLOW_SYSTEM -> chain.proceed()
                }
            }
            hook(dbClass.getDeclaredMethod("isBlackHansApp", String::class.java)).intercept { chain ->
                val pkg = chain.getArg(0) as? String
                when (modeFor(pkg, BgSubsystem.HANS_FREEZE)) {
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
                        when (modeFor(pkg, BgSubsystem.HANS_FREEZE)) {
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
                    when (modeFor(pkg, BgSubsystem.AUTO_START)) {
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
                val hostingType = extractHostingType(pr)
                val isActivity = hostingType?.contains("activity", ignoreCase = true) == true
                if (!isActivity) {
                    val autoMode = modeFor(pkg, BgSubsystem.AUTO_START)
                    val assocMode = modeFor(pkg, BgSubsystem.ASSOCIATE_START)
                    if (autoMode.isRestrict || assocMode.isRestrict) {
                        val sub = if (autoMode.isRestrict) BgSubsystem.AUTO_START else BgSubsystem.ASSOCIATE_START
                        recordEvent(pkg, sub, "prevent_start_proc", hostingType ?: "bg")
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
                when (modeFor(pkg, BgSubsystem.AUTO_START)) {
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
                val pkg = extractServiceRecordPkg(chain.getArg(0))
                val autoMode = modeFor(pkg, BgSubsystem.AUTO_START)
                val assocMode = modeFor(pkg, BgSubsystem.ASSOCIATE_START)
                if (autoMode.isRestrict || assocMode.isRestrict) {
                    val sub = if (autoMode.isRestrict) BgSubsystem.AUTO_START else BgSubsystem.ASSOCIATE_START
                    recordEvent(pkg, sub, "block_service_restart", "START_STICKY")
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
                    when (modeFor(pkg, BgSubsystem.ASSOCIATE_START)) {
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
                when (modeFor(pkg, BgSubsystem.ASSOCIATE_START)) {
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
                        val targetPkg = extractServiceRecordPkg(chain.getArg(0))
                        when (modeFor(targetPkg, BgSubsystem.ASSOCIATE_START)) {
                            PolicyMode.FORCE_ALLOW -> false
                            PolicyMode.FORCE_RESTRICT,
                            PolicyMode.FORCE_RESTRICT_HBG -> {
                                recordEvent(targetPkg, BgSubsystem.ASSOCIATE_START, "prevent_service", "startService")
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
                val targetPkg = extractServiceRecordPkg(chain.getArg(2))
                when (modeFor(targetPkg, BgSubsystem.ASSOCIATE_START)) {
                    PolicyMode.FORCE_ALLOW -> false
                    PolicyMode.FORCE_RESTRICT,
                    PolicyMode.FORCE_RESTRICT_HBG -> {
                        recordEvent(targetPkg, BgSubsystem.ASSOCIATE_START, "prevent_bind", chain.getArg(0) as? String ?: "")
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
                val resolvePkg = (chain.getArg(2) as? ResolveInfo)?.activityInfo?.packageName
                val filterPkg = extractBroadcastFilterPkg(chain.getArg(3))
                val argPkg = chain.getArg(4) as? String
                val targetPkg = resolvePkg ?: filterPkg ?: argPkg
                when (modeFor(targetPkg, BgSubsystem.ASSOCIATE_START)) {
                    PolicyMode.FORCE_ALLOW -> false
                    PolicyMode.FORCE_RESTRICT,
                    PolicyMode.FORCE_RESTRICT_HBG -> {
                        recordEvent(targetPkg, BgSubsystem.ASSOCIATE_START, "prevent_receiver", "broadcast")
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
                val appInfoPkg = (chain.getArg(2) as? ApplicationInfo)?.packageName
                val targetPkg = appInfoPkg ?: (chain.getArg(3) as? String)
                when (modeFor(targetPkg, BgSubsystem.ASSOCIATE_START)) {
                    PolicyMode.FORCE_ALLOW -> false
                    PolicyMode.FORCE_RESTRICT,
                    PolicyMode.FORCE_RESTRICT_HBG -> {
                        recordEvent(targetPkg, BgSubsystem.ASSOCIATE_START, "prevent_provider", chain.getArg(3) as? String ?: "")
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
                    val pkg = chain.getArg(1) as? String
                    if (modeFor(pkg, BgSubsystem.BATTERY_DOZE) == PolicyMode.FORCE_ALLOW) {
                        recordEvent(pkg, BgSubsystem.BATTERY_DOZE, "skip_oguard", m.name)
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
                if (modeFor(pkg, BgSubsystem.BATTERY_DOZE) == PolicyMode.FORCE_ALLOW) {
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
                when (modeFor(pkg, BgSubsystem.BATTERY_DOZE)) {
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
                    when (modeFor(pkg, BgSubsystem.BATTERY_DOZE)) {
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
                when (modeFor(pkg, BgSubsystem.BATTERY_DOZE)) {
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
                    when (modeFor(pkg, BgSubsystem.BATTERY_DOZE)) {
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
                val pkg = extractProcessRecordPkg(chain.getArg(0))
                when (modeFor(pkg, BgSubsystem.ATHENA_RECENT)) {
                    PolicyMode.FORCE_ALLOW -> {
                        recordEvent(pkg, BgSubsystem.ATHENA_RECENT, "skip_athena_clean", "skipCacheEmptyKill")
                        true
                    }
                    PolicyMode.FORCE_RESTRICT,
                    PolicyMode.FORCE_RESTRICT_HBG -> false
                    PolicyMode.FOLLOW_SYSTEM -> chain.proceed()
                }
            }

            hook(athenaClass.getDeclaredMethod("doProactiveClean", prClass, Int::class.javaPrimitiveType)).intercept { chain ->
                val pkg = extractProcessRecordPkg(chain.getArg(0))
                if (modeFor(pkg, BgSubsystem.ATHENA_RECENT) == PolicyMode.FORCE_ALLOW) {
                    recordEvent(pkg, BgSubsystem.ATHENA_RECENT, "skip_athena_clean", "doProactiveClean")
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
                if (modeFor(pkg, BgSubsystem.ATHENA_RECENT) == PolicyMode.FORCE_ALLOW) {
                    recordEvent(pkg, BgSubsystem.ATHENA_RECENT, "skip_athena_forcestop", chain.getArg(4) as? String ?: "")
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
                if (modeFor(pkg, BgSubsystem.ATHENA_RECENT) == PolicyMode.FORCE_ALLOW) {
                    setOsenseSkipReason(procInfo, SKIP_REASON_ALLOW)
                    recordEvent(pkg, BgSubsystem.ATHENA_RECENT, "skip_nirvana", "cch-empty")
                    return@intercept SKIP_REASON_ALLOW
                }
                chain.proceed()
            }

            hook(osenseKillClass.getDeclaredMethod("getNonUiCheckSkipReason", piClass)).intercept { chain ->
                val procInfo = chain.getArg(0)
                val pkg = extractOsenseProcInfoPkg(procInfo)
                if (modeFor(pkg, BgSubsystem.ATHENA_RECENT) == PolicyMode.FORCE_ALLOW) {
                    setOsenseSkipReason(procInfo, SKIP_REASON_ALLOW)
                    recordEvent(pkg, BgSubsystem.ATHENA_RECENT, "skip_nirvana", "non-ui")
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
                if (modeFor(pkg, BgSubsystem.ATHENA_RECENT) == PolicyMode.FORCE_ALLOW) {
                    setOsenseGroupSkipReason(group, SKIP_REASON_ALLOW)
                    recordEvent(pkg, BgSubsystem.ATHENA_RECENT, "skip_nirvana", "group-clean")
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
                if (modeFor(pkg, BgSubsystem.ATHENA_RECENT) == PolicyMode.FORCE_ALLOW) {
                    setOsenseGroupSkipReason(group, SKIP_REASON_ALLOW)
                    recordEvent(pkg, BgSubsystem.ATHENA_RECENT, "skip_nirvana", "recent-bg")
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
                val pkg = extractProcessRecordPkg(chain.getArg(0))
                if (modeFor(pkg, BgSubsystem.ATHENA_RECENT) == PolicyMode.FORCE_ALLOW) {
                    val reason = (chain.getArg(2) as? String) ?: (chain.getArg(1) as? String) ?: "osense"
                    recordEvent(pkg, BgSubsystem.ATHENA_RECENT, "skip_okill", reason)
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
                val pkg = chain.getArg(2) as? String
                if (modeFor(pkg, BgSubsystem.ATHENA_RECENT) == PolicyMode.FORCE_ALLOW) {
                    recordEvent(pkg, BgSubsystem.ATHENA_RECENT, "skip_remove_task", "hans")
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
                    if (!modeFor(pkg, BgSubsystem.ATHENA_RECENT).isRestrict) {
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
                                if (modeFor(pkg, BgSubsystem.ATHENA_RECENT).isRestrict && pkg != null) {
                                    val userId = extractTaskUserId(task)
                                    recordEvent(pkg, BgSubsystem.ATHENA_RECENT, "remove_task_forcestop", reason)
                                    workerHandler.post { forceStopPackageDirect(pkg, userId) }
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
                when (modeFor(pkg, BgSubsystem.ATHENA_RECENT)) {
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
                val pkg = extractProcessRecordPkg(chain.getArg(0))
                when (modeFor(pkg, BgSubsystem.CPU_LIMIT)) {
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
                val pkg = extractProcessRecordPkg(chain.getArg(0))
                if (modeFor(pkg, BgSubsystem.CPU_LIMIT) == PolicyMode.FORCE_ALLOW) {
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
                val pkg = extractProcessInfoPkg(chain.getArg(0))
                if (modeFor(pkg, BgSubsystem.CPU_LIMIT) == PolicyMode.FORCE_ALLOW) {
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
                when (modeFor(pkg, BgSubsystem.CPU_LIMIT)) {
                    PolicyMode.FORCE_ALLOW -> false
                    PolicyMode.FORCE_RESTRICT,
                    PolicyMode.FORCE_RESTRICT_HBG -> true
                    PolicyMode.FOLLOW_SYSTEM -> chain.proceed()
                }
            }
            hook(cpuDbClass.getDeclaredMethod("inVipHBGList", String::class.java)).intercept { chain ->
                val pkg = chain.getArg(0) as? String
                when (modeFor(pkg, BgSubsystem.CPU_LIMIT)) {
                    PolicyMode.FORCE_ALLOW,
                    PolicyMode.FORCE_RESTRICT_HBG -> true
                    PolicyMode.FORCE_RESTRICT -> false
                    PolicyMode.FOLLOW_SYSTEM -> chain.proceed()
                }
            }
            runCatching {
                hook(cpuDbClass.getDeclaredMethod("inParticularPkgList", String::class.java)).intercept { chain ->
                    val pkg = chain.getArg(0) as? String
                    when (modeFor(pkg, BgSubsystem.CPU_LIMIT)) {
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
                when (modeFor(pkg, BgSubsystem.CPU_LIMIT)) {
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
                val pkg = chain.getArg(1) as? String
                when (modeFor(pkg, BgSubsystem.DOZE_NET)) {
                    PolicyMode.FORCE_ALLOW -> false
                    PolicyMode.FORCE_RESTRICT,
                    PolicyMode.FORCE_RESTRICT_HBG -> true
                    PolicyMode.FOLLOW_SYSTEM -> chain.proceed()
                }
            }
            hook(dbClass.getDeclaredMethod("isTrafficWhitePkg", String::class.java)).intercept { chain ->
                val pkg = chain.getArg(0) as? String
                when (modeFor(pkg, BgSubsystem.DOZE_NET)) {
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
                when (modeFor(pkg, BgSubsystem.BROADCAST_RESTRICT)) {
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
                val appPkg = (chain.getArg(0) as? ApplicationInfo)?.packageName
                val prPkg = extractProcessRecordPkg(chain.getArg(1))
                val pkg = appPkg ?: prPkg
                when (modeFor(pkg, BgSubsystem.BROADCAST_RESTRICT)) {
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
                val appPkg = (chain.getArg(2) as? ApplicationInfo)?.packageName
                val prPkg = extractProcessRecordPkg(chain.getArg(3))
                val pkg = appPkg ?: prPkg
                if (modeFor(pkg, BgSubsystem.BROADCAST_RESTRICT) == PolicyMode.FORCE_ALLOW) {
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
    }

    private fun scheduleBatteryDozeSync() {
        workerHandler.removeCallbacks(syncDozeRunnable)
        workerHandler.postDelayed(syncDozeRunnable, 350L)
    }

    private fun syncBatteryDozeState(): Int {
        val dic = deviceIdleController ?: return 0
        val currentRules = rules
        val allowPkgs = ArrayList<String>()
        val restrictPkgs = ArrayList<String>()
        for ((pkg, map) in currentRules) {
            when (map[BgSubsystem.BATTERY_DOZE]) {
                PolicyMode.FORCE_ALLOW -> allowPkgs.add(pkg)
                PolicyMode.FORCE_RESTRICT, PolicyMode.FORCE_RESTRICT_HBG -> restrictPkgs.add(pkg)
                else -> {}
            }
        }
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
            val am = resolveSystemContext()?.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (am != null) {
                runCatching {
                    val m = am.javaClass.getMethod("forceStopPackage", String::class.java)
                    m.invoke(am, pkg)
                    true
                }.getOrDefault(false)
            } else {
                false
            }
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    private fun handleRuntimeQuery(ctx: Context?, pkgName: String?): Bundle {
        val targetPkg = pkgName?.trim().takeIf { !it.isNullOrEmpty() }
        val events = synchronized(eventLock) {
            val seq = if (targetPkg != null) {
                eventBuffer.asSequence().filter { it.pkgName == targetPkg }
            } else {
                eventBuffer.asSequence()
            }
            ArrayList(seq.toList().asReversed().take(80).map { it.encode() })
        }

        val out = Bundle()
        if (targetPkg != null && ctx != null) {
            val status = buildPkgRuntimeStatus(ctx, targetPkg)
            out.putAll(status.toBundle())
        } else {
            out.putBoolean("ok", true)
        }
        out.putStringArrayList("events", events)
        return out
    }

    private fun buildPkgRuntimeStatus(ctx: Context, pkg: String): PkgRuntimeStatus {
        val uid = runCatching { ctx.packageManager.getPackageUid(pkg, 0) }.getOrNull() ?: -1

        val standbyBucket = runCatching {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            usm?.javaClass?.getMethod("getAppStandbyBucket", String::class.java)?.invoke(usm, pkg) as? Int
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

        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val runningProcs = runCatching {
            am?.runningAppProcesses
                ?.filter { it.pkgList?.contains(pkg) == true || it.processName.startsWith(pkg) }
                ?.map { "${it.processName} (pid=${it.pid})" }
                .orEmpty()
        }.getOrDefault(emptyList())

        @Suppress("DEPRECATION")
        val activeServices = runCatching {
            am?.getRunningServices(200)
                ?.filter { it.service.packageName == pkg }
                ?.map { it.service.shortClassName }
                ?.distinct()
                .orEmpty()
        }.getOrDefault(emptyList())

        return PkgRuntimeStatus(
            packageName = pkg,
            available = true,
            standbyBucket = standbyBucket,
            dozeWhitelisted = realDozeWhitelisted,
            runAnyInBgAllowed = runAnyInBgAllowed,
            runningProcesses = runningProcs,
            activeServices = activeServices,
        )
    }

    private fun recordEvent(pkg: String?, sub: BgSubsystem, action: String, detail: String = "") {
        if (pkg.isNullOrBlank()) return
        val now = System.currentTimeMillis()
        val key = "$pkg|${sub.key}|$action"
        val prev = eventCooldown[key]
        if (prev != null && now - prev < EVENT_COOLDOWN_MS) return
        eventCooldown[key] = now
        val ev = RuntimeEvent(
            timestampMs = now,
            pkgName = pkg,
            subsystemKey = sub.key,
            action = action,
            detail = detail,
        )
        synchronized(eventLock) {
            if (eventBuffer.size >= MAX_EVENTS) {
                eventBuffer.removeFirst()
            }
            eventBuffer.addLast(ev)
        }
        if (globalFlags.verboseLog) {
            log(Log.INFO, TAG, "Event: $pkg [${sub.key}] $action $detail")
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

    private fun extractProcessRecordPkg(obj: Any?): String? {
        if (obj == null) return null
        val m = processRecordAppInfoMethod ?: runCatching {
            obj.javaClass.getMethod("getApplicationInfo").also { processRecordAppInfoMethod = it }
        }.getOrNull() ?: return null
        return runCatching { (m.invoke(obj) as? ApplicationInfo)?.packageName }.getOrNull()
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

    private fun extractBroadcastFilterPkg(obj: Any?): String? {
        if (obj == null) return null
        val f = broadcastFilterPkgField ?: findDeclaredField(obj.javaClass, "packageName")?.also {
            broadcastFilterPkgField = it
        } ?: return null
        return runCatching { f.get(obj) as? String }.getOrNull()
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
