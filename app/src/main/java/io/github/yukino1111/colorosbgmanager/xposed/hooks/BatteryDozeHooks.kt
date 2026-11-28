package io.github.yukino1111.colorosbgmanager.xposed.hooks

import android.util.ArrayMap
import android.util.Log
import android.util.SparseBooleanArray
import io.github.yukino1111.colorosbgmanager.model.BgSubsystem
import io.github.yukino1111.colorosbgmanager.model.HookDiagnosticEntry
import io.github.yukino1111.colorosbgmanager.model.HookPointStatus
import io.github.yukino1111.colorosbgmanager.model.PolicyMode
import io.github.yukino1111.colorosbgmanager.xposed.HookHost
import io.github.yukino1111.colorosbgmanager.xposed.HookSupport
import io.github.yukino1111.colorosbgmanager.xposed.MultiUserPackageHelper
import io.github.yukino1111.colorosbgmanager.xposed.SystemObjectAdapters

internal object BatteryDozeHooks {

    private const val STANDBY_BUCKET_EXEMPTED = 5
    private const val STANDBY_BUCKET_RARE = 40

    fun install(host: HookHost, cl: ClassLoader): Boolean {
        val scope = BgSubsystem.BATTERY_DOZE.key
        var anyOk = false
        var appIdBuilderHooked = false

        HookSupport.loadClassOrNull(host, cl, "com.android.server.hans.oguard.policy.AppPowerPolicy", scope, required = true)
            ?.let { policyClass ->
                listOf("tryKillApp", "tryForceStopApp", "tryRemindApp").forEach { methodName ->
                    if (HookSupport.hookSingle(
                            host = host,
                            scopeKey = scope,
                            clazz = policyClass,
                            methodName = methodName,
                            Int::class.javaPrimitiveType!!,
                            String::class.java,
                            String::class.java,
                            required = true,
                        ) { chain ->
                            val uid = chain.getArg(0) as Int
                            val userId = MultiUserPackageHelper.userIdOfUid(uid)
                            val pkg = chain.getArg(1) as? String
                            if (host.modeFor(pkg, userId, BgSubsystem.BATTERY_DOZE) == PolicyMode.FORCE_ALLOW) {
                                host.recordEvent(pkg, BgSubsystem.BATTERY_DOZE, "skip_oguard", methodName, userId)
                                return@hookSingle false
                            }
                            chain.proceed()
                        }
                    ) {
                        anyOk = true
                    }
                }

                HookSupport.hookSingle(
                    host = host,
                    scopeKey = scope,
                    clazz = policyClass,
                    methodName = "tryKillAbnormalAudioApp",
                    Int::class.javaPrimitiveType!!,
                    String::class.java,
                    String::class.java,
                    required = false,
                ) { chain ->
                    val uid = chain.getArg(0) as Int
                    val userId = MultiUserPackageHelper.userIdOfUid(uid)
                    val pkg = chain.getArg(1) as? String
                    if (host.modeFor(pkg, userId, BgSubsystem.BATTERY_DOZE) == PolicyMode.FORCE_ALLOW) {
                        host.recordEvent(pkg, BgSubsystem.BATTERY_DOZE, "skip_oguard", "tryKillAbnormalAudioApp", userId)
                        return@hookSingle false
                    }
                    chain.proceed()
                }

                if (HookSupport.hookSingle(
                        host = host,
                        scopeKey = scope,
                        clazz = policyClass,
                        methodName = "requestKillApp",
                        String::class.java,
                        String::class.java,
                        String::class.java,
                        String::class.java,
                        Int::class.javaPrimitiveType!!,
                        String::class.java,
                        required = true,
                    ) { chain ->
                        val pkg = chain.getArg(0) as? String
                        if (host.modeForFallback(pkg, BgSubsystem.BATTERY_DOZE) == PolicyMode.FORCE_ALLOW) {
                            host.recordEvent(pkg, BgSubsystem.BATTERY_DOZE, "skip_oguard", "requestKillApp")
                            return@hookSingle false
                        }
                        chain.proceed()
                    }
                ) {
                    anyOk = true
                }
            }

        HookSupport.loadClassOrNull(host, cl, "com.android.server.hans.OplusHansDBConfig", scope, required = false)
            ?.let { dbClass ->
                if (HookSupport.hookPkgBool(host, dbClass, "isActiveUpdateDozeWhiteList", String::class.java, sub = BgSubsystem.BATTERY_DOZE)) {
                    anyOk = true
                }
                if (HookSupport.hookPkgBool(
                        host,
                        dbClass,
                        "isInAlarmWhiteList",
                        String::class.java,
                        String::class.java,
                        Boolean::class.javaPrimitiveType!!,
                        sub = BgSubsystem.BATTERY_DOZE,
                    )
                ) {
                    anyOk = true
                }
            }

        HookSupport.loadClassOrNull(host, cl, "com.android.server.hans.oguard.OGuardDBConfig", scope, required = false)
            ?.let { oguardDbClass ->
                if (HookSupport.hookPkgBool(
                        host,
                        oguardDbClass,
                        "inThirdAppWhiteList",
                        String::class.java,
                        Int::class.javaObjectType,
                        sub = BgSubsystem.BATTERY_DOZE,
                    )
                ) {
                    anyOk = true
                }
                HookSupport.hookPkgBool(
                    host,
                    oguardDbClass,
                    "inThirdAppBlackList",
                    String::class.java,
                    Int::class.javaObjectType,
                    sub = BgSubsystem.BATTERY_DOZE,
                    invert = true,
                )
                HookSupport.hookPkgBool(host, oguardDbClass, "isSpecApp", String::class.java, sub = BgSubsystem.BATTERY_DOZE)
                HookSupport.hookPkgBool(host, oguardDbClass, "isSpecBlackApp", String::class.java, sub = BgSubsystem.BATTERY_DOZE, invert = true)
                HookSupport.hookPkgBool(host, oguardDbClass, "isInGlobalBlackAppList", String::class.java, sub = BgSubsystem.BATTERY_DOZE, invert = true)
                HookSupport.hookPkgBool(host, oguardDbClass, "isHighAuthorityPkg", String::class.java, sub = BgSubsystem.BATTERY_DOZE)
            }

        HookSupport.loadClassOrNull(host, cl, "com.android.server.DeviceIdleController", scope, required = true)
            ?.let { idleClass ->
                if (HookSupport.hookSingle(
                        host = host,
                        scopeKey = scope,
                        clazz = idleClass,
                        methodName = "onBootPhase",
                        Int::class.javaPrimitiveType!!,
                        required = true,
                    ) { chain ->
                        host.deviceIdleController = chain.thisObject
                        val result = chain.proceed()
                        val phase = chain.getArg(0) as Int
                        if (phase >= 1000) {
                            host.bootCompleted = true
                            host.scheduleBatteryDozeSync()
                            host.registerScreenOffReceiver()
                        }
                        result
                    }
                ) {
                    anyOk = true
                }

                listOf(
                    "isPowerSaveWhitelistAppInternal",
                    "isPowerSaveWhitelistExceptIdleAppInternal",
                ).forEach { methodName ->
                    if (HookSupport.hookSingle(
                            host = host,
                            scopeKey = scope,
                            clazz = idleClass,
                            methodName = methodName,
                            String::class.java,
                            required = true,
                        ) { chain ->
                            if (host.deviceIdleController == null) host.deviceIdleController = chain.thisObject
                            val pkg = chain.getArg(0) as? String
                            when (host.modeForFallback(pkg, BgSubsystem.BATTERY_DOZE)) {
                                PolicyMode.FORCE_ALLOW -> true
                                PolicyMode.FORCE_RESTRICT,
                                PolicyMode.FORCE_RESTRICT_HBG -> false
                                PolicyMode.FOLLOW_SYSTEM -> chain.proceed()
                            }
                        }
                    ) {
                        anyOk = true
                    }
                }

                listOf(
                    "isAppOnWhitelistInternal" to true,
                    "isAppOnExceptIdleWhitelistInternal" to false,
                ).forEach { (methodName, required) ->
                    if (HookSupport.hookSingle(
                            host = host,
                            scopeKey = scope,
                            clazz = idleClass,
                            methodName = methodName,
                            Int::class.javaPrimitiveType!!,
                            required = required,
                        ) { chain ->
                            if (host.deviceIdleController == null) host.deviceIdleController = chain.thisObject
                            val appId = chain.getArg(0) as Int
                            when (host.resolveDozeModeForAppId(appId)) {
                                PolicyMode.FORCE_ALLOW -> true
                                PolicyMode.FORCE_RESTRICT,
                                PolicyMode.FORCE_RESTRICT_HBG -> false
                                PolicyMode.FOLLOW_SYSTEM -> chain.proceed()
                            }
                        }
                    ) {
                        anyOk = true
                    }
                }

                val appIdGetterGroups = listOf(
                    "DeviceIdleController#getAppIdWhitelist*" to setOf("getAppIdWhitelistInternal", "getAppIdWhitelist"),
                    "DeviceIdleController#getAppIdWhitelistExceptIdle*" to setOf("getAppIdWhitelistExceptIdleInternal", "getAppIdWhitelistExceptIdle"),
                    "DeviceIdleController#getAppIdUserWhitelist*" to setOf("getAppIdUserWhitelistInternal", "getAppIdUserWhitelist"),
                )
                appIdGetterGroups.forEach { (label, names) ->
                    if (HookSupport.hookAllMatching(
                            host = host,
                            scopeKey = scope,
                            clazz = idleClass,
                            methodName = names.first(),
                            required = true,
                            targetLabel = label,
                            predicate = {
                                it.name in names && it.parameterTypes.isEmpty() && it.returnType == IntArray::class.java
                            },
                        ) { _, chain ->
                            val orig = chain.proceed() as? IntArray ?: return@hookAllMatching null
                            host.adjustDozeAppIds(orig)
                        }
                    ) {
                        anyOk = true
                    }
                }

                val whitelistPkgMethods = setOf(
                    "getFullPowerWhitelistInternal",
                    "getFullPowerWhitelistInternalUnchecked",
                    "getFullPowerWhitelistExceptIdleInternal",
                    "getUserPowerWhitelistInternal",
                )
                val packageManagerField = SystemObjectAdapters.findDeclaredField(idleClass, "mPackageManagerInternal")
                val visibilityFilter = packageManagerField?.let {
                    SystemObjectAdapters.findDeclaredMethod(
                        it.type, "filterAppAccess", String::class.java,
                        Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
                    )
                }
                if (HookSupport.hookAllMatching(
                        host = host,
                        scopeKey = scope,
                        clazz = idleClass,
                        methodName = "getFullPowerWhitelistInternal",
                        required = true,
                        targetLabel = "DeviceIdleController#getPowerWhitelistStrings",
                        predicate = {
                            val params = it.parameterTypes
                            it.name in whitelistPkgMethods && it.returnType == Array<String>::class.java &&
                                (params.isEmpty() || (
                                    params.size == 2 && params[0] == Int::class.javaPrimitiveType &&
                                        params[1] == Int::class.javaPrimitiveType
                                    ))
                        },
                    ) { method, chain ->
                        val orig = chain.proceed() as? Array<*> ?: return@hookAllMatching null
                        if (method.parameterCount == 0) {
                            host.adjustDozePackages(orig)
                        } else {
                            val callingUid = chain.getArg(0) as Int
                            val callingUserId = chain.getArg(1) as Int
                            val pm = runCatching { packageManagerField?.get(chain.thisObject) }.getOrNull()
                            // Do not add a name the ROM filtered for this caller.
                            host.adjustDozePackages(orig) { pkg ->
                                pkg in orig || (pm != null && visibilityFilter != null && runCatching {
                                    visibilityFilter.invoke(pm, pkg, callingUid, callingUserId) == false
                                }.getOrDefault(false))
                            }
                        }
                    }
                ) {
                    anyOk = true
                }

                // Newer ROMs publish the arrays to ActivityManager and standby before update returns.
                appIdBuilderHooked = HookSupport.hookSingle(
                    host, scope, idleClass, "buildAppIdArray",
                    ArrayMap::class.java, ArrayMap::class.java, SparseBooleanArray::class.java,
                    required = false,
                ) { chain ->
                    val orig = chain.proceed() as? IntArray ?: return@hookSingle null
                    host.adjustDozeAppIds(orig)
                }
                if (appIdBuilderHooked) anyOk = true

                if (HookSupport.hookSingle(
                        host = host,
                        scopeKey = scope,
                        clazz = idleClass,
                        methodName = "updateWhitelistAppIdsLocked",
                        required = true,
                    ) { chain ->
                        if (host.deviceIdleController == null) host.deviceIdleController = chain.thisObject
                        val result = chain.proceed()
                        if (!appIdBuilderHooked) applyInMemoryDozeAppIds(host, chain.thisObject)
                        result
                    }
                ) {
                    anyOk = true
                }
            }

        if (!appIdBuilderHooked) {
            HookSupport.loadClassOrNull(host, cl, "com.android.server.power.PowerManagerService\$LocalService", scope, required = false)
                ?.let { pmsLocalClass ->
                    val ok = HookSupport.hookSingle(
                        host = host,
                        scopeKey = scope,
                        clazz = pmsLocalClass,
                        methodName = "setDeviceIdleWhitelist",
                        IntArray::class.java,
                        required = false,
                    ) { chain ->
                        val orig = chain.getArg(0) as? IntArray
                        if (orig != null) {
                            chain.proceed(arrayOf(host.adjustDozeAppIds(orig)))
                        } else {
                            chain.proceed()
                        }
                    }
                    host.powerWhitelistHooked = ok
                }
        }

        HookSupport.loadClassOrNull(host, cl, "com.android.server.usage.AppStandbyController", scope, required = false)
            ?.let { standbyClass ->
                if (HookSupport.hookSingle(
                        host = host,
                        scopeKey = scope,
                        clazz = standbyClass,
                        methodName = "onBootPhase",
                        Int::class.javaPrimitiveType!!,
                        required = false,
                    ) { chain ->
                        host.appStandbyController = chain.thisObject
                        chain.proceed()
                    }
                ) {
                    anyOk = true
                }

                if (HookSupport.hookSingle(
                        host = host,
                        scopeKey = scope,
                        clazz = standbyClass,
                        methodName = "getAppStandbyBucket",
                        String::class.java,
                        Int::class.javaPrimitiveType!!,
                        Long::class.javaPrimitiveType!!,
                        Boolean::class.javaPrimitiveType!!,
                        required = true,
                    ) { chain ->
                        if (host.appStandbyController == null) host.appStandbyController = chain.thisObject
                        val pkg = chain.getArg(0) as? String
                        val userId = chain.getArg(1) as Int
                        when (host.modeFor(pkg, userId, BgSubsystem.BATTERY_DOZE)) {
                            PolicyMode.FORCE_ALLOW -> STANDBY_BUCKET_EXEMPTED
                            PolicyMode.FORCE_RESTRICT,
                            PolicyMode.FORCE_RESTRICT_HBG -> {
                                val orig = chain.proceed() as Int
                                if (orig < STANDBY_BUCKET_RARE) STANDBY_BUCKET_RARE else orig
                            }
                            PolicyMode.FOLLOW_SYSTEM -> chain.proceed()
                        }
                    }
                ) {
                    anyOk = true
                }

                if (HookSupport.hookAllMatching(
                        host = host,
                        scopeKey = scope,
                        clazz = standbyClass,
                        methodName = "isAppIdleFiltered",
                        required = true,
                        predicate = { m ->
                            val params = m.parameterTypes
                            m.name == "isAppIdleFiltered" && m.returnType == Boolean::class.javaPrimitiveType &&
                                params.size == 4 && params[0] == String::class.java &&
                                params[1] == Int::class.javaPrimitiveType &&
                                ((params[2] == Int::class.javaPrimitiveType && params[3] == Long::class.javaPrimitiveType) ||
                                    (params[2] == Long::class.javaPrimitiveType && params[3] == Boolean::class.javaPrimitiveType))
                        },
                    ) { method, chain ->
                        val pkg = chain.getArg(0) as? String
                        val userArg = if (method.parameterTypes[2] == Int::class.javaPrimitiveType) 2 else 1
                        val userId = chain.getArg(userArg) as Int
                        when (host.modeFor(pkg, userId, BgSubsystem.BATTERY_DOZE)) {
                            PolicyMode.FORCE_ALLOW -> false
                            PolicyMode.FORCE_RESTRICT,
                            PolicyMode.FORCE_RESTRICT_HBG -> true
                            PolicyMode.FOLLOW_SYSTEM -> chain.proceed()
                        }
                    }
                ) {
                    anyOk = true
                }
            }

        HookSupport.loadClassOrNull(host, cl, "com.android.server.OplusGuardElfConfigUtil", scope, required = false)
            ?.let { guardUtilClass ->
                if (HookSupport.hookSingle(
                        host = host,
                        scopeKey = scope,
                        clazz = guardUtilClass,
                        methodName = "getAppCpuKillSwitch",
                        required = false,
                    ) { chain ->
                        if (host.globalFlags.disableGuardElfCpuKill) false else chain.proceed()
                    }
                ) {
                    anyOk = true
                }
            }

        return anyOk
    }

    fun applyInMemoryDozeAppIds(host: HookHost, dic: Any?) {
        if (dic == null) return
        val groups = listOf(
            "all" to listOf("mPowerSaveWhitelistAllAppIdArray", "mPowerSaveWhitelistAllAppIds"),
            "exceptIdle" to listOf("mPowerSaveWhitelistExceptIdleAppIdArray", "mPowerSaveWhitelistExceptIdleAppIds"),
            "user" to listOf("mPowerSaveWhitelistUserAppIdArray", "mPowerSaveWhitelistUserAppIds"),
        )
        var adjustedAll: IntArray? = null
        for ((tag, candidates) in groups) {
            for (name in candidates) {
                val field = SystemObjectAdapters.findDeclaredField(dic.javaClass, name) ?: continue
                val orig = runCatching { field.get(dic) as? IntArray }.getOrNull() ?: continue
                val adjusted = host.adjustDozeAppIds(orig)
                runCatching { field.set(dic, adjusted) }
                if (tag == "all") {
                    adjustedAll = adjusted
                }
                break
            }
        }
        if (adjustedAll == null) {
            host.recordDiagnostic(
                HookDiagnosticEntry(
                    scopeKey = BgSubsystem.BATTERY_DOZE.key,
                    target = "DeviceIdleController#mPowerSaveWhitelistAllAppIdArray",
                    required = false,
                    status = HookPointStatus.MISSING_METHOD,
                    detail = "IntArray field not found on ${dic.javaClass.simpleName}",
                ),
            )
            host.logMsg(Log.WARN, "No IntArray power-save whitelist field found on ${dic.javaClass.name}")
        }
        if (!host.powerWhitelistHooked && adjustedAll != null) {
            runCatching {
                val pmi = SystemObjectAdapters.findDeclaredField(dic.javaClass, "mLocalPowerManager")?.get(dic)
                    ?: return@runCatching
                val setter = SystemObjectAdapters.findDeclaredMethod(pmi.javaClass, "setDeviceIdleWhitelist", IntArray::class.java)
                    ?: pmi.javaClass.getMethod("setDeviceIdleWhitelist", IntArray::class.java)
                setter.invoke(pmi, adjustedAll)
            }
        }
    }

    fun isDeviceDozeWhitelisted(dic: Any, pkg: String): Boolean = runCatching {
        val userMap = SystemObjectAdapters.findDeclaredField(dic.javaClass, "mPowerSaveWhitelistUserApps")?.get(dic) as? Map<*, *>
        val sysMap = (
            SystemObjectAdapters.findDeclaredField(dic.javaClass, "mPowerSaveWhitelistSystemApps")
                ?: SystemObjectAdapters.findDeclaredField(dic.javaClass, "mPowerSaveWhitelistApps")
            )?.get(dic) as? Map<*, *>
        (userMap?.containsKey(pkg) == true) || (sysMap?.containsKey(pkg) == true)
    }.getOrDefault(false)
}
