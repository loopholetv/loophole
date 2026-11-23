package io.github.loopholetv.data.vod

import io.github.loopholetv.data.db.filterQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VodFilterTest {
    @Test fun codecRoundTripsAndIgnoresJunk() {
        val f = VodFilter(
            genres = setOf(28, 12),
            decades = setOf(1990, 0),
            rating = RatingMin.SEVEN,
            runtime = RuntimeBand.FROM_90_TO_120,
            sort = VodSort.RATING,
        )
        assertEquals(f, VodFilter.decode(f.encode()))
        assertEquals(VodFilter(), VodFilter.decode(VodFilter().encode()))
        assertEquals(VodFilter(), VodFilter.decode(null))
        assertEquals(VodFilter(), VodFilter.decode(""))
        assertEquals(VodFilter(), VodFilter.decode("nonsense;;=;x=y"))
        // Each bad part falls back on its own; the good parts stay.
        assertEquals(
            VodFilter(genres = setOf(18), decades = setOf(2010), sort = VodSort.TITLE),
            VodFilter.decode("g=18,abc,-3;d=2010,1975,1800,x;r=NINE;t=SOMETIMES;s=TITLE;z=1"),
        )
    }

    @Test fun defaultAndHasFilter() {
        assertTrue(VodFilter().isDefault)
        assertFalse(VodFilter().hasFilter)
        val sortOnly = VodFilter(sort = VodSort.YEAR)
        assertFalse(sortOnly.isDefault)
        assertFalse(sortOnly.hasFilter)
        assertTrue(VodFilter(rating = RatingMin.SIX).hasFilter)
        assertTrue(VodFilter(runtime = RuntimeBand.OVER_120).hasFilter)
        assertTrue(VodFilter(genres = setOf(1)).hasFilter)
        assertTrue(VodFilter(decades = setOf(0)).hasFilter)
    }

    @Test fun builderShape() {
        val sortOnly = filterQuery(TitleKind.FILM, VodFilter(sort = VodSort.TITLE)).sql
        assertFalse(sortOnly.contains("t.tmdbId IS NOT NULL AND"))
        assertTrue(sortOnly.contains("ORDER BY t.tmdbId IS NULL"))
        assertFalse(sortOnly.contains("OVER"))

        val filtered = filterQuery(TitleKind.FILM, VodFilter(genres = setOf(28), rating = RatingMin.EIGHT)).sql
        assertTrue(filtered.contains("t.tmdbId IS NOT NULL"))
        assertTrue(filtered.contains("l.genreId IN (28)"))
        assertTrue(filtered.contains("t.rating >= 8.0"))
        assertTrue(filtered.contains("NOT EXISTS"))

        val count = filterQuery(TitleKind.SHOW, VodFilter(decades = setOf(0, 1980)), countOnly = true).sql
        assertTrue(count.startsWith("SELECT COUNT(*)"))
        assertFalse(count.contains("ORDER BY"))
        assertTrue(count.contains("t.year < 1970"))
        assertTrue(count.contains("t.year BETWEEN 1980 AND 1989"))
    }

    /** The other copy's visibility check is the outer one with its alias swapped, and nothing else. */
    @Test fun otherCopyVisibilityUsesItsOwnAlias() {
        val film = io.github.loopholetv.data.db.visibleAs(io.github.loopholetv.data.db.FILM_VISIBLE, "f", "o")
        assertFalse(Regex("(?<![A-Za-z0-9_])f\\.").containsMatchIn(film))
        assertEquals(io.github.loopholetv.data.db.FILM_VISIBLE.replace("f.", "o."), film)
        val show = io.github.loopholetv.data.db.visibleAs(io.github.loopholetv.data.db.SHOW_VISIBLE, "s", "o")
        assertFalse(Regex("(?<![A-Za-z0-9_])s\\.").containsMatchIn(show))
    }
}
