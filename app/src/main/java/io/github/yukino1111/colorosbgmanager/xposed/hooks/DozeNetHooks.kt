package io.github.yukino1111.colorosbgmanager.xposed.hooks

import io.github.yukino1111.colorosbgmanager.model.BgSubsystem
import io.github.yukino1111.colorosbgmanager.model.PolicyMode
import io.github.yukino1111.colorosbgmanager.xposed.HookHost
import io.github.yukino1111.colorosbgmanager.xposed.HookSupport
import io.github.yukino1111.colorosbgmanager.xposed.MultiUserPackageHelper

internal object DozeNetHooks {

    fun install(host: HookHost, cl: ClassLoader): Boolean {
        val scope = BgSubsystem.DOZE_NET.key
        var anyOk = false

        HookSupport.loadClassOrNull(host, cl, "com.android.server.hans.OplusHansDBConfig", scope, required = true)
            ?.let { dbClass ->
                if (HookSupport.hookSingle(
                        host = host,
                        scopeKey = scope,
                        clazz = dbClass,
                        methodName = "isRestrictedNetAppInDoze",
                        Int::class.javaPrimitiveType!!,
                        String::class.java,
                        required = true,
                    ) { chain ->
                        val uid = chain.getArg(0) as Int
                        val pkg = chain.getArg(1) as? String
                        val userId = MultiUserPackageHelper.userIdOfUid(uid)
                        when (host.modeFor(pkg, userId, BgSubsystem.DOZE_NET)) {
                            PolicyMode.FORCE_ALLOW -> false
                            PolicyMode.FORCE_RESTRICT,
                            PolicyMode.FORCE_RESTRICT_HBG -> true
                            PolicyMode.FOLLOW_SYSTEM -> chain.proceed()
                        }
                    }
                ) {
                    anyOk = true
                }
                if (HookSupport.hookPkgBool(host, dbClass, "isTrafficWhitePkg", String::class.java, sub = BgSubsystem.DOZE_NET)) {
                    anyOk = true
                }
                HookSupport.hookPkgBool(host, dbClass, "isInTcpRetransmissionWhitePkgList", String::class.java, sub = BgSubsystem.DOZE_NET)
                HookSupport.hookPkgBool(host, dbClass, "isInTcpRetransmissionBlackPkgList", String::class.java, sub = BgSubsystem.DOZE_NET, invert = true)
            }

        HookSupport.loadClassOrNull(host, cl, "com.android.server.hans.oguard.policy.AppPowerPolicy", scope, required = true)
            ?.let { policyClass ->
                if (HookSupport.hookSingle(
                        host = host,
                        scopeKey = scope,
                        clazz = policyClass,
                        methodName = "setUidFirewallRule",
                        Int::class.javaPrimitiveType!!,
                        Boolean::class.javaPrimitiveType!!,
                        required = true,
                    ) { chain ->
                        val uid = chain.getArg(0) as Int
                        val restrict = chain.getArg(1) as Boolean
                        if (restrict && host.uidHasMode(uid, BgSubsystem.DOZE_NET, PolicyMode.FORCE_ALLOW)) {
                            return@hookSingle null
                        }
                        chain.proceed()
                    }
                ) {
                    anyOk = true
                }
            }

        return anyOk
    }
}
