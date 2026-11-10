package io.github.loopholetv.update

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.provider.Settings
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import io.github.loopholetv.BuildConfig
import io.github.loopholetv.LoopholeApp
import java.io.File

/** [InstallPlatform] on PackageManager and a PackageInstaller session. */
class AndroidInstallPlatform(context: Context) : InstallPlatform {
    private val app = context.applicationContext
    private val pm = app.packageManager
    private val facts = PackageFactsReader(pm, app.packageName)

    /** The session handed to the installer last; abandoned when a new one starts. */
    private var lastSession: Int? = null

    override fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || pm.canRequestPackageInstalls()

    override fun verify(file: File): VerifyResult {
        val installed = facts.installed() ?: return VerifyResult.UNREADABLE
        return verifyUpdate(facts.archive(file), installed)
    }

    override fun install(file: File) {
        val installer = pm.packageInstaller
        lastSession?.let { id -> runCatching { installer.abandonSession(id) } }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(file.length())
        }
        val id = installer.createSession(params)
        lastSession = id
        try {
            installer.openSession(id).use { session ->
                file.inputStream().use { input ->
                    session.openWrite("base.apk", 0, file.length()).use { out ->
                        input.copyTo(out, 64 * 1024)
                        session.fsync(out)
                    }
                }
                val intent = Intent(app, UpdateInstallReceiver::class.java).setAction(ACTION_INSTALL_STATUS)
                // The installer adds the status extras, so the intent must be mutable (explicit, ours only).
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                val pending = PendingIntent.getBroadcast(app, id, intent, flags)
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            lastSession = null
            throw e
        }
    }

    override fun openInstallSettings() {
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                add(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${app.packageName}".toUri()))
                add(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES))
            }
            add(Intent(Settings.ACTION_SECURITY_SETTINGS))
            add(Intent(Settings.ACTION_SETTINGS))
        }
        for (intent in candidates) {
            try {
                app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
    }

    companion object {
        const val ACTION_INSTALL_STATUS = BuildConfig.APPLICATION_ID + ".action.UPDATE_INSTALL_STATUS"
    }
}

/**
 * The install session's status (not exported). The system first asks for user action: its
 * confirmation screen is started here; the final outcome goes to the [Updater].
 */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AndroidInstallPlatform.ACTION_INSTALL_STATUS) return
        val updater = (context.applicationContext as? LoopholeApp)?.container?.updater ?: return
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm == null) {
                    updater.onInstallOutcome(InstallOutcome.FAILED)
                    return
                }
                try {
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {
                    updater.onInstallOutcome(InstallOutcome.FAILED)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> updater.onInstallOutcome(InstallOutcome.SUCCEEDED)
            PackageInstaller.STATUS_FAILURE_ABORTED -> updater.onInstallOutcome(InstallOutcome.CANCELLED)
            else -> updater.onInstallOutcome(InstallOutcome.FAILED)
        }
    }
}

/** The release endpoint; a debug build reads an override from `no_backup/update_url` when present. */
internal fun releaseUrl(context: Context): String {
    if (BuildConfig.DEBUG) {
        val override = runCatching { File(context.noBackupFilesDir, "update_url").readText().trim() }.getOrNull()
        if (override != null && (override.startsWith("http://") || override.startsWith("https://"))) return override
    }
    return GITHUB_LATEST_RELEASE_URL
}

/** Bytes the app may still write in [dir]: allocatable space from API 26 (counts clearable cache). */
fun freeBytesFor(context: Context, dir: File): Long = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val storage = context.getSystemService(android.os.storage.StorageManager::class.java)
        storage.getAllocatableBytes(storage.getUuidForPath(dir))
    } else {
        @Suppress("UsableSpace")
        dir.usableSpace
    }
}.getOrDefault(0L)
