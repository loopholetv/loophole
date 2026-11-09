package io.github.loopholetv.data.repo

import io.github.loopholetv.data.db.EpgStatusEntity
import io.github.loopholetv.data.db.PlaylistEntity
import io.github.loopholetv.data.db.PlaylistType
import io.github.loopholetv.data.settings.AppSettings
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
}
