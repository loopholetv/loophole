package io.github.loopholetv.setup

import io.github.loopholetv.data.db.ChannelPrefsEntity
import io.github.loopholetv.data.db.GroupPrefsEntity
import io.github.loopholetv.data.db.GroupSort
import io.github.loopholetv.data.db.PlaylistEntity
import io.github.loopholetv.data.db.PlaylistType
import io.github.loopholetv.data.db.ShowPrefsEntity
import io.github.loopholetv.data.settings.AppSettings
import io.github.loopholetv.data.settings.AspectMode
import io.github.loopholetv.data.settings.BufferPreset
import io.github.loopholetv.data.settings.GuideAction
import io.github.loopholetv.data.settings.KeyBindings
import io.github.loopholetv.data.settings.ParentalSettings
import io.github.loopholetv.data.settings.PlaybackSettings
import io.github.loopholetv.data.settings.PlayerAction
import io.github.loopholetv.data.settings.RemoteSettings
import io.github.loopholetv.data.settings.SettingsLock
import io.github.loopholetv.data.settings.SubtitleBackground
import io.github.loopholetv.data.settings.SubtitleColour
import io.github.loopholetv.data.settings.SubtitleEdge
import io.github.loopholetv.data.settings.SubtitleSize
import io.github.loopholetv.data.settings.SubtitleStyle
import io.github.loopholetv.record.storage.StorageKind
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

/** A 32-byte PIN hash and a 16-byte salt, Base64, as a bundle carries them. */
private const val HASH = "aGFzaC0kc2VjcmV0MDEyMzQ1Njc4OWFiY2RlZmdoaWo="
private const val SALT = "c2FsdC0kc2VjcmV0MDEyMw=="

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
                    epgShiftMinutes = 60, position = 3, aspectMode = AspectMode.ZOOM, locked = true,
                ),
                // A film's lock is a channel edit under its film key.
                SetupChannelEdit(key = "film-$secret", locked = true),
            ),
            groupEdits = listOf(
                SetupGroupEdit(
                    title = "News", hidden = true, customName = "Headlines", position = 2, sortMode = GroupSort.CUSTOM, locked = true,
                ),
            ),
            showLocks = listOf(SetupShowLock("show-$secret")),
        ),
        SetupPlaylist(
            name = "Xtream", type = PlaylistType.XTREAM, xtreamServer = "http://xt.example.test",
            xtreamUser = "user-$secret", xtreamPass = "pass-$secret",
        ),
        SetupPlaylist(
            name = "Portal", type = PlaylistType.STALKER, url = "http://portal.example.test/c/",
            mac = "00:1A:79:$secret", portalLogin = "login-$secret", portalPassword = "pw-$secret",
            portalSerial = "serial-$secret", portalDeviceId = "dev-$secret", portalDeviceId2 = "dev2-$secret",
            portalSignature = "sig-$secret", connectionsAllowed = 2,
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
        pipOnHome = true, autoPlayNext = false,
    ),
    recordStorage = StorageKind.SMB, smbHost = "nas.example.test", smbShare = "tv", smbFolder = "rec",
    smbUser = "u-$secret", smbPassword = "p-$secret", smbDomain = "HOME",
    nfsHost = "nfs.example.test", nfsExport = "/export", nfsFolder = "rec2",
    usbTreeUri = "content://usb/$secret", reminderLeadMin = 5, reminderAutoSwitch = true,
    padBeforeMin = 2, padAfterMin = 15,
    tmdbKey = "abcdef0123456789abcdef0123456789",
    parental = ParentalSettings(
        pinHash = HASH, pinSalt = SALT, unlockMinutes = 30, settingsLock = SettingsLock.PLAYLISTS_EPG,
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
        assertTrue(json.startsWith("{\"version\":2,"))
        // Another version: nothing after the version is read, so no file is staged.
        assertNull(decode(json.replace("\"version\":2", "\"version\":3").toByteArray()))
        // Even when the rest wouldn't parse.
        assertNull(decode("{\"version\":3,\"playlists\":[[[[".toByteArray()))
        assertNull(decode("{\"version\":\"2\"}".toByteArray()))
        // The version must come first.
        val versionSecond = json.replace("\"version\":2,", "").replace(",\"skippedFiles\"", ",\"version\":2,\"skippedFiles\"")
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
        assertFalse(json.contains("\"version\":3"))
        assertNull(decode(json.replace("\"version\":2", "\"version\":3").toByteArray()))
        assertNull(decode(json.replace("\"version\":2,", "").toByteArray()))
    }

    @Test
    fun version1BundleRefused() {
        val json = String(SetupJson.encode(sampleBundle()))
        assertNull(decode(json.replace("\"version\":2", "\"version\":1").toByteArray()))
        assertEquals(2, SetupBundle.VERSION)
    }

    @Test
    fun roundTripCarriesLocksAndPin() {
        val decoded = decode(SetupJson.encode(sampleBundle()))!!
        val main = decoded.playlists[0]
        assertTrue("channel lock", main.channelEdits.first { it.key.startsWith("http") }.locked)
        assertTrue("film lock", main.channelEdits.first { it.key.startsWith("film-") }.locked)
        assertTrue("group lock", main.groupEdits.single().locked)
        assertEquals(listOf(SetupShowLock("show-$SECRET")), main.showLocks)
        assertEquals(emptyList<SetupShowLock>(), decoded.playlists[1].showLocks)
        val p = decoded.settings.parental
        assertEquals(HASH, p.pinHash)
        assertEquals(SALT, p.pinSalt)
        assertEquals(30, p.unlockMinutes)
        assertEquals(SettingsLock.PLAYLISTS_EPG, p.settingsLock)
        // A TV with no PIN round-trips as one too.
        val none = sampleBundle().let { it.copy(settings = it.settings.copy(parental = ParentalSettings())) }
        assertEquals(ParentalSettings(), decode(SetupJson.encode(none))!!.settings.parental)
    }

    @Test
    fun aVersion2BundleMissingOrBadInNewFieldsIsRefused() {
        val json = String(SetupJson.encode(sampleBundle()))
        // No lock flag on an edit, no show locks, or any parental field missing or out of range: not a bundle.
        assertNull(decode(json.replace("\"locked\":true", "\"x\":true").toByteArray()))
        assertNull(decode(json.replace("\"showLocks\"", "\"other\"").toByteArray()))
        for (field in listOf("parentalPinHash", "parentalPinSalt", "parentalUnlockMinutes", "parentalSettingsLock")) {
            assertNull(field, decode(json.replace("\"$field\"", "\"x$field\"").toByteArray()))
        }
        assertNull(decode(json.replace("\"parentalUnlockMinutes\":30", "\"parentalUnlockMinutes\":7").toByteArray()))
        assertNull(decode(json.replace("\"PLAYLISTS_EPG\"", "\"SOMETIMES\"").toByteArray()))
        assertNull(decode(json.replace("\"parentalPinSalt\":\"$SALT\"", "\"parentalPinSalt\":null").toByteArray()))
        assertNull(decode(json.replace("\"parentalPinHash\":\"$HASH\"", "\"parentalPinHash\":\"%%%\"").toByteArray()))
        assertNull(decode(json.replace("\"showKey\":\"show-$SECRET\"", "\"showKey\":7").toByteArray()))
        // Base64 of the wrong length is no PIN this TV could ever match: a 16-byte salt and a 32-byte hash only.
        val shortSalt = "c2FsdC0kc2VjcmV0" // 12 bytes
        val shortHash = "aGFzaC0kc2VjcmV0MDEyMzQ1Njc4OWFiY2RlZmdo" // 30 bytes
        assertNull(decode(json.replace("\"parentalPinSalt\":\"$SALT\"", "\"parentalPinSalt\":\"$shortSalt\"").toByteArray()))
        assertNull(decode(json.replace("\"parentalPinHash\":\"$HASH\"", "\"parentalPinHash\":\"$shortHash\"").toByteArray()))
        assertNull(decode(json.replace("\"parentalPinSalt\":\"$SALT\"", "\"parentalPinSalt\":\"$HASH\"").toByteArray()))
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
            add(bundle.settings.parental.toString())
            bundle.playlists.forEach { p ->
                add(p.toString())
                add(p.file.toString())
                p.channelEdits.forEach { add(it.toString()) }
                p.groupEdits.forEach { add(it.toString()) }
                p.showLocks.forEach { add(it.toString()) }
            }
            bundle.favourites.forEach { add(it.toString()) }
            add(SetupExportResult(bundle, skippedLargeFiles = 1).toString())
            val removed = RemovedPlaylist(1, PlaylistType.M3U, "http://m3u.example.test/$SECRET.m3u")
            add(removed.toString())
            add(SetupImportResult.Ok(skippedFiles = 1, removed = listOf(removed)).toString())
            add(SetupImportResult.Failed.toString())
        }
        for (text in texts) {
            for (leak in listOf(SECRET, "example.test", "#EXTM3U", "Headlines", "aGFzaC0", "c2FsdC0")) {
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
        assertClassified(ShowPrefsEntity::class.java, SetupFields.SHOW_PREFS_COPIED, emptySet())
        assertClassified(ParentalSettings::class.java, SetupFields.PARENTAL_COPIED, emptySet())
    }

    @Test
    fun usbStorageIsNotCopied() {
        val usb = SetupSettings.from(AppSettings(recordStorage = StorageKind.USB, usbTreeUri = "content://usb/x"))
        assertEquals(StorageKind.NONE, usb.recordStorage)
        assertEquals(StorageKind.NONE, SetupSettings.from(AppSettings(recordStorage = StorageKind.INTERNAL)).recordStorage)
        assertEquals("", usb.applyTo(AppSettings(usbTreeUri = "")).usbTreeUri)
        assertEquals("content://usb/mine", usb.applyTo(AppSettings(usbTreeUri = "content://usb/mine")).usbTreeUri)

        val smb = changedSettings()
        val bundle = sampleBundle()
        val decoded = decode(SetupJson.encode(bundle))!!
        assertEquals(StorageKind.SMB, decoded.settings.recordStorage)
        val applied = decoded.settings.applyTo(AppSettings())
        assertEquals(smb.smbUser, applied.smbUser)
        assertEquals(smb.smbPassword, applied.smbPassword)
        assertEquals(smb.smbHost, applied.smbHost)
        assertEquals(StorageKind.NFS, SetupSettings.from(AppSettings(recordStorage = StorageKind.NFS)).recordStorage)
        assertEquals(2, decoded.playlists.first { it.type == PlaylistType.STALKER }.connectionsAllowed)
    }

    @Test
    fun xtreamFormatRoundTrips() {
        val base = sampleBundle()
        val withFormat = base.copy(
            playlists = base.playlists.map { if (it.type == PlaylistType.XTREAM) it.copy(xtreamFormat = "hls") else it },
        )
        val decoded = decode(SetupJson.encode(withFormat))!!
        assertEquals("hls", decoded.playlists.first { it.type == PlaylistType.XTREAM }.xtreamFormat)
        assertNull(decode(SetupJson.encode(base))!!.playlists.first { it.type == PlaylistType.XTREAM }.xtreamFormat)

        val odd = withFormat.copy(
            playlists = withFormat.playlists.map { if (it.type == PlaylistType.XTREAM) it.copy(xtreamFormat = "mkv") else it },
        )
        assertNull(decode(SetupJson.encode(odd))!!.playlists.first { it.type == PlaylistType.XTREAM }.xtreamFormat)
    }

    @Test
    fun xtreamVodRoundTripsAndDefaultsOn() {
        val base = sampleBundle()
        assertTrue(decode(SetupJson.encode(base))!!.playlists.first { it.type == PlaylistType.XTREAM }.xtreamVod)
        val off = base.copy(
            playlists = base.playlists.map { if (it.type == PlaylistType.XTREAM) it.copy(xtreamVod = false) else it },
        )
        val json = String(SetupJson.encode(off))
        assertTrue(json, "\"xtreamVod\":false" in json)
        assertFalse(decode(json.toByteArray())!!.playlists.first { it.type == PlaylistType.XTREAM }.xtreamVod)

        // A bundle from a TV that predates the setting has no such field: films and series stay on.
        val older = json.replace(",\"xtreamVod\":false", "").replace(",\"xtreamVod\":true", "")
        assertFalse(older, "xtreamVod" in older)
        assertTrue(decode(older.toByteArray())!!.playlists.all { it.xtreamVod })
    }

    @Test
    fun aBundleWithoutANetworkFolderKeepsThisTvsStorage() {
        val target = AppSettings(
            recordStorage = StorageKind.SMB, smbHost = "mine.example.test", smbShare = "share", smbFolder = "rec",
            smbUser = "me", smbPassword = "pw-mine", smbDomain = "home", nfsHost = "nfs-mine.example.test",
            nfsExport = "/mine", nfsFolder = "f", usbTreeUri = "content://usb/mine",
        )
        val none = SetupSettings.from(AppSettings(recordStorage = StorageKind.USB, usbTreeUri = "content://usb/x"))
        assertEquals(StorageKind.NONE, none.recordStorage)
        assertStorageKept(target, none.applyTo(target))
    }

    @Test
    fun aNetworkFolderIsAppliedOntoATvWithNone() {
        val applied = SetupSettings.from(changedSettings()).applyTo(AppSettings(recordStorage = StorageKind.NONE))
        assertEquals(StorageKind.SMB, applied.recordStorage)
        assertEquals("nas.example.test", applied.smbHost)
        assertEquals(changedSettings().smbUser, applied.smbUser)
        assertEquals(changedSettings().smbPassword, applied.smbPassword)
        // SMB was chosen: the NFS settings neither travel nor change here.
        assertEquals("", applied.nfsHost)
    }

    @Test
    fun anSmbBundleKeepsTheTargetsNfsSettings() {
        val target = AppSettings(recordStorage = StorageKind.NFS, nfsHost = "nfs-mine.example.test", nfsExport = "/mine", nfsFolder = "f")
        val applied = SetupSettings.from(changedSettings()).applyTo(target)
        assertEquals(StorageKind.SMB, applied.recordStorage)
        assertEquals("nas.example.test", applied.smbHost)
        assertEquals(listOf("nfs-mine.example.test", "/mine", "f"), listOf(applied.nfsHost, applied.nfsExport, applied.nfsFolder))
    }

    @Test
    fun anNfsBundleKeepsTheTargetsSmbLogin() {
        val nfs = changedSettings().copy(recordStorage = StorageKind.NFS)
        val target = AppSettings(recordStorage = StorageKind.SMB, smbHost = "mine.example.test", smbUser = "me", smbPassword = "pw-mine")
        val applied = SetupSettings.from(nfs).applyTo(target)
        assertEquals(StorageKind.NFS, applied.recordStorage)
        assertEquals("nfs.example.test", applied.nfsHost)
        assertEquals(listOf("mine.example.test", "me", "pw-mine"), listOf(applied.smbHost, applied.smbUser, applied.smbPassword))
    }

    @Test
    fun onlyTheChosenProtocolTravels() {
        val sent = changedSettings()
        val nfs = String(SetupJson.encode(sampleBundle().copy(settings = SetupSettings.from(sent.copy(recordStorage = StorageKind.NFS)))))
        assertTrue(nfs.contains("\"smbPassword\":\"\""))
        assertTrue(nfs.contains("\"smbUser\":\"\""))
        assertFalse(nfs.contains("nas.example.test"))
        assertTrue(nfs.contains("nfs.example.test"))
        val smb = SetupSettings.from(sent)
        assertEquals("", smb.nfsHost)
        assertEquals(sent.smbPassword, smb.smbPassword)
        val usb = SetupSettings.from(sent.copy(recordStorage = StorageKind.USB))
        assertEquals(listOf("", "", ""), listOf(usb.smbPassword, usb.smbHost, usb.nfsHost))
    }

    @Test
    fun anOlderBundleWithoutStorageFieldsKeepsThisTvsStorage() {
        val fields = "recordStorage|smbHost|smbShare|smbFolder|smbUser|smbPassword|smbDomain|nfsHost|nfsExport|nfsFolder"
        val json = String(SetupJson.encode(sampleBundle())).replace(Regex(",\"($fields)\":\"(?:[^\"\\\\]|\\\\.)*\""), "")
        assertFalse(json.contains("smbHost"))
        val decoded = decode(json.toByteArray())!!
        val target = AppSettings(recordStorage = StorageKind.NFS, nfsHost = "nfs-mine.example.test", nfsExport = "/mine", smbPassword = "pw")
        assertStorageKept(target, decoded.settings.applyTo(target))
        decoded.discardFiles()
    }

    private fun assertStorageKept(target: AppSettings, result: AppSettings) {
        assertEquals(target.recordStorage, result.recordStorage)
        assertEquals(
            listOf(target.smbHost, target.smbShare, target.smbFolder, target.smbUser, target.smbPassword, target.smbDomain),
            listOf(result.smbHost, result.smbShare, result.smbFolder, result.smbUser, result.smbPassword, result.smbDomain),
        )
        assertEquals(listOf(target.nfsHost, target.nfsExport, target.nfsFolder), listOf(result.nfsHost, result.nfsExport, result.nfsFolder))
        assertEquals(target.usbTreeUri, result.usbTreeUri)
    }

    @Test
    fun tmdbKeyIsCopied() {
        val key = "abcdef0123456789abcdef0123456789"
        val bundle = sampleBundle()
        assertEquals(key, bundle.settings.tmdbKey)
        val decoded = decode(SetupJson.encode(bundle))!!
        assertEquals(key, decoded.settings.applyTo(AppSettings(tmdbKey = "mine")).tmdbKey)
        assertFalse(decoded.settings.toString().contains(key))
        decoded.discardFiles()
    }

    @Test
    fun aTvWithoutAKeyOrAnOlderBundleKeepsThisTvsKey() {
        val target = AppSettings(tmdbKey = "0123456789abcdef0123456789abcdef")
        assertEquals(target.tmdbKey, SetupSettings.from(AppSettings()).applyTo(target).tmdbKey)
        val json = String(SetupJson.encode(sampleBundle())).replace(Regex(",\"tmdbKey\":\"[^\"]*\""), "")
        assertFalse(json.contains("tmdbKey"))
        val decoded = decode(json.toByteArray())!!
        assertEquals(target.tmdbKey, decoded.settings.applyTo(target).tmdbKey)
        decoded.discardFiles()
    }

    @Test
    fun settingsCopyOnlyTheCopiedValues() {
        val from = changedSettings()
        val onto = AppSettings(castTvName = "Bedroom", lastGuideScope = "all")
        val result = SetupSettings.from(from).applyTo(onto)
        assertEquals(
            from.copy(
                lastGuideScope = "all", castAllowJoin = onto.castAllowJoin, castTvName = "Bedroom", usbTreeUri = onto.usbTreeUri,
                // SMB is chosen: the NFS settings stay this TV's own.
                nfsHost = onto.nfsHost, nfsExport = onto.nfsExport, nfsFolder = onto.nfsFolder,
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
