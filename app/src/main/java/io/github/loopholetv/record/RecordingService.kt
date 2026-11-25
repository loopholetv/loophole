package io.github.loopholetv.record

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.loopholetv.LoopholeApp
import io.github.loopholetv.R
import io.github.loopholetv.cast.wifiLockMode

/** What the recording coordinator tells the platform: a recording began or ended. */
interface RecordingServiceControl {
    fun started(id: Long, title: String)
    fun ended(id: Long)

    companion object {
        val NONE: RecordingServiceControl = object : RecordingServiceControl {
            override fun started(id: Long, title: String) = Unit
            override fun ended(id: Long) = Unit
        }
    }
}

/** A short wake lock per recording, from its start alarm until the run is recording or the start is given up. */
interface StartWakeLock {
    /** Holds the lock for [id], for at most [timeoutMs]. */
    fun hold(id: Long, timeoutMs: Long)

    fun release(id: Long)

    companion object {
        val NONE: StartWakeLock = object : StartWakeLock {
            override fun hold(id: Long, timeoutMs: Long) = Unit
            override fun release(id: Long) = Unit
        }
    }
}

/** [StartWakeLock] with a partial wake lock per recording. */
class PowerStartWakeLock(context: Context) : StartWakeLock {
    private val power = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private val locks = java.util.concurrent.ConcurrentHashMap<Long, PowerManager.WakeLock>()

    override fun hold(id: Long, timeoutMs: Long) {
        runCatching {
            val lock = locks[id] ?: power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, START_WAKE_TAG)
                ?.apply { setReferenceCounted(false) }
                ?.also { locks[id] = it }
            lock?.acquire(timeoutMs)
        }.onFailure { Log.w("Loophole", "Could not hold the wake lock for a recording's start") }
    }

    override fun release(id: Long) {
        locks.remove(id)?.takeIf { it.isHeld }?.let { runCatching { it.release() } }
    }

    private companion object {
        const val START_WAKE_TAG = "Loophole:recording-start"
    }
}

/**
 * Keeps the process alive and the radio and CPU awake while any recording runs. It holds no recording
 * itself: the recorder runs in the app process, this only shows the ongoing notification, holds the
 * locks and keeps the casting server up so other TVs can reach the recordings.
 */
class RecordingService : Service() {
    private val active = LinkedHashMap<Long, String>()

    internal var wakeLock: PowerManager.WakeLock? = null
        private set
    internal var wifiLock: WifiManager.WifiLock? = null
        private set

    /** Keeps the casting server up in the background; replaced in tests. */
    internal var holdServer: (Boolean) -> Unit = { hold ->
        (application as LoopholeApp).container.castController.holdServer(hold)
    }

    private var serverHeld = false

    /** How many times the service went foreground; read by tests. */
    internal var foregroundCalls = 0
        private set

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getLongExtra(EXTRA_ID, NO_ID) ?: NO_ID
        val stopping = intent?.action == ACTION_STOP
        if (stopping) {
            active.remove(id)
        } else if (intent?.action == ACTION_START && id != NO_ID) {
            active[id] = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        }
        if (active.isNotEmpty()) {
            acquire()
            showNotification()
        } else {
            // A start request must reach startForeground within seconds, even when there is nothing to record.
            if (!stopping) showNotification()
            release()
            stopForegroundAndSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }

    private fun showNotification() {
        val notification = notification()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        foregroundCalls++
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException is one of these: the recording runs on, unprotected.
            Log.w(TAG, "Recording service could not go foreground")
        } catch (e: SecurityException) {
            Log.w(TAG, "Recording service could not go foreground")
        }
    }

    private fun notification(): Notification {
        Notifications.ensureChannels(this)
        return NotificationCompat.Builder(this, Notifications.RECORDINGS)
            .setSmallIcon(R.drawable.loophole_mark)
            .setContentTitle(notificationText(active.values.toList()))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun stopForegroundAndSelf(startId: Int) {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    private fun acquire() {
        val power = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        runCatching {
            if (wakeLock == null) {
                wakeLock = power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG)?.apply { setReferenceCounted(false) }
            }
            wakeLock?.takeIf { !it.isHeld }?.acquire()
        }.onFailure { Log.w(TAG, "Could not hold the wake lock for recording") }
        runCatching {
            if (wifiLock == null) {
                wifiLock = wifi?.createWifiLock(wifiLockMode(), WIFI_TAG)?.apply { setReferenceCounted(false) }
            }
            wifiLock?.takeIf { !it.isHeld }?.acquire()
        }.onFailure { Log.w(TAG, "Could not hold the Wi-Fi lock for recording") }
        if (!serverHeld) {
            serverHeld = true
            holdServer(true)
        }
    }

    private fun release() {
        wakeLock?.takeIf { it.isHeld }?.let { runCatching { it.release() } }
        wifiLock?.takeIf { it.isHeld }?.let { runCatching { it.release() } }
        if (serverHeld) {
            serverHeld = false
            holdServer(false)
        }
    }

    companion object {
        private const val TAG = "Loophole"
        private const val WAKE_TAG = "Loophole:recording"
        private const val WIFI_TAG = "loophole-record"
        private const val NOTIFICATION_ID = 0x5EC0
        private const val NO_ID = -1L
        internal const val ACTION_START = "io.github.loopholetv.record.START"
        internal const val ACTION_STOP = "io.github.loopholetv.record.STOP"
        internal const val EXTRA_ID = "id"
        internal const val EXTRA_TITLE = "title"

        /**
         * Starts the service for a recording. Android 12 and later refuse to start a foreground service
         * from the background except in a few windows; an exact-alarm broadcast is one, so call this from
         * the alarm path. When refused the recording still runs in the app process, just unprotected.
         */
        fun start(context: Context, recordingId: Long, title: String) {
            val intent = Intent(context, RecordingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_ID, recordingId)
                .putExtra(EXTRA_TITLE, title)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException (API 31+) is one of these; no message, it can name the app state.
                Log.w(TAG, "Recording service could not start")
            }
        }

        /** Tells the service a recording ended; the service stops itself after the last one. */
        fun stop(context: Context, recordingId: Long) {
            val intent = Intent(context, RecordingService::class.java)
                .setAction(ACTION_STOP)
                .putExtra(EXTRA_ID, recordingId)
            try {
                context.startService(intent)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Recording service could not be told to stop")
            }
        }

        /** The adapter the coordinator uses. */
        fun control(context: Context): RecordingServiceControl {
            val app = context.applicationContext
            return object : RecordingServiceControl {
                override fun started(id: Long, title: String) = start(app, id, title)
                override fun ended(id: Long) = stop(app, id)
            }
        }
    }
}

/** "Recording <title>" for one recording, "Recording <n> programmes" for several. */
internal fun notificationText(titles: List<String>): String =
    if (titles.size == 1) (if (titles[0] == LOCKED_TITLE) LOCKED_TITLE else "Recording ${titles[0]}") else "Recording ${titles.size} programmes"
