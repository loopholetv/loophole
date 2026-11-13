package io.github.loopholetv.data.repo

import io.github.loopholetv.data.db.EpgStatusEntity
import io.github.loopholetv.data.db.PlaylistEntity
import io.github.loopholetv.data.db.PlaylistType
import io.github.loopholetv.data.settings.AppSettings
import io.github.loopholetv.data.settings.VodFillFlags
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/** Startup policy itself is covered by [RefreshCoordinatorTest]; this covers the launcher. */
@OptIn(ExperimentalCoroutinesApi::class)
class StartupRefresherTest {

    @Test
    fun startsOnlyOncePerProcess() = runTest {
        var runs = 0
        val r = StartupRefresher(run = { runs++ }, scope = this)
        r.start()
        assertNull(r.start())
        advanceUntilIdle()
        assertEquals(1, runs)
    }

    @Test
    fun swallowsRunnerFailure() = runTest {
        var ran = false
        // An escaping exception would fail runTest (it is launched in the test scope).
        StartupRefresher(run = { ran = true; error("boom") }, scope = this).start()
        advanceUntilIdle()
        assertEquals(true, ran)
    }

    @Test
    fun coordinatorConstructorRunsStartup() = runTest {
        val calls = mutableListOf<String>()
        val coordinator = RefreshCoordinator(
            playlists = {
                listOf(
                    PlaylistEntity(
                        id = 1, name = "P", type = PlaylistType.M3U, url = "http://x", xtreamServer = null,
                        xtreamUser = null, xtreamPass = null, epgUrlsFromSource = "", manualEpgUrl = null,
                        lastRefreshedAt = null, lastError = null,
                    ),
                )
            },
            epgStatus = { null },
            refreshPlaylistFn = { id, _ -> calls += "p$id"; ImportResult.Success(id, 1) },
            refreshEpgFn = { id, _ -> calls += "e$id"; EpgStatusEntity(id, Instant.EPOCH, 0, true, null) },
            settings = { AppSettings() },
        )
        StartupRefresher(coordinator, this).start()
        advanceUntilIdle()
        assertEquals(listOf("p1", "e1"), calls)
    }

    // --- one-off film and show fill after the update ---

    private class FakeFlags : VodFillFlags {
        var filled = false
        val pending = mutableSetOf<Long>()
        override suspend fun isFilled() = filled
        override suspend fun markFilled(skipped: Set<Long>) { filled = true; pending += skipped }
        override suspend fun skipped(): Set<Long> = pending.toSet()
        override suspend fun clearSkipped(id: Long) { pending -= id }
    }

    private class FillEnv(types: List<PlaylistType>, var failIds: Set<Long> = emptySet(), off: Set<Long> = emptySet()) {
        val playlists = types.mapIndexed { i, t ->
            PlaylistEntity(
                id = i + 1L, name = "P$i", type = t, url = "http://x", xtreamServer = null,
                xtreamUser = null, xtreamPass = null, epgUrlsFromSource = "", manualEpgUrl = null,
                lastRefreshedAt = null, lastError = null,
            ).let { if (it.id in off) it.copy(enabled = false) else it }
        }
        val flags = FakeFlags()
        val filled get() = flags.filled
        val refreshed = mutableListOf<Long>()
        val fill = VodFill(
            playlists = { playlists },
            refresh = { id ->
                refreshed += id
                if (id in failIds) ImportResult.Failure(ImportError.Unreachable, "down") else ImportResult.Success(id, 1)
            },
            flags = flags,
        )
    }

    @Test
    fun refreshesM3uOnceAfterUpdate() = kotlinx.coroutines.test.runTest {
        val env = FillEnv(listOf(PlaylistType.M3U, PlaylistType.M3U_FILE))
        StartupRefresher(run = {}, scope = this, vodFill = env.fill).start()
        advanceUntilIdle()
        assertEquals(listOf(1L, 2L), env.refreshed)
        assertEquals(true, env.filled)
        StartupRefresher(run = {}, scope = this, vodFill = env.fill).start()
        advanceUntilIdle()
        assertEquals(listOf(1L, 2L), env.refreshed)
    }

    @Test
    fun failedFillRetriesNextStart() = kotlinx.coroutines.test.runTest {
        val env = FillEnv(listOf(PlaylistType.M3U, PlaylistType.M3U), failIds = setOf(2L))
        StartupRefresher(run = {}, scope = this, vodFill = env.fill).start()
        advanceUntilIdle()
        assertEquals(listOf(1L, 2L), env.refreshed)
        assertEquals(false, env.filled)
        env.failIds = emptySet()
        StartupRefresher(run = {}, scope = this, vodFill = env.fill).start()
        advanceUntilIdle()
        assertEquals(true, env.filled)
    }

    @Test
    fun turnedOffPlaylistsAreNotContactedByTheFill() = kotlinx.coroutines.test.runTest {
        val env = FillEnv(listOf(PlaylistType.M3U, PlaylistType.M3U_FILE, PlaylistType.XTREAM), off = setOf(2L, 3L))
        StartupRefresher(run = {}, scope = this, vodFill = env.fill).start()
        advanceUntilIdle()
        assertEquals(listOf(1L), env.refreshed)
        assertEquals(true, env.filled)
        assertEquals(setOf(2L), env.flags.pending)
    }

    @Test
    fun turningASkippedPlaylistBackOnFillsIt() = kotlinx.coroutines.test.runTest {
        val env = FillEnv(listOf(PlaylistType.M3U, PlaylistType.M3U), off = setOf(2L))
        env.fill.run()
        env.refreshed.clear()

        env.fill.afterEnabled(1L)
        assertEquals(emptyList<Long>(), env.refreshed)
        env.fill.afterEnabled(2L)
        assertEquals(listOf(2L), env.refreshed)
        assertEquals(emptySet<Long>(), env.flags.pending)
        env.fill.afterEnabled(2L)
        assertEquals(listOf(2L), env.refreshed)
    }

    @Test
    fun aFailedFillOnTurningBackOnTriesAgainNextTime() = kotlinx.coroutines.test.runTest {
        val env = FillEnv(listOf(PlaylistType.M3U), off = setOf(1L))
        env.fill.run()
        env.failIds = setOf(1L)

        env.fill.afterEnabled(1L)
        assertEquals(setOf(1L), env.flags.pending)
        env.failIds = emptySet()
        env.fill.afterEnabled(1L)
        assertEquals(listOf(1L, 1L), env.refreshed)
        assertEquals(emptySet<Long>(), env.flags.pending)
    }

    @Test
    fun xtreamNotForced() = kotlinx.coroutines.test.runTest {
        val env = FillEnv(listOf(PlaylistType.XTREAM, PlaylistType.STALKER))
        StartupRefresher(run = {}, scope = this, vodFill = env.fill).start()
        advanceUntilIdle()
        assertEquals(emptyList<Long>(), env.refreshed)
        assertEquals(true, env.filled)
    }

    @Test
    fun fillRunsBeforeTheRegularStartupUpdate() = kotlinx.coroutines.test.runTest {
        val env = FillEnv(listOf(PlaylistType.M3U))
        val order = mutableListOf<String>()
        val fill = VodFill({ env.playlists }, { order += "fill"; ImportResult.Success(it, 1) }, FakeFlags())
        StartupRefresher(run = { order += "startup" }, scope = this, vodFill = fill).start()
        advanceUntilIdle()
        assertEquals(listOf("fill", "startup"), order)
    }

    @Test
    fun freshInstallSetsFlagWithoutRefreshing() = kotlinx.coroutines.test.runTest {
        val env = FillEnv(emptyList())
        StartupRefresher(run = {}, scope = this, vodFill = env.fill).markFilledWithoutPlaylists()
        advanceUntilIdle()
        assertEquals(true, env.filled)
        assertEquals(emptyList<Long>(), env.refreshed)
    }
}
