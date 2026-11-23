package io.github.loopholetv.ui.vod

import io.github.loopholetv.data.vod.RatingMin
import io.github.loopholetv.data.vod.RuntimeBand
import io.github.loopholetv.data.vod.VodFilter
import io.github.loopholetv.data.vod.VodSort
import org.junit.Assert.assertEquals
import org.junit.Test

class FilterSummaryTest {
    private val names = mapOf(28 to "Action", 18 to "Drama", 35 to "Comedy", 53 to "Thriller")

    @Test fun summaryText() {
        assertEquals(
            "Action · ★7+ · by rating",
            filterSummary(VodFilter(genres = setOf(28), rating = RatingMin.SEVEN, sort = VodSort.RATING), names),
        )
        assertEquals("Action, Drama", filterSummary(VodFilter(genres = setOf(18, 28)), names))
        assertEquals("Action +2", filterSummary(VodFilter(genres = setOf(28, 18, 35)), names))
        assertEquals("1990s", filterSummary(VodFilter(decades = setOf(1990)), names))
        assertEquals("Before 1970", filterSummary(VodFilter(decades = setOf(VodFilter.BEFORE_1970)), names))
        assertEquals("2010s, Before 1970", filterSummary(VodFilter(decades = setOf(VodFilter.BEFORE_1970, 2010)), names))
        assertEquals("★6+", filterSummary(VodFilter(rating = RatingMin.SIX), names))
        assertEquals("Under 90 min", filterSummary(VodFilter(runtime = RuntimeBand.UNDER_90), names))
        assertEquals("90 min–2 h", filterSummary(VodFilter(runtime = RuntimeBand.FROM_90_TO_120), names))
        assertEquals("Over 2 h", filterSummary(VodFilter(runtime = RuntimeBand.OVER_120), names))
        assertEquals("by year", filterSummary(VodFilter(sort = VodSort.YEAR), names))
        assertEquals("by title", filterSummary(VodFilter(sort = VodSort.TITLE), names))
        assertEquals("by running time", filterSummary(VodFilter(sort = VodSort.RUNTIME), names))
    }

    @Test fun recentSortIsLeftOut() {
        assertEquals("★8+", filterSummary(VodFilter(rating = RatingMin.EIGHT, sort = VodSort.RECENT), names))
        assertEquals("", filterSummary(VodFilter(), names))
    }

    @Test fun unknownGenresAreLeftOut() {
        assertEquals("Action", filterSummary(VodFilter(genres = setOf(28, 9999)), names))
    }
}
