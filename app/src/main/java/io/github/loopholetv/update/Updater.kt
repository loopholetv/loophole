package io.github.loopholetv.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import java.io.File

/** The device side of an update: install permission, APK checks and the system installer. */
interface InstallPlatform {
    /** Whether Android lets this app start an install (always true below API 26). */
    fun canInstall(): Boolean

    fun verify(file: File): VerifyResult

    /** Hands [file] to the system installer; the outcome arrives through [Updater.onInstallOutcome]. */
    fun install(file: File)

    /** Opens the system screen where the user allows installs from this app. */
    fun openInstallSettings()
}

enum class InstallOutcome { SUCCEEDED, CANCELLED, FAILED }

/** How long after returning to the app an install with no reported outcome counts as cancelled. */
internal const val LOST_RESULT_MS = 3_000L

/**
 * Process-wide update flow: the start-up check, the download (which keeps going while the user is
 * elsewhere in the app), verification and the hand-off to the system installer. [status] drives
 * the guide card and About. Nothing here is logged.
 */
class Updater(
    scope: CoroutineScope,
    private val checker: UpdateChecker,
    private val downloader: UpdateFiles,
    private val platform: InstallPlatform,
    private val enabled: suspend () -> Boolean,
    /** Returns once the start-up splash is gone. */
    private val gate: suspend () -> Unit,
) {
    private val scope = scope + CoroutineExceptionHandler { _, _ -> }

    private val _status = MutableStateFlow<UpdateStatus>(UpdateStatus.None)
    val status: StateFlow<UpdateStatus> = _status.asStateFlow()

    private val _about = MutableStateFlow<AboutCheck>(AboutCheck.Idle)
    val about: StateFlow<AboutCheck> = _about.asStateFlow()

    private var download: Job? = null
    @Volatile private var downloadGeneration = 0
    private var firstFocusTaken = false

    private fun send(e: UpdateEvent) = _status.update { reduce(it, e) }

    /** Once per process, after the splash: the daily check, then the card if there is something to offer. */
    fun onColdStart(): Job = scope.launch {
        gate()
        val on = try {
            enabled()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            true
        }
        val offer = checker.onStart(on)
        if (offer == null) {
            // Nothing to offer (turned off, Later, or this build is the update): no file is kept.
            if (_status.value is UpdateStatus.None) downloader.clear()
            return@launch
        }
        offer(offer)
    }

    private fun offer(r: Release) {
        if (downloader.existing(r) != null) send(UpdateEvent.AlreadyDownloaded(r)) else send(UpdateEvent.Offered(r))
    }

    /** True the first time only: the card takes focus when it first appears in this process. */
    fun claimFirstFocus(): Boolean {
        if (firstFocusTaken) return false
        firstFocusTaken = true
        return true
    }

    /** Runs a card (or About) button; returns the background work it started, if any. */
    fun perform(action: UpdateAction): Job? = when (action) {
        UpdateAction.UPDATE, UpdateAction.RETRY -> startDownload()
        UpdateAction.LATER, UpdateAction.CLOSE -> later()
        UpdateAction.CANCEL -> { cancelDownload(); null }
        UpdateAction.INSTALL -> install()
        UpdateAction.OPEN_SETTINGS -> { platform.openInstallSettings(); null }
    }

    private fun startDownload(): Job? {
        val s = _status.value
        if (s !is UpdateStatus.Available && s !is UpdateStatus.Failed) return null
        val r = s.releaseOrNull() ?: return null
        send(UpdateEvent.StartDownload)
        val previous = download
        val generation = ++downloadGeneration
        // Events from a cancelled download that is still winding down must not reach the card.
        fun sendIfCurrent(e: UpdateEvent) {
            if (generation == downloadGeneration) send(e)
        }
        return scope.launch {
            // A cancelled download finishes its own clean-up before the next one touches the directory.
            previous?.cancelAndJoin()
            try {
                val file = downloader.download(r) { sendIfCurrent(UpdateEvent.Progress(it)) }
                if (platform.verify(file) == VerifyResult.OK) {
                    sendIfCurrent(UpdateEvent.Downloaded)
                } else {
                    file.delete()
                    sendIfCurrent(UpdateEvent.VerifyFailed)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: NotEnoughSpaceException) {
                sendIfCurrent(UpdateEvent.NotEnoughSpace)
            } catch (_: Exception) {
                sendIfCurrent(UpdateEvent.DownloadFailed)
            }
        }.also { download = it }
    }

    private fun cancelDownload() {
        if (_status.value !is UpdateStatus.Downloading) return
        // Kept in [download] so the next start waits for it to finish cleaning up.
        download?.cancel()
        downloadGeneration++
        send(UpdateEvent.CancelDownload)
    }

    private fun later(): Job? {
        val s = _status.value
        if (s is UpdateStatus.None || s is UpdateStatus.Downloading || s is UpdateStatus.Installing) return null
        send(UpdateEvent.Later)
        return scope.launch { checker.later() }
    }

    private fun install(): Job? {
        val s = _status.value
        if (s !is UpdateStatus.Ready && s !is UpdateStatus.NeedsPermission) return null
        val r = s.releaseOrNull() ?: return null
        if (!platform.canInstall()) {
            send(UpdateEvent.NoInstallPermission)
            return null
        }
        // Claimed before the checks run so a second press can't start a second session.
        send(UpdateEvent.InstallStarted)
        return scope.launch {
            val file = downloader.existing(r)
            if (file == null || platform.verify(file) != VerifyResult.OK) {
                file?.delete()
                _status.update { if (it is UpdateStatus.Installing) UpdateStatus.Failed(it.release) else it }
                return@launch
            }
            try {
                platform.install(file)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                send(UpdateEvent.InstallFailed)
            }
        }
    }

    fun onInstallOutcome(outcome: InstallOutcome) {
        when (outcome) {
            InstallOutcome.SUCCEEDED -> send(UpdateEvent.InstallSucceeded)
            InstallOutcome.CANCELLED -> send(UpdateEvent.InstallCancelled)
            InstallOutcome.FAILED -> send(UpdateEvent.InstallFailed)
        }
    }

    /**
     * The app is in front again: back from the install-permission screen the install goes on; an
     * install whose result never came (some installers report nothing when their confirmation is
     * dismissed) returns to "Install" after [LOST_RESULT_MS].
     */
    fun onResumed(): Job? = when (val s = _status.value) {
        is UpdateStatus.NeedsPermission -> if (platform.canInstall()) install() else null
        is UpdateStatus.Installing -> scope.launch {
            delay(LOST_RESULT_MS)
            _status.update { if (it === s) UpdateStatus.Ready(s.release) else it }
        }
        else -> null
    }

    /** About's "Check for updates": checks now, whatever the time since the last check. */
    fun checkNow(): Job? {
        if (_about.value == AboutCheck.Checking) return null
        _about.value = AboutCheck.Checking
        return scope.launch {
            val result = try {
                checker.checkNow()
            } catch (e: CancellationException) {
                _about.value = AboutCheck.Idle
                throw e
            } catch (_: Exception) {
                CheckResult.Failed
            }
            _about.value = when (result) {
                CheckResult.UpToDate -> AboutCheck.UpToDate
                CheckResult.Failed -> AboutCheck.Failed
                is CheckResult.Available -> {
                    offer(result.release)
                    AboutCheck.Available(result.release)
                }
            }
        }
    }
}
