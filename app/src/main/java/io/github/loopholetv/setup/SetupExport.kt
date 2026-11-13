package io.github.loopholetv.setup

import androidx.room.withTransaction
import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.db.ChannelPrefsEntity
import io.github.loopholetv.data.db.EpgSourceEntity
import io.github.loopholetv.data.db.FavouriteEntity
import io.github.loopholetv.data.db.GroupPrefsEntity
import io.github.loopholetv.data.db.PlaylistEntity
import io.github.loopholetv.data.db.PlaylistType
import io.github.loopholetv.data.repo.LocalM3uOpener
import io.github.loopholetv.data.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * [skippedLargeFiles] local-file playlists were left out of [bundle] (too large, past the total,
 * or unreadable); the bundle carries the same count for the receiving TV.
 */
class SetupExportResult(val bundle: SetupBundle, val skippedLargeFiles: Int) {
    override fun toString() = "SetupExportResult(bundle=$bundle, skippedLargeFiles=$skippedLargeFiles)"
}

/**
 * Builds this TV's [SetupBundle]: the copied tables and settings (see [SetupFields]). File
 * contents are staged in temp files, not memory: call [SetupBundle.discardFiles] once the bundle
 * has been sent (left-over temps are also swept up by [LocalM3uOpener] after a while).
 */
internal class SetupExporter(
    private val db: AppDatabase,
    private val settings: SettingsStore,
    private val files: LocalM3uOpener,
    private val maxFileBytes: Int = MAX_SETUP_FILE_BYTES,
    private val maxTotalFileBytes: Int = MAX_SETUP_TOTAL_FILE_BYTES,
) {
    private class Tables(
        val playlists: List<PlaylistEntity>,
        val links: List<EpgSourceEntity>,
        val channelEdits: List<ChannelPrefsEntity>,
        val groupEdits: List<GroupPrefsEntity>,
        val favourites: List<FavouriteEntity>,
    )

    suspend fun export(fromName: String): SetupExportResult {
        // One read transaction, so the tables agree with each other.
        val t = db.withTransaction {
            val dao = db.setupDao()
            Tables(dao.playlists(), dao.epgSources(), dao.channelPrefs(), dao.groupPrefs(), dao.favourites())
        }
        val current = settings.current()
        val links = t.links.groupBy { it.playlistId }
        val channelEdits = t.channelEdits.groupBy { it.playlistId }
        val groupEdits = t.groupEdits.groupBy { it.playlistId }
        val playlists = ArrayList<SetupPlaylist>()
        val position = HashMap<Long, Int>()
        var skipped = 0
        var fileBytes = 0L
        val staged = ArrayList<SetupFile>()
        try {
            for (p in t.playlists) {
                val file = if (p.type == PlaylistType.M3U_FILE) {
                    val room = minOf(maxFileBytes.toLong(), maxTotalFileBytes - fileBytes).coerceAtLeast(0)
                    val read = withContext(Dispatchers.IO) { stage(p, room) }
                    if (read == null) {
                        skipped++
                        continue
                    }
                    staged += read
                    fileBytes += read.size
                    read
                } else {
                    null
                }
                position[p.id] = playlists.size
                playlists += p.toSetup(
                    file = file,
                    links = links[p.id].orEmpty().map { it.url },
                    channelEdits = channelEdits[p.id].orEmpty().mapNotNull { it.toSetup() },
                    groupEdits = groupEdits[p.id].orEmpty().filterNot { it.isDefault() }.map { it.toSetup() },
                )
            }
        } catch (e: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) { staged.forEach { f -> f.staged?.let { runCatching { it.delete() } } } }
            throw e
        }
        val favourites = t.favourites.mapNotNull { f ->
            position[f.playlistId]?.let { SetupFavourite(it, f.channelKey, f.addedAt.toEpochMilli()) }
        }
        val bundle = SetupBundle(
            fromName = fromName,
            playlists = playlists,
            favourites = favourites,
            settings = SetupSettings.from(current),
            skippedFiles = skipped,
        )
        return SetupExportResult(bundle, skipped)
    }

    /**
     * Copies the file (else its last-good copy) into a temp file, never holding it in memory;
     * null when over [limit], unreadable, or no temp could be made.
     */
    private fun stage(p: PlaylistEntity, limit: Long): SetupFile? {
        val input = files.openRaw(p.url.orEmpty(), p.id) ?: return null
        val (temp, out) = files.newTemp() ?: run {
            input.close()
            return null
        }
        val size = try {
            input.use { inp -> out.use { copyCapped(inp, it, limit) } }
        } catch (_: IOException) {
            null // never the message: it may name the path
        } catch (_: SecurityException) {
            null
        }
        if (size == null) temp.delete()
        return size?.let { SetupFile(temp, it) }
    }

    /** The bytes copied, or null past [limit]. */
    private fun copyCapped(input: InputStream, out: OutputStream, limit: Long): Long? {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) return total
            total += n
            if (total > limit) return null
            out.write(buffer, 0, n)
        }
    }

    private fun PlaylistEntity.toSetup(
        file: SetupFile?,
        links: List<String>,
        channelEdits: List<SetupChannelEdit>,
        groupEdits: List<SetupGroupEdit>,
    ) = SetupPlaylist(
        name = name,
        type = type,
        url = url.takeUnless { type == PlaylistType.M3U_FILE },
        xtreamServer = xtreamServer,
        xtreamUser = xtreamUser,
        xtreamPass = xtreamPass,
        manualEpgUrl = manualEpgUrl,
        enabled = enabled,
        userAgent = userAgent,
        updateHours = updateHours,
        updateOnStart = updateOnStart,
        useSourceEpg = useSourceEpg,
        mac = mac,
        portalLogin = portalLogin,
        portalPassword = portalPassword,
        portalSerial = portalSerial,
        portalDeviceId = portalDeviceId,
        portalDeviceId2 = portalDeviceId2,
        portalSignature = portalSignature,
        file = file,
        guideLinks = links,
        channelEdits = channelEdits,
        groupEdits = groupEdits,
    )

    /** Null when nothing copied is left (only a decoder or external player choice). */
    private fun ChannelPrefsEntity.toSetup(): SetupChannelEdit? {
        val edit = SetupChannelEdit(
            key = channelKey,
            hidden = hidden,
            customName = customName,
            customLogo = customLogo,
            epgOverride = epgOverride,
            epgShiftMinutes = epgShiftMinutes,
            position = position,
            aspectMode = aspectMode,
        )
        return edit.takeUnless { it == SetupChannelEdit(channelKey) }
    }

    private fun GroupPrefsEntity.toSetup() = SetupGroupEdit(
        title = groupTitle,
        hidden = hidden,
        customName = customName,
        position = position,
        sortMode = sortMode,
    )
}
