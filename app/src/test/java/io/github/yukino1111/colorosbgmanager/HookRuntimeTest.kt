package io.github.yukino1111.colorosbgmanager

import io.github.yukino1111.colorosbgmanager.model.BgSubsystem
import io.github.yukino1111.colorosbgmanager.model.HookDiagnosticEntry
import io.github.yukino1111.colorosbgmanager.model.HookPointStatus
import io.github.yukino1111.colorosbgmanager.xposed.HookDiagnosticsRegistry
import io.github.yukino1111.colorosbgmanager.xposed.HookHost
import io.github.yukino1111.colorosbgmanager.xposed.HookSupport
import io.github.yukino1111.colorosbgmanager.xposed.PkgBoolVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Method
import java.lang.reflect.Proxy

class HookRuntimeTest {
    @Test
    fun matchedOverloadsReportEachRegistrationResult() {
        val diagnostics = HookDiagnosticsRegistry()
        val host = host(diagnostics) { it.parameterTypes.single() == String::class.java }

        val active = HookSupport.hookAllMatching(host, "test", Callbacks::class.java, "check") { _, _ -> true }

        assertTrue(active)
        assertTrue(diagnostics.isScopeActive("test"))
        assertEquals(
            mapOf(
                "Callbacks#check(int):boolean" to HookPointStatus.HOOKED,
                "Callbacks#check(java.lang.String):boolean" to HookPointStatus.ERROR,
            ),
            diagnostics.snapshot().associate { it.target to it.status },
        )
    }

    @Test
    fun absentAlternativeDoesNotDegradeWorkingFallback() {
        val diagnostics = HookDiagnosticsRegistry()
        val sub = BgSubsystem.BROADCAST_RESTRICT
        val variants = listOf(PkgBoolVariant(arrayOf(Boolean::class.javaPrimitiveType!!)), PkgBoolVariant(arrayOf(String::class.java)))

        val active = HookSupport.hookPkgBoolVariants(host(diagnostics), Callbacks::class.java, "check", variants, sub)

        assertTrue(active)
        val result = diagnostics.snapshot().single()
        assertEquals("Callbacks#check(java.lang.String)", result.target)
        assertEquals(HookPointStatus.FALLBACK, result.status)
    }

    @Test
    fun missingParameterIsReportedAgainstTheSkippedRequiredHook() {
        val diagnostics = HookDiagnosticsRegistry()

        val active = HookSupport.hookSingle(host(diagnostics), "test", Callbacks::class.java, "check", null) { true }

        assertFalse(active)
        val result = diagnostics.snapshot().single()
        assertEquals("Callbacks#check(?)", result.target)
        assertEquals(HookPointStatus.MISSING_CLASS, result.status)
        assertTrue(result.required)
    }

    @Test
    fun unresolvableSignatureDoesNotAbortRegistrationOfLaterHooks() {
        val diagnostics = HookDiagnosticsRegistry()
        val host = host(diagnostics)
        val original = BrokenCallbacks::class.java
        val loader = object : ClassLoader(original.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> = when (name) {
                MissingParameter::class.java.name -> throw ClassNotFoundException(name)
                original.name -> findLoadedClass(name) ?: original.getResourceAsStream("/${name.replace('.', '/')}.class")!!
                    .use { it.readBytes() }.let { defineClass(name, it, 0, it.size) }
                else -> super.loadClass(name, resolve)
            }
        }
        val broken = loader.loadClass(original.name)

        assertFalse(HookSupport.hookAllMatching(host, "test", broken, "check") { _, _ -> true })
        assertFalse(HookSupport.hookSingle(host, "test", broken, "absent") { true })
        assertTrue(HookSupport.hookSingle(host, "test", Callbacks::class.java, "check", Int::class.javaPrimitiveType!!) { true })
        val results = diagnostics.snapshot().associateBy { it.target }
        assertEquals(HookPointStatus.ERROR, results.getValue("BrokenCallbacks#check").status)
        assertEquals(HookPointStatus.ERROR, results.getValue("BrokenCallbacks#absent()").status)
        assertEquals(HookPointStatus.HOOKED, results.getValue("Callbacks#check(int)").status)
    }

    private fun host(diagnostics: HookDiagnosticsRegistry, fail: (Method) -> Boolean = { false }): HookHost =
        Proxy.newProxyInstance(HookHost::class.java.classLoader, arrayOf(HookHost::class.java)) { _, method, args ->
            when (method.name) {
                "interceptMethod" -> if (fail(args[0] as Method)) error("Registration rejected")
                "recordDiagnostic" -> diagnostics.record(args.single() as HookDiagnosticEntry)
                "logMsg" -> Unit
                else -> error("Unexpected host call: ${method.name}")
            }
            null
        } as HookHost

    private class Callbacks {
        fun check(value: Int): Boolean = value != 0
        fun check(value: String): Boolean = value.isNotEmpty()
    }

}

private class MissingParameter

private class BrokenCallbacks {
    fun check(value: MissingParameter): Boolean = value.hashCode() != 0
}
