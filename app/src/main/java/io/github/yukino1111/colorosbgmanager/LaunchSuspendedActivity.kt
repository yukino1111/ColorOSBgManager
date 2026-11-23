package io.github.yukino1111.colorosbgmanager

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import io.github.yukino1111.colorosbgmanager.data.DeviceSyncClient
import io.github.yukino1111.colorosbgmanager.model.RulesCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LaunchSuspendedActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pkg = intent?.getStringExtra(EXTRA_PKG)?.trim().orEmpty()
        val userId = intent?.getIntExtra(EXTRA_USER_ID, 0) ?: 0
        val token = intent?.getStringExtra(EXTRA_TOKEN).orEmpty()

        if (pkg.isEmpty() || !DeviceSyncClient.verifyShortcutToken(this, token)) {
            finish()
            return
        }

        CoroutineScope(Dispatchers.Main).launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val res = contentResolver.call(
                        Uri.parse(RulesCodec.BRIDGE_URI),
                        RulesCodec.BRIDGE_LAUNCH_APP_METHOD,
                        null,
                        Bundle().apply {
                            putString("pkg", pkg)
                            putInt("user_id", userId)
                        },
                    )
                    res?.getBoolean("ok", false) == true
                }.getOrDefault(false)
            }
            if (!ok) {
                Toast.makeText(applicationContext, "Launch failed: $pkg (u$userId)", Toast.LENGTH_SHORT).show()
            }
            finish()
        }
    }

    companion object {
        const val EXTRA_PKG = "extra_pkg"
        const val EXTRA_USER_ID = "extra_user_id"
        const val EXTRA_TOKEN = "extra_token"
    }
}
