package io.github.loopholetv.data.repo

import io.github.loopholetv.data.xmltv.XmltvChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class EpgMatcherTest {
    private fun epg(vararg channels: Pair<String, List<String>>): Map<String, XmltvChannel> =
        channels.associate { (id, names) -> id to XmltvChannel(id, names, null) }

    private fun ch(id: Long, name: String, tvgId: String? = null, tvgName: String? = null) =
        ChannelKeyView(id, tvgId, tvgName, name)

    @Test fun exactTvgIdWins() {
        val epg = epg("news.id" to listOf("Something Else"), "other.id" to listOf("Example News"))
        val result = EpgMatcher.match(listOf(ch(1, "Example News", tvgId = "news.id")), epg)
        assertEquals(mapOf(1L to "news.id"), result)
    }

    @Test fun caseInsensitiveTvgId() {
        val result = EpgMatcher.match(listOf(ch(1, "Whatever", tvgId = "NEWS.Example")), epg("news.example" to emptyList()))
        assertEquals(mapOf(1L to "news.example"), result)
    }

    @Test fun nameFallbackStripsCountryPrefixAndQuality() {
        val epg = epg("fox504" to listOf("Fox Sports 504"))
        assertEquals(mapOf(1L to "fox504"), EpgMatcher.match(listOf(ch(1, "AU: FOX SPORTS 504 HD")), epg))
        // tvg-name is preferred over the display name; an unknown tvg-id falls back to names
        assertEquals(
            mapOf(2L to "fox504"),
            EpgMatcher.match(listOf(ch(2, "Unrelated", tvgId = "nope", tvgName = "US| Fox Sports 504 FHD")), epg),
        )
        assertEquals("foxsports504", EpgMatcher.normalise("AU: FOX SPORTS 504 HD"))
        assertEquals("bbcone", EpgMatcher.normalise("  UK | BBC One 4K backup "))
    }

    @Test fun noMatchReturnsAbsent() {
        val result = EpgMatcher.match(
            listOf(ch(1, "Example Movies", tvgId = "movies.id"), ch(2, "HD")),
            epg("news.id" to listOf("Example News"), "blank" to listOf("SD")),
        )
        assertFalse(result.containsKey(1L))
        assertFalse(result.containsKey(2L)) // names that normalise to "" never match
        assertEquals(emptyMap<Long, String>(), result)
    }
}
