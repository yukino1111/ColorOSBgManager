package io.github.yukino1111.colorosbgmanager

import io.github.yukino1111.colorosbgmanager.model.BgSubsystem
import io.github.yukino1111.colorosbgmanager.model.NativeListKind
import io.github.yukino1111.colorosbgmanager.model.NativeSource
import io.github.yukino1111.colorosbgmanager.model.NativeState
import io.github.yukino1111.colorosbgmanager.model.PolicyMode
import io.github.yukino1111.colorosbgmanager.model.RulesCodec
import io.github.yukino1111.colorosbgmanager.model.SystemSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BgControlModelsTest {

    @Test
    fun encodeAndDecode_preservesNonDefaultOverridesAndDropsFollowSystem() {
        val original = mapOf(
            "com.example.keep" to mapOf(
                BgSubsystem.HANS_FREEZE to PolicyMode.FORCE_ALLOW,
                BgSubsystem.AUTO_START to PolicyMode.FOLLOW_SYSTEM,
                BgSubsystem.CPU_LIMIT to PolicyMode.FORCE_ALLOW,
                BgSubsystem.DOZE_NET to PolicyMode.FORCE_ALLOW,
                BgSubsystem.BROADCAST_RESTRICT to PolicyMode.FORCE_RESTRICT,
            ),
            "com.example.tame" to mapOf(
                BgSubsystem.BATTERY_DOZE to PolicyMode.FORCE_RESTRICT,
            ),
        )

        val encoded = RulesCodec.encodeRules(original)
        val decoded = RulesCodec.decodeRules(encoded)

        assertEquals(PolicyMode.FORCE_ALLOW, decoded["com.example.keep"]?.get(BgSubsystem.HANS_FREEZE))
        assertFalse(decoded["com.example.keep"]?.containsKey(BgSubsystem.AUTO_START) == true)
        assertEquals(PolicyMode.FORCE_ALLOW, decoded["com.example.keep"]?.get(BgSubsystem.CPU_LIMIT))
        assertEquals(PolicyMode.FORCE_ALLOW, decoded["com.example.keep"]?.get(BgSubsystem.DOZE_NET))
        assertEquals(PolicyMode.FORCE_RESTRICT, decoded["com.example.keep"]?.get(BgSubsystem.BROADCAST_RESTRICT))
        assertEquals(PolicyMode.FORCE_RESTRICT, decoded["com.example.tame"]?.get(BgSubsystem.BATTERY_DOZE))
    }

    @Test
    fun decodeRules_migratesLegacyCpuNetBroadcastKey() {
        val decoded = RulesCodec.decodeRules(setOf("com.example.legacy|cpulimit_net|1"))
        assertEquals(PolicyMode.FORCE_ALLOW, decoded["com.example.legacy"]?.get(BgSubsystem.CPU_LIMIT))
        assertEquals(PolicyMode.FORCE_ALLOW, decoded["com.example.legacy"]?.get(BgSubsystem.DOZE_NET))
        assertEquals(PolicyMode.FORCE_ALLOW, decoded["com.example.legacy"]?.get(BgSubsystem.BROADCAST_RESTRICT))
    }

    @Test
    fun snapshotNativeResolution_distinguishesUserCloudAndSplitSubsystems() {
        val snapshot = SystemSnapshot(
            serverInjected = true,
            lists = mapOf(
                NativeListKind.HANS_SYSTEM_WHITE to setOf("com.cloud.white"),
                NativeListKind.HANS_BPM_USER to setOf("com.user.white"),
                NativeListKind.HANS_SYS_BLACK to setOf("com.black.app"),
                NativeListKind.AUTOSTART_SYSTEM_WHITE to setOf("com.auto.cloud"),
                NativeListKind.AUTOSTART_USER_WHITE to setOf("com.auto.user"),
                NativeListKind.AUTOSTART_USER_DENY to setOf("com.auto.closed"),
                NativeListKind.GUARDELF_LIMIT_BLACK to setOf("com.guardelf.black"),
                NativeListKind.CPULIMIT_WHITE to setOf("com.cpu.white"),
                NativeListKind.CPULIMIT_VIP_HBG to setOf("com.cpu.viphbg"),
                NativeListKind.CPULIMIT_BLACK to setOf("com.black.app"),
                NativeListKind.DOZE_NET_WHITE to setOf("com.net.white"),
                NativeListKind.DOZE_NET_CLOUD_RESTRICT to setOf("com.net.cloud.off"),
                NativeListKind.NET_DEEPSLEEP_RESTRICT to setOf("com.net.local"),
                NativeListKind.BROADCAST_WHITE to setOf("com.br.white"),
                NativeListKind.RECENT_LOCKED to setOf("com.locked.app"),
                NativeListKind.ATHENA_TRIM_WHITE to setOf("com.trim.cloud"),
            ),
            listActive = mapOf(
                NativeListKind.DOZE_NET_CLOUD_RESTRICT to false,
            ),
        )

        val cloudHans = snapshot.nativeResolutionFor("com.cloud.white", BgSubsystem.HANS_FREEZE)
        assertEquals(NativeState.WHITELISTED, cloudHans.state)
        assertEquals(NativeSource.CLOUD_DEFAULT, cloudHans.source)

        val userHans = snapshot.nativeResolutionFor("com.user.white", BgSubsystem.HANS_FREEZE)
        assertEquals(NativeState.WHITELISTED, userHans.state)
        assertEquals(NativeSource.SYSTEM_USER, userHans.source)

        val userAuto = snapshot.nativeResolutionFor("com.auto.user", BgSubsystem.AUTO_START)
        assertEquals(NativeState.WHITELISTED, userAuto.state)
        assertEquals(NativeSource.SYSTEM_USER, userAuto.source)

        val closedAuto = snapshot.nativeResolutionFor("com.auto.closed", BgSubsystem.AUTO_START)
        assertEquals(NativeState.NEUTRAL, closedAuto.state)

        val guardElfBlack = snapshot.nativeResolutionFor("com.guardelf.black", BgSubsystem.BATTERY_DOZE)
        assertEquals(NativeState.BLACKLISTED, guardElfBlack.state)
        assertEquals(NativeSource.CLOUD_DEFAULT, guardElfBlack.source)

        val cloudTrim = snapshot.nativeResolutionFor("com.trim.cloud", BgSubsystem.ATHENA_RECENT)
        assertEquals(NativeState.WHITELISTED, cloudTrim.state)
        assertEquals(NativeSource.CLOUD_DEFAULT, cloudTrim.source)

        assertEquals(NativeState.WHITELISTED, snapshot.nativeStateFor("com.cpu.white", BgSubsystem.CPU_LIMIT))
        assertEquals(NativeState.NEUTRAL, snapshot.nativeStateFor("com.cpu.viphbg", BgSubsystem.CPU_LIMIT))
        assertEquals(NativeState.BLACKLISTED, snapshot.nativeStateFor("com.black.app", BgSubsystem.CPU_LIMIT))
        assertEquals(NativeState.WHITELISTED, snapshot.nativeStateFor("com.net.white", BgSubsystem.DOZE_NET))
        assertEquals(NativeState.NEUTRAL, snapshot.nativeStateFor("com.net.cloud.off", BgSubsystem.DOZE_NET))
        assertFalse(snapshot.isListActive(NativeListKind.DOZE_NET_CLOUD_RESTRICT))
        assertEquals(setOf("com.net.cloud.off"), snapshot.listOfKind(NativeListKind.DOZE_NET_CLOUD_RESTRICT))
        val localNet = snapshot.nativeResolutionFor("com.net.local", BgSubsystem.DOZE_NET)
        assertEquals(NativeState.BLACKLISTED, localNet.state)
        assertEquals(NativeSource.SYSTEM_USER, localNet.source)
        assertEquals(NativeState.WHITELISTED, snapshot.nativeStateFor("com.br.white", BgSubsystem.BROADCAST_RESTRICT))
        assertEquals(NativeState.WHITELISTED, snapshot.nativeStateFor("com.locked.app", BgSubsystem.ATHENA_RECENT))
    }
}
