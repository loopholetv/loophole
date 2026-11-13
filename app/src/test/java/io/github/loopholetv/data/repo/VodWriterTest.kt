package io.github.loopholetv.data.repo

import io.github.loopholetv.data.db.ChannelPrefsDao
import io.github.loopholetv.data.db.ChannelPrefsEntity
import io.github.loopholetv.data.db.EpisodeEntity
import io.github.loopholetv.data.db.EpisodeForShows
import io.github.loopholetv.data.db.FilmEntity
import io.github.loopholetv.data.db.KeyFirstSeen
import io.github.loopholetv.data.db.KeyShowKey
import io.github.loopholetv.data.db.ShowEntity
import io.github.loopholetv.data.db.VodWriteDao
import io.github.loopholetv.data.vod.VodKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class VodWriterTest {
    private class FakeVodDao : VodWriteDao {
        val stored = LinkedHashMap<String, EpisodeEntity>()
        var showKeyUpdates = 0
        val filmBatches = ArrayList<List<FilmEntity>>()
        val episodeBatches = ArrayList<List<EpisodeEntity>>()
        val shows = ArrayList<ShowEntity>()

        override suspend fun filmKeys(p: Long) = filmBatches.flatten().map { KeyFirstSeen(it.key, it.firstSeenAt) }
        override suspend fun episodeKeys(p: Long) = stored.values.map { KeyFirstSeen(it.key, it.firstSeenAt) }
        override suspend fun episodeShowKeys(p: Long) = stored.values.map { KeyShowKey(it.key, it.showKey) }
        override suspend fun upsertFilms(rows: List<FilmEntity>) { filmBatches += rows }
        override suspend fun upsertEpisodes(rows: List<EpisodeEntity>) {
            episodeBatches += rows
            rows.forEach { stored[it.key] = it }
        }
        override suspend fun deleteFilms(p: Long, keys: List<String>) = Unit
        override suspend fun deleteEpisodes(p: Long, keys: List<String>) = Unit
        override suspend fun deleteShows(p: Long) { shows.clear() }
        override suspend fun insertShows(rows: List<ShowEntity>) { shows += rows }
        override suspend fun filmNameGroups(p: Long) = emptyList<KeyNameGroup>()
        override suspend fun episodeNameGroups(p: Long) = emptyList<KeyNameGroup>()
        override suspend fun episodesForShows(p: Long) = stored.values.map {
            EpisodeForShows(it.key, it.groupTitle, it.name, it.showKey, it.logo, it.position, it.firstSeenAt)
        }
        override suspend fun setShowKey(p: Long, key: String, showKey: String) {
            showKeyUpdates++
            stored[key] = stored.getValue(key).copy(showKey = showKey)
        }
    }

    private class FakePrefs : ChannelPrefsDao {
        override suspend fun get(playlistId: Long, key: String): ChannelPrefsEntity? = null
        override fun observe(playlistId: Long, key: String): Flow<ChannelPrefsEntity?> = error("unused")
        override suspend fun upsert(prefs: ChannelPrefsEntity) = Unit
        override suspend fun delete(playlistId: Long, key: String) = Unit
        override suspend fun forPlaylist(playlistId: Long) = emptyList<ChannelPrefsEntity>()
        override suspend fun epgOverrides(playlistId: Long) = emptyList<ChannelPrefsEntity>()
        override suspend fun maxPositiveShift(): Int? = null
    }

    private fun vod(i: Int, url: String = "http://s.test/$i.mp4", key: String = url, kind: VodKind = VodKind.MOVIE) =
        ImportedVod(kind, key, "Film $i", url, null, "G", null, null, null)

    @Test fun flushesInBatches() = runTest {
        val dao = FakeVodDao()
        val writer = VodWriter(dao, FakePrefs(), 1, Instant.EPOCH)

        for (i in 1..1_201) writer.add(vod(i))
        assertEquals(2, dao.filmBatches.size)
        assertEquals(1_201, writer.finish())

        assertEquals(3, dao.filmBatches.size)
        assertEquals(listOf(500, 500, 201), dao.filmBatches.map { it.size })
    }

    @Test fun skipsBlankUrlAndTrueDuplicates() = runTest {
        val dao = FakeVodDao()
        val writer = VodWriter(dao, FakePrefs(), 1, Instant.EPOCH)

        writer.add(vod(1, url = "  "))
        writer.add(vod(2, url = "http://s.test/a.mp4?t=1", key = "http://s.test/a.mp4"))
        writer.add(vod(3, url = "http://s.test/a.mp4?t=1", key = "http://s.test/a.mp4"))
        writer.add(vod(4, url = "http://s.test/b.mp4", key = "http://s.test/b.mp4"))
        writer.add(vod(5, url = "http://s.test/b.mp4", key = "http://s.test/b.mp4"))

        assertEquals(2, writer.finish())
        assertEquals(listOf("Film 2", "Film 4"), dao.filmBatches.flatten().map { it.name })
    }

    @Test fun itemsTellingApartOnlyByQueryKeepTheirOwnKeys() = runTest {
        val dao = FakeVodDao()
        val writer = VodWriter(dao, FakePrefs(), 1, Instant.EPOCH)
        val base = "http://s.test/movie.php"

        writer.add(vod(1, url = "$base?id=123", key = base))
        writer.add(vod(2, url = "$base?id=456", key = base))
        writer.add(vod(3, url = "$base?id=456", key = base))
        writer.add(vod(4, url = "$base?id=7", key = base, kind = VodKind.EPISODE).copy(name = "Show S01E01"))
        writer.add(vod(5, url = "$base?id=8", key = base, kind = VodKind.EPISODE).copy(name = "Show S01E02"))

        assertEquals(4, writer.finish())
        assertEquals(
            listOf("Film 1" to base, "Film 2" to "$base?id=456"),
            dao.filmBatches.flatten().map { it.name to it.key },
        )
        assertEquals(listOf(base, "$base?id=8"), dao.stored.keys.toList())
    }

    @Test fun aShowCountsAsNewWhenItGainsANewEpisode() = runTest {
        val dao = FakeVodDao()
        val later = Instant.ofEpochSecond(2_000_000)
        suspend fun import(at: Instant, episodes: Int) {
            val writer = VodWriter(dao, FakePrefs(), 1, at)
            for (e in 1..episodes) {
                writer.add(vod(e, url = "http://s.test/s$e.mp4", kind = VodKind.EPISODE).copy(name = "Show S0${e}E01", group = "Show"))
            }
            writer.finish()
        }

        import(Instant.ofEpochSecond(1_000_000), episodes = 2)
        assertEquals(listOf(Instant.EPOCH), dao.shows.map { it.firstSeenAt })
        import(later, episodes = 3)

        assertEquals(listOf(later), dao.shows.map { it.firstSeenAt })
    }

    @Test fun aRekeyImportCountsNewKeysAsOld() = runTest {
        val dao = FakeVodDao()
        VodWriter(dao, FakePrefs(), 1, Instant.ofEpochSecond(1_000_000)).run {
            add(vod(1, url = "http://old.test/1.mp4"))
            finish()
        }
        val writer = VodWriter(dao, FakePrefs(), 1, Instant.ofEpochSecond(2_000_000), rekey = true)
        writer.add(vod(1, url = "http://new.test/1.mp4"))
        writer.add(vod(2, url = "http://new.test/2.mp4"))
        writer.finish()

        assertEquals(listOf(Instant.EPOCH, Instant.EPOCH), dao.filmBatches.last().map { it.firstSeenAt })
    }

    @Test fun anOrdinaryUpdateMarksNewKeysWithItsTime() = runTest {
        val dao = FakeVodDao()
        val later = Instant.ofEpochSecond(2_000_000)
        VodWriter(dao, FakePrefs(), 1, Instant.ofEpochSecond(1_000_000)).run {
            add(vod(1))
            finish()
        }
        val writer = VodWriter(dao, FakePrefs(), 1, later)
        writer.add(vod(1))
        writer.add(vod(2))
        writer.finish()

        assertEquals(listOf(Instant.EPOCH, later), dao.filmBatches.last().map { it.firstSeenAt })
    }

    @Test fun repeatedImportDoesNotUpdateShowKeys() = runTest {
        val dao = FakeVodDao()
        // A category-style group: several shows, so the real key differs from the provisional one.
        suspend fun import() {
            val writer = VodWriter(dao, FakePrefs(), 1, Instant.EPOCH)
            for (show in listOf("Alpha", "Beta")) for (e in 1..3) {
                writer.add(vod(e, url = "http://s.test/$show$e.mp4", kind = VodKind.EPISODE).copy(name = "$show S01E0$e", group = "Cat"))
            }
            writer.add(vod(9, url = "http://s.test/Gamma.mp4", kind = VodKind.EPISODE).copy(name = "Gamma S01E01", group = "Cat"))
            writer.finish()
        }

        import()
        assertEquals(7, dao.showKeyUpdates)
        dao.showKeyUpdates = 0
        import()

        assertEquals(0, dao.showKeyUpdates)
    }
}
