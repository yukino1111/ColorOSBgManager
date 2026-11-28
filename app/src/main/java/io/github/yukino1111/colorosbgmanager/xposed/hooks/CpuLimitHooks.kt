package io.github.yukino1111.colorosbgmanager.xposed.hooks

import io.github.yukino1111.colorosbgmanager.model.BgSubsystem
import io.github.yukino1111.colorosbgmanager.model.PolicyMode
import io.github.yukino1111.colorosbgmanager.xposed.HookHost
import io.github.yukino1111.colorosbgmanager.xposed.HookSupport
import io.github.yukino1111.colorosbgmanager.xposed.SystemObjectAdapters

internal object CpuLimitHooks {

    fun install(host: HookHost, cl: ClassLoader): Boolean {
        val scope = BgSubsystem.CPU_LIMIT.key
        var anyOk = false

        HookSupport.loadClassOrNull(host, cl, "com.android.server.am.OplusCpuLimitManager", scope, required = true)
            ?.let { cpuClass ->
                val prClass = HookSupport.loadClassOrNull(host, cl, "com.android.server.am.ProcessRecord", scope, required = false)
                val piClass = HookSupport.loadClassOrNull(host, cl, "com.android.server.cpulimit.ProcessInfo", scope, required = false)

                if (prClass != null) {
                    if (HookSupport.hookSingle(
                            host = host,
                            scopeKey = scope,
                            clazz = cpuClass,
                            methodName = "skipSetSchedGroup",
                            prClass,
                            String::class.java,
                            required = true,
                        ) { chain ->
                            val pr = chain.getArg(0)
                            val pkg = SystemObjectAdapters.extractProcessPkg(pr)
                            val userId = SystemObjectAdapters.extractProcessUserId(pr)
                            when (host.modeFor(pkg, userId, BgSubsystem.CPU_LIMIT)) {
                                PolicyMode.FORCE_ALLOW -> true
                                PolicyMode.FORCE_RESTRICT,
                                PolicyMode.FORCE_RESTRICT_HBG -> false
                                PolicyMode.FOLLOW_SYSTEM -> chain.proceed()
                            }
                        }
                    ) {
                        anyOk = true
                    }

                    if (HookSupport.hookSingle(
                            host = host,
                            scopeKey = scope,
                            clazz = cpuClass,
                            methodName = "processGroupCanAjustedByOcl",
                            prClass,
                            Int::class.javaPrimitiveType!!,
                            Int::class.javaPrimitiveType!!,
                            required = true,
                        ) { chain ->
                            val pr = chain.getArg(0)
                            val pkg = SystemObjectAdapters.extractProcessPkg(pr)
                            val userId = SystemObjectAdapters.extractProcessUserId(pr)
                            if (host.modeFor(pkg, userId, BgSubsystem.CPU_LIMIT) == PolicyMode.FORCE_ALLOW) {
                                return@hookSingle false
                            }
                            chain.proceed()
                        }
                    ) {
                        anyOk = true
                    }
                }

                if (piClass != null) {
                    if (HookSupport.hookSingle(
                            host = host,
                            scopeKey = scope,
                            clazz = cpuClass,
                            methodName = "adjustProcessGroup",
                            piClass,
                            Int::class.javaPrimitiveType!!,
                            String::class.java,
                            String::class.java,
                            Boolean::class.javaPrimitiveType!!,
                            required = true,
                        ) { chain ->
                            val pi = chain.getArg(0)
                            val pkg = SystemObjectAdapters.extractProcessInfoPkg(pi)
                            val userId = SystemObjectAdapters.extractProcessInfoUserId(pi)
                            if (host.modeFor(pkg, userId, BgSubsystem.CPU_LIMIT) == PolicyMode.FORCE_ALLOW) {
                                return@hookSingle null
                            }
                            chain.proceed()
                        }
                    ) {
                        anyOk = true
                    }
                }
            }

        HookSupport.loadClassOrNull(host, cl, "com.android.server.cpulimit.CpuLimitDBConfig", scope, required = false)
            ?.let { cpuDbClass ->
                if (HookSupport.hookPkgBool(host, cpuDbClass, "inCpuSetBlackList", String::class.java, sub = BgSubsystem.CPU_LIMIT, invert = true, required = true)) {
                    anyOk = true
                }
                if (HookSupport.hookSingle(
                        host = host,
                        scopeKey = scope,
                        clazz = cpuDbClass,
                        methodName = "inVipHBGList",
                        String::class.java,
                        required = true,
                    ) { chain ->
                        val pkg = chain.getArg(0) as? String
                        when (host.modeForFallback(pkg, BgSubsystem.CPU_LIMIT)) {
                            PolicyMode.FORCE_ALLOW,
                            PolicyMode.FORCE_RESTRICT_HBG -> true
                            PolicyMode.FORCE_RESTRICT -> false
                            PolicyMode.FOLLOW_SYSTEM -> chain.proceed()
                        }
                    }
                ) {
                    anyOk = true
                }
                HookSupport.hookPkgBool(host, cpuDbClass, "inParticularPkgList", String::class.java, sub = BgSubsystem.CPU_LIMIT)
            }

        HookSupport.loadClassOrNull(host, cl, "com.android.server.hans.OplusHansDBConfig", scope, required = false)
            ?.let { dbClass ->
                listOf(
                    "isInCpuCtlWhiteList",
                    "isInCpuHighLoadFreezeEnableWhiteList",
                    "isInCpuHighLoadSceneEnableWhiteList",
                ).forEach { methodName ->
                    if (HookSupport.hookPkgBool(host, dbClass, methodName, String::class.java, sub = BgSubsystem.CPU_LIMIT)) {
                        anyOk = true
                    }
                }
            }

        return anyOk
    }
}
