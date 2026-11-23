package io.github.yukino1111.colorosbgmanager

import android.app.Application
import io.github.yukino1111.colorosbgmanager.data.DeviceSyncClient

class BgManagerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DeviceSyncClient.getInstance(this).start()
    }
}
