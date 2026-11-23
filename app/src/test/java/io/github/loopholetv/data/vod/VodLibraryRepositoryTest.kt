package io.github.loopholetv.data.vod

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.db.ChannelPrefsEntity
import io.github.loopholetv.data.db.EpisodeEntity
import io.github.loopholetv.data.db.FilmEntity
import io.github.loopholetv.data.db.KeyRef
import io.github.loopholetv.data.db.ShowEntity
import io.github.loopholetv.data.db.VodProgressEntity
import io.github.loopholetv.data.db.addPlaylist
import io.github.loopholetv.data.db.inMemoryDb
import io.github.loopholetv.data.db.matchTitle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class VodLibraryRepositoryTest {
    private var nowMs = 1_000L
    private lateinit var db: AppDatabase
    private lateinit var library: VodLibraryRepository
    private var p = 0L

    private fun at(ms: Long) = Instant.ofEpochMilli(ms)

    @Before fun setUp() = runTest {
        db = inMemoryDb()
        library = VodLibraryRepository(db, VodRepository(db) { at(nowMs) }) { at(nowMs) }
        p = db.addPlaylist()
    }

    @After fun tearDown() = db.close()

    private suspend fun show(key: String = "s", name: String = "Show $key") =
        db.vodWriteDao().insertShows(listOf(ShowEntity(p, key, name, "G", "Cat", "http://logo.test/$key.png", at(0), 0)))

    private suspend fun episodes(showKey: String, vararg numbers: Int) = db.vodWriteDao().upsertEpisodes(
        numbers.mapIndexed { i, n ->
            EpisodeEntity(p, "e$n", showKey, "G", "Ep $n", 1, n, null, "http://s.test/e$n.mp4", null, null, null, i, at(0))
        },
    )

    private suspend fun watch(n: Int, positionMs: Long, lastWatched: Long, watched: Boolean = false, durationMs: Long? = 120_000) =
        db.vodProgressDao().upsert(VodProgressEntity(VodKind.EPISODE, p, "e$n", positionMs, durationMs, watched, at(lastWatched)))

    private suspend fun film(key: String) = db.vodWriteDao().upsertFilms(
        listOf(FilmEntity(p, key, key, "C", null, "http://s.test/$key.mp4", null, null, null, 0, at(0))),
    )

    @Test fun continueShowCardUsesDisplayTitle() = runTest {
        show(name = "BB US"); episodes("s", 1, 2)
        watch(1, 0, 100, watched = true)
        db.matchTitle(p, TitleKind.SHOW, "s", 1396, "Breaking Bad", posterPath = "/bb.jpg", badge = "4K")

        val card = library.continueShows().first().single()
        assertEquals("Breaking Bad", card.title)
        assertEquals("https://image.tmdb.org/t/p/w342/bb.jpg", card.logo)
        assertEquals("4K", card.badge)
        assertEquals("S1 E2", card.caption)
    }

    @Test fun continueFilmCardUsesDisplayTitle() = runTest {
        film("f")
        db.vodProgressDao().upsert(VodProgressEntity(VodKind.MOVIE, p, "f", 60_000, 120_000, false, at(5)))
        db.matchTitle(p, TitleKind.FILM, "f", 949, "Heat", posterPath = "/h.jpg", badge = "HD")

        val card = library.continueFilms().first().single()
        assertEquals("Heat", card.title)
        assertEquals("https://image.tmdb.org/t/p/w342/h.jpg", card.logo)
        assertEquals("HD", card.badge)
    }

    @Test fun continueShowsPointsAtNextEpisode() = runTest {
        show(); episodes("s", 1, 2, 3)
        watch(1, 0, 100, watched = true)

        val next = library.continueShows().first().single()
        assertEquals("Show s", next.title)
        assertEquals("s", next.key)
        assertEquals("http://logo.test/s.png", next.logo)
        assertEquals("S1 E2", next.caption)
        assertEquals(VodRef(VodKind.EPISODE, p, "e2"), next.play)
        assertNull(next.fraction)
        assertEquals(0L, next.startMs)

        watch(2, 60_000, 200)
        val resumed = library.continueShows().first().single()
        assertEquals(VodRef(VodKind.EPISODE, p, "e2"), resumed.play)
        assertEquals(0.5f, resumed.fraction)
        assertEquals(60_000L, resumed.startMs)
    }

    @Test fun continueShowsDropsFinishedShow() = runTest {
        show(); episodes("s", 1, 2)
        watch(1, 0, 100, watched = true)
        watch(2, 0, 200, watched = true)
        assertEquals(emptyList<ContinueCard>(), library.continueShows().first())
    }

    @Test fun continueShowsDismissalClearsOnNewPlay() = runTest {
        show(); episodes("s", 1, 2, 3)
        watch(1, 0, 100, watched = true)
        nowMs = 150
        library.dismissContinue(TitleKind.SHOW, p, "s")
        assertEquals(emptyList<ContinueCard>(), library.continueShows().first())

        watch(2, 0, 300, watched = true)
        assertEquals("S1 E3", library.continueShows().first().single().caption)
    }

    @Test fun continueShowsFollowsRefreshThatRemovesNextEpisode() = runTest {
        show(); episodes("s", 1, 2, 3, 4, 5)
        watch(3, 0, 100, watched = true)
        assertEquals("S1 E4", library.continueShows().first().single().caption)

        db.vodWriteDao().deleteEpisodes(p, listOf("e4"))
        assertEquals(VodRef(VodKind.EPISODE, p, "e5"), library.continueShows().first().single().play)

        db.vodWriteDao().deleteShows(p)
        db.vodWriteDao().deleteEpisodes(p, listOf("e1", "e2", "e3", "e5"))
        assertEquals(emptyList<ContinueCard>(), library.continueShows().first())
    }

    @Test fun continueShowsUpdatesLiveWhenTargetEpisodeIsHidden() = runTest {
        show(); episodes("s", 1, 2, 3)
        watch(1, 0, 100, watched = true)
        val first = CompletableDeferred<Unit>()
        val emissions = async(Dispatchers.Default) { library.continueShows().onEach { first.complete(Unit) }.take(2).toList() }
        withContext(Dispatchers.Default) { withTimeout(5_000) { first.await() } }

        db.channelPrefsDao().upsert(ChannelPrefsEntity(p, "e2", hidden = true))
        val seen = withContext(Dispatchers.Default) { withTimeout(5_000) { emissions.await() } }
        assertEquals(VodRef(VodKind.EPISODE, p, "e2"), seen[0].single().play)
        assertEquals(VodRef(VodKind.EPISODE, p, "e3"), seen[1].single().play)
    }

    @Test fun continueFilmsCarryProgress() = runTest {
        film("f")
        db.vodProgressDao().upsert(VodProgressEntity(VodKind.MOVIE, p, "f", 30_000, 120_000, false, at(5)))
        val card = library.continueFilms().first().single()
        assertEquals(VodRef(VodKind.MOVIE, p, "f"), card.play)
        assertEquals(0.25f, card.fraction)
        assertEquals(30_000L, card.startMs)
        assertNull(card.caption)
    }

    @Test fun myListToggleAndOrder() = runTest {
        film("a"); film("b")
        assertEquals(false, library.inMyList(TitleKind.FILM, p, "a").first())

        nowMs = 10
        library.setInMyList(TitleKind.FILM, p, "a", true)
        nowMs = 20
        library.setInMyList(TitleKind.FILM, p, "b", true)
        assertEquals(listOf("b", "a"), library.myListFilms().first().map { it.key })
        assertEquals(true, library.inMyList(TitleKind.FILM, p, "a").first())
        assertEquals(setOf(KeyRef(p, "a"), KeyRef(p, "b")), library.myListKeys(TitleKind.FILM).first())
        assertEquals(emptySet<KeyRef>(), library.myListKeys(TitleKind.SHOW).first())

        library.setInMyList(TitleKind.FILM, p, "b", false)
        assertEquals(listOf("a"), library.myListFilms().first().map { it.key })
        assertEquals(false, library.inMyList(TitleKind.FILM, p, "b").first())
    }
}
