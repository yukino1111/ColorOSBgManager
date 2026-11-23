package io.github.yukino1111.colorosbgmanager.data

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.UserHandle
import io.github.yukino1111.colorosbgmanager.model.AppProcessSummary
import io.github.yukino1111.colorosbgmanager.model.AppUserKey
import io.github.yukino1111.colorosbgmanager.model.BgSubsystem
import io.github.yukino1111.colorosbgmanager.model.BridgeAppEntry
import io.github.yukino1111.colorosbgmanager.model.DeviceUserInfo
import io.github.yukino1111.colorosbgmanager.model.GlobalRuleFlags
import io.github.yukino1111.colorosbgmanager.model.PkgRuntimeStatus
import io.github.yukino1111.colorosbgmanager.model.PolicyMode
import io.github.yukino1111.colorosbgmanager.model.ProcessDetailInfo
import io.github.yukino1111.colorosbgmanager.model.ProcessRunBucket
import io.github.yukino1111.colorosbgmanager.model.RootProcessInfo
import io.github.yukino1111.colorosbgmanager.model.RulesCodec
import io.github.yukino1111.colorosbgmanager.model.RuntimeEvent
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
import java.util.UUID

data class InstalledAppEntry(
    val label: String,
    val packageName: String,
    val userId: Int,
    val uid: Int,
    val icon: Drawable,
    val isSystem: Boolean,
    val isSuspended: Boolean = false,
) {
    val key: AppUserKey get() = AppUserKey(packageName, userId)
}

data class BgMasterUiState(
    val language: AppLanguage = AppLanguage.ZH,
    val xposedConnected: Boolean = false,
    val xposedApiVersion: Int = 0,
    val snapshot: SystemSnapshot = SystemSnapshot(),
    val users: List<DeviceUserInfo> = listOf(DeviceUserInfo(0, "Owner", false)),
    val rules: Map<AppUserKey, Map<BgSubsystem, PolicyMode>> = emptyMap(),
    val autoFreezePkgs: Set<AppUserKey> = emptySet(),
    val globalFlags: GlobalRuleFlags = GlobalRuleFlags(),
    val apps: List<InstalledAppEntry> = emptyList(),
    val loadingApps: Boolean = true,
    val loadingSnapshot: Boolean = true,
    val appProcessSummaries: List<AppProcessSummary> = emptyList(),
    val loadingProcesses: Boolean = false,
    val rootProcesses: List<RootProcessInfo> = emptyList(),
    val rootAvailable: Boolean? = null,
    val rootError: String? = null,
    val loadingRootProcesses: Boolean = false,
    val recentEvents: List<RuntimeEvent> = emptyList(),
    val detailRuntime: PkgRuntimeStatus? = null,
    val detailEvents: List<RuntimeEvent> = emptyList(),
    val loadingDetailRuntime: Boolean = false,
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
            val freezeSet = RulesCodec.decodeFreezePackages(prefs.getStringSet(RulesCodec.KEY_FREEZE_PKGS_SET, emptySet()))
            val globals = RulesCodec.readGlobalFlags(prefs)
            _state.update {
                it.copy(
                    xposedConnected = true,
                    xposedApiVersion = service.apiVersion,
                    rules = rules,
                    autoFreezePkgs = freezeSet,
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
        val (snap, events) = withContext(Dispatchers.IO) {
            val snapshot = runCatching {
                val bundle = context.contentResolver.call(
                    Uri.parse(RulesCodec.BRIDGE_URI),
                    RulesCodec.BRIDGE_METHOD,
                    null,
                    null,
                )
                SystemSnapshot.fromBundle(bundle)
            }.getOrDefault(SystemSnapshot(serverInjected = false))
            val runtimeEvents = if (snapshot.serverInjected) {
                queryRuntimeEvents(null, 0)
            } else {
                emptyList()
            }
            snapshot to runtimeEvents
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
            current.copy(
                snapshot = snap,
                loadingSnapshot = false,
                recentEvents = events,
                statusMessage = msg,
            )
        }
    }

    suspend fun loadPackageRuntime(pkgName: String, userId: Int) {
        _state.update { it.copy(loadingDetailRuntime = true) }
        val (runtime, events) = withContext(Dispatchers.IO) {
            val bundle = runCatching {
                context.contentResolver.call(
                    Uri.parse(RulesCodec.BRIDGE_URI),
                    RulesCodec.BRIDGE_RUNTIME_METHOD,
                    pkgName,
                    Bundle().apply { putInt("user_id", userId) },
                )
            }.getOrNull()
            val status = PkgRuntimeStatus.fromBundle(bundle, pkgName, userId)
            if (status.available) {
                ShortcutHelper.syncPinnedShortcut(context, pkgName, userId, status.isSuspended)
            }
            val decoded = bundle?.getStringArrayList("events")
                ?.mapNotNull { RuntimeEvent.decode(it) }
                .orEmpty()
            status to decoded
        }
        _state.update { current ->
            val updatedApps = if (runtime.available) {
                current.apps.map { app ->
                    if (app.packageName == pkgName && app.userId == userId) {
                        app.copy(isSuspended = runtime.isSuspended)
                    } else {
                        app
                    }
                }
            } else {
                current.apps
            }
            current.copy(
                apps = updatedApps,
                detailRuntime = runtime,
                detailEvents = events,
                loadingDetailRuntime = false,
            )
        }
    }

    private fun queryRuntimeEvents(pkgName: String?, userId: Int): List<RuntimeEvent> = runCatching {
        val bundle = context.contentResolver.call(
            Uri.parse(RulesCodec.BRIDGE_URI),
            RulesCodec.BRIDGE_RUNTIME_METHOD,
            pkgName,
            Bundle().apply { putInt("user_id", userId) },
        )
        bundle?.getStringArrayList("events")
            ?.mapNotNull { RuntimeEvent.decode(it) }
            .orEmpty()
    }.getOrDefault(emptyList())

    suspend fun loadApps() {
        _state.update { it.copy(loadingApps = true) }
        val (loadedUsers, loadedApps) = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val baseIconCache = HashMap<String, Drawable>()
            fun loadBaseIcon(pkg: String): Drawable {
                return baseIconCache.getOrPut(pkg) {
                    runCatching { pm.getApplicationIcon(pkg) }.getOrElse { pm.defaultActivityIcon }
                }
            }

            fun loadBadgedIcon(pkg: String, uid: Int, userId: Int): Drawable {
                val base = loadBaseIcon(pkg)
                if (userId == 0) return base
                return runCatching {
                    pm.getUserBadgedIcon(base, UserHandle.getUserHandleForUid(uid))
                }.getOrDefault(base)
            }

            val bridgeBundle = runCatching {
                context.contentResolver.call(
                    Uri.parse(RulesCodec.BRIDGE_URI),
                    RulesCodec.BRIDGE_APPS_METHOD,
                    null,
                    null,
                )
            }.getOrNull()

            if (bridgeBundle?.getBoolean("ok", false) == true) {
                val users = bridgeBundle.getStringArrayList("users")
                    ?.mapNotNull { DeviceUserInfo.decode(it) }
                    ?.ifEmpty { null }
                    ?: listOf(DeviceUserInfo(0, "Owner", false))
                val rawApps = bridgeBundle.getStringArrayList("apps")
                    ?.mapNotNull { BridgeAppEntry.decode(it) }
                    .orEmpty()
                val entries = rawApps
                    .filter { it.packageName != context.packageName || it.userId != 0 }
                    .map { item ->
                        InstalledAppEntry(
                            label = item.label,
                            packageName = item.packageName,
                            userId = item.userId,
                            uid = item.uid,
                            icon = loadBadgedIcon(item.packageName, item.uid, item.userId),
                            isSystem = item.isSystem,
                            isSuspended = item.isSuspended,
                        )
                    }
                    .distinctBy { it.key }
                    .sortedWith(compareBy<InstalledAppEntry> { it.label.lowercase() }.thenBy { it.userId })
                users to entries
            } else {
                val packages = pm.getInstalledApplications(PackageManager.MATCH_ALL)
                val entries = packages
                    .filter { it.packageName != context.packageName }
                    .map { info ->
                        val suspended = runCatching { pm.isPackageSuspended(info.packageName) }.getOrDefault(false)
                        InstalledAppEntry(
                            label = pm.getApplicationLabel(info).toString(),
                            packageName = info.packageName,
                            userId = 0,
                            uid = info.uid,
                            icon = loadBaseIcon(info.packageName),
                            isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                            isSuspended = suspended,
                        )
                    }
                    .sortedBy { it.label.lowercase() }
                listOf(DeviceUserInfo(0, "Owner", false)) to entries
            }
        }
        withContext(Dispatchers.IO) {
            ShortcutHelper.syncAllPinnedShortcuts(context, loadedApps)
        }
        _state.update { it.copy(users = loadedUsers, apps = loadedApps, loadingApps = false) }
    }

    suspend fun loadProcesses(showSpinner: Boolean = false) {
        if (showSpinner) {
            _state.update { it.copy(loadingProcesses = true) }
        }
        val currentApps = _state.value.apps
        val summaries = withContext(Dispatchers.IO) {
            val bundle = runCatching {
                context.contentResolver.call(
                    Uri.parse(RulesCodec.BRIDGE_URI),
                    RulesCodec.BRIDGE_PROCESSES_METHOD,
                    null,
                    null,
                )
            }.getOrNull()
            if (bundle?.getBoolean("ok", false) != true) {
                return@withContext emptyList()
            }
            val procs = bundle.getStringArrayList("procs")
                ?.mapNotNull { ProcessDetailInfo.decode(it) }
                .orEmpty()
            val extraServicesMap = HashMap<AppUserKey, List<String>>()
            bundle.getStringArrayList("extra_services")?.forEach { line ->
                val parts = line.split('\t', limit = 3)
                if (parts.size == 3) {
                    val userId = parts[0].toIntOrNull() ?: return@forEach
                    val pkg = parts[1]
                    val svcs = parts[2].split(',').filter { it.isNotBlank() }
                    extraServicesMap[AppUserKey(pkg, userId)] = svcs
                }
            }
            aggregateProcessSummaries(currentApps, procs, extraServicesMap)
        }
        _state.update { it.copy(appProcessSummaries = summaries, loadingProcesses = false) }
    }

    suspend fun loadRootProcesses(showSpinner: Boolean = false) {
        if (showSpinner) {
            _state.update { it.copy(loadingRootProcesses = true) }
        }
        val res = withContext(Dispatchers.IO) { RootProcessClient.queryRootProcesses() }
        _state.update {
            it.copy(
                rootProcesses = res.processes,
                rootAvailable = res.rootGranted,
                rootError = res.error,
                loadingRootProcesses = false,
            )
        }
    }

    private fun aggregateProcessSummaries(
        apps: List<InstalledAppEntry>,
        procs: List<ProcessDetailInfo>,
        extraServicesMap: Map<AppUserKey, List<String>>,
    ): List<AppProcessSummary> {
        val procsByApp = procs.groupBy { AppUserKey(it.packageName, it.userId) }
        return apps.map { app ->
            val key = app.key
            val appProcs = procsByApp[key].orEmpty()
                .sortedWith(compareBy<ProcessDetailInfo> { it.isCached }.thenByDescending { it.memoryKb }.thenByDescending { it.cpuPercent })
            val extraSvcs = extraServicesMap[key].orEmpty()
            if (appProcs.isEmpty()) {
                AppProcessSummary(
                    packageName = app.packageName,
                    userId = app.userId,
                    bucket = ProcessRunBucket.STOPPED,
                    totalMemoryKb = 0L,
                    totalCpuPercent = 0f,
                    maxUptimeMs = 0L,
                    processCount = 0,
                    serviceCount = 0,
                    processes = emptyList(),
                )
            } else {
                val hasActive = appProcs.any { !it.isCached }
                val bucket = if (hasActive) ProcessRunBucket.RUNNING else ProcessRunBucket.CACHED
                val memKb = appProcs.sumOf { it.memoryKb }
                val cpu = appProcs.sumOf { it.cpuPercent.toDouble() }.toFloat()
                val uptime = appProcs.maxOfOrNull { it.uptimeMs } ?: 0L
                val svcCount = appProcs.sumOf { it.services.size } + extraSvcs.size
                AppProcessSummary(
                    packageName = app.packageName,
                    userId = app.userId,
                    bucket = bucket,
                    totalMemoryKb = memKb,
                    totalCpuPercent = cpu,
                    maxUptimeMs = uptime,
                    processCount = appProcs.size,
                    serviceCount = svcCount,
                    processes = appProcs,
                    extraServices = extraSvcs,
                )
            }
        }
    }

    fun setRule(target: AppUserKey, subsystem: BgSubsystem, mode: PolicyMode) {
        val prefs = remotePrefs ?: run {
            postMessage(stringsFor(_state.value.language).toastNeedLposedRule)
            return
        }
        val updated = _state.value.rules.toMutableMap().apply {
            val pkgMap = (this[target]?.toMutableMap() ?: mutableMapOf()).apply {
                if (mode == PolicyMode.FOLLOW_SYSTEM) remove(subsystem) else put(subsystem, mode)
            }
            if (pkgMap.isEmpty()) remove(target) else put(target, pkgMap)
        }
        prefs.edit().putStringSet(RulesCodec.KEY_RULES_SET, RulesCodec.encodeRules(updated)).apply()
        _state.update { it.copy(rules = updated) }
    }

    fun setAllSubsystemsForPackage(target: AppUserKey, mode: PolicyMode) {
        val prefs = remotePrefs ?: run {
            postMessage(stringsFor(_state.value.language).toastNeedLposedRule)
            return
        }
        val updated = _state.value.rules.toMutableMap().apply {
            if (mode == PolicyMode.FOLLOW_SYSTEM) {
                remove(target)
            } else {
                put(target, BgSubsystem.entries.associateWith { mode })
            }
        }
        prefs.edit().putStringSet(RulesCodec.KEY_RULES_SET, RulesCodec.encodeRules(updated)).apply()
        _state.update { it.copy(rules = updated) }
    }

    fun setAutoFreeze(target: AppUserKey, enabled: Boolean) {
        val prefs = remotePrefs ?: run {
            postMessage(stringsFor(_state.value.language).toastNeedLposedSetting)
            return
        }
        val updated = _state.value.autoFreezePkgs.toMutableSet().apply {
            if (enabled) add(target) else remove(target)
        }
        prefs.edit().putStringSet(RulesCodec.KEY_FREEZE_PKGS_SET, RulesCodec.encodeFreezePackages(updated)).apply()
        _state.update { it.copy(autoFreezePkgs = updated) }
    }

    suspend fun setPackageSuspended(target: AppUserKey, suspend: Boolean) {
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val res = context.contentResolver.call(
                    Uri.parse(RulesCodec.BRIDGE_URI),
                    RulesCodec.BRIDGE_SET_SUSPEND_METHOD,
                    null,
                    Bundle().apply {
                        putString("pkg", target.packageName)
                        putInt("user_id", target.userId)
                        putBoolean("suspend", suspend)
                    },
                )
                res?.getBoolean("ok", false) == true
            }.getOrDefault(false)
        }
        val strings = stringsFor(_state.value.language)
        if (ok) {
            withContext(Dispatchers.IO) {
                ShortcutHelper.syncPinnedShortcut(context, target.packageName, target.userId, suspend)
            }
            loadPackageRuntime(target.packageName, target.userId)
            postMessage(if (suspend) strings.toastSuspendOk else strings.toastUnsuspendOk)
        } else {
            postMessage(strings.toastActionFailed)
        }
    }

    suspend fun launchApp(target: AppUserKey) {
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val res = context.contentResolver.call(
                    Uri.parse(RulesCodec.BRIDGE_URI),
                    RulesCodec.BRIDGE_LAUNCH_APP_METHOD,
                    null,
                    Bundle().apply {
                        putString("pkg", target.packageName)
                        putInt("user_id", target.userId)
                    },
                )
                val launched = res?.getBoolean("ok", false) == true
                if (launched) {
                    ShortcutHelper.syncPinnedShortcut(context, target.packageName, target.userId, false)
                }
                launched
            }.getOrDefault(false)
        }
        if (ok) {
            _state.update { current ->
                current.copy(
                    apps = current.apps.map { app ->
                        if (app.key == target) app.copy(isSuspended = false) else app
                    },
                    detailRuntime = current.detailRuntime?.let { rt ->
                        if (rt.packageName == target.packageName && rt.userId == target.userId) {
                            rt.copy(isSuspended = false)
                        } else {
                            rt
                        }
                    },
                )
            }
        } else {
            postMessage(stringsFor(_state.value.language).toastLaunchFailed)
        }
    }

    fun pinLaunchShortcut(app: InstalledAppEntry) {
        val strings = stringsFor(_state.value.language)
        val pinned = ShortcutHelper.pinShortcut(context, app)
        postMessage(if (pinned) strings.toastShortcutRequested else strings.toastShortcutUnsupported)
    }

    fun updateGlobalFlag(transform: (GlobalRuleFlags) -> GlobalRuleFlags) {
        val prefs = remotePrefs ?: run {
            postMessage(stringsFor(_state.value.language).toastNeedLposedSetting)
            return
        }
        val rawNext = transform(_state.value.globalFlags)
        val next = rawNext.copy(
            autoFreezeDelaySec = RulesCodec.normalizeAutoFreezeDelaySec(rawNext.autoFreezeDelaySec),
        )
        prefs.edit()
            .putBoolean(RulesCodec.KEY_GLOBAL_GUARDELF, next.disableGuardElfCpuKill)
            .putBoolean(RulesCodec.KEY_GLOBAL_BROADCAST, next.disableBroadcastQuotaGlobal)
            .putBoolean(RulesCodec.KEY_GLOBAL_STRICT, next.disableStrictModeFreeze)
            .putBoolean(RulesCodec.KEY_VERBOSE_LOG, next.verboseLog)
            .putInt(RulesCodec.KEY_AUTO_FREEZE_DELAY_SEC, next.autoFreezeDelaySec)
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
        private const val KEY_SHORTCUT_TOKEN = "shortcut_secret_token"

        internal fun getOrCreateShortcutToken(ctx: Context): String {
            val prefs = ctx.getSharedPreferences(UI_PREF_NAME, Context.MODE_PRIVATE)
            prefs.getString(KEY_SHORTCUT_TOKEN, null)?.let { return it }
            val generated = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_SHORTCUT_TOKEN, generated).apply()
            return generated
        }

        fun verifyShortcutToken(ctx: Context, provided: String): Boolean {
            val prefs = ctx.getSharedPreferences(UI_PREF_NAME, Context.MODE_PRIVATE)
            val saved = prefs.getString(KEY_SHORTCUT_TOKEN, null) ?: return false
            return saved == provided
        }
    }
}
