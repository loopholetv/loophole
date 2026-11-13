package io.github.loopholetv.setup

import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import io.github.loopholetv.data.db.GroupSort
import io.github.loopholetv.data.db.PlaylistType
import io.github.loopholetv.data.settings.AppSettings
import io.github.loopholetv.data.settings.AspectMode
import io.github.loopholetv.data.settings.BufferPreset
import io.github.loopholetv.data.settings.RemoteSettings
import io.github.loopholetv.data.settings.SubtitleBackground
import io.github.loopholetv.data.settings.SubtitleColour
import io.github.loopholetv.data.settings.SubtitleEdge
import io.github.loopholetv.data.settings.SubtitleSize
import io.github.loopholetv.data.settings.SubtitleStyle
import io.github.loopholetv.data.settings.decodeBindings
import io.github.loopholetv.data.settings.encodeBindings
import okio.Buffer
import okio.BufferedSink
import okio.ByteString.Companion.decodeBase64
import okio.Sink
import okio.Timeout
import okio.buffer
import okio.sink
import okio.source
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Largest local playlist file a setup carries; a bigger one is left out and counted. */
const val MAX_SETUP_FILE_BYTES = 20 * 1024 * 1024

/**
 * Most file content one setup carries in all, so the encoded bundle stays well under the transfer
 * limit and within a TV's heap; files past it are left out and counted like large ones.
 */
const val MAX_SETUP_TOTAL_FILE_BYTES = 40 * 1024 * 1024

/**
 * A TV's setup as sent to another TV. Channels and programmes are not in it: they come from the
 * refresh after the import. Playlists are referred to by their position in [playlists], and
 * channels by the stable key edits and favourites already use, so both re-attach on the new TV.
 *
 * Holds playlist addresses, logins and file contents: [toString] (here and in every part) never
 * prints them.
 */
data class SetupBundle(
    val version: Int = VERSION,
    /** The sending TV's name, for the outcome message. */
    val fromName: String,
    val playlists: List<SetupPlaylist>,
    /** Every favourite in the user's order. */
    val favourites: List<SetupFavourite>,
    val settings: SetupSettings,
    /** Local-file playlists the sending TV left out (too large or unreadable). */
    val skippedFiles: Int = 0,
) {
    override fun toString() =
        "SetupBundle(version=$version, playlists=${playlists.size}, favourites=${favourites.size}, skippedFiles=$skippedFiles)"

    companion object {
        const val VERSION = 1
    }
}

/**
 * One playlist with its logins and its own settings. For [PlaylistType.M3U_FILE] the address is
 * not sent (it means nothing on another TV): [file] carries the contents instead.
 */
data class SetupPlaylist(
    val name: String,
    val type: PlaylistType,
    val url: String? = null,
    val xtreamServer: String? = null,
    val xtreamUser: String? = null,
    val xtreamPass: String? = null,
    /** The older single guide link column, carried as stored. */
    val manualEpgUrl: String? = null,
    val enabled: Boolean = true,
    val userAgent: String? = null,
    val updateHours: Int? = null,
    val updateOnStart: Boolean? = null,
    val useSourceEpg: Boolean = true,
    val mac: String? = null,
    val portalLogin: String? = null,
    val portalPassword: String? = null,
    val portalSerial: String? = null,
    val portalDeviceId: String? = null,
    val portalDeviceId2: String? = null,
    val portalSignature: String? = null,
    val file: SetupFile? = null,
    /** Added guide links, in order. */
    val guideLinks: List<String> = emptyList(),
    val channelEdits: List<SetupChannelEdit> = emptyList(),
    val groupEdits: List<SetupGroupEdit> = emptyList(),
) {
    override fun toString() =
        "SetupPlaylist(type=$type, file=${file != null}, guideLinks=${guideLinks.size}, " +
            "channelEdits=${channelEdits.size}, groupEdits=${groupEdits.size})"
}

/**
 * A local playlist file's contents: in memory, or (for large setups) staged in a temp file so it
 * is never held whole in memory. A staged file belongs to the bundle: the import moves it into
 * place, or [SetupBundle.discardFiles] removes it.
 */
class SetupFile private constructor(private val bytes: ByteArray?, internal val staged: File?, val size: Long) {
    constructor(bytes: ByteArray) : this(bytes, null, bytes.size.toLong())
    internal constructor(staged: File, size: Long) : this(null, staged, size)

    fun open(): InputStream = bytes?.inputStream() ?: requireNotNull(staged).inputStream()

    fun readBytes(): ByteArray = open().use { it.readBytes() }

    /** Compares contents (reads staged files: meant for tests). */
    override fun equals(other: Any?) = other is SetupFile && other.size == size && other.readBytes().contentEquals(readBytes())
    override fun hashCode() = size.hashCode()
    override fun toString() = "SetupFile(bytes=$size)"
}

/** Removes the bundle's staged files (when it won't be imported, or after sending it). */
fun SetupBundle.discardFiles() {
    playlists.forEach { p -> p.file?.staged?.let { runCatching { it.delete() } } }
}

/** The copied part of a channel's edits ([SetupFields.CHANNEL_EDIT_COPIED]); [key] is the channel's stable key. */
data class SetupChannelEdit(
    val key: String,
    val hidden: Boolean = false,
    val customName: String? = null,
    val customLogo: String? = null,
    val epgOverride: String? = null,
    val epgShiftMinutes: Int = 0,
    val position: Int? = null,
    val aspectMode: AspectMode? = null,
) {
    override fun toString() = "SetupChannelEdit(hidden=$hidden, renamed=${customName != null}, shift=$epgShiftMinutes)"
}

/** A group's edits, keyed by its original title. */
data class SetupGroupEdit(
    val title: String,
    val hidden: Boolean = false,
    val customName: String? = null,
    val position: Int? = null,
    val sortMode: GroupSort = GroupSort.PLAYLIST,
) {
    override fun toString() = "SetupGroupEdit(hidden=$hidden, renamed=${customName != null}, sort=$sortMode)"
}

/** A favourite: [playlist] is a position in [SetupBundle.playlists]. */
data class SetupFavourite(val playlist: Int, val key: String, val addedAtMs: Long) {
    override fun toString() = "SetupFavourite(playlist=$playlist)"
}

/**
 * The copied settings, field by field ([SetupFields] lists what is and isn't copied). [applyTo]
 * changes only these, so this TV's identity, personal state and hardware choices stay.
 */
data class SetupSettings(
    val playlistUpdateHours: Int,
    val playlistUpdateOnStart: Boolean,
    val lastChannelOnStart: Boolean,
    val confirmExit: Boolean,
    val checkForUpdates: Boolean,
    val userAgent: String?,
    val udpProxy: String?,
    val epgUpdateHours: Int,
    val epgUpdateOnStart: Boolean,
    val epgUpdateOnPlaylistChange: Boolean,
    val epgPastDays: Int,
    val epgStoreDescriptions: Boolean,
    val bufferPreset: BufferPreset,
    val audioLanguage: String?,
    val subtitleLanguage: String?,
    val subtitleStyle: SubtitleStyle,
    val pipOnHome: Boolean,
    val remote: RemoteSettings,
) {
    fun applyTo(s: AppSettings): AppSettings = s.copy(
        playlistUpdateHours = playlistUpdateHours,
        playlistUpdateOnStart = playlistUpdateOnStart,
        lastChannelOnStart = lastChannelOnStart,
        confirmExit = confirmExit,
        checkForUpdates = checkForUpdates,
        userAgent = userAgent,
        udpProxy = udpProxy,
        epgUpdateHours = epgUpdateHours,
        epgUpdateOnStart = epgUpdateOnStart,
        epgUpdateOnPlaylistChange = epgUpdateOnPlaylistChange,
        epgPastDays = epgPastDays,
        epgStoreDescriptions = epgStoreDescriptions,
        playback = s.playback.copy(
            bufferPreset = bufferPreset,
            audioLanguage = audioLanguage,
            subtitleLanguage = subtitleLanguage,
            subtitleStyle = subtitleStyle,
            pipOnHome = pipOnHome,
        ),
        remote = remote,
    )

    override fun toString() = "SetupSettings(<hidden>)"

    companion object {
        fun from(s: AppSettings) = SetupSettings(
            playlistUpdateHours = s.playlistUpdateHours,
            playlistUpdateOnStart = s.playlistUpdateOnStart,
            lastChannelOnStart = s.lastChannelOnStart,
            confirmExit = s.confirmExit,
            checkForUpdates = s.checkForUpdates,
            userAgent = s.userAgent,
            udpProxy = s.udpProxy,
            epgUpdateHours = s.epgUpdateHours,
            epgUpdateOnStart = s.epgUpdateOnStart,
            epgUpdateOnPlaylistChange = s.epgUpdateOnPlaylistChange,
            epgPastDays = s.epgPastDays,
            epgStoreDescriptions = s.epgStoreDescriptions,
            bufferPreset = s.playback.bufferPreset,
            audioLanguage = s.playback.audioLanguage,
            subtitleLanguage = s.playback.subtitleLanguage,
            subtitleStyle = s.playback.subtitleStyle,
            pipOnHome = s.playback.pipOnHome,
            remote = s.remote,
        )
    }
}

/**
 * Every stored field, sorted into copied and not copied. A test fails when a new field is in
 * neither list, so each one is a deliberate choice.
 */
internal object SetupFields {
    val APP_COPIED = setOf(
        "playlistUpdateHours", "playlistUpdateOnStart", "lastChannelOnStart", "confirmExit", "checkForUpdates",
        "userAgent", "udpProxy", "epgUpdateHours", "epgUpdateOnStart", "epgUpdateOnPlaylistChange", "epgPastDays",
        "epgStoreDescriptions", "remote",
        // Split field by field below.
        "playback",
    )

    /** This TV's identity and personal state. */
    val APP_NOT_COPIED = setOf("lastGuideScope", "castAllowJoin", "castTvName", "onboardingSkipped")

    val PLAYBACK_COPIED = setOf("bufferPreset", "audioLanguage", "subtitleLanguage", "subtitleStyle", "pipOnHome")

    /** Hardware-specific. */
    val PLAYBACK_NOT_COPIED = setOf(
        "videoDecoder", "colourMode", "audioDecoder", "autoFrameRate", "preferSurround", "passthrough", "decodeDtsToPcm",
        "tunneling", "audioDelayMs",
    )

    /** Sent field by field; a new one must be added to the wire form too. */
    val SUBTITLE_STYLE_COPIED = setOf("size", "colour", "background", "edge")

    val REMOTE_COPIED = setOf("mediaKeysSeek", "rwRewindsLive", "leftRightSeek", "leftRewindsLive", "bindings")

    /** `url` is not sent for a local-file playlist: its contents are. */
    val PLAYLIST_COPIED = setOf(
        "name", "type", "url", "xtreamServer", "xtreamUser", "xtreamPass", "manualEpgUrl", "enabled", "userAgent", "updateHours",
        "updateOnStart", "useSourceEpg", "mac", "portalLogin", "portalPassword", "portalSerial", "portalDeviceId",
        "portalDeviceId2", "portalSignature",
    )

    /**
     * The row id (new on import), values the next refresh or login fills in again, the key
     * carry-over flag and the portal session (each TV makes its own).
     */
    val PLAYLIST_NOT_COPIED = setOf(
        "id", "epgUrlsFromSource", "lastRefreshedAt", "lastError", "rekeyPending",
        "portalEndpoint", "portalToken", "xtreamTimeZone", "xtreamArchiveExt",
    )

    val CHANNEL_EDIT_COPIED = setOf(
        "playlistId", "channelKey", "hidden", "customName", "customLogo", "epgOverride", "epgShiftMinutes",
        "position", "aspectMode",
    )

    /** Hardware-specific decoders, and the per-channel external player. */
    val CHANNEL_EDIT_NOT_COPIED = setOf("videoDecoder", "audioDecoder", "externalPlayer")

    val GROUP_EDIT_COPIED = setOf("playlistId", "groupTitle", "hidden", "customName", "position", "sortMode")
}

/**
 * The setup's wire form: one JSON object written and read with Moshi's streaming API. File
 * contents are Base64.
 */
object SetupJson {
    /** Bytes per Base64 chunk; a multiple of 3, so the chunks join into one valid Base64 text. */
    private const val BASE64_CHUNK = 3 * 16 * 1024

    /** Base64 characters decoded at a time; a multiple of 4, so each chunk decodes on its own. */
    private const val BASE64_TEXT_CHUNK = 4 * 16 * 1024L

    private const val QUOTE = '"'.code.toByte()

    /**
     * Writes the JSON twice: once only to count its bytes, then into an array of exactly that
     * size. The peak is the bundle plus one copy of its text, never a buffer and a copy of it.
     */
    fun encode(b: SetupBundle): ByteArray {
        val counter = CountingSink()
        write(b, counter.buffer())
        require(counter.count <= Int.MAX_VALUE - 8) { "Setup too large" }
        val out = ByteArray(counter.count.toInt())
        write(b, ArrayOutputStream(out).sink().buffer())
        return out
    }

    private fun write(b: SetupBundle, sink: BufferedSink) {
        JsonWriter.of(sink).use { w ->
            w.serializeNulls = true
            w.beginObject()
            w.name("version").value(b.version.toLong())
            w.name("fromName").value(b.fromName)
            w.name("skippedFiles").value(b.skippedFiles.toLong())
            w.name("settings")
            writeSettings(w, b.settings)
            w.name("playlists").beginArray()
            b.playlists.forEach { writePlaylist(w, it) }
            w.endArray()
            w.name("favourites").beginArray()
            b.favourites.forEach { f ->
                w.beginObject()
                w.name("playlist").value(f.playlist.toLong())
                w.name("key").value(f.key)
                w.name("addedAt").value(f.addedAtMs)
                w.endObject()
            }
            w.endArray()
            w.endObject()
        }
    }

    private class CountingSink : Sink {
        var count = 0L
            private set

        override fun write(source: Buffer, byteCount: Long) {
            count += byteCount
            source.skip(byteCount)
        }

        override fun flush() = Unit
        override fun timeout(): Timeout = Timeout.NONE
        override fun close() = Unit
    }

    /** Fills [array] exactly; a size mismatch means the two passes disagreed. */
    private class ArrayOutputStream(private val array: ByteArray) : OutputStream() {
        private var position = 0

        override fun write(b: Int) {
            if (position >= array.size) throw IOException("Setup size changed while writing")
            array[position++] = b.toByte()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len > array.size - position) throw IOException("Setup size changed while writing")
            System.arraycopy(b, off, array, position, len)
            position += len
        }

        override fun close() {
            if (position != array.size) throw IOException("Setup size changed while writing")
        }
    }

    /** [decode] from an array. */
    fun decode(
        bytes: ByteArray,
        stage: () -> Pair<File, OutputStream>?,
        maxFileBytes: Int = MAX_SETUP_FILE_BYTES,
        maxTotalFileBytes: Int = MAX_SETUP_TOTAL_FILE_BYTES,
    ): SetupBundle? = decode(bytes.inputStream(), stage, maxFileBytes, maxTotalFileBytes)

    /**
     * Reads a bundle from [input] (closed when done). Each file's contents are Base64-decoded in
     * chunks straight into a temp file from [stage] (null: none could be made), so neither a file
     * nor its text is ever held whole in memory. A file past [maxFileBytes], or past
     * [maxTotalFileBytes] with the earlier ones, is dropped as it is written and its playlist is
     * left out and counted in [SetupBundle.skippedFiles].
     *
     * Null on garbage, a missing or malformed field, or a version other than [SetupBundle.VERSION]
     * (checked first, before anything else is read). On null every staged temp is gone.
     */
    fun decode(
        input: InputStream,
        stage: () -> Pair<File, OutputStream>?,
        maxFileBytes: Int = MAX_SETUP_FILE_BYTES,
        maxTotalFileBytes: Int = MAX_SETUP_TOTAL_FILE_BYTES,
    ): SetupBundle? {
        val d = Decoding(stage, maxFileBytes.toLong(), maxTotalFileBytes.toLong())
        val bundle = try {
            JsonReader.of(input.source().buffer()).use { r ->
                val read = readBundle(r, d)
                if (r.peek() != JsonReader.Token.END_DOCUMENT) null else read?.withoutDropped()?.takeIf { it.isConsistent() }
            }
        } catch (_: Exception) {
            // JsonDataException, JsonEncodingException (an IOException), NumberFormatException, a
            // full disk and the like: all mean no setup this version can use.
            null
        }
        if (bundle == null) d.staged.forEach { runCatching { it.delete() } }
        return bundle
    }

    /** One decode's file staging. */
    private class Decoding(val stage: () -> Pair<File, OutputStream>?, val maxFile: Long, val maxTotal: Long) {
        val staged = ArrayList<File>()
        var total = 0L
    }

    /** Marks a file playlist whose contents were past the caps (compared by identity). */
    private val DROPPED = SetupFile(ByteArray(0))

    /** Leaves out the playlists whose file was dropped, and their favourites. */
    private fun SetupBundle.withoutDropped(): SetupBundle {
        val keep = playlists.indices.filter { playlists[it].file !== DROPPED }
        if (keep.size == playlists.size) return this
        val position = keep.withIndex().associate { (newIndex, oldIndex) -> oldIndex to newIndex }
        return copy(
            playlists = keep.map { playlists[it] },
            favourites = favourites.mapNotNull { f -> position[f.playlist]?.let { f.copy(playlist = it) } },
            skippedFiles = skippedFiles + (playlists.size - keep.size),
        )
    }

    private fun SetupBundle.isConsistent(): Boolean =
        version == SetupBundle.VERSION &&
            skippedFiles >= 0 &&
            favourites.all { it.playlist in playlists.indices } &&
            playlists.all { (it.type == PlaylistType.M3U_FILE) == (it.file != null) }

    // --- writing ---

    private fun writeSettings(w: JsonWriter, s: SetupSettings) {
        w.beginObject()
        w.name("playlistUpdateHours").value(s.playlistUpdateHours.toLong())
        w.name("playlistUpdateOnStart").value(s.playlistUpdateOnStart)
        w.name("lastChannelOnStart").value(s.lastChannelOnStart)
        w.name("confirmExit").value(s.confirmExit)
        w.name("checkForUpdates").value(s.checkForUpdates)
        w.name("userAgent").value(s.userAgent)
        w.name("udpProxy").value(s.udpProxy)
        w.name("epgUpdateHours").value(s.epgUpdateHours.toLong())
        w.name("epgUpdateOnStart").value(s.epgUpdateOnStart)
        w.name("epgUpdateOnPlaylistChange").value(s.epgUpdateOnPlaylistChange)
        w.name("epgPastDays").value(s.epgPastDays.toLong())
        w.name("epgStoreDescriptions").value(s.epgStoreDescriptions)
        w.name("bufferPreset").value(s.bufferPreset.name)
        w.name("audioLanguage").value(s.audioLanguage)
        w.name("subtitleLanguage").value(s.subtitleLanguage)
        w.name("subtitleSize").value(s.subtitleStyle.size.name)
        w.name("subtitleColour").value(s.subtitleStyle.colour.name)
        w.name("subtitleBackground").value(s.subtitleStyle.background.name)
        w.name("subtitleEdge").value(s.subtitleStyle.edge.name)
        w.name("pipOnHome").value(s.pipOnHome)
        w.name("mediaKeysSeek").value(s.remote.mediaKeysSeek)
        w.name("rwRewindsLive").value(s.remote.rwRewindsLive)
        w.name("leftRightSeek").value(s.remote.leftRightSeek)
        w.name("leftRewindsLive").value(s.remote.leftRewindsLive)
        w.name("bindings").value(encodeBindings(s.remote.bindings))
        w.endObject()
    }

    private fun writePlaylist(w: JsonWriter, p: SetupPlaylist) {
        w.beginObject()
        w.name("name").value(p.name)
        w.name("type").value(p.type.name)
        w.name("url").value(p.url)
        w.name("xtreamServer").value(p.xtreamServer)
        w.name("xtreamUser").value(p.xtreamUser)
        w.name("xtreamPass").value(p.xtreamPass)
        w.name("manualEpgUrl").value(p.manualEpgUrl)
        w.name("enabled").value(p.enabled)
        w.name("userAgent").value(p.userAgent)
        w.name("updateHours").value(p.updateHours?.toLong())
        w.name("updateOnStart").value(p.updateOnStart)
        w.name("useSourceEpg").value(p.useSourceEpg)
        w.name("mac").value(p.mac)
        w.name("portalLogin").value(p.portalLogin)
        w.name("portalPassword").value(p.portalPassword)
        w.name("portalSerial").value(p.portalSerial)
        w.name("portalDeviceId").value(p.portalDeviceId)
        w.name("portalDeviceId2").value(p.portalDeviceId2)
        w.name("portalSignature").value(p.portalSignature)
        w.name("file")
        val file = p.file
        if (file == null) {
            w.nullValue()
        } else {
            w.beginObject()
            w.name("data")
            writeBase64(w, file)
            w.endObject()
        }
        w.name("guideLinks").beginArray()
        p.guideLinks.forEach { w.value(it) }
        w.endArray()
        w.name("channelEdits").beginArray()
        p.channelEdits.forEach { e ->
            w.beginObject()
            w.name("key").value(e.key)
            w.name("hidden").value(e.hidden)
            w.name("customName").value(e.customName)
            w.name("customLogo").value(e.customLogo)
            w.name("epgOverride").value(e.epgOverride)
            w.name("epgShiftMinutes").value(e.epgShiftMinutes.toLong())
            w.name("position").value(e.position?.toLong())
            w.name("aspectMode").value(e.aspectMode?.name)
            w.endObject()
        }
        w.endArray()
        w.name("groupEdits").beginArray()
        p.groupEdits.forEach { g ->
            w.beginObject()
            w.name("title").value(g.title)
            w.name("hidden").value(g.hidden)
            w.name("customName").value(g.customName)
            w.name("position").value(g.position?.toLong())
            w.name("sortMode").value(g.sortMode.name)
            w.endObject()
        }
        w.endArray()
        w.endObject()
    }

    /** Streams the file's Base64 text in chunks (a staged file is read from disk as it goes). */
    private fun writeBase64(w: JsonWriter, file: SetupFile) {
        w.valueSink().use { sink ->
            sink.writeUtf8("\"")
            file.open().source().buffer().use { source ->
                var left = file.size
                while (left > 0) {
                    val n = minOf(BASE64_CHUNK.toLong(), left)
                    sink.writeUtf8(source.readByteString(n).base64())
                    left -= n
                }
            }
            sink.writeUtf8("\"")
        }
    }

    // --- reading ---

    private fun readBundle(r: JsonReader, d: Decoding): SetupBundle? {
        var fromName: String? = null
        var skipped = 0
        var settings: SetupSettings? = null
        var playlists: List<SetupPlaylist>? = null
        var favourites: List<SetupFavourite>? = null
        r.beginObject()
        // The version comes first: another version is turned away before anything else is read.
        if (!r.hasNext() || r.nextName() != "version") return null
        if (r.peek() != JsonReader.Token.NUMBER || r.nextLong() != SetupBundle.VERSION.toLong()) return null
        while (r.hasNext()) {
            when (r.nextName()) {
                "version" -> throw JsonDataException("repeated version")
                "fromName" -> fromName = r.string()
                "skippedFiles" -> skipped = r.nextInt()
                "settings" -> settings = readSettings(r)
                "playlists" -> playlists = r.list { readPlaylist(it, d) }
                "favourites" -> favourites = r.list { readFavourite(it) }
                else -> r.skipValue()
            }
        }
        r.endObject()
        return SetupBundle(
            version = SetupBundle.VERSION,
            fromName = fromName ?: return null,
            playlists = playlists ?: return null,
            favourites = favourites ?: return null,
            settings = settings ?: return null,
            skippedFiles = skipped,
        )
    }

    private fun readSettings(r: JsonReader): SetupSettings {
        val v = r.fields()
        val d = AppSettings()
        val dp = d.playback
        val ds = dp.subtitleStyle
        val dr = d.remote
        return SetupSettings(
            playlistUpdateHours = v.int("playlistUpdateHours") ?: d.playlistUpdateHours,
            playlistUpdateOnStart = v.bool("playlistUpdateOnStart") ?: d.playlistUpdateOnStart,
            lastChannelOnStart = v.bool("lastChannelOnStart") ?: d.lastChannelOnStart,
            confirmExit = v.bool("confirmExit") ?: d.confirmExit,
            checkForUpdates = v.bool("checkForUpdates") ?: d.checkForUpdates,
            userAgent = v.string("userAgent"),
            udpProxy = v.string("udpProxy"),
            epgUpdateHours = v.int("epgUpdateHours") ?: d.epgUpdateHours,
            epgUpdateOnStart = v.bool("epgUpdateOnStart") ?: d.epgUpdateOnStart,
            epgUpdateOnPlaylistChange = v.bool("epgUpdateOnPlaylistChange") ?: d.epgUpdateOnPlaylistChange,
            epgPastDays = v.int("epgPastDays") ?: d.epgPastDays,
            epgStoreDescriptions = v.bool("epgStoreDescriptions") ?: d.epgStoreDescriptions,
            bufferPreset = v.enum("bufferPreset", dp.bufferPreset),
            audioLanguage = v.string("audioLanguage"),
            subtitleLanguage = v.string("subtitleLanguage"),
            subtitleStyle = SubtitleStyle(
                size = v.enum<SubtitleSize>("subtitleSize", ds.size),
                colour = v.enum<SubtitleColour>("subtitleColour", ds.colour),
                background = v.enum<SubtitleBackground>("subtitleBackground", ds.background),
                edge = v.enum<SubtitleEdge>("subtitleEdge", ds.edge),
            ),
            pipOnHome = v.bool("pipOnHome") ?: dp.pipOnHome,
            remote = RemoteSettings(
                mediaKeysSeek = v.bool("mediaKeysSeek") ?: dr.mediaKeysSeek,
                rwRewindsLive = v.bool("rwRewindsLive") ?: dr.rwRewindsLive,
                leftRightSeek = v.bool("leftRightSeek") ?: dr.leftRightSeek,
                leftRewindsLive = v.bool("leftRewindsLive") ?: dr.leftRewindsLive,
                bindings = decodeBindings(v.string("bindings")),
            ),
        )
    }

    private fun readPlaylist(r: JsonReader, d: Decoding): SetupPlaylist {
        val fields = HashMap<String, Any?>()
        var file: SetupFile? = null
        var links: List<String> = emptyList()
        var channelEdits: List<SetupChannelEdit> = emptyList()
        var groupEdits: List<SetupGroupEdit> = emptyList()
        r.beginObject()
        while (r.hasNext()) {
            when (val name = r.nextName()) {
                "file" -> file = if (r.peek() == JsonReader.Token.NULL) r.nextNull() else readFile(r, d)
                "guideLinks" -> links = r.list { it.string() ?: throw JsonDataException("null link") }
                "channelEdits" -> channelEdits = r.list { readChannelEdit(it) }
                "groupEdits" -> groupEdits = r.list { readGroupEdit(it) }
                else -> fields[name] = r.scalar()
            }
        }
        r.endObject()
        val v = Fields(fields)
        return SetupPlaylist(
            name = v.string("name") ?: throw JsonDataException("no name"),
            type = PlaylistType.entries.firstOrNull { it.name == v.string("type") } ?: throw JsonDataException("type"),
            url = v.string("url"),
            xtreamServer = v.string("xtreamServer"),
            xtreamUser = v.string("xtreamUser"),
            xtreamPass = v.string("xtreamPass"),
            manualEpgUrl = v.string("manualEpgUrl"),
            enabled = v.bool("enabled") ?: true,
            userAgent = v.string("userAgent"),
            updateHours = v.int("updateHours"),
            updateOnStart = v.bool("updateOnStart"),
            useSourceEpg = v.bool("useSourceEpg") ?: true,
            mac = v.string("mac"),
            portalLogin = v.string("portalLogin"),
            portalPassword = v.string("portalPassword"),
            portalSerial = v.string("portalSerial"),
            portalDeviceId = v.string("portalDeviceId"),
            portalDeviceId2 = v.string("portalDeviceId2"),
            portalSignature = v.string("portalSignature"),
            file = file,
            guideLinks = links,
            channelEdits = channelEdits,
            groupEdits = groupEdits,
        )
    }

    private fun readFile(r: JsonReader, d: Decoding): SetupFile {
        var file: SetupFile? = null
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "data" -> {
                    if (file != null) throw JsonDataException("repeated data")
                    file = stageBase64(r, d)
                }
                else -> r.skipValue()
            }
        }
        r.endObject()
        return file ?: throw JsonDataException("no data")
    }

    /** Decodes the Base64 value into a new temp file; [DROPPED] (temp removed) past the caps. */
    private fun stageBase64(r: JsonReader, d: Decoding): SetupFile {
        val (temp, out) = d.stage() ?: throw IOException("No temp file")
        d.staged += temp
        val budget = minOf(d.maxFile, d.maxTotal - d.total)
        val written = out.use { readBase64(r, it, budget) }
        if (written < 0) {
            temp.delete()
            d.staged -= temp
            return DROPPED
        }
        d.total += written
        return SetupFile(temp, written)
    }

    /**
     * Decodes a Base64 string value chunk by chunk from the raw JSON into [out], so neither the
     * text nor the contents are ever held whole. Returns the bytes written, or -1 once they would
     * pass [budget] (writing stops; the rest is still read and checked). Base64 needs no JSON
     * escapes; anything else in it is malformed.
     */
    private fun readBase64(r: JsonReader, out: OutputStream, budget: Long): Long {
        if (r.peek() != JsonReader.Token.STRING) throw JsonDataException("expected Base64")
        r.nextSource().use { raw ->
            if (raw.readByte() != QUOTE) throw JsonDataException("expected Base64")
            var written = 0L
            var over = false
            while (true) {
                // Looks no further than one chunk ahead, so at most a chunk is buffered.
                val end = raw.indexOf(QUOTE, 0, BASE64_TEXT_CHUNK)
                val n = when {
                    end >= 0 -> end
                    raw.request(BASE64_TEXT_CHUNK) -> BASE64_TEXT_CHUNK
                    else -> throw JsonDataException("unterminated")
                }
                if (n % 4 != 0L) throw JsonDataException("bad Base64")
                if (n > 0) {
                    val chunk = raw.readUtf8(n).decodeBase64() ?: throw JsonDataException("bad Base64")
                    if (!over && written + chunk.size > budget) over = true
                    if (!over) {
                        chunk.write(out)
                        written += chunk.size
                    }
                }
                if (end >= 0) break
            }
            raw.readByte()
            if (!raw.exhausted()) throw JsonDataException("trailing data")
            return if (over) -1 else written
        }
    }

    private fun readChannelEdit(r: JsonReader): SetupChannelEdit {
        val v = r.fields()
        return SetupChannelEdit(
            key = v.string("key") ?: throw JsonDataException("no key"),
            hidden = v.bool("hidden") ?: false,
            customName = v.string("customName"),
            customLogo = v.string("customLogo"),
            epgOverride = v.string("epgOverride"),
            epgShiftMinutes = v.int("epgShiftMinutes") ?: 0,
            position = v.int("position"),
            aspectMode = v.string("aspectMode")?.let { s -> AspectMode.entries.firstOrNull { it.name == s } },
        )
    }

    private fun readGroupEdit(r: JsonReader): SetupGroupEdit {
        val v = r.fields()
        return SetupGroupEdit(
            title = v.string("title") ?: throw JsonDataException("no title"),
            hidden = v.bool("hidden") ?: false,
            customName = v.string("customName"),
            position = v.int("position"),
            sortMode = v.enum("sortMode", GroupSort.PLAYLIST),
        )
    }

    private fun readFavourite(r: JsonReader): SetupFavourite {
        val v = r.fields()
        return SetupFavourite(
            playlist = v.int("playlist") ?: throw JsonDataException("no playlist"),
            key = v.string("key") ?: throw JsonDataException("no key"),
            addedAtMs = v.long("addedAt") ?: 0,
        )
    }

    private fun <T> JsonReader.list(item: (JsonReader) -> T): List<T> {
        val out = ArrayList<T>()
        beginArray()
        while (hasNext()) out += item(this)
        endArray()
        return out
    }

    /** A string or null; any other token is malformed. */
    private fun JsonReader.string(): String? = when (peek()) {
        JsonReader.Token.NULL -> nextNull()
        JsonReader.Token.STRING -> nextString()
        else -> throw JsonDataException("expected a string")
    }

    /** A flat object of scalars (strings, whole numbers, booleans, nulls). */
    private fun JsonReader.fields(): Fields {
        val values = HashMap<String, Any?>()
        beginObject()
        while (hasNext()) values[nextName()] = scalar()
        endObject()
        return Fields(values)
    }

    private fun JsonReader.scalar(): Any? = when (peek()) {
        JsonReader.Token.STRING -> nextString()
        JsonReader.Token.NUMBER -> nextLong()
        JsonReader.Token.BOOLEAN -> nextBoolean()
        JsonReader.Token.NULL -> nextNull()
        else -> throw JsonDataException("expected a value")
    }

    /** Typed reads of [fields]: a missing or null value is null, a value of the wrong type is malformed. */
    private class Fields(private val values: Map<String, Any?>) {
        private inline fun <reified T> typed(key: String): T? = when (val v = values[key]) {
            null -> null
            is T -> v
            else -> throw JsonDataException("wrong type")
        }

        fun string(key: String): String? = typed(key)
        fun bool(key: String): Boolean? = typed(key)
        fun long(key: String): Long? = typed(key)
        fun int(key: String): Int? = long(key)?.let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else throw JsonDataException("range") }

        /** An unknown name (a newer version's value) gives [default]. */
        inline fun <reified E : Enum<E>> enum(key: String, default: E): E =
            string(key)?.let { s -> enumValues<E>().firstOrNull { it.name == s } } ?: default
    }
}
