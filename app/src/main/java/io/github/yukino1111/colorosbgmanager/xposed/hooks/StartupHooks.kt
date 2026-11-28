package io.github.yukino1111.colorosbgmanager.xposed.hooks

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import io.github.yukino1111.colorosbgmanager.model.BgSubsystem
import io.github.yukino1111.colorosbgmanager.model.PolicyMode
import io.github.yukino1111.colorosbgmanager.xposed.HookHost
import io.github.yukino1111.colorosbgmanager.xposed.HookSupport
import io.github.yukino1111.colorosbgmanager.xposed.MultiUserPackageHelper
import io.github.yukino1111.colorosbgmanager.xposed.SystemObjectAdapters

internal object StartupHooks {

    fun installAutoStart(host: HookHost, cl: ClassLoader): Boolean {
        val scope = BgSubsystem.AUTO_START.key
        var anyOk = false

        HookSupport.loadClassOrNull(host, cl, "com.android.server.am.OplusAppStartupConfig", scope, required = false)
            ?.let { cfgClass ->
                listOf(
                    "isInAutoBootWhiteList",
                    "isInCustomAutoBootWhiteList",
                    "isInAutoBootUserWhiteList",
                    "isJobRestrictionWhiteList",
                ).forEach { methodName ->
                    if (HookSupport.hookPkgBool(
                            host,
                            cfgClass,
                            methodName,
                            String::class.java,
                            Int::class.javaPrimitiveType!!,
                            sub = BgSubsystem.AUTO_START,
                            userIdArgIdx = 1,
                        )
                    ) {
                        anyOk = true
                    }
                }
                listOf(
                    "inAuthScopeAutoStartWhiteList",
                    "isInAppStandbyRestrictWhiteList",
                    "inNotUsedCallerWhiteList",
                ).forEach { methodName ->
                    if (HookSupport.hookPkgBool(host, cfgClass, methodName, String::class.java, sub = BgSubsystem.AUTO_START)) {
                        anyOk = true
                    }
                }
            }

        HookSupport.loadClassOrNull(host, cl, "com.android.server.am.OplusAppStartupManager", scope, required = true)
            ?.let { mgrClass ->
                val amsClass = HookSupport.loadClassOrNull(host, cl, "com.android.server.am.ActivityManagerService", scope, required = true)
                val prClass = HookSupport.loadClassOrNull(host, cl, "com.android.server.am.ProcessRecord", scope, required = true)
                val srClass = HookSupport.loadClassOrNull(host, cl, "com.android.server.am.ServiceRecord", scope, required = false)

                val processListExt = HookSupport.loadClassOrNull(
                    host, cl, "com.android.server.am.ProcessListExtImpl", scope, required = false,
                )
                val modernStartHooked = if (processListExt != null) {
                    val hostingClass = HookSupport.loadClassOrNull(
                        host, cl, "com.android.server.am.HostingRecord", scope, required = false,
                    )
                    HookSupport.hookSingle(
                        host, scope, processListExt, "interceptStartProcessBeforePendingStartCheck",
                        amsClass, prClass, hostingClass, required = false,
                    ) { chain ->
                        if (host.amsInstance == null) host.amsInstance = chain.getArg(0)
                        val hostingType = SystemObjectAdapters.extractHostingRecordType(chain.getArg(2))
                        resolveProcessStartDecision(host, chain.getArg(1), hostingType) ?: chain.proceed()
                    }
                } else {
                    false
                }
                if (modernStartHooked || HookSupport.hookSingle(
                        host, scope, mgrClass, "shouldPreventStartProcess", amsClass, prClass,
                    ) { chain ->
                        if (host.amsInstance == null) host.amsInstance = chain.getArg(0)
                        val pr = chain.getArg(1)
                        resolveProcessStartDecision(host, pr, SystemObjectAdapters.extractHostingType(pr)) ?: chain.proceed()
                    }
                ) {
                    anyOk = true
                }

                if (HookSupport.hookPkgBool(
                        host,
                        mgrClass,
                        "restrictApp",
                        String::class.java,
                        Int::class.javaPrimitiveType!!,
                        sub = BgSubsystem.AUTO_START,
                        invert = true,
                        userIdArgIdx = 1,
                        required = true,
                    )
                ) {
                    anyOk = true
                }

                HookSupport.hookPkgBool(
                    host,
                    mgrClass,
                    "isInAccountSyncWhiteList",
                    String::class.java,
                    Int::class.javaPrimitiveType!!,
                    Int::class.javaPrimitiveType!!,
                    sub = BgSubsystem.AUTO_START,
                    userIdArgIdx = 1,
                    restrictEventAction = "block_account_sync",
                )
                HookSupport.hookPkgBool(
                    host,
                    mgrClass,
                    "isJobRestrictionWhiteList",
                    String::class.java,
                    Int::class.javaPrimitiveType!!,
                    sub = BgSubsystem.AUTO_START,
                    userIdArgIdx = 1,
                )
                HookSupport.hookPkgBool(host, mgrClass, "inProtectWhiteList", String::class.java, sub = BgSubsystem.AUTO_START)

                if (prClass != null) {
                    HookSupport.hookSingle(
                        host = host,
                        scopeKey = scope,
                        clazz = mgrClass,
                        methodName = "isAllowRestartService",
                        prClass,
                        required = false,
                    ) { chain ->
                        val pr = chain.getArg(0)
                        val pkg = SystemObjectAdapters.extractProcessPkg(pr)
                        val userId = SystemObjectAdapters.extractProcessUserId(pr)
                        val autoMode = host.modeFor(pkg, userId, BgSubsystem.AUTO_START)
                        val assocMode = host.modeFor(pkg, userId, BgSubsystem.ASSOCIATE_START)
                        when {
                            autoMode.isRestrict || assocMode.isRestrict -> {
                                val sub = if (autoMode.isRestrict) BgSubsystem.AUTO_START else BgSubsystem.ASSOCIATE_START
                                host.recordEvent(pkg, sub, "block_restart_proc", "isAllowRestartService", userId)
                                false
                            }
                            autoMode == PolicyMode.FORCE_ALLOW -> true
                            else -> chain.proceed()
                        }
                    }
                }

            }

        HookSupport.loadClassOrNull(host, cl, "com.android.server.am.ActiveServices", scope, required = false)
            ?.let { activeServicesClass ->
                val srClass = HookSupport.loadClassOrNull(host, cl, "com.android.server.am.ServiceRecord", scope, required = true)
                if (srClass != null) installQueuedServiceRestartHook(host, scope, activeServicesClass, srClass)
                if (HookSupport.hookSingle(
                        host = host,
                        scopeKey = scope,
                        clazz = activeServicesClass,
                        methodName = "scheduleServiceRestartLocked",
                        srClass,
                        Boolean::class.javaPrimitiveType!!,
                        required = true,
                    ) { chain ->
                        val sr = chain.getArg(0)
                        val pkg = SystemObjectAdapters.extractServiceRecordPkg(sr)
                        val userId = SystemObjectAdapters.extractServiceRecordUserId(sr)
                        val autoMode = host.modeFor(pkg, userId, BgSubsystem.AUTO_START)
                        val assocMode = host.modeFor(pkg, userId, BgSubsystem.ASSOCIATE_START)
                        if (autoMode.isRestrict || assocMode.isRestrict) {
                            val sub = if (autoMode.isRestrict) BgSubsystem.AUTO_START else BgSubsystem.ASSOCIATE_START
                            host.recordEvent(pkg, sub, "block_service_restart", "START_STICKY", userId)
                            return@hookSingle false
                        }
                        chain.proceed()
                    }
                ) {
                    anyOk = true
                }
            }

        return anyOk
    }

    fun installAssociateStart(host: HookHost, cl: ClassLoader): Boolean {
        val scope = BgSubsystem.ASSOCIATE_START.key
        var anyOk = false

        HookSupport.loadClassOrNull(host, cl, "com.android.server.am.OplusAppStartupConfig", scope, required = false)
            ?.let { cfgClass ->
                listOf(
                    "inAssociateStartWhiteList",
                    "inCustomAssociateStartWhiteList",
                    "inAssociateStartUserWhiteList",
                ).forEach { methodName ->
                    if (HookSupport.hookPkgBool(
                            host,
                            cfgClass,
                            methodName,
                            String::class.java,
                            Int::class.javaPrimitiveType!!,
                            sub = BgSubsystem.ASSOCIATE_START,
                            userIdArgIdx = 1,
                        )
                    ) {
                        anyOk = true
                    }
                }
                if (HookSupport.hookPkgBool(host, cfgClass, "inAuthScopeAssStartWhiteList", String::class.java, sub = BgSubsystem.ASSOCIATE_START)) {
                    anyOk = true
                }
            }

        HookSupport.loadClassOrNull(host, cl, "com.android.server.am.OplusAppStartupManager", scope, required = true)
            ?.let { mgrClass ->
                if (HookSupport.hookPkgBool(
                        host,
                        mgrClass,
                        "inAssociateStartWhiteList",
                        String::class.java,
                        Int::class.javaPrimitiveType!!,
                        sub = BgSubsystem.ASSOCIATE_START,
                        userIdArgIdx = 1,
                        required = true,
                    )
                ) {
                    anyOk = true
                }

                val srClass = HookSupport.loadClassOrNull(host, cl, "com.android.server.am.ServiceRecord", scope, required = false)
                val prClass = HookSupport.loadClassOrNull(host, cl, "com.android.server.am.ProcessRecord", scope, required = false)
                val bqClass = HookSupport.loadClassOrNull(host, cl, "com.android.server.am.BroadcastQueue", scope, required = false)
                val brClass = HookSupport.loadClassOrNull(host, cl, "com.android.server.am.BroadcastRecord", scope, required = false)
                val bfClass = HookSupport.loadClassOrNull(host, cl, "com.android.server.am.BroadcastFilter", scope, required = false)
                val cpClass = HookSupport.loadClassOrNull(host, cl, "com.android.server.am.ContentProviderRecord", scope, required = false)

                if (HookSupport.hookAllMatching(
                        host = host,
                        scopeKey = scope,
                        clazz = mgrClass,
                        methodName = "shouldPreventStartService",
                        required = true,
                        predicate = { it.name == "shouldPreventStartService" },
                    ) { _, chain ->
                        val sr = chain.getArg(0)
                        val targetPkg = SystemObjectAdapters.extractServiceRecordPkg(sr)
                        val userId = SystemObjectAdapters.extractServiceRecordUserId(sr)
                        when (host.modeFor(targetPkg, userId, BgSubsystem.ASSOCIATE_START)) {
                            PolicyMode.FORCE_ALLOW -> false
                            PolicyMode.FORCE_RESTRICT,
                            PolicyMode.FORCE_RESTRICT_HBG -> {
                                host.recordEvent(targetPkg, BgSubsystem.ASSOCIATE_START, "prevent_service", "startService", userId)
                                true
                            }
                            PolicyMode.FOLLOW_SYSTEM -> chain.proceed()
                        }
                    }
                ) {
                    anyOk = true
                }

                if (srClass != null) {
                    if (HookSupport.hookSingle(
                            host = host,
                            scopeKey = scope,
                            clazz = mgrClass,
                            methodName = "isPreventFromBindService",
                            String::class.java,
                            Int::class.javaPrimitiveType!!,
                            srClass,
                            Intent::class.java,
                            Int::class.javaPrimitiveType!!,
                            required = true,
                        ) { chain ->
                            val sr = chain.getArg(2)
                            val targetPkg = SystemObjectAdapters.extractServiceRecordPkg(sr)
                            val userId = SystemObjectAdapters.extractServiceRecordUserId(sr)
                            when (host.modeFor(targetPkg, userId, BgSubsystem.ASSOCIATE_START)) {
                                PolicyMode.FORCE_ALLOW -> false
                                PolicyMode.FORCE_RESTRICT,
                                PolicyMode.FORCE_RESTRICT_HBG -> {
                                    host.recordEvent(targetPkg, BgSubsystem.ASSOCIATE_START, "prevent_bind", chain.getArg(0) as? String ?: "", userId)
                                    true
                                }
                                PolicyMode.FOLLOW_SYSTEM -> chain.proceed()
                            }
                        }
                    ) {
                        anyOk = true
                    }
                }

                if (HookSupport.hookSingle(
                        host = host,
                        scopeKey = scope,
                        clazz = mgrClass,
                        methodName = "shouldPreventSendReceiver",
                        bqClass,
                        brClass,
                        ResolveInfo::class.java,
                        bfClass,
                        String::class.java,
                        required = true,
                    ) { chain ->
                        resolvePreventReceiverDecision(
                            host = host,
                            br = chain.getArg(1),
                            ri = chain.getArg(2) as? ResolveInfo,
                            bf = chain.getArg(3),
                        )?.let { return@hookSingle it }
                        chain.proceed()
                    }
                ) {
                    anyOk = true
                }

                HookSupport.hookSingle(
                    host, scope, mgrClass, "validStartProcessFromBroadcast",
                    bqClass, brClass, String::class.java, Int::class.javaPrimitiveType!!,
                    required = false,
                ) { chain ->
                    val pkg = chain.getArg(2) as? String
                    val userId = chain.getArg(3) as Int
                    if (host.modeFor(pkg, userId, BgSubsystem.ASSOCIATE_START) == PolicyMode.FORCE_ALLOW) {
                        false
                    } else {
                        chain.proceed()
                    }
                }

                if (brClass != null && bfClass != null) {
                    HookSupport.hookSingle(
                        host = host,
                        scopeKey = scope,
                        clazz = mgrClass,
                        methodName = "shouldPreventSendReceiverReal",
                        brClass,
                        ResolveInfo::class.java,
                        bfClass,
                        String::class.java,
                        required = false,
                    ) { chain ->
                        resolvePreventReceiverDecision(
                            host = host,
                            br = chain.getArg(0),
                            ri = chain.getArg(1) as? ResolveInfo,
                            bf = chain.getArg(2),
                        )?.let { return@hookSingle it }
                        chain.proceed()
                    }
                }

                if (prClass != null && cpClass != null) {
                    if (HookSupport.hookSingle(
                            host = host,
                            scopeKey = scope,
                            clazz = mgrClass,
                            methodName = "shouldPreventStartProvider",
                            prClass,
                            cpClass,
                            ApplicationInfo::class.java,
                            String::class.java,
                            Int::class.javaPrimitiveType!!,
                            required = true,
                        ) { chain ->
                            val appInfo = chain.getArg(2) as? ApplicationInfo
                            val targetPkg = appInfo?.packageName ?: (chain.getArg(3) as? String)
                            val userId = appInfo?.let { MultiUserPackageHelper.userIdOfUid(it.uid) } ?: (chain.getArg(4) as Int)
                            when (host.modeFor(targetPkg, userId, BgSubsystem.ASSOCIATE_START)) {
                                PolicyMode.FORCE_ALLOW -> false
                                PolicyMode.FORCE_RESTRICT,
                                PolicyMode.FORCE_RESTRICT_HBG -> {
                                    host.recordEvent(targetPkg, BgSubsystem.ASSOCIATE_START, "prevent_provider", chain.getArg(3) as? String ?: "", userId)
                                    true
                                }
                                PolicyMode.FOLLOW_SYSTEM -> chain.proceed()
                            }
                        }
                    ) {
                        anyOk = true
                    }
                }
            }

        return anyOk
    }

    private fun resolveProcessStartDecision(host: HookHost, pr: Any?, hostingType: String?): Boolean? {
        if (hostingType.isNullOrEmpty() || hostingType.contains("activity", ignoreCase = true)) return null
        val pkg = SystemObjectAdapters.extractProcessPkg(pr)
        val userId = SystemObjectAdapters.extractProcessUserId(pr)
        val autoMode = host.modeFor(pkg, userId, BgSubsystem.AUTO_START)
        val assocMode = host.modeFor(pkg, userId, BgSubsystem.ASSOCIATE_START)
        if (autoMode.isRestrict || assocMode.isRestrict) {
            val sub = if (autoMode.isRestrict) BgSubsystem.AUTO_START else BgSubsystem.ASSOCIATE_START
            host.recordEvent(pkg, sub, "prevent_start_proc", hostingType, userId)
            return true
        }
        return if (autoMode == PolicyMode.FORCE_ALLOW) false else null
    }

    private fun installQueuedServiceRestartHook(host: HookHost, scope: String, activeServicesClass: Class<*>, srClass: Class<*>) {
        val unschedule = SystemObjectAdapters.findDeclaredMethod(
            activeServicesClass, "unscheduleServiceRestartLocked",
            srClass, Int::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!,
        ) ?: return
        val getUid = SystemObjectAdapters.findDeclaredMethod(activeServicesClass, "getServiceUid", srClass) ?: return
        HookSupport.hookSingle(
            host, scope, activeServicesClass, "performServiceRestartLocked", srClass, required = false,
        ) { chain ->
            val sr = chain.getArg(0)
            val pkg = SystemObjectAdapters.extractServiceRecordPkg(sr)
            val userId = SystemObjectAdapters.extractServiceRecordUserId(sr)
            val autoRestrict = host.modeFor(pkg, userId, BgSubsystem.AUTO_START).isRestrict
            if (autoRestrict || host.modeFor(pkg, userId, BgSubsystem.ASSOCIATE_START).isRestrict) {
                val cancelled = runCatching {
                    val uid = getUid.invoke(null, sr) as Int
                    unschedule.invoke(chain.thisObject, sr, uid, true) == true
                }.getOrDefault(false)
                if (cancelled) {
                    val sub = if (autoRestrict) BgSubsystem.AUTO_START else BgSubsystem.ASSOCIATE_START
                    host.recordEvent(pkg, sub, "block_restart_reschedule", "ServiceRecord", userId)
                    return@hookSingle null
                }
            }
            chain.proceed()
        }
    }

    private fun resolvePreventReceiverDecision(
        host: HookHost,
        br: Any?,
        ri: ResolveInfo?,
        bf: Any?,
    ): Boolean? {
        val resolvePkg = ri?.activityInfo?.packageName
        val filterPkg = SystemObjectAdapters.extractBroadcastFilterPkg(bf)
        val targetPkg = resolvePkg ?: filterPkg
        val userId = SystemObjectAdapters.extractReceiverUserId(ri, bf, br)
        return when (host.modeFor(targetPkg, userId, BgSubsystem.ASSOCIATE_START)) {
            PolicyMode.FORCE_ALLOW -> false
            PolicyMode.FORCE_RESTRICT,
            PolicyMode.FORCE_RESTRICT_HBG -> {
                host.recordEvent(targetPkg, BgSubsystem.ASSOCIATE_START, "prevent_receiver", "broadcast", userId)
                true
            }
            PolicyMode.FOLLOW_SYSTEM -> null
        }
    }
}
