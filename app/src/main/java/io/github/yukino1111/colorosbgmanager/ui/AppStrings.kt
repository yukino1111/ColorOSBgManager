package io.github.yukino1111.colorosbgmanager.ui

import androidx.compose.runtime.staticCompositionLocalOf
import io.github.yukino1111.colorosbgmanager.model.BgSubsystem
import io.github.yukino1111.colorosbgmanager.model.DeviceUserInfo
import io.github.yukino1111.colorosbgmanager.model.NativeCpuTier
import io.github.yukino1111.colorosbgmanager.model.NativeListKind
import io.github.yukino1111.colorosbgmanager.model.NativeResolution
import io.github.yukino1111.colorosbgmanager.model.NativeSource
import io.github.yukino1111.colorosbgmanager.model.NativeState
import io.github.yukino1111.colorosbgmanager.model.PolicyMode

enum class AppLanguage(val code: String) {
    ZH("zh"),
    EN("en");

    fun toggle(): AppLanguage = if (this == ZH) EN else ZH

    companion object {
        fun fromCode(code: String?, defaultZh: Boolean): AppLanguage = when (code) {
            ZH.code -> ZH
            EN.code -> EN
            else -> if (defaultZh) ZH else EN
        }
    }
}

data class AppStrings(
    val language: AppLanguage,
    val appTitle: String,
    val lposedDisconnected: String,
    val inactiveRebootHint: String,
    val activatedSummary: (Int) -> String,
    val langSwitchLabel: String,
    val refreshCd: String,
    val tabOverview: String,
    val tabApps: String,
    val tabProcesses: String,
    val statusActive: String,
    val statusInactive: String,
    val enableInLsposedHint: String,
    val systemNotLoadedHint: String,
    val waitingXposedServiceHint: String,
    val mountedSubsystems: (Int, Int) -> String,
    val recheck: String,
    val nativeListsTitle: String,
    val nativeListsDesc: (Int, Int) -> String,
    val nativeListsInactive: String,
    val sectionSubsystems: String,
    val hookOk: String,
    val hookMissing: String,
    val systemCounts: (Int, Int) -> String,
    val moduleCounts: (Int, Int) -> String,
    val unmodified: String,
    val sectionGlobals: String,
    val globalGuardElfTitle: String,
    val globalGuardElfSub: String,
    val globalBroadcastTitle: String,
    val globalBroadcastSub: String,
    val globalStrictTitle: String,
    val globalStrictSub: String,
    val globalVerboseTitle: String,
    val globalVerboseSub: String,
    val searchAppsPlaceholder: String,
    val allDimensions: String,
    val autoFreezeFilterTag: (Int) -> String,
    val sectionOverridden: String,
    val sectionNativeLists: String,
    val sectionOtherApps: String,
    val emptyFilterResult: String,
    val collapse: String,
    val expand: String,
    val systemTag: String,
    val defaultRulesText: String,
    val allowShort: String,
    val restrictShort: String,
    val backCd: String,
    val allowPreset: String,
    val restrictPreset: String,
    val restoreDefault: String,
    val resetAllAppsCd: String,
    val resetSingleAppCd: String,
    val resetAllDialogTitle: String,
    val resetAllDialogMessage: (Int) -> String,
    val confirmReset: String,
    val cancel: String,
    val toastResetAllOk: String,
    val toastResetAppOk: String,
    val presetConfigAllowTitle: String,
    val presetConfigRestrictTitle: String,
    val presetConfigHint: String,
    val presetRestoreRecommended: String,
    val presetSaveOnly: String,
    val presetSaveAndApply: String,
    val presetEmptyWarning: String,
    val presetMoreCd: String,
    val notInNativeList: String,
    val currentOverrideAllow: String,
    val currentOverrideRestrict: String,
    val currentOverrideHbg: String,
    val currentOverrideLbg: String,
    val currentNeutral: String,
    val currentPrefix: String,
    val cloudDefaultListsHeader: String,
    val systemUserListsHeader: String,
    val installedSummary: (Int, Int) -> String,
    val nativeDetailSubtitle: (String, Int) -> String,
    val cloudInactivePill: String,
    val cloudSwitchOffSuffix: String,
    val filterPkgPlaceholder: String,
    val installed: String,
    val notInstalled: String,
    val toastRefreshed: (Int, Int, Int) -> String,
    val toastNotInjected: String,
    val toastNeedLposedRule: String,
    val toastNeedLposedSetting: String,
    val sectionRecentEvents: String,
    val noRecentEvents: String,
    val sectionRuntimeStatus: String,
    val runtimeDozeWl: String,
    val runtimeStandbyBucket: String,
    val runtimeRunningProcs: String,
    val runtimeWhitelisted: String,
    val runtimeNotWhitelisted: String,
    val runtimeNotRunning: String,
    val sectionAppEvents: String,
    val searchRootPlaceholder: String,
    val showKernelThreads: String,
    val allUsersChip: String,
    val userBadge: (Int) -> String,
    val rootUnavailableHint: (String?) -> String,
    val retryRootButton: String,
    val processGroupRunning: String,
    val processGroupCached: String,
    val processGroupStopped: String,
    val processGroupRoot: String,
    val procMemLabel: String,
    val procCpuLabel: String,
    val procUptimeLabel: String,
    val procStateLabel: String,
    val procAndSvcCount: (Int, Int) -> String,
    val hostedServicesCount: (Int) -> String,
    val noActiveServices: String,
    val extraServicesHeader: String,
    val closeDialog: String,
    val openAppSettingsCd: String,
    val sectionFreezeControl: String,
    val autoFreezeSwitchTitle: String,
    val autoFreezeSwitchSub: String,
    val autoUnfreezeSwitchTitle: String,
    val autoUnfreezeSwitchSub: String,
    val freezeStatusSuspended: String,
    val freezeStatusActive: String,
    val actionFreezeNow: String,
    val actionUnfreezeNow: String,
    val actionLaunchInApp: String,
    val actionPinShortcut: String,
    val autoFreezeDelayTitle: String,
    val autoFreezeDelaySub: (Int) -> String,
    val autoFreezeDelayOptionLabel: (Int) -> String,
    val toastSuspendOk: String,
    val toastUnsuspendOk: String,
    val toastActionFailed: String,
    val toastLaunchFailed: String,
    val toastShortcutRequested: String,
    val toastShortcutUnsupported: String,
    val aboutTitle: String,
    val aboutRepoTitle: String,
    val aboutRepoUrl: String,
    val aboutLinkCopied: String,
    val aboutOpenFailed: String,
) {
    fun subsystemTitle(sub: BgSubsystem): String = when (language) {
        AppLanguage.ZH -> sub.title
        AppLanguage.EN -> when (sub) {
            BgSubsystem.HANS_FREEZE -> "HANS Freeze"
            BgSubsystem.AUTO_START -> "AutoStart"
            BgSubsystem.ASSOCIATE_START -> "Associate Start"
            BgSubsystem.BATTERY_DOZE -> "Battery / GuardElf / Doze"
            BgSubsystem.ATHENA_RECENT -> "Athena Memory & Lock"
            BgSubsystem.CPU_LIMIT -> "CPU Core Limit"
            BgSubsystem.DOZE_NET -> "Doze Network"
            BgSubsystem.BROADCAST_RESTRICT -> "Broadcast Throttle"
        }
    }

    fun subsystemShort(sub: BgSubsystem): String = when (language) {
        AppLanguage.ZH -> sub.shortTitle
        AppLanguage.EN -> when (sub) {
            BgSubsystem.HANS_FREEZE -> "HANS"
            BgSubsystem.AUTO_START -> "AutoStart"
            BgSubsystem.ASSOCIATE_START -> "Assoc"
            BgSubsystem.BATTERY_DOZE -> "Doze"
            BgSubsystem.ATHENA_RECENT -> "Athena"
            BgSubsystem.CPU_LIMIT -> "CPU"
            BgSubsystem.DOZE_NET -> "Net"
            BgSubsystem.BROADCAST_RESTRICT -> "Broadcast"
        }
    }

    fun subsystemDesc(sub: BgSubsystem): String = when (language) {
        AppLanguage.ZH -> sub.description
        AppLanguage.EN -> when (sub) {
            BgSubsystem.HANS_FREEZE -> "Control cgroup freeze, background socket blocking, and strict power limits"
            BgSubsystem.AUTO_START -> "Control app auto-start on boot, schedule, or system triggers"
            BgSubsystem.ASSOCIATE_START -> "Control indirect launch via Service / Receiver / ContentProvider"
            BgSubsystem.BATTERY_DOZE -> "Control OGuard background kill, GuardElf cloud limits, and deep Doze"
            BgSubsystem.ATHENA_RECENT -> "Control Athena cache/empty process cleanup, memory trim, and force-stop"
            BgSubsystem.CPU_LIMIT -> "Control background Lbg/Hbg cpuset throttling and VIP core retention"
            BgSubsystem.DOZE_NET -> "Control deep-sleep network proxy limits and traffic whitelist"
            BgSubsystem.BROADCAST_RESTRICT -> "Control redundant system broadcast filtering and restriction whitelists"
        }
    }

    fun nativeListTitle(kind: NativeListKind): String = when (language) {
        AppLanguage.ZH -> kind.title
        AppLanguage.EN -> when (kind) {
            NativeListKind.HANS_SYSTEM_WHITE -> "HANS Default Whitelist (ELSA)"
            NativeListKind.HANS_BPM_USER -> "HANS Settings Whitelist (fixedRecord)"
            NativeListKind.HANS_SYS_BLACK -> "HANS Default Blacklist (SysBlack)"
            NativeListKind.AUTOSTART_SYSTEM_WHITE -> "AutoStart Default Whitelist"
            NativeListKind.AUTOSTART_USER_WHITE -> "AutoStart Settings Allow"
            NativeListKind.AUTOSTART_USER_DENY -> "AutoStart Settings Denied Log"
            NativeListKind.ASSOCIATE_SYSTEM_WHITE -> "Associate Start Default Whitelist"
            NativeListKind.ASSOCIATE_USER_WHITE -> "Associate Start Settings Allow"
            NativeListKind.ASSOCIATE_USER_DENY -> "Associate Start Settings Denied Log"
            NativeListKind.BATTERY_NOT_RESTRICT -> "Battery Settings Unrestricted"
            NativeListKind.DOZE_WHITE -> "Battery Default & Doze Whitelist"
            NativeListKind.GUARDELF_LIMIT_BLACK -> "GuardElf Default Restrict List"
            NativeListKind.RECENT_LOCKED -> "Recents Lock & Memory Protect"
            NativeListKind.ATHENA_TRIM_WHITE -> "Athena Trim Default Whitelist"
            NativeListKind.RECENT_FORBID -> "System Lock-Forbidden List"
            NativeListKind.CPULIMIT_WHITE -> "CPU Limit Default Whitelist"
            NativeListKind.CPULIMIT_VIP_HBG -> "CPU Limit VIP High-BG Group (Hbg)"
            NativeListKind.CPULIMIT_BLACK -> "CPU Limit Default Blacklist (Lbg)"
            NativeListKind.DOZE_NET_WHITE -> "DeepSleep Net Default Whitelist"
            NativeListKind.DOZE_NET_CLOUD_RESTRICT -> "DeepSleep Net Default Restrict List"
            NativeListKind.NET_DEEPSLEEP_RESTRICT -> "Sleep / Screen-Off Local Restrict List"
            NativeListKind.BROADCAST_WHITE -> "Broadcast Throttle Default Whitelist"
        }
    }

    fun policyModeLabel(
        mode: PolicyMode,
        subsystem: BgSubsystem? = null,
        compact: Boolean = false,
    ): String = when (language) {
        AppLanguage.ZH -> when {
            subsystem == BgSubsystem.CPU_LIMIT && compact -> when (mode) {
                PolicyMode.FOLLOW_SYSTEM -> "跟随"
                PolicyMode.FORCE_ALLOW -> "放行"
                PolicyMode.FORCE_RESTRICT_HBG -> "Hbg"
                PolicyMode.FORCE_RESTRICT -> "Lbg"
            }
            subsystem == BgSubsystem.CPU_LIMIT -> when (mode) {
                PolicyMode.FOLLOW_SYSTEM -> "跟随系统"
                PolicyMode.FORCE_ALLOW -> "强制放行"
                PolicyMode.FORCE_RESTRICT_HBG -> "限核 (Hbg)"
                PolicyMode.FORCE_RESTRICT -> "限核 (Lbg)"
            }
            else -> when (mode) {
                PolicyMode.FOLLOW_SYSTEM -> "跟随系统"
                PolicyMode.FORCE_ALLOW -> "强制放行"
                PolicyMode.FORCE_RESTRICT,
                PolicyMode.FORCE_RESTRICT_HBG -> "强制管控"
            }
        }
        AppLanguage.EN -> when {
            subsystem == BgSubsystem.CPU_LIMIT && compact -> when (mode) {
                PolicyMode.FOLLOW_SYSTEM -> "System"
                PolicyMode.FORCE_ALLOW -> "Allow"
                PolicyMode.FORCE_RESTRICT_HBG -> "Hbg"
                PolicyMode.FORCE_RESTRICT -> "Lbg"
            }
            subsystem == BgSubsystem.CPU_LIMIT -> when (mode) {
                PolicyMode.FOLLOW_SYSTEM -> "Follow System"
                PolicyMode.FORCE_ALLOW -> "Force Allow"
                PolicyMode.FORCE_RESTRICT_HBG -> "Limit (Hbg)"
                PolicyMode.FORCE_RESTRICT -> "Limit (Lbg)"
            }
            else -> when (mode) {
                PolicyMode.FOLLOW_SYSTEM -> "Follow System"
                PolicyMode.FORCE_ALLOW -> "Force Allow"
                PolicyMode.FORCE_RESTRICT,
                PolicyMode.FORCE_RESTRICT_HBG -> "Force Restrict"
            }
        }
    }

    fun overrideShortTag(mode: PolicyMode, subsystem: BgSubsystem): String = when (mode) {
        PolicyMode.FORCE_ALLOW -> allowShort
        PolicyMode.FORCE_RESTRICT_HBG -> "Hbg"
        PolicyMode.FORCE_RESTRICT -> if (subsystem == BgSubsystem.CPU_LIMIT) "Lbg" else restrictShort
        PolicyMode.FOLLOW_SYSTEM -> ""
    }

    fun effectiveOverrideText(mode: PolicyMode, subsystem: BgSubsystem): String = when (mode) {
        PolicyMode.FORCE_ALLOW -> currentOverrideAllow
        PolicyMode.FORCE_RESTRICT_HBG -> currentOverrideHbg
        PolicyMode.FORCE_RESTRICT -> if (subsystem == BgSubsystem.CPU_LIMIT) currentOverrideLbg else currentOverrideRestrict
        PolicyMode.FOLLOW_SYSTEM -> currentNeutral
    }

    fun nativeSourceLabel(source: NativeSource): String = when (language) {
        AppLanguage.ZH -> source.label
        AppLanguage.EN -> when (source) {
            NativeSource.SYSTEM_USER -> "Settings"
            NativeSource.CLOUD_DEFAULT -> "Default"
        }
    }

    fun nativeSourceShort(source: NativeSource): String = when (language) {
        AppLanguage.ZH -> source.shortLabel
        AppLanguage.EN -> when (source) {
            NativeSource.SYSTEM_USER -> "User"
            NativeSource.CLOUD_DEFAULT -> "Default"
        }
    }

    fun nativeStateLabel(state: NativeState): String = when (language) {
        AppLanguage.ZH -> state.label
        AppLanguage.EN -> when (state) {
            NativeState.WHITELISTED -> "Allow"
            NativeState.BLACKLISTED -> "Restrict"
            NativeState.NEUTRAL -> "Default"
        }
    }

    fun formatNativeTag(res: NativeResolution): String {
        val prefix = res.source?.let { nativeSourceShort(it) } ?: if (language == AppLanguage.ZH) "系统" else "Sys"
        return when (language) {
            AppLanguage.ZH -> when (res.cpuTier) {
                NativeCpuTier.HBG -> "${prefix} Hbg"
                NativeCpuTier.LBG -> "${prefix} Lbg"
                null -> when (res.state) {
                    NativeState.WHITELISTED -> "${prefix}放行"
                    NativeState.BLACKLISTED -> "${prefix}限制"
                    NativeState.NEUTRAL -> "常规"
                }
            }
            AppLanguage.EN -> when (res.cpuTier) {
                NativeCpuTier.HBG -> "$prefix Hbg"
                NativeCpuTier.LBG -> "$prefix Lbg"
                null -> when (res.state) {
                    NativeState.WHITELISTED -> "$prefix Allow"
                    NativeState.BLACKLISTED -> "$prefix Restrict"
                    NativeState.NEUTRAL -> "Default"
                }
            }
        }
    }

    fun appCategoryLabel(cat: AppCategory): String = when (language) {
        AppLanguage.ZH -> cat.label
        AppLanguage.EN -> when (cat) {
            AppCategory.USER -> "User"
            AppCategory.SYSTEM -> "System"
            AppCategory.ALL -> "All"
        }
    }

    fun policyScopeLabel(scope: PolicyScopeFilter): String = when (language) {
        AppLanguage.ZH -> scope.label
        AppLanguage.EN -> when (scope) {
            PolicyScopeFilter.ALL -> "All"
            PolicyScopeFilter.OVERRIDDEN -> "Overridden"
            PolicyScopeFilter.NATIVE_HIT -> "System Lists"
            PolicyScopeFilter.MANAGED -> "Managed"
        }
    }

    fun processCategoryLabel(cat: ProcessCategoryFilter): String = when (language) {
        AppLanguage.ZH -> when (cat) {
            ProcessCategoryFilter.ALL -> "全部"
            ProcessCategoryFilter.USER -> "用户"
            ProcessCategoryFilter.SYSTEM -> "系统"
            ProcessCategoryFilter.ROOT -> "Root"
        }
        AppLanguage.EN -> when (cat) {
            ProcessCategoryFilter.ALL -> "All"
            ProcessCategoryFilter.USER -> "User"
            ProcessCategoryFilter.SYSTEM -> "System"
            ProcessCategoryFilter.ROOT -> "Root"
        }
    }

    fun userFilterChipLabel(user: DeviceUserInfo): String = when (language) {
        AppLanguage.ZH -> when {
            user.userId == 0 -> "机主 (u0)"
            user.isClone -> "分身 (u${user.userId})"
            else -> "${user.name} (u${user.userId})"
        }
        AppLanguage.EN -> when {
            user.userId == 0 -> "Owner (u0)"
            user.isClone -> "Clone (u${user.userId})"
            else -> "${user.name} (u${user.userId})"
        }
    }

    fun formatRuntimeAction(action: String): String = when (language) {
        AppLanguage.ZH -> when (action) {
            "skip_nirvana" -> "拦截 Nirvana 清理"
            "skip_okill" -> "拦截系统杀进程"
            "skip_athena_clean" -> "拦截 Athena 清理"
            "skip_athena_forcestop" -> "拦截 Athena 强停"
            "skip_remove_task" -> "保留划卡后台"
            "remove_task_forcestop" -> "划卡强停应用"
            "prevent_start_proc" -> "拦截后台拉起"
            "block_service_restart" -> "阻断服务自重启"
            "prevent_service" -> "拦截启动服务"
            "prevent_bind" -> "拦截绑定服务"
            "prevent_receiver" -> "拦截广播拉起"
            "prevent_provider" -> "拦截 Provider 拉起"
            "skip_oguard" -> "拦截 OGuard 省电杀"
            "sync_doze_add" -> "同步加入 Doze 白名单"
            "sync_doze_remove" -> "同步移出 Doze 白名单"
            "auto_suspend" -> "无后台自动冻结"
            "manual_suspend" -> "手动冻结 (pm suspend)"
            "manual_unsuspend" -> "手动解冻"
            "unsuspend_launch" -> "解冻并直启"
            else -> action
        }
        AppLanguage.EN -> when (action) {
            "skip_nirvana" -> "Skipped Nirvana kill"
            "skip_okill" -> "Skipped system kill"
            "skip_athena_clean" -> "Skipped Athena clean"
            "skip_athena_forcestop" -> "Skipped Athena force-stop"
            "skip_remove_task" -> "Kept on swipe"
            "remove_task_forcestop" -> "Force-stopped on swipe"
            "prevent_start_proc" -> "Blocked BG process start"
            "block_service_restart" -> "Blocked service restart"
            "prevent_service" -> "Blocked startService"
            "prevent_bind" -> "Blocked bindService"
            "prevent_receiver" -> "Blocked receiver"
            "prevent_provider" -> "Blocked provider"
            "skip_oguard" -> "Skipped OGuard kill"
            "sync_doze_add" -> "Added to Doze whitelist"
            "sync_doze_remove" -> "Removed from Doze whitelist"
            "auto_suspend" -> "Auto-suspended (no BG)"
            "manual_suspend" -> "Suspended (pm suspend)"
            "manual_unsuspend" -> "Unsuspended"
            "unsuspend_launch" -> "Unsuspended & launched"
            else -> action
        }
    }

    fun formatStandbyBucket(bucket: Int): String = when (bucket) {
        5 -> if (language == AppLanguage.ZH) "5 (豁免)" else "5 (Exempted)"
        10 -> if (language == AppLanguage.ZH) "10 (活跃)" else "10 (Active)"
        20 -> if (language == AppLanguage.ZH) "20 (工作集)" else "20 (Working)"
        30 -> if (language == AppLanguage.ZH) "30 (常用)" else "30 (Frequent)"
        40 -> if (language == AppLanguage.ZH) "40 (极少)" else "40 (Rare)"
        45 -> if (language == AppLanguage.ZH) "45 (受限)" else "45 (Restricted)"
        else -> if (bucket < 0) "-" else bucket.toString()
    }
}

val ZhAppStrings = AppStrings(
    language = AppLanguage.ZH,
    appTitle = "ColorOS 后台管理",
    lposedDisconnected = "LSPosed 未连接",
    inactiveRebootHint = "未激活，请重启系统",
    activatedSummary = { count -> "已激活 · $count 个应用已调整" },
    langSwitchLabel = "中/EN",
    refreshCd = "刷新",
    tabOverview = "总览",
    tabApps = "应用",
    tabProcesses = "进程",
    statusActive = "已激活",
    statusInactive = "未激活",
    enableInLsposedHint = "请在 LSPosed 启用并勾选「系统框架 (android)」",
    systemNotLoadedHint = "系统进程未加载，请重启一次",
    waitingXposedServiceHint = "系统 Hook 已挂载，等待 Xposed 服务连接",
    mountedSubsystems = { active, total -> "已挂载 $active/$total 个子系统" },
    recheck = "重检",
    nativeListsTitle = "系统名单",
    nativeListsDesc = { kinds, entries -> "查看 ColorOS 原厂 $kinds 份名单（共 $entries 项）" },
    nativeListsInactive = "激活后可查看原厂名单",
    sectionSubsystems = "后台子系统",
    hookOk = "正常",
    hookMissing = "未挂载",
    systemCounts = { white, black -> "系统 白 $white / 黑 $black" },
    moduleCounts = { allow, restrict -> "· 模块 放行 $allow / 管控 $restrict" },
    unmodified = "· 未调整",
    sectionGlobals = "全局设置",
    globalGuardElfTitle = "禁用 GuardElf 异常耗电 CPU 查杀",
    globalGuardElfSub = "关闭系统后台 CPU 超标自动强杀",
    globalBroadcastTitle = "关闭后台广播限制",
    globalBroadcastSub = "全局放行系统后台广播拦截与限流",
    globalStrictTitle = "关闭 HANS 严格省电限制",
    globalStrictSub = "解除严格模式下对后台的额外拦截",
    globalVerboseTitle = "调试日志",
    globalVerboseSub = "输出详细规则日志到 LSPosed",
    searchAppsPlaceholder = "搜索应用或包名",
    allDimensions = "全部维度",
    autoFreezeFilterTag = { count -> if (count > 0) "自动冻结 ($count)" else "自动冻结" },
    sectionOverridden = "置顶 · 模块调整",
    sectionNativeLists = "系统名单",
    sectionOtherApps = "其他应用",
    emptyFilterResult = "当前筛选下没有匹配的应用",
    collapse = "收起",
    expand = "展开",
    systemTag = "系统",
    defaultRulesText = "均按常规规则",
    allowShort = "放行",
    restrictShort = "管控",
    backCd = "返回",
    allowPreset = "预设放行",
    restrictPreset = "预设管控",
    restoreDefault = "恢复默认",
    resetAllAppsCd = "全部恢复为跟随系统",
    resetSingleAppCd = "恢复此应用为跟随系统",
    resetAllDialogTitle = "还原全部应用策略",
    resetAllDialogMessage = { count -> "将把已调整的 $count 个应用恢复为全跟随系统（自动冻结设置保持不变）。" },
    confirmReset = "确认还原",
    cancel = "取消",
    toastResetAllOk = "已将全部应用恢复为跟随系统（冻结设置不变）",
    toastResetAppOk = "已恢复为全跟随系统（冻结设置不变）",
    presetConfigAllowTitle = "自定义「预设放行」包含项",
    presetConfigRestrictTitle = "自定义「预设管控」包含项",
    presetConfigHint = "点击按钮主区仅更新以下勾选项，未勾选项保留当前状态",
    presetRestoreRecommended = "还原推荐",
    presetSaveOnly = "仅保存",
    presetSaveAndApply = "保存并应用",
    presetEmptyWarning = "请至少勾选一项子系统",
    presetMoreCd = "配置预设包含项",
    notInNativeList = "未命中名单",
    currentOverrideAllow = "当前：强制放行（模块）",
    currentOverrideRestrict = "当前：强制管控（模块）",
    currentOverrideHbg = "当前：限核 Hbg（较宽后台核）",
    currentOverrideLbg = "当前：限核 Lbg（严格小核）",
    currentNeutral = "当前：按常规规则",
    currentPrefix = "当前：",
    cloudDefaultListsHeader = "系统默认名单（出厂与云控）",
    systemUserListsHeader = "系统设置名单（本地开关记录）",
    installedSummary = { installed, total -> "已安装 $installed 项 · 总计 $total 项" },
    nativeDetailSubtitle = { source, total -> "只读 · $source · 已安装置顶（共 $total 项）" },
    cloudInactivePill = "未生效",
    cloudSwitchOffSuffix = " · 云控开关已关闭（不生效）",
    filterPkgPlaceholder = "过滤包名或应用名称",
    installed = "已安装",
    notInstalled = "未安装",
    toastRefreshed = { active, total, entries -> "已刷新：Hook 挂载 $active/$total，原厂名单共 $entries 条" },
    toastNotInjected = "system_server 尚未注入，请先在 LSPosed 启用并重启",
    toastNeedLposedRule = "未连接到 LSPosed 服务，无法保存规则",
    toastNeedLposedSetting = "未连接到 LSPosed 服务，无法保存设置",
    sectionRecentEvents = "最近调度动态",
    noRecentEvents = "暂无拦截或调度事件",
    sectionRuntimeStatus = "实时应用状态",
    runtimeDozeWl = "Doze 白名单",
    runtimeStandbyBucket = "待机分组",
    runtimeRunningProcs = "存活进程",
    runtimeWhitelisted = "已加入",
    runtimeNotWhitelisted = "未加入",
    runtimeNotRunning = "未运行",
    sectionAppEvents = "应用调度日志",
    searchRootPlaceholder = "搜索命令、进程或 PID",
    showKernelThreads = "显示内核线程",
    allUsersChip = "全部用户",
    userBadge = { uid -> "u$uid" },
    rootUnavailableHint = { err -> if (err.isNullOrBlank()) "需要 Root (su) 权限读取原生/特权进程" else "无法读取 Root 进程：$err（若用 KernelSU 请先授予本应用 Root 权限）" },
    retryRootButton = "请求 Root 授权读取",
    processGroupRunning = "运行中",
    processGroupCached = "已缓存",
    processGroupStopped = "未运行",
    processGroupRoot = "原生与 Root 二进制",
    procMemLabel = "内存",
    procCpuLabel = "CPU",
    procUptimeLabel = "运行",
    procStateLabel = "状态",
    procAndSvcCount = { p, s -> "$p 进程 · $s 服务" },
    hostedServicesCount = { count -> "托管服务 ($count)" },
    noActiveServices = "该进程暂无运行中服务",
    extraServicesHeader = "未关联具体 PID 的服务",
    closeDialog = "关闭",
    openAppSettingsCd = "策略设置",
    sectionFreezeControl = "应用冻结与启动 (pm suspend)",
    autoFreezeSwitchTitle = "无后台时自动冻结",
    autoFreezeSwitchSub = "进程退出、划掉任务或息屏并确认无活跃后台服务后自动 suspend",
    autoUnfreezeSwitchTitle = "智能解冻",
    autoUnfreezeSwitchSub = "点击桌面图标或被其他应用唤起时自动解除 suspend",
    freezeStatusSuspended = "已冻结",
    freezeStatusActive = "正常",
    actionFreezeNow = "立即冻结",
    actionUnfreezeNow = "立即解冻",
    actionLaunchInApp = "启动",
    actionPinShortcut = "添加桌面快捷方式",
    autoFreezeDelayTitle = "无后台自动冻结等待时间",
    autoFreezeDelaySub = { sec ->
        when {
            sec <= 0 -> "确认无活跃前台/后台服务后立刻执行 suspend"
            sec % 60 == 0 -> "确认无活跃前台/后台服务后延迟 ${sec / 60} 分钟执行 suspend"
            else -> "确认无活跃前台/后台服务后延迟 ${sec} 秒执行 suspend"
        }
    },
    autoFreezeDelayOptionLabel = { sec ->
        when (sec) {
            0 -> "立刻"
            60 -> "1min"
            300 -> "5min"
            else -> "${sec}s"
        }
    },
    toastSuspendOk = "已执行 pm suspend 冻结",
    toastUnsuspendOk = "已解冻应用",
    toastActionFailed = "操作失败，请检查 system_server 注入或受保护限制",
    toastLaunchFailed = "启动失败，未找到该用户的启动入口",
    toastShortcutRequested = "已请求桌面添加直启快捷方式",
    toastShortcutUnsupported = "当前桌面不支持创建固定快捷方式",
    aboutTitle = "关于",
    aboutRepoTitle = "源代码仓库",
    aboutRepoUrl = "https://github.com/yukino1111/ColorOSBgManager",
    aboutLinkCopied = "仓库链接已复制到剪贴板",
    aboutOpenFailed = "未找到可用浏览器，链接已复制到剪贴板",
)

val EnAppStrings = AppStrings(
    language = AppLanguage.EN,
    appTitle = "ColorOS BgManager",
    lposedDisconnected = "LSPosed disconnected",
    inactiveRebootHint = "Inactive, please reboot",
    activatedSummary = { count -> "Active · $count apps overridden" },
    langSwitchLabel = "中/EN",
    refreshCd = "Refresh",
    tabOverview = "Overview",
    tabApps = "Apps",
    tabProcesses = "Processes",
    statusActive = "Active",
    statusInactive = "Inactive",
    enableInLsposedHint = "Enable in LSPosed and select System Framework (android)",
    systemNotLoadedHint = "system_server not injected, please reboot",
    waitingXposedServiceHint = "System hooks active; waiting for Xposed service bind",
    mountedSubsystems = { active, total -> "$active/$total subsystems hooked" },
    recheck = "Retry",
    nativeListsTitle = "System Lists",
    nativeListsDesc = { kinds, entries -> "Inspect $kinds ColorOS native lists ($entries entries)" },
    nativeListsInactive = "Available after activation",
    sectionSubsystems = "Subsystems",
    hookOk = "Active",
    hookMissing = "Unhooked",
    systemCounts = { white, black -> "Sys W $white / B $black" },
    moduleCounts = { allow, restrict -> "· Override Allow $allow / Restrict $restrict" },
    unmodified = "· Default",
    sectionGlobals = "Global Settings",
    globalGuardElfTitle = "Disable GuardElf High-CPU Kill",
    globalGuardElfSub = "Stop automatic killing on background CPU threshold",
    globalBroadcastTitle = "Disable Background Broadcast Limit",
    globalBroadcastSub = "Globally bypass background broadcast throttling",
    globalStrictTitle = "Disable HANS Strict Power Restriction",
    globalStrictSub = "Bypass extra restrictions under strict power mode",
    globalVerboseTitle = "Verbose Logging",
    globalVerboseSub = "Print detailed rule logs to LSPosed",
    searchAppsPlaceholder = "Search app or package",
    allDimensions = "All Subsystems",
    autoFreezeFilterTag = { count -> if (count > 0) "Auto-suspend ($count)" else "Auto-suspend" },
    sectionOverridden = "Pinned · Overridden",
    sectionNativeLists = "System Lists",
    sectionOtherApps = "Other Apps",
    emptyFilterResult = "No matching apps",
    collapse = "Collapse",
    expand = "Expand",
    systemTag = "System",
    defaultRulesText = "Following default rules",
    allowShort = "Allow",
    restrictShort = "Restrict",
    backCd = "Back",
    allowPreset = "Allow Preset",
    restrictPreset = "Restrict Preset",
    restoreDefault = "Reset Default",
    resetAllAppsCd = "Reset all apps to Follow System",
    resetSingleAppCd = "Reset app to Follow System",
    resetAllDialogTitle = "Reset All App Policies",
    resetAllDialogMessage = { count -> "Reset all $count customized apps to Follow System? (Auto-suspend settings stay unchanged.)" },
    confirmReset = "Reset",
    cancel = "Cancel",
    toastResetAllOk = "All apps reset to Follow System (suspend unchanged)",
    toastResetAppOk = "App reset to Follow System (suspend unchanged)",
    presetConfigAllowTitle = "Customize Allow Preset",
    presetConfigRestrictTitle = "Customize Restrict Preset",
    presetConfigHint = "Tapping the button updates only checked items; unchecked subsystems keep their state",
    presetRestoreRecommended = "Recommended",
    presetSaveOnly = "Save",
    presetSaveAndApply = "Save & Apply",
    presetEmptyWarning = "Select at least one subsystem",
    presetMoreCd = "Configure preset items",
    notInNativeList = "Unlisted",
    currentOverrideAllow = "Effective: Force Allow (Module)",
    currentOverrideRestrict = "Effective: Force Restrict (Module)",
    currentOverrideHbg = "Effective: Limit Hbg (Wider BG cores)",
    currentOverrideLbg = "Effective: Limit Lbg (Tight BG cores)",
    currentNeutral = "Effective: Default Rules",
    currentPrefix = "Effective: ",
    cloudDefaultListsHeader = "System Default Lists (ROM & Cloud)",
    systemUserListsHeader = "System Settings Lists (Local Records)",
    installedSummary = { installed, total -> "$installed installed · $total total" },
    nativeDetailSubtitle = { source, total -> "Read-only · $source · Installed first ($total total)" },
    cloudInactivePill = "Inactive",
    cloudSwitchOffSuffix = " · Cloud switch off (inactive)",
    filterPkgPlaceholder = "Filter package or app name",
    installed = "Installed",
    notInstalled = "Not installed",
    toastRefreshed = { active, total, entries -> "Refreshed: Hooks $active/$total, $entries native entries" },
    toastNotInjected = "system_server not injected; enable in LSPosed and reboot",
    toastNeedLposedRule = "LSPosed service not connected; cannot save rule",
    toastNeedLposedSetting = "LSPosed service not connected; cannot save setting",
    sectionRecentEvents = "Recent Activity",
    noRecentEvents = "No recent interventions yet",
    sectionRuntimeStatus = "App Runtime Status",
    runtimeDozeWl = "Doze Whitelist",
    runtimeStandbyBucket = "Standby Bucket",
    runtimeRunningProcs = "Running Processes",
    runtimeWhitelisted = "Whitelisted",
    runtimeNotWhitelisted = "Not Listed",
    runtimeNotRunning = "Not running",
    sectionAppEvents = "App Dispatch Log",
    searchRootPlaceholder = "Search cmdline, user, or PID",
    showKernelThreads = "Show kernel threads",
    allUsersChip = "All Users",
    userBadge = { uid -> "u$uid" },
    rootUnavailableHint = { err -> if (err.isNullOrBlank()) "Root (su) permission needed to inspect native binaries" else "Root query unavailable: $err (grant root in KernelSU/Magisk)" },
    retryRootButton = "Request Root (su)",
    processGroupRunning = "Running",
    processGroupCached = "Cached",
    processGroupStopped = "Not Running",
    processGroupRoot = "Native & Root Binaries",
    procMemLabel = "Mem",
    procCpuLabel = "CPU",
    procUptimeLabel = "Up",
    procStateLabel = "State",
    procAndSvcCount = { p, s -> "$p proc · $s svc" },
    hostedServicesCount = { count -> "Hosted Services ($count)" },
    noActiveServices = "No running services in this process",
    extraServicesHeader = "Unmapped App Services",
    closeDialog = "Close",
    openAppSettingsCd = "App Settings",
    sectionFreezeControl = "Freeze & Launch (pm suspend)",
    autoFreezeSwitchTitle = "Auto-suspend when idle",
    autoFreezeSwitchSub = "Suspend package after exit, swipe-kill, or screen off when no active services remain",
    autoUnfreezeSwitchTitle = "Smart unsuspend",
    autoUnfreezeSwitchSub = "Automatically unsuspend when opened from launcher or another app",
    freezeStatusSuspended = "Suspended",
    freezeStatusActive = "Active",
    actionFreezeNow = "Suspend",
    actionUnfreezeNow = "Unsuspend",
    actionLaunchInApp = "Launch",
    actionPinShortcut = "Add to Home",
    autoFreezeDelayTitle = "Auto-suspend Delay",
    autoFreezeDelaySub = { sec ->
        when {
            sec <= 0 -> "Suspend immediately once no active foreground/service remains"
            sec % 60 == 0 -> "Wait ${sec / 60} min after no active foreground/service before suspending"
            else -> "Wait ${sec}s after no active foreground/service before suspending"
        }
    },
    autoFreezeDelayOptionLabel = { sec ->
        when (sec) {
            0 -> "Instant"
            60 -> "1min"
            300 -> "5min"
            else -> "${sec}s"
        }
    },
    toastSuspendOk = "Package suspended",
    toastUnsuspendOk = "Package unsuspended",
    toastActionFailed = "Action failed; check system_server injection",
    toastLaunchFailed = "Launch failed for this user",
    toastShortcutRequested = "Shortcut pin requested",
    toastShortcutUnsupported = "Pinned shortcuts not supported by launcher",
    aboutTitle = "About",
    aboutRepoTitle = "Source Repository",
    aboutRepoUrl = "https://github.com/yukino1111/ColorOSBgManager",
    aboutLinkCopied = "Repository URL copied to clipboard",
    aboutOpenFailed = "No browser found, URL copied to clipboard",
)

fun stringsFor(lang: AppLanguage): AppStrings = when (lang) {
    AppLanguage.ZH -> ZhAppStrings
    AppLanguage.EN -> EnAppStrings
}

val LocalAppStrings = staticCompositionLocalOf { ZhAppStrings }
