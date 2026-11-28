package io.github.yukino1111.colorosbgmanager

import io.github.yukino1111.colorosbgmanager.data.RootProcessClient
import io.github.yukino1111.colorosbgmanager.model.AppUserKey
import io.github.yukino1111.colorosbgmanager.model.BgSubsystem
import io.github.yukino1111.colorosbgmanager.model.BridgeAppEntry
import io.github.yukino1111.colorosbgmanager.model.DeviceUserInfo
import io.github.yukino1111.colorosbgmanager.model.DozePolicyHelper
import io.github.yukino1111.colorosbgmanager.model.GlobalRuleFlags
import io.github.yukino1111.colorosbgmanager.model.HookDiagnosticEntry
import io.github.yukino1111.colorosbgmanager.model.HookPointStatus
import io.github.yukino1111.colorosbgmanager.model.NativeCpuTier
import io.github.yukino1111.colorosbgmanager.model.NativeListKind
import io.github.yukino1111.colorosbgmanager.model.NativeSource
import io.github.yukino1111.colorosbgmanager.model.NativeState
import io.github.yukino1111.colorosbgmanager.model.PolicyMode
import io.github.yukino1111.colorosbgmanager.model.ProcessDetailInfo
import io.github.yukino1111.colorosbgmanager.model.RulesCodec
import io.github.yukino1111.colorosbgmanager.model.RuntimeEvent
import io.github.yukino1111.colorosbgmanager.model.SystemSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BgControlModelsTest {

    @Test
    fun encodeAndDecode_preservesMultiUserOverridesAndDropsFollowSystem() {
        val k0 = AppUserKey("com.example.keep", 0)
        val k999 = AppUserKey("com.example.keep", 999)
        val t0 = AppUserKey("com.example.tame", 0)
        val original = mapOf(
            k0 to mapOf(
                BgSubsystem.HANS_FREEZE to PolicyMode.FORCE_ALLOW,
                BgSubsystem.AUTO_START to PolicyMode.FOLLOW_SYSTEM,
                BgSubsystem.CPU_LIMIT to PolicyMode.FORCE_ALLOW,
                BgSubsystem.DOZE_NET to PolicyMode.FORCE_ALLOW,
                BgSubsystem.BROADCAST_RESTRICT to PolicyMode.FORCE_RESTRICT,
            ),
            k999 to mapOf(
                BgSubsystem.ATHENA_RECENT to PolicyMode.FORCE_RESTRICT,
            ),
            t0 to mapOf(
                BgSubsystem.BATTERY_DOZE to PolicyMode.FORCE_RESTRICT,
            ),
        )

        val encoded = RulesCodec.encodeRules(original)
        val decoded = RulesCodec.decodeRules(encoded)

        assertEquals(PolicyMode.FORCE_ALLOW, decoded[k0]?.get(BgSubsystem.HANS_FREEZE))
        assertFalse(decoded[k0]?.containsKey(BgSubsystem.AUTO_START) == true)
        assertEquals(PolicyMode.FORCE_ALLOW, decoded[k0]?.get(BgSubsystem.CPU_LIMIT))
        assertEquals(PolicyMode.FORCE_ALLOW, decoded[k0]?.get(BgSubsystem.DOZE_NET))
        assertEquals(PolicyMode.FORCE_RESTRICT, decoded[k0]?.get(BgSubsystem.BROADCAST_RESTRICT))
        assertEquals(PolicyMode.FORCE_RESTRICT, decoded[k999]?.get(BgSubsystem.ATHENA_RECENT))
        assertEquals(PolicyMode.FORCE_RESTRICT, decoded[t0]?.get(BgSubsystem.BATTERY_DOZE))
    }

    @Test
    fun decodeRules_migratesLegacyCpuNetBroadcastKey() {
        val decoded = RulesCodec.decodeRules(setOf("com.example.legacy|cpulimit_net|1"))
        val key = AppUserKey("com.example.legacy", 0)
        assertEquals(PolicyMode.FORCE_ALLOW, decoded[key]?.get(BgSubsystem.CPU_LIMIT))
        assertEquals(PolicyMode.FORCE_ALLOW, decoded[key]?.get(BgSubsystem.DOZE_NET))
        assertEquals(PolicyMode.FORCE_ALLOW, decoded[key]?.get(BgSubsystem.BROADCAST_RESTRICT))
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
        val vipHbgRes = snapshot.nativeResolutionFor("com.cpu.viphbg", BgSubsystem.CPU_LIMIT)
        assertEquals(NativeState.BLACKLISTED, vipHbgRes.state)
        assertEquals(NativeCpuTier.HBG, vipHbgRes.cpuTier)
        val cpuBlackRes = snapshot.nativeResolutionFor("com.black.app", BgSubsystem.CPU_LIMIT)
        assertEquals(NativeState.BLACKLISTED, cpuBlackRes.state)
        assertEquals(NativeCpuTier.LBG, cpuBlackRes.cpuTier)
        assertEquals(NativeState.WHITELISTED, snapshot.nativeStateFor("com.net.white", BgSubsystem.DOZE_NET))
        assertEquals(NativeState.NEUTRAL, snapshot.nativeStateFor("com.net.cloud.off", BgSubsystem.DOZE_NET))
        assertFalse(snapshot.isListActive(NativeListKind.DOZE_NET_CLOUD_RESTRICT))
        assertEquals(setOf("com.net.cloud.off"), snapshot.listOfKind(NativeListKind.DOZE_NET_CLOUD_RESTRICT))
        val localNet = snapshot.nativeResolutionFor("com.net.local", BgSubsystem.DOZE_NET)
        assertEquals(NativeState.BLACKLISTED, localNet.state)
        assertEquals(NativeSource.SYSTEM_USER, localNet.source)
        assertEquals(NativeState.WHITELISTED, snapshot.nativeStateFor("com.br.white", BgSubsystem.BROADCAST_RESTRICT))
        assertEquals(NativeState.WHITELISTED, snapshot.nativeStateFor("com.locked.app", BgSubsystem.ATHENA_RECENT))

        val disabledSnapshot = snapshot.copy(
            listActive = mapOf(
                NativeListKind.HANS_SYSTEM_WHITE to false,
                NativeListKind.HANS_SYS_BLACK to false,
                NativeListKind.ATHENA_TRIM_WHITE to false,
                NativeListKind.CPULIMIT_WHITE to false,
                NativeListKind.CPULIMIT_VIP_HBG to false,
            ),
        )
        assertEquals(NativeState.NEUTRAL, disabledSnapshot.nativeStateFor("com.cloud.white", BgSubsystem.HANS_FREEZE))
        assertEquals(NativeState.WHITELISTED, disabledSnapshot.nativeStateFor("com.user.white", BgSubsystem.HANS_FREEZE))
        assertEquals(NativeState.NEUTRAL, disabledSnapshot.nativeStateFor("com.black.app", BgSubsystem.HANS_FREEZE))
        assertEquals(NativeState.NEUTRAL, disabledSnapshot.nativeStateFor("com.trim.cloud", BgSubsystem.ATHENA_RECENT))
        assertEquals(NativeState.NEUTRAL, disabledSnapshot.nativeStateFor("com.cpu.white", BgSubsystem.CPU_LIMIT))
        assertEquals(NativeState.NEUTRAL, disabledSnapshot.nativeStateFor("com.cpu.viphbg", BgSubsystem.CPU_LIMIT))
    }

    @Test
    fun runtimeEvent_encodeAndDecode_roundTripsCleanly() {
        val ev = RuntimeEvent(
            timestampMs = 1795426000123L,
            pkgName = "oolong.client",
            subsystemKey = BgSubsystem.ATHENA_RECENT.key,
            action = "skip_nirvana",
            detail = "cch-empty\tcheck",
            userId = 999,
        )
        val decoded = RuntimeEvent.decode(ev.encode())
        assertEquals(1795426000123L, decoded?.timestampMs)
        assertEquals("oolong.client", decoded?.pkgName)
        assertEquals("athena", decoded?.subsystemKey)
        assertEquals("skip_nirvana", decoded?.action)
        assertEquals("cch-empty check", decoded?.detail)
        assertEquals(999, decoded?.userId)

        val legacyDecoded = RuntimeEvent.decode("1795426000123\toolong.client\tathena\tskip_nirvana\tcch-empty check")
        assertEquals(0, legacyDecoded?.userId)
    }

    @Test
    fun telemetryAndMultiUserModels_roundTripCleanly() {
        val user = DeviceUserInfo(999, "MultiApp\tClone", isClone = true)
        val decodedUser = DeviceUserInfo.decode(user.encode())
        assertEquals(999, decodedUser?.userId)
        assertEquals("MultiApp Clone", decodedUser?.name)
        assertTrue(decodedUser?.isClone == true)

        val app = BridgeAppEntry("com.tencent.mm", 999, 99910123, "微信(分身)", isSystem = false, isSuspended = true)
        val decodedApp = BridgeAppEntry.decode(app.encode())
        assertEquals(app, decodedApp)

        val proc = ProcessDetailInfo(
            pid = 4321,
            userId = 999,
            uid = 99910123,
            processName = "com.tencent.mm:push",
            packageName = "com.tencent.mm",
            stateLabel = "BFGS",
            isCached = false,
            oomAdj = 200,
            memoryKb = 102400L,
            cpuPercent = 2.5f,
            uptimeMs = 60000L,
            services = listOf(".PushService", ".CoreService"),
        )
        val decodedProc = ProcessDetailInfo.decode(proc.encode())
        assertEquals(proc, decodedProc)

        val freezeSet = setOf(AppUserKey("com.taobao.taobao", 0), AppUserKey("com.tencent.mm", 999))
        val decodedFreeze = RulesCodec.decodeFreezePackages(RulesCodec.encodeFreezePackages(freezeSet))
        assertEquals(freezeSet, decodedFreeze)

        assertEquals(0, RulesCodec.normalizeAutoFreezeDelaySec(0))
        assertEquals(0, RulesCodec.normalizeAutoFreezeDelaySec(-5))
        assertEquals(60, RulesCodec.normalizeAutoFreezeDelaySec(10))
        assertEquals(60, RulesCodec.normalizeAutoFreezeDelaySec(60))
        assertEquals(300, RulesCodec.normalizeAutoFreezeDelaySec(300))
        assertEquals(300, RulesCodec.normalizeAutoFreezeDelaySec(999))
    }

    @Test
    fun rootTopParser_extractsRootAndFiltersApps() {
        val sample = """
            28945 u0_a226      10226 170M  95M S  0.0   0:00.44       00:29 com.oplus.vdc
            28904 root             0    0    0 I  0.0   0:00.00       00:33 [kworker/2:6]
            31000 system        1000 902M 120M S  7.1   3:19.65       18:38 system_server
            30510 root             0 2.6M 1.2M S  0.3   0:00.27       18:40 zn-daemon --flag
            30511 root             0 232M 180M S  0.0   0:12.00       18:40 zygote64
            30512 root             0 4.0M 2.0M R  1.0   0:00.01       00:00 top -b -n 1 -q -o PID,USER,UID,RES,SHR,S,%CPU,TIME+,ETIME,CMDLINE
            30513 root             0 3.0M 1.5M S  0.5   0:00.01       00:00 /system/bin/sh -c COLUMNS=512 top -b -n 1 -q -o PID,USER,UID,RES,SHR,S,%CPU,TIME+,ETIME,CMDLINE
        """.trimIndent()
        val parsed = RootProcessClient.parseTopOutput(sample)
        assertEquals(4, parsed.size)
        assertEquals(30511, parsed[0].pid)
        assertFalse(parsed[0].isKernelThread)
        assertEquals(30510, parsed[1].pid)
        assertEquals(2662L, parsed[1].resKb)
        assertEquals(0.3f, parsed[1].cpuPercent)
        assertEquals(31000, parsed[2].pid)
        assertEquals(923648L, parsed[2].resKb)
        assertEquals(28904, parsed[3].pid)
        assertTrue(parsed[3].isKernelThread)
    }

    @Test
    fun dozePolicyHelper_restoresOriginalSystemWhitelistOnFollowSystem() {
        val systemIds = intArrayOf(10100, 10200)
        val systemPkgs = arrayOf("com.sys.allow", "com.user.allow")

        val afterAllowIds = DozePolicyHelper.adjustWhitelistAppIds(
            orig = systemIds,
            allowIds = setOf(10300),
            restrictIds = emptySet(),
        )
        assertEquals(listOf(10100, 10200, 10300), afterAllowIds.toList())

        val afterAllowToFollowIds = DozePolicyHelper.adjustWhitelistAppIds(
            orig = systemIds,
            allowIds = emptySet(),
            restrictIds = emptySet(),
        )
        assertEquals(listOf(10100, 10200), afterAllowToFollowIds.toList())

        val afterRestrictIds = DozePolicyHelper.adjustWhitelistAppIds(
            orig = systemIds,
            allowIds = emptySet(),
            restrictIds = setOf(10200),
        )
        assertEquals(listOf(10100), afterRestrictIds.toList())

        val afterRestrictToFollowIds = DozePolicyHelper.adjustWhitelistAppIds(
            orig = systemIds,
            allowIds = emptySet(),
            restrictIds = emptySet(),
        )
        assertEquals(listOf(10100, 10200), afterRestrictToFollowIds.toList())

        val adjustedPkgs = DozePolicyHelper.adjustWhitelistPackages(
            orig = systemPkgs,
            allowPkgs = setOf("com.app.new"),
            restrictPkgs = setOf("com.user.allow"),
        )
        assertEquals(listOf("com.sys.allow", "com.app.new"), adjustedPkgs.toList())

        val restoredPkgs = DozePolicyHelper.adjustWhitelistPackages(
            orig = systemPkgs,
            allowPkgs = emptySet(),
            restrictPkgs = emptySet(),
        )
        assertEquals(listOf("com.sys.allow", "com.user.allow"), restoredPkgs.toList())
    }

    @Test
    fun subsystemPresets_haveExpectedDefaultsAndRoundTrip() {
        assertEquals(
            setOf(
                BgSubsystem.HANS_FREEZE,
                BgSubsystem.AUTO_START,
                BgSubsystem.ASSOCIATE_START,
                BgSubsystem.BATTERY_DOZE,
                BgSubsystem.ATHENA_RECENT,
                BgSubsystem.DOZE_NET,
            ),
            RulesCodec.DEFAULT_ALLOW_PRESET,
        )
        assertEquals(
            setOf(
                BgSubsystem.HANS_FREEZE,
                BgSubsystem.BATTERY_DOZE,
                BgSubsystem.ATHENA_RECENT,
            ),
            RulesCodec.DEFAULT_RESTRICT_PRESET,
        )

        assertEquals(
            RulesCodec.DEFAULT_RESTRICT_PRESET,
            RulesCodec.decodeSubsystemPreset(null, RulesCodec.DEFAULT_RESTRICT_PRESET),
        )
        assertEquals(
            emptySet<BgSubsystem>(),
            RulesCodec.decodeSubsystemPreset("", RulesCodec.DEFAULT_RESTRICT_PRESET),
        )

        val custom = setOf(BgSubsystem.CPU_LIMIT, BgSubsystem.HANS_FREEZE)
        val encoded = RulesCodec.encodeSubsystemPreset(custom)
        assertEquals("hans,cpu_limit", encoded)
        assertEquals(custom, RulesCodec.decodeSubsystemPreset(encoded, RulesCodec.DEFAULT_ALLOW_PRESET))
    }

    @Test
    fun hookDiagnostics_encodeDecodeAndDegradedReportWork() {
        val okEntry = HookDiagnosticEntry("athena", "OplusAthenaAmManager#skipCacheEmptyKill", true, HookPointStatus.HOOKED, "ProcessRecord")
        val optMiss = HookDiagnosticEntry("battery_doze", "DeviceIdleController#legacy", false, HookPointStatus.MISSING_METHOD, "none")
        val reqMiss = HookDiagnosticEntry("athena", "OplusAthenaAmManager#doProactiveClean", true, HookPointStatus.MISSING_METHOD, "available=doProactiveClean(String)")
        val fallback = HookDiagnosticEntry("broadcast_restrict", "OplusListManagerImpl#isSkipBroadcastFlagRestricted(java.lang.String)", true, HookPointStatus.FALLBACK)

        assertEquals(okEntry, HookDiagnosticEntry.decode(okEntry.encode()))
        assertEquals(reqMiss, HookDiagnosticEntry.decode(reqMiss.encode()))

        val snapshot = SystemSnapshot(
            serverInjected = true,
            hookStatus = mapOf(
                BgSubsystem.ATHENA_RECENT.key to true,
                BgSubsystem.BATTERY_DOZE.key to true,
            ),
            hookDiagnostics = listOf(okEntry, optMiss, reqMiss, fallback),
            deviceSummary = "OPD2413 | Android 16 (SDK 36)",
        )

        assertTrue(snapshot.isDegraded(BgSubsystem.ATHENA_RECENT))
        assertFalse(snapshot.isDegraded(BgSubsystem.BATTERY_DOZE))

        val report = snapshot.formatCompatibilityReport("1.1.4 (7)", 102)
        assertTrue(report.contains("OPD2413 | Android 16 (SDK 36)"))
        assertTrue(report.contains("athena: DEGRADED"))
        assertTrue(report.contains("battery_doze: ACTIVE"))
        assertTrue(report.contains("Required Missing/Failed (1):"))
        assertTrue(report.contains("[athena][REQ] OplusAthenaAmManager#doProactiveClean -> missing_method"))
        assertTrue(report.contains("Optional Variants Unmatched (1):"))
        assertTrue(report.contains("[battery_doze][OPT] DeviceIdleController#legacy -> missing_method"))
        assertTrue(report.contains("[broadcast_restrict] OplusListManagerImpl#isSkipBroadcastFlagRestricted(java.lang.String)"))
    }
}
