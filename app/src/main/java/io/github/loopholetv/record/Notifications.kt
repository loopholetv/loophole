package io.github.loopholetv.record

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

/** The notification channels for recordings and reminders. */
object Notifications {
    const val RECORDINGS = "recordings"
    const val REMINDERS = "reminders"

    internal const val RECORDINGS_NAME = "Recordings"
    internal const val REMINDERS_NAME = "Reminders"

    /** Creates both channels; safe to call at every start. Recordings is quiet (it stays up for hours), Reminders is not. */
    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        manager.createNotificationChannel(
            NotificationChannel(RECORDINGS, RECORDINGS_NAME, NotificationManager.IMPORTANCE_LOW),
        )
        manager.createNotificationChannel(
            NotificationChannel(REMINDERS, REMINDERS_NAME, NotificationManager.IMPORTANCE_HIGH),
        )
    }
}
