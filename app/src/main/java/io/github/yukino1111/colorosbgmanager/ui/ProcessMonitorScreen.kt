package io.github.yukino1111.colorosbgmanager.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yukino1111.colorosbgmanager.data.BgMasterUiState
import io.github.yukino1111.colorosbgmanager.data.InstalledAppEntry
import io.github.yukino1111.colorosbgmanager.model.AppProcessSummary
import io.github.yukino1111.colorosbgmanager.model.AppUserKey
import io.github.yukino1111.colorosbgmanager.model.ProcessDetailInfo
import io.github.yukino1111.colorosbgmanager.model.ProcessRunBucket
import io.github.yukino1111.colorosbgmanager.model.RootProcessInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale

enum class ProcessCategoryFilter {
    ALL,
    USER,
    SYSTEM,
    ROOT,
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ProcessesTab(
    state: BgMasterUiState,
    listState: LazyListState,
    isActiveOnScreen: Boolean,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    category: ProcessCategoryFilter,
    onCategoryChange: (ProcessCategoryFilter) -> Unit,
    onLoadProcesses: (Boolean) -> Unit,
    onLoadRootProcesses: (Boolean) -> Unit,
    onOpenProcessDetail: (AppUserKey) -> Unit,
) {
    val strings = LocalAppStrings.current
    val motionScheme = MaterialTheme.motionScheme
    var showKernelThreads by remember { mutableStateOf(false) }
    var runningExpanded by remember { mutableStateOf(true) }
    var cachedExpanded by remember { mutableStateOf(true) }
    var stoppedExpanded by remember { mutableStateOf(false) }
    var inspectedRootProc by remember { mutableStateOf<RootProcessInfo?>(null) }

    LaunchedEffect(isActiveOnScreen, category, state.snapshot.serverInjected, state.apps.size, state.rootAvailable) {
        if (!isActiveOnScreen) return@LaunchedEffect
        if (category == ProcessCategoryFilter.ROOT) {
            if (state.rootAvailable == null) {
                onLoadRootProcesses(true)
            }
            if (state.rootAvailable != false) {
                while (isActive) {
                    delay(2500L)
                    onLoadRootProcesses(false)
                }
            }
        } else if (state.snapshot.serverInjected) {
            onLoadProcesses(state.appProcessSummaries.isEmpty())
            while (isActive) {
                delay(2000L)
                onLoadProcesses(false)
            }
        }
    }

    val (runningList, cachedList, stoppedList) = remember(
        state.apps,
        state.appProcessSummaries,
        searchQuery,
        category,
    ) {
        if (category == ProcessCategoryFilter.ROOT) {
            return@remember Triple(
                emptyList<Pair<InstalledAppEntry, AppProcessSummary>>(),
                emptyList<Pair<InstalledAppEntry, AppProcessSummary>>(),
                emptyList<Pair<InstalledAppEntry, AppProcessSummary>>(),
            )
        }
        val summaryMap = state.appProcessSummaries.associateBy { AppUserKey(it.packageName, it.userId) }
        val q = searchQuery.trim().lowercase()
        val running = ArrayList<Pair<InstalledAppEntry, AppProcessSummary>>()
        val cached = ArrayList<Pair<InstalledAppEntry, AppProcessSummary>>()
        val stopped = ArrayList<Pair<InstalledAppEntry, AppProcessSummary>>()

        state.apps.forEach { app ->
            val catOk = when (category) {
                ProcessCategoryFilter.USER -> !app.isSystem
                ProcessCategoryFilter.SYSTEM -> app.isSystem
                ProcessCategoryFilter.ALL -> true
                ProcessCategoryFilter.ROOT -> false
            }
            val queryOk = q.isEmpty() ||
                app.label.lowercase().contains(q) ||
                app.packageName.lowercase().contains(q)
            if (catOk && queryOk) {
                val summary = summaryMap[app.key] ?: AppProcessSummary(
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
                when (summary.bucket) {
                    ProcessRunBucket.RUNNING -> running.add(app to summary)
                    ProcessRunBucket.CACHED -> cached.add(app to summary)
                    ProcessRunBucket.STOPPED -> stopped.add(app to summary)
                }
            }
        }

        running.sortWith(
            compareByDescending<Pair<InstalledAppEntry, AppProcessSummary>> { it.second.totalMemoryKb }
                .thenByDescending { it.second.totalCpuPercent }
                .thenBy { it.first.label.lowercase() },
        )
        cached.sortWith(compareByDescending<Pair<InstalledAppEntry, AppProcessSummary>> { it.second.totalMemoryKb }.thenBy { it.first.label.lowercase() })
        stopped.sortWith(compareBy<Pair<InstalledAppEntry, AppProcessSummary>> { it.first.label.lowercase() }.thenBy { it.first.userId })

        Triple(running, cached, stopped)
    }

    val multiUserPkgs = remember(state.apps) {
        state.apps.groupingBy { it.packageName }.eachCount().filterValues { it > 1 }.keys
    }

    val filteredRootProcs = remember(state.rootProcesses, searchQuery, showKernelThreads) {
        val q = searchQuery.trim().lowercase()
        state.rootProcesses.filter { proc ->
            val kOk = showKernelThreads || !proc.isKernelThread
            val qOk = q.isEmpty() ||
                proc.cmdline.lowercase().contains(q) ||
                proc.user.lowercase().contains(q) ||
                proc.pid.toString().contains(q)
            kOk && qOk
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .animateContentSize(animationSpec = motionScheme.defaultSpatialSpec()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Md3eSearchField(
                value = searchQuery,
                onValueChange = onSearchChange,
                placeholder = if (category == ProcessCategoryFilter.ROOT) strings.searchRootPlaceholder else strings.searchAppsPlaceholder,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PopupFilterButton(
                    selectedLabel = strings.processCategoryLabel(category),
                    icon = Icons.Rounded.Apps,
                    items = ProcessCategoryFilter.entries,
                    itemLabel = { strings.processCategoryLabel(it) },
                    isSelected = { it == category },
                    onSelect = onCategoryChange,
                )

                if (category == ProcessCategoryFilter.ROOT) {
                    Spacer(Modifier.weight(1f))
                    FilterChip(
                        selected = showKernelThreads,
                        onClick = { showKernelThreads = !showKernelThreads },
                        label = { Text(strings.showKernelThreads) },
                    )
                    IconButton(onClick = { onLoadRootProcesses(true) }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = strings.refreshCd)
                    }
                }
            }
        }

        if (category == ProcessCategoryFilter.ROOT) {
            when {
                state.loadingRootProcesses && state.rootProcesses.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        LoadingIndicator()
                    }
                }
                state.rootAvailable == false -> {
                    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(Icons.Rounded.Terminal, contentDescription = null, modifier = Modifier.size(40.dp))
                            Text(
                                text = strings.rootUnavailableHint(state.rootError),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(
                                onClick = { onLoadRootProcesses(true) },
                                shapes = ButtonDefaults.shapes(),
                            ) {
                                Text(strings.retryRootButton)
                            }
                        }
                    }
                }
                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
                    ) {
                        item(key = "hdr_root_procs") {
                            SectionHeaderRow(
                                icon = Icons.Rounded.Terminal,
                                title = strings.processGroupRoot,
                                count = filteredRootProcs.size,
                                accent = true,
                            )
                        }
                        itemsIndexed(filteredRootProcs, key = { _, proc -> "root_${proc.pid}" }) { idx, proc ->
                            RootProcessRowCard(
                                proc = proc,
                                index = idx,
                                count = filteredRootProcs.size,
                                modifier = Modifier.animateItem(
                                    fadeInSpec = motionScheme.defaultEffectsSpec(),
                                    placementSpec = motionScheme.defaultSpatialSpec(),
                                    fadeOutSpec = motionScheme.fastEffectsSpec(),
                                ),
                                onClick = { inspectedRootProc = proc },
                            )
                        }
                    }
                }
            }
        } else if (state.loadingApps || (state.loadingProcesses && state.appProcessSummaries.isEmpty())) {
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
                item(key = "hdr_proc_running") {
                    CollapsibleSectionHeader(
                        icon = Icons.Rounded.PlayArrow,
                        title = strings.processGroupRunning,
                        count = runningList.size,
                        expanded = runningExpanded,
                        accent = true,
                        onToggle = { runningExpanded = !runningExpanded },
                    )
                }
                if (runningExpanded) {
                    itemsIndexed(runningList, key = { _, pair -> "run_${pair.first.uniqueKey()}" }) { idx, (app, summary) ->
                        AppProcessRowCard(
                            app = app,
                            summary = summary,
                            index = idx,
                            count = runningList.size,
                            showUserBadge = app.userId != 0 || app.packageName in multiUserPkgs,
                            modifier = Modifier.animateItem(
                                fadeInSpec = motionScheme.defaultEffectsSpec(),
                                placementSpec = motionScheme.defaultSpatialSpec(),
                                fadeOutSpec = motionScheme.fastEffectsSpec(),
                            ),
                            onClick = { onOpenProcessDetail(app.key) },
                        )
                    }
                }

                item(key = "hdr_proc_cached") {
                    CollapsibleSectionHeader(
                        icon = Icons.Rounded.Memory,
                        title = strings.processGroupCached,
                        count = cachedList.size,
                        expanded = cachedExpanded,
                        accent = false,
                        onToggle = { cachedExpanded = !cachedExpanded },
                    )
                }
                if (cachedExpanded) {
                    itemsIndexed(cachedList, key = { _, pair -> "cch_${pair.first.uniqueKey()}" }) { idx, (app, summary) ->
                        AppProcessRowCard(
                            app = app,
                            summary = summary,
                            index = idx,
                            count = cachedList.size,
                            showUserBadge = app.userId != 0 || app.packageName in multiUserPkgs,
                            modifier = Modifier.animateItem(
                                fadeInSpec = motionScheme.defaultEffectsSpec(),
                                placementSpec = motionScheme.defaultSpatialSpec(),
                                fadeOutSpec = motionScheme.fastEffectsSpec(),
                            ),
                            onClick = { onOpenProcessDetail(app.key) },
                        )
                    }
                }

                item(key = "hdr_proc_stopped") {
                    CollapsibleSectionHeader(
                        icon = Icons.Rounded.PowerSettingsNew,
                        title = strings.processGroupStopped,
                        count = stoppedList.size,
                        expanded = stoppedExpanded,
                        accent = false,
                        onToggle = { stoppedExpanded = !stoppedExpanded },
                    )
                }
                if (stoppedExpanded) {
                    itemsIndexed(stoppedList, key = { _, pair -> "stp_${pair.first.uniqueKey()}" }) { idx, (app, summary) ->
                        AppProcessRowCard(
                            app = app,
                            summary = summary,
                            index = idx,
                            count = stoppedList.size,
                            showUserBadge = app.userId != 0 || app.packageName in multiUserPkgs,
                            modifier = Modifier.animateItem(
                                fadeInSpec = motionScheme.defaultEffectsSpec(),
                                placementSpec = motionScheme.defaultSpatialSpec(),
                                fadeOutSpec = motionScheme.fastEffectsSpec(),
                            ),
                            onClick = { onOpenProcessDetail(app.key) },
                        )
                    }
                }

                if (runningList.isEmpty() && cachedList.isEmpty() && stoppedList.isEmpty()) {
                    item(key = "proc_empty") {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
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

    inspectedRootProc?.let { proc ->
        AlertDialog(
            onDismissRequest = { inspectedRootProc = null },
            title = {
                Text(
                    "PID ${proc.pid} · ${proc.user} (${proc.uid})",
                    style = MaterialTheme.typography.titleMediumEmphasized,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "${strings.procMemLabel}: RES ${formatMemoryKb(proc.resKb)} / SHR ${formatMemoryKb(proc.shrKb)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "${strings.procCpuLabel}: ${formatCpuPercent(proc.cpuPercent)} · ${strings.procStateLabel}: ${proc.state}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "${strings.procUptimeLabel}: ${proc.elapsedTime} (CPU ${proc.cpuTime})",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    HorizontalDivider()
                    Text(
                        proc.cmdline,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { inspectedRootProc = null }) {
                    Text(strings.closeDialog)
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CollapsibleSectionHeader(
    icon: ImageVector,
    title: String,
    count: Int,
    expanded: Boolean,
    accent: Boolean,
    onToggle: () -> Unit,
) {
    val tint = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(top = 10.dp, bottom = 6.dp, start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Text(
            "$title ($count)",
            style = MaterialTheme.typography.labelLargeEmphasized,
            color = tint,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AppProcessRowCard(
    app: InstalledAppEntry,
    summary: AppProcessSummary,
    index: Int,
    count: Int,
    showUserBadge: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val strings = LocalAppStrings.current
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
                if (summary.bucket == ProcessRunBucket.STOPPED) {
                    Text(
                        text = strings.runtimeNotRunning,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        StatusPill(
                            text = "${strings.procMemLabel} ${formatMemoryKb(summary.totalMemoryKb)}",
                            active = summary.bucket == ProcessRunBucket.RUNNING,
                        )
                        StatusPill(
                            text = "${strings.procCpuLabel} ${formatCpuPercent(summary.totalCpuPercent)}",
                            active = summary.totalCpuPercent >= 1f,
                        )
                        StatusPill(
                            text = "${strings.procUptimeLabel} ${formatDurationMs(summary.maxUptimeMs)}",
                            active = false,
                        )
                        StatusPill(
                            text = strings.procAndSvcCount(summary.processCount, summary.serviceCount),
                            active = summary.serviceCount > 0,
                        )
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
                if (showUserBadge) {
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
private fun RootProcessRowCard(
    proc: RootProcessInfo,
    index: Int,
    count: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val strings = LocalAppStrings.current
    SegmentedListItem(
        onClick = onClick,
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        colors = ListItemDefaults.segmentedColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = modifier,
        supportingContent = {
            FlowRow(
                modifier = Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                StatusPill(text = "PID ${proc.pid}", active = proc.uid == 0)
                StatusPill(text = "${proc.user} (${proc.uid})", active = proc.uid == 0)
                StatusPill(text = "RES ${formatMemoryKb(proc.resKb)}", active = false)
                StatusPill(text = "${strings.procCpuLabel} ${formatCpuPercent(proc.cpuPercent)}", active = proc.cpuPercent >= 1f)
                StatusPill(text = "${proc.state} · ${proc.elapsedTime}", active = false)
            }
        },
        content = {
            Text(
                text = proc.cmdline,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AppProcessDetailPage(
    target: AppUserKey,
    appEntry: InstalledAppEntry?,
    state: BgMasterUiState,
    onBack: () -> Unit,
    onLoadProcesses: (Boolean) -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    val strings = LocalAppStrings.current
    val motionScheme = MaterialTheme.motionScheme
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val summary = remember(state.appProcessSummaries, target) {
        state.appProcessSummaries.firstOrNull { it.packageName == target.packageName && it.userId == target.userId }
    }

    LaunchedEffect(target, state.snapshot.serverInjected) {
        if (state.snapshot.serverInjected) {
            onLoadProcesses(summary == null)
            while (isActive) {
                delay(2000L)
                onLoadProcesses(false)
            }
        }
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
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = appEntry?.label ?: target.packageName,
                                style = MaterialTheme.typography.titleLargeEmphasized,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (target.userId != 0) {
                                StatusPill(text = strings.userBadge(target.userId), active = true)
                            }
                        }
                        Text(
                            text = target.packageName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenAppSettings) {
                        Icon(Icons.Rounded.Settings, contentDescription = strings.openAppSettingsCd)
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "proc_detail_summary") {
                Card(
                    shape = MaterialTheme.shapes.large,
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = strings.procAndSvcCount(summary?.processCount ?: 0, summary?.serviceCount ?: 0),
                            style = MaterialTheme.typography.titleMediumEmphasized,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            StatusPill(
                                text = "${strings.procMemLabel} ${formatMemoryKb(summary?.totalMemoryKb ?: 0L)}",
                                active = (summary?.processCount ?: 0) > 0,
                            )
                            StatusPill(
                                text = "${strings.procCpuLabel} ${formatCpuPercent(summary?.totalCpuPercent ?: 0f)}",
                                active = (summary?.totalCpuPercent ?: 0f) >= 1f,
                            )
                            StatusPill(
                                text = "${strings.procUptimeLabel} ${formatDurationMs(summary?.maxUptimeMs ?: 0L)}",
                                active = false,
                            )
                            if (appEntry?.isSuspended == true) {
                                StatusPill(text = strings.freezeStatusSuspended, active = false)
                            }
                        }
                    }
                }
            }

            if (summary != null) {
                items(summary.processes, key = { it.pid }) { proc ->
                    SingleProcessDetailCard(
                        proc = proc,
                        modifier = Modifier.animateItem(
                            fadeInSpec = motionScheme.defaultEffectsSpec(),
                            placementSpec = motionScheme.defaultSpatialSpec(),
                            fadeOutSpec = motionScheme.fastEffectsSpec(),
                        ),
                    )
                }
                if (summary.extraServices.isNotEmpty()) {
                    item(key = "extra_services_card") {
                        Card(
                            shape = MaterialTheme.shapes.large,
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    strings.extraServicesHeader,
                                    style = MaterialTheme.typography.titleSmallEmphasized,
                                )
                                summary.extraServices.forEach { svc ->
                                    Text(
                                        text = svc,
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SingleProcessDetailCard(
    proc: ProcessDetailInfo,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current
    Card(
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = modifier.animateContentSize(animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec()),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = proc.processName,
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                StatusPill(text = "PID ${proc.pid}", active = !proc.isCached)
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StatusPill(text = "${proc.stateLabel} (adj=${proc.oomAdj})", active = !proc.isCached)
                StatusPill(text = "${strings.procMemLabel} ${formatMemoryKb(proc.memoryKb)}", active = false)
                StatusPill(text = "${strings.procCpuLabel} ${formatCpuPercent(proc.cpuPercent)}", active = proc.cpuPercent >= 1f)
                StatusPill(text = "${strings.procUptimeLabel} ${formatDurationMs(proc.uptimeMs)}", active = false)
            }

            HorizontalDivider()

            Text(
                text = strings.hostedServicesCount(proc.services.size),
                style = MaterialTheme.typography.labelLargeEmphasized,
            )
            if (proc.services.isEmpty()) {
                Text(
                    text = strings.noActiveServices,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    proc.services.forEach { svc ->
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = svc,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun InstalledAppEntry.uniqueKey(): String = "$userId|$packageName"

internal fun formatMemoryKb(kb: Long): String = when {
    kb <= 0L -> "0 KB"
    kb < 1024L -> "$kb KB"
    kb < 1024L * 1024L -> String.format(Locale.US, "%.1f MB", kb / 1024.0)
    else -> String.format(Locale.US, "%.2f GB", kb / (1024.0 * 1024.0))
}

internal fun formatCpuPercent(pct: Float): String = String.format(Locale.US, "%.1f%%", pct)

internal fun formatDurationMs(ms: Long): String {
    if (ms <= 0L) return "0s"
    val totalSec = ms / 1000L
    val hours = totalSec / 3600L
    val mins = (totalSec % 3600L) / 60L
    val secs = totalSec % 60L
    return when {
        hours > 0L -> "${hours}h ${mins}m"
        mins > 0L -> "${mins}m ${secs}s"
        else -> "${secs}s"
    }
}
