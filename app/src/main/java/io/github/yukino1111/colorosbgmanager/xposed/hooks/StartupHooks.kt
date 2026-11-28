package io.github.yukino1111.colorosbgmanager.xposed.hooks

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.os.Handler
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

                if (amsClass != null && prClass != null) {
                    if (HookSupport.hookSingle(
                            host = host,
                            scopeKey = scope,
                            clazz = mgrClass,
                            methodName = "shouldPreventStartProcess",
                            amsClass,
                            prClass,
                            required = true,
                        ) { chain ->
                            if (host.amsInstance == null) host.amsInstance = chain.getArg(0)
                            val pr = chain.getArg(1)
                            val pkg = SystemObjectAdapters.extractProcessPkg(pr)
                            val userId = SystemObjectAdapters.extractProcessUserId(pr)
                            val hostingType = SystemObjectAdapters.extractHostingType(pr)
                            val isActivity = hostingType?.contains("activity", ignoreCase = true) == true
                            if (!isActivity) {
                                val autoMode = host.modeFor(pkg, userId, BgSubsystem.AUTO_START)
                                val assocMode = host.modeFor(pkg, userId, BgSubsystem.ASSOCIATE_START)
                                if (autoMode.isRestrict || assocMode.isRestrict) {
                                    val sub = if (autoMode.isRestrict) BgSubsystem.AUTO_START else BgSubsystem.ASSOCIATE_START
                                    host.recordEvent(pkg, sub, "prevent_start_proc", hostingType ?: "bg", userId)
                                    return@hookSingle true
                                }
                                if (autoMode == PolicyMode.FORCE_ALLOW) {
                                    return@hookSingle false
                                }
                            }
                            chain.proceed()
                        }
                    ) {
                        anyOk = true
                    }
                }

                if (HookSupport.hookPkgBool(
                        host,
                        mgrClass,
                        "shouldRestrictApp",
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
                    userIdArgIdx = 2,
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

                if (srClass != null) {
                    HookSupport.hookSingle(
                        host = host,
                        scopeKey = scope,
                        clazz = mgrClass,
                        methodName = "handleServiceRestartReschedule",
                        srClass,
                        Handler::class.java,
                        required = false,
                    ) { chain ->
                        val sr = chain.getArg(0)
                        val pkg = SystemObjectAdapters.extractServiceRecordPkg(sr)
                        val userId = SystemObjectAdapters.extractServiceRecordUserId(sr)
                        val autoMode = host.modeFor(pkg, userId, BgSubsystem.AUTO_START)
                        val assocMode = host.modeFor(pkg, userId, BgSubsystem.ASSOCIATE_START)
                        if (autoMode.isRestrict || assocMode.isRestrict) {
                            val sub = if (autoMode.isRestrict) BgSubsystem.AUTO_START else BgSubsystem.ASSOCIATE_START
                            host.recordEvent(pkg, sub, "block_restart_reschedule", "ServiceRecord", userId)
                            return@hookSingle false
                        }
                        chain.proceed()
                    }
                }
            }

        HookSupport.loadClassOrNull(host, cl, "com.android.server.am.ActiveServices", scope, required = false)
            ?.let { activeServicesClass ->
                val srClass = HookSupport.loadClassOrNull(host, cl, "com.android.server.am.ServiceRecord", scope, required = false)
                    ?: return@let
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

                if (bqClass != null && brClass != null && bfClass != null) {
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
                                argPkg = chain.getArg(4) as? String,
                            )?.let { return@hookSingle it }
                            chain.proceed()
                        }
                    ) {
                        anyOk = true
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
                            argPkg = chain.getArg(3) as? String,
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

    private fun resolvePreventReceiverDecision(
        host: HookHost,
        br: Any?,
        ri: ResolveInfo?,
        bf: Any?,
        argPkg: String?,
    ): Boolean? {
        val resolvePkg = ri?.activityInfo?.packageName
        val filterPkg = SystemObjectAdapters.extractBroadcastFilterPkg(bf)
        val targetPkg = resolvePkg ?: filterPkg ?: argPkg
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
