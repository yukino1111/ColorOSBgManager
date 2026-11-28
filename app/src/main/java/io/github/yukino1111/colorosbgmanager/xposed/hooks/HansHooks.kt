package io.github.yukino1111.colorosbgmanager.xposed.hooks

import io.github.yukino1111.colorosbgmanager.model.BgSubsystem
import io.github.yukino1111.colorosbgmanager.model.PolicyMode
import io.github.yukino1111.colorosbgmanager.xposed.HookHost
import io.github.yukino1111.colorosbgmanager.xposed.HookSupport
import io.github.yukino1111.colorosbgmanager.xposed.MultiUserPackageHelper
import io.github.yukino1111.colorosbgmanager.xposed.SystemObjectAdapters

internal object HansHooks {

    fun install(host: HookHost, cl: ClassLoader): Boolean {
        val scope = BgSubsystem.HANS_FREEZE.key
        var anyOk = false
        val hansPkgClass = HookSupport.loadClassOrNull(
            host,
            cl,
            "com.android.server.hans.OplusHansPackage",
            scope,
            required = true,
        ) ?: return false

        HookSupport.loadClassOrNull(host, cl, "com.android.server.hans.OplusHansImportance", scope, required = true)
            ?.let { impClass ->
                val overloads = listOf(
                    arrayOf(hansPkgClass, Int::class.javaPrimitiveType!!),
                    arrayOf(hansPkgClass, Int::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!),
                    arrayOf(hansPkgClass, Int::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!, String::class.java),
                )
                overloads.forEachIndexed { idx, params ->
                    if (HookSupport.hookSingle(
                            host = host,
                            scopeKey = scope,
                            clazz = impClass,
                            methodName = "isImportantPkg",
                            paramTypes = params,
                            required = idx == 0,
                            targetLabel = "OplusHansImportance#isImportantPkg(${params.size})",
                        ) { chain ->
                            val hansPkg = chain.getArg(0)
                            val pkg = SystemObjectAdapters.extractHansPkgName(hansPkg)
                            val userId = SystemObjectAdapters.extractHansUserId(hansPkg)
                            if (host.modeFor(pkg, userId, BgSubsystem.HANS_FREEZE) == PolicyMode.FORCE_ALLOW) {
                                return@hookSingle true
                            }
                            chain.proceed()
                        }
                    ) {
                        anyOk = true
                    }
                }
            }

        HookSupport.loadClassOrNull(host, cl, "com.android.server.hans.freeze.HansCGroup", scope, required = true)
            ?.let { cgroupClass ->
                if (HookSupport.hookSingle(
                        host = host,
                        scopeKey = scope,
                        clazz = cgroupClass,
                        methodName = "hansFreezeLocked",
                        hansPkgClass,
                        String::class.java,
                        required = true,
                    ) { chain ->
                        val hansPkg = chain.getArg(0)
                        val pkg = SystemObjectAdapters.extractHansPkgName(hansPkg)
                        val userId = SystemObjectAdapters.extractHansUserId(hansPkg)
                        if (host.modeFor(pkg, userId, BgSubsystem.HANS_FREEZE) == PolicyMode.FORCE_ALLOW) {
                            return@hookSingle false
                        }
                        chain.proceed()
                    }
                ) {
                    anyOk = true
                }
            }

        HookSupport.loadClassOrNull(host, cl, "com.android.server.hans.OplusHansRestriction", scope, required = true)
            ?.let { restrictClass ->
                if (HookSupport.hookSingle(
                        host = host,
                        scopeKey = scope,
                        clazz = restrictClass,
                        methodName = "isBlockedForStrictMode",
                        Int::class.javaPrimitiveType!!,
                        Int::class.javaPrimitiveType!!,
                        String::class.java,
                        Int::class.javaPrimitiveType!!,
                        String::class.java,
                        Int::class.javaPrimitiveType!!,
                        Int::class.javaPrimitiveType!!,
                        Boolean::class.javaPrimitiveType!!,
                        required = true,
                    ) { chain ->
                        val uid = chain.getArg(0) as Int
                        val pkg = chain.getArg(4) as? String
                        val userId = MultiUserPackageHelper.userIdOfUid(uid)
                        if (host.globalFlags.disableStrictModeFreeze ||
                            host.modeFor(pkg, userId, BgSubsystem.HANS_FREEZE) == PolicyMode.FORCE_ALLOW
                        ) {
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
                if (HookSupport.hookSingle(
                        host = host,
                        scopeKey = scope,
                        clazz = dbClass,
                        methodName = "isHansWhitelistApp",
                        Int::class.javaPrimitiveType!!,
                        required = true,
                    ) { chain ->
                        val uid = chain.getArg(0) as Int
                        when {
                            host.uidHasMode(uid, BgSubsystem.HANS_FREEZE, PolicyMode.FORCE_ALLOW) -> true
                            host.uidHasMode(uid, BgSubsystem.HANS_FREEZE, PolicyMode.FORCE_RESTRICT) ||
                                host.uidHasMode(uid, BgSubsystem.HANS_FREEZE, PolicyMode.FORCE_RESTRICT_HBG) -> false
                            else -> chain.proceed()
                        }
                    }
                ) {
                    anyOk = true
                }

                if (HookSupport.hookPkgBool(host, dbClass, "isProtectApp", String::class.java, sub = BgSubsystem.HANS_FREEZE)) {
                    anyOk = true
                }
                if (HookSupport.hookPkgBool(host, dbClass, "isBlackHansApp", String::class.java, sub = BgSubsystem.HANS_FREEZE, invert = true)) {
                    anyOk = true
                }
                HookSupport.hookSingle(
                    host = host,
                    scopeKey = scope,
                    clazz = dbClass,
                    methodName = "isSysBlackApp",
                    Int::class.javaPrimitiveType!!,
                    required = false,
                ) { chain ->
                    val uid = chain.getArg(0) as Int
                    when {
                        host.uidHasMode(uid, BgSubsystem.HANS_FREEZE, PolicyMode.FORCE_ALLOW) -> false
                        host.uidHasMode(uid, BgSubsystem.HANS_FREEZE, PolicyMode.FORCE_RESTRICT) ||
                            host.uidHasMode(uid, BgSubsystem.HANS_FREEZE, PolicyMode.FORCE_RESTRICT_HBG) -> true
                        else -> chain.proceed()
                    }
                }
                listOf(
                    "inAndroidFreezeWhiteList",
                    "inAndroidFreezeKillWhiteList",
                    "inAndroidFreezeCheckLockWhiteList",
                    "inAsyncBinderFreezeWhiteList",
                    "inUidIdleWhiteList",
                ).forEach { methodName ->
                    HookSupport.hookPkgBool(host, dbClass, methodName, String::class.java, String::class.java, sub = BgSubsystem.HANS_FREEZE)
                }
                listOf(
                    "inFastFreezeWhiteList",
                    "inSysBlackFgsExempt",
                    "isWhiteAppIfTypeIgnored",
                ).forEach { methodName ->
                    HookSupport.hookPkgBool(host, dbClass, methodName, String::class.java, sub = BgSubsystem.HANS_FREEZE)
                }
                HookSupport.hookPkgBool(host, dbClass, "isSysBlackConfigApp", String::class.java, sub = BgSubsystem.HANS_FREEZE, invert = true)
            }

        return anyOk
    }
}
