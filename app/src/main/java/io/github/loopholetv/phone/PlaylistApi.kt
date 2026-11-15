package io.github.loopholetv.phone

import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import io.github.loopholetv.cast.net.HttpRequest
import io.github.loopholetv.cast.net.HttpResponse
import io.github.loopholetv.data.db.PlaylistEntity
import io.github.loopholetv.data.db.PlaylistType
import io.github.loopholetv.data.repo.ImportError
import io.github.loopholetv.data.repo.ImportResult
import io.github.loopholetv.data.repo.SourceEdit
import io.github.loopholetv.data.repo.errorCopy
import io.github.loopholetv.data.stalker.PortalConfig
import io.github.loopholetv.data.stalker.normaliseMac
import io.github.loopholetv.data.xtream.XtreamCredentials
import io.github.loopholetv.ui.onboarding.FormCheck
import io.github.loopholetv.ui.onboarding.INVALID_MAC
import io.github.loopholetv.ui.onboarding.Mode
import io.github.loopholetv.ui.onboarding.PlaylistFormInput
import io.github.loopholetv.ui.onboarding.checkPlaylistForm
import io.github.loopholetv.ui.onboarding.failureCopy
import io.github.loopholetv.ui.settings.storedErrorCopy
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The phone page's playlist routes. Adds and edits go through the TV form's own rules
 * ([checkPlaylistForm]) and run in [jobs]; passwords are never sent back, and an edit without one
 * keeps the stored one. Local files stay TV-only (the phone can't hand over a file).
 *
 * Turning a playlist on or off and deleting one run on [work], as Settings runs them, and the reply
 * goes straight back: either can wait behind a running load, and a request held open that long
 * would use up the phone's few connections.
 */
class PlaylistApi(
    private val jobs: PhoneJobs,
    /** Outlives the request (the app-wide scope in production). */
    private val work: CoroutineScope,
    private val playlists: suspend () -> List<PlaylistEntity>,
    private val channelCounts: suspend () -> Map<Long, Int>,
    private val playlist: suspend (id: Long) -> PlaylistEntity?,
    private val addM3u: suspend (name: String, url: String, progress: (Int) -> Unit) -> ImportResult,
    private val addXtream: suspend (name: String, creds: XtreamCredentials, progress: (Int) -> Unit) -> ImportResult,
    private val addStalker: suspend (name: String, cfg: PortalConfig, progress: (Int) -> Unit) -> ImportResult,
    private val refreshGuide: suspend (playlistId: Long, progress: (Int) -> Unit) -> Unit,
    /** Saves the new source and refreshes the playlist and its guide. */
    private val updateSource: suspend (playlistId: Long, edit: SourceEdit, progress: (Int) -> Unit) -> ImportResult,
    /** Refreshes the playlist only; the guide follows when [guideAfterRefresh] says so. */
    private val refreshPlaylist: suspend (playlistId: Long, progress: (Int) -> Unit) -> ImportResult,
    private val guideAfterRefresh: suspend (playlistId: Long) -> Boolean,
    private val setEnabled: suspend (playlistId: Long, enabled: Boolean) -> Unit,
    /** Runs after a playlist is turned on (it may load the playlist). */
    private val afterEnabled: suspend (playlistId: Long) -> Unit,
    private val delete: suspend (playlistId: Long) -> Unit,
) {
    /** Keeps on/off writes in the order the phone sent them. */
    private val enabling = Mutex()

    fun register(server: PhoneServer) {
        server.api("GET", PLAYLISTS, handler = ::get)
        server.api("POST", PLAYLISTS, handler = ::post)
        server.api("GET", JOB, handler = ::job)
    }

    private suspend fun get(req: HttpRequest, res: HttpResponse) {
        if (req.path == PLAYLISTS) return list(res)
        val (id, action) = target(req.path) ?: return res.sendError(404, NOT_FOUND)
        if (action != null) return res.sendError(404, NOT_FOUND)
        val p = playlist(id) ?: return res.sendError(404, NOT_FOUND)
        if (p.type == PlaylistType.M3U_FILE) return res.sendError(409, TV_ONLY)
        res.sendJson(200) { writeForm(p) }
    }

    private suspend fun post(req: HttpRequest, res: HttpResponse) {
        if (req.path == PLAYLISTS) return save(req, res, null)
        val (id, action) = target(req.path) ?: return res.sendError(404, NOT_FOUND)
        if (action != null && action !in ACTIONS) return res.sendError(404, NOT_FOUND)
        val p = playlist(id) ?: return res.sendError(404, NOT_FOUND)
        when (action) {
            null -> save(req, res, p)
            "refresh" -> res.sendJob(enqueueRefresh(p))
            else -> {
                if (jobs.isBusy(p.id)) return res.sendBusy(p)
                if (action == "delete") deleteAndReply(p, res) else enable(req, res, p)
            }
        }
    }

    private suspend fun list(res: HttpResponse) {
        val all = playlists()
        val counts = channelCounts()
        res.sendJson(200) {
            serializeNulls = true
            beginArray()
            for (p in all) {
                beginObject()
                name("id").value(p.id)
                name("name").value(p.name)
                name("kind").value(kindOf(p.type))
                name("enabled").value(p.enabled)
                name("lastRefreshedAt").value(p.lastRefreshedAt?.toEpochMilli())
                // Only fixed copy, as Settings shows it: the stored message is a raw import error.
                name("error").value(storedErrorCopy(p.lastError)?.takeIf { it.isError }?.text)
                name("channels").value(counts[p.id] ?: 0)
                endObject()
            }
            endArray()
        }
    }

    /** The form as the stored playlist fills it, without either password. */
    private fun JsonWriter.writeForm(p: PlaylistEntity) {
        val portal = p.type == PlaylistType.STALKER
        beginObject()
        name("mode").value(kindOf(p.type))
        name("name").value(p.name)
        name("url").value(if (p.type == PlaylistType.M3U) p.url.orEmpty() else "")
        name("server").value(p.xtreamServer.orEmpty())
        name("user").value(p.xtreamUser.orEmpty())
        name("portalUrl").value(if (portal) p.url.orEmpty() else "")
        name("mac").value(p.mac.orEmpty())
        name("portalLogin").value(p.portalLogin.orEmpty())
        name("serial").value(p.portalSerial.orEmpty())
        name("deviceId").value(p.portalDeviceId.orEmpty())
        name("deviceId2").value(p.portalDeviceId2.orEmpty())
        name("signature").value(p.portalSignature.orEmpty())
        name("hasPass").value(!p.xtreamPass.isNullOrEmpty())
        name("hasPortalPass").value(!p.portalPassword.isNullOrEmpty())
        endObject()
    }

    /** An add ([stored] null) or an edit of [stored]. */
    private fun save(req: HttpRequest, res: HttpResponse, stored: PlaylistEntity?) {
        val fields = readFields(req) ?: return res.sendError(400, BAD_REQUEST)
        val mode = MODES[fields["mode"]] ?: return res.sendError(400, BAD_REQUEST)
        if (mode == Mode.FILE) return res.sendError(400, TV_ONLY)
        if (stored?.type == PlaylistType.M3U_FILE) return res.sendError(409, TV_ONLY)
        if (stored != null && jobs.isBusy(stored.id)) return res.sendBusy(stored)
        when (val check = checkPlaylistForm(formInput(mode, fields, stored))) {
            FormCheck.Incomplete -> res.sendError(400, "incomplete")
            FormCheck.InvalidMac -> res.sendJson(400) {
                beginObject()
                name("error").value("invalid_mac")
                name("message").value(INVALID_MAC)
                endObject()
            }
            is FormCheck.Ok -> res.sendJob(if (stored == null) enqueueAdd(mode, check) else enqueueEdit(mode, stored, check))
        }
    }

    private fun enqueueAdd(mode: Mode, check: FormCheck.Ok): Long =
        jobs.enqueue(PhoneJobKind.ADD, null, check.name) { progress ->
            val channels = { n: Int -> progress(PhoneJobPhase.LoadingChannels(n)) }
            val result = when (val source = check.source) {
                is SourceEdit.M3uUrl -> addM3u(check.name, source.url, channels)
                is SourceEdit.Xtream -> addXtream(check.name, source.creds, channels)
                is SourceEdit.Portal -> addStalker(check.name, source.cfg, channels)
                is SourceEdit.LocalFile -> ImportResult.Failure(ImportError.Unexpected, "")
            }
            when (result) {
                is ImportResult.Failure -> PhoneJobPhase.Failed(failureCopy(mode, result))
                is ImportResult.Success -> {
                    // The new playlist is busy while its guide loads.
                    progress.claim(result.playlistId)
                    loadGuide(result.playlistId, progress)
                    PhoneJobPhase.Done(result.playlistId)
                }
                // A copied setup replaced the playlists meanwhile: this one was never kept.
                ImportResult.Stopped -> PhoneJobPhase.Failed(errorCopy(ImportError.Unexpected))
            }
        }

    /** The edited playlist keeps its name (renaming is its own action, as on the TV). */
    private fun enqueueEdit(mode: Mode, p: PlaylistEntity, check: FormCheck.Ok): Long =
        jobs.enqueue(PhoneJobKind.EDIT, p.id, p.name) { progress ->
            val result = updateSource(p.id, check.source) { n -> progress(PhoneJobPhase.LoadingChannels(n)) }
            finished(p.id, mode, result)
        }

    private fun enqueueRefresh(p: PlaylistEntity): Long =
        jobs.enqueue(PhoneJobKind.REFRESH, p.id, p.name) { progress ->
            val result = refreshPlaylist(p.id) { n -> progress(PhoneJobPhase.LoadingChannels(n)) }
            if (result is ImportResult.Success && guideAfterRefresh(p.id)) loadGuide(p.id, progress)
            finished(p.id, modeOf(p.type), result)
        }

    private fun finished(id: Long, mode: Mode, result: ImportResult): PhoneJobPhase = when (result) {
        is ImportResult.Failure -> PhoneJobPhase.Failed(failureCopy(mode, result))
        is ImportResult.Success, ImportResult.Stopped -> PhoneJobPhase.Done(id)
    }

    /** A guide error doesn't fail the job: the channels are in, and Settings shows the guide's status. */
    private suspend fun loadGuide(id: Long, progress: (PhoneJobPhase) -> Unit) {
        progress(PhoneJobPhase.LoadingGuide(0))
        try {
            refreshGuide(id) { n -> progress(PhoneJobPhase.LoadingGuide(n)) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Stored by the guide's repository.
        }
    }

    private fun deleteAndReply(p: PlaylistEntity, res: HttpResponse) {
        work.launch { delete(p.id) }
        res.sendJson(200) {
            beginObject()
            name("ok").value(true)
            endObject()
        }
    }

    private fun enable(req: HttpRequest, res: HttpResponse, p: PlaylistEntity) {
        val on = readEnabled(req) ?: return res.sendError(400, BAD_REQUEST)
        // Started here so it queues on [enabling] before the reply; the write itself waits there.
        work.launch(start = CoroutineStart.UNDISPATCHED) {
            enabling.withLock { setEnabled(p.id, on) }
            if (on) afterEnabled(p.id)
        }
        res.sendJson(200) {
            beginObject()
            name("enabled").value(on)
            endObject()
        }
    }

    private fun job(req: HttpRequest, res: HttpResponse) {
        if (req.path != JOB) return res.sendError(404, NOT_FOUND)
        // Queue first (a job leaves it only once it is current), so a job is never missed between the two.
        val queued = jobs.queued.value
        val job = jobs.current.value
        res.sendJson(200) {
            serializeNulls = true
            beginObject()
            if (job != null) {
                name("id").value(job.id)
                name("kind").value(job.kind.name.lowercase())
                name("playlistId").value(job.playlistId)
                name("name").value(job.name)
                val phase = job.phase
                name("phase").value(phaseName(phase))
                name("count").value(
                    when (phase) {
                        is PhoneJobPhase.LoadingChannels -> phase.count
                        is PhoneJobPhase.LoadingGuide -> phase.programmes
                        else -> null
                    },
                )
                name("message").value((phase as? PhoneJobPhase.Failed)?.message)
            }
            name("queued").value(queued)
            endObject()
        }
    }

    private fun HttpResponse.sendJob(id: Long) = sendJson(202) {
        beginObject()
        name("job").value(id)
        endObject()
    }

    private fun HttpResponse.sendBusy(p: PlaylistEntity) = sendJson(409) {
        beginObject()
        name("error").value("busy")
        name("message").value("Busy loading ${p.name}")
        endObject()
    }

    private companion object {
        const val PLAYLISTS = "/api/playlists"
        const val JOB = "/api/job"
        const val NOT_FOUND = "not_found"
        const val BAD_REQUEST = "bad_request"
        const val TV_ONLY = "tv_only"
        val ACTIONS = setOf("enabled", "refresh", "delete")
        val MODES = mapOf("m3u" to Mode.M3U, "file" to Mode.FILE, "xtream" to Mode.XTREAM, "portal" to Mode.PORTAL)
    }
}

/** `/api/playlists/<id>` or `/api/playlists/<id>/<action>` → id and action; null for anything else. */
private fun target(path: String): Pair<Long, String?>? {
    val parts = path.removePrefix("/api/playlists/").split('/')
    if (parts.size > 2 || parts.any { it.isEmpty() }) return null
    val id = parts[0].takeIf { s -> s.all { it in '0'..'9' } }?.toLongOrNull() ?: return null
    return id to parts.getOrNull(1)
}

private fun kindOf(type: PlaylistType) = when (type) {
    PlaylistType.M3U -> "m3u"
    PlaylistType.M3U_FILE -> "file"
    PlaylistType.XTREAM -> "xtream"
    PlaylistType.STALKER -> "portal"
}

private fun modeOf(type: PlaylistType) = when (type) {
    PlaylistType.M3U -> Mode.M3U
    PlaylistType.M3U_FILE -> Mode.FILE
    PlaylistType.XTREAM -> Mode.XTREAM
    PlaylistType.STALKER -> Mode.PORTAL
}

private fun phaseName(phase: PhoneJobPhase) = when (phase) {
    is PhoneJobPhase.LoadingChannels -> "channels"
    is PhoneJobPhase.LoadingGuide -> "guide"
    is PhoneJobPhase.Done -> "done"
    is PhoneJobPhase.Failed -> "failed"
}

/**
 * The form's text fields. A password missing or null on an edit keeps [stored]'s only while the
 * edit still points at the same login ([sameXtreamLogin], [samePortalLogin]); otherwise it is empty,
 * so a stored password is never sent to a new address.
 */
private fun formInput(mode: Mode, f: Map<String, String?>, stored: PlaylistEntity?): PlaylistFormInput {
    fun text(key: String) = f[key].orEmpty()
    val typed = PlaylistFormInput(
        mode = mode,
        name = text("name"),
        url = text("url"),
        server = text("server"),
        user = text("user"),
        pass = text("pass"),
        portalUrl = text("portalUrl"),
        mac = text("mac"),
        portalLogin = text("portalLogin"),
        portalPass = text("portalPass"),
        serial = text("serial"),
        deviceId = text("deviceId"),
        deviceId2 = text("deviceId2"),
        signature = text("signature"),
    )
    if (stored == null) return typed
    return typed.copy(
        pass = f["pass"] ?: stored.xtreamPass.takeIf { sameXtreamLogin(typed, stored) }.orEmpty(),
        portalPass = f["portalPass"] ?: stored.portalPassword.takeIf { samePortalLogin(typed, stored) }.orEmpty(),
    )
}

/** The same Xtream server (as the form normalises it) and user as [stored]'s. */
private fun sameXtreamLogin(input: PlaylistFormInput, stored: PlaylistEntity): Boolean =
    input.mode == Mode.XTREAM && stored.type == PlaylistType.XTREAM &&
        XtreamCredentials.normalizeServer(input.server.trim()) == stored.xtreamServer?.let { XtreamCredentials.normalizeServer(it.trim()) } &&
        input.user.trim() == stored.xtreamUser?.trim()

/** The same portal address, MAC and login (as the form normalises them) as [stored]'s. */
private fun samePortalLogin(input: PlaylistFormInput, stored: PlaylistEntity): Boolean {
    if (input.mode != Mode.PORTAL || stored.type != PlaylistType.STALKER) return false
    val mac = normaliseMac(input.mac) ?: return false
    return input.portalUrl.trim() == stored.url?.trim() &&
        mac == stored.mac?.let(::normaliseMac) &&
        input.portalLogin.trim().ifEmpty { null } == stored.portalLogin?.trim()?.ifEmpty { null }
}

/** A JSON object of strings and nulls (numbers read as text); null when the body is anything else. */
private fun readFields(req: HttpRequest): Map<String, String?>? = readBody(req) { r ->
    val out = HashMap<String, String?>()
    r.beginObject()
    while (r.hasNext()) {
        val key = r.nextName()
        out[key] = if (r.peek() == JsonReader.Token.NULL) r.nextNull<String>() else r.nextString()
    }
    r.endObject()
    out
}

/** `{"enabled":true|false}` → the value; null without a boolean. */
private fun readEnabled(req: HttpRequest): Boolean? = readBody(req) { r ->
    var on: Boolean? = null
    r.beginObject()
    while (r.hasNext()) {
        if (r.nextName() == "enabled" && r.peek() == JsonReader.Token.BOOLEAN) on = r.nextBoolean() else r.skipValue()
    }
    r.endObject()
    on
}

private fun <T> readBody(req: HttpRequest, read: (JsonReader) -> T?): T? = try {
    req.jsonBody().use(read)
} catch (_: IOException) {
    null
} catch (_: JsonDataException) {
    null
}
