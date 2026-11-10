package io.github.loopholetv.update

/** Where an offered update is; drives the guide card and About. */
sealed interface UpdateStatus {
    /** Nothing to show (no update, Later, or turned off). */
    data object None : UpdateStatus
    data class Available(val release: Release) : UpdateStatus
    data class Downloading(val release: Release, val percent: Int) : UpdateStatus
    data class Ready(val release: Release) : UpdateStatus
    /** Android won't let Loophole install until the user allows it (API 26+). */
    data class NeedsPermission(val release: Release) : UpdateStatus
    /** Handed to the system installer; its confirmation is showing. */
    data class Installing(val release: Release) : UpdateStatus
    data class Failed(val release: Release) : UpdateStatus
}

sealed interface UpdateEvent {
    data class Offered(val release: Release) : UpdateEvent
    /** A file for the offered release is already downloaded. */
    data class AlreadyDownloaded(val release: Release) : UpdateEvent
    data object Later : UpdateEvent
    data object StartDownload : UpdateEvent
    data class Progress(val percent: Int) : UpdateEvent
    data object Downloaded : UpdateEvent
    data object CancelDownload : UpdateEvent
    data object DownloadFailed : UpdateEvent
    data object VerifyFailed : UpdateEvent
    data object NoInstallPermission : UpdateEvent
    data object InstallStarted : UpdateEvent
    data object InstallSucceeded : UpdateEvent
    /** The user backed out of the system confirmation. */
    data object InstallCancelled : UpdateEvent
    data object InstallFailed : UpdateEvent
}

/** Pure transitions; an event that does not apply leaves the status as it is. */
fun reduce(s: UpdateStatus, e: UpdateEvent): UpdateStatus {
    val r = s.releaseOrNull()
    return when (e) {
        is UpdateEvent.Offered -> if (s is UpdateStatus.None || s is UpdateStatus.Available || s is UpdateStatus.Failed) {
            UpdateStatus.Available(e.release)
        } else {
            s
        }
        is UpdateEvent.AlreadyDownloaded -> if (s is UpdateStatus.None || s is UpdateStatus.Available) UpdateStatus.Ready(e.release) else s
        UpdateEvent.Later -> when (s) {
            is UpdateStatus.Available, is UpdateStatus.Ready, is UpdateStatus.NeedsPermission, is UpdateStatus.Failed -> UpdateStatus.None
            else -> s
        }
        UpdateEvent.StartDownload -> when (s) {
            is UpdateStatus.Available, is UpdateStatus.Failed -> UpdateStatus.Downloading(r!!, 0)
            else -> s
        }
        is UpdateEvent.Progress -> if (s is UpdateStatus.Downloading) s.copy(percent = e.percent.coerceIn(0, 100)) else s
        UpdateEvent.Downloaded -> if (s is UpdateStatus.Downloading) UpdateStatus.Ready(s.release) else s
        UpdateEvent.CancelDownload -> if (s is UpdateStatus.Downloading) UpdateStatus.Available(s.release) else s
        UpdateEvent.DownloadFailed -> if (s is UpdateStatus.Downloading) UpdateStatus.Failed(s.release) else s
        UpdateEvent.VerifyFailed -> if (r != null && s !is UpdateStatus.None) UpdateStatus.Failed(r) else s
        UpdateEvent.NoInstallPermission -> when (s) {
            is UpdateStatus.Ready, is UpdateStatus.NeedsPermission, is UpdateStatus.Installing -> UpdateStatus.NeedsPermission(r!!)
            else -> s
        }
        UpdateEvent.InstallStarted -> when (s) {
            is UpdateStatus.Ready, is UpdateStatus.NeedsPermission -> UpdateStatus.Installing(r!!)
            else -> s
        }
        UpdateEvent.InstallSucceeded -> if (s is UpdateStatus.Installing) UpdateStatus.None else s
        UpdateEvent.InstallCancelled -> if (s is UpdateStatus.Installing) UpdateStatus.Ready(s.release) else s
        UpdateEvent.InstallFailed -> if (s is UpdateStatus.Installing) UpdateStatus.Failed(s.release) else s
    }
}

fun UpdateStatus.releaseOrNull(): Release? = when (this) {
    UpdateStatus.None -> null
    is UpdateStatus.Available -> release
    is UpdateStatus.Downloading -> release
    is UpdateStatus.Ready -> release
    is UpdateStatus.NeedsPermission -> release
    is UpdateStatus.Installing -> release
    is UpdateStatus.Failed -> release
}

/** What a card button does. */
enum class UpdateAction { UPDATE, LATER, CANCEL, INSTALL, OPEN_SETTINGS, RETRY, CLOSE }

/** The card's text and buttons; the first button is the one focus goes to. */
data class UpdateCardUi(val message: String, val buttons: List<Pair<String, UpdateAction>>)

/** Null: no card. */
fun cardFor(s: UpdateStatus): UpdateCardUi? = when (s) {
    UpdateStatus.None -> null
    is UpdateStatus.Available -> UpdateCardUi(
        availableText(s.release),
        listOf("Update" to UpdateAction.UPDATE, "Later" to UpdateAction.LATER),
    )
    is UpdateStatus.Downloading -> UpdateCardUi("Downloading… ${s.percent}%", listOf("Cancel" to UpdateAction.CANCEL))
    is UpdateStatus.Ready -> UpdateCardUi(
        "Ready to install",
        listOf("Install" to UpdateAction.INSTALL, "Later" to UpdateAction.LATER),
    )
    is UpdateStatus.NeedsPermission -> UpdateCardUi(
        "Allow Loophole to install updates",
        listOf("Open settings" to UpdateAction.OPEN_SETTINGS, "Later" to UpdateAction.LATER),
    )
    is UpdateStatus.Installing -> UpdateCardUi("Installing…", emptyList())
    is UpdateStatus.Failed -> UpdateCardUi(
        "Update failed",
        listOf("Try again" to UpdateAction.RETRY, "Close" to UpdateAction.CLOSE),
    )
}

fun availableText(r: Release): String = "Loophole ${r.version.display} is available"

/** What Back on the card does: Later where the card offers it, else Close for a failure; else nothing. */
fun backActionFor(s: UpdateStatus): UpdateAction? = cardFor(s)?.buttons?.map { it.second }
    ?.firstOrNull { it == UpdateAction.LATER || it == UpdateAction.CLOSE }

/** About's "Check for updates" row. */
sealed interface AboutCheck {
    data object Idle : AboutCheck
    data object Checking : AboutCheck
    data object UpToDate : AboutCheck
    data class Available(val release: Release) : AboutCheck
    data object Failed : AboutCheck
}

/** The second line under "Check for updates"; null before any check. */
fun aboutCheckText(c: AboutCheck): String? = when (c) {
    AboutCheck.Idle -> null
    AboutCheck.Checking -> "Checking…"
    AboutCheck.UpToDate -> "You're up to date"
    is AboutCheck.Available -> availableText(c.release)
    AboutCheck.Failed -> "Couldn't check for updates"
}
