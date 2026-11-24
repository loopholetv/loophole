package io.github.loopholetv.data.tmdb

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TmdbMatcherTest {
    private class FakeLookup : TmdbLookup {
        val calls = mutableListOf<String>()
        var find: TmdbResult<FindResult> = TmdbResult.NotFound
        var movies: (String, Int?) -> TmdbResult<List<SearchHit>> = { _, _ -> TmdbResult.Ok(emptyList()) }
        var shows: (String, Int?) -> TmdbResult<List<SearchHit>> = { _, _ -> TmdbResult.Ok(emptyList()) }

        override suspend fun findImdb(id: String, language: String): TmdbResult<FindResult> {
            calls += "find:$id"
            return find
        }

        override suspend fun searchMovie(query: String, year: Int?, language: String): TmdbResult<List<SearchHit>> {
            calls += "movie:$query:$year"
            return movies(query, year)
        }

        override suspend fun searchTv(query: String, year: Int?, language: String): TmdbResult<List<SearchHit>> {
            calls += "tv:$query:$year"
            return shows(query, year)
        }
    }

    private fun hit(id: Long, title: String, year: Int?, original: String? = null) =
        SearchHit(id, title, original, year, 10)

    private fun ok(vararg hits: SearchHit) = TmdbResult.Ok(hits.toList())

    private fun matcher(f: FakeLookup) = TmdbMatcher(f)

    @Test fun imdbIdUsesFind() = runTest {
        val f = FakeLookup().apply { find = TmdbResult.Ok(FindResult(listOf(603L, 9L), listOf(5L))) }
        assertEquals(MatchOutcome.Matched(TmdbKind.MOVIE, 603), matcher(f).matchFilm("The Matrix", "tt0133093", "en-US"))
        assertEquals(listOf("find:tt0133093"), f.calls)
    }

    @Test fun showImdbIdUsesFindTvResults() = runTest {
        val f = FakeLookup().apply { find = TmdbResult.Ok(FindResult(listOf(1L), listOf(5L))) }
        assertEquals(MatchOutcome.Matched(TmdbKind.TV, 5), matcher(f).matchShow("Show", "tt123", "en-US"))
    }

    @Test fun imdbMissFallsBackToSearch() = runTest {
        val f = FakeLookup().apply {
            find = TmdbResult.Ok(FindResult(emptyList(), emptyList()))
            movies = { _, _ -> ok(hit(603, "The Matrix", 1999)) }
        }
        val r = matcher(f).matchFilm("The Matrix (1999)", "tt0133093", "en-US")
        assertEquals(MatchOutcome.Matched(TmdbKind.MOVIE, 603), r)
        assertEquals(listOf("find:tt0133093", "movie:The Matrix:1999"), f.calls)
    }

    @Test fun numericShowIdNeedsNoRequest() = runTest {
        val f = FakeLookup()
        assertEquals(MatchOutcome.Matched(TmdbKind.TV, 1399), matcher(f).matchShow("Anything", "1399", "en-US"))
        assertTrue(f.calls.isEmpty())
    }

    @Test fun searchAcceptsExactTitleWithinOneYear() = runTest {
        val f = FakeLookup().apply { movies = { _, _ -> ok(hit(1, "The Matrix", 2000)) } }
        assertEquals(MatchOutcome.Matched(TmdbKind.MOVIE, 1), matcher(f).matchFilm("The Matrix (1999)", null, "en-US"))
    }

    @Test fun searchRejectsYearOffByTwo() = runTest {
        val f = FakeLookup().apply { movies = { _, _ -> ok(hit(1, "The Matrix", 2001)) } }
        assertEquals(MatchOutcome.NotFound, matcher(f).matchFilm("The Matrix (1999)", null, "en-US"))
    }

    @Test fun searchRejectsDifferentTopTitle() = runTest {
        val f = FakeLookup().apply {
            movies = { _, _ -> ok(hit(2, "The Matrix Reloaded", 2003), hit(1, "The Matrix", 1999)) }
        }
        assertEquals(MatchOutcome.NotFound, matcher(f).matchFilm("The Matrix", null, "en-US"))
    }

    @Test fun searchMatchesOriginalTitle() = runTest {
        val f = FakeLookup().apply { movies = { _, _ -> ok(hit(7, "Amelie", 2001, original = "Le Fabuleux Destin d'Amélie Poulain")) } }
        val r = matcher(f).matchFilm("Le Fabuleux Destin d'Amélie Poulain", null, "en-US")
        assertEquals(MatchOutcome.Matched(TmdbKind.MOVIE, 7), r)
    }

    @Test fun unknownYearOnEitherSideStillMatches() = runTest {
        val f = FakeLookup().apply { movies = { _, _ -> ok(hit(1, "The Matrix", null)) } }
        assertEquals(MatchOutcome.Matched(TmdbKind.MOVIE, 1), matcher(f).matchFilm("The Matrix (1999)", null, "en-US"))
    }

    @Test fun yearSearchWithNoHitsRetriesWithoutYear() = runTest {
        val f = FakeLookup().apply {
            movies = { _, year -> if (year != null) ok() else ok(hit(1, "The Matrix", 2000)) }
        }
        assertEquals(MatchOutcome.Matched(TmdbKind.MOVIE, 1), matcher(f).matchFilm("The Matrix (1999)", null, "en-US"))
        assertEquals(listOf("movie:The Matrix:1999", "movie:The Matrix:null"), f.calls)
    }

    @Test fun noYearRetryStillAppliesYearTolerance() = runTest {
        val f = FakeLookup().apply {
            movies = { _, year -> if (year != null) ok() else ok(hit(1, "The Matrix", 2005)) }
        }
        assertEquals(MatchOutcome.NotFound, matcher(f).matchFilm("The Matrix (1999)", null, "en-US"))
    }

    @Test fun bareYearAlsoTriesYearAsPartOfTitle() = runTest {
        val f = FakeLookup().apply {
            movies = { q, _ -> if (q == "Wonder Woman 1984") ok(hit(9, "Wonder Woman 1984", 2020)) else ok() }
        }
        val r = matcher(f).matchFilm("Wonder Woman 1984", null, "en-US")
        assertEquals(MatchOutcome.Matched(TmdbKind.MOVIE, 9), r)
        assertEquals(
            listOf("movie:Wonder Woman:1984", "movie:Wonder Woman:null", "movie:Wonder Woman 1984:null"),
            f.calls,
        )
    }

    @Test fun bracketedYearDoesNotTryYearAsTitle() = runTest {
        val f = FakeLookup()
        assertEquals(MatchOutcome.NotFound, matcher(f).matchFilm("Wonder Woman (1984)", null, "en-US"))
        assertEquals(listOf("movie:Wonder Woman:1984", "movie:Wonder Woman:null"), f.calls)
    }

    @Test fun showsSearchTv() = runTest {
        val f = FakeLookup().apply { shows = { _, _ -> ok(hit(3, "Breaking Bad", 2008)) } }
        assertEquals(MatchOutcome.Matched(TmdbKind.TV, 3), matcher(f).matchShow("Breaking Bad", null, "en-US"))
        assertEquals(listOf("tv:Breaking Bad:null"), f.calls)
    }

    @Test fun emptyCleanedNameIsNotFoundWithoutRequest() = runTest {
        val f = FakeLookup()
        assertEquals(MatchOutcome.NotFound, matcher(f).matchFilm("4K", null, "en-US"))
        assertTrue(f.calls.isEmpty())
    }

    @Test fun badKeyPropagates() = runTest {
        val f = FakeLookup().apply { movies = { _, _ -> TmdbResult.BadKey } }
        assertEquals(MatchOutcome.BadKey, matcher(f).matchFilm("The Matrix", null, "en-US"))
        val g = FakeLookup().apply { find = TmdbResult.BadKey }
        assertEquals(MatchOutcome.BadKey, matcher(g).matchFilm("The Matrix", "tt1", "en-US"))
    }

    @Test fun failurePropagatesRetryAfter() = runTest {
        val f = FakeLookup().apply { movies = { _, _ -> TmdbResult.Failed(30) } }
        assertEquals(MatchOutcome.Failed(30), matcher(f).matchFilm("The Matrix", null, "en-US"))
    }

    @Test fun notFoundFromSearchCountsAsNoHits() = runTest {
        val f = FakeLookup().apply { movies = { _, _ -> TmdbResult.NotFound } }
        assertEquals(MatchOutcome.NotFound, matcher(f).matchFilm("The Matrix", null, "en-US"))
    }

    @Test fun providerYearUsedWhenNameHasNone() = runTest {
        val f = FakeLookup().apply { movies = { _, _ -> ok(hit(1, "The Matrix", 2005)) } }
        assertEquals(MatchOutcome.NotFound, matcher(f).matchFilm("The Matrix", null, "en-US", year = 1999))
        // The provider's year rules out the 2005 hit; no "The Matrix 1999" retry, as the name has no year.
        assertEquals(listOf("movie:The Matrix:1999"), f.calls)

        val g = FakeLookup().apply { shows = { _, _ -> ok(hit(3, "Breaking Bad", 2008)) } }
        assertEquals(MatchOutcome.Matched(TmdbKind.TV, 3), matcher(g).matchShow("Breaking Bad", null, "en-US", year = 2008))
        assertEquals(listOf("tv:Breaking Bad:2008"), g.calls)
    }

    @Test fun nameYearWinsOverProviderYear() = runTest {
        val f = FakeLookup().apply { movies = { _, _ -> ok(hit(1, "The Matrix", 1999)) } }
        assertEquals(MatchOutcome.Matched(TmdbKind.MOVIE, 1), matcher(f).matchFilm("The Matrix (1999)", null, "en-US", year = 2010))
        assertEquals(listOf("movie:The Matrix:1999"), f.calls)
    }
}
