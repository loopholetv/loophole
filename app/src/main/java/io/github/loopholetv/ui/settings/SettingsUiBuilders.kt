package io.github.loopholetv.ui.settings

import io.github.loopholetv.data.db.EpgSourceEntity
import io.github.loopholetv.data.db.EpgStatusEntity
import io.github.loopholetv.data.db.PlaylistEntity
import io.github.loopholetv.data.db.PlaylistType
import io.github.loopholetv.data.repo.COPY_NOTICE_PREFIX
import io.github.loopholetv.data.repo.EPG_NO_SOURCE
import io.github.loopholetv.data.repo.PORTAL_EPG_SOURCE
import io.github.loopholetv.data.repo.PORTAL_ERROR_COPY
import io.github.loopholetv.data.repo.announcedEpgUrls
import io.github.loopholetv.data.repo.m3uSecrets
import io.github.loopholetv.data.repo.playlistSecrets
import io.github.loopholetv.data.xtream.XtreamCredentials
import io.github.loopholetv.util.redact
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One added guide link; [url] is redacted and safe to show. */
data class EpgSourceUi(val id: Long, val url: String)

/** One playlist's block on Settings → EPG. */
data class EpgUi(
    val playlistId: Long,
    val playlistName: String,
    /** False for a local file whose header announces no guide (nothing to turn on or off). */
    val showOwnGuide: Boolean,
    val useOwnGuide: Boolean,
    /** The playlist's announced guide links, redacted (the portal guide has none to show). */
    val ownGuide: List<String>,
    val sources: List<EpgSourceUi>,
    val updating: Boolean,
    /** Status line (redacted), or null when the guide was never loaded. */
    val statusText: String?,
    val statusIsError: Boolean,
)

internal class EpgUiBuilder(
    private val xtreamEpgUrl: (XtreamCredentials) -> String,
    zone: ZoneId,
    locale: Locale,
) {
    private val dateFormat = DateTimeFormatter.ofPattern("d/M/yy h:mm a", locale).withZone(zone)

    fun build(p: PlaylistEntity, status: EpgStatusEntity?, added: List<EpgSourceEntity>, updating: Boolean): EpgUi {
        val announced = announcedEpgUrls(p, xtreamEpgUrl).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val secrets = playlistSecrets(p) + (announced + added.map { it.url }).flatMap(::m3uSecrets)
        val (text, isError) = when {
            updating -> "Updating guide…" to false
            status == null -> null to false
            // On this page "add one in Settings → EPG" is no help, and it stayed after a link was
            // saved: simply no source yet shows nothing. Real update failures still show.
            !status.ok && status.message == EPG_NO_SOURCE -> null to false
            status.ok -> buildString {
                append("EPG is successfully updated for ${status.matchedChannels} channels on ${status.updatedAt?.let(dateFormat::format).orEmpty()}")
                // e.g. "(1 of 2 guide sources failed)"; stored messages are already redacted.
                status.message?.takeIf { it.isNotBlank() }?.let { append(' ').append(redact(it, secrets)) }
            } to false
            // Stored messages are already redacted; redact again so no source secret can ever show.
            else -> redact(status.message.orEmpty(), secrets) to true
        }
        return EpgUi(
            playlistId = p.id,
            playlistName = p.name,
            showOwnGuide = !(p.type == PlaylistType.M3U_FILE && announced.isEmpty()),
            useOwnGuide = p.useSourceEpg,
            ownGuide = announced.filter { it != PORTAL_EPG_SOURCE }.map { redact(it, secrets) },
            sources = added.map { EpgSourceUi(it.id, redact(it.url, secrets)) },
            updating = updating,
            statusText = text,
            statusIsError = isError,
        )
    }
}

/** Builds a Playlists row: "<N> channels · On|Off · <last update or error>". */
internal class PlaylistRowText(zone: ZoneId, private val locale: Locale) {
    private val updated = DateTimeFormatter.ofPattern("d MMM HH:mm", locale).withZone(zone)

    fun row(p: PlaylistEntity, count: Int, op: PlaylistOp): PlaylistRowUi {
        val (status, isError) = status(p, op)
        return PlaylistRowUi(
            id = p.id,
            name = p.name,
            channelCount = count,
            op = op,
            enabled = p.enabled,
            userAgent = p.userAgent,
            updateHours = p.updateHours,
            updateOnStart = p.updateOnStart,
            statusText = status,
            statusIsError = isError,
            secondaryText = "${channelsGrouped(count, locale)} · ${onOff(p.enabled)} · $status",
        )
    }

    /** The status text, and whether it reports a failure (shown in the error colour). */
    private fun status(p: PlaylistEntity, op: PlaylistOp): Pair<String, Boolean> {
        val stored = p.lastError?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            op is PlaylistOp.Updating -> "Updating… ${channelsGrouped(op.count, locale)}" to false
            op is PlaylistOp.Failed -> op.message to true
            // Stored errors are raw (redacted) import messages: only fixed copy is shown. The last-good-copy
            // notice reports a success; the portal's messages are fixed copy. A failed source
            // edit keeps its new values and stores its error here, so it never reads as a success.
            stored != null && stored.startsWith(COPY_NOTICE_PREFIX) -> stored to false
            stored != null && stored in PORTAL_ERROR_COPY -> stored to true
            stored != null -> LAST_UPDATE_FAILED to true
            p.lastRefreshedAt != null -> "Updated ${updated.format(p.lastRefreshedAt)}" to false
            else -> NOT_UPDATED_YET to false
        }
    }
}
