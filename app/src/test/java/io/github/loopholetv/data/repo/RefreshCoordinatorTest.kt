package io.github.loopholetv.data.repo

import io.github.loopholetv.data.db.EpgStatusEntity
import io.github.loopholetv.data.db.PlaylistEntity
import io.github.loopholetv.data.db.PlaylistType
import io.github.loopholetv.data.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Duration
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class RefreshCoordinatorTest {
    private val now = Instant.parse("2026-11-09T12:00:00Z")
    private fun ago(minutes: Long): Instant = now.minus(Duration.ofMinutes(minutes))

    private val calls = mutableListOf<String>()
    private var settings = AppSettings()
    private val lastPlaylist = mutableMapOf<Long, Instant?>()
    private val epgStatus = mutableMapOf<Long, EpgStatusEntity?>()
    private var playlistResult: (Long) -> ImportResult = { ImportResult.Success(it, 1) }
    private var epgAction: (Long) -> Unit = {}
    private var listPlaylists: () -> List<PlaylistEntity> = { lastPlaylist.keys.sorted().map(::playlist) }

    private val disabled = mutableSetOf<Long>()
    private val ownHours = mutableMapOf<Long, Int?>()
    private val ownOnStart = mutableMapOf<Long, Boolean?>()

    private fun playlist(id: Long) = PlaylistEntity(
        id = id, name = "P$id", type = PlaylistType.M3U, url = "http://x/$id", xtreamServer = null,
        xtreamUser = null, xtreamPass = null, epgUrlsFromSource = "", manualEpgUrl = null,
        lastRefreshedAt = lastPlaylist[id], lastError = null,
        enabled = id !in disabled, updateHours = ownHours[id], updateOnStart = ownOnStart[id],
    )

    private fun ok(id: Long, at: Instant?, message: String? = null) =
        EpgStatusEntity(playlistId = id, updatedAt = at, matchedChannels = 1, ok = true, message = message)

    /** Awaited inside each fake refresh (after recording the call); complete it to let refreshes finish. */
    private var playlistGate: CompletableDeferred<Unit>? = null
    private var epgGate: CompletableDeferred<Unit>? = null

    /** Fakes behave like the repositories: a success stores its time, so a later due check sees it. */
    private fun coordinator() = RefreshCoordinator(
        playlists = { listPlaylists() },
        epgStatus = { epgStatus[it] },
        refreshPlaylistFn = { id, _ ->
            calls += "p$id"
            playlistGate?.await()
            playlistResult(id).also { if (it is ImportResult.Success) lastPlaylist[id] = now }
        },
        refreshEpgFn = { id, _ ->
            calls += "e$id"
            epgGate?.await()
            epgAction(id)
            ok(id, now).also { epgStatus[id] = it }
        },
        settings = { settings },
        clock = { now },
        updateSourceFn = { id, _, _ ->
            calls += "s$id"
            playlistResult(id).also { if (it is ImportResult.Success) lastPlaylist[id] = now }
        },
    )

    /** Playlists [ids], all never refreshed (so everything is due). */
    private fun given(vararg ids: Long) = ids.forEach { lastPlaylist[it] = null }

    // --- refreshPlaylist / EPG chain ---

    @Test
    fun playlistSuccessChainsEpgWhenEnabled() = runTest {
        given(1)
        val r = coordinator().refreshPlaylist(1)
        assertEquals(ImportResult.Success(1, 1), r)
        assertEquals(listOf("p1", "e1"), calls)
    }

    @Test
    fun noChainWhenDisabled() = runTest {
        given(1)
        settings = settings.copy(epgUpdateOnPlaylistChange = false)
        coordinator().refreshPlaylist(1)
        assertEquals(listOf("p1"), calls)
    }

    @Test
    fun manualRefreshWithoutChainLeavesTheGuideToTheCaller() = runTest {
        // Settings' single "Update playlist" runs the guide itself (always, with progress).
        given(1)
        val r = coordinator().refreshPlaylist(1, chainEpg = false)
        assertEquals(ImportResult.Success(1, 1), r)
        assertEquals(listOf("p1"), calls)
    }

    @Test
    fun noChainAfterPlaylistFailure() = runTest {
        given(1)
        playlistResult = { ImportResult.Failure(ImportError.EmptyPlaylist, "x") }
        coordinator().refreshPlaylist(1)
        assertEquals(listOf("p1"), calls)
    }

    @Test
    fun refreshEpgReturnsNullWhenRepositoryThrows() = runTest {
        given(1)
        epgAction = { throw IllegalArgumentException("Unknown playlist $it") }
        assertNull(coordinator().refreshEpg(1))
    }

    // --- runDue ---

    @Test
    fun notDueSkipsDownload() = runTest {
        lastPlaylist[1] = ago(60)
        lastPlaylist[2] = ago(25 * 60)
        epgStatus[1] = ok(1, ago(60))
        epgStatus[2] = ok(2, ago(60))
        val c = coordinator()
        c.runDue(RefreshKind.PLAYLIST)
        c.runDue(RefreshKind.EPG)
        // Playlist 2 was due; its EPG follows (chained). Nothing else was due.
        assertEquals(listOf("p2", "e2"), calls)
    }

    @Test
    fun offIntervalNeverRuns() = runTest {
        given(1)
        settings = settings.copy(playlistUpdateHours = 0, epgUpdateHours = 0)
        val c = coordinator()
        c.runDue(RefreshKind.PLAYLIST)
        c.runDue(RefreshKind.EPG)
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun epgDueRunsRegardlessOfPlaylistState() = runTest {
        lastPlaylist[1] = ago(60)
        lastPlaylist[2] = ago(60)
        epgStatus[1] = ok(1, ago(13 * 60))
        epgStatus[2] = null
        coordinator().runDue(RefreshKind.EPG)
        assertEquals(listOf("e1", "e2"), calls)
    }

    @Test
    fun partialEpgSuccessCountsAsSuccess() = runTest {
        lastPlaylist[1] = ago(60)
        epgStatus[1] = ok(1, ago(60), message = "(1 of 2 guide sources failed)")
        coordinator().runDue(RefreshKind.EPG)
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun failedEpgKeepsPreviousSuccessTime() = runTest {
        lastPlaylist[1] = ago(60)
        epgStatus[1] = EpgStatusEntity(1, updatedAt = ago(60), matchedChannels = 0, ok = false, message = "x")
        coordinator().runDue(RefreshKind.EPG)
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun authFailureSkipsEpg() = runTest {
        given(1, 2)
        playlistResult = {
            ImportResult.Failure(if (it == 1L) ImportError.Unreachable else ImportError.AuthFailed, "x")
        }
        coordinator().runDue(RefreshKind.PLAYLIST)
        assertEquals(listOf("p1", "p2"), calls)
    }

    @Test
    fun workerSkipsDeletedPlaylist() = runTest {
        given(1, 2)
        playlistResult = {
            if (it == 1L) throw IllegalArgumentException("Unknown playlist $it") else ImportResult.Success(it, 1)
        }
        coordinator().runDue(RefreshKind.PLAYLIST)
        assertEquals(listOf("p1", "p2", "e2"), calls)
    }

    @Test
    fun coordinatorNeverThrows() = runTest {
        given(1)
        playlistResult = { error("boom") }
        epgAction = { error("boom") }
        val c = coordinator()
        c.runDue(RefreshKind.PLAYLIST)
        c.runDue(RefreshKind.EPG)
        c.runStartup()
        assertTrue(c.refreshPlaylist(1) is ImportResult.Failure)
        assertNull(c.refreshEpg(1))

        listPlaylists = { error("db gone") }
        c.runDue(RefreshKind.PLAYLIST)
        c.runStartup()
        settings = AppSettings()
        val broken = RefreshCoordinator(
            playlists = { listOf(playlist(1)) },
            epgStatus = { error("db gone") },
            refreshPlaylistFn = { _, _ -> ImportResult.Success(1, 1) },
            refreshEpgFn = { _, _ -> error("boom") },
            settings = { error("settings gone") },
            clock = { now },
        )
        broken.runDue(RefreshKind.EPG)
        broken.runStartup()
        broken.refreshPlaylist(1)
    }

    @Test
    fun cancellationPropagates() = runTest {
        given(1)
        playlistResult = { throw CancellationException("stop") }
        try {
            coordinator().runDue(RefreshKind.PLAYLIST)
            fail("expected CancellationException")
        } catch (_: CancellationException) {
        }
    }

    // --- concurrency: due checks happen inside the coordinator lock ---

    @Test
    fun concurrentPlaylistChainAndEpgJobRefreshEpgOnce() = runTest {
        // The 24 h playlist job and the 12 h EPG job fire together; both see the EPG as due.
        lastPlaylist[1] = ago(25 * 60)
        epgStatus[1] = ok(1, ago(13 * 60))
        playlistGate = CompletableDeferred()
        val c = coordinator()
        val playlistJob = launch { c.runDue(RefreshKind.PLAYLIST) }
        val epgJob = launch { c.runDue(RefreshKind.EPG) }
        runCurrent()
        playlistGate!!.complete(Unit)
        playlistJob.join(); epgJob.join()
        assertEquals(listOf("p1", "e1"), calls)
    }

    @Test
    fun concurrentEpgJobThenPlaylistChainRefreshEpgOnce() = runTest {
        lastPlaylist[1] = ago(25 * 60)
        epgStatus[1] = ok(1, ago(13 * 60))
        epgGate = CompletableDeferred()
        val c = coordinator()
        val epgJob = launch { c.runDue(RefreshKind.EPG) }
        runCurrent()
        val playlistJob = launch { c.runDue(RefreshKind.PLAYLIST) }
        runCurrent()
        epgGate!!.complete(Unit)
        playlistJob.join(); epgJob.join()
        // The EPG was refreshed moments ago (within START_GAP), so the chained EPG update skips it.
        assertEquals(listOf("e1", "p1"), calls)
    }

    @Test
    fun startupDuringPlaylistJobDoesNotRepeatIt() = runTest {
        lastPlaylist[1] = ago(25 * 60)
        epgStatus[1] = ok(1, ago(13 * 60))
        playlistGate = CompletableDeferred()
        val c = coordinator()
        val job = launch { c.runDue(RefreshKind.PLAYLIST) }
        val startup = launch { c.runStartup() }
        runCurrent()
        playlistGate!!.complete(Unit)
        job.join(); startup.join()
        assertEquals(listOf("p1", "e1"), calls)
    }

    @Test
    fun chainSkippedWhenEpgJustUpdated() = runTest {
        given(1)
        epgStatus[1] = ok(1, ago(5))
        coordinator().refreshPlaylist(1)
        assertEquals(listOf("p1"), calls)
    }

    @Test
    fun dueRecheckedBeforeEachRefresh() = runTest {
        // Playlist 2 is refreshed elsewhere (e.g. Settings) while playlist 1 is being refreshed.
        given(1, 2)
        settings = settings.copy(epgUpdateOnPlaylistChange = false)
        playlistResult = { if (it == 1L) lastPlaylist[2] = now; ImportResult.Success(it, 1) }
        coordinator().runDue(RefreshKind.PLAYLIST)
        assertEquals(listOf("p1"), calls)
    }

    // --- runStartup (moved from StartupRefresherTest) ---

    @Test
    fun runsPlaylistThenEpgPerPlaylistSequentially() = runTest {
        given(1, 2)
        coordinator().runStartup()
        assertEquals(listOf("p1", "e1", "p2", "e2"), calls)
    }

    @Test
    fun skipsEpgWhenPlaylistUnreachableOrAuthFailed() = runTest {
        given(1, 2)
        playlistResult = {
            ImportResult.Failure(if (it == 1L) ImportError.Unreachable else ImportError.AuthFailed, "x")
        }
        coordinator().runStartup()
        assertEquals(listOf("p1", "p2"), calls)
    }

    @Test
    fun runsEpgAfterOtherPlaylistFailures() = runTest {
        given(1)
        playlistResult = { ImportResult.Failure(ImportError.EmptyPlaylist, "x") }
        coordinator().runStartup()
        assertEquals(listOf("p1", "e1"), calls)
    }

    @Test
    fun oneThrowingPlaylistDoesNotStopTheNext() = runTest {
        given(1, 2)
        playlistResult = { if (it == 1L) error("boom") else ImportResult.Success(it, 1) }
        coordinator().runStartup()
        assertEquals(listOf("p1", "p2", "e2"), calls)
    }

    @Test
    fun epgFailureDoesNotStopTheNextPlaylist() = runTest {
        given(1, 2)
        epgAction = { if (it == 1L) error("boom") }
        coordinator().runStartup()
        assertEquals(listOf("p1", "e1", "p2", "e2"), calls)
    }

    @Test
    fun startupRunsEpgOnce() = runTest {
        // onStart wants the EPG and the chained EPG update wants it too: still one EPG refresh.
        lastPlaylist[1] = ago(60)
        epgStatus[1] = ok(1, ago(60))
        coordinator().runStartup()
        assertEquals(listOf("p1", "e1"), calls)
    }

    @Test
    fun startupSkipsWithinStartGap() = runTest {
        lastPlaylist[1] = ago(10)
        epgStatus[1] = ok(1, ago(10))
        coordinator().runStartup()
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun startupOnlyEpgWhenPlaylistNotWanted() = runTest {
        lastPlaylist[1] = ago(60)
        epgStatus[1] = ok(1, ago(60))
        settings = settings.copy(playlistUpdateOnStart = false)
        coordinator().runStartup()
        assertEquals(listOf("e1"), calls)
    }

    @Test
    fun startupChainOnlyWhenEpgOnStartOff() = runTest {
        lastPlaylist[1] = ago(60)
        epgStatus[1] = ok(1, ago(60))
        settings = settings.copy(epgUpdateOnStart = false)
        coordinator().runStartup()
        assertEquals(listOf("p1", "e1"), calls)

        calls.clear()
        lastPlaylist[1] = ago(60)
        epgStatus[1] = ok(1, ago(60))
        settings = settings.copy(epgUpdateOnPlaylistChange = false)
        coordinator().runStartup()
        assertEquals(listOf("p1"), calls)
    }

    @Test
    fun startupWithEverythingOffDoesNothing() = runTest {
        lastPlaylist[1] = ago(60)
        epgStatus[1] = ok(1, ago(60))
        settings = AppSettings(
            playlistUpdateHours = 0, playlistUpdateOnStart = false,
            epgUpdateHours = 0, epgUpdateOnStart = false,
        )
        coordinator().runStartup()
        assertEquals(emptyList<String>(), calls)
    }

    // --- per-playlist options and turned-off playlists ---

    @Test
    fun disabledPlaylistNeverAutoRefreshed() = runTest {
        given(1, 2)
        disabled += 1
        val c = coordinator()
        c.runDue(RefreshKind.PLAYLIST)
        c.runDue(RefreshKind.EPG)
        c.runStartup()
        // Playlist 2: due job (+ chained EPG update); EPG job and startup then find it fresh.
        assertEquals(listOf("p2", "e2"), calls)

        // Neither the EPG job nor startup touches a turned-off playlist whose guide is due.
        calls.clear()
        lastPlaylist[1] = ago(60)
        epgStatus[1] = null
        c.runDue(RefreshKind.EPG)
        c.runStartup()
        assertTrue(calls.none { it.endsWith("1") })
    }

    @Test
    fun manualUpdateOfDisabledPlaylistSkipsEpgChain() = runTest {
        given(1)
        disabled += 1
        assertEquals(ImportResult.Success(1, 1), coordinator().refreshPlaylist(1))
        assertEquals(listOf("p1"), calls)
    }

    @Test
    fun perPlaylistIntervalOverridesGeneral() = runTest {
        // General 24 h; playlist 1 has its own 6 h; both were updated 7 h ago.
        lastPlaylist[1] = ago(7 * 60)
        lastPlaylist[2] = ago(7 * 60)
        ownHours[1] = 6
        settings = settings.copy(playlistUpdateHours = 24, epgUpdateOnPlaylistChange = false)
        coordinator().runDue(RefreshKind.PLAYLIST)
        assertEquals(listOf("p1"), calls)

        // Its own "Off" wins over a due general interval.
        calls.clear()
        lastPlaylist[1] = ago(25 * 60)
        ownHours[1] = 0
        lastPlaylist[2] = ago(60)
        coordinator().runDue(RefreshKind.PLAYLIST)
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun nullIntervalUsesGeneral() = runTest {
        lastPlaylist[1] = ago(7 * 60)
        ownHours[1] = null
        settings = settings.copy(playlistUpdateHours = 6, epgUpdateOnPlaylistChange = false)
        coordinator().runDue(RefreshKind.PLAYLIST)
        assertEquals(listOf("p1"), calls)
    }

    @Test
    fun perPlaylistUpdateOnStartOverridesGeneral() = runTest {
        lastPlaylist[1] = ago(60)
        lastPlaylist[2] = ago(60)
        epgStatus[1] = ok(1, ago(10))
        epgStatus[2] = ok(2, ago(10))
        ownOnStart[1] = false
        ownOnStart[2] = null
        settings = settings.copy(playlistUpdateOnStart = true, epgUpdateOnPlaylistChange = false)
        coordinator().runStartup()
        assertEquals(listOf("p2"), calls)

        calls.clear()
        lastPlaylist[1] = ago(60)
        lastPlaylist[2] = ago(60)
        ownOnStart[1] = true
        settings = settings.copy(playlistUpdateOnStart = false)
        coordinator().runStartup()
        assertEquals(listOf("p1"), calls)
    }

    @Test
    fun refreshAllRunsEnabledInOrderOneAtATime() = runTest {
        given(3, 1, 2)
        disabled += 2
        val progress = mutableListOf<Pair<Long, Int>>()
        playlistGate = CompletableDeferred()
        val c = coordinator()
        val job = launch { c.refreshAll { id, count -> progress += id to count } }
        runCurrent()
        // The first refresh is waiting: nothing else has started.
        assertEquals(listOf("p1"), calls)
        playlistGate!!.complete(Unit)
        job.join()
        assertEquals(listOf("p1", "e1", "p3", "e3"), calls)
    }

    @Test
    fun refreshAllReportsProgressPerPlaylist() = runTest {
        given(1, 2)
        val progress = mutableListOf<Pair<Long, Int>>()
        val c = RefreshCoordinator(
            playlists = { listPlaylists() },
            epgStatus = { epgStatus[it] },
            refreshPlaylistFn = { id, p -> p(id.toInt() * 10); ImportResult.Success(id, 1) },
            refreshEpgFn = { id, _ -> ok(id, now) },
            settings = { settings },
            clock = { now },
        )
        val results = c.refreshAll { id, count -> progress += id to count }
        assertEquals(listOf(1L to 10, 2L to 20), progress)
        assertEquals(mapOf(1L to ImportResult.Success(1, 1), 2L to ImportResult.Success(2, 1)), results)
    }

    // --- source edits ---

    private val edit = SourceEdit.M3uUrl("http://example.test/new.m3u")

    @Test
    fun updateSourceAlwaysChainsEpgEvenWhenRecentlyUpdated() = runTest {
        given(1)
        epgStatus[1] = ok(1, ago(5)) // a plain refresh would skip the chain now
        assertEquals(ImportResult.Success(1, 1), coordinator().updateSource(1, edit))
        assertEquals(listOf("s1", "e1"), calls)
    }

    @Test
    fun updateSourceChainFollowsTheSettingAndSuccess() = runTest {
        given(1)
        settings = settings.copy(epgUpdateOnPlaylistChange = false)
        coordinator().updateSource(1, edit)
        assertEquals(listOf("s1"), calls)

        calls.clear()
        settings = AppSettings()
        playlistResult = { ImportResult.Failure(ImportError.Unreachable, "x") }
        coordinator().updateSource(1, edit)
        assertEquals(listOf("s1"), calls)

        calls.clear()
        playlistResult = { ImportResult.Success(it, 1) }
        disabled += 1
        coordinator().updateSource(1, edit)
        assertEquals(listOf("s1"), calls)
    }

    @Test
    fun refreshAllRereadsEachPlaylistBeforeItsTurn() = runTest {
        given(1, 2, 3)
        settings = settings.copy(epgUpdateOnPlaylistChange = false)
        // While 1 refreshes, 2 is turned off and 3 deleted (e.g. from Settings).
        playlistResult = {
            if (it == 1L) { disabled += 2; lastPlaylist.remove(3L) }
            ImportResult.Success(it, 1)
        }
        val results = coordinator().refreshAll()
        assertEquals(listOf("p1"), calls)
        assertEquals(setOf(1L), results.keys)
    }
}
