package io.github.yukino1111.colorosbgmanager.ui

import android.graphics.drawable.Drawable
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RssFeed
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import io.github.yukino1111.colorosbgmanager.data.BgMasterUiState
import io.github.yukino1111.colorosbgmanager.data.InstalledAppEntry
import io.github.yukino1111.colorosbgmanager.model.BgSubsystem
import io.github.yukino1111.colorosbgmanager.model.GlobalRuleFlags
import io.github.yukino1111.colorosbgmanager.model.NativeCpuTier
import io.github.yukino1111.colorosbgmanager.model.NativeListKind
import io.github.yukino1111.colorosbgmanager.model.NativeSource
import io.github.yukino1111.colorosbgmanager.model.NativeState
import io.github.yukino1111.colorosbgmanager.model.PolicyMode
import io.github.yukino1111.colorosbgmanager.model.SystemSnapshot

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
    data class AppDetail(val packageName: String) : ScreenRoute
    data object NativeLists : ScreenRoute
    data class NativeListDetail(val kind: NativeListKind) : ScreenRoute
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BgMasterScreen(
    state: BgMasterUiState,
    onRefreshAll: () -> Unit,
    onToggleLanguage: () -> Unit,
    onSetRule: (String, BgSubsystem, PolicyMode) -> Unit,
    onSetAllForPackage: (String, PolicyMode) -> Unit,
    onUpdateGlobals: ((GlobalRuleFlags) -> GlobalRuleFlags) -> Unit,
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
    onSetRule: (String, BgSubsystem, PolicyMode) -> Unit,
    onSetAllForPackage: (String, PolicyMode) -> Unit,
    onUpdateGlobals: ((GlobalRuleFlags) -> GlobalRuleFlags) -> Unit,
    onDismissMessage: () -> Unit,
) {
    val strings = LocalAppStrings.current
    var route by remember { mutableStateOf<ScreenRoute>(ScreenRoute.Home) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }
    var appCategory by remember { mutableStateOf(AppCategory.USER) }
    var policyScopeFilter by remember { mutableStateOf(PolicyScopeFilter.ALL) }
    var selectedSubsystem by remember { mutableStateOf<BgSubsystem?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.statusMessage) {
        val msg = state.statusMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        onDismissMessage()
    }

    BackHandler(enabled = route != ScreenRoute.Home) {
        route = when (route) {
            is ScreenRoute.NativeListDetail -> ScreenRoute.NativeLists
            else -> ScreenRoute.Home
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        AnimatedContent(
            targetState = route,
            transitionSpec = {
                val goingBack = (initialState is ScreenRoute.NativeListDetail && targetState is ScreenRoute.NativeLists) ||
                    (initialState !is ScreenRoute.Home && targetState is ScreenRoute.Home)
                if (goingBack) {
                    (slideInHorizontally(tween(240)) { -it / 6 } + fadeIn(tween(220))) togetherWith
                        (slideOutHorizontally(tween(220)) { it / 6 } + fadeOut(tween(180)))
                } else {
                    (slideInHorizontally(tween(240)) { it / 6 } + fadeIn(tween(220))) togetherWith
                        (slideOutHorizontally(tween(220)) { -it / 10 } + fadeOut(tween(180)))
                }
            },
            label = "route_transition",
        ) { currentRoute ->
            when (currentRoute) {
                ScreenRoute.Home -> {
                    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
                    Scaffold(
                        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
                        snackbarHost = { SnackbarHost(snackbarHostState) },
                        topBar = {
                            LargeTopAppBar(
                                title = {
                                    Column {
                                        Text(strings.appTitle, fontWeight = FontWeight.Bold)
                                        val subtitle = when {
                                            !state.xposedConnected -> strings.lposedDisconnected
                                            !state.snapshot.serverInjected -> strings.inactiveRebootHint
                                            else -> strings.activatedSummary(state.rules.size)
                                        }
                                        Text(
                                            subtitle,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                actions = {
                                    TextButton(onClick = onToggleLanguage) {
                                        Text(
                                            text = strings.langSwitchLabel,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                    }
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
                            }
                        },
                    ) { padding ->
                        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                            if (selectedTab == 0) {
                                DashboardTab(
                                    state = state,
                                    onJumpToSubsystem = { sub ->
                                        selectedSubsystem = sub
                                        selectedTab = 1
                                    },
                                    onOpenNativeLists = { route = ScreenRoute.NativeLists },
                                    onUpdateGlobals = onUpdateGlobals,
                                    onRefreshAll = onRefreshAll,
                                )
                            } else {
                                AppsTab(
                                    state = state,
                                    searchQuery = searchQuery,
                                    onSearchChange = { searchQuery = it },
                                    appCategory = appCategory,
                                    onCategoryChange = { appCategory = it },
                                    policyScopeFilter = policyScopeFilter,
                                    onScopeFilterChange = { policyScopeFilter = it },
                                    selectedSubsystem = selectedSubsystem,
                                    onSubsystemChange = { selectedSubsystem = it },
                                    onSetRule = onSetRule,
                                    onOpenDetail = { pkg -> route = ScreenRoute.AppDetail(pkg) },
                                )
                            }
                        }
                    }
                }

                is ScreenRoute.AppDetail -> {
                    val appEntry = state.apps.firstOrNull { it.packageName == currentRoute.packageName }
                    AppDetailPage(
                        packageName = currentRoute.packageName,
                        appEntry = appEntry,
                        state = state,
                        snackbarHostState = snackbarHostState,
                        onBack = { route = ScreenRoute.Home },
                        onSetRule = { sub, mode -> onSetRule(currentRoute.packageName, sub, mode) },
                        onSetAll = { mode -> onSetAllForPackage(currentRoute.packageName, mode) },
                    )
                }

                ScreenRoute.NativeLists -> {
                    NativeListsIndexPage(
                        state = state,
                        onBack = { route = ScreenRoute.Home },
                        onSelectKind = { kind -> route = ScreenRoute.NativeListDetail(kind) },
                    )
                }

                is ScreenRoute.NativeListDetail -> {
                    NativeListDetailPage(
                        kind = currentRoute.kind,
                        state = state,
                        onBack = { route = ScreenRoute.NativeLists },
                    )
                }
            }
        }
    }
}

@Composable
private fun DashboardTab(
    state: BgMasterUiState,
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
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ElevatedCard(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = if (state.snapshot.serverInjected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    },
                ),
            ) {
                Row(
                    modifier = Modifier.padding(18.dp),
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
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
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
                        Button(onClick = onRefreshAll) {
                            Text(strings.recheck)
                        }
                    }
                }
            }
        }

        item {
            ElevatedCard(
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier
                    .fillMaxWidth()
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
                        Text(strings.nativeListsTitle, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
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
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        items(BgSubsystem.entries) { subsystem ->
            val hooked = state.snapshot.hookStatus[subsystem.key] ?: false
            val allowCount = state.rules.values.count { it[subsystem] == PolicyMode.FORCE_ALLOW }
            val restrictCount = state.rules.values.count { it[subsystem]?.isRestrict == true }
            val (nativeWhiteCount, nativeBlackCount) = remember(state.snapshot, subsystem) {
                nativeCountsForSubsystem(state.snapshot, subsystem)
            }

            ElevatedCard(
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onJumpToSubsystem(subsystem) },
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
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
                            Text(strings.subsystemTitle(subsystem), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
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
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
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

        item {
            Text(
                strings.sectionGlobals,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        item {
            ElevatedCard(shape = RoundedCornerShape(22.dp)) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    GlobalToggleRow(
                        title = strings.globalGuardElfTitle,
                        subtitle = strings.globalGuardElfSub,
                        checked = state.globalFlags.disableGuardElfCpuKill,
                        onCheckedChange = { v -> onUpdateGlobals { it.copy(disableGuardElfCpuKill = v) } },
                    )
                    HorizontalDivider()
                    GlobalToggleRow(
                        title = strings.globalBroadcastTitle,
                        subtitle = strings.globalBroadcastSub,
                        checked = state.globalFlags.disableBroadcastQuotaGlobal,
                        onCheckedChange = { v -> onUpdateGlobals { it.copy(disableBroadcastQuotaGlobal = v) } },
                    )
                    HorizontalDivider()
                    GlobalToggleRow(
                        title = strings.globalStrictTitle,
                        subtitle = strings.globalStrictSub,
                        checked = state.globalFlags.disableStrictModeFreeze,
                        onCheckedChange = { v -> onUpdateGlobals { it.copy(disableStrictModeFreeze = v) } },
                    )
                    HorizontalDivider()
                    GlobalToggleRow(
                        title = strings.globalVerboseTitle,
                        subtitle = strings.globalVerboseSub,
                        checked = state.globalFlags.verboseLog,
                        onCheckedChange = { v -> onUpdateGlobals { it.copy(verboseLog = v) } },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AppsTab(
    state: BgMasterUiState,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    appCategory: AppCategory,
    onCategoryChange: (AppCategory) -> Unit,
    policyScopeFilter: PolicyScopeFilter,
    onScopeFilterChange: (PolicyScopeFilter) -> Unit,
    selectedSubsystem: BgSubsystem?,
    onSubsystemChange: (BgSubsystem?) -> Unit,
    onSetRule: (String, BgSubsystem, PolicyMode) -> Unit,
    onOpenDetail: (String) -> Unit,
) {
    val strings = LocalAppStrings.current
    var scopeMenuExpanded by remember { mutableStateOf(false) }
    var nativeSectionExpanded by remember { mutableStateOf(true) }

    val groupedApps = remember(
        state.apps,
        state.rules,
        state.snapshot,
        searchQuery,
        appCategory,
        policyScopeFilter,
        selectedSubsystem,
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
                catOk && queryOk
            }
            .sortedBy { it.label.lowercase() }
            .forEach { app ->
                val isOverridden = hasOverrideInDimension(state.rules, app.packageName, selectedSubsystem)
                val isNativeHit = isManagedBySystemInDimension(state.snapshot, app.packageName, selectedSubsystem)
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
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchChange,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(24.dp),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    placeholder = { Text(strings.searchAppsPlaceholder) },
                )

                Box {
                    FilledTonalButton(
                        onClick = { scopeMenuExpanded = true },
                        shape = RoundedCornerShape(20.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp),
                    ) {
                        Icon(Icons.Rounded.FilterList, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(strings.policyScopeLabel(policyScopeFilter), maxLines = 1)
                    }
                    DropdownMenu(
                        expanded = scopeMenuExpanded,
                        onDismissRequest = { scopeMenuExpanded = false },
                    ) {
                        PolicyScopeFilter.entries.forEach { f ->
                            DropdownMenuItem(
                                text = { Text(strings.policyScopeLabel(f)) },
                                leadingIcon = {
                                    if (policyScopeFilter == f) {
                                        Icon(Icons.Rounded.Check, contentDescription = null)
                                    }
                                },
                                onClick = {
                                    onScopeFilterChange(f)
                                    scopeMenuExpanded = false
                                },
                            )
                        }
                    }
                }
            }

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                AppCategory.entries.forEachIndexed { index, cat ->
                    SegmentedButton(
                        selected = appCategory == cat,
                        onClick = { onCategoryChange(cat) },
                        shape = SegmentedButtonDefaults.itemShape(index, AppCategory.entries.size),
                        label = { Text(strings.appCategoryLabel(cat)) },
                    )
                }
            }

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = selectedSubsystem == null,
                    onClick = { onSubsystemChange(null) },
                    label = { Text(strings.allDimensions) },
                )
                BgSubsystem.entries.forEach { sub ->
                    FilterChip(
                        selected = selectedSubsystem == sub,
                        onClick = { onSubsystemChange(sub) },
                        label = { Text(strings.subsystemShort(sub)) },
                    )
                }
            }
        }

        if (state.loadingApps) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                LoadingIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
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
                    items(groupedApps.overridden, key = { "ov_${it.packageName}" }) { app ->
                        AppRowItem(
                            app = app,
                            pkgRules = state.rules[app.packageName].orEmpty(),
                            snapshot = state.snapshot,
                            selectedSubsystem = selectedSubsystem,
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
                        items(groupedApps.nativeHits, key = { "nat_${it.packageName}" }) { app ->
                            AppRowItem(
                                app = app,
                                pkgRules = state.rules[app.packageName].orEmpty(),
                                snapshot = state.snapshot,
                                selectedSubsystem = selectedSubsystem,
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
                    items(groupedApps.neutral, key = { "neu_${it.packageName}" }) { app ->
                        AppRowItem(
                            app = app,
                            pkgRules = state.rules[app.packageName].orEmpty(),
                            snapshot = state.snapshot,
                            selectedSubsystem = selectedSubsystem,
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

@Composable
private fun SectionHeaderRow(
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
            .padding(top = 6.dp, bottom = 2.dp, start = 2.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            text = "$title ($count)",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
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
    snapshot: SystemSnapshot,
    selectedSubsystem: BgSubsystem?,
    onSetRule: (String, BgSubsystem, PolicyMode) -> Unit,
    onOpenDetail: (String) -> Unit,
) {
    if (selectedSubsystem != null) {
        SingleDimensionAppCard(
            app = app,
            subsystem = selectedSubsystem,
            nativeResolution = snapshot.nativeResolutionFor(app.packageName, selectedSubsystem),
            currentMode = pkgRules[selectedSubsystem] ?: PolicyMode.FOLLOW_SYSTEM,
            onModeSelected = { mode -> onSetRule(app.packageName, selectedSubsystem, mode) },
            onOpenDetail = { onOpenDetail(app.packageName) },
        )
    } else {
        AllDimensionsAppCard(
            app = app,
            pkgRules = pkgRules,
            snapshot = snapshot,
            onClick = { onOpenDetail(app.packageName) },
        )
    }
}

@Composable
private fun SingleDimensionAppCard(
    app: InstalledAppEntry,
    subsystem: BgSubsystem,
    nativeResolution: io.github.yukino1111.colorosbgmanager.model.NativeResolution,
    currentMode: PolicyMode,
    onModeSelected: (PolicyMode) -> Unit,
    onOpenDetail: () -> Unit,
) {
    val strings = LocalAppStrings.current
    var menuExpanded by remember { mutableStateOf(false) }

    ElevatedCard(
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenDetail),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DrawableIcon(app.icon, modifier = Modifier.size(40.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    app.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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
            Spacer(Modifier.width(8.dp))
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
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AllDimensionsAppCard(
    app: InstalledAppEntry,
    pkgRules: Map<BgSubsystem, PolicyMode>,
    snapshot: SystemSnapshot,
    onClick: () -> Unit,
) {
    val strings = LocalAppStrings.current
    val overrides = remember(pkgRules) {
        BgSubsystem.entries.mapNotNull { sub ->
            val mode = pkgRules[sub] ?: PolicyMode.FOLLOW_SYSTEM
            if (mode != PolicyMode.FOLLOW_SYSTEM) sub to mode else null
        }
    }
    val nativeNonNeutral = remember(app.packageName, pkgRules, snapshot) {
        BgSubsystem.entries.mapNotNull { sub ->
            val mode = pkgRules[sub] ?: PolicyMode.FOLLOW_SYSTEM
            val native = snapshot.nativeResolutionFor(app.packageName, sub)
            if (mode == PolicyMode.FOLLOW_SYSTEM && native.state != NativeState.NEUTRAL) sub to native else null
        }
    }

    ElevatedCard(
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DrawableIcon(app.icon, modifier = Modifier.size(40.dp))
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        app.label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        app.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (app.isSystem) {
                    StatusPill(text = strings.systemTag, active = false)
                }
            }

            if (overrides.isEmpty() && nativeNonNeutral.isEmpty()) {
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
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppDetailPage(
    packageName: String,
    appEntry: InstalledAppEntry?,
    state: BgMasterUiState,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onSetRule: (BgSubsystem, PolicyMode) -> Unit,
    onSetAll: (PolicyMode) -> Unit,
) {
    val strings = LocalAppStrings.current
    val pkgRules = state.rules[packageName].orEmpty()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
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
                            Text(
                                appEntry?.label ?: packageName,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
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
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { onSetAll(PolicyMode.FORCE_ALLOW) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(strings.allowAll)
                    }
                    FilledTonalButton(
                        onClick = { onSetAll(PolicyMode.FOLLOW_SYSTEM) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(strings.restoreDefault)
                    }
                }
            }

            items(BgSubsystem.entries) { sub ->
                val nativeRes = state.snapshot.nativeResolutionFor(packageName, sub)
                val currentMode = pkgRules[sub] ?: PolicyMode.FOLLOW_SYSTEM

                ElevatedCard(shape = RoundedCornerShape(20.dp)) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(strings.subsystemTitle(sub), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
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
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (currentMode != PolicyMode.FOLLOW_SYSTEM) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    fontWeight = if (currentMode != PolicyMode.FOLLOW_SYSTEM) FontWeight.SemiBold else FontWeight.Normal,
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
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            modes.forEachIndexed { idx, mode ->
                                SegmentedButton(
                                    selected = currentMode == mode,
                                    onClick = { onSetRule(sub, mode) },
                                    shape = SegmentedButtonDefaults.itemShape(idx, modes.size),
                                    label = {
                                        Text(
                                            strings.policyModeLabel(mode, sub, compact = modes.size > 3),
                                            maxLines = 1,
                                            softWrap = false,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NativeListsIndexPage(
    state: BgMasterUiState,
    onBack: () -> Unit,
    onSelectKind: (NativeListKind) -> Unit,
) {
    val strings = LocalAppStrings.current
    val installedPkgSet = remember(state.apps) { state.apps.mapTo(HashSet()) { it.packageName } }
    val cloudLists = remember { NativeListKind.entries.filter { it.source == NativeSource.CLOUD_DEFAULT } }
    val userLists = remember { NativeListKind.entries.filter { it.source == NativeSource.SYSTEM_USER } }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = strings.backCd)
                    }
                },
                title = { Text(strings.nativeListsTitle) },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "hdr_cloud_lists") {
                SectionHeaderRow(
                    icon = Icons.Rounded.Shield,
                    title = strings.cloudDefaultListsHeader,
                    count = cloudLists.size,
                    accent = true,
                )
            }
            items(cloudLists, key = { it.name }) { kind ->
                NativeListIndexCard(
                    kind = kind,
                    state = state,
                    installedPkgSet = installedPkgSet,
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
            items(userLists, key = { it.name }) { kind ->
                NativeListIndexCard(
                    kind = kind,
                    state = state,
                    installedPkgSet = installedPkgSet,
                    onClick = { onSelectKind(kind) },
                )
            }
        }
    }
}

@Composable
private fun NativeListIndexCard(
    kind: NativeListKind,
    state: BgMasterUiState,
    installedPkgSet: Set<String>,
    onClick: () -> Unit,
) {
    val strings = LocalAppStrings.current
    val allPkgs = state.snapshot.listOfKind(kind)
    val installedCount = allPkgs.count { it in installedPkgSet }
    val active = state.snapshot.isListActive(kind)
    ElevatedCard(
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = state.snapshot.serverInjected, onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(strings.nativeListTitle(kind), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    when {
                        !state.snapshot.serverInjected -> strings.statusInactive
                        !active -> strings.installedSummary(installedCount, allPkgs.size) + strings.cloudSwitchOffSuffix
                        else -> strings.installedSummary(installedCount, allPkgs.size)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.snapshot.serverInjected && !active) {
                StatusPill(text = strings.cloudInactivePill, active = false)
                Spacer(Modifier.width(6.dp))
            }
            StatusPill(text = strings.nativeSourceShort(kind.source), active = kind.source == NativeSource.SYSTEM_USER)
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Rounded.ChevronRight, contentDescription = null)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NativeListDetailPage(
    kind: NativeListKind,
    state: BgMasterUiState,
    onBack: () -> Unit,
) {
    val strings = LocalAppStrings.current
    var search by remember { mutableStateOf("") }
    val appMap = remember(state.apps) { state.apps.associateBy { it.packageName } }
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
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = strings.backCd)
                    }
                },
                title = {
                    Column {
                        Text(strings.nativeListTitle(kind), maxLines = 1, overflow = TextOverflow.Ellipsis)
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
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(24.dp),
                singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                placeholder = { Text(strings.filterPkgPlaceholder) },
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(sortedEntries, key = { it }) { pkg ->
                    val app = appMap[pkg]
                    OutlinedCard(shape = RoundedCornerShape(18.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (app != null) {
                                DrawableIcon(app.icon, modifier = Modifier.size(36.dp))
                                Spacer(Modifier.width(12.dp))
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = app?.label ?: pkg,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = pkg,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            StatusPill(
                                text = if (app != null) strings.installed else strings.notInstalled,
                                active = app != null,
                            )
                        }
                    }
                }
            }
        }
    }
}

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
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun StatusPill(text: String, active: Boolean) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
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
        shape = RoundedCornerShape(50),
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
    Surface(shape = RoundedCornerShape(50), color = bg) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(12.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = fg,
                maxLines = 1,
            )
        }
    }
}

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
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.clickable(onClick = onClick),
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
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onClick),
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
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }
        PolicyMode.FORCE_RESTRICT_HBG -> {
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.clickable(onClick = onClick),
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
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onTertiary,
                    )
                }
            }
        }
        PolicyMode.FORCE_RESTRICT -> {
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.clickable(onClick = onClick),
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
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onError,
                    )
                }
            }
        }
    }
}

@Composable
private fun DrawableIcon(drawable: Drawable, modifier: Modifier = Modifier) {
    val bitmap = remember(drawable) { drawable.toBitmap(width = 96, height = 96).asImageBitmap() }
    Image(
        bitmap = bitmap,
        contentDescription = null,
        modifier = modifier.clip(RoundedCornerShape(12.dp)),
    )
}

private fun hasOverrideInDimension(
    rules: Map<String, Map<BgSubsystem, PolicyMode>>,
    pkgName: String,
    subsystem: BgSubsystem?,
): Boolean {
    val pkgMap = rules[pkgName] ?: return false
    return if (subsystem == null) {
        pkgMap.values.any { it != PolicyMode.FOLLOW_SYSTEM }
    } else {
        (pkgMap[subsystem] ?: PolicyMode.FOLLOW_SYSTEM) != PolicyMode.FOLLOW_SYSTEM
    }
}

private fun isManagedBySystemInDimension(
    snapshot: SystemSnapshot,
    pkgName: String,
    subsystem: BgSubsystem?,
): Boolean {
    return if (subsystem == null) {
        BgSubsystem.entries.any { snapshot.nativeStateFor(pkgName, it) != NativeState.NEUTRAL }
    } else {
        snapshot.nativeStateFor(pkgName, subsystem) != NativeState.NEUTRAL
    }
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
