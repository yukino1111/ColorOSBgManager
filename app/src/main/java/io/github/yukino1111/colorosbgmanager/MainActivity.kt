package io.github.yukino1111.colorosbgmanager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import io.github.yukino1111.colorosbgmanager.data.DeviceSyncClient
import io.github.yukino1111.colorosbgmanager.ui.BgMasterScreen
import io.github.yukino1111.colorosbgmanager.ui.theme.BgMasterTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var syncClient: DeviceSyncClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        syncClient = DeviceSyncClient.getInstance(applicationContext)
        syncClient.start()

        lifecycleScope.launch {
            syncClient.refreshAll(userTriggered = false)
        }

        setContent {
            val state by syncClient.state.collectAsState()
            BgMasterTheme {
                BgMasterScreen(
                    state = state,
                    onRefreshAll = {
                        lifecycleScope.launch { syncClient.refreshAll(userTriggered = true) }
                    },
                    onToggleLanguage = syncClient::toggleLanguage,
                    onSetRule = syncClient::setRule,
                    onResetSinglePackage = { target ->
                        syncClient.setAllSubsystemsForPackage(target, io.github.yukino1111.colorosbgmanager.model.PolicyMode.FOLLOW_SYSTEM)
                    },
                    onResetAllRules = syncClient::resetAllAppRules,
                    onApplyPreset = syncClient::applyPresetForPackage,
                    onUpdatePreset = syncClient::updateSubsystemPreset,
                    onUpdateGlobals = syncClient::updateGlobalFlag,
                    onLoadPackageRuntime = { pkg, userId ->
                        lifecycleScope.launch { syncClient.loadPackageRuntime(pkg, userId) }
                    },
                    onLoadProcesses = { showSpinner ->
                        lifecycleScope.launch { syncClient.loadProcesses(showSpinner) }
                    },
                    onLoadRootProcesses = { showSpinner ->
                        lifecycleScope.launch { syncClient.loadRootProcesses(showSpinner) }
                    },
                    onSetAutoFreeze = { target, enabled ->
                        lifecycleScope.launch { syncClient.setAutoFreeze(target, enabled) }
                    },
                    onSetAutoUnfreeze = syncClient::setAutoUnfreeze,
                    onSetPackageSuspended = { target, suspend ->
                        lifecycleScope.launch { syncClient.setPackageSuspended(target, suspend) }
                    },
                    onLaunchApp = { target ->
                        lifecycleScope.launch { syncClient.launchApp(target) }
                    },
                    onPinShortcut = syncClient::pinLaunchShortcut,
                    onDismissMessage = syncClient::clearMessage,
                )
            }
        }
    }
}
