package io.github.yukino1111.colorosbgmanager.model

import android.content.SharedPreferences
import android.os.Bundle

enum class BgSubsystem(
    val key: String,
    val title: String,
    val shortTitle: String,
    val description: String,
    val sourceSummary: String,
) {
    HANS_FREEZE(
        key = "hans",
        title = "HANS 墓碑冻结",
        shortTitle = "墓碑",
        description = "拦截切后台后的 Cgroup 冻结、Socket 断网与严格省电限制",
        sourceSummary = "sys_elsa_config_list.xml + bpm.xml + fixedRecord.xml",
    ),
    AUTO_START(
        key = "autostart",
        title = "自启动管控",
        shortTitle = "自启",
        description = "控制开机、定时及系统事件下的应用自启动权限",
        sourceSummary = "startup_v3 + autostart_white_list.txt + startup_dynamic_list",
    ),
    ASSOCIATE_START(
        key = "associate",
        title = "关联启动管控",
        shortTitle = "关联",
        description = "控制被其他应用通过 Service / Receiver / Provider 拉起",
        sourceSummary = "startup_v3 + associate_white_list.txt + startup_dynamic_list",
    ),
    BATTERY_DOZE(
        key = "battery_doze",
        title = "电池后台 / GuardElf / Doze",
        shortTitle = "电池Doze",
        description = "豁免 OGuard 后台强杀、GuardElf 云控限制与深度 Doze",
        sourceSummary = "allow_prohibit + not_restrict + doze_wl + RomUpdate limitbkg",
    ),
    ATHENA_RECENT(
        key = "athena",
        title = "Athena 内存清理与锁定",
        shortTitle = "内存防杀",
        description = "豁免 Athena 缓存/空进程清理、主动内存回收与强制停止",
        sourceSummary = "OplusAthenaManager + sys_elsa trimMem + recenttask",
    ),
    CPU_LIMIT(
        key = "cpu_limit",
        title = "CPU 限核",
        shortTitle = "限核",
        description = "控制后台进程的 Lbg/Hbg 核组限制与 VIP 保核调度",
        sourceSummary = "sys_cpulimit_config.xml + sys_elsa cpuCtlWhiteList",
    ),
    DOZE_NET(
        key = "doze_net",
        title = "Doze 限网",
        shortTitle = "限网",
        description = "控制深度休眠下的后台联网代理与流量白名单",
        sourceSummary = "deepsleep + guardelf net + sys_elsa trafficWhite",
    ),
    BROADCAST_RESTRICT(
        key = "broadcast_restrict",
        title = "广播限流",
        shortTitle = "广播",
        description = "控制高频系统广播过滤与广播限制白名单豁免",
        sourceSummary = "OplusBroadcastRestriction + BrOpt + skipbroadcast",
    );

    fun supportedModes(): List<PolicyMode> = when (this) {
        CPU_LIMIT -> listOf(
            PolicyMode.FOLLOW_SYSTEM,
            PolicyMode.FORCE_ALLOW,
            PolicyMode.FORCE_RESTRICT_HBG,
            PolicyMode.FORCE_RESTRICT,
        )
        else -> listOf(
            PolicyMode.FOLLOW_SYSTEM,
            PolicyMode.FORCE_ALLOW,
            PolicyMode.FORCE_RESTRICT,
        )
    }

    companion object {
        fun fromKey(key: String): BgSubsystem? = entries.firstOrNull { it.key == key }
    }
}

enum class PolicyMode(val code: Int, val label: String) {
    FOLLOW_SYSTEM(0, "跟随系统"),
    FORCE_ALLOW(1, "强制放行"),
    FORCE_RESTRICT(2, "强制管控"),
    FORCE_RESTRICT_HBG(3, "限核(Hbg)");

    val isRestrict: Boolean get() = this == FORCE_RESTRICT || this == FORCE_RESTRICT_HBG

    companion object {
        fun fromCode(code: Int): PolicyMode = entries.firstOrNull { it.code == code } ?: FOLLOW_SYSTEM
    }
}

enum class NativeSource(val label: String, val shortLabel: String) {
    SYSTEM_USER("系统设置", "设置"),
    CLOUD_DEFAULT("系统默认", "默认"),
}

enum class NativeListKind(
    val title: String,
    val bundleKey: String,
    val subsystem: BgSubsystem,
    val source: NativeSource,
) {
    HANS_SYSTEM_WHITE("墓碑系统默认白名单 (ELSA)", "hans_system_white", BgSubsystem.HANS_FREEZE, NativeSource.CLOUD_DEFAULT),
    HANS_BPM_USER("墓碑系统设置白名单 (fixedRecord)", "hans_bpm_user", BgSubsystem.HANS_FREEZE, NativeSource.SYSTEM_USER),
    HANS_SYS_BLACK("墓碑系统默认黑名单 (SysBlack)", "hans_sys_black", BgSubsystem.HANS_FREEZE, NativeSource.CLOUD_DEFAULT),
    AUTOSTART_SYSTEM_WHITE("自启动系统默认白名单", "autostart_sys_white", BgSubsystem.AUTO_START, NativeSource.CLOUD_DEFAULT),
    AUTOSTART_USER_WHITE("自启动系统设置允许", "autostart_white", BgSubsystem.AUTO_START, NativeSource.SYSTEM_USER),
    AUTOSTART_USER_DENY("自启动系统设置已关记录", "autostart_user_deny", BgSubsystem.AUTO_START, NativeSource.SYSTEM_USER),
    ASSOCIATE_SYSTEM_WHITE("关联启动系统默认白名单", "associate_sys_white", BgSubsystem.ASSOCIATE_START, NativeSource.CLOUD_DEFAULT),
    ASSOCIATE_USER_WHITE("关联启动系统设置允许", "associate_white", BgSubsystem.ASSOCIATE_START, NativeSource.SYSTEM_USER),
    ASSOCIATE_USER_DENY("关联启动系统设置已关记录", "associate_user_deny", BgSubsystem.ASSOCIATE_START, NativeSource.SYSTEM_USER),
    BATTERY_NOT_RESTRICT("电池系统设置允许后台", "battery_not_restrict", BgSubsystem.BATTERY_DOZE, NativeSource.SYSTEM_USER),
    DOZE_WHITE("电池默认不限制与 Doze 白名单", "doze_white", BgSubsystem.BATTERY_DOZE, NativeSource.CLOUD_DEFAULT),
    GUARDELF_LIMIT_BLACK("GuardElf 系统默认限制名单", "guardelf_limit_black", BgSubsystem.BATTERY_DOZE, NativeSource.CLOUD_DEFAULT),
    RECENT_LOCKED("最近任务锁定与内存保护", "recent_locked", BgSubsystem.ATHENA_RECENT, NativeSource.SYSTEM_USER),
    ATHENA_TRIM_WHITE("内存清理系统默认白名单", "athena_trim_white", BgSubsystem.ATHENA_RECENT, NativeSource.CLOUD_DEFAULT),
    RECENT_FORBID("系统禁止锁定名单", "recent_forbid", BgSubsystem.ATHENA_RECENT, NativeSource.CLOUD_DEFAULT),
    CPULIMIT_WHITE("CPU 限核默认白名单", "cpulimit_white", BgSubsystem.CPU_LIMIT, NativeSource.CLOUD_DEFAULT),
    CPULIMIT_VIP_HBG("CPU 限核 VIP 高核组 (Hbg)", "cpulimit_vip_hbg", BgSubsystem.CPU_LIMIT, NativeSource.CLOUD_DEFAULT),
    CPULIMIT_BLACK("CPU 限核默认黑名单 (Lbg)", "cpulimit_black", BgSubsystem.CPU_LIMIT, NativeSource.CLOUD_DEFAULT),
    DOZE_NET_WHITE("DeepSleep 限网默认白名单", "doze_net_white", BgSubsystem.DOZE_NET, NativeSource.CLOUD_DEFAULT),
    DOZE_NET_CLOUD_RESTRICT("DeepSleep 限网默认限制名单", "doze_net_cloud_restrict", BgSubsystem.DOZE_NET, NativeSource.CLOUD_DEFAULT),
    NET_DEEPSLEEP_RESTRICT("休眠 / 息屏本地限制名单", "net_deepsleep", BgSubsystem.DOZE_NET, NativeSource.SYSTEM_USER),
    BROADCAST_WHITE("广播限流默认白名单", "broadcast_white", BgSubsystem.BROADCAST_RESTRICT, NativeSource.CLOUD_DEFAULT),
}

enum class NativeState(val label: String) {
    WHITELISTED("放行"),
    BLACKLISTED("限制"),
    NEUTRAL("常规"),
}

enum class NativeCpuTier {
    HBG,
    LBG,
}

data class NativeResolution(
    val state: NativeState,
    val source: NativeSource? = null,
    val cpuTier: NativeCpuTier? = null,
)

data class GlobalRuleFlags(
    val disableGuardElfCpuKill: Boolean = false,
    val disableBroadcastQuotaGlobal: Boolean = false,
    val disableStrictModeFreeze: Boolean = false,
    val verboseLog: Boolean = false,
)

data class SystemSnapshot(
    val serverInjected: Boolean = false,
    val lists: Map<NativeListKind, Set<String>> = emptyMap(),
    val listActive: Map<NativeListKind, Boolean> = emptyMap(),
    val hookStatus: Map<String, Boolean> = emptyMap(),
    val rawSnapshotTimestamp: Long = 0L,
) {
    fun isListActive(kind: NativeListKind): Boolean = listActive[kind] ?: true

    fun nativeStateFor(pkgName: String, subsystem: BgSubsystem): NativeState =
        nativeResolutionFor(pkgName, subsystem).state

    fun nativeResolutionFor(pkgName: String, subsystem: BgSubsystem): NativeResolution = when (subsystem) {
        BgSubsystem.HANS_FREEZE -> when {
            pkgName in listOfKind(NativeListKind.HANS_BPM_USER) ->
                NativeResolution(NativeState.WHITELISTED, NativeSource.SYSTEM_USER)
            isListActive(NativeListKind.HANS_SYSTEM_WHITE) &&
                pkgName in listOfKind(NativeListKind.HANS_SYSTEM_WHITE) ->
                NativeResolution(NativeState.WHITELISTED, NativeSource.CLOUD_DEFAULT)
            isListActive(NativeListKind.HANS_SYS_BLACK) &&
                pkgName in listOfKind(NativeListKind.HANS_SYS_BLACK) ->
                NativeResolution(NativeState.BLACKLISTED, NativeSource.CLOUD_DEFAULT)
            else -> NativeResolution(NativeState.NEUTRAL)
        }

        BgSubsystem.AUTO_START -> when {
            pkgName in listOfKind(NativeListKind.AUTOSTART_USER_WHITE) ->
                NativeResolution(NativeState.WHITELISTED, NativeSource.SYSTEM_USER)
            pkgName in listOfKind(NativeListKind.AUTOSTART_SYSTEM_WHITE) ->
                NativeResolution(NativeState.WHITELISTED, NativeSource.CLOUD_DEFAULT)
            else -> NativeResolution(NativeState.NEUTRAL)
        }

        BgSubsystem.ASSOCIATE_START -> when {
            pkgName in listOfKind(NativeListKind.ASSOCIATE_USER_WHITE) ->
                NativeResolution(NativeState.WHITELISTED, NativeSource.SYSTEM_USER)
            pkgName in listOfKind(NativeListKind.ASSOCIATE_SYSTEM_WHITE) ->
                NativeResolution(NativeState.WHITELISTED, NativeSource.CLOUD_DEFAULT)
            else -> NativeResolution(NativeState.NEUTRAL)
        }

        BgSubsystem.BATTERY_DOZE -> when {
            pkgName in listOfKind(NativeListKind.BATTERY_NOT_RESTRICT) ->
                NativeResolution(NativeState.WHITELISTED, NativeSource.SYSTEM_USER)
            pkgName in listOfKind(NativeListKind.DOZE_WHITE) ->
                NativeResolution(NativeState.WHITELISTED, NativeSource.CLOUD_DEFAULT)
            isListActive(NativeListKind.GUARDELF_LIMIT_BLACK) &&
                pkgName in listOfKind(NativeListKind.GUARDELF_LIMIT_BLACK) ->
                NativeResolution(NativeState.BLACKLISTED, NativeSource.CLOUD_DEFAULT)
            else -> NativeResolution(NativeState.NEUTRAL)
        }

        BgSubsystem.ATHENA_RECENT -> when {
            pkgName in listOfKind(NativeListKind.RECENT_LOCKED) ->
                NativeResolution(NativeState.WHITELISTED, NativeSource.SYSTEM_USER)
            isListActive(NativeListKind.ATHENA_TRIM_WHITE) &&
                pkgName in listOfKind(NativeListKind.ATHENA_TRIM_WHITE) ->
                NativeResolution(NativeState.WHITELISTED, NativeSource.CLOUD_DEFAULT)
            pkgName in listOfKind(NativeListKind.RECENT_FORBID) ->
                NativeResolution(NativeState.BLACKLISTED, NativeSource.CLOUD_DEFAULT)
            else -> NativeResolution(NativeState.NEUTRAL)
        }

        BgSubsystem.CPU_LIMIT -> when {
            isListActive(NativeListKind.CPULIMIT_WHITE) &&
                pkgName in listOfKind(NativeListKind.CPULIMIT_WHITE) ->
                NativeResolution(NativeState.WHITELISTED, NativeSource.CLOUD_DEFAULT)
            isListActive(NativeListKind.CPULIMIT_VIP_HBG) &&
                pkgName in listOfKind(NativeListKind.CPULIMIT_VIP_HBG) ->
                NativeResolution(NativeState.BLACKLISTED, NativeSource.CLOUD_DEFAULT, NativeCpuTier.HBG)
            pkgName in listOfKind(NativeListKind.CPULIMIT_BLACK) ->
                NativeResolution(NativeState.BLACKLISTED, NativeSource.CLOUD_DEFAULT, NativeCpuTier.LBG)
            else -> NativeResolution(NativeState.NEUTRAL)
        }

        BgSubsystem.DOZE_NET -> when {
            pkgName in listOfKind(NativeListKind.DOZE_NET_WHITE) ->
                NativeResolution(NativeState.WHITELISTED, NativeSource.CLOUD_DEFAULT)
            pkgName in listOfKind(NativeListKind.NET_DEEPSLEEP_RESTRICT) ->
                NativeResolution(NativeState.BLACKLISTED, NativeSource.SYSTEM_USER)
            isListActive(NativeListKind.DOZE_NET_CLOUD_RESTRICT) &&
                pkgName in listOfKind(NativeListKind.DOZE_NET_CLOUD_RESTRICT) ->
                NativeResolution(NativeState.BLACKLISTED, NativeSource.CLOUD_DEFAULT)
            else -> NativeResolution(NativeState.NEUTRAL)
        }

        BgSubsystem.BROADCAST_RESTRICT -> when {
            pkgName in listOfKind(NativeListKind.BROADCAST_WHITE) ->
                NativeResolution(NativeState.WHITELISTED, NativeSource.CLOUD_DEFAULT)
            else -> NativeResolution(NativeState.NEUTRAL)
        }
    }

    fun listOfKind(kind: NativeListKind): Set<String> = lists[kind].orEmpty()

    companion object {
        fun fromBundle(bundle: Bundle?): SystemSnapshot {
            if (bundle == null || !bundle.getBoolean("bgmaster_ok", false)) {
                return SystemSnapshot(serverInjected = false)
            }
            val parsedLists = NativeListKind.entries.associateWith { kind ->
                bundle.getStringArrayList(kind.bundleKey)?.toSet().orEmpty()
            }
            val inactiveKeys = bundle.getStringArrayList("inactive_lists")?.toSet().orEmpty()
            val parsedActive = NativeListKind.entries.associateWith { kind ->
                kind.bundleKey !in inactiveKeys
            }
            val hookBundle = bundle.getBundle("hook_status")
            val hooks = buildMap {
                hookBundle?.keySet()?.forEach { key ->
                    put(key, hookBundle.getBoolean(key, false))
                }
            }
            return SystemSnapshot(
                serverInjected = true,
                lists = parsedLists,
                listActive = parsedActive,
                hookStatus = hooks,
                rawSnapshotTimestamp = bundle.getLong("timestamp", System.currentTimeMillis()),
            )
        }
    }
}

object RulesCodec {
    const val PREF_NAME = "bgmanager_rules"
    const val KEY_RULES_SET = "pkg_subsystem_rules"
    const val KEY_GLOBAL_GUARDELF = "global_disable_guardelf_kill"
    const val KEY_GLOBAL_BROADCAST = "global_disable_broadcast_restrict"
    const val KEY_GLOBAL_STRICT = "global_disable_hans_strict"
    const val KEY_VERBOSE_LOG = "global_verbose_log"

    const val BRIDGE_URI = "content://settings/system"
    const val BRIDGE_METHOD = "bgmanager_query_snapshot"

    fun encodeRules(rules: Map<String, Map<BgSubsystem, PolicyMode>>): Set<String> {
        val out = LinkedHashSet<String>()
        for ((pkg, map) in rules) {
            for ((sub, mode) in map) {
                if (mode != PolicyMode.FOLLOW_SYSTEM) {
                    out.add("$pkg|${sub.key}|${mode.code}")
                }
            }
        }
        return out
    }

    fun decodeRules(raw: Set<String>?): Map<String, Map<BgSubsystem, PolicyMode>> {
        if (raw.isNullOrEmpty()) return emptyMap()
        val result = LinkedHashMap<String, MutableMap<BgSubsystem, PolicyMode>>()
        for (entry in raw) {
            val parts = entry.split('|')
            if (parts.size != 3) continue
            val pkg = parts[0]
            val mode = PolicyMode.fromCode(parts[2].toIntOrNull() ?: 0)
            if (pkg.isBlank() || mode == PolicyMode.FOLLOW_SYSTEM) continue
            val targets = if (parts[1] == "cpulimit_net") {
                listOf(BgSubsystem.CPU_LIMIT, BgSubsystem.DOZE_NET, BgSubsystem.BROADCAST_RESTRICT)
            } else {
                listOfNotNull(BgSubsystem.fromKey(parts[1]))
            }
            if (targets.isEmpty()) continue
            val pkgMap = result.getOrPut(pkg) { mutableMapOf() }
            targets.forEach { sub ->
                pkgMap[sub] = if (sub != BgSubsystem.CPU_LIMIT && mode == PolicyMode.FORCE_RESTRICT_HBG) {
                    PolicyMode.FORCE_RESTRICT
                } else {
                    mode
                }
            }
        }
        return result
    }

    fun readGlobalFlags(prefs: SharedPreferences): GlobalRuleFlags = GlobalRuleFlags(
        disableGuardElfCpuKill = prefs.getBoolean(KEY_GLOBAL_GUARDELF, false),
        disableBroadcastQuotaGlobal = prefs.getBoolean(KEY_GLOBAL_BROADCAST, false),
        disableStrictModeFreeze = prefs.getBoolean(KEY_GLOBAL_STRICT, false),
        verboseLog = prefs.getBoolean(KEY_VERBOSE_LOG, false),
    )
}
