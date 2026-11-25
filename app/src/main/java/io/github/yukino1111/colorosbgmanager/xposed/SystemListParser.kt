package io.github.yukino1111.colorosbgmanager.xposed

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Xml
import io.github.yukino1111.colorosbgmanager.model.NativeListKind
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.StringReader

internal object SystemListParser {

    private val ROMUPDATE_URI: Uri = Uri.parse("content://com.oplus.romupdate.provider.db/update_list")

    fun buildSnapshotBundle(
        hookStatus: Map<String, Boolean>,
        systemClassLoader: ClassLoader? = null,
        systemContext: Context? = null,
    ): Bundle {
        val romUpdateFilters = queryRomUpdateFilters(
            context = systemContext,
            filterNames = setOf(
                "sys_guardelf_limitbkg_blacklist",
                "sys_guardelf_config_list",
                "sys_elsa_config_list",
                "sys_appfrozen_config_list",
                "sys_startup_v3_config_list",
                "sys_res_control_config",
                "sys_deviceidle_whitelist",
                "sys_clear_appfilter_list",
                "sys_custom_clear_config",
                "sys_athena_config_list",
                "sys_ams_skipbroadcast",
                "sys_cpulimit_config",
            ),
        )

        val localElsa = parseElsaConfig("/data/oplus/os/bpm/sys_elsa_config_list.xml")
        val cloudElsa = romUpdateFilters["sys_elsa_config_list"]?.let(::parseElsaConfigString)
        val elsaLists = mergeElsaResults(localElsa, cloudElsa)
        val appFrozenNoFreeze = romUpdateFilters["sys_appfrozen_config_list"]
            ?.let { parseTextTagXmlString(it, setOf("no_frozen")) }
            .orEmpty()
        val bpmAll = parseAttTagXml("/data/oplus/os/bpm/bpm.xml", setOf("p"), "att")
        val fixedRecordUser = parseFixedRecord("/data/oplus/os/bpm/fixedRecord.xml")
        val hansUserWhite = fixedRecordUser + (bpmAll - elsaLists.white)
        val hansSystemWhite = elsaLists.white + appFrozenNoFreeze + (bpmAll - hansUserWhite)

        val notRestrictAll = parseAttTagXml("/data/oplus/os/battery/not_restrict.xml", setOf("p"), "att")
        val notifyPresetTemplate = parseAttTagXml("/data/oplus/os/battery/notify_whitelist.xml", setOf("p"), "att")
        val allowProhibitUser = parseTextTagXml("/data/oplus/os/battery/allow_prohibit_app.xml", setOf("allow"))
        val batteryUserWhite = allowProhibitUser + (notRestrictAll - notifyPresetTemplate)

        val dozeWhite = parseTextTagXml("/data/oplus/os/battery/doze_wl_local.xml", setOf("wl")) +
            romUpdateFilters["sys_deviceidle_whitelist"]?.let { parseTextTagXmlString(it, setOf("wl")) }.orEmpty() +
            parseTextTagXml("/data/system/deviceidle.xml", setOf("wl")) +
            (notRestrictAll - batteryUserWhite)

        val startupV3 = mergeStartupV3Results(
            parseStartupV3("/data/oplus/os/startup/sys_startup_v3_config_list.xml"),
            romUpdateFilters["sys_startup_v3_config_list"]?.let(::parseStartupV3String),
        )
        val customStartupV3 = parseStartupV3("/data/oplus/os/startup/sys_custom_startup_v3_config_list.xml")
        val startupDynamic = parseStartupDynamic("/data/oplus/os/startup/startup_dynamic_list.xml")
        val appRevivalPkgs = parseAppRevival("/data/oplus/os/startup/sys_res_control_config.xml") +
            romUpdateFilters["sys_res_control_config"]?.let(::parseAppRevivalString).orEmpty()

        val autostartUserWhite = startupDynamic.autostartUserAllow
        val autostartSystemWhite = (
            startupV3.autostart +
                customStartupV3.autostart +
                parseLineList("/data/oplus/os/startup/autostart_white_list.txt") +
                startupDynamic.autostartSystemAllow
            ) - startupDynamic.autostartDeny - autostartUserWhite

        val associateUserWhite = startupDynamic.associateUserAllow
        val associateSystemWhite = (
            startupV3.associate +
                customStartupV3.associate +
                parseLineList("/data/oplus/os/startup/associate_white_list.txt") +
                startupDynamic.associateSystemAllow
            ) - startupDynamic.associateDeny - associateUserWhite + appRevivalPkgs

        val guardElfLimitBlack = romUpdateFilters["sys_guardelf_limitbkg_blacklist"]
            ?.let { parseGuardElfLimitBlack(it) }
            ?: GuardElfBlackResult(emptySet(), true)
        val guardElfCloud = romUpdateFilters["sys_guardelf_config_list"]
            ?.let { parseGuardElfCloudConfig(it) }
            ?: GuardElfCloudResult(emptySet(), emptySet(), false)

        val recentLocked = parseFlexiblePkgXml("/data/oplus/os/recenttask/locked_apps.xml") +
            parseFlexiblePkgXml("/data/oplus/os/recenttask/default_locked_apps.xml") +
            parseFlexiblePkgXml("/data/oplus/os/recenttask/fixed_lock_apps.xml") +
            parseFlexiblePkgXml("/data/oplus/os/battery/recent_lock.xml") +
            readRuntimeRecentLockList(systemClassLoader)

        val recentForbid = parseFlexiblePkgXml("/data/oplus/os/recenttask/forbid_lock_apps.xml") +
            readLauncherForbidLockList(systemContext)

        val clearFilterWhite = romUpdateFilters["sys_clear_appfilter_list"]
            ?.let(::parseClearAppFilterWhite)
            .orEmpty()
        val customClearExWhite = romUpdateFilters["sys_custom_clear_config"]
            ?.let(::parseCustomClearItgsaWhite)
            .orEmpty()
        val athenaSceneWhite = romUpdateFilters["sys_athena_config_list"]
            ?.let(::parseAthenaSceneWhite)
            .orEmpty()
        val athenaTrimWhite = elsaLists.trimMemWhite + clearFilterWhite + customClearExWhite + athenaSceneWhite

        val cpuLimitConfig = mergeCpuLimitResults(
            parseCpuLimitConfig("/data/oplus/os/bpm/sys_cpulimit_config.xml"),
            romUpdateFilters["sys_cpulimit_config"]?.let(::parseCpuLimitConfigString),
        )
        val skipBroadcastWhite = parseTextTagXml("/data/oplus/os/config/sys_ams_skipbroadcast.xml", setOf("wl")) +
            parseTextTagXml("/data/oplus/os/sys_ams_skipbroadcast.xml", setOf("wl")) +
            romUpdateFilters["sys_ams_skipbroadcast"]?.let { parseTextTagXmlString(it, setOf("wl")) }.orEmpty() +
            parseTextTagXml("/system_ext/oplus/startup_config.xml", setOf("skipBroadcastWhite"))

        val effectiveCpuWhite = if (cpuLimitConfig.particularEnable) cpuLimitConfig.particular else emptySet()
        val cpuLimitWhite = cpuLimitConfig.particular + elsaLists.cpuCtlWhite
        val cpuLimitVipHbg = cpuLimitConfig.vipHbg - effectiveCpuWhite
        val effectiveCpuVipHbg = if (cpuLimitConfig.vipHbgEnable) cpuLimitVipHbg else emptySet()
        val cpuLimitBlack = cpuLimitConfig.black - effectiveCpuWhite - effectiveCpuVipHbg - elsaLists.cpuCtlWhite
        val dozeNetWhite = guardElfCloud.netWhite + elsaLists.trafficWhite

        val netDeepSleepLocal = parseFlexiblePkgXml("/data/oplus/os/battery/app_traffic_deepsleep.xml") +
            parseFlexiblePkgXml("/data/oplus/os/battery/screenoff_restrict.xml") +
            parseFlexiblePkgXml("/data/oplus/os/battery/persist_restrict.xml")

        val dataMap = mapOf(
            NativeListKind.HANS_SYSTEM_WHITE to hansSystemWhite,
            NativeListKind.HANS_BPM_USER to hansUserWhite,
            NativeListKind.HANS_SYS_BLACK to elsaLists.black,
            NativeListKind.AUTOSTART_SYSTEM_WHITE to autostartSystemWhite,
            NativeListKind.AUTOSTART_USER_WHITE to autostartUserWhite,
            NativeListKind.AUTOSTART_USER_DENY to startupDynamic.autostartDeny,
            NativeListKind.ASSOCIATE_SYSTEM_WHITE to associateSystemWhite,
            NativeListKind.ASSOCIATE_USER_WHITE to associateUserWhite,
            NativeListKind.ASSOCIATE_USER_DENY to startupDynamic.associateDeny,
            NativeListKind.BATTERY_NOT_RESTRICT to batteryUserWhite,
            NativeListKind.DOZE_WHITE to dozeWhite,
            NativeListKind.GUARDELF_LIMIT_BLACK to guardElfLimitBlack.pkgs,
            NativeListKind.RECENT_LOCKED to recentLocked,
            NativeListKind.ATHENA_TRIM_WHITE to athenaTrimWhite,
            NativeListKind.RECENT_FORBID to recentForbid,
            NativeListKind.CPULIMIT_WHITE to cpuLimitWhite,
            NativeListKind.CPULIMIT_VIP_HBG to cpuLimitVipHbg,
            NativeListKind.CPULIMIT_BLACK to cpuLimitBlack,
            NativeListKind.DOZE_NET_WHITE to dozeNetWhite,
            NativeListKind.DOZE_NET_CLOUD_RESTRICT to guardElfCloud.netRestrict,
            NativeListKind.NET_DEEPSLEEP_RESTRICT to netDeepSleepLocal,
            NativeListKind.BROADCAST_WHITE to skipBroadcastWhite,
        )

        val inactiveKeys = buildList {
            if (!elsaLists.hansEnable) {
                add(NativeListKind.HANS_SYSTEM_WHITE.bundleKey)
                add(NativeListKind.HANS_SYS_BLACK.bundleKey)
            }
            if (!elsaLists.trimMemEnable) add(NativeListKind.ATHENA_TRIM_WHITE.bundleKey)
            if (!guardElfLimitBlack.isOpen) add(NativeListKind.GUARDELF_LIMIT_BLACK.bundleKey)
            if (!guardElfCloud.limitNetEnable) add(NativeListKind.DOZE_NET_CLOUD_RESTRICT.bundleKey)
            if (!cpuLimitConfig.particularEnable) add(NativeListKind.CPULIMIT_WHITE.bundleKey)
            if (!cpuLimitConfig.vipHbgEnable) add(NativeListKind.CPULIMIT_VIP_HBG.bundleKey)
        }

        return Bundle().apply {
            putBoolean("bgmaster_ok", true)
            putLong("timestamp", System.currentTimeMillis())
            dataMap.forEach { (kind, pkgs) ->
                putStringArrayList(kind.bundleKey, ArrayList(pkgs.filter { it.isNotBlank() }.sorted()))
            }
            putStringArrayList("inactive_lists", ArrayList(inactiveKeys))
            val hooks = Bundle()
            hookStatus.forEach { (k, v) -> hooks.putBoolean(k, v) }
            putBundle("hook_status", hooks)
        }
    }

    private data class ElsaResult(
        val white: Set<String>,
        val black: Set<String>,
        val cpuCtlWhite: Set<String>,
        val trafficWhite: Set<String>,
        val trimMemWhite: Set<String>,
        val hansEnable: Boolean = true,
        val trimMemEnable: Boolean = true,
    )

    private fun parseElsaConfig(path: String): ElsaResult {
        var result = ElsaResult(emptySet(), emptySet(), emptySet(), emptySet(), emptySet())
        withXmlParser(path) { parser ->
            result = parseElsaConfigFromParser(parser)
        }
        return result
    }

    private fun parseElsaConfigString(xmlContent: String): ElsaResult {
        var result = ElsaResult(emptySet(), emptySet(), emptySet(), emptySet(), emptySet())
        withXmlStringParser(xmlContent) { parser ->
            result = parseElsaConfigFromParser(parser)
        }
        return result
    }

    private fun mergeElsaResults(local: ElsaResult, cloud: ElsaResult?): ElsaResult {
        if (cloud == null) return local
        return ElsaResult(
            white = local.white + cloud.white,
            black = local.black + cloud.black,
            cpuCtlWhite = local.cpuCtlWhite + cloud.cpuCtlWhite,
            trafficWhite = local.trafficWhite + cloud.trafficWhite,
            trimMemWhite = local.trimMemWhite + cloud.trimMemWhite,
            hansEnable = cloud.hansEnable && local.hansEnable,
            trimMemEnable = cloud.trimMemEnable && local.trimMemEnable,
        )
    }

    private fun parseElsaConfigFromParser(parser: XmlPullParser): ElsaResult {
        val white = LinkedHashSet<String>()
        val black = LinkedHashSet<String>()
        val cpuCtlWhite = LinkedHashSet<String>()
        val trafficWhite = LinkedHashSet<String>()
        val trimMemWhite = LinkedHashSet<String>()
        var hansEnable = true
        var trimMemEnable = true
        var inAndroidFreeze = false
        var androidFreezeWhiteActive = false
        var inTrafficWhite = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "enableConfig" -> {
                        hansEnable = parser.getAttributeValue(null, "hansEnable")?.equals("false", true) != true
                        trimMemEnable = parser.getAttributeValue(null, "MStateTrimMemConfig")?.equals("false", true) != true
                    }
                    "whitePkg" -> {
                        val pkg = normalizePackageName(parser.getAttributeValue(null, "name"))
                        if (pkg != null) {
                            if (inTrafficWhite) trafficWhite.add(pkg) else white.add(pkg)
                        }
                    }
                    "imPkg" -> normalizePackageName(parser.getAttributeValue(null, "pkg"))?.let(white::add)
                    "gms", "default-input-pkg" -> normalizePackageName(parser.getAttributeValue(null, "name"))?.let(white::add)
                    "pkgConfig" -> {
                        val cat = parser.getAttributeValue(null, "category")?.trim()?.toIntOrNull(16) ?: 0
                        val names = parser.getAttributeValue(null, "name").orEmpty()
                        names.split('#', ',').forEach { token ->
                            val pkg = normalizePackageName(token) ?: return@forEach
                            if ((cat and 0x1) != 0) trafficWhite.add(pkg)
                            if ((cat and 0x2) != 0) {
                                white.add(pkg)
                                trimMemWhite.add(pkg)
                            }
                            if ((cat and 0x100) != 0) white.add(pkg)
                        }
                    }
                    "android-freeze" -> {
                        inAndroidFreeze = true
                        val freezeEnable = parser.getAttributeValue(null, "enable")?.equals("false", true) != true
                        val whiteEnable = parser.getAttributeValue(null, "whiteEnable")?.equals("false", true) != true
                        androidFreezeWhiteActive = freezeEnable && whiteEnable
                    }
                    "white" -> if (inAndroidFreeze && androidFreezeWhiteActive) {
                        normalizePackageName(parser.getAttributeValue(null, "pkg"))?.let(white::add)
                    }
                    "SysBlack", "SysBlackApp" -> normalizePackageName(parser.getAttributeValue(null, "name"))?.let(black::add)
                    "blackHansPkg" -> {
                        if (parser.getAttributeValue(null, "enable")?.equals("false", true) != true) {
                            parser.getAttributeValue(null, "pkg").orEmpty().split('#', ',').forEach { token ->
                                normalizePackageName(token)?.let(black::add)
                            }
                        }
                    }
                    "cpuCtlWhiteList" -> normalizePackageName(parser.getAttributeValue(null, "pkg"))?.let(cpuCtlWhite::add)
                    "trafficWhite" -> inTrafficWhite = true
                    "pkg", "item" -> if (inTrafficWhite) {
                        val attrPkg = parser.getAttributeValue(null, "pkg")
                            ?: parser.getAttributeValue(null, "name")
                        normalizePackageName(attrPkg)?.let(trafficWhite::add)
                    }
                    "trimMemWhiteConfig" -> {
                        val raw = parser.getAttributeValue(null, "whitePkg").orEmpty()
                        raw.split('#', ',').forEach { token ->
                            normalizePackageName(token)?.let(trimMemWhite::add)
                        }
                    }
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "android-freeze" -> {
                        inAndroidFreeze = false
                        androidFreezeWhiteActive = false
                    }
                    "trafficWhite" -> inTrafficWhite = false
                }
            }
            event = parser.next()
        }
        return ElsaResult(white, black, cpuCtlWhite, trafficWhite, trimMemWhite, hansEnable, trimMemEnable)
    }

    private fun parseFixedRecord(path: String): Set<String> {
        val out = LinkedHashSet<String>()
        withXmlParser(path) { parser ->
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "p") {
                    val isClick = parser.getAttributeValue(null, "isClick")
                    if (isClick == null || isClick.equals("true", ignoreCase = true)) {
                        normalizePackageName(parser.getAttributeValue(null, "pkg"))?.let(out::add)
                    }
                }
                event = parser.next()
            }
        }
        return out
    }

    private fun parseAttTagXml(path: String, tags: Set<String>, attr: String): Set<String> {
        val out = LinkedHashSet<String>()
        withXmlParser(path) { parser ->
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name in tags) {
                    normalizePackageName(parser.getAttributeValue(null, attr))?.let(out::add)
                }
                event = parser.next()
            }
        }
        return out
    }

    private fun parseTextTagXml(path: String, tags: Set<String>): Set<String> {
        val out = LinkedHashSet<String>()
        withXmlParser(path) { parser ->
            parseTextTagXmlFromParser(parser, tags, out)
        }
        return out
    }

    private fun parseTextTagXmlString(xmlContent: String, tags: Set<String>): Set<String> {
        val out = LinkedHashSet<String>()
        withXmlStringParser(xmlContent) { parser ->
            parseTextTagXmlFromParser(parser, tags, out)
        }
        return out
    }

    private fun parseTextTagXmlFromParser(
        parser: XmlPullParser,
        tags: Set<String>,
        out: MutableSet<String>,
    ) {
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name in tags) {
                val attrPkg = parser.getAttributeValue(null, "att")
                    ?: parser.getAttributeValue(null, "pkg")
                    ?: parser.getAttributeValue(null, "name")
                val normalizedAttr = normalizePackageName(attrPkg)
                if (normalizedAttr != null) {
                    out.add(normalizedAttr)
                } else {
                    val text = runCatching { parser.nextText() }.getOrNull()
                    normalizePackageName(text)?.let(out::add)
                }
            }
            event = parser.next()
        }
    }

    private data class GuardElfBlackResult(val pkgs: Set<String>, val isOpen: Boolean)

    private fun parseGuardElfLimitBlack(xmlContent: String): GuardElfBlackResult {
        val pkgs = LinkedHashSet<String>()
        var isOpen = true
        withXmlStringParser(xmlContent) { parser ->
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "isOpen" -> {
                            val v = runCatching { parser.nextText() }.getOrNull()?.trim()
                            isOpen = v != "0" && !v.equals("false", ignoreCase = true)
                        }
                        "p" -> normalizePackageName(parser.getAttributeValue(null, "att"))?.let(pkgs::add)
                    }
                }
                event = parser.next()
            }
        }
        return GuardElfBlackResult(pkgs, isOpen)
    }

    private data class GuardElfCloudResult(
        val netWhite: Set<String>,
        val netRestrict: Set<String>,
        val limitNetEnable: Boolean,
    )

    private fun parseGuardElfCloudConfig(xmlContent: String): GuardElfCloudResult {
        val netWhite = LinkedHashSet<String>()
        val netRestrictCandidate = LinkedHashSet<String>()
        var limitNetEnable = false
        withXmlStringParser(xmlContent) { parser ->
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "DeepSleepUseNetworkDisableWhiteList",
                        "NetworkDisableVowifiWhiteList",
                        "DeepSleepUseIMAppList" -> {
                            val text = runCatching { parser.nextText() }.getOrNull()
                            normalizePackageName(text)?.let(netWhite::add)
                        }
                        "DeepSleepLimitNetEnable" -> {
                            val text = runCatching { parser.nextText() }.getOrNull()?.trim()
                            limitNetEnable = text.equals("true", ignoreCase = true)
                        }
                        "DeepSleepUseLimitNetAppList" -> {
                            val text = runCatching { parser.nextText() }.getOrNull()
                            normalizePackageName(text)?.let(netRestrictCandidate::add)
                        }
                    }
                }
                event = parser.next()
            }
        }
        return GuardElfCloudResult(
            netWhite = netWhite,
            netRestrict = netRestrictCandidate,
            limitNetEnable = limitNetEnable,
        )
    }

    private data class StartupV3Result(val autostart: Set<String>, val associate: Set<String>)

    private fun parseStartupV3(path: String): StartupV3Result {
        var result = StartupV3Result(emptySet(), emptySet())
        withXmlParser(path) { parser ->
            result = parseStartupV3FromParser(parser)
        }
        return result
    }

    private fun parseStartupV3String(xmlContent: String): StartupV3Result {
        var result = StartupV3Result(emptySet(), emptySet())
        withXmlStringParser(xmlContent) { parser ->
            result = parseStartupV3FromParser(parser)
        }
        return result
    }

    private fun mergeStartupV3Results(local: StartupV3Result, cloud: StartupV3Result?): StartupV3Result {
        if (cloud == null) return local
        return StartupV3Result(
            autostart = local.autostart + cloud.autostart,
            associate = local.associate + cloud.associate,
        )
    }

    private fun parseStartupV3FromParser(parser: XmlPullParser): StartupV3Result {
        val auto = LinkedHashSet<String>()
        val assoc = LinkedHashSet<String>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "startup") {
                val pkg = normalizePackageName(parser.getAttributeValue(null, "pkgName"))
                val type = parser.getAttributeValue(null, "type")
                val all = parser.getAttributeValue(null, "all")
                if (pkg != null && (all == "1" || all == "11")) {
                    if (type == "1") auto.add(pkg)
                    if (type == "2") assoc.add(pkg)
                }
            }
            event = parser.next()
        }
        return StartupV3Result(auto, assoc)
    }

    private fun parseAppRevival(path: String): Set<String> {
        val out = LinkedHashSet<String>()
        withXmlParser(path) { parser ->
            parseAppRevivalFromParser(parser, out)
        }
        return out
    }

    private fun parseAppRevivalString(xmlContent: String): Set<String> {
        val out = LinkedHashSet<String>()
        withXmlStringParser(xmlContent) { parser ->
            parseAppRevivalFromParser(parser, out)
        }
        return out
    }

    private fun parseAppRevivalFromParser(parser: XmlPullParser, out: MutableSet<String>) {
        var inRevival = false
        var revivalEnable = true
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when {
                    parser.name == "appRevival" -> {
                        inRevival = true
                        revivalEnable = true
                    }
                    inRevival && parser.name == "policy" -> {
                        revivalEnable = parser.getAttributeValue(null, "enable")?.equals("false", true) != true
                    }
                    inRevival && revivalEnable && parser.name == "item" -> {
                        normalizePackageName(parser.getAttributeValue(null, "pkg"))?.let(out::add)
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name == "appRevival") {
                    inRevival = false
                }
            }
            event = parser.next()
        }
    }

    private fun parseClearAppFilterWhite(xmlContent: String): Set<String> {
        val out = LinkedHashSet<String>()
        withXmlStringParser(xmlContent) { parser ->
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG &&
                    (parser.name == "p_wl" || parser.name == "p_crm" || parser.name == "p_cond_wl")
                ) {
                    val hasKeepAttr = listOf(
                        "proc_keep",
                        "kill_restart_service",
                        "service_quick_restart",
                        "remove_task_filter",
                    ).any { !parser.getAttributeValue(null, it).isNullOrBlank() }
                    if (hasKeepAttr) {
                        normalizePackageName(parser.getAttributeValue(null, "pkg"))?.let(out::add)
                    }
                }
                event = parser.next()
            }
        }
        return out
    }

    private fun parseCustomClearItgsaWhite(xmlContent: String): Set<String> {
        val out = LinkedHashSet<String>()
        withXmlStringParser(xmlContent) { parser ->
            var inItgsaList = false
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when {
                        parser.name == "config_list" -> {
                            val key = parser.getAttributeValue(null, "key").orEmpty()
                            inItgsaList = key.contains("itgsa", ignoreCase = true)
                        }
                        inItgsaList && parser.name == "sub" -> {
                            normalizePackageName(parser.getAttributeValue(null, "val"))?.let(out::add)
                        }
                    }
                    XmlPullParser.END_TAG -> if (parser.name == "config_list") {
                        inItgsaList = false
                    }
                }
                event = parser.next()
            }
        }
        return out
    }

    private fun parseAthenaSceneWhite(xmlContent: String): Set<String> {
        val out = LinkedHashSet<String>()
        withXmlStringParser(xmlContent) { parser ->
            var inSceneList = false
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when {
                        parser.name == "config_list" -> {
                            val key = parser.getAttributeValue(null, "key").orEmpty()
                            inSceneList = key.startsWith("xiaobai_scene_packages")
                        }
                        inSceneList && parser.name == "sub" -> {
                            normalizePackageName(parser.getAttributeValue(null, "val"))?.let(out::add)
                        }
                    }
                    XmlPullParser.END_TAG -> if (parser.name == "config_list") {
                        inSceneList = false
                    }
                }
                event = parser.next()
            }
        }
        return out
    }

    private data class StartupDynamicResult(
        val autostartUserAllow: Set<String>,
        val autostartSystemAllow: Set<String>,
        val autostartDeny: Set<String>,
        val associateUserAllow: Set<String>,
        val associateSystemAllow: Set<String>,
        val associateDeny: Set<String>,
    )

    private fun parseStartupDynamic(path: String): StartupDynamicResult {
        val autoUserAllow = LinkedHashSet<String>()
        val autoSysAllow = LinkedHashSet<String>()
        val autoDeny = LinkedHashSet<String>()
        val assocUserAllow = LinkedHashSet<String>()
        val assocSysAllow = LinkedHashSet<String>()
        val assocDeny = LinkedHashSet<String>()
        withXmlParser(path) { parser ->
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "dynamic") {
                    val pkg = normalizePackageName(parser.getAttributeValue(null, "pkgName"))
                    val type = parser.getAttributeValue(null, "type")
                    val source = parser.getAttributeValue(null, "source")
                    val sw = parser.getAttributeValue(null, "switch")
                    if (pkg != null) {
                        when {
                            type == "1" && sw == "1" -> {
                                if (source == "1") autoUserAllow.add(pkg) else autoSysAllow.add(pkg)
                            }
                            type == "1" && sw == "0" -> autoDeny.add(pkg)
                            type == "0" && sw == "1" -> {
                                if (source == "1") assocUserAllow.add(pkg) else assocSysAllow.add(pkg)
                            }
                            type == "0" && sw == "0" -> assocDeny.add(pkg)
                        }
                    }
                }
                event = parser.next()
            }
        }
        return StartupDynamicResult(
            autostartUserAllow = autoUserAllow,
            autostartSystemAllow = autoSysAllow,
            autostartDeny = autoDeny,
            associateUserAllow = assocUserAllow,
            associateSystemAllow = assocSysAllow,
            associateDeny = assocDeny,
        )
    }

    private data class CpuLimitConfigResult(
        val vipHbg: Set<String>,
        val vipHbgEnable: Boolean,
        val particular: Set<String>,
        val particularEnable: Boolean,
        val black: Set<String>,
    )

    private fun parseCpuLimitConfig(path: String): CpuLimitConfigResult {
        var result = CpuLimitConfigResult(emptySet(), true, emptySet(), true, emptySet())
        withXmlParser(path) { parser ->
            result = parseCpuLimitConfigFromParser(parser)
        }
        return result
    }

    private fun parseCpuLimitConfigString(xmlContent: String): CpuLimitConfigResult {
        var result = CpuLimitConfigResult(emptySet(), true, emptySet(), true, emptySet())
        withXmlStringParser(xmlContent) { parser ->
            result = parseCpuLimitConfigFromParser(parser)
        }
        return result
    }

    private fun mergeCpuLimitResults(local: CpuLimitConfigResult, cloud: CpuLimitConfigResult?): CpuLimitConfigResult {
        if (cloud == null) return local
        return CpuLimitConfigResult(
            vipHbg = local.vipHbg + cloud.vipHbg,
            vipHbgEnable = cloud.vipHbgEnable && local.vipHbgEnable,
            particular = local.particular + cloud.particular,
            particularEnable = cloud.particularEnable && local.particularEnable,
            black = local.black + cloud.black,
        )
    }

    private fun parseCpuLimitConfigFromParser(parser: XmlPullParser): CpuLimitConfigResult {
        val vipHbg = LinkedHashSet<String>()
        val particular = LinkedHashSet<String>()
        val black = LinkedHashSet<String>()
        var vipHbgEnable = true
        var particularEnable = true
        var section: String? = null
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when {
                    parser.name == "cpuSetPkg" && parser.getAttributeValue(null, "category") == "black" -> {
                        section = "black"
                    }
                    parser.name == "vip_HBG" -> {
                        section = "vip_HBG"
                        vipHbgEnable = parser.getAttributeValue(null, "enable")?.equals("false", true) != true
                    }
                    parser.name == "particularPkg" -> {
                        section = "particularPkg"
                        particularEnable = parser.getAttributeValue(null, "enable")?.equals("false", true) != true
                    }
                    section != null && parser.name == "pkg" -> {
                        val pkg = normalizePackageName(parser.getAttributeValue(null, "name"))
                            ?: normalizePackageName(runCatching { parser.nextText() }.getOrNull())
                        if (pkg != null) {
                            when (section) {
                                "black" -> black.add(pkg)
                                "vip_HBG" -> vipHbg.add(pkg)
                                "particularPkg" -> particular.add(pkg)
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "cpuSetPkg", "vip_HBG", "particularPkg" -> section = null
                }
            }
            event = parser.next()
        }
        return CpuLimitConfigResult(vipHbg, vipHbgEnable, particular, particularEnable, black)
    }

    private fun parseFlexiblePkgXml(path: String): Set<String> {
        val file = File(path)
        if (!file.exists() || !file.canRead() || file.length() == 0L) return emptySet()
        val out = LinkedHashSet<String>()
        withXmlParser(path) { parser ->
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    val attrs = listOf("att", "pkg", "pkgName", "packageName", "name")
                    for (a in attrs) {
                        normalizePackageName(parser.getAttributeValue(null, a))?.let(out::add)
                    }
                } else if (event == XmlPullParser.TEXT) {
                    normalizePackageName(parser.text)?.let(out::add)
                }
                event = parser.next()
            }
        }
        return out
    }

    private fun queryRomUpdateFilters(context: Context?, filterNames: Set<String>): Map<String, String> {
        val resolver = context?.contentResolver ?: return emptyMap()
        val result = HashMap<String, String>()
        runCatching {
            resolver.query(
                ROMUPDATE_URI,
                arrayOf("filterName", "xml"),
                null,
                null,
                null,
            )?.use { cursor ->
                val nameCol = cursor.getColumnIndex("filterName")
                val xmlCol = cursor.getColumnIndex("xml")
                if (nameCol < 0 || xmlCol < 0) return@use
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameCol) ?: continue
                    if (name in filterNames) {
                        val xml = cursor.getString(xmlCol)
                        if (!xml.isNullOrBlank()) {
                            result[name] = xml
                        }
                    }
                }
            }
        }
        return result
    }

    private fun readRuntimeRecentLockList(classLoader: ClassLoader?): Set<String> {
        val cl = classLoader ?: return emptySet()
        val out = LinkedHashSet<String>()
        runCatching {
            val clazz = cl.loadClass("com.android.server.OplusListManagerImpl")
            val instance = clazz.getMethod("getInstance").invoke(null) ?: return@runCatching
            val getConfigInfo = runCatching {
                clazz.getMethod("getConfigInfo", String::class.java, Int::class.javaPrimitiveType)
            }.getOrNull()
            if (getConfigInfo != null) {
                for (configKey in listOf("recent_lock_list", "recent_super_lock_list")) {
                    val bundle = runCatching {
                        getConfigInfo.invoke(instance, configKey, 0) as? Bundle
                    }.getOrNull() ?: continue
                    bundle.getStringArrayList(configKey)?.forEach { entry ->
                        normalizePackageName(entry)?.let(out::add)
                    }
                }
            }
            val lockRaw = clazz.getMethod("getRecentLockListWithUserIdAsUser", Int::class.javaPrimitiveType)
                .invoke(instance, 0) as? List<*>
            lockRaw?.forEach { entry ->
                val rawPkg = (entry as? android.util.Pair<*, *>)?.first as? String ?: entry as? String
                normalizePackageName(rawPkg)?.let(out::add)
            }
        }
        runCatching {
            val cfgClass = cl.loadClass("com.oplus.util.OplusCommonConfig")
            val cfg = cfgClass.getMethod("getInstance").invoke(null) ?: return@runCatching
            val getConfigInfo = runCatching {
                cfgClass.getMethod("getConfigInfo", String::class.java, Int::class.javaPrimitiveType)
            }.getOrNull()
            val getConfigInfoAsUser = runCatching {
                cfgClass.getMethod(
                    "getConfigInfoAsUser",
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                )
            }.getOrNull()
            for (configKey in listOf("recent_lock_list", "recent_super_lock_list")) {
                val b1 = getConfigInfo?.let {
                    runCatching { it.invoke(cfg, configKey, 1) as? Bundle }.getOrNull()
                }
                b1?.getStringArrayList(configKey)?.forEach { entry ->
                    normalizePackageName(entry)?.let(out::add)
                }
                val b2 = getConfigInfoAsUser?.let {
                    runCatching { it.invoke(cfg, configKey, 1, 0) as? Bundle }.getOrNull()
                }
                b2?.getStringArrayList(configKey)?.forEach { entry ->
                    normalizePackageName(entry)?.let(out::add)
                }
            }
        }
        return out
    }

    private fun readLauncherForbidLockList(context: Context?): Set<String> {
        val pm = context?.packageManager ?: return emptySet()
        val out = LinkedHashSet<String>()
        listOf("com.android.launcher", "com.oppo.launcher").forEach { launcherPkg ->
            runCatching {
                val res = pm.getResourcesForApplication(launcherPkg)
                val id = res.getIdentifier("forbid_lock_applications", "array", launcherPkg)
                if (id != 0) {
                    res.getStringArray(id).forEach { raw ->
                        normalizePackageName(raw)?.let(out::add)
                    }
                }
            }
        }
        return out
    }

    private fun parseLineList(path: String): Set<String> {
        val file = File(path)
        if (!file.exists() || !file.canRead()) return emptySet()
        return runCatching {
            file.readLines()
                .map { it.trim() }
                .filter { !it.startsWith("#") }
                .mapNotNull { normalizePackageName(it) }
                .toSet()
        }.getOrDefault(emptySet())
    }

    private fun normalizePackageName(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed.contains(' ')) return null
        val pkg = trimmed.substringBefore('#').substringBefore(':')
        return pkg.takeIf { it.contains('.') && !it.startsWith(".") && !it.endsWith(".") }
    }

    private inline fun withXmlParser(path: String, block: (XmlPullParser) -> Unit) {
        val file = File(path)
        if (!file.exists() || !file.canRead() || file.length() == 0L) return
        runCatching {
            file.inputStream().buffered().use { input ->
                val parser = Xml.newPullParser()
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                parser.setInput(input, "UTF-8")
                block(parser)
            }
        }
    }

    private inline fun withXmlStringParser(xmlContent: String, block: (XmlPullParser) -> Unit) {
        if (xmlContent.isBlank()) return
        runCatching {
            StringReader(xmlContent).use { reader ->
                val parser = Xml.newPullParser()
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                parser.setInput(reader)
                block(parser)
            }
        }
    }
}
