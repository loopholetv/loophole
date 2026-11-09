package io.github.loopholetv

import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper

/**
 * Robolectric's application (robolectric.properties): Robolectric runs no ContentProviders, so App
 * Startup never initialises WorkManager; do it here before [LoopholeApp.onCreate] schedules updates.
 */
class TestLoopholeApp : LoopholeApp() {
    override fun onCreate() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            this,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        super.onCreate()
    }
}
