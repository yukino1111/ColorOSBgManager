package io.github.yukino1111.colorosbgmanager.xposed.hooks

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.util.ArrayMap
import io.github.yukino1111.colorosbgmanager.model.BgSubsystem
import io.github.yukino1111.colorosbgmanager.model.PolicyMode
import io.github.yukino1111.colorosbgmanager.xposed.HookHost
import io.github.yukino1111.colorosbgmanager.xposed.HookSupport
import io.github.yukino1111.colorosbgmanager.xposed.MultiUserPackageHelper
import io.github.yukino1111.colorosbgmanager.xposed.PkgBoolVariant
import io.github.yukino1111.colorosbgmanager.xposed.SystemObjectAdapters

internal object BroadcastHooks {

    fun install(host: HookHost, cl: ClassLoader): Boolean {
        val scope = BgSubsystem.BROADCAST_RESTRICT.key
        var anyOk = false

        HookSupport.loadClassOrNull(host, cl, "com.android.server.am.OplusAppStartupConfig", scope, required = false)
            ?.let { startupCfgClass ->
                if (HookSupport.hookPkgBool(
                        host,
                        startupCfgClass,
                        "isInBroadcastRestrictionWhiteList",
                        String::class.java,
                        String::class.java,
                        sub = BgSubsystem.BROADCAST_RESTRICT,
                    )
                ) {
                    anyOk = true
                }
                if (HookSupport.hookPkgBool(
                        host,
                        startupCfgClass,
                        "isBrOptWhitelist",
                        String::class.java,
                        String::class.java,
                        sub = BgSubsystem.BROADCAST_RESTRICT,
                    )
                ) {
                    anyOk = true
                }
                HookSupport.hookPkgBool(
                    host,
                    startupCfgClass,
                    "isInRestrictStickyBroadcastWhiteList",
                    String::class.java,
                    sub = BgSubsystem.BROADCAST_RESTRICT,
                )
                HookSupport.hookPkgBool(
                    host,
                    startupCfgClass,
                    "isInSpecificBroadcastWhiteList",
                    String::class.java,
                    sub = BgSubsystem.BROADCAST_RESTRICT,
                )
            }

        HookSupport.loadClassOrNull(host, cl, "com.android.server.OplusListManagerImpl", scope, required = false)
            ?.let { listMgrClass ->
                val variants = listOf(
                    PkgBoolVariant(
                        paramTypes = arrayOf(Int::class.javaPrimitiveType!!, String::class.java, ApplicationInfo::class.java),
                        pkgArgIdx = 1,
                    ),
                    PkgBoolVariant(
                        paramTypes = arrayOf(String::class.java),
                        pkgArgIdx = 0,
                    ),
                )
                if (HookSupport.hookPkgBoolVariants(
                        host = host,
                        clazz = listMgrClass,
                        methodName = "isSkipBroadcastFlagRestricted",
                        variants = variants,
                        sub = BgSubsystem.BROADCAST_RESTRICT,
                        required = true,
                    )
                ) {
                    anyOk = true
                }
            }

        val prClass = HookSupport.loadClassOrNull(host, cl, "com.android.server.am.ProcessRecord", scope, required = false)

        HookSupport.loadClassOrNull(host, cl, "com.android.server.am.OplusBroadcastRestriction\$Restriction", scope, required = true)
            ?.let { restrictionBaseClass ->
                if (prClass != null) {
                    if (HookSupport.hookSingle(
                            host = host,
                            scopeKey = scope,
                            clazz = restrictionBaseClass,
                            methodName = "isAllowByDefaultCommonCheck",
                            ApplicationInfo::class.java,
                            prClass,
                            Intent::class.java,
                            required = true,
                        ) { chain ->
                            val appInfo = chain.getArg(0) as? ApplicationInfo
                            val pr = chain.getArg(1)
                            val pkg = appInfo?.packageName ?: SystemObjectAdapters.extractProcessPkg(pr)
                            val userId = appInfo?.let { MultiUserPackageHelper.userIdOfUid(it.uid) }
                                ?: SystemObjectAdapters.extractProcessUserId(pr)
                            when (host.modeFor(pkg, userId, BgSubsystem.BROADCAST_RESTRICT)) {
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
            }

        HookSupport.loadClassOrNull(host, cl, "com.android.server.am.OplusBroadcastRestriction", scope, required = true)
            ?.let { brClass ->
                if (prClass != null) {
                    if (HookSupport.hookSingle(
                            host = host,
                            scopeKey = scope,
                            clazz = brClass,
                            methodName = "restrict",
                            Intent::class.java,
                            ArrayMap::class.java,
                            ApplicationInfo::class.java,
                            prClass,
                            Boolean::class.javaPrimitiveType!!,
                            Boolean::class.javaPrimitiveType!!,
                            required = true,
                        ) { chain ->
                            if (host.globalFlags.disableBroadcastQuotaGlobal) return@hookSingle false
                            val appInfo = chain.getArg(2) as? ApplicationInfo
                            val pr = chain.getArg(3)
                            val pkg = appInfo?.packageName ?: SystemObjectAdapters.extractProcessPkg(pr)
                            val userId = appInfo?.let { MultiUserPackageHelper.userIdOfUid(it.uid) }
                                ?: SystemObjectAdapters.extractProcessUserId(pr)
                            if (host.modeFor(pkg, userId, BgSubsystem.BROADCAST_RESTRICT) == PolicyMode.FORCE_ALLOW) {
                                return@hookSingle false
                            }
                            chain.proceed()
                        }
                    ) {
                        anyOk = true
                    }
                }
            }

        return anyOk
    }
}
