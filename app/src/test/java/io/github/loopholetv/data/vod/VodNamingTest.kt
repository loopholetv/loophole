package io.github.loopholetv.data.vod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VodNamingTest {
    @Test fun kindOfMovieAndSeries() {
        assertEquals(VodKind.MOVIE, vodKindOf(" Movie "))
        assertEquals(VodKind.EPISODE, vodKindOf("SERIES"))
    }

    @Test fun kindOfAnythingElseIsNull() {
        assertNull(vodKindOf("live"))
        assertNull(vodKindOf(null))
        assertNull(vodKindOf(""))
    }

    @Test fun keyDropsQueryAndFragment() {
        assertEquals("http://h/movie/u/p/1.mkv", vodKey("http://h/movie/u/p/1.mkv?token=abc#x"))
    }

    @Test fun keyKeepsPlainUrl() {
        assertEquals("http://h/movie/u/p/1.mkv", vodKey("http://h/movie/u/p/1.mkv"))
    }

    @Test fun parsesEpisodeNumbers() {
        assertEquals(EpisodeNumber(1, 3), parseEpisodeNumber("Show S01E03"))
        assertEquals(EpisodeNumber(1, 12), parseEpisodeNumber("show s1 e12"))
        assertEquals(EpisodeNumber(1, 3), parseEpisodeNumber("Show S01  E03 Title"))
        assertNull(parseEpisodeNumber("Show 1x03"))
    }

    @Test fun showNameIsTextBeforeMatch() {
        assertEquals("Show", showNameFrom("Show - S01E03"))
        assertNull(showNameFrom("S01E03"))
        assertNull(showNameFrom("No number here"))
    }

    @Test fun showStyleGroupIsOneShow() {
        val result = assignShows(
            listOf(
                EpisodeShowInput("k1", "Breaking Show", "Breaking Show S01E01"),
                EpisodeShowInput("k2", "Breaking Show", "Breaking Show S01E02"),
                EpisodeShowInput("k3", "Breaking Show", "Breaking Show Special"),
            ),
        )
        listOf("k1", "k2", "k3").forEach {
            assertEquals(ShowAssignment("g:Breaking Show", "Breaking Show", null), result[it])
        }
    }

    @Test fun categoryStyleGroupSplitsIntoShows() {
        val result = assignShows(
            listOf(
                EpisodeShowInput("a1", "Netflix", "Alpha S01E01"),
                EpisodeShowInput("a2", "Netflix", "Alpha S01E02"),
                EpisodeShowInput("b1", "Netflix", "Beta S01E01"),
                EpisodeShowInput("b2", "Netflix", "Beta S02E01"),
            ),
        )
        assertEquals("Alpha", result["a1"]!!.showName)
        assertEquals(result["a1"]!!.showKey, result["a2"]!!.showKey)
        assertEquals("Beta", result["b1"]!!.showName)
        assertEquals(result["b1"]!!.showKey, result["b2"]!!.showKey)
        assertEquals(2, result.values.map { it.showKey }.distinct().size)
        assertEquals("Netflix", result["a1"]!!.category)
        assertEquals("Netflix", result["b2"]!!.category)
    }

    private data class Ep(val season: Int?, val episode: Int?, val position: Int)

    @Test fun sortsBySeasonThenEpisodeNullsLast() {
        val s2e1 = Ep(2, 1, 0)
        val s1e2 = Ep(1, 2, 1)
        val none = Ep(null, null, 2)
        val s1e1 = Ep(1, 1, 3)
        val sorted = sortEpisodes(listOf(s2e1, s1e2, none, s1e1), { it.season }, { it.episode }, { it.position })
        assertEquals(listOf(s1e1, s1e2, s2e1, none), sorted)
    }
}
