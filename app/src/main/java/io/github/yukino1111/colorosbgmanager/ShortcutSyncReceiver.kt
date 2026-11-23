package io.github.yukino1111.colorosbgmanager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.yukino1111.colorosbgmanager.data.ShortcutHelper
import io.github.yukino1111.colorosbgmanager.model.RulesCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ShortcutSyncReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != RulesCodec.ACTION_SYNC_SHORTCUT) return
        val pkg = intent.getStringExtra("pkg")?.trim().orEmpty()
        if (pkg.isEmpty()) return
        val userId = intent.getIntExtra("user_id", 0)
        val suspended = intent.getBooleanExtra("suspended", false)
        val pending = goAsync()
        val appCtx = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ShortcutHelper.syncPinnedShortcut(appCtx, pkg, userId, suspended)
            } finally {
                pending.finish()
            }
        }
    }
}
