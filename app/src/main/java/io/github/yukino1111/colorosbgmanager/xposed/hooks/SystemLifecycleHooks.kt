package io.github.yukino1111.colorosbgmanager.xposed.hooks

import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import io.github.yukino1111.colorosbgmanager.model.AppUserKey
import io.github.yukino1111.colorosbgmanager.model.BgSubsystem
import io.github.yukino1111.colorosbgmanager.xposed.HookHost
import io.github.yukino1111.colorosbgmanager.xposed.HookSupport
import io.github.yukino1111.colorosbgmanager.xposed.MultiUserPackageHelper
import io.github.yukino1111.colorosbgmanager.xposed.SystemObjectAdapters
import java.lang.reflect.Field

internal object SystemLifecycleHooks {

    private const val SCOPE = "lifecycle"

    fun install(host: HookHost, cl: ClassLoader) {
        HookSupport.loadClassOrNull(host, cl, "com.android.server.am.ActivityManagerService", SCOPE, required = true)
            ?.let { amsClass ->
                HookSupport.hookSingle(
                    host = host,
                    scopeKey = SCOPE,
                    clazz = amsClass,
                    methodName = "finishBooting",
                    required = true,
                ) { chain ->
                    if (host.amsInstance == null) host.amsInstance = chain.thisObject
                    val result = chain.proceed()
                    host.bootCompleted = true
                    host.scheduleBatteryDozeSync()
                    host.registerScreenOffReceiver()
                    result
                }

                HookSupport.hookAllMatching(
                    host = host,
                    scopeKey = SCOPE,
                    clazz = amsClass,
                    methodName = "systemReady",
                    required = true,
                    predicate = { it.name == "systemReady" },
                ) { _, chain ->
                    if (host.amsInstance == null) host.amsInstance = chain.thisObject
                    chain.proceed()
                }
            }

        HookSupport.loadClassOrNull(host, cl, "com.android.server.am.ProcessList", SCOPE, required = false)
            ?.let { procListClass ->
                val prClass = HookSupport.loadClassOrNull(host, cl, "com.android.server.am.ProcessRecord", SCOPE, required = false)
                    ?: return@let
                HookSupport.hookAllMatching(
                    host = host,
                    scopeKey = SCOPE,
                    clazz = procListClass,
                    methodName = "removeProcessLocked",
                    required = false,
                    predicate = { it.name == "removeProcessLocked" && it.parameterTypes.isNotEmpty() && it.parameterTypes[0] == prClass },
                ) { _, chain ->
                    val pr = chain.getArg(0)
                    val pkg = SystemObjectAdapters.extractProcessPkg(pr)
                    val userId = SystemObjectAdapters.extractProcessUserId(pr)
                    val res = chain.proceed()
                    if (pkg != null && AppUserKey(pkg, userId) in host.autoFreezePkgs) {
                        host.scheduleAutoFreezeCheck(pkg, userId, host.globalFlags.autoFreezeDelaySec * 1000L)
                    }
                    res
                }
            }

        installActivityLaunchUnfreezeHooks(host, cl)
    }

    private fun installActivityLaunchUnfreezeHooks(host: HookHost, cl: ClassLoader) {
        val interceptorClass = HookSupport.loadClassOrNull(
            host,
            cl,
            "com.android.server.wm.ActivityStartInterceptor",
            SCOPE,
            required = false,
        ) ?: return

        val aInfoField = SystemObjectAdapters.findDeclaredField(interceptorClass, "mAInfo") ?: return
        val rInfoField = SystemObjectAdapters.findDeclaredField(interceptorClass, "mRInfo") ?: return
        val userIdField = SystemObjectAdapters.findDeclaredField(interceptorClass, "mUserId") ?: return
        val serviceField = SystemObjectAdapters.findDeclaredField(interceptorClass, "mService") ?: return

        HookSupport.hookSingle(
            host = host,
            scopeKey = SCOPE,
            clazz = interceptorClass,
            methodName = "interceptSuspendedPackageIfNeeded",
            required = false,
        ) { chain ->
            val interceptor = chain.thisObject ?: return@hookSingle chain.proceed()
            val aInfo = aInfoField.get(interceptor) as? ActivityInfo
            val appInfo = aInfo?.applicationInfo
            if (appInfo != null && (appInfo.flags and ApplicationInfo.FLAG_SUSPENDED) != 0) {
                val pkg = appInfo.packageName
                val userId = userIdField.getInt(interceptor)
                val key = AppUserKey(pkg, userId)
                if (key !in host.autoUnfreezeDisabledPkgs &&
                    (key in host.autoFreezePkgs || isSuspendedBySystem(serviceField, interceptor, pkg, userId))
                ) {
                    host.cancelPendingAutoFreeze(pkg, userId)
                    if (MultiUserPackageHelper.setPackageSuspended(pkg, userId, false)) {
                        appInfo.flags = appInfo.flags and ApplicationInfo.FLAG_SUSPENDED.inv()
                        (rInfoField.get(interceptor) as? ResolveInfo)?.activityInfo?.applicationInfo?.let {
                            it.flags = it.flags and ApplicationInfo.FLAG_SUSPENDED.inv()
                        }
                        host.recordEvent(pkg, BgSubsystem.HANS_FREEZE, "unsuspend_launch", "activity", userId)
                        host.notifyShortcutStateChanged(host.resolveSystemContext(), pkg, userId, false)
                        return@hookSingle false
                    }
                }
            }
            chain.proceed()
        }
    }

    private fun isSuspendedBySystem(
        serviceField: Field,
        interceptor: Any,
        pkg: String,
        userId: Int,
    ): Boolean = runCatching {
        val service = serviceField.get(interceptor) ?: return false
        val pmi = service.javaClass.getMethod("getPackageManagerInternalLocked").invoke(service) ?: return false
        val getSuspending = pmi.javaClass.getMethod(
            "getSuspendingPackage",
            String::class.java,
            Int::class.javaPrimitiveType,
        )
        val suspender = getSuspending.invoke(pmi, pkg, userId) ?: return false
        val suspenderPkg = suspender.javaClass.getField("packageName").get(suspender) as? String
        suspenderPkg == "android"
    }.getOrDefault(false)
}
