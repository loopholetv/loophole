package io.github.loopholetv.ui.onboarding

import io.github.loopholetv.data.repo.SourceEdit
import io.github.loopholetv.data.repo.withHttpScheme
import io.github.loopholetv.data.stalker.PortalConfig
import io.github.loopholetv.data.stalker.normaliseMac
import io.github.loopholetv.data.xtream.XtreamCredentials

/** What the add-playlist form holds, as plain text; checked by [checkPlaylistForm]. */
data class PlaylistFormInput(
    val mode: Mode,
    val name: String = "",
    val url: String = "",
    val server: String = "",
    val user: String = "",
    val pass: String = "",
    val fileLocation: String = "",
    val fileName: String = "",
    val portalUrl: String = "",
    val mac: String = "",
    val portalLogin: String = "",
    val portalPass: String = "",
    val serial: String = "",
    val deviceId: String = "",
    val deviceId2: String = "",
    val signature: String = "",
) {
    /** Never includes an address, login, password, MAC or identity value. */
    override fun toString() = "PlaylistFormInput(mode=$mode)"
}

sealed interface FormCheck {
    data class Ok(val source: SourceEdit, val name: String) : FormCheck
    data object Incomplete : FormCheck
    data object InvalidMac : FormCheck
}

fun AddPlaylistUi.toFormInput() = PlaylistFormInput(
    mode = mode, name = name, url = url, server = server, user = user, pass = pass,
    fileLocation = fileLocation, fileName = fileName, portalUrl = portalUrl, mac = mac,
    portalLogin = portalLogin, portalPass = portalPass,
    serial = serial, deviceId = deviceId, deviceId2 = deviceId2, signature = signature,
)

/** The source and name [input] describes, or why it can't be added yet. */
fun checkPlaylistForm(input: PlaylistFormInput): FormCheck {
    if (input.mode == Mode.PORTAL && input.portalUrl.isNotBlank() && normaliseMac(input.mac) == null) {
        return FormCheck.InvalidMac
    }
    val source = sourceOf(input) ?: return FormCheck.Incomplete
    return FormCheck.Ok(source, nameFor(input, source))
}

private fun String.typedOrNull(): String? = trim().ifEmpty { null }

private fun sourceOf(s: PlaylistFormInput): SourceEdit? = when (s.mode) {
    Mode.M3U -> s.url.takeIf { it.isNotBlank() }?.let { SourceEdit.M3uUrl(withHttpScheme(it)) }
    // Wait for the display name, or a content:// pick would be named after its URI segment.
    Mode.FILE -> s.fileLocation.takeIf { it.isNotBlank() && s.fileName.isNotBlank() }?.let { SourceEdit.LocalFile(it) }
    Mode.XTREAM ->
        if (s.server.isBlank() || s.user.isBlank() || s.pass.isBlank()) null
        else SourceEdit.Xtream(XtreamCredentials(s.server.trim(), s.user.trim(), s.pass))
    Mode.PORTAL -> {
        val mac = normaliseMac(s.mac)
        if (s.portalUrl.isBlank() || mac == null) null
        else SourceEdit.Portal(
            PortalConfig(
                portalUrl = s.portalUrl.trim(),
                mac = mac,
                login = s.portalLogin.typedOrNull(),
                password = s.portalPass.takeIf { it.isNotBlank() },
                serial = s.serial.typedOrNull(),
                deviceId = s.deviceId.typedOrNull(),
                deviceId2 = s.deviceId2.typedOrNull(),
                signature = s.signature.typedOrNull(),
            ),
        )
    }
}

private fun nameFor(s: PlaylistFormInput, source: SourceEdit): String = s.name.trim().ifEmpty {
    when (source) {
        is SourceEdit.M3uUrl -> AddPlaylistViewModel.defaultName(source.url)
        is SourceEdit.LocalFile -> fileBaseName(s.fileName.ifBlank { source.location.substringAfterLast('/') })
        is SourceEdit.Xtream -> AddPlaylistViewModel.defaultName(s.server)
        is SourceEdit.Portal -> AddPlaylistViewModel.defaultName(source.cfg.portalUrl)
    }
}
