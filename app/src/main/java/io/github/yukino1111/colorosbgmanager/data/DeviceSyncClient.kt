package io.github.yukino1111.colorosbgmanager.data

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import io.github.yukino1111.colorosbgmanager.model.BgSubsystem
import io.github.yukino1111.colorosbgmanager.model.GlobalRuleFlags
import io.github.yukino1111.colorosbgmanager.model.PolicyMode
import io.github.yukino1111.colorosbgmanager.model.RulesCodec
import io.github.yukino1111.colorosbgmanager.model.SystemSnapshot
import io.github.yukino1111.colorosbgmanager.ui.AppLanguage
import io.github.yukino1111.colorosbgmanager.ui.stringsFor
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.util.Locale

data class InstalledAppEntry(
    val label: String,
    val packageName: String,
    val icon: Drawable,
    val isSystem: Boolean,
)

data class BgMasterUiState(
    val language: AppLanguage = AppLanguage.ZH,
    val xposedConnected: Boolean = false,
    val xposedApiVersion: Int = 0,
    val snapshot: SystemSnapshot = SystemSnapshot(),
    val rules: Map<String, Map<BgSubsystem, PolicyMode>> = emptyMap(),
    val globalFlags: GlobalRuleFlags = GlobalRuleFlags(),
    val apps: List<InstalledAppEntry> = emptyList(),
    val loadingApps: Boolean = true,
    val loadingSnapshot: Boolean = true,
    val statusMessage: String? = null,
)

class DeviceSyncClient(private val context: Context) {

    private val uiPrefs: SharedPreferences =
        context.getSharedPreferences(UI_PREF_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(
        BgMasterUiState(
            language = AppLanguage.fromCode(
                code = uiPrefs.getString(KEY_LANGUAGE, null),
                defaultZh = Locale.getDefault().language.startsWith("zh"),
            ),
        ),
    )
    val state: StateFlow<BgMasterUiState> = _state.asStateFlow()

    private var remotePrefs: SharedPreferences? = null

    private val serviceListener = object : XposedServiceHelper.OnServiceListener {
        override fun onServiceBind(service: XposedService) {
            val prefs = service.getRemotePreferences(RulesCodec.PREF_NAME)
            remotePrefs = prefs
            val rules = RulesCodec.decodeRules(prefs.getStringSet(RulesCodec.KEY_RULES_SET, emptySet()))
            val globals = RulesCodec.readGlobalFlags(prefs)
            _state.update {
                it.copy(
                    xposedConnected = true,
                    xposedApiVersion = service.apiVersion,
                    rules = rules,
                    globalFlags = globals,
                )
            }
        }

        override fun onServiceDied(service: XposedService) {
            remotePrefs = null
            _state.update { it.copy(xposedConnected = false) }
        }
    }

    fun start() {
        XposedServiceHelper.registerListener(serviceListener)
    }

    fun toggleLanguage() {
        val next = _state.value.language.toggle()
        uiPrefs.edit().putString(KEY_LANGUAGE, next.code).apply()
        _state.update { it.copy(language = next) }
    }

    suspend fun refreshAll(userTriggered: Boolean = false) {
        refreshSnapshot(showResultToast = userTriggered)
        loadApps()
    }

    suspend fun refreshSnapshot(showResultToast: Boolean = false) {
        _state.update { it.copy(loadingSnapshot = true) }
        val snap = withContext(Dispatchers.IO) {
            runCatching {
                val bundle = context.contentResolver.call(
                    Uri.parse(RulesCodec.BRIDGE_URI),
                    RulesCodec.BRIDGE_METHOD,
                    null,
                    null,
                )
                SystemSnapshot.fromBundle(bundle)
            }.getOrDefault(SystemSnapshot(serverInjected = false))
        }
        _state.update { current ->
            val strings = stringsFor(current.language)
            val msg = if (showResultToast) {
                if (snap.serverInjected) {
                    val activeHooks = BgSubsystem.entries.count { snap.hookStatus[it.key] == true }
                    val totalLists = snap.lists.values.sumOf { it.size }
                    strings.toastRefreshed(activeHooks, BgSubsystem.entries.size, totalLists)
                } else {
                    strings.toastNotInjected
                }
            } else {
                current.statusMessage
            }
            current.copy(snapshot = snap, loadingSnapshot = false, statusMessage = msg)
        }
    }

    suspend fun loadApps() {
        _state.update { it.copy(loadingApps = true) }
        val loaded = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val packages = pm.getInstalledApplications(PackageManager.MATCH_ALL)
            packages
                .filter { it.packageName != context.packageName }
                .map { info ->
                    InstalledAppEntry(
                        label = pm.getApplicationLabel(info).toString(),
                        packageName = info.packageName,
                        icon = pm.getApplicationIcon(info),
                        isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    )
                }
                .sortedBy { it.label.lowercase() }
        }
        _state.update { it.copy(apps = loaded, loadingApps = false) }
    }

    fun setRule(pkgName: String, subsystem: BgSubsystem, mode: PolicyMode) {
        val prefs = remotePrefs ?: run {
            postMessage(stringsFor(_state.value.language).toastNeedLposedRule)
            return
        }
        val updated = _state.value.rules.toMutableMap().apply {
            val pkgMap = (this[pkgName]?.toMutableMap() ?: mutableMapOf()).apply {
                if (mode == PolicyMode.FOLLOW_SYSTEM) remove(subsystem) else put(subsystem, mode)
            }
            if (pkgMap.isEmpty()) remove(pkgName) else put(pkgName, pkgMap)
        }
        prefs.edit().putStringSet(RulesCodec.KEY_RULES_SET, RulesCodec.encodeRules(updated)).apply()
        _state.update { it.copy(rules = updated) }
    }

    fun setAllSubsystemsForPackage(pkgName: String, mode: PolicyMode) {
        val prefs = remotePrefs ?: run {
            postMessage(stringsFor(_state.value.language).toastNeedLposedRule)
            return
        }
        val updated = _state.value.rules.toMutableMap().apply {
            if (mode == PolicyMode.FOLLOW_SYSTEM) {
                remove(pkgName)
            } else {
                put(pkgName, BgSubsystem.entries.associateWith { mode })
            }
        }
        prefs.edit().putStringSet(RulesCodec.KEY_RULES_SET, RulesCodec.encodeRules(updated)).apply()
        _state.update { it.copy(rules = updated) }
    }

    fun updateGlobalFlag(transform: (GlobalRuleFlags) -> GlobalRuleFlags) {
        val prefs = remotePrefs ?: run {
            postMessage(stringsFor(_state.value.language).toastNeedLposedSetting)
            return
        }
        val next = transform(_state.value.globalFlags)
        prefs.edit()
            .putBoolean(RulesCodec.KEY_GLOBAL_GUARDELF, next.disableGuardElfCpuKill)
            .putBoolean(RulesCodec.KEY_GLOBAL_BROADCAST, next.disableBroadcastQuotaGlobal)
            .putBoolean(RulesCodec.KEY_GLOBAL_STRICT, next.disableStrictModeFreeze)
            .putBoolean(RulesCodec.KEY_VERBOSE_LOG, next.verboseLog)
            .apply()
        _state.update { it.copy(globalFlags = next) }
    }

    fun clearMessage() {
        _state.update { it.copy(statusMessage = null) }
    }

    private fun postMessage(msg: String) {
        _state.update { it.copy(statusMessage = msg) }
    }

    companion object {
        private const val UI_PREF_NAME = "ui_prefs"
        private const val KEY_LANGUAGE = "app_language"
    }
}
