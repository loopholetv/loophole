package io.github.loopholetv.work

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import io.github.loopholetv.data.db.EpgStatusEntity
import io.github.loopholetv.data.db.PlaylistEntity
import io.github.loopholetv.data.db.PlaylistType
import io.github.loopholetv.data.repo.ImportResult
import io.github.loopholetv.data.repo.RefreshCoordinator
import io.github.loopholetv.data.repo.RefreshKind
import io.github.loopholetv.data.settings.AppSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/** Test application: a [RefreshRunnerProvider] whose coordinator records calls (never-refreshed playlist 1). */
class FakeRunnerApp : Application(), RefreshRunnerProvider {
    val calls = mutableListOf<String>()
    var fail = false

    override val refreshCoordinator = RefreshCoordinator(
        playlists = {
            if (fail) error("db gone")
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
        settings = { AppSettings(epgUpdateOnPlaylistChange = false) },
    )
}

@RunWith(RobolectricTestRunner::class)
@Config(application = FakeRunnerApp::class)
class RefreshWorkerTest {
    private lateinit var app: FakeRunnerApp

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
    }

    private fun run(kind: String?): ListenableWorker.Result {
        val builder = TestListenableWorkerBuilder<RefreshWorker>(app as Context)
        if (kind != null) builder.setInputData(workDataOf(RefreshWorker.KEY_KIND to kind))
        return runBlocking { builder.build().doWork() }
    }

    @Test
    fun runsDueForKindAndSucceeds() {
        assertEquals(ListenableWorker.Result.success(), run(RefreshKind.PLAYLIST.name))
        assertEquals(listOf("p1"), app.calls)

        app.calls.clear()
        assertEquals(ListenableWorker.Result.success(), run(RefreshKind.EPG.name))
        assertEquals(listOf("e1"), app.calls)
    }

    @Test
    fun succeedsEvenWhenRunnerFails() {
        app.fail = true
        assertEquals(ListenableWorker.Result.success(), run(RefreshKind.PLAYLIST.name))
        assertEquals(emptyList<String>(), app.calls)
    }

    @Test
    fun unknownKindSucceedsWithoutWork() {
        assertEquals(ListenableWorker.Result.success(), run("BOGUS"))
        assertEquals(ListenableWorker.Result.success(), run(null))
        assertEquals(emptyList<String>(), app.calls)
    }
}
