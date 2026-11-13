package io.github.loopholetv.setup

import androidx.room.withTransaction
import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.db.ChannelPrefsEntity
import io.github.loopholetv.data.db.EpgSourceEntity
import io.github.loopholetv.data.db.FavouriteEntity
import io.github.loopholetv.data.db.GroupPrefsEntity
import io.github.loopholetv.data.db.HistoryEntity
import io.github.loopholetv.data.db.PlaylistEntity
import io.github.loopholetv.data.db.PlaylistType
import io.github.loopholetv.data.repo.ChannelOverlay
import io.github.loopholetv.data.repo.LocalM3uOpener
import io.github.loopholetv.data.settings.AppSettings
import io.github.loopholetv.data.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.Instant

sealed interface SetupImportResult {
    /**
     * [skippedFiles] local-file playlists were too large to copy (left out by the sending TV, or
     * by this one). [removed] are the playlists this TV had before, for clean-up the importer
     * can't do itself (portal sessions).
     */
    data class Ok(val skippedFiles: Int, val removed: List<RemovedPlaylist> = emptyList()) : SetupImportResult {
        override fun toString() = "Ok(skippedFiles=$skippedFiles, removed=${removed.size})"
    }

    /** Nothing was changed. */
    data object Failed : SetupImportResult
}

/** A playlist an import replaced. [url] may hold a login: never printed. */
data class RemovedPlaylist(val id: Long, val type: PlaylistType, val url: String?) {
    override fun toString() = "RemovedPlaylist(id=$id, type=$type)"
}

/**
 * Makes this TV a mirror of a [SetupBundle] for the copied parts: playlists (new ids), guide links,
 * channel and group edits, favourites and the copied settings replace what was here. Everything
 * else stays: this TV's name and cast settings, its hardware playback choices, settings this
 * class doesn't know, and - for a playlist this TV already had (same type and address or login) -
 * its watch history and per-channel decoder and external player choices. Channels and the guide
 * come from the refresh the caller starts afterwards.
 *
 * Received file contents become each new playlist's last-good copy ([LocalM3uOpener.copyFile]),
 * and the playlist reads that file by absolute path, so refresh, delete and the copy tee treat it
 * like any other local file.
 *
 * All or nothing (Room and DataStore can't share a transaction, so the order matters):
 * 1. File contents are staged to temp files; if one can't be written, they go and nothing else is touched.
 * 2. One Room transaction replaces the rows, moves the staged files into place, and ends with the
 *    one DataStore edit. A failing row write or move never reaches the settings; a failing
 *    settings edit rolls the rows back.
 * 3. If the transaction fails after the settings edit ran (the commit fails, or the edit threw
 *    after storing), the copied settings are written back to what they were and the moved files
 *    are removed. Only that write-back failing as well could leave the settings ahead of the rows.
 * 4. After the commit, the replaced playlists' copies and file grants go (best effort).
 *
 * [serialize] runs the whole import under the caller's refresh lock, so a refresh in flight can't
 * write channels for a playlist being replaced.
 */
internal class SetupImporter(
    private val db: AppDatabase,
    private val settings: SettingsStore,
    private val files: LocalM3uOpener,
    private val serialize: suspend (suspend () -> Unit) -> Unit = { it() },
    private val overlay: ChannelOverlay = ChannelOverlay(db),
    /** Runs inside the transaction right after the settings edit (a test seam for a failing commit). */
    private val afterSettingsWritten: suspend () -> Unit = {},
) {
    /**
     * Once started, an import finishes (or fails cleanly) even if the caller goes away. Imports
     * never overlap, whichever importer runs them.
     */
    suspend fun import(b: SetupBundle): SetupImportResult = LOCK.withLock {
        withContext(NonCancellable) {
            var result: SetupImportResult = SetupImportResult.Failed
            serialize { result = replace(b) }
            result
        }
    }

    /** A received file staged in a temp, to become playlist [index]'s copy. */
    private class Staged(val index: Int, val temp: File)

    private suspend fun replace(bundle: SetupBundle): SetupImportResult {
        val (b, dropped) = withinLimits(bundle)
        val staged = ArrayList<Staged>()
        val moved = ArrayList<File>()
        try {
            withContext(Dispatchers.IO) {
                val copies = files.copyFile(0).parentFile
                b.playlists.forEachIndexed { i, p ->
                    val file = p.file ?: return@forEachIndexed
                    val own = file.staged
                    if (own != null && own.parentFile == copies) {
                        // Decoded straight into this TV's copies folder: moved into place as it is.
                        staged += Staged(i, own)
                    } else {
                        val (temp, out) = files.newTemp() ?: throw IOException("No temp file")
                        staged += Staged(i, temp)
                        out.use { o -> file.open().use { it.copyTo(o) } }
                    }
                }
            }
        } catch (_: Exception) {
            discard(staged, moved)
            return SetupImportResult.Failed
        }
        var before: AppSettings? = null
        val replaced = try {
            db.withTransaction {
                val ids = replaceRows(b)
                staged.forEach { s ->
                    val target = files.copyFile(ids[s.index])
                    if (!s.temp.renameTo(target)) throw IOException("Couldn't move a received file")
                    moved += target
                }
                settings.update { current ->
                    before = current
                    b.settings.applyTo(current)
                }
                afterSettingsWritten()
                ids.old
            }
        } catch (_: Exception) {
            before?.let { restore(it) }
            discard(staged, moved)
            return SetupImportResult.Failed
        }
        withContext(Dispatchers.IO) {
            for (p in replaced) {
                runCatching { files.deleteCopy(p.id) }
                // No new playlist reads a content:// location, so every old grant can go.
                if (p.type == PlaylistType.M3U_FILE) p.url?.let { runCatching { files.release(it) } }
            }
        }
        return SetupImportResult.Ok(
            skippedFiles = b.skippedFiles + dropped,
            removed = replaced.map { RemovedPlaylist(it.id, it.type, it.url) },
        )
    }

    /** Leaves out file playlists past the per-file or total cap (a bundle this TV didn't make). */
    private fun withinLimits(b: SetupBundle): Pair<SetupBundle, Int> {
        val keep = ArrayList<Int>()
        var total = 0L
        b.playlists.forEachIndexed { i, p ->
            val size = p.file?.size ?: 0
            if (size > MAX_SETUP_FILE_BYTES || total + size > MAX_SETUP_TOTAL_FILE_BYTES) return@forEachIndexed
            total += size
            keep += i
        }
        if (keep.size == b.playlists.size) return b to 0
        val position = keep.withIndex().associate { (newIndex, oldIndex) -> oldIndex to newIndex }
        val trimmed = b.copy(
            playlists = keep.map { b.playlists[it] },
            favourites = b.favourites.mapNotNull { f -> position[f.playlist]?.let { f.copy(playlist = it) } },
        )
        return trimmed to b.playlists.size - keep.size
    }

    /** Puts the copied settings back to [previous], leaving the others as they are now. */
    private suspend fun restore(previous: AppSettings) {
        val copied = SetupSettings.from(previous)
        try {
            settings.update { copied.applyTo(it) }
        } catch (_: Exception) {
            // Nothing more can be done; the next import or settings change overwrites them.
        }
    }

    private suspend fun discard(staged: List<Staged>, moved: List<File>) = withContext(Dispatchers.IO) {
        staged.forEach { runCatching { it.temp.delete() } }
        moved.forEach { runCatching { it.delete() } }
    }

    /** The new playlists' ids by bundle position, and the playlists that were here. */
    private class Ids(val new: LongArray, val old: List<PlaylistEntity>) {
        operator fun get(index: Int) = new[index]
    }

    /** Inside the transaction: replaces the copied rows. */
    private suspend fun replaceRows(b: SetupBundle): Ids {
        val dao = db.setupDao()
        val old = dao.playlists()
        val oldPrefs = dao.channelPrefs().groupBy { it.playlistId }
        val oldHistory = dao.history().groupBy { it.playlistId }
        val unmatched = HashMap<String, ArrayDeque<PlaylistEntity>>()
        old.forEach { p -> p.identity()?.let { unmatched.getOrPut(it) { ArrayDeque() }.addLast(p) } }

        dao.deleteAllPlaylists()
        val ids = LongArray(b.playlists.size)
        val links = ArrayList<EpgSourceEntity>()
        val channelEdits = ArrayList<ChannelPrefsEntity>()
        val groupEdits = ArrayList<GroupPrefsEntity>()
        val history = ArrayList<HistoryEntity>()
        b.playlists.forEachIndexed { i, p ->
            val entity = p.toEntity()
            val id = dao.insertPlaylist(entity)
            if (p.type == PlaylistType.M3U_FILE) dao.setUrl(id, files.copyFile(id).absolutePath)
            ids[i] = id
            val same = entity.identity()?.let { unmatched[it]?.removeFirstOrNull() }
            p.guideLinks.forEachIndexed { position, url -> links += EpgSourceEntity(playlistId = id, url = url, position = position) }
            groupEdits += p.groupEdits.map { it.toEntity(id) }.filterNot { it.isDefault() }
            channelEdits += mergeChannelEdits(id, p.channelEdits, same?.let { oldPrefs[it.id] }.orEmpty())
            same?.let { s -> oldHistory[s.id].orEmpty().mapTo(history) { it.copy(playlistId = id) } }
        }
        dao.insertEpgSources(links)
        dao.insertGroupPrefs(groupEdits)
        dao.insertChannelPrefs(channelEdits)
        dao.insertFavourites(
            b.favourites.mapIndexed { i, f ->
                FavouriteEntity(ids[f.playlist], f.key, position = i + 1L, addedAt = Instant.ofEpochMilli(f.addedAtMs))
            },
        )
        dao.insertHistory(history)
        // The same upkeep as a playlist delete.
        overlay.renumber()
        db.maintenanceDao().deleteUnreferencedProgrammes()
        return Ids(ids, old)
    }

    /** The copied edits, plus this TV's own decoder and external player choices for the same playlist. */
    private fun mergeChannelEdits(id: Long, edits: List<SetupChannelEdit>, kept: List<ChannelPrefsEntity>): List<ChannelPrefsEntity> {
        val byKey = edits.associateBy { it.key }
        val keptByKey = kept.associateBy { it.channelKey }
        return (byKey.keys + keptByKey.keys).mapNotNull { key ->
            val e = byKey[key]
            val k = keptByKey[key]
            ChannelPrefsEntity(
                playlistId = id,
                channelKey = key,
                hidden = e?.hidden ?: false,
                customName = e?.customName,
                customLogo = e?.customLogo,
                epgOverride = e?.epgOverride,
                epgShiftMinutes = e?.epgShiftMinutes ?: 0,
                position = e?.position,
                videoDecoder = k?.videoDecoder,
                audioDecoder = k?.audioDecoder,
                externalPlayer = k?.externalPlayer,
                aspectMode = e?.aspectMode,
            ).takeUnless { it.isDefault() }
        }
    }

    /** A local file's location is set once its id (and so its copy's path) is known. */
    private fun SetupPlaylist.toEntity() = PlaylistEntity(
        name = name,
        type = type,
        url = if (type == PlaylistType.M3U_FILE) null else url,
        xtreamServer = xtreamServer,
        xtreamUser = xtreamUser,
        xtreamPass = xtreamPass,
        epgUrlsFromSource = "",
        manualEpgUrl = manualEpgUrl,
        lastRefreshedAt = null,
        lastError = null,
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
    )

    private fun SetupGroupEdit.toEntity(playlistId: Long) = GroupPrefsEntity(
        playlistId = playlistId,
        groupTitle = title,
        hidden = hidden,
        customName = customName,
        position = position,
        sortMode = sortMode,
    )

    /**
     * Which playlist this is, across TVs: its type and address (and login or MAC). Null for a
     * local file, whose location means nothing on another TV.
     */
    private fun PlaylistEntity.identity(): String? = when (type) {
        PlaylistType.M3U -> url?.trim()?.let { "m3u\n$it" }
        PlaylistType.XTREAM -> {
            val server = xtreamServer?.trim()?.trimEnd('/')
            if (server == null || xtreamUser == null) null else "xtream\n$server\n${xtreamUser.trim()}"
        }
        PlaylistType.STALKER -> url?.trim()?.let { "portal\n$it\n${mac?.trim()?.uppercase().orEmpty()}" }
        PlaylistType.M3U_FILE -> null
    }

    private companion object {
        /** Shared by every importer: one import at a time per process. */
        val LOCK = Mutex()
    }
}
