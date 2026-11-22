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
        val elsaLists = parseElsaConfig("/data/oplus/os/bpm/sys_elsa_config_list.xml")
        val bpmAll = parseAttTagXml("/data/oplus/os/bpm/bpm.xml", setOf("p"), "att")
        val fixedRecordUser = parseFixedRecord("/data/oplus/os/bpm/fixedRecord.xml")
        val hansUserWhite = fixedRecordUser + (bpmAll - elsaLists.white)
        val hansSystemWhite = elsaLists.white + (bpmAll - hansUserWhite)

        val notRestrictAll = parseAttTagXml("/data/oplus/os/battery/not_restrict.xml", setOf("p"), "att")
        val notifyPresetTemplate = parseAttTagXml("/data/oplus/os/battery/notify_whitelist.xml", setOf("p"), "att")
        val allowProhibitUser = parseTextTagXml("/data/oplus/os/battery/allow_prohibit_app.xml", setOf("allow"))
        val batteryUserWhite = allowProhibitUser + (notRestrictAll - notifyPresetTemplate)

        val dozeWhite = parseTextTagXml("/data/oplus/os/battery/doze_wl_local.xml", setOf("wl")) +
            parseTextTagXml("/data/system/deviceidle.xml", setOf("wl")) +
            (notRestrictAll - batteryUserWhite)

        val startupV3 = parseStartupV3("/data/oplus/os/startup/sys_startup_v3_config_list.xml")
        val customStartupV3 = parseStartupV3("/data/oplus/os/startup/sys_custom_startup_v3_config_list.xml")
        val startupDynamic = parseStartupDynamic("/data/oplus/os/startup/startup_dynamic_list.xml")

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
            ) - startupDynamic.associateDeny - associateUserWhite

        val romUpdateFilters = queryRomUpdateFilters(
            context = systemContext,
            filterNames = setOf("sys_guardelf_limitbkg_blacklist", "sys_guardelf_config_list"),
        )
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
            readRuntimeAthenaProtectList(systemClassLoader)

        val recentForbid = parseFlexiblePkgXml("/data/oplus/os/recenttask/forbid_lock_apps.xml")

        val cpuLimitConfig = parseCpuLimitConfig("/data/oplus/os/bpm/sys_cpulimit_config.xml")
        val skipBroadcastWhite = parseTextTagXml("/data/oplus/os/config/sys_ams_skipbroadcast.xml", setOf("wl")) +
            parseTextTagXml("/data/oplus/os/sys_ams_skipbroadcast.xml", setOf("wl")) +
            parseTextTagXml("/system_ext/oplus/startup_config.xml", setOf("skipBroadcastWhite"))

        val effectiveCpuWhite = if (cpuLimitConfig.particularEnable) cpuLimitConfig.particular else emptySet()
        val cpuLimitWhite = cpuLimitConfig.particular
        val cpuLimitVipHbg = cpuLimitConfig.vipHbg - effectiveCpuWhite
        val effectiveCpuVipHbg = if (cpuLimitConfig.vipHbgEnable) cpuLimitVipHbg else emptySet()
        val cpuLimitBlack = cpuLimitConfig.black - effectiveCpuWhite - effectiveCpuVipHbg
        val dozeNetWhite = guardElfCloud.netWhite

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
            NativeListKind.ATHENA_TRIM_WHITE to elsaLists.trimMemWhite,
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
        val white = LinkedHashSet<String>()
        val black = LinkedHashSet<String>()
        val cpuCtlWhite = LinkedHashSet<String>()
        val trafficWhite = LinkedHashSet<String>()
        val trimMemWhite = LinkedHashSet<String>()
        var hansEnable = true
        var trimMemEnable = true
        withXmlParser(path) { parser ->
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
            parseAttTagXmlFromParser(parser, tags, attr, out)
        }
        return out
    }

    private fun parseAttTagXmlString(xmlContent: String, tags: Set<String>, attr: String): Set<String> {
        val out = LinkedHashSet<String>()
        withXmlStringParser(xmlContent) { parser ->
            parseAttTagXmlFromParser(parser, tags, attr, out)
        }
        return out
    }

    private fun parseAttTagXmlFromParser(
        parser: XmlPullParser,
        tags: Set<String>,
        attr: String,
        out: MutableSet<String>,
    ) {
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name in tags) {
                normalizePackageName(parser.getAttributeValue(null, attr))?.let(out::add)
            }
            event = parser.next()
        }
    }

    private fun parseTextTagXml(path: String, tags: Set<String>): Set<String> {
        val out = LinkedHashSet<String>()
        withXmlParser(path) { parser ->
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
        return out
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
        val auto = LinkedHashSet<String>()
        val assoc = LinkedHashSet<String>()
        withXmlParser(path) { parser ->
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
        }
        return StartupV3Result(auto, assoc)
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
        val vipHbg = LinkedHashSet<String>()
        val particular = LinkedHashSet<String>()
        val black = LinkedHashSet<String>()
        var vipHbgEnable = true
        var particularEnable = true
        var section: String? = null
        withXmlParser(path) { parser ->
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

    private fun readRuntimeAthenaProtectList(classLoader: ClassLoader?): Set<String> {
        val cl = classLoader ?: return emptySet()
        return runCatching {
            val clazz = cl.loadClass("com.android.server.wm.OplusAthenaManager")
            val instance = clazz.getMethod("getInstance").invoke(null) ?: return emptySet()
            val rawList = clazz.getMethod("getProtectList").invoke(instance) as? List<*> ?: return emptySet()
            rawList.mapNotNull { normalizePackageName(it as? String) }.toSet()
        }.getOrDefault(emptySet())
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
        val pkg = trimmed.substringBefore(':')
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
