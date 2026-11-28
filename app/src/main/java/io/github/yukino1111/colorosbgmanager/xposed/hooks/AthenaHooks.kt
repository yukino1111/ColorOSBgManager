package io.github.yukino1111.colorosbgmanager.xposed.hooks

import android.content.Context
import io.github.yukino1111.colorosbgmanager.model.BgSubsystem
import io.github.yukino1111.colorosbgmanager.model.PolicyMode
import io.github.yukino1111.colorosbgmanager.xposed.HookHost
import io.github.yukino1111.colorosbgmanager.xposed.HookSupport
import io.github.yukino1111.colorosbgmanager.xposed.SystemObjectAdapters

internal object AthenaHooks {

    private const val SKIP_REASON_ALLOW = "bgmanager-allow"

    fun install(host: HookHost, cl: ClassLoader, removeTaskDepth: ThreadLocal<Int>): Boolean {
        val scope = BgSubsystem.ATHENA_RECENT.key
        var anyOk = false

        HookSupport.loadClassOrNull(host, cl, "com.android.server.am.OplusAthenaAmManager", scope, required = true)
            ?.let { athenaClass ->
                val prClass = HookSupport.loadClassOrNull(host, cl, "com.android.server.am.ProcessRecord", scope, required = false)

                if (HookSupport.hookAllMatching(
                        host = host,
                        scopeKey = scope,
                        clazz = athenaClass,
                        methodName = "skipCacheEmptyKill",
                        required = true,
                        predicate = { it.name == "skipCacheEmptyKill" && it.parameterTypes.size == 1 },
                    ) { _, chain ->
                        val pr = chain.getArg(0)
                        val pkg = SystemObjectAdapters.extractProcessPkg(pr)
                        val userId = SystemObjectAdapters.extractProcessUserId(pr)
                        when (host.modeFor(pkg, userId, BgSubsystem.ATHENA_RECENT)) {
                            PolicyMode.FORCE_ALLOW -> {
                                host.recordEvent(pkg, BgSubsystem.ATHENA_RECENT, "skip_athena_clean", "skipCacheEmptyKill", userId)
                                true
                            }
                            PolicyMode.FORCE_RESTRICT,
                            PolicyMode.FORCE_RESTRICT_HBG -> false
                            PolicyMode.FOLLOW_SYSTEM -> chain.proceed()
                        }
                    }
                ) {
                    anyOk = true
                }

                if (prClass != null) {
                    if (HookSupport.hookSingle(
                            host = host,
                            scopeKey = scope,
                            clazz = athenaClass,
                            methodName = "doProactiveClean",
                            prClass,
                            Int::class.javaPrimitiveType!!,
                            required = true,
                        ) { chain ->
                            val pr = chain.getArg(0)
                            val pkg = SystemObjectAdapters.extractProcessPkg(pr)
                            val userId = SystemObjectAdapters.extractProcessUserId(pr)
                            if (host.modeFor(pkg, userId, BgSubsystem.ATHENA_RECENT) == PolicyMode.FORCE_ALLOW) {
                                host.recordEvent(pkg, BgSubsystem.ATHENA_RECENT, "skip_athena_clean", "doProactiveClean", userId)
                                return@hookSingle null
                            }
                            chain.proceed()
                        }
                    ) {
                        anyOk = true
                    }
                }

                if (HookSupport.hookSingle(
                        host = host,
                        scopeKey = scope,
                        clazz = athenaClass,
                        methodName = "forceStopPackage",
                        String::class.java,
                        Int::class.javaPrimitiveType!!,
                        Int::class.javaPrimitiveType!!,
                        Int::class.javaPrimitiveType!!,
                        String::class.java,
                        String::class.java,
                        required = true,
                    ) { chain ->
                        val pkg = chain.getArg(0) as? String
                        val userId = chain.getArg(1) as Int
                        if (host.modeFor(pkg, userId, BgSubsystem.ATHENA_RECENT) == PolicyMode.FORCE_ALLOW) {
                            host.recordEvent(
                                pkg,
                                BgSubsystem.ATHENA_RECENT,
                                "skip_athena_forcestop",
                                chain.getArg(4) as? String ?: "",
                                userId,
                            )
                            return@hookSingle null
                        }
                        chain.proceed()
                    }
                ) {
                    anyOk = true
                }

                HookSupport.hookPkgBool(
                    host,
                    athenaClass,
                    "isAuthScopeProtectProc",
                    String::class.java,
                    String::class.java,
                    sub = BgSubsystem.ATHENA_RECENT,
                )
                HookSupport.hookPkgBool(
                    host,
                    athenaClass,
                    "isShouldForbidRestartForClear",
                    String::class.java,
                    Int::class.javaPrimitiveType!!,
                    sub = BgSubsystem.ATHENA_RECENT,
                    invert = true,
                    userIdArgIdx = 1,
                    restrictEventAction = "forbid_clear_restart",
                )
            }

        HookSupport.loadClassOrNull(host, cl, "com.android.server.am.OplusOsenseKillAction", scope, required = true)
            ?.let { osenseKillClass ->
                val killCtxClass = HookSupport.loadClassOrNull(host, cl, "com.android.server.am.OplusOsenseKillAction\$KillContext", scope, required = false)
                val piClass = HookSupport.loadClassOrNull(host, cl, "com.android.server.am.ProcessInfo", scope, required = false)
                val pgClass = HookSupport.loadClassOrNull(host, cl, "com.android.server.am.ProcessGroup", scope, required = false)
                val prClass = HookSupport.loadClassOrNull(host, cl, "com.android.server.am.ProcessRecord", scope, required = false)
                val gameExtClass = HookSupport.loadClassOrNull(host, cl, "com.android.server.app.IGameManagerServiceExt", scope, required = false)

                if (killCtxClass != null && piClass != null) {
                    if (HookSupport.hookSingle(
                            host = host,
                            scopeKey = scope,
                            clazz = osenseKillClass,
                            methodName = "getCchEmptyCheckSkipReason",
                            killCtxClass,
                            piClass,
                            Long::class.javaPrimitiveType!!,
                            Set::class.java,
                            Set::class.java,
                            required = true,
                        ) { chain ->
                            val procInfo = chain.getArg(1)
                            val pkg = SystemObjectAdapters.extractOsenseProcInfoPkg(procInfo)
                            val userId = SystemObjectAdapters.extractOsenseProcInfoUserId(procInfo)
                            if (host.modeFor(pkg, userId, BgSubsystem.ATHENA_RECENT) == PolicyMode.FORCE_ALLOW) {
                                SystemObjectAdapters.setOsenseSkipReason(procInfo, SKIP_REASON_ALLOW)
                                host.recordEvent(pkg, BgSubsystem.ATHENA_RECENT, "skip_nirvana", "cch-empty", userId)
                                return@hookSingle SKIP_REASON_ALLOW
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
                            clazz = osenseKillClass,
                            methodName = "getNonUiCheckSkipReason",
                            piClass,
                            required = true,
                        ) { chain ->
                            val procInfo = chain.getArg(0)
                            val pkg = SystemObjectAdapters.extractOsenseProcInfoPkg(procInfo)
                            val userId = SystemObjectAdapters.extractOsenseProcInfoUserId(procInfo)
                            if (host.modeFor(pkg, userId, BgSubsystem.ATHENA_RECENT) == PolicyMode.FORCE_ALLOW) {
                                SystemObjectAdapters.setOsenseSkipReason(procInfo, SKIP_REASON_ALLOW)
                                host.recordEvent(pkg, BgSubsystem.ATHENA_RECENT, "skip_nirvana", "non-ui", userId)
                                return@hookSingle SKIP_REASON_ALLOW
                            }
                            chain.proceed()
                        }
                    ) {
                        anyOk = true
                    }
                }

                if (killCtxClass != null && pgClass != null && gameExtClass != null) {
                    if (HookSupport.hookSingle(
                            host = host,
                            scopeKey = scope,
                            clazz = osenseKillClass,
                            methodName = "getAllProcCheckSkipType",
                            killCtxClass,
                            pgClass,
                            gameExtClass,
                            Long::class.javaPrimitiveType!!,
                            required = true,
                        ) { chain ->
                            val group = chain.getArg(1)
                            val pkg = SystemObjectAdapters.extractOsenseGroupPkg(group)
                            val userId = SystemObjectAdapters.extractOsenseGroupUserId(group)
                            if (host.modeFor(pkg, userId, BgSubsystem.ATHENA_RECENT) == PolicyMode.FORCE_ALLOW) {
                                SystemObjectAdapters.setOsenseGroupSkipReason(group, SKIP_REASON_ALLOW)
                                host.recordEvent(pkg, BgSubsystem.ATHENA_RECENT, "skip_nirvana", "group-clean", userId)
                                return@hookSingle SKIP_REASON_ALLOW
                            }
                            chain.proceed()
                        }
                    ) {
                        anyOk = true
                    }
                }

                if (killCtxClass != null && pgClass != null) {
                    if (HookSupport.hookSingle(
                            host = host,
                            scopeKey = scope,
                            clazz = osenseKillClass,
                            methodName = "getAllProcCheckSkipTypeForRecentBgApp",
                            killCtxClass,
                            pgClass,
                            required = true,
                        ) { chain ->
                            val group = chain.getArg(1)
                            val pkg = SystemObjectAdapters.extractOsenseGroupPkg(group)
                            val userId = SystemObjectAdapters.extractOsenseGroupUserId(group)
                            if (host.modeFor(pkg, userId, BgSubsystem.ATHENA_RECENT) == PolicyMode.FORCE_ALLOW) {
                                SystemObjectAdapters.setOsenseGroupSkipReason(group, SKIP_REASON_ALLOW)
                                host.recordEvent(pkg, BgSubsystem.ATHENA_RECENT, "skip_nirvana", "recent-bg", userId)
                                return@hookSingle SKIP_REASON_ALLOW
                            }
                            chain.proceed()
                        }
                    ) {
                        anyOk = true
                    }
                }

                if (prClass != null) {
                    if (HookSupport.hookSingle(
                            host = host,
                            scopeKey = scope,
                            clazz = osenseKillClass,
                            methodName = "killLocked",
                            prClass,
                            String::class.java,
                            String::class.java,
                            Int::class.javaPrimitiveType!!,
                            Int::class.javaPrimitiveType!!,
                            Boolean::class.javaPrimitiveType!!,
                            required = true,
                        ) { chain ->
                            val pr = chain.getArg(0)
                            val pkg = SystemObjectAdapters.extractProcessPkg(pr)
                            val userId = SystemObjectAdapters.extractProcessUserId(pr)
                            if (host.modeFor(pkg, userId, BgSubsystem.ATHENA_RECENT) == PolicyMode.FORCE_ALLOW) {
                                val reason = (chain.getArg(2) as? String) ?: (chain.getArg(1) as? String) ?: "osense"
                                host.recordEvent(pkg, BgSubsystem.ATHENA_RECENT, "skip_okill", reason, userId)
                                return@hookSingle null
                            }
                            chain.proceed()
                        }
                    ) {
                        anyOk = true
                    }
                }
            }

        HookSupport.loadClassOrNull(host, cl, "com.android.server.am.OplusHansManager", scope, required = false)
            ?.let { hansMgrClass ->
                if (HookSupport.hookSingle(
                        host = host,
                        scopeKey = scope,
                        clazz = hansMgrClass,
                        methodName = "handleRemoveTask",
                        Boolean::class.javaPrimitiveType!!,
                        Int::class.javaPrimitiveType!!,
                        String::class.java,
                        required = true,
                    ) { chain ->
                        val userId = chain.getArg(1) as Int
                        val pkg = chain.getArg(2) as? String
                        if (host.modeFor(pkg, userId, BgSubsystem.ATHENA_RECENT) == PolicyMode.FORCE_ALLOW) {
                            host.recordEvent(pkg, BgSubsystem.ATHENA_RECENT, "skip_remove_task", "hans", userId)
                            return@hookSingle null
                        }
                        chain.proceed()
                    }
                ) {
                    anyOk = true
                }
            }

        HookSupport.loadClassOrNull(host, cl, "com.android.server.OplusListManagerImpl", scope, required = false)
            ?.let { listMgrClass ->
                val listMethods = listOf(
                    "getRemoveTaskFilterPkgList" to arrayOf<Class<*>>(Context::class.java),
                    "getRemoveTaskFilterProcessList" to arrayOf<Class<*>>(Context::class.java),
                    "getKillRestartServicePkgList" to arrayOf<Class<*>>(Context::class.java),
                    "getQuickRestartProcList" to emptyArray(),
                    "getKeepAliveList" to arrayOf<Class<*>>(String::class.java),
                    "getAmsEmptyKillFilterList" to emptyArray(),
                    "getAmsEmptyKillBootUpFilterList" to emptyArray(),
                    "getProtectSelfWhiteList" to emptyArray(),
                    "getCustomWhiteList" to emptyArray(),
                    "getCustomDynamicWhiteList" to emptyArray(),
                    "getGlobalWhiteList" to arrayOf<Class<*>>(Context::class.java),
                    "getGlobalWhiteList" to arrayOf<Class<*>>(Context::class.java, Int::class.javaPrimitiveType!!),
                    "getGlobalProcessWhiteList" to arrayOf<Class<*>>(Context::class.java),
                    "getStageProtectList" to emptyArray(),
                    "getStageProtectList" to arrayOf<Class<*>>(Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!),
                    "getStageProtectListAsUser" to arrayOf<Class<*>>(
                        Int::class.javaPrimitiveType!!,
                        Int::class.javaPrimitiveType!!,
                        Int::class.javaPrimitiveType!!,
                    ),
                )
                listMethods.forEach { (methodName, params) ->
                    if (HookSupport.hookFilterPkgCollection(host, listMgrClass, methodName, *params, sub = BgSubsystem.ATHENA_RECENT)) {
                        anyOk = true
                    }
                }
            }

        HookSupport.loadClassOrNull(host, cl, "com.android.server.wm.ActivityTaskSupervisor", scope, required = false)
            ?.let { supervisorClass ->
                val taskClass = HookSupport.loadClassOrNull(host, cl, "com.android.server.wm.Task", scope, required = false)
                    ?: return@let
                if (HookSupport.hookAllMatching(
                        host = host,
                        scopeKey = scope,
                        clazz = supervisorClass,
                        methodName = "removeTask",
                        required = true,
                        predicate = { m ->
                            m.name == "removeTask" &&
                                m.parameterTypes.size >= 4 &&
                                m.parameterTypes[0] == taskClass &&
                                m.parameterTypes[1] == Boolean::class.javaPrimitiveType &&
                                m.parameterTypes[2] == Boolean::class.javaPrimitiveType &&
                                m.parameterTypes[3] == String::class.java
                        },
                    ) { _, chain ->
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
                                    val pkg = SystemObjectAdapters.extractTaskPkg(task)
                                    val userId = SystemObjectAdapters.extractTaskUserId(task)
                                    if (pkg != null) {
                                        if (host.modeFor(pkg, userId, BgSubsystem.ATHENA_RECENT).isRestrict) {
                                            host.recordEvent(pkg, BgSubsystem.ATHENA_RECENT, "remove_task_forcestop", reason, userId)
                                            host.workerHandler.post { host.forceStopPackageDirect(pkg, userId) }
                                        }
                                        if (io.github.yukino1111.colorosbgmanager.model.AppUserKey(pkg, userId) in host.autoFreezePkgs) {
                                            host.scheduleAutoFreezeCheck(pkg, userId, 1500L)
                                        }
                                    }
                                }
                            }
                            result
                        } finally {
                            removeTaskDepth.set(depth - 1)
                        }
                    }
                ) {
                    anyOk = true
                }
            }

        HookSupport.loadClassOrNull(host, cl, "com.android.server.hans.OplusHansDBConfig", scope, required = false)
            ?.let { dbClass ->
                HookSupport.hookPkgBool(host, dbClass, "isTrimMemWhitePkg", String::class.java, sub = BgSubsystem.ATHENA_RECENT)
                HookSupport.hookPkgBool(host, dbClass, "inThermalKeepAliveImpPkgList", String::class.java, sub = BgSubsystem.ATHENA_RECENT)
                HookSupport.hookPkgBool(host, dbClass, "isInRecentAppBlackList", String::class.java, sub = BgSubsystem.ATHENA_RECENT, invert = true)
            }

        HookSupport.loadClassOrNull(host, cl, "com.android.server.hans.oguard.OGuardDBConfig", scope, required = false)
            ?.let { oguardDbClass ->
                HookSupport.hookPkgBool(
                    host,
                    oguardDbClass,
                    "isInRecentLockAppList",
                    String::class.java,
                    Int::class.javaPrimitiveType!!,
                    sub = BgSubsystem.ATHENA_RECENT,
                    userIdArgIdx = 1,
                )
            }

        return anyOk
    }
}
