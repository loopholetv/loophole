package io.github.loopholetv.ui.vod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class TitleDetailsFormatTest {
    private val base = TitleDetailsUi(
        year = 1999, runtimeMinutes = 136, seasonCount = null, rating = 8.2,
        genres = listOf("Action", "Sci-Fi"), overview = null, cast = emptyList(), backdropUrl = null, badge = null,
    )

    @Test fun formatMetaVariants() {
        assertEquals("1999 · 2 h 16 min · ★ 8.2 · Action, Sci-Fi", formatMeta(base))
        assertEquals("1999 · 2 h 16 min · Action, Sci-Fi", formatMeta(base.copy(rating = null)))
        assertEquals("1999 · 2 h 16 min · Action, Sci-Fi", formatMeta(base.copy(rating = 0.0)))
        assertEquals("1999 · 45 min · ★ 8.2 · Action, Sci-Fi", formatMeta(base.copy(runtimeMinutes = 45)))
        assertEquals("1999 · 2 h · ★ 8.2 · Action, Sci-Fi", formatMeta(base.copy(runtimeMinutes = 120)))
        assertEquals("2008 · 1 season · ★ 9.0", formatMeta(base.copy(year = 2008, runtimeMinutes = null, seasonCount = 1, rating = 9.0, genres = emptyList())))
        assertEquals("3 seasons", formatMeta(base.copy(year = null, runtimeMinutes = null, seasonCount = 3, rating = null, genres = emptyList())))
        assertEquals("★ 7.5", formatMeta(base.copy(year = null, runtimeMinutes = null, rating = 7.46, genres = emptyList())))
        assertEquals("", formatMeta(base.copy(year = null, runtimeMinutes = null, rating = null, genres = emptyList())))
    }

    @Test fun providerDetailsSplitGenresAndCast() {
        val d = providerDetailsUi(
            plot = " A teacher turns. ", cast = "Bryan Cranston, ,Aaron Paul ", genre = "Drama / Crime|Thriller, ",
            rating = 8.9, year = 2008, runtimeMinutes = 47, backdrop = "https://img.test/bd.jpg",
        )!!

        assertEquals(listOf("Drama", "Crime", "Thriller"), d.genres)
        assertEquals(listOf("Bryan Cranston", "Aaron Paul"), d.cast)
        assertEquals("A teacher turns.", d.overview)
        assertEquals("https://img.test/bd.jpg", d.backdropUrl)
        assertEquals("2008 · 47 min · ★ 8.9 · Drama, Crime, Thriller", formatMeta(d))
        assertNull(d.seasonCount)
        assertNull(d.badge)
        assertFalse(d.fromTmdb)
        // Only a web address is used as the backdrop.
        assertNull(providerDetailsUi("Plot", null, null, null, null, null, "/bd.jpg")!!.backdropUrl)
        assertEquals("http://img.test/bd.jpg", providerDetailsUi("Plot", null, null, null, null, null, "http://img.test/bd.jpg")!!.backdropUrl)
    }

    @Test fun providerDetailsAllBlankIsNull() {
        assertNull(providerDetailsUi(null, null, null, null, null, null, null))
        assertNull(providerDetailsUi(" ", "", " , ", null, null, null, "  "))
    }
}
