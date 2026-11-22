package io.github.yukino1111.colorosbgmanager.xposed

import android.app.Application
import android.content.ContentProvider
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.os.Binder
import android.os.Bundle
import android.util.Log
import io.github.yukino1111.colorosbgmanager.BuildConfig
import io.github.yukino1111.colorosbgmanager.model.BgSubsystem
import io.github.yukino1111.colorosbgmanager.model.GlobalRuleFlags
import io.github.yukino1111.colorosbgmanager.model.PolicyMode
import io.github.yukino1111.colorosbgmanager.model.RulesCodec
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

    private var remotePrefs: SharedPreferences? = null
    private val hooked = AtomicBoolean(false)
    private val settingsBridgeHooked = AtomicBoolean(false)
    private val attachWatcherHooked = AtomicBoolean(false)
    private val hookStatus = ConcurrentHashMap<String, Boolean>()
    private val uidPkgCache = ConcurrentHashMap<Int, List<String>>()

    private var getHansPkgNameMethod: Method? = null
    private var processRecordAppInfoMethod: Method? = null
    private var processInfoGetPkgMethod: Method? = null
    private var serviceRecordPkgField: Field? = null
    private var broadcastFilterPkgField: Field? = null

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
                if (method == RulesCodec.BRIDGE_METHOD) {
                    val callingUid = Binder.getCallingUid()
                    val ctx = resolveSystemContext() ?: (chain.thisObject as? ContentProvider)?.context
                    val allowedUid = runCatching {
                        ctx?.packageManager?.getPackageUid(BuildConfig.APPLICATION_ID, 0)
                    }.getOrNull()
                    if (callingUid == 1000 || callingUid == 0 || (allowedUid != null && callingUid == allowedUid)) {
                        val token = Binder.clearCallingIdentity()
                        return@intercept try {
                            SystemListParser.buildSnapshotBundle(
                                hookStatus = hookStatus,
                                systemClassLoader = systemServerClassLoader,
                                systemContext = ctx,
                            )
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
        return runCatching {
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
            true
        }.onFailure { log(Log.ERROR, TAG, "Hook AutoStart failed", it) }.getOrDefault(false)
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
                            PolicyMode.FORCE_RESTRICT_HBG -> true
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
                    PolicyMode.FORCE_RESTRICT_HBG -> true
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
                    PolicyMode.FORCE_RESTRICT_HBG -> true
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
                    PolicyMode.FORCE_RESTRICT_HBG -> true
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
            listOf(
                idleClass.getDeclaredMethod("isPowerSaveWhitelistAppInternal", String::class.java),
                idleClass.getDeclaredMethod("isPowerSaveWhitelistExceptIdleAppInternal", String::class.java),
            ).forEach { m ->
                hook(m).intercept { chain ->
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
                    PolicyMode.FORCE_ALLOW -> true
                    PolicyMode.FORCE_RESTRICT,
                    PolicyMode.FORCE_RESTRICT_HBG -> false
                    PolicyMode.FOLLOW_SYSTEM -> chain.proceed()
                }
            }

            hook(athenaClass.getDeclaredMethod("doProactiveClean", prClass, Int::class.javaPrimitiveType)).intercept { chain ->
                val pkg = extractProcessRecordPkg(chain.getArg(0))
                if (modeFor(pkg, BgSubsystem.ATHENA_RECENT) == PolicyMode.FORCE_ALLOW) {
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
                    return@intercept null
                }
                chain.proceed()
            }
            anyOk = true
        }.onFailure { log(Log.ERROR, TAG, "Hook OplusAthenaAmManager failed", it) }

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

    private fun extractProcessInfoPkg(obj: Any?): String? {
        if (obj == null) return null
        val m = processInfoGetPkgMethod ?: runCatching {
            obj.javaClass.getMethod("getPkgName").also { processInfoGetPkgMethod = it }
        }.getOrNull() ?: return null
        return runCatching { m.invoke(obj) as? String }.getOrNull()
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
    }
}
