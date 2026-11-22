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

        syncClient = DeviceSyncClient(applicationContext)
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
                    onSetAllForPackage = syncClient::setAllSubsystemsForPackage,
                    onUpdateGlobals = syncClient::updateGlobalFlag,
                    onDismissMessage = syncClient::clearMessage,
                )
            }
        }
    }
}
