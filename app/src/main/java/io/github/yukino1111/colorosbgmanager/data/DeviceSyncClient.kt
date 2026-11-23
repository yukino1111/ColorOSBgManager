package io.github.yukino1111.colorosbgmanager.data

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import android.os.UserHandle
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.scale
import io.github.yukino1111.colorosbgmanager.LaunchSuspendedActivity
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
                res?.getBoolean("ok", false) == true
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
        val sm = context.getSystemService(ShortcutManager::class.java)
        if (sm == null || !sm.isRequestPinShortcutSupported) {
            postMessage(strings.toastShortcutUnsupported)
            return
        }
        val token = getOrCreateShortcutToken(context)
        val launchIntent = Intent(context, LaunchSuspendedActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(LaunchSuspendedActivity.EXTRA_PKG, app.packageName)
            putExtra(LaunchSuspendedActivity.EXTRA_USER_ID, app.userId)
            putExtra(LaunchSuspendedActivity.EXTRA_TOKEN, token)
        }
        val shortcutLabel = if (app.userId == 0) app.label else "${app.label} (u${app.userId})"
        val icon = buildShortcutIcon(app)
        val shortcutId = "launch_${app.userId}_${app.packageName}"
        val shortcut = ShortcutInfo.Builder(context, shortcutId)
            .setShortLabel(shortcutLabel)
            .setLongLabel(shortcutLabel)
            .setIcon(icon)
            .setIntent(launchIntent)
            .build()
        if (sm.pinnedShortcuts.any { it.id == shortcutId }) {
            runCatching { sm.updateShortcuts(listOf(shortcut)) }
        }
        val pinned = runCatching { sm.requestPinShortcut(shortcut, null) }.getOrDefault(false)
        postMessage(if (pinned) strings.toastShortcutRequested else strings.toastShortcutUnsupported)
    }

    private fun buildShortcutIcon(app: InstalledAppEntry): Icon {
        val pm = context.packageManager
        var drawable = pm.getLaunchIntentForPackage(app.packageName)?.component?.let { cmp ->
            runCatching { pm.getActivityIcon(cmp) }.getOrNull()
        } ?: runCatching { pm.getApplicationIcon(app.packageName) }.getOrElse { app.icon }
        while (drawable is DrawableWrapper) {
            drawable = drawable.drawable ?: break
        }
        val am = context.getSystemService(ActivityManager::class.java)
        val launcherSize = (am?.launcherLargeIconSize ?: 192).coerceIn(144, 288)
        val adaptiveSize = (launcherSize * 1.5f).toInt().coerceIn(216, 432)
        if (drawable is AdaptiveIconDrawable) {
            val copy = drawable.constantState?.newDrawable()?.mutate() as? AdaptiveIconDrawable
                ?: drawable
            val bitmap = createBitmap(adaptiveSize, adaptiveSize)
            val canvas = Canvas(bitmap)
            copy.background?.let { bg ->
                bg.setBounds(0, 0, adaptiveSize, adaptiveSize)
                bg.draw(canvas)
            }
            copy.foreground?.let { fg ->
                fg.setBounds(0, 0, adaptiveSize, adaptiveSize)
                fg.draw(canvas)
            }
            return Icon.createWithAdaptiveBitmap(bitmap)
        }
        val rawBitmap = drawable.toBitmap(launcherSize, launcherSize, Bitmap.Config.ARGB_8888)
        return adaptBitmapToShortcutIcon(rawBitmap, adaptiveSize)
    }

    private fun adaptBitmapToShortcutIcon(src: Bitmap, adaptiveSize: Int): Icon {
        val w = src.width
        val h = src.height
        val cornersTransparent = listOf(
            src.getPixel(0, 0),
            src.getPixel(w - 1, 0),
            src.getPixel(0, h - 1),
            src.getPixel(w - 1, h - 1),
        ).any { Color.alpha(it) < 32 }
        if (!cornersTransparent) {
            return Icon.createWithAdaptiveBitmap(src.scale(adaptiveSize, adaptiveSize))
        }

        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (Color.alpha(src.getPixel(x, y)) > 24) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < minX || maxY < minY) {
            return Icon.createWithBitmap(src)
        }

        val cx = (minX + maxX) / 2f
        val cy = (minY + maxY) / 2f
        val rx = (maxX - minX) / 2f
        val ry = (maxY - minY) / 2f
        val rimColors = ArrayList<Int>(32)
        for (i in 0 until 32) {
            val angle = (i * Math.PI * 2.0) / 32.0
            val cos = kotlin.math.cos(angle)
            val sin = kotlin.math.sin(angle)
            for (step in 95 downTo 70 step 5) {
                val frac = step / 100f
                val sx = (cx + rx * frac * cos).toInt().coerceIn(0, w - 1)
                val sy = (cy + ry * frac * sin).toInt().coerceIn(0, h - 1)
                val px = src.getPixel(sx, sy)
                if (Color.alpha(px) > 200) {
                    rimColors.add(px)
                    break
                }
            }
        }
        val bleedColor = if (rimColors.isNotEmpty()) {
            val r = rimColors.map { Color.red(it) }.sorted()[rimColors.size / 2]
            val g = rimColors.map { Color.green(it) }.sorted()[rimColors.size / 2]
            val b = rimColors.map { Color.blue(it) }.sorted()[rimColors.size / 2]
            Color.rgb(r, g, b)
        } else {
            Color.WHITE
        }

        val out = createBitmap(adaptiveSize, adaptiveSize)
        val canvas = Canvas(out)
        canvas.drawColor(bleedColor)
        val targetBox = (adaptiveSize * (2f / 3f)).toInt() + 2
        val cropW = maxX - minX + 1
        val cropH = maxY - minY + 1
        val scale = targetBox.toFloat() / maxOf(cropW, cropH)
        val dstW = (cropW * scale).toInt().coerceAtLeast(1)
        val dstH = (cropH * scale).toInt().coerceAtLeast(1)
        val left = (adaptiveSize - dstW) / 2
        val top = (adaptiveSize - dstH) / 2
        canvas.drawBitmap(
            src,
            Rect(minX, minY, maxX + 1, maxY + 1),
            Rect(left, top, left + dstW, top + dstH),
            null,
        )
        return Icon.createWithAdaptiveBitmap(out)
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

        private fun getOrCreateShortcutToken(ctx: Context): String {
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
