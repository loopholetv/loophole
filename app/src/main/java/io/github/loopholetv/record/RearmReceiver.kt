package io.github.loopholetv.record

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** After a boot, an app update or a clock or time zone change: recover what was running, then set the alarms again. */
class RearmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in HANDLED) return
        runAsync(context, "REARM") { it.container.recoverAndRearm() }
    }

    private companion object {
        val HANDLED = setOf(
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
        )
    }
}
