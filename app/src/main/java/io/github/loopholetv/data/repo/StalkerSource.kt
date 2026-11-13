package io.github.loopholetv.data.repo

import io.github.loopholetv.data.db.MaintenanceDao
import io.github.loopholetv.data.db.PlaylistEntity
import io.github.loopholetv.data.stalker.PortalConfig
import io.github.loopholetv.data.stalker.StalkerChannelMeta
import io.github.loopholetv.data.stalker.StalkerClient
import io.github.loopholetv.data.stalker.StalkerSession
import io.github.loopholetv.data.stalker.StalkerSessions
import io.github.loopholetv.data.stalker.stalkerTvgId

/** How many hours of portal guide to read. */
internal const val PORTAL_EPG_HOURS = 72

/**
 * A MAC portal's channel list for one import. The session was acquired (and its token saved)
 * before the import transaction opened; [stream] only reads from the portal and makes no
 * database calls. Channels are keyed by the portal's channel id, so a renamed or renumbered
 * channel keeps its edits.
 */
internal class StalkerSource(
    private val client: StalkerClient,
    private val session: StalkerSession,
    private val cfg: PortalConfig,
) : ChannelSource {
    /** The portal guide isn't a URL: it is announced by the playlist type ([PORTAL_EPG_SOURCE]). */
    override suspend fun stream(emit: (ImportItem) -> Unit): String {
        client.channels(session, cfg) { c ->
            emit(
                ImportedChannel(
                    key = c.id,
                    name = c.name,
                    streamUrl = c.cmd,
                    preferredNumber = c.number,
                    logo = c.logo,
                    group = c.genre.blankToNull(),
                    tvgId = stalkerTvgId(c.id, c.xmltvId),
                    tvgName = null,
                    userAgent = null,
                    referrer = null,
                    catchupType = null,
                    catchupDays = null,
                    catchupSource = null,
                    needsLink = c.needsLink,
                ),
            )
        }
        return ""
    }

    override fun noChannelsError(): ImportError = ImportError.EmptyPlaylist
}

/**
 * The portal guide reader for [EpgRepository] (`portalEpg`): `get_epg_info` for [PORTAL_EPG_HOURS]
 * hours through the playlist's session. The stored channels give the guide ids and names, so the
 * guide's channel ids match the channels' `tvg-id`s (`stalkerTvgId`) and every portal channel with
 * programmes is listed (as an `XmltvChannel`) for "Choose TV guide".
 */
internal suspend fun readPortalGuide(
    playlist: PlaylistEntity,
    maintenance: MaintenanceDao,
    client: StalkerClient,
    sessions: StalkerSessions,
    emit: (Any) -> Unit,
) {
    val meta = maintenance.portalChannels(playlist.id).associate { row ->
        row.key to StalkerChannelMeta(name = row.name, xmltvId = row.tvgId)
    }
    sessions.withSession(playlist.id) { s, cfg -> client.epg(s, cfg, PORTAL_EPG_HOURS, meta, emit) }
}
