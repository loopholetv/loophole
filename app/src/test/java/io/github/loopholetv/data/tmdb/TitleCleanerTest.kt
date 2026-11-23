package io.github.loopholetv.data.tmdb

import io.github.loopholetv.data.vod.TitleKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleCleanerTest {
    @Test fun cleansRealisticNames() {
        val rows = listOf(
            "EN | The Matrix (1999) 4K" to CleanTitle("The Matrix", 1999, "4K", true),
            "UK: Paddington 2 [2017] FHD" to CleanTitle("Paddington 2", 2017, "HD", true),
            "[FR] Amélie 2001 MULTI" to CleanTitle("Amélie", 2001, null, false),
            "Blade Runner 2049 (2017)" to CleanTitle("Blade Runner 2049", 2017, null, true),
            "1917" to CleanTitle("1917", null, null),
            "Dune Part Two HEVC x265 HDR" to CleanTitle("Dune Part Two", null, null),
            "4K" to CleanTitle("", null, "4K"),
            "Breaking Bad" to CleanTitle("Breaking Bad", null, null),
            "Blade Runner 2049" to CleanTitle("Blade Runner 2049", null, null),
            "Wonder Woman 1984" to CleanTitle("Wonder Woman", 1984, null, false),
            "Film Name (2010) VOSTFR" to CleanTitle("Film Name", 2010, null, true),
            "Film Name (2010) SD" to CleanTitle("Film Name", 2010, null, true),
            "CSI: Miami" to CleanTitle("CSI: Miami", null, null),
            "UHD: Heat" to CleanTitle("Heat", null, "4K"),
        )
        for ((raw, expected) in rows) assertEquals(raw, expected, cleanTitle(raw))
    }

    @Test fun highestBadgeWins() {
        assertEquals("4K", cleanTitle("Heat (1995) 1080p UHD").badge)
        assertEquals("HD", cleanTitle("Heat (1995) 720p").badge)
        assertEquals("4K", cleanTitle("Heat 2160p").badge)
    }

    @Test fun normalisesForComparison() {
        assertEquals(
            "lord of the rings the return of the king",
            normaliseTitle("The Lord of the Rings: The Return of the King"),
        )
        assertEquals("amelie", normaliseTitle("Amélie"))
        assertEquals("fast and furious", normaliseTitle("Fast & Furious"))
    }

    @Test fun fingerprintPrefersTvgId() {
        assertEquals("FILM|id:tt123", fingerprint(TitleKind.FILM, "tt123", "The Matrix (1999)"))
        assertEquals("FILM|n:matrix|1999", fingerprint(TitleKind.FILM, null, "The Matrix (1999)"))
        assertEquals("FILM|n:matrix|1999", fingerprint(TitleKind.FILM, "  ", "EN | The Matrix (1999) 4K"))
        assertEquals("SHOW|n:breaking bad|", fingerprint(TitleKind.SHOW, null, "Breaking Bad"))
        assertEquals("SHOW|id:42", fingerprint(TitleKind.SHOW, "42", "Breaking Bad"))
    }

    @Test fun normalisedNonLatinTitlesStayDistinct() {
        val a = normaliseTitle("Брат")
        val b = normaliseTitle("Движение вверх")
        assertTrue(a.isNotEmpty() && b.isNotEmpty())
        assertNotEquals(a, b)
        assertNotEquals(normaliseTitle("千と千尋の神隠し"), normaliseTitle("君の名は"))
    }

    @Test fun badgeOnlyTitlesDoNotShareFingerprint() {
        assertNotEquals(fingerprint(TitleKind.FILM, null, "4K"), fingerprint(TitleKind.FILM, null, "HD"))
    }
}
