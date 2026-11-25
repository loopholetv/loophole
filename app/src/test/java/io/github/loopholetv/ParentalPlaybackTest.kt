package io.github.loopholetv

import androidx.media3.common.Player
import io.github.loopholetv.cast.CastControls
import io.github.loopholetv.cast.CastState
import io.github.loopholetv.cast.StopReason
import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.db.ChannelEntity
import io.github.loopholetv.data.db.ChannelPrefsEntity
import io.github.loopholetv.data.db.ChannelScope
import io.github.loopholetv.data.db.GroupPrefsEntity
import io.github.loopholetv.data.db.ShowEntity
import io.github.loopholetv.data.db.ShowPrefsEntity
import io.github.loopholetv.data.db.addPlaylist
import io.github.loopholetv.data.db.channel
import io.github.loopholetv.data.db.inMemoryDb
import io.github.loopholetv.data.db.insertChannels
import io.github.loopholetv.data.db.isChannelLocked
import io.github.loopholetv.data.db.isVodLocked
import io.github.loopholetv.data.repo.ChannelOverlay
import io.github.loopholetv.data.vod.VodKind
import io.github.loopholetv.data.vod.VodRef
import io.github.loopholetv.player.Playback
import io.github.loopholetv.player.PlaybackState
import io.github.loopholetv.player.PreviewHold
import io.github.loopholetv.player.StreamTags
import io.github.loopholetv.record.ActiveRecording
import io.github.loopholetv.record.RecordingCoordinator
import io.github.loopholetv.record.RecordingRuns
import io.github.loopholetv.record.StreamUses
import io.github.loopholetv.data.db.RecordingReason
import io.github.loopholetv.ui.nav.StartPlayback
import io.github.loopholetv.ui.nav.resolveStartPlayback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/** Locked channels, films and shows are refused at every play entry while the PIN is not entered. */
@RunWith(RobolectricTestRunner::class)
class ParentalPlaybackTest {
    private lateinit var db: AppDatabase
    private var p = 0L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var unlocked = false
    private var noPin = false

    @Before fun setUp() = runTest {
        db = inMemoryDb()
        p = db.addPlaylist()
    }

    @After fun tearDown() {
        scope.cancel()
        db.close()
    }

    private class NoPlayback : Playback {
        override val player: Player get() = error("not used")
        override val state: StateFlow<PlaybackState> = MutableStateFlow(PlaybackState.Idle)
        override fun play(channel: ChannelEntity) = Unit
        override fun stop() = Unit
    }

    private class NoCast : CastControls {
        override val state: StateFlow<CastState> = MutableStateFlow(CastState.Off)
        override fun startCasting() = Unit
        override fun stopCasting(reason: StopReason) = Unit
    }

    /** Nothing records: a play the lock allows goes straight through. */
    private class NoRuns : RecordingRuns {
        override val active: StateFlow<Map<Long, ActiveRecording>> = MutableStateFlow(emptyMap())
        override var runGate: suspend (id: Long, fromArchive: Boolean) -> Unit = { _, _ -> }
        override suspend fun begin(id: Long, fromArchive: Boolean) = Unit
        override suspend fun stop(id: Long, reason: RecordingReason) = Unit
        override suspend fun recover() = Unit
    }

    private fun coordinator(): RecordingCoordinator {
        val runner = NoRuns()
        val playback = NoPlayback()
        val cast = NoCast()
        return RecordingCoordinator(
            runner, db, StreamUses(playback, cast, runner, hostOfChannel = { null }), playback, PreviewHold(), cast,
            evictIdle = {}, clock = { Instant.EPOCH }, scope = scope, isUnlocked = { unlocked }, noPin = { noPin },
        )
    }

    private suspend fun channels(group: String = "G", vararg keys: String): List<ChannelEntity> =
        db.insertChannels(*keys.mapIndexed { i, k -> channel(p, k, i + 1, groupTitle = group) }.toTypedArray()).values.toList()

    private suspend fun lockChannel(key: String) = db.channelPrefsDao().upsert(ChannelPrefsEntity(p, key, locked = true))

    private suspend fun lockGroup(title: String) = db.groupPrefsDao().upsert(GroupPrefsEntity(p, title, locked = true))

    private fun addFilm(key: String, category: String = "C") = db.openHelper.writableDatabase.execSQL(
        """INSERT INTO vod_films (playlistId, `key`, name, category, streamUrl, position, firstSeenAt)
           VALUES ($p, '$key', 'Film', '$category', 'http://s.test/$key.mp4', 0, 5)""",
    )

    private suspend fun addShow(key: String, group: String = "Drama", vararg episodes: String) {
        db.vodWriteDao().insertShows(listOf(ShowEntity(p, key, "Show", group, null, null, Instant.EPOCH, 0)))
        for (e in episodes) {
            db.openHelper.writableDatabase.execSQL(
                """INSERT INTO vod_episodes (playlistId, `key`, showKey, groupTitle, name, streamUrl, position, firstSeenAt)
                   VALUES ($p, '$e', '$key', '$group', 'Ep', 'http://s.test/$e.mp4', 0, 5)""",
            )
        }
    }

    // Lookups

    @Test fun channelLockedByItselfOrItsGroup() = runTest {
        channels("News", "a", "b")
        channels("Kids", "c")
        lockChannel("a")
        lockGroup("Kids")
        val lock = db.lockDao()
        assertTrue(lock.isChannelLocked(p, "a"))
        assertFalse(lock.isChannelLocked(p, "b"))
        assertTrue(lock.isChannelLocked(p, "c"))
        assertFalse(lock.isChannelLocked(p, "missing"))
        assertFalse(lock.isChannelLocked(p + 1, "a"))
    }

    @Test fun channelNameMatchesTheShownNameOrThePlaylistName() = runTest {
        db.insertChannels(channel(p, "a", 1, name = "Original", customName = "My Name"), channel(p, "b", 2, name = "Other"))
        lockChannel("a")
        val lock = db.lockDao()
        assertTrue(lock.channelNameLocked("My Name"))
        assertTrue(lock.channelNameLocked("Original"))
        assertFalse(lock.channelNameLocked("Other"))
    }

    @Test fun filmLockedByItselfOrItsCategory() = runTest {
        addFilm("own")
        addFilm("cat", category = "Adult")
        addFilm("free")
        lockChannel("own")
        lockGroup("Adult")
        val lock = db.lockDao()
        assertTrue(lock.isVodLocked(VodKind.MOVIE, p, "own"))
        assertTrue(lock.isVodLocked(VodKind.MOVIE, p, "cat"))
        assertFalse(lock.isVodLocked(VodKind.MOVIE, p, "free"))
    }

    @Test fun showAndItsEpisodesLockedByTheShowOrItsGroup() = runTest {
        addShow("s1", "Drama", "e1")
        addShow("s2", "Adult", "e2")
        addShow("s3", "Drama", "e3")
        db.lockDao().upsertShowPrefs(ShowPrefsEntity(p, "s1", locked = true))
        lockGroup("Adult")
        val lock = db.lockDao()
        assertTrue(lock.isVodLocked(VodKind.EPISODE, p, "e1"))
        assertTrue(lock.isVodLocked(VodKind.EPISODE, p, "e2"))
        assertFalse(lock.isVodLocked(VodKind.EPISODE, p, "e3"))
        assertFalse(lock.isVodLocked(VodKind.EPISODE, p, "gone"))
        assertFalse(lock.isVodLocked(VodKind.RECORDING, p, "e1"))
    }

    // Gates

    @Test fun lockedChannelRefusedSilently() = runTest {
        val a = channels("G", "a").single()
        lockChannel("a")
        val gate = coordinator()
        // The passed entity says visible; the row decides.
        assertTrue(a.visible)
        assertFalse(gate.beforeUserPlay(a))
        assertNull(gate.prompt.value)
    }

    @Test fun lockedGroupChannelIsRefusedToo() = runTest {
        val a = channels("Kids", "a").single()
        lockGroup("Kids")
        assertFalse(coordinator().beforeUserPlay(a, live = false))
    }

    @Test fun unlockedLockedChannelPlays() = runTest {
        val a = channels("G", "a", "b").first { it.key == "a" }
        lockChannel("a")
        unlocked = true
        assertTrue(coordinator().beforeUserPlay(a))
    }

    @Test fun unlockedChannelPlaysWhileOthersAreLocked() = runTest {
        val all = channels("G", "a", "b")
        lockChannel("a")
        assertTrue(coordinator().beforeUserPlay(all.first { it.key == "b" }))
    }

    @Test fun lockedFilmRefused() = runTest {
        addFilm("f")
        addFilm("g", category = "Adult")
        lockChannel("f")
        lockGroup("Adult")
        val gate = coordinator()
        assertFalse(gate.beforeUserPlayVod(VodRef(VodKind.MOVIE, p, "f")))
        assertFalse(gate.beforeUserPlayVod(VodRef(VodKind.MOVIE, p, "g")))
        unlocked = true
        assertTrue(gate.beforeUserPlayVod(VodRef(VodKind.MOVIE, p, "f")))
    }

    @Test fun lockedShowEpisodeRefused() = runTest {
        addShow("s", "Drama", "e1")
        db.lockDao().upsertShowPrefs(ShowPrefsEntity(p, "s", locked = true))
        val gate = coordinator()
        assertFalse(gate.beforeUserPlayVod(VodRef(VodKind.EPISODE, p, "e1")))
        unlocked = true
        assertTrue(gate.beforeUserPlayVod(VodRef(VodKind.EPISODE, p, "e1")))
    }

    @Test fun withNoPinNoLockIsLookedUp() = runTest {
        val a = channels("G", "a").single()
        addFilm("f")
        // A lock row a TV with no PIN can't have: it is never read, so nothing is refused.
        lockChannel("a")
        lockChannel("f")
        noPin = true
        val gate = coordinator()
        // Nothing recording and no lookup: the gate answers without suspending, as before locks existed.
        var live: Boolean? = null
        var film: Boolean? = null
        val plays = launch(start = CoroutineStart.UNDISPATCHED) {
            live = gate.beforeUserPlay(a)
            film = gate.beforeUserPlayVod(VodRef(VodKind.MOVIE, p, "f"))
        }
        assertTrue("answered at once", plays.isCompleted)
        assertEquals(true, live)
        assertEquals(true, film)

        // With a PIN the lock is looked up (a database read, which suspends) and refuses.
        noPin = false
        val checked = launch(start = CoroutineStart.UNDISPATCHED) { live = gate.beforeUserPlay(a) }
        assertFalse(checked.isCompleted)
        checked.join()
        assertEquals(false, live)
    }

    // Start

    @Test fun lastChannelLockedOpensGuide() = runTest {
        val a = channels("G", "a").single()
        val open = resolveStartPlayback(true, { a }, ChannelScope.All, { _, _ -> true }, isLocked = { false })
        assertEquals(StartPlayback(a.id, ChannelScope.All), open)
        val locked = resolveStartPlayback(true, { a }, ChannelScope.All, { _, _ -> true }, isLocked = { true })
        assertNull(locked)
    }

    // Relock

    @Test fun relockDoesNotStopFullScreenPlayer() = runTest(timeout = 60.seconds) {
        val a = channels("Kids", "a").single()
        lockGroup("Kids")
        val overlay = ChannelOverlay(db)
        overlay.setLockState(unlocked = true)
        val stops = AtomicInteger()
        val seen = AtomicInteger()
        val state = MutableStateFlow<PlaybackState>(PlaybackState.Playing(a.id, StreamTags.NONE))
        stopPlaybackWhenChannelHidden(
            state, { id -> db.channelDao().shownIgnoringLock(id).onEach { seen.incrementAndGet() } },
            { stops.incrementAndGet() }, scope, main = Dispatchers.Default,
        )
        awaitCondition { seen.get() >= 1 }

        overlay.setLockState(unlocked = false) // relock: the channel leaves every list
        assertFalse(db.channelDao().byId(a.id)!!.visible)
        // Hiding still stops, so the watcher is alive and has seen the relock.
        db.groupPrefsDao().upsert(GroupPrefsEntity(p, "Kids", hidden = true, locked = true))
        ChannelOverlay(db).applyChannel(a.playlistId, a.key)
        awaitCondition { stops.get() >= 1 }
        assertEquals(1, stops.get())
    }

    @Test fun relockAloneNeverStops() = runTest(timeout = 60.seconds) {
        val a = channels("Kids", "a").single()
        lockGroup("Kids")
        val overlay = ChannelOverlay(db)
        overlay.setLockState(unlocked = true)
        val stops = AtomicInteger()
        val seen = AtomicInteger()
        val state = MutableStateFlow<PlaybackState>(PlaybackState.Playing(a.id, StreamTags.NONE))
        stopPlaybackWhenChannelHidden(
            state, { id -> db.channelDao().shownIgnoringLock(id).onEach { seen.incrementAndGet() } },
            { stops.incrementAndGet() }, scope, main = Dispatchers.Default,
        )
        awaitCondition { seen.get() >= 1 }
        val before = seen.get()
        overlay.setLockState(unlocked = false)
        // The query re-runs on the change; it still says shown, so nothing stops.
        awaitCondition { seen.get() > before }
        assertEquals(0, stops.get())
        assertFalse(db.channelDao().byId(a.id)!!.visible)
    }

    @Test fun hideStillStopsWhileUnlocked() = runTest(timeout = 60.seconds) {
        val a = channels("G", "a").single()
        val stops = AtomicInteger()
        val seen = AtomicInteger()
        val state = MutableStateFlow<PlaybackState>(PlaybackState.Playing(a.id, StreamTags.NONE))
        stopPlaybackWhenChannelHidden(
            state, { id -> db.channelDao().shownIgnoringLock(id).onEach { seen.incrementAndGet() } },
            { stops.incrementAndGet() }, scope, main = Dispatchers.Default,
        )
        awaitCondition { seen.get() >= 1 }
        ChannelOverlay(db).setLockState(unlocked = true)
        db.channelPrefsDao().upsert(ChannelPrefsEntity(p, "a", hidden = true))
        ChannelOverlay(db).applyChannel(p, "a")
        awaitCondition { stops.get() >= 1 }
        assertEquals(1, stops.get())
    }

    private suspend fun awaitCondition(done: () -> Boolean) = withContext(Dispatchers.Default) {
        withTimeout(5_000) { while (!done()) yield() }
    }
}
