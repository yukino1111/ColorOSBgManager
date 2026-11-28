package io.github.yukino1111.colorosbgmanager.xposed

import android.content.Context
import android.os.Handler
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.yukino1111.colorosbgmanager.model.AppUserKey
import io.github.yukino1111.colorosbgmanager.model.BgSubsystem
import io.github.yukino1111.colorosbgmanager.model.GlobalRuleFlags
import io.github.yukino1111.colorosbgmanager.model.HookDiagnosticEntry
import io.github.yukino1111.colorosbgmanager.model.HookPointStatus
import io.github.yukino1111.colorosbgmanager.model.PolicyMode
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

internal data class PkgBoolVariant(
    val paramTypes: Array<Class<*>>,
    val pkgArgIdx: Int = 0,
    val userIdArgIdx: Int? = null,
)

internal interface HookHost {
    var amsInstance: Any?
    var deviceIdleController: Any?
    var appStandbyController: Any?
    var powerWhitelistHooked: Boolean
    var bootCompleted: Boolean

    val globalFlags: GlobalRuleFlags
    val autoFreezePkgs: Set<AppUserKey>
    val autoUnfreezeDisabledPkgs: Set<AppUserKey>
    val workerHandler: Handler

    fun interceptMethod(method: Method, block: (XposedInterface.Chain) -> Any?)
    fun modeFor(pkg: String?, userId: Int, sub: BgSubsystem): PolicyMode
    fun modeForFallback(pkg: String?, sub: BgSubsystem): PolicyMode
    fun uidHasMode(uid: Int, sub: BgSubsystem, target: PolicyMode): Boolean
    fun recordEvent(pkg: String?, sub: BgSubsystem, action: String, detail: String = "", userId: Int = 0)
    fun resolveSystemContext(): Context?
    fun resolveAppIdForPkg(pkg: String): Int
    fun resolveDozeModeForAppId(appId: Int): PolicyMode
    fun adjustDozeAppIds(orig: IntArray?): IntArray
    fun adjustDozePackages(orig: Array<*>?, canAdd: (String) -> Boolean = { true }): Array<String>
    fun forceStopPackageDirect(pkg: String, userId: Int): Boolean
    fun scheduleBatteryDozeSync()
    fun scheduleAutoFreezeCheck(pkg: String, userId: Int, delayMs: Long)
    fun cancelPendingAutoFreeze(pkg: String, userId: Int)
    fun registerScreenOffReceiver()
    fun notifyShortcutStateChanged(ctx: Context?, pkg: String, userId: Int, suspended: Boolean)
    fun recordDiagnostic(entry: HookDiagnosticEntry)
    fun logMsg(priority: Int, msg: String, tr: Throwable? = null)
}

internal class HookDiagnosticsRegistry {
    private val entries = ConcurrentHashMap<String, HookDiagnosticEntry>()

    fun record(entry: HookDiagnosticEntry) {
        entries["${entry.scopeKey}|${entry.target}"] = entry
    }

    fun snapshot(): List<HookDiagnosticEntry> =
        entries.values.sortedWith(compareBy<HookDiagnosticEntry> { it.scopeKey }.thenBy { it.target })

    fun isScopeActive(scopeKey: String): Boolean =
        entries.values.any { it.scopeKey == scopeKey && it.status.isOk }
}

internal object HookSupport {

    fun loadClassOrNull(
        host: HookHost,
        cl: ClassLoader,
        className: String,
        scopeKey: String,
        required: Boolean = true,
    ): Class<*>? = runCatching {
        cl.loadClass(className)
    }.onFailure { tr ->
        val simpleName = className.substringAfterLast('.')
        host.recordDiagnostic(
            HookDiagnosticEntry(
                scopeKey = scopeKey,
                target = simpleName,
                required = required,
                status = HookPointStatus.MISSING_CLASS,
                detail = className,
            ),
        )
        host.logMsg(if (required) Log.ERROR else Log.WARN, "Missing class $className", tr)
    }.getOrNull()

    fun hookSingle(
        host: HookHost,
        scopeKey: String,
        clazz: Class<*>,
        methodName: String,
        vararg paramTypes: Class<*>?,
        required: Boolean = true,
        targetLabel: String = "${clazz.simpleName}#$methodName(${formatParams(paramTypes)})",
        block: (XposedInterface.Chain) -> Any?,
    ): Boolean {
        if (paramTypes.any { it == null }) {
            val detail = "Parameter class unavailable; see class lookup diagnostics"
            host.recordDiagnostic(HookDiagnosticEntry(scopeKey, targetLabel, required, HookPointStatus.MISSING_CLASS, detail))
            return false
        }
        val method = try {
            @Suppress("UNCHECKED_CAST") // Null elements were rejected above.
            clazz.getDeclaredMethod(methodName, *(paramTypes as Array<out Class<*>>))
        } catch (_: NoSuchMethodException) {
            val detail = runCatching { describeAvailableMethods(clazz.declaredMethods, methodName) }
                .getOrElse { "Cannot enumerate signatures: ${describeError(it)}" }
            reportMissingMethod(host, scopeKey, targetLabel, required, detail)
            return false
        } catch (tr: Throwable) {
            reportHookError(host, scopeKey, targetLabel, required, tr)
            return false
        }
        return registerMethod(host, scopeKey, targetLabel, required, method, block = block)
    }

    fun hookAllMatching(
        host: HookHost,
        scopeKey: String,
        clazz: Class<*>,
        methodName: String,
        required: Boolean = true,
        targetLabel: String = "${clazz.simpleName}#$methodName",
        predicate: (Method) -> Boolean = { it.name == methodName },
        block: (Method, XposedInterface.Chain) -> Any?,
    ): Boolean {
        val methods = runCatching { clazz.declaredMethods }.getOrElse { tr ->
            reportHookError(host, scopeKey, targetLabel, required, tr)
            return false
        }
        val matched = runCatching { methods.filter(predicate) }.getOrElse { tr ->
            reportHookError(host, scopeKey, targetLabel, required, tr)
            return false
        }
        if (matched.isEmpty()) {
            reportMissingMethod(host, scopeKey, targetLabel, required, describeAvailableMethods(methods, methodName))
            return false
        }
        var anyHooked = false
        for (method in matched) {
            val signature = "${clazz.simpleName}#${formatSignature(method)}"
            if (registerMethod(host, scopeKey, signature, required, method) { chain -> block(method, chain) }) {
                anyHooked = true
            }
        }
        return anyHooked
    }

    fun hookPkgBool(
        host: HookHost,
        clazz: Class<*>,
        methodName: String,
        vararg paramTypes: Class<*>,
        sub: BgSubsystem,
        invert: Boolean = false,
        pkgArgIdx: Int = 0,
        userIdArgIdx: Int? = null,
        restrictEventAction: String? = null,
        required: Boolean = false,
    ): Boolean = hookSingle(
        host = host,
        scopeKey = sub.key,
        clazz = clazz,
        methodName = methodName,
        paramTypes = paramTypes,
        required = required,
    ) { chain ->
        interceptPkgBool(host, chain, sub, methodName, invert, pkgArgIdx, userIdArgIdx, restrictEventAction)
    }

    fun hookPkgBoolVariants(
        host: HookHost,
        clazz: Class<*>,
        methodName: String,
        variants: List<PkgBoolVariant>,
        sub: BgSubsystem,
        invert: Boolean = false,
        restrictEventAction: String? = null,
        required: Boolean = true,
    ): Boolean {
        val targetLabel = "${clazz.simpleName}#$methodName"
        var anyHooked = false
        var foundOrErrored = false
        for ((index, variant) in variants.withIndex()) {
            val signature = "$targetLabel(${formatParams(variant.paramTypes)})"
            val method = try {
                clazz.getDeclaredMethod(methodName, *variant.paramTypes)
            } catch (_: NoSuchMethodException) {
                // Absent alternative signatures are expected across ROM versions.
                continue
            } catch (tr: Throwable) {
                foundOrErrored = true
                reportHookError(host, sub.key, signature, required, tr)
                continue
            }
            foundOrErrored = true
            val status = if (index > 0 && !anyHooked) HookPointStatus.FALLBACK else HookPointStatus.HOOKED
            if (registerMethod(host, sub.key, signature, required, method, status) { chain ->
                    interceptPkgBool(
                        host, chain, sub, methodName, invert,
                        variant.pkgArgIdx, variant.userIdArgIdx, restrictEventAction,
                    )
                }
            ) {
                anyHooked = true
            }
        }
        if (!foundOrErrored) {
            val detail = runCatching { describeAvailableMethods(clazz.declaredMethods, methodName) }
                .getOrElse { "Cannot enumerate signatures: ${describeError(it)}" }
            reportMissingMethod(host, sub.key, targetLabel, required, detail)
        }
        return anyHooked
    }

    fun hookFilterPkgCollection(
        host: HookHost,
        clazz: Class<*>,
        methodName: String,
        vararg paramTypes: Class<*>,
        sub: BgSubsystem,
        required: Boolean = false,
    ): Boolean = hookSingle(
        host = host,
        scopeKey = sub.key,
        clazz = clazz,
        methodName = methodName,
        paramTypes = paramTypes,
        required = required,
    ) { chain ->
        val raw = chain.proceed()
        when (raw) {
            is List<*> -> {
                val filtered = ArrayList<Any?>(raw.size)
                for (item in raw) {
                    val pkg = extractListEntryPkg(item)
                    if (pkg == null || !host.modeForFallback(pkg, sub).isRestrict) {
                        filtered.add(item)
                    }
                }
                filtered
            }
            is Set<*> -> {
                val filtered = android.util.ArraySet<Any?>()
                for (item in raw) {
                    val pkg = extractListEntryPkg(item)
                    if (pkg == null || !host.modeForFallback(pkg, sub).isRestrict) {
                        filtered.add(item)
                    }
                }
                filtered
            }
            else -> raw
        }
    }

    private fun interceptPkgBool(
        host: HookHost,
        chain: XposedInterface.Chain,
        sub: BgSubsystem,
        methodName: String,
        invert: Boolean,
        pkgArgIdx: Int,
        userIdArgIdx: Int?,
        restrictEventAction: String?,
    ): Any? {
        val pkg = chain.getArg(pkgArgIdx) as? String
        val userId = userIdArgIdx?.let { chain.getArg(it) as? Int }
        val mode = if (userId != null) host.modeFor(pkg, userId, sub) else host.modeForFallback(pkg, sub)
        return when (mode) {
            PolicyMode.FORCE_ALLOW -> !invert
            PolicyMode.FORCE_RESTRICT,
            PolicyMode.FORCE_RESTRICT_HBG -> {
                if (restrictEventAction != null) {
                    host.recordEvent(pkg, sub, restrictEventAction, methodName, userId ?: 0)
                }
                invert
            }
            PolicyMode.FOLLOW_SYSTEM -> chain.proceed()
        }
    }

    private fun extractListEntryPkg(item: Any?): String? {
        val raw = (item as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val pkg = raw.substringBefore('@').substringBefore('#').substringBefore(':')
        return pkg.takeIf { it.contains('.') }
    }

    private fun registerMethod(
        host: HookHost,
        scopeKey: String,
        target: String,
        required: Boolean,
        method: Method,
        status: HookPointStatus = HookPointStatus.HOOKED,
        block: (XposedInterface.Chain) -> Any?,
    ): Boolean {
        runCatching { host.interceptMethod(method, block) }.getOrElse { tr ->
            reportHookError(host, scopeKey, target, required, tr)
            return false
        }
        host.recordDiagnostic(HookDiagnosticEntry(scopeKey, target, required, status))
        return true
    }

    private fun reportMissingMethod(host: HookHost, scopeKey: String, target: String, required: Boolean, detail: String) {
        host.recordDiagnostic(HookDiagnosticEntry(scopeKey, target, required, HookPointStatus.MISSING_METHOD, detail))
        host.logMsg(if (required) Log.WARN else Log.INFO, "Missing method $target: $detail")
    }

    private fun reportHookError(host: HookHost, scopeKey: String, target: String, required: Boolean, tr: Throwable) {
        host.recordDiagnostic(HookDiagnosticEntry(scopeKey, target, required, HookPointStatus.ERROR, describeError(tr)))
        host.logMsg(Log.ERROR, "Hook failed $target", tr)
    }

    private fun describeError(tr: Throwable): String =
        "${tr.javaClass.simpleName}: ${tr.message.orEmpty()}"

    private fun formatParams(types: Array<out Class<*>?>): String =
        types.joinToString(",") { it?.typeName ?: "?" }

    private fun formatSignature(method: Method): String =
        "${method.name}(${formatParams(method.parameterTypes)}):${method.returnType.typeName}"

    private fun describeAvailableMethods(methods: Array<Method>, methodName: String): String {
        val candidates = methods.filter { it.name == methodName }.map(::formatSignature)
        return if (candidates.isEmpty()) "no declarations with this name"
        else "available=${candidates.joinToString("; ")}"
    }
}
