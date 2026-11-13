package io.github.loopholetv.setup

import io.github.loopholetv.data.db.ChannelPrefsEntity
import io.github.loopholetv.data.db.GroupPrefsEntity
import io.github.loopholetv.data.db.GroupSort
import io.github.loopholetv.data.db.PlaylistEntity
import io.github.loopholetv.data.db.PlaylistType
import io.github.loopholetv.data.settings.AppSettings
import io.github.loopholetv.data.settings.AspectMode
import io.github.loopholetv.data.settings.BufferPreset
import io.github.loopholetv.data.settings.GuideAction
import io.github.loopholetv.data.settings.KeyBindings
import io.github.loopholetv.data.settings.PlaybackSettings
import io.github.loopholetv.data.settings.PlayerAction
import io.github.loopholetv.data.settings.RemoteSettings
import io.github.loopholetv.data.settings.SubtitleBackground
import io.github.loopholetv.data.settings.SubtitleColour
import io.github.loopholetv.data.settings.SubtitleEdge
import io.github.loopholetv.data.settings.SubtitleSize
import io.github.loopholetv.data.settings.SubtitleStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.OutputStream
import java.lang.reflect.Modifier

/** Marks every secret-bearing value, so a leak in toString is easy to spot. */
internal const val SECRET = "s3cr3t"

/** A bundle that sets every copied value away from its default. */
internal fun sampleBundle(secret: String = SECRET): SetupBundle = SetupBundle(
    fromName = "Living room",
    playlists = listOf(
        SetupPlaylist(
            name = "Main", type = PlaylistType.M3U, url = "http://m3u.example.test/$secret.m3u",
            manualEpgUrl = "http://legacy.example.test/$secret.xml",
            enabled = false, userAgent = "UA-$secret", updateHours = 6, updateOnStart = false, useSourceEpg = false,
            guideLinks = listOf("http://epg1.example.test/$secret.xml", "http://epg2.example.test/$secret.xml"),
            channelEdits = listOf(
                SetupChannelEdit(
                    key = "http://stream.example.test/$secret/1.ts", hidden = true, customName = "One $secret",
                    customLogo = "http://logo.example.test/$secret.png", epgOverride = "one.$secret",
                    epgShiftMinutes = 60, position = 3, aspectMode = AspectMode.ZOOM,
                ),
            ),
            groupEdits = listOf(
                SetupGroupEdit(title = "News", hidden = true, customName = "Headlines", position = 2, sortMode = GroupSort.CUSTOM),
            ),
        ),
        SetupPlaylist(
            name = "Xtream", type = PlaylistType.XTREAM, xtreamServer = "http://xt.example.test",
            xtreamUser = "user-$secret", xtreamPass = "pass-$secret",
        ),
        SetupPlaylist(
            name = "Portal", type = PlaylistType.STALKER, url = "http://portal.example.test/c/",
            mac = "00:1A:79:$secret", portalLogin = "login-$secret", portalPassword = "pw-$secret",
            portalSerial = "serial-$secret", portalDeviceId = "dev-$secret", portalDeviceId2 = "dev2-$secret",
            portalSignature = "sig-$secret",
        ),
        SetupPlaylist(
            name = "File", type = PlaylistType.M3U_FILE,
            file = SetupFile("#EXTM3U\n#EXTINF:-1,$secret\nhttp://f.example.test/$secret.ts\n".toByteArray()),
        ),
    ),
    favourites = listOf(
        SetupFavourite(playlist = 1, key = "42", addedAtMs = 1_000),
        SetupFavourite(playlist = 0, key = "http://stream.example.test/$secret/1.ts", addedAtMs = 2_000),
    ),
    settings = SetupSettings.from(changedSettings(secret)),
    skippedFiles = 1,
)

/** Every copied setting changed from its default, and every one that isn't copied too. */
internal fun changedSettings(secret: String = SECRET): AppSettings = AppSettings(
    playlistUpdateHours = 6, playlistUpdateOnStart = false,
    epgUpdateHours = 48, epgUpdateOnStart = false, epgUpdateOnPlaylistChange = false, epgPastDays = 3,
    epgStoreDescriptions = false, lastChannelOnStart = true, confirmExit = false, checkForUpdates = false,
    lastGuideScope = "group:1:News", userAgent = "UA-$secret", udpProxy = "udp-$secret.example.test:4022",
    castAllowJoin = false, castTvName = "Kitchen",
    playback = PlaybackSettings(
        bufferPreset = BufferPreset.LARGE, videoDecoder = io.github.loopholetv.data.settings.DecoderChoice.SOFTWARE,
        audioDecoder = io.github.loopholetv.data.settings.DecoderChoice.SOFTWARE, autoFrameRate = true,
        preferSurround = false, passthrough = io.github.loopholetv.data.settings.Passthrough.OFF,
        decodeDtsToPcm = true, tunneling = true, audioLanguage = "de", subtitleLanguage = "fr", audioDelayMs = 150,
        subtitleStyle = SubtitleStyle(SubtitleSize.LARGE, SubtitleColour.YELLOW, SubtitleBackground.BLACK, SubtitleEdge.SHADOW),
        pipOnHome = true,
    ),
    remote = RemoteSettings(
        mediaKeysSeek = false, rwRewindsLive = false, leftRightSeek = false, leftRewindsLive = true,
        bindings = KeyBindings(
            player = KeyBindings.DEFAULT.player + (183 to PlayerAction.FAVOURITE) + (166 to PlayerAction.NONE),
            guide = KeyBindings.DEFAULT.guide + (184 to GuideAction.SEARCH),
        ),
    ),
)

class SetupBundleTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val stageDir by lazy { tmp.newFolder("staged") }
    private var staged = 0

    private fun stage(): Pair<File, OutputStream> {
        staged++
        val f = File.createTempFile(".tmp-", null, stageDir)
        return f to f.outputStream()
    }

    private fun decode(bytes: ByteArray, maxFile: Int = MAX_SETUP_FILE_BYTES, maxTotal: Int = MAX_SETUP_TOTAL_FILE_BYTES) =
        SetupJson.decode(bytes, ::stage, maxFile, maxTotal)

    private fun stagedFiles() = stageDir.listFiles().orEmpty().toList()

    @Test
    fun versionIsCheckedBeforeAnythingElse() {
        val json = String(SetupJson.encode(sampleBundle()))
        assertTrue(json.startsWith("{\"version\":1,"))
        // A newer version: nothing after the version is read, so no file is staged.
        assertNull(decode(json.replace("\"version\":1", "\"version\":2").toByteArray()))
        // Even when the rest wouldn't parse.
        assertNull(decode("{\"version\":2,\"playlists\":[[[[".toByteArray()))
        assertNull(decode("{\"version\":\"1\"}".toByteArray()))
        // The version must come first.
        val versionSecond = json.replace("\"version\":1,", "").replace(",\"skippedFiles\"", ",\"version\":1,\"skippedFiles\"")
        assertNull(decode(versionSecond.toByteArray()))
        assertEquals(0, staged)
    }

    @Test
    fun receivedFilesAreStagedOnDiskAndRemovedOnFailure() {
        val json = String(SetupJson.encode(sampleBundle()))
        val decoded = decode(json.toByteArray())!!
        val file = decoded.playlists.single { it.file != null }.file!!
        assertEquals(stagedFiles(), listOfNotNull(file.staged))
        decoded.discardFiles()
        assertEquals(emptyList<File>(), stagedFiles())

        // Cut right after the file contents: the file was staged, then decoding failed.
        val cut = json.substring(0, json.indexOf("\"guideLinks\"", json.indexOf("\"data\"")))
        assertNull(decode(cut.toByteArray()))
        assertEquals(2, staged)
        assertEquals(emptyList<File>(), stagedFiles())
    }

    @Test
    fun filesPastTheCapsAreDroppedWhileDecoding() {
        val sample = sampleBundle()
        val files = listOf(80, 80, 60).mapIndexed { i, size ->
            SetupPlaylist(name = "f$i", type = PlaylistType.M3U_FILE, file = SetupFile(ByteArray(size) { i.toByte() }))
        }
        val bundle = sample.copy(
            playlists = files + sample.playlists.filter { it.file == null },
            favourites = listOf(SetupFavourite(1, "dropped", 1), SetupFavourite(2, "kept", 2), SetupFavourite(3, "xt", 3)),
        )

        val decoded = decode(SetupJson.encode(bundle), maxFile = 100, maxTotal = 150)!!

        assertEquals(listOf("f0", "f2", "Main", "Xtream", "Portal"), decoded.playlists.map { it.name })
        assertEquals(sample.skippedFiles + 1, decoded.skippedFiles)
        assertEquals(listOf(1 to "kept", 2 to "xt"), decoded.favourites.map { it.playlist to it.key })
        assertEquals(SetupFile(ByteArray(60) { 2.toByte() }), decoded.playlists[1].file)
        // The dropped file's temp is gone; the two kept ones remain for the import.
        assertEquals(2, stagedFiles().size)
        // Over the per-file cap alone.
        assertEquals(listOf("f2", "Main", "Xtream", "Portal"), decode(SetupJson.encode(bundle), maxFile = 70)!!.playlists.map { it.name })
    }
    @Test
    fun jsonRoundTripKeepsEveryValue() {
        val bundle = sampleBundle()
        assertEquals(bundle, decode(SetupJson.encode(bundle)))
    }

    @Test
    fun unknownVersionDecodesToNull() {
        val json = String(SetupJson.encode(sampleBundle()))
        assertNotNull(decode(json.toByteArray()))
        assertFalse(json.contains("\"version\":2"))
        assertNull(decode(json.replace("\"version\":1", "\"version\":2").toByteArray()))
        assertNull(decode(json.replace("\"version\":1,", "").toByteArray()))
    }

    @Test
    fun garbageDecodesToNull() {
        val json = String(SetupJson.encode(sampleBundle()))
        assertNull(decode(ByteArray(0)))
        assertNull(decode("not json".toByteArray()))
        assertNull(decode("[1,2]".toByteArray()))
        assertNull(decode(json.substring(0, json.length / 2).toByteArray()))
        assertNull(decode((json + "{}").toByteArray()))
        // A favourite pointing past the playlists, and a file playlist without its file.
        assertNull(decode(json.replace("\"playlist\":1", "\"playlist\":9").toByteArray()))
        val noFile = SetupJson.encode(sampleBundle().let { b -> b.copy(playlists = b.playlists.map { it.copy(file = null) }) })
        assertNull(decode(noFile))
        assertNull(decode(json.replace("\"type\":\"XTREAM\"", "\"type\":\"OTHER\"").toByteArray()))
        // File contents that aren't Base64.
        assertNull(decode(json.replace(Regex("\"data\":\"[^\"]*\""), "\"data\":\"%%%%\"").toByteArray()))
        assertNull(decode(json.replace(Regex("\"data\":\"[^\"]*\""), "\"data\":7").toByteArray()))
    }

    @Test
    fun largeFileContentsRoundTrip() {
        // Several Base64 chunks, and a length that isn't a multiple of 3.
        val bytes = ByteArray(300_001) { (it * 31).toByte() }
        val bundle = sampleBundle().let { b ->
            b.copy(playlists = b.playlists.map { if (it.file != null) it.copy(file = SetupFile(bytes)) else it })
        }
        assertEquals(bundle, decode(SetupJson.encode(bundle)))
    }

    @Test
    fun bundleToStringHasNoSecrets() {
        val bundle = sampleBundle()
        val texts = buildList {
            add(bundle.toString())
            add(bundle.settings.toString())
            bundle.playlists.forEach { p ->
                add(p.toString())
                add(p.file.toString())
                p.channelEdits.forEach { add(it.toString()) }
                p.groupEdits.forEach { add(it.toString()) }
            }
            bundle.favourites.forEach { add(it.toString()) }
            add(SetupExportResult(bundle, skippedLargeFiles = 1).toString())
            val removed = RemovedPlaylist(1, PlaylistType.M3U, "http://m3u.example.test/$SECRET.m3u")
            add(removed.toString())
            add(SetupImportResult.Ok(skippedFiles = 1, removed = listOf(removed)).toString())
            add(SetupImportResult.Failed.toString())
        }
        for (text in texts) {
            for (leak in listOf(SECRET, "example.test", "#EXTM3U", "Headlines")) {
                assertFalse("leaked $leak in $text", text.contains(leak))
            }
        }
    }

    @Test
    fun everySettingIsClassifiedAsCopiedOrNot() {
        assertClassified(AppSettings::class.java, SetupFields.APP_COPIED, SetupFields.APP_NOT_COPIED)
        assertClassified(PlaybackSettings::class.java, SetupFields.PLAYBACK_COPIED, SetupFields.PLAYBACK_NOT_COPIED)
        assertClassified(SubtitleStyle::class.java, SetupFields.SUBTITLE_STYLE_COPIED, emptySet())
        assertClassified(RemoteSettings::class.java, SetupFields.REMOTE_COPIED, emptySet())
    }

    @Test
    fun everyPlaylistAndEditColumnIsClassifiedAsCopiedOrNot() {
        assertClassified(PlaylistEntity::class.java, SetupFields.PLAYLIST_COPIED, SetupFields.PLAYLIST_NOT_COPIED)
        assertClassified(ChannelPrefsEntity::class.java, SetupFields.CHANNEL_EDIT_COPIED, SetupFields.CHANNEL_EDIT_NOT_COPIED)
        assertClassified(GroupPrefsEntity::class.java, SetupFields.GROUP_EDIT_COPIED, emptySet())
    }

    @Test
    fun settingsCopyOnlyTheCopiedValues() {
        val from = changedSettings()
        val onto = AppSettings(castTvName = "Bedroom", lastGuideScope = "all")
        val result = SetupSettings.from(from).applyTo(onto)
        assertEquals(
            from.copy(
                lastGuideScope = "all", castAllowJoin = onto.castAllowJoin, castTvName = "Bedroom",
                playback = from.playback.copy(
                    videoDecoder = onto.playback.videoDecoder, audioDecoder = onto.playback.audioDecoder,
                    autoFrameRate = onto.playback.autoFrameRate, preferSurround = onto.playback.preferSurround,
                    passthrough = onto.playback.passthrough, decodeDtsToPcm = onto.playback.decodeDtsToPcm,
                    tunneling = onto.playback.tunneling, audioDelayMs = onto.playback.audioDelayMs,
                ),
            ),
            result,
        )
    }

    private fun assertClassified(type: Class<*>, copied: Set<String>, notCopied: Set<String>) {
        val fields = type.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.map { it.name }.toSet()
        assertEquals("${type.simpleName}: copied and not-copied overlap", emptySet<String>(), copied intersect notCopied)
        assertEquals("${type.simpleName}: every field classified", fields, copied + notCopied)
    }
}
