package io.github.yukino1111.colorosbgmanager.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.AddToHomeScreen
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RssFeed
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import io.github.yukino1111.colorosbgmanager.BuildConfig
import io.github.yukino1111.colorosbgmanager.data.BgMasterUiState
import io.github.yukino1111.colorosbgmanager.data.InstalledAppEntry
import io.github.yukino1111.colorosbgmanager.model.AppUserKey
import io.github.yukino1111.colorosbgmanager.model.BgSubsystem
import io.github.yukino1111.colorosbgmanager.model.GlobalRuleFlags
import io.github.yukino1111.colorosbgmanager.model.NativeCpuTier
import io.github.yukino1111.colorosbgmanager.model.NativeListKind
import io.github.yukino1111.colorosbgmanager.model.NativeSource
import io.github.yukino1111.colorosbgmanager.model.NativeState
import io.github.yukino1111.colorosbgmanager.model.PkgRuntimeStatus
import io.github.yukino1111.colorosbgmanager.model.PolicyMode
import io.github.yukino1111.colorosbgmanager.model.RulesCodec
import io.github.yukino1111.colorosbgmanager.model.RuntimeEvent
import io.github.yukino1111.colorosbgmanager.model.SystemSnapshot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class AppCategory(val label: String) {
    USER("用户"),
    SYSTEM("系统"),
    ALL("全部"),
}

enum class PolicyScopeFilter(val label: String) {
    ALL("全部"),
    OVERRIDDEN("仅模块调整"),
    NATIVE_HIT("仅系统名单"),
    MANAGED("调整与名单"),
}

private sealed interface ScreenRoute {
    data object Home : ScreenRoute
    data class AppDetail(val target: AppUserKey) : ScreenRoute
    data class AppProcessDetail(val target: AppUserKey) : ScreenRoute
    data object NativeLists : ScreenRoute
    data class NativeListDetail(val kind: NativeListKind) : ScreenRoute
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BgMasterScreen(
    state: BgMasterUiState,
    onRefreshAll: () -> Unit,
    onToggleLanguage: () -> Unit,
    onSetRule: (AppUserKey, BgSubsystem, PolicyMode) -> Unit,
    onSetAllForPackage: (AppUserKey, PolicyMode) -> Unit,
    onUpdateGlobals: ((GlobalRuleFlags) -> GlobalRuleFlags) -> Unit,
    onLoadPackageRuntime: (String, Int) -> Unit,
    onLoadProcesses: (Boolean) -> Unit,
    onLoadRootProcesses: (Boolean) -> Unit,
    onSetAutoFreeze: (AppUserKey, Boolean) -> Unit,
    onSetAutoUnfreeze: (AppUserKey, Boolean) -> Unit,
    onSetPackageSuspended: (AppUserKey, Boolean) -> Unit,
    onLaunchApp: (AppUserKey) -> Unit,
    onPinShortcut: (InstalledAppEntry) -> Unit,
    onDismissMessage: () -> Unit,
) {
    val strings = stringsFor(state.language)
    CompositionLocalProvider(LocalAppStrings provides strings) {
        BgMasterScreenContent(
            state = state,
            onRefreshAll = onRefreshAll,
            onToggleLanguage = onToggleLanguage,
            onSetRule = onSetRule,
            onSetAllForPackage = onSetAllForPackage,
            onUpdateGlobals = onUpdateGlobals,
            onLoadPackageRuntime = onLoadPackageRuntime,
            onLoadProcesses = onLoadProcesses,
            onLoadRootProcesses = onLoadRootProcesses,
            onSetAutoFreeze = onSetAutoFreeze,
            onSetAutoUnfreeze = onSetAutoUnfreeze,
            onSetPackageSuspended = onSetPackageSuspended,
            onLaunchApp = onLaunchApp,
            onPinShortcut = onPinShortcut,
            onDismissMessage = onDismissMessage,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BgMasterScreenContent(
    state: BgMasterUiState,
    onRefreshAll: () -> Unit,
    onToggleLanguage: () -> Unit,
    onSetRule: (AppUserKey, BgSubsystem, PolicyMode) -> Unit,
    onSetAllForPackage: (AppUserKey, PolicyMode) -> Unit,
    onUpdateGlobals: ((GlobalRuleFlags) -> GlobalRuleFlags) -> Unit,
    onLoadPackageRuntime: (String, Int) -> Unit,
    onLoadProcesses: (Boolean) -> Unit,
    onLoadRootProcesses: (Boolean) -> Unit,
    onSetAutoFreeze: (AppUserKey, Boolean) -> Unit,
    onSetAutoUnfreeze: (AppUserKey, Boolean) -> Unit,
    onSetPackageSuspended: (AppUserKey, Boolean) -> Unit,
    onLaunchApp: (AppUserKey) -> Unit,
    onPinShortcut: (InstalledAppEntry) -> Unit,
    onDismissMessage: () -> Unit,
) {
    val strings = LocalAppStrings.current
    var backStack by remember { mutableStateOf<List<ScreenRoute>>(listOf(ScreenRoute.Home)) }
    val route = backStack.last()
    var selectedTab by remember { mutableIntStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }
    var appCategory by remember { mutableStateOf(AppCategory.USER) }
    var policyScopeFilter by remember { mutableStateOf(PolicyScopeFilter.ALL) }
    var selectedSubsystems by remember { mutableStateOf(BgSubsystem.entries.toSet()) }
    var autoFreezeOnly by remember { mutableStateOf(false) }

    var procSearchQuery by remember { mutableStateOf("") }
    var procCategory by remember { mutableStateOf(ProcessCategoryFilter.USER) }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val overviewListState = rememberLazyListState()
    val appsListState = rememberLazyListState()
    val processesListState = rememberLazyListState()
    val nativeListsIndexState = rememberLazyListState()

    val snackbarHostState = remember { SnackbarHostState() }
    val haptics = LocalHapticFeedback.current
    var showAboutDialog by remember { mutableStateOf(false) }

    LaunchedEffect(state.statusMessage) {
        val msg = state.statusMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        onDismissMessage()
    }

    val popBack: () -> Unit = {
        if (backStack.size > 1) {
            backStack = backStack.dropLast(1)
        }
    }
    val pushRoute: (ScreenRoute) -> Unit = { next ->
        backStack = backStack + next
    }

    BackHandler(enabled = backStack.size > 1) {
        popBack()
    }

    val motionScheme = MaterialTheme.motionScheme
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        AnimatedContent(
            targetState = route,
            transitionSpec = {
                val goingBack = (initialState is ScreenRoute.NativeListDetail && targetState is ScreenRoute.NativeLists) ||
                    (initialState is ScreenRoute.AppDetail && targetState is ScreenRoute.AppProcessDetail) ||
                    (initialState !is ScreenRoute.Home && targetState is ScreenRoute.Home)
                if (goingBack) {
                    (slideInHorizontally(motionScheme.defaultSpatialSpec()) { -it / 6 } +
                        fadeIn(motionScheme.defaultEffectsSpec())) togetherWith
                        (slideOutHorizontally(motionScheme.defaultSpatialSpec()) { it / 6 } +
                            fadeOut(motionScheme.fastEffectsSpec()))
                } else {
                    (slideInHorizontally(motionScheme.defaultSpatialSpec()) { it / 6 } +
                        fadeIn(motionScheme.defaultEffectsSpec())) togetherWith
                        (slideOutHorizontally(motionScheme.defaultSpatialSpec()) { -it / 10 } +
                            fadeOut(motionScheme.fastEffectsSpec()))
                }
            },
            label = "route_transition",
        ) { currentRoute ->
            when (currentRoute) {
                ScreenRoute.Home -> {
                    Scaffold(
                        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
                        containerColor = MaterialTheme.colorScheme.surface,
                        snackbarHost = { SnackbarHost(snackbarHostState) },
                        topBar = {
                            TopAppBar(
                                colors = TopAppBarDefaults.topAppBarColors(
                                    containerColor = MaterialTheme.colorScheme.surface,
                                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                ),
                                title = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(
                                            modifier = Modifier
                                                .weight(1f, fill = false)
                                                .combinedClickable(
                                                    interactionSource = remember { MutableInteractionSource() },
                                                    indication = null,
                                                    onClick = {},
                                                    onLongClick = {
                                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                        showAboutDialog = true
                                                    },
                                                ),
                                        ) {
                                            Text(
                                                strings.appTitle,
                                                style = MaterialTheme.typography.titleLargeEmphasized,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            val subtitle = when {
                                                !state.xposedConnected -> strings.lposedDisconnected
                                                !state.snapshot.serverInjected -> strings.inactiveRebootHint
                                                else -> strings.activatedSummary(state.rules.size)
                                            }
                                            Text(
                                                subtitle,
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        Spacer(Modifier.width(10.dp))
                                        FilledTonalButton(
                                            onClick = onToggleLanguage,
                                            shapes = ButtonDefaults.shapes(),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                            modifier = Modifier.height(32.dp),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Translate,
                                                contentDescription = null,
                                                modifier = Modifier.size(15.dp),
                                            )
                                            Spacer(Modifier.width(4.dp))
                                            Text(
                                                text = strings.langSwitchLabel,
                                                style = MaterialTheme.typography.labelMediumEmphasized,
                                            )
                                        }
                                    }
                                },
                                actions = {
                                    IconButton(
                                        onClick = onRefreshAll,
                                        enabled = !state.loadingSnapshot && !state.loadingApps,
                                    ) {
                                        if (state.loadingSnapshot) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(20.dp),
                                                strokeWidth = 2.2.dp,
                                            )
                                        } else {
                                            Icon(Icons.Rounded.Refresh, contentDescription = strings.refreshCd)
                                        }
                                    }
                                },
                                scrollBehavior = scrollBehavior,
                            )
                        },
                        bottomBar = {
                            NavigationBar {
                                NavigationBarItem(
                                    selected = selectedTab == 0,
                                    onClick = { selectedTab = 0 },
                                    icon = { Icon(Icons.Rounded.Dashboard, null) },
                                    label = { Text(strings.tabOverview) },
                                )
                                NavigationBarItem(
                                    selected = selectedTab == 1,
                                    onClick = { selectedTab = 1 },
                                    icon = { Icon(Icons.Rounded.Apps, null) },
                                    label = { Text(strings.tabApps) },
                                )
                                NavigationBarItem(
                                    selected = selectedTab == 2,
                                    onClick = { selectedTab = 2 },
                                    icon = { Icon(Icons.Rounded.Memory, null) },
                                    label = { Text(strings.tabProcesses) },
                                )
                            }
                        },
                    ) { padding ->
                        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                            AnimatedContent(
                                targetState = selectedTab,
                                transitionSpec = {
                                    fadeIn(motionScheme.defaultEffectsSpec()) togetherWith
                                        fadeOut(motionScheme.fastEffectsSpec())
                                },
                                label = "home_tab_transition",
                            ) { tabIdx ->
                                when (tabIdx) {
                                    0 -> DashboardTab(
                                        state = state,
                                        listState = overviewListState,
                                        onJumpToSubsystem = { sub ->
                                            selectedSubsystems = setOf(sub)
                                            autoFreezeOnly = false
                                            selectedTab = 1
                                        },
                                        onOpenNativeLists = { pushRoute(ScreenRoute.NativeLists) },
                                        onUpdateGlobals = onUpdateGlobals,
                                        onRefreshAll = onRefreshAll,
                                    )
                                    1 -> AppsTab(
                                        state = state,
                                        listState = appsListState,
                                        searchQuery = searchQuery,
                                        onSearchChange = { searchQuery = it },
                                        appCategory = appCategory,
                                        onCategoryChange = { appCategory = it },
                                        policyScopeFilter = policyScopeFilter,
                                        onScopeFilterChange = { policyScopeFilter = it },
                                        selectedSubsystems = selectedSubsystems,
                                        onSubsystemsChange = { selectedSubsystems = it },
                                        autoFreezeOnly = autoFreezeOnly,
                                        onAutoFreezeOnlyChange = { autoFreezeOnly = it },
                                        onSetRule = onSetRule,
                                        onOpenDetail = { target -> pushRoute(ScreenRoute.AppDetail(target)) },
                                    )
                                    else -> ProcessesTab(
                                        state = state,
                                        listState = processesListState,
                                        isActiveOnScreen = route == ScreenRoute.Home,
                                        searchQuery = procSearchQuery,
                                        onSearchChange = { procSearchQuery = it },
                                        category = procCategory,
                                        onCategoryChange = { procCategory = it },
                                        onLoadProcesses = onLoadProcesses,
                                        onLoadRootProcesses = onLoadRootProcesses,
                                        onOpenProcessDetail = { target -> pushRoute(ScreenRoute.AppProcessDetail(target)) },
                                    )
                                }
                            }
                        }
                    }
                }

                is ScreenRoute.AppProcessDetail -> {
                    val appEntry = state.apps.firstOrNull { it.key == currentRoute.target }
                    AppProcessDetailPage(
                        target = currentRoute.target,
                        appEntry = appEntry,
                        state = state,
                        onBack = popBack,
                        onLoadProcesses = onLoadProcesses,
                        onOpenAppSettings = { pushRoute(ScreenRoute.AppDetail(currentRoute.target)) },
                    )
                }

                is ScreenRoute.AppDetail -> {
                    val appEntry = state.apps.firstOrNull { it.key == currentRoute.target }
                    AppDetailPage(
                        target = currentRoute.target,
                        appEntry = appEntry,
                        state = state,
                        snackbarHostState = snackbarHostState,
                        onBack = popBack,
                        onLoadRuntime = { onLoadPackageRuntime(currentRoute.target.packageName, currentRoute.target.userId) },
                        onSetRule = { sub, mode -> onSetRule(currentRoute.target, sub, mode) },
                        onSetAll = { mode -> onSetAllForPackage(currentRoute.target, mode) },
                        onSetAutoFreeze = { enabled -> onSetAutoFreeze(currentRoute.target, enabled) },
                        onSetAutoUnfreeze = { enabled -> onSetAutoUnfreeze(currentRoute.target, enabled) },
                        onSetPackageSuspended = { suspend -> onSetPackageSuspended(currentRoute.target, suspend) },
                        onLaunchApp = { onLaunchApp(currentRoute.target) },
                        onPinShortcut = { appEntry?.let(onPinShortcut) },
                    )
                }

                ScreenRoute.NativeLists -> {
                    NativeListsIndexPage(
                        state = state,
                        listState = nativeListsIndexState,
                        onBack = popBack,
                        onSelectKind = { kind -> pushRoute(ScreenRoute.NativeListDetail(kind)) },
                    )
                }

                is ScreenRoute.NativeListDetail -> {
                    NativeListDetailPage(
                        kind = currentRoute.kind,
                        state = state,
                        onBack = popBack,
                    )
                }
            }
        }
    }

    if (showAboutDialog) {
        BgManagerAboutDialog(onDismiss = { showAboutDialog = false })
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DashboardTab(
    state: BgMasterUiState,
    listState: LazyListState,
    onJumpToSubsystem: (BgSubsystem) -> Unit,
    onOpenNativeLists: () -> Unit,
    onUpdateGlobals: ((GlobalRuleFlags) -> GlobalRuleFlags) -> Unit,
    onRefreshAll: () -> Unit,
) {
    val strings = LocalAppStrings.current
    val totalNativeCount = remember(state.snapshot) {
        state.snapshot.lists.values.sumOf { it.size }
    }
    val activeHookCount = remember(state.snapshot) {
        BgSubsystem.entries.count { state.snapshot.hookStatus[it.key] == true }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(
                shape = MaterialTheme.shapes.large,
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (state.snapshot.serverInjected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    },
                ),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (state.snapshot.serverInjected) {
                            Icons.Rounded.CheckCircle
                        } else {
                            Icons.Rounded.WarningAmber
                        },
                        contentDescription = null,
                        modifier = Modifier.size(26.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (state.snapshot.serverInjected) strings.statusActive else strings.statusInactive,
                            style = MaterialTheme.typography.titleMediumEmphasized,
                        )
                        Text(
                            text = when {
                                !state.snapshot.serverInjected && !state.xposedConnected -> strings.enableInLsposedHint
                                !state.snapshot.serverInjected -> strings.systemNotLoadedHint
                                !state.xposedConnected -> strings.waitingXposedServiceHint
                                else -> strings.mountedSubsystems(activeHookCount, BgSubsystem.entries.size)
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (!state.snapshot.serverInjected) {
                        Button(
                            onClick = onRefreshAll,
                            shapes = ButtonDefaults.shapes(),
                        ) {
                            Text(strings.recheck)
                        }
                    }
                }
            }
        }

        item {
            Card(
                shape = MaterialTheme.shapes.large,
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.large)
                    .clickable(onClick = onOpenNativeLists),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        modifier = Modifier.size(44.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.AutoMirrored.Rounded.ListAlt, contentDescription = null)
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(strings.nativeListsTitle, style = MaterialTheme.typography.titleSmallEmphasized)
                        Text(
                            text = if (state.snapshot.serverInjected) {
                                strings.nativeListsDesc(NativeListKind.entries.size, totalNativeCount)
                            } else {
                                strings.nativeListsInactive
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(Icons.Rounded.ChevronRight, contentDescription = null)
                }
            }
        }

        item {
            Text(
                strings.sectionSubsystems,
                style = MaterialTheme.typography.titleMediumEmphasized,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        item(key = "subsystems_group") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                BgSubsystem.entries.forEach { subsystem ->
                    val hooked = state.snapshot.hookStatus[subsystem.key] ?: false
                    val allowCount = state.rules.values.count { it[subsystem] == PolicyMode.FORCE_ALLOW }
                    val restrictCount = state.rules.values.count { it[subsystem]?.isRestrict == true }
                    val (nativeWhiteCount, nativeBlackCount) = remember(state.snapshot, subsystem) {
                        nativeCountsForSubsystem(state.snapshot, subsystem)
                    }

                    Card(
                        shape = MaterialTheme.shapes.large,
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.large)
                            .clickable { onJumpToSubsystem(subsystem) },
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                modifier = Modifier.size(42.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(iconForSubsystem(subsystem), contentDescription = null)
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        strings.subsystemTitle(subsystem),
                                        style = MaterialTheme.typography.titleSmallEmphasized,
                                    )
                                    StatusPill(
                                        text = if (hooked) strings.hookOk else strings.hookMissing,
                                        active = hooked,
                                    )
                                }
                                Text(
                                    strings.subsystemDesc(subsystem),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        text = strings.systemCounts(nativeWhiteCount, nativeBlackCount),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (allowCount > 0 || restrictCount > 0) {
                                        Text(
                                            text = strings.moduleCounts(allowCount, restrictCount),
                                            style = MaterialTheme.typography.labelSmallEmphasized,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    } else {
                                        Text(
                                            text = strings.unmodified,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                            Icon(
                                Icons.Rounded.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        item {
            Text(
                strings.sectionGlobals,
                style = MaterialTheme.typography.titleMediumEmphasized,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        item {
            Card(
                shape = MaterialTheme.shapes.large,
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    GlobalToggleRow(
                        title = strings.globalGuardElfTitle,
                        subtitle = strings.globalGuardElfSub,
                        checked = state.globalFlags.disableGuardElfCpuKill,
                        onCheckedChange = { v -> onUpdateGlobals { it.copy(disableGuardElfCpuKill = v) } },
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    GlobalToggleRow(
                        title = strings.globalBroadcastTitle,
                        subtitle = strings.globalBroadcastSub,
                        checked = state.globalFlags.disableBroadcastQuotaGlobal,
                        onCheckedChange = { v -> onUpdateGlobals { it.copy(disableBroadcastQuotaGlobal = v) } },
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    GlobalToggleRow(
                        title = strings.globalStrictTitle,
                        subtitle = strings.globalStrictSub,
                        checked = state.globalFlags.disableStrictModeFreeze,
                        onCheckedChange = { v -> onUpdateGlobals { it.copy(disableStrictModeFreeze = v) } },
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(strings.autoFreezeDelayTitle, style = MaterialTheme.typography.titleSmallEmphasized)
                            Text(
                                strings.autoFreezeDelaySub(state.globalFlags.autoFreezeDelaySec),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        val delayOptions = RulesCodec.AUTO_FREEZE_DELAY_OPTIONS
                        ConnectedToggleRow(
                            items = delayOptions,
                            isSelected = { state.globalFlags.autoFreezeDelaySec == it },
                            label = { strings.autoFreezeDelayOptionLabel(it) },
                            onSelect = { sec -> onUpdateGlobals { it.copy(autoFreezeDelaySec = sec) } },
                        )
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    GlobalToggleRow(
                        title = strings.globalVerboseTitle,
                        subtitle = strings.globalVerboseSub,
                        checked = state.globalFlags.verboseLog,
                        onCheckedChange = { v -> onUpdateGlobals { it.copy(verboseLog = v) } },
                    )
                }
            }
        }

        if (state.snapshot.serverInjected) {
            item {
                Text(
                    strings.sectionRecentEvents,
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            item {
                RuntimeEventsCard(
                    events = state.recentEvents.take(12),
                    showPackageName = true,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun <T> PopupFilterButton(
    selectedLabel: String,
    icon: ImageVector,
    items: List<T>,
    itemLabel: (T) -> String,
    isSelected: (T) -> Boolean,
    onSelect: (T) -> Unit,
    showTrailingChevron: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilledTonalButton(
            onClick = { expanded = true },
            shapes = ButtonDefaults.shapes(),
            contentPadding = contentPadding,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(selectedLabel, maxLines = 1)
            if (showTrailingChevron) {
                Spacer(Modifier.width(2.dp))
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            items.forEach { item ->
                DropdownMenuItem(
                    text = { Text(itemLabel(item)) },
                    leadingIcon = {
                        if (isSelected(item)) {
                            Icon(Icons.Rounded.Check, contentDescription = null)
                        }
                    },
                    onClick = {
                        onSelect(item)
                        expanded = false
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AppsTab(
    state: BgMasterUiState,
    listState: LazyListState,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    appCategory: AppCategory,
    onCategoryChange: (AppCategory) -> Unit,
    policyScopeFilter: PolicyScopeFilter,
    onScopeFilterChange: (PolicyScopeFilter) -> Unit,
    selectedSubsystems: Set<BgSubsystem>,
    onSubsystemsChange: (Set<BgSubsystem>) -> Unit,
    autoFreezeOnly: Boolean,
    onAutoFreezeOnlyChange: (Boolean) -> Unit,
    onSetRule: (AppUserKey, BgSubsystem, PolicyMode) -> Unit,
    onOpenDetail: (AppUserKey) -> Unit,
) {
    val strings = LocalAppStrings.current
    val motionScheme = MaterialTheme.motionScheme
    var nativeSectionExpanded by remember { mutableStateOf(true) }
    val allSubsystemsSelected = selectedSubsystems.size == BgSubsystem.entries.size

    val groupedApps = remember(
        state.apps,
        state.rules,
        state.snapshot,
        state.autoFreezePkgs,
        searchQuery,
        appCategory,
        policyScopeFilter,
        selectedSubsystems,
        autoFreezeOnly,
    ) {
        val q = searchQuery.trim().lowercase()
        val overridden = ArrayList<InstalledAppEntry>()
        val nativeHits = ArrayList<InstalledAppEntry>()
        val neutral = ArrayList<InstalledAppEntry>()

        state.apps
            .asSequence()
            .filter { app ->
                val catOk = when (appCategory) {
                    AppCategory.USER -> !app.isSystem
                    AppCategory.SYSTEM -> app.isSystem
                    AppCategory.ALL -> true
                }
                val queryOk = q.isEmpty() ||
                    app.label.lowercase().contains(q) ||
                    app.packageName.lowercase().contains(q)
                val freezeOk = !autoFreezeOnly || app.key in state.autoFreezePkgs
                catOk && queryOk && freezeOk
            }
            .sortedWith(compareBy<InstalledAppEntry> { it.label.lowercase() }.thenBy { it.userId })
            .forEach { app ->
                val isOverridden = hasOverrideInDimensions(state.rules, app.key, selectedSubsystems) ||
                    ((allSubsystemsSelected || autoFreezeOnly) && app.key in state.autoFreezePkgs)
                val isNativeHit = isManagedBySystemInDimensions(state.snapshot, app.packageName, selectedSubsystems)
                val passesScope = when (policyScopeFilter) {
                    PolicyScopeFilter.ALL -> true
                    PolicyScopeFilter.OVERRIDDEN -> isOverridden
                    PolicyScopeFilter.NATIVE_HIT -> isNativeHit
                    PolicyScopeFilter.MANAGED -> isOverridden || isNativeHit
                }
                if (passesScope) {
                    when {
                        isOverridden -> overridden.add(app)
                        isNativeHit -> nativeHits.add(app)
                        else -> neutral.add(app)
                    }
                }
            }

        GroupedAppSections(
            overridden = overridden,
            nativeHits = nativeHits,
            neutral = neutral,
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .animateContentSize(animationSpec = motionScheme.defaultSpatialSpec()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Md3eSearchField(
                    value = searchQuery,
                    onValueChange = onSearchChange,
                    placeholder = strings.searchAppsPlaceholder,
                    modifier = Modifier.weight(1f),
                )

                PopupFilterButton(
                    selectedLabel = strings.policyScopeLabel(policyScopeFilter),
                    icon = Icons.Rounded.FilterList,
                    items = PolicyScopeFilter.entries,
                    itemLabel = { strings.policyScopeLabel(it) },
                    isSelected = { it == policyScopeFilter },
                    onSelect = onScopeFilterChange,
                    showTrailingChevron = false,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
                )
            }

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = allSubsystemsSelected,
                    onClick = {
                        onSubsystemsChange(
                            if (allSubsystemsSelected) emptySet() else BgSubsystem.entries.toSet(),
                        )
                    },
                    label = { Text(strings.allDimensions) },
                )
                FilterChip(
                    selected = autoFreezeOnly,
                    onClick = { onAutoFreezeOnlyChange(!autoFreezeOnly) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.AcUnit,
                            contentDescription = null,
                            modifier = Modifier.size(FilterChipDefaults.IconSize),
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        selectedLeadingIconColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    ),
                    label = { Text(strings.autoFreezeFilterTag(state.autoFreezePkgs.size)) },
                )
                BgSubsystem.entries.forEach { sub ->
                    val checked = sub in selectedSubsystems
                    FilterChip(
                        selected = checked,
                        onClick = {
                            onSubsystemsChange(
                                if (checked) selectedSubsystems - sub else selectedSubsystems + sub,
                            )
                        },
                        label = { Text(strings.subsystemShort(sub)) },
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PopupFilterButton(
                    selectedLabel = strings.appCategoryLabel(appCategory),
                    icon = Icons.Rounded.Apps,
                    items = AppCategory.entries,
                    itemLabel = { strings.appCategoryLabel(it) },
                    isSelected = { it == appCategory },
                    onSelect = onCategoryChange,
                )
            }
        }

        if (state.loadingApps) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                LoadingIndicator()
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            ) {
                if (groupedApps.overridden.isNotEmpty()) {
                    item(key = "hdr_overridden") {
                        SectionHeaderRow(
                            icon = Icons.Rounded.PushPin,
                            title = strings.sectionOverridden,
                            count = groupedApps.overridden.size,
                            accent = true,
                        )
                    }
                    itemsIndexed(groupedApps.overridden, key = { _, it -> "ov_${it.userId}|${it.packageName}" }) { idx, app ->
                        AppRowItem(
                            app = app,
                            pkgRules = state.rules[app.key].orEmpty(),
                            isAutoFreeze = app.key in state.autoFreezePkgs,
                            snapshot = state.snapshot,
                            selectedSubsystems = selectedSubsystems,
                            index = idx,
                            count = groupedApps.overridden.size,
                            modifier = Modifier.animateItem(
                                fadeInSpec = motionScheme.defaultEffectsSpec(),
                                placementSpec = motionScheme.defaultSpatialSpec(),
                                fadeOutSpec = motionScheme.fastEffectsSpec(),
                            ),
                            onSetRule = onSetRule,
                            onOpenDetail = onOpenDetail,
                        )
                    }
                }

                if (groupedApps.nativeHits.isNotEmpty()) {
                    item(key = "hdr_native") {
                        SectionHeaderRow(
                            icon = Icons.Rounded.Tune,
                            title = strings.sectionNativeLists,
                            count = groupedApps.nativeHits.size,
                            accent = false,
                            collapsible = true,
                            expanded = nativeSectionExpanded,
                            onToggleExpand = { nativeSectionExpanded = !nativeSectionExpanded },
                        )
                    }
                    if (nativeSectionExpanded) {
                        itemsIndexed(groupedApps.nativeHits, key = { _, it -> "nat_${it.userId}|${it.packageName}" }) { idx, app ->
                            AppRowItem(
                                app = app,
                                pkgRules = state.rules[app.key].orEmpty(),
                                isAutoFreeze = app.key in state.autoFreezePkgs,
                                snapshot = state.snapshot,
                                selectedSubsystems = selectedSubsystems,
                                index = idx,
                                count = groupedApps.nativeHits.size,
                                modifier = Modifier.animateItem(
                                    fadeInSpec = motionScheme.defaultEffectsSpec(),
                                    placementSpec = motionScheme.defaultSpatialSpec(),
                                    fadeOutSpec = motionScheme.fastEffectsSpec(),
                                ),
                                onSetRule = onSetRule,
                                onOpenDetail = onOpenDetail,
                            )
                        }
                    }
                }

                if (groupedApps.neutral.isNotEmpty()) {
                    item(key = "hdr_neutral") {
                        SectionHeaderRow(
                            icon = Icons.Rounded.Apps,
                            title = strings.sectionOtherApps,
                            count = groupedApps.neutral.size,
                            accent = false,
                        )
                    }
                    itemsIndexed(groupedApps.neutral, key = { _, it -> "neu_${it.userId}|${it.packageName}" }) { idx, app ->
                        AppRowItem(
                            app = app,
                            pkgRules = state.rules[app.key].orEmpty(),
                            isAutoFreeze = app.key in state.autoFreezePkgs,
                            snapshot = state.snapshot,
                            selectedSubsystems = selectedSubsystems,
                            index = idx,
                            count = groupedApps.neutral.size,
                            modifier = Modifier.animateItem(
                                fadeInSpec = motionScheme.defaultEffectsSpec(),
                                placementSpec = motionScheme.defaultSpatialSpec(),
                                fadeOutSpec = motionScheme.fastEffectsSpec(),
                            ),
                            onSetRule = onSetRule,
                            onOpenDetail = onOpenDetail,
                        )
                    }
                }

                if (groupedApps.isEmpty) {
                    item(key = "empty_state") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 48.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                strings.emptyFilterResult,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class GroupedAppSections(
    val overridden: List<InstalledAppEntry>,
    val nativeHits: List<InstalledAppEntry>,
    val neutral: List<InstalledAppEntry>,
) {
    val isEmpty: Boolean get() = overridden.isEmpty() && nativeHits.isEmpty() && neutral.isEmpty()
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SectionHeaderRow(
    icon: ImageVector,
    title: String,
    count: Int,
    accent: Boolean,
    collapsible: Boolean = false,
    expanded: Boolean = true,
    onToggleExpand: (() -> Unit)? = null,
) {
    val strings = LocalAppStrings.current
    val tint = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (collapsible && onToggleExpand != null) Modifier.clickable(onClick = onToggleExpand) else Modifier)
            .padding(top = 10.dp, bottom = 6.dp, start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            text = "$title ($count)",
            style = MaterialTheme.typography.labelLargeEmphasized,
            color = tint,
            modifier = Modifier.weight(1f),
        )
        if (collapsible) {
            Icon(
                imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = if (expanded) strings.collapse else strings.expand,
                tint = tint,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun AppRowItem(
    app: InstalledAppEntry,
    pkgRules: Map<BgSubsystem, PolicyMode>,
    isAutoFreeze: Boolean,
    snapshot: SystemSnapshot,
    selectedSubsystems: Set<BgSubsystem>,
    index: Int,
    count: Int,
    modifier: Modifier = Modifier,
    onSetRule: (AppUserKey, BgSubsystem, PolicyMode) -> Unit,
    onOpenDetail: (AppUserKey) -> Unit,
) {
    if (selectedSubsystems.size == 1) {
        val singleSub = selectedSubsystems.first()
        SingleDimensionAppCard(
            app = app,
            subsystem = singleSub,
            nativeResolution = snapshot.nativeResolutionFor(app.packageName, singleSub),
            currentMode = pkgRules[singleSub] ?: PolicyMode.FOLLOW_SYSTEM,
            index = index,
            count = count,
            modifier = modifier,
            onModeSelected = { mode -> onSetRule(app.key, singleSub, mode) },
            onOpenDetail = { onOpenDetail(app.key) },
        )
    } else {
        AllDimensionsAppCard(
            app = app,
            pkgRules = pkgRules,
            isAutoFreeze = isAutoFreeze,
            snapshot = snapshot,
            selectedSubsystems = selectedSubsystems,
            index = index,
            count = count,
            modifier = modifier,
            onClick = { onOpenDetail(app.key) },
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SingleDimensionAppCard(
    app: InstalledAppEntry,
    subsystem: BgSubsystem,
    nativeResolution: io.github.yukino1111.colorosbgmanager.model.NativeResolution,
    currentMode: PolicyMode,
    index: Int,
    count: Int,
    modifier: Modifier = Modifier,
    onModeSelected: (PolicyMode) -> Unit,
    onOpenDetail: () -> Unit,
) {
    val strings = LocalAppStrings.current
    var menuExpanded by remember { mutableStateOf(false) }

    SegmentedListItem(
        onClick = onOpenDetail,
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        colors = ListItemDefaults.segmentedColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        modifier = modifier.animateContentSize(animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec()),
        leadingContent = {
            DrawableIcon(app.icon, modifier = Modifier.size(40.dp))
        },
        trailingContent = {
            Box {
                ModeSelectorPill(
                    mode = currentMode,
                    subsystem = subsystem,
                    onClick = { menuExpanded = true },
                )
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    subsystem.supportedModes().forEach { mode ->
                        DropdownMenuItem(
                            text = { Text(strings.policyModeLabel(mode, subsystem)) },
                            leadingIcon = {
                                if (currentMode == mode) {
                                    Icon(Icons.Rounded.Check, contentDescription = null)
                                }
                            },
                            onClick = {
                                onModeSelected(mode)
                                menuExpanded = false
                            },
                        )
                    }
                }
            }
        },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (nativeResolution.state != NativeState.NEUTRAL) {
                    NativeOutlinedBadge(
                        text = strings.formatNativeTag(nativeResolution),
                        isErrorBadge = nativeResolution.state == NativeState.BLACKLISTED &&
                            nativeResolution.cpuTier != NativeCpuTier.HBG,
                    )
                }
            }
        },
        content = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    app.label,
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (app.userId != 0) {
                    StatusPill(text = strings.userBadge(app.userId), active = true)
                }
                if (app.isSuspended) {
                    StatusPill(text = strings.freezeStatusSuspended, active = false)
                }
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AllDimensionsAppCard(
    app: InstalledAppEntry,
    pkgRules: Map<BgSubsystem, PolicyMode>,
    isAutoFreeze: Boolean,
    snapshot: SystemSnapshot,
    selectedSubsystems: Set<BgSubsystem>,
    index: Int,
    count: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val strings = LocalAppStrings.current
    val overrides = remember(pkgRules, selectedSubsystems) {
        BgSubsystem.entries.filter { it in selectedSubsystems }.mapNotNull { sub ->
            val mode = pkgRules[sub] ?: PolicyMode.FOLLOW_SYSTEM
            if (mode != PolicyMode.FOLLOW_SYSTEM) sub to mode else null
        }
    }
    val nativeNonNeutral = remember(app.packageName, pkgRules, snapshot, selectedSubsystems) {
        BgSubsystem.entries.filter { it in selectedSubsystems }.mapNotNull { sub ->
            val mode = pkgRules[sub] ?: PolicyMode.FOLLOW_SYSTEM
            val native = snapshot.nativeResolutionFor(app.packageName, sub)
            if (mode == PolicyMode.FOLLOW_SYSTEM && native.state != NativeState.NEUTRAL) sub to native else null
        }
    }

    SegmentedListItem(
        onClick = onClick,
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        colors = ListItemDefaults.segmentedColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        modifier = modifier.animateContentSize(animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec()),
        leadingContent = {
            DrawableIcon(app.icon, modifier = Modifier.size(40.dp))
        },
        trailingContent = if (app.isSystem) {
            { StatusPill(text = strings.systemTag, active = false) }
        } else {
            null
        },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (overrides.isEmpty() && nativeNonNeutral.isEmpty() && !isAutoFreeze) {
                    Text(
                        text = strings.defaultRulesText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (isAutoFreeze) {
                            StatusPill(text = strings.autoFreezeSwitchTitle, active = true)
                        }
                        overrides.forEach { (sub, mode) ->
                            val origNative = snapshot.nativeResolutionFor(app.packageName, sub)
                            val origSuffix = if (origNative.state != NativeState.NEUTRAL) {
                                " (${strings.formatNativeTag(origNative)})"
                            } else {
                                ""
                            }
                            OverrideFilledBadge(
                                text = "${strings.subsystemShort(sub)}: ${strings.overrideShortTag(mode, sub)}$origSuffix",
                                mode = mode,
                            )
                        }
                        nativeNonNeutral.forEach { (sub, native) ->
                            NativeOutlinedBadge(
                                text = "${strings.subsystemShort(sub)}: ${strings.formatNativeTag(native)}",
                                isErrorBadge = native.state == NativeState.BLACKLISTED &&
                                    native.cpuTier != NativeCpuTier.HBG,
                            )
                        }
                    }
                }
            }
        },
        content = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    app.label,
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (app.userId != 0) {
                    StatusPill(text = strings.userBadge(app.userId), active = true)
                }
                if (app.isSuspended) {
                    StatusPill(text = strings.freezeStatusSuspended, active = false)
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AppDetailPage(
    target: AppUserKey,
    appEntry: InstalledAppEntry?,
    state: BgMasterUiState,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onLoadRuntime: () -> Unit,
    onSetRule: (BgSubsystem, PolicyMode) -> Unit,
    onSetAll: (PolicyMode) -> Unit,
    onSetAutoFreeze: (Boolean) -> Unit,
    onSetAutoUnfreeze: (Boolean) -> Unit,
    onSetPackageSuspended: (Boolean) -> Unit,
    onLaunchApp: () -> Unit,
    onPinShortcut: () -> Unit,
) {
    val strings = LocalAppStrings.current
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val packageName = target.packageName
    val pkgRules = state.rules[target].orEmpty()
    val runtime = state.detailRuntime?.takeIf { it.packageName == packageName && it.userId == target.userId }
    val appEvents = if (runtime != null) {
        state.detailEvents
    } else {
        emptyList()
    }
    val isSuspended = runtime?.isSuspended ?: appEntry?.isSuspended ?: false
    val isAutoFreeze = target in state.autoFreezePkgs
    val isAutoUnfreeze = target !in state.autoUnfreezeDisabledPkgs

    LaunchedEffect(target, state.snapshot.serverInjected) {
        if (state.snapshot.serverInjected) {
            onLoadRuntime()
        }
    }

    LaunchedEffect(pkgRules[BgSubsystem.BATTERY_DOZE]) {
        if (runtime != null && state.snapshot.serverInjected) {
            kotlinx.coroutines.delay(200)
            onLoadRuntime()
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = strings.backCd)
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (appEntry != null) {
                            DrawableIcon(appEntry.icon, modifier = Modifier.size(34.dp))
                            Spacer(Modifier.width(10.dp))
                        }
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    appEntry?.label ?: packageName,
                                    style = MaterialTheme.typography.titleLargeEmphasized,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                if (target.userId != 0) {
                                    StatusPill(text = strings.userBadge(target.userId), active = true)
                                }
                            }
                            Text(
                                packageName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                actions = {
                    if (state.snapshot.serverInjected) {
                        IconButton(onClick = onLoadRuntime, enabled = !state.loadingDetailRuntime) {
                            if (state.loadingDetailRuntime) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(Icons.Rounded.Refresh, contentDescription = strings.refreshCd)
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.snapshot.serverInjected) {
                item {
                    RuntimeStatusCard(runtime = runtime)
                }
            }

            item {
                Card(
                    shape = MaterialTheme.shapes.large,
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = { onSetAll(PolicyMode.FORCE_ALLOW) },
                                modifier = Modifier.weight(1f),
                                shapes = ButtonDefaults.shapes(),
                            ) {
                                Text(strings.allowAll)
                            }
                            FilledTonalButton(
                                onClick = { onSetAll(PolicyMode.FOLLOW_SYSTEM) },
                                modifier = Modifier.weight(1f),
                                shapes = ButtonDefaults.shapes(),
                            ) {
                                Text(strings.restoreDefault)
                            }
                        }

                        BgSubsystem.entries.forEach { sub ->
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            )
                            val nativeRes = state.snapshot.nativeResolutionFor(packageName, sub)
                            val currentMode = pkgRules[sub] ?: PolicyMode.FOLLOW_SYSTEM

                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            strings.subsystemTitle(sub),
                                            style = MaterialTheme.typography.titleSmallEmphasized,
                                        )
                                        val effectiveText = when (currentMode) {
                                            PolicyMode.FOLLOW_SYSTEM -> when (nativeRes.state) {
                                                NativeState.WHITELISTED,
                                                NativeState.BLACKLISTED -> "${strings.currentPrefix}${strings.formatNativeTag(nativeRes)}"
                                                NativeState.NEUTRAL -> strings.currentNeutral
                                            }
                                            else -> strings.effectiveOverrideText(currentMode, sub)
                                        }
                                        Text(
                                            effectiveText,
                                            style = if (currentMode != PolicyMode.FOLLOW_SYSTEM) {
                                                MaterialTheme.typography.labelSmallEmphasized
                                            } else {
                                                MaterialTheme.typography.labelSmall
                                            },
                                            color = if (currentMode != PolicyMode.FOLLOW_SYSTEM) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                        )
                                    }
                                    if (nativeRes.state == NativeState.NEUTRAL) {
                                        StatusPill(text = strings.notInNativeList, active = false)
                                    } else {
                                        NativeOutlinedBadge(
                                            text = strings.formatNativeTag(nativeRes),
                                            isErrorBadge = nativeRes.state == NativeState.BLACKLISTED &&
                                                nativeRes.cpuTier != NativeCpuTier.HBG,
                                        )
                                    }
                                }
                                Text(
                                    strings.subsystemDesc(sub),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                val modes = sub.supportedModes()
                                ConnectedToggleRow(
                                    items = modes,
                                    isSelected = { currentMode == it },
                                    label = { strings.policyModeLabel(it, sub, compact = modes.size > 3) },
                                    onSelect = { onSetRule(sub, it) },
                                )
                            }
                        }
                    }
                }
            }

            item {
                FreezeControlCard(
                    serverInjected = state.snapshot.serverInjected,
                    isSuspended = isSuspended,
                    isAutoFreeze = isAutoFreeze,
                    isAutoUnfreeze = isAutoUnfreeze,
                    canPinShortcut = appEntry != null,
                    onSetAutoFreeze = onSetAutoFreeze,
                    onSetAutoUnfreeze = onSetAutoUnfreeze,
                    onSetSuspended = onSetPackageSuspended,
                    onLaunchApp = onLaunchApp,
                    onPinShortcut = onPinShortcut,
                )
            }

            if (state.snapshot.serverInjected) {
                item {
                    RuntimeEventsCard(
                        events = appEvents.take(25),
                        showPackageName = false,
                        title = strings.sectionAppEvents,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FreezeControlCard(
    serverInjected: Boolean,
    isSuspended: Boolean,
    isAutoFreeze: Boolean,
    isAutoUnfreeze: Boolean,
    canPinShortcut: Boolean,
    onSetAutoFreeze: (Boolean) -> Unit,
    onSetAutoUnfreeze: (Boolean) -> Unit,
    onSetSuspended: (Boolean) -> Unit,
    onLaunchApp: () -> Unit,
    onPinShortcut: () -> Unit,
) {
    val strings = LocalAppStrings.current
    val motionScheme = MaterialTheme.motionScheme
    val subContainerColor by animateColorAsState(
        targetValue = if (isAutoUnfreeze) {
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        animationSpec = motionScheme.defaultEffectsSpec(),
        label = "autoUnfreezeContainer",
    )
    val subIconBgColor by animateColorAsState(
        targetValue = if (isAutoUnfreeze) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        animationSpec = motionScheme.defaultEffectsSpec(),
        label = "autoUnfreezeIconBg",
    )
    val subIconTint by animateColorAsState(
        targetValue = if (isAutoUnfreeze) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = motionScheme.defaultEffectsSpec(),
        label = "autoUnfreezeIconTint",
    )

    Card(
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.animateContentSize(animationSpec = motionScheme.defaultSpatialSpec()),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    strings.sectionFreezeControl,
                    style = MaterialTheme.typography.titleSmallEmphasized,
                )
                StatusPill(
                    text = if (isSuspended) strings.freezeStatusSuspended else strings.freezeStatusActive,
                    active = !isSuspended,
                )
            }

            Column(modifier = Modifier.fillMaxWidth()) {
                GlobalToggleRow(
                    title = strings.autoFreezeSwitchTitle,
                    subtitle = strings.autoFreezeSwitchSub,
                    checked = isAutoFreeze,
                    onCheckedChange = onSetAutoFreeze,
                )
                AnimatedVisibility(
                    visible = isAutoFreeze,
                    enter = expandVertically(
                        animationSpec = motionScheme.defaultSpatialSpec(),
                        expandFrom = Alignment.Top,
                    ) + fadeIn(animationSpec = motionScheme.defaultEffectsSpec()),
                    exit = shrinkVertically(
                        animationSpec = motionScheme.fastSpatialSpec(),
                        shrinkTowards = Alignment.Top,
                    ) + fadeOut(animationSpec = motionScheme.fastEffectsSpec()),
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = subContainerColor,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .clip(MaterialTheme.shapes.medium)
                            .clickable { onSetAutoUnfreeze(!isAutoUnfreeze) },
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = subIconBgColor,
                                modifier = Modifier.size(32.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Rounded.Bolt,
                                        contentDescription = null,
                                        tint = subIconTint,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    strings.autoUnfreezeSwitchTitle,
                                    style = MaterialTheme.typography.titleSmallEmphasized,
                                )
                                Text(
                                    strings.autoUnfreezeSwitchSub,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Switch(
                                checked = isAutoUnfreeze,
                                onCheckedChange = onSetAutoUnfreeze,
                                thumbContent = if (isAutoUnfreeze) {
                                    {
                                        Icon(
                                            imageVector = Icons.Rounded.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(SwitchDefaults.IconSize),
                                        )
                                    }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(
                    onClick = { onSetSuspended(!isSuspended) },
                    enabled = serverInjected,
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(if (isSuspended) strings.actionUnfreezeNow else strings.actionFreezeNow)
                }
                Button(
                    onClick = onLaunchApp,
                    enabled = serverInjected,
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(strings.actionLaunchInApp)
                }
                if (canPinShortcut) {
                    FilledTonalButton(
                        onClick = onPinShortcut,
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.AddToHomeScreen, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(strings.actionPinShortcut)
                    }
                }
            }
            if (!serverInjected) {
                Text(
                    text = strings.systemNotLoadedHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RuntimeStatusCard(
    runtime: PkgRuntimeStatus?,
) {
    val strings = LocalAppStrings.current
    Card(
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                strings.sectionRuntimeStatus,
                style = MaterialTheme.typography.titleSmallEmphasized,
            )
            if (runtime == null || !runtime.available) {
                Text(
                    strings.noRecentEvents,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    StatusPill(
                        text = "${strings.runtimeDozeWl}: ${if (runtime.dozeWhitelisted) strings.runtimeWhitelisted else strings.runtimeNotWhitelisted}",
                        active = runtime.dozeWhitelisted,
                    )
                    StatusPill(
                        text = "${strings.runtimeStandbyBucket}: ${strings.formatStandbyBucket(runtime.standbyBucket)}",
                        active = runtime.standbyBucket in 0..10,
                    )
                }
                Text(
                    text = "${strings.runtimeRunningProcs}: " + if (runtime.runningProcesses.isEmpty()) {
                        strings.runtimeNotRunning
                    } else {
                        runtime.runningProcesses.joinToString(", ")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RuntimeEventsCard(
    events: List<RuntimeEvent>,
    showPackageName: Boolean,
    title: String? = null,
) {
    val strings = LocalAppStrings.current
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    Card(
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (title != null) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmallEmphasized,
                )
            }
            if (events.isEmpty()) {
                Text(
                    strings.noRecentEvents,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                events.forEachIndexed { idx, ev ->
                    if (idx > 0) HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            val userPrefix = if (ev.userId != 0) "[u${ev.userId}] " else ""
                            val eventTitle = if (showPackageName) {
                                "$userPrefix${ev.pkgName} · ${strings.formatRuntimeAction(ev.action)}"
                            } else {
                                strings.formatRuntimeAction(ev.action)
                            }
                            Text(
                                text = eventTitle,
                                style = MaterialTheme.typography.bodySmallEmphasized,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val subText = BgSubsystem.fromKey(ev.subsystemKey)?.let { strings.subsystemShort(it) } ?: ev.subsystemKey
                            val detailSuffix = if (ev.detail.isNotBlank()) " · ${ev.detail}" else ""
                            Text(
                                text = "$subText$detailSuffix",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = timeFmt.format(Date(ev.timestampMs)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun NativeListsIndexPage(
    state: BgMasterUiState,
    listState: LazyListState,
    onBack: () -> Unit,
    onSelectKind: (NativeListKind) -> Unit,
) {
    val strings = LocalAppStrings.current
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val installedPkgSet = remember(state.apps) { state.apps.mapTo(HashSet()) { it.packageName } }
    val cloudLists = remember { NativeListKind.entries.filter { it.source == NativeSource.CLOUD_DEFAULT } }
    val userLists = remember { NativeListKind.entries.filter { it.source == NativeSource.SYSTEM_USER } }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = strings.backCd)
                    }
                },
                title = {
                    Text(
                        strings.nativeListsTitle,
                        style = MaterialTheme.typography.titleLargeEmphasized,
                    )
                },
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        ) {
            item(key = "hdr_cloud_lists") {
                SectionHeaderRow(
                    icon = Icons.Rounded.Shield,
                    title = strings.cloudDefaultListsHeader,
                    count = cloudLists.size,
                    accent = true,
                )
            }
            itemsIndexed(cloudLists, key = { _, it -> it.name }) { idx, kind ->
                NativeListIndexCard(
                    kind = kind,
                    state = state,
                    installedPkgSet = installedPkgSet,
                    index = idx,
                    count = cloudLists.size,
                    onClick = { onSelectKind(kind) },
                )
            }
            item(key = "hdr_user_lists") {
                SectionHeaderRow(
                    icon = Icons.Rounded.Tune,
                    title = strings.systemUserListsHeader,
                    count = userLists.size,
                    accent = false,
                )
            }
            itemsIndexed(userLists, key = { _, it -> it.name }) { idx, kind ->
                NativeListIndexCard(
                    kind = kind,
                    state = state,
                    installedPkgSet = installedPkgSet,
                    index = idx,
                    count = userLists.size,
                    onClick = { onSelectKind(kind) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun NativeListIndexCard(
    kind: NativeListKind,
    state: BgMasterUiState,
    installedPkgSet: Set<String>,
    index: Int,
    count: Int,
    onClick: () -> Unit,
) {
    val strings = LocalAppStrings.current
    val allPkgs = state.snapshot.listOfKind(kind)
    val installedCount = allPkgs.count { it in installedPkgSet }
    val active = state.snapshot.isListActive(kind)
    SegmentedListItem(
        onClick = onClick,
        enabled = state.snapshot.serverInjected,
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        colors = ListItemDefaults.segmentedColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.snapshot.serverInjected && !active) {
                    StatusPill(text = strings.cloudInactivePill, active = false)
                    Spacer(Modifier.width(6.dp))
                }
                StatusPill(text = strings.nativeSourceShort(kind.source), active = kind.source == NativeSource.SYSTEM_USER)
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Rounded.ChevronRight, contentDescription = null)
            }
        },
        supportingContent = {
            Text(
                when {
                    !state.snapshot.serverInjected -> strings.statusInactive
                    !active -> strings.installedSummary(installedCount, allPkgs.size) + strings.cloudSwitchOffSuffix
                    else -> strings.installedSummary(installedCount, allPkgs.size)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        content = {
            Text(
                strings.nativeListTitle(kind),
                style = MaterialTheme.typography.titleSmallEmphasized,
            )
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun NativeListDetailPage(
    kind: NativeListKind,
    state: BgMasterUiState,
    onBack: () -> Unit,
) {
    val strings = LocalAppStrings.current
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var search by remember { mutableStateOf("") }
    val appMap = remember(state.apps) {
        state.apps.sortedByDescending { it.userId }.associateBy { it.packageName }
    }
    val rawSet = state.snapshot.listOfKind(kind)

    val sortedEntries = remember(rawSet, search, appMap) {
        val q = search.trim().lowercase()
        rawSet
            .filter { pkg ->
                val label = appMap[pkg]?.label?.lowercase().orEmpty()
                q.isEmpty() || pkg.lowercase().contains(q) || label.contains(q)
            }
            .sortedWith(
                compareByDescending<String> { appMap.containsKey(it) }
                    .thenBy { appMap[it]?.label?.lowercase() ?: it },
            )
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = strings.backCd)
                    }
                },
                title = {
                    Column {
                        Text(
                            strings.nativeListTitle(kind),
                            style = MaterialTheme.typography.titleLargeEmphasized,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val subText = strings.nativeDetailSubtitle(strings.nativeSourceLabel(kind.source), rawSet.size) +
                            if (!state.snapshot.isListActive(kind)) strings.cloudSwitchOffSuffix else ""
                        Text(
                            subText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            Md3eSearchField(
                value = search,
                onValueChange = { search = it },
                placeholder = strings.filterPkgPlaceholder,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            ) {
                itemsIndexed(sortedEntries, key = { _, it -> it }) { idx, pkg ->
                    val app = appMap[pkg]
                    SegmentedListItem(
                        shapes = ListItemDefaults.segmentedShapes(index = idx, count = sortedEntries.size),
                        colors = ListItemDefaults.segmentedColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                        leadingContent = if (app != null) {
                            { DrawableIcon(app.icon, modifier = Modifier.size(36.dp)) }
                        } else {
                            null
                        },
                        trailingContent = {
                            StatusPill(
                                text = if (app != null) strings.installed else strings.notInstalled,
                                active = app != null,
                            )
                        },
                        supportingContent = {
                            Text(
                                text = pkg,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        content = {
                            Text(
                                text = app?.label ?: pkg,
                                style = MaterialTheme.typography.titleSmallEmphasized,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun GlobalToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmallEmphasized)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
internal fun Md3eSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        shape = CircleShape,
        singleLine = true,
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(Icons.Rounded.Close, contentDescription = null)
                }
            }
        },
        placeholder = { Text(placeholder) },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun <T> ConnectedToggleRow(
    items: List<T>,
    isSelected: (T) -> Boolean,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    val toggleColors = ToggleButtonDefaults.colors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        checkedContainerColor = MaterialTheme.colorScheme.primary,
        checkedContentColor = MaterialTheme.colorScheme.onPrimary,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        items.forEachIndexed { idx, item ->
            val shapes = when {
                items.size <= 1 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                idx == 0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                idx == items.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
            }
            ToggleButton(
                checked = isSelected(item),
                onCheckedChange = { onSelect(item) },
                modifier = Modifier.weight(1f),
                shapes = shapes,
                colors = toggleColors,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
            ) {
                Text(
                    text = label(item),
                    style = MaterialTheme.typography.labelLargeEmphasized,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BgManagerAboutDialog(onDismiss: () -> Unit) {
    val strings = LocalAppStrings.current
    val context = LocalContext.current
    val appIconBitmap = remember(context) {
        context.packageManager.getApplicationIcon(context.applicationInfo)
            .toBitmap(width = 144, height = 144)
            .asImageBitmap()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.aboutTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Image(
                        bitmap = appIconBitmap,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = strings.appTitle,
                            style = MaterialTheme.typography.titleLargeEmphasized,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(4.dp))
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            Text(
                                text = "v${BuildConfig.VERSION_NAME}",
                                style = MaterialTheme.typography.labelMediumEmphasized,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                            )
                        }
                    }
                }

                Card(
                    shape = MaterialTheme.shapes.large,
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.large)
                        .combinedClickable(
                            onClick = {
                                onDismiss()
                                openRepoUrl(context, strings.aboutRepoUrl, strings.aboutOpenFailed)
                            },
                            onLongClick = {
                                copyToClipboard(context, "ColorOSBgManager_Repo", strings.aboutRepoUrl)
                                Toast.makeText(context, strings.aboutLinkCopied, Toast.LENGTH_SHORT).show()
                            },
                        ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 12.dp),
                        ) {
                            Text(
                                text = strings.aboutRepoTitle,
                                style = MaterialTheme.typography.labelLargeEmphasized,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = strings.aboutRepoUrl,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {},
    )
}

private fun openRepoUrl(context: Context, url: String, fallbackMsg: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    val opened = runCatching { context.startActivity(intent) }.isSuccess
    if (!opened) {
        copyToClipboard(context, "ColorOSBgManager_Repo", url)
        Toast.makeText(context, fallbackMsg, Toast.LENGTH_SHORT).show()
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun StatusPill(text: String, active: Boolean) {
    Surface(
        shape = CircleShape,
        color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmallEmphasized,
            color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NativeOutlinedBadge(text: String, isErrorBadge: Boolean) {
    val borderColor = if (isErrorBadge) {
        MaterialTheme.colorScheme.error.copy(alpha = 0.55f)
    } else {
        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.55f)
    }
    val textColor = if (isErrorBadge) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.tertiary
    }
    Surface(
        shape = CircleShape,
        color = Color.Transparent,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
            maxLines = 1,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun OverrideFilledBadge(text: String, mode: PolicyMode) {
    val (bg, fg, icon) = when (mode) {
        PolicyMode.FORCE_ALLOW -> Triple(
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.onPrimary,
            Icons.Rounded.Check,
        )
        PolicyMode.FORCE_RESTRICT_HBG -> Triple(
            MaterialTheme.colorScheme.tertiary,
            MaterialTheme.colorScheme.onTertiary,
            Icons.Rounded.Speed,
        )
        else -> Triple(
            MaterialTheme.colorScheme.error,
            MaterialTheme.colorScheme.onError,
            Icons.Rounded.Close,
        )
    }
    Surface(shape = CircleShape, color = bg) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(12.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmallEmphasized,
                color = fg,
                maxLines = 1,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ModeSelectorPill(
    mode: PolicyMode,
    subsystem: BgSubsystem? = null,
    onClick: () -> Unit,
) {
    val strings = LocalAppStrings.current
    val modeText = strings.policyModeLabel(mode, subsystem)
    when (mode) {
        PolicyMode.FOLLOW_SYSTEM -> {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.clip(CircleShape).clickable(onClick = onClick),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        modeText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Icon(
                        Icons.Rounded.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        PolicyMode.FORCE_ALLOW -> {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clip(CircleShape).clickable(onClick = onClick),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(15.dp),
                    )
                    Text(
                        modeText,
                        style = MaterialTheme.typography.labelMediumEmphasized,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }
        PolicyMode.FORCE_RESTRICT_HBG -> {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.clip(CircleShape).clickable(onClick = onClick),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        Icons.Rounded.Speed,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiary,
                        modifier = Modifier.size(15.dp),
                    )
                    Text(
                        modeText,
                        style = MaterialTheme.typography.labelMediumEmphasized,
                        color = MaterialTheme.colorScheme.onTertiary,
                    )
                }
            }
        }
        PolicyMode.FORCE_RESTRICT -> {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.clip(CircleShape).clickable(onClick = onClick),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        Icons.Rounded.Block,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onError,
                        modifier = Modifier.size(15.dp),
                    )
                    Text(
                        modeText,
                        style = MaterialTheme.typography.labelMediumEmphasized,
                        color = MaterialTheme.colorScheme.onError,
                    )
                }
            }
        }
    }
}

@Composable
internal fun DrawableIcon(drawable: Drawable, modifier: Modifier = Modifier) {
    val bitmap = remember(drawable) { drawable.toBitmap(width = 96, height = 96).asImageBitmap() }
    Image(
        bitmap = bitmap,
        contentDescription = null,
        modifier = modifier.clip(MaterialTheme.shapes.medium),
    )
}

private fun hasOverrideInDimensions(
    rules: Map<AppUserKey, Map<BgSubsystem, PolicyMode>>,
    target: AppUserKey,
    subsystems: Set<BgSubsystem>,
): Boolean {
    if (subsystems.isEmpty()) return false
    val pkgMap = rules[target] ?: return false
    return subsystems.any { sub ->
        (pkgMap[sub] ?: PolicyMode.FOLLOW_SYSTEM) != PolicyMode.FOLLOW_SYSTEM
    }
}

private fun isManagedBySystemInDimensions(
    snapshot: SystemSnapshot,
    pkgName: String,
    subsystems: Set<BgSubsystem>,
): Boolean {
    if (subsystems.isEmpty()) return false
    return subsystems.any { snapshot.nativeStateFor(pkgName, it) != NativeState.NEUTRAL }
}

private fun iconForSubsystem(sub: BgSubsystem): ImageVector = when (sub) {
    BgSubsystem.HANS_FREEZE -> Icons.Rounded.Shield
    BgSubsystem.AUTO_START -> Icons.Rounded.PowerSettingsNew
    BgSubsystem.ASSOCIATE_START -> Icons.Rounded.Security
    BgSubsystem.BATTERY_DOZE -> Icons.Rounded.BatteryChargingFull
    BgSubsystem.ATHENA_RECENT -> Icons.Rounded.Memory
    BgSubsystem.CPU_LIMIT -> Icons.Rounded.Speed
    BgSubsystem.DOZE_NET -> Icons.Rounded.WifiOff
    BgSubsystem.BROADCAST_RESTRICT -> Icons.Rounded.RssFeed
}

private fun nativeCountsForSubsystem(snapshot: SystemSnapshot, sub: BgSubsystem): Pair<Int, Int> = when (sub) {
    BgSubsystem.HANS_FREEZE -> (
        (if (snapshot.isListActive(NativeListKind.HANS_SYSTEM_WHITE)) snapshot.listOfKind(NativeListKind.HANS_SYSTEM_WHITE) else emptySet()) +
            snapshot.listOfKind(NativeListKind.HANS_BPM_USER)
        ).size to if (snapshot.isListActive(NativeListKind.HANS_SYS_BLACK)) {
        snapshot.listOfKind(NativeListKind.HANS_SYS_BLACK).size
    } else {
        0
    }
    BgSubsystem.AUTO_START -> (
        snapshot.listOfKind(NativeListKind.AUTOSTART_SYSTEM_WHITE) +
            snapshot.listOfKind(NativeListKind.AUTOSTART_USER_WHITE)
        ).size to 0
    BgSubsystem.ASSOCIATE_START -> (
        snapshot.listOfKind(NativeListKind.ASSOCIATE_SYSTEM_WHITE) +
            snapshot.listOfKind(NativeListKind.ASSOCIATE_USER_WHITE)
        ).size to 0
    BgSubsystem.BATTERY_DOZE -> (
        snapshot.listOfKind(NativeListKind.BATTERY_NOT_RESTRICT) +
            snapshot.listOfKind(NativeListKind.DOZE_WHITE)
        ).size to if (snapshot.isListActive(NativeListKind.GUARDELF_LIMIT_BLACK)) {
        snapshot.listOfKind(NativeListKind.GUARDELF_LIMIT_BLACK).size
    } else {
        0
    }
    BgSubsystem.ATHENA_RECENT -> (
        snapshot.listOfKind(NativeListKind.RECENT_LOCKED) +
            if (snapshot.isListActive(NativeListKind.ATHENA_TRIM_WHITE)) {
                snapshot.listOfKind(NativeListKind.ATHENA_TRIM_WHITE)
            } else {
                emptySet()
            }
        ).size to snapshot.listOfKind(NativeListKind.RECENT_FORBID).size
    BgSubsystem.CPU_LIMIT -> (
        if (snapshot.isListActive(NativeListKind.CPULIMIT_WHITE)) {
            snapshot.listOfKind(NativeListKind.CPULIMIT_WHITE).size
        } else {
            0
        }
        ) to (
        snapshot.listOfKind(NativeListKind.CPULIMIT_BLACK) +
            if (snapshot.isListActive(NativeListKind.CPULIMIT_VIP_HBG)) {
                snapshot.listOfKind(NativeListKind.CPULIMIT_VIP_HBG)
            } else {
                emptySet()
            }
        ).size
    BgSubsystem.DOZE_NET -> snapshot.listOfKind(NativeListKind.DOZE_NET_WHITE).size to (
        snapshot.listOfKind(NativeListKind.NET_DEEPSLEEP_RESTRICT) +
            if (snapshot.isListActive(NativeListKind.DOZE_NET_CLOUD_RESTRICT)) {
                snapshot.listOfKind(NativeListKind.DOZE_NET_CLOUD_RESTRICT)
            } else {
                emptySet()
            }
        ).size
    BgSubsystem.BROADCAST_RESTRICT -> snapshot.listOfKind(NativeListKind.BROADCAST_WHITE).size to 0
}
