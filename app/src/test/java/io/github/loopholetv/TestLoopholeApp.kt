package io.github.loopholetv

import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper

/**
 * Robolectric's application (robolectric.properties): Robolectric runs no ContentProviders, so App
 * Startup never initialises WorkManager; do it here before [LoopholeApp.onCreate] schedules updates.
 * Casting never reaches a real network here: no service discovery and no fixed beacon port.
 */
class TestLoopholeApp : LoopholeApp() {
    override fun onCreate() {
        container.castNsd = { NoNsd }
        container.castBeaconPort = 0
        WorkManagerTestInitHelper.initializeTestWorkManager(
            this,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        super.onCreate()
    }

    private object NoNsd : io.github.loopholetv.cast.NsdPort {
        override fun register(serviceName: String, port: Int, txt: Map<String, String>) {}
        override fun unregister() {}
        override fun browse(onFound: (host: String, txt: Map<String, String>) -> Unit, onLost: (id: String) -> Unit) {}
        override fun stopBrowse() {}
    }
}
