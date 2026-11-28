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
    fun adjustDozePackages(orig: Array<*>?): Array<String>
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

    fun isScopeActive(scopeKey: String): Boolean {
        val scoped = entries.values.filter { it.scopeKey == scopeKey }
        if (scoped.isEmpty()) return false
        val required = scoped.filter { it.required }
        return if (required.isNotEmpty()) {
            required.any { it.status.isOk }
        } else {
            scoped.any { it.status.isOk }
        }
    }
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
        vararg paramTypes: Class<*>,
        required: Boolean = true,
        targetLabel: String = "${clazz.simpleName}#$methodName",
        block: (XposedInterface.Chain) -> Any?,
    ): Boolean {
        val method = runCatching {
            clazz.getDeclaredMethod(methodName, *paramTypes)
        }.getOrNull()
        if (method == null) {
            val detail = describeAvailableMethods(clazz, methodName)
            host.recordDiagnostic(
                HookDiagnosticEntry(scopeKey, targetLabel, required, HookPointStatus.MISSING_METHOD, detail),
            )
            host.logMsg(if (required) Log.ERROR else Log.WARN, "Missing method $targetLabel: $detail")
            return false
        }
        return runCatching {
            host.interceptMethod(method, block)
            host.recordDiagnostic(
                HookDiagnosticEntry(scopeKey, targetLabel, required, HookPointStatus.HOOKED, formatParams(method)),
            )
            true
        }.onFailure { tr ->
            host.recordDiagnostic(
                HookDiagnosticEntry(scopeKey, targetLabel, required, HookPointStatus.ERROR, tr.message.orEmpty()),
            )
            host.logMsg(Log.ERROR, "Hook failed $targetLabel", tr)
        }.getOrDefault(false)
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
        val matched = clazz.declaredMethods.filter(predicate)
        if (matched.isEmpty()) {
            val detail = describeAvailableMethods(clazz, methodName)
            host.recordDiagnostic(
                HookDiagnosticEntry(scopeKey, targetLabel, required, HookPointStatus.MISSING_METHOD, detail),
            )
            host.logMsg(if (required) Log.WARN else Log.INFO, "No matching method for $targetLabel: $detail")
            return false
        }
        var hookedCount = 0
        var lastError: Throwable? = null
        matched.forEach { method ->
            runCatching {
                host.interceptMethod(method) { chain -> block(method, chain) }
                hookedCount++
            }.onFailure { lastError = it }
        }
        return if (hookedCount > 0) {
            val detail = matched.joinToString("; ") { "${it.name}(${formatParams(it)})" }
            host.recordDiagnostic(
                HookDiagnosticEntry(scopeKey, targetLabel, required, HookPointStatus.HOOKED, detail),
            )
            true
        } else {
            host.recordDiagnostic(
                HookDiagnosticEntry(scopeKey, targetLabel, required, HookPointStatus.ERROR, lastError?.message.orEmpty()),
            )
            host.logMsg(Log.ERROR, "Hook failed $targetLabel", lastError)
            false
        }
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
        var hookedCount = 0
        var firstVariantHooked = false
        val matchedSigs = ArrayList<String>()
        var lastError: Throwable? = null

        variants.forEachIndexed { idx, variant ->
            val method = runCatching {
                clazz.getDeclaredMethod(methodName, *variant.paramTypes)
            }.getOrNull() ?: return@forEachIndexed

            runCatching {
                host.interceptMethod(method) { chain ->
                    interceptPkgBool(
                        host = host,
                        chain = chain,
                        sub = sub,
                        methodName = methodName,
                        invert = invert,
                        pkgArgIdx = variant.pkgArgIdx,
                        userIdArgIdx = variant.userIdArgIdx,
                        restrictEventAction = restrictEventAction,
                    )
                }
                hookedCount++
                if (idx == 0) firstVariantHooked = true
                matchedSigs.add("(${formatParams(method)})")
            }.onFailure { lastError = it }
        }

        return if (hookedCount > 0) {
            val status = if (firstVariantHooked) HookPointStatus.HOOKED else HookPointStatus.FALLBACK
            host.recordDiagnostic(
                HookDiagnosticEntry(sub.key, targetLabel, required, status, matchedSigs.joinToString("; ")),
            )
            true
        } else {
            val detail = lastError?.message ?: describeAvailableMethods(clazz, methodName)
            val status = if (lastError != null) HookPointStatus.ERROR else HookPointStatus.MISSING_METHOD
            host.recordDiagnostic(HookDiagnosticEntry(sub.key, targetLabel, required, status, detail))
            host.logMsg(if (required) Log.ERROR else Log.WARN, "Variant hook miss $targetLabel: $detail", lastError)
            false
        }
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

    private fun formatParams(method: Method): String =
        method.parameterTypes.joinToString(",") { it.simpleName }

    fun describeAvailableMethods(clazz: Class<*>, methodName: String): String {
        val candidates = clazz.declaredMethods
            .filter { it.name == methodName }
            .map { "${it.name}(${formatParams(it)}):${it.returnType.simpleName}" }
        return if (candidates.isEmpty()) {
            "none on ${clazz.simpleName}"
        } else {
            "available=${candidates.joinToString("; ")}"
        }
    }
}
