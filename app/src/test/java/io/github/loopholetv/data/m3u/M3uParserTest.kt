package io.github.loopholetv.data.m3u

import java.io.ByteArrayInputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class M3uParserTest {
    private fun res(name: String) = javaClass.classLoader!!.getResourceAsStream("fixtures/$name")!!

    private fun parse(text: String): Pair<M3uHeader, List<M3uEntry>> {
        val out = mutableListOf<M3uEntry>()
        val h = M3uParser.parse(ByteArrayInputStream(text.toByteArray())) { out += it }
        return h to out
    }

    private fun parseRes(name: String): Pair<M3uHeader, List<M3uEntry>> {
        val out = mutableListOf<M3uEntry>()
        val h = M3uParser.parse(res(name)) { out += it }
        return h to out
    }

    private fun single(text: String) = parse(text).second.single()

    @Test fun catchupAttributesTolerant() {
        val e = single("#EXTINF:-1 catchup=\"XC\" catchup-days=\"7d\",A\nhttp://example.test/a\n")
        assertEquals("XC", e.catchup)
        assertEquals(7, e.catchupDays)
        assertNull(single("#EXTINF:-1 catchup=\"xc\" catchup-days=\"x\",A\nhttp://example.test/a\n").catchupDays)
    }

    @Test fun catchupTypeAlias() {
        assertEquals("shift", single("#EXTINF:-1 catchup-type=\"shift\",A\nhttp://example.test/a\n").catchup)
    }

    @Test fun headerCatchupDefaultsApply() {
        val text = "#EXTM3U catchup=\"default\" catchup-days=\"3\" catchup-source=\"http://example.test/a?s={utc}\"\n" +
            "#EXTINF:-1,Plain\nhttp://example.test/a\n" +
            "#EXTINF:-1 catchup-days=\"1\",Own\nhttp://example.test/b\n"
        val (_, entries) = parse(text)
        assertEquals("default", entries[0].catchup)
        assertEquals(3, entries[0].catchupDays)
        assertEquals("http://example.test/a?s={utc}", entries[0].catchupSource)
        assertEquals(1, entries[1].catchupDays)
        assertEquals("default", entries[1].catchup)
    }

    @Test fun timeshiftImpliesShift() {
        val a = single("#EXTINF:-1 timeshift=\"2\",A\nhttp://example.test/a\n")
        assertEquals("shift", a.catchup)
        assertEquals(2, a.catchupDays)
        val b = single("#EXTINF:-1 tvg-rec=\"5\",A\nhttp://example.test/a\n")
        assertEquals("shift", b.catchup)
        assertEquals(5, b.catchupDays)
        assertNull(single("#EXTINF:-1 timeshift=\"0\",A\nhttp://example.test/a\n").catchup)
    }

    @Test fun timeshiftZeroKeepsDefaultWindow() {
        val e = single("#EXTM3U catchup=\"default\"\n#EXTINF:-1 timeshift=\"0\",A\nhttp://example.test/a\n")
        assertEquals("default", e.catchup)
        assertNull(e.catchupDays)
    }

    @Test fun malformedEntryValuesFallThrough() {
        val text = "#EXTM3U catchup=\"default\" catchup-days=\"3\" catchup-correction=\"+2\"\n" +
            "#EXTINF:-1 catchup-days=\"x\" catchup-correction=\"1,5\",A\nhttp://example.test/a\n"
        val e = parse(text).second.single()
        assertEquals(3, e.catchupDays)
        assertEquals(120, e.catchupCorrectionMinutes)
        val f = single("#EXTINF:-1 timeshift=\"x\" tvg-rec=\"5\",A\nhttp://example.test/a\n")
        assertEquals(5, f.catchupDays)
        assertEquals("shift", f.catchup)
        val g = single("#EXTINF:-1 catchup=\"\" timeshift=\"3\",A\nhttp://example.test/a\n")
        assertEquals("shift", g.catchup)
    }

    @Test fun outOfRangeCorrectionIsClamped() {
        assertEquals(1440, single("#EXTINF:-1 catchup-correction=\"1e10\",A\nhttp://example.test/a\n").catchupCorrectionMinutes)
        assertEquals(-1440, single("#EXTINF:-1 catchup-correction=\"-99\",A\nhttp://example.test/a\n").catchupCorrectionMinutes)
    }

    @Test fun catchupCorrectionMinutes() {
        assertEquals(-90, single("#EXTINF:-1 catchup-correction=\"-1.5\",A\nhttp://example.test/a\n").catchupCorrectionMinutes)
        val (_, entries) = parse(
            "#EXTM3U catchup-correction=\"+2\"\n#EXTINF:-1,A\nhttp://example.test/a\n" +
                "#EXTINF:-1 catchup-correction=\"0\",B\nhttp://example.test/b\n",
        )
        assertEquals(120, entries[0].catchupCorrectionMinutes)
        assertEquals(0, entries[1].catchupCorrectionMinutes)
        assertEquals(0, single("#EXTINF:-1,A\nhttp://example.test/a\n").catchupCorrectionMinutes)
    }

    @Test fun parsesHeaderEpgUrls() {
        val (h, _) = parseRes("basic.m3u")
        assertEquals(listOf("http://e.test/a.xml", "http://e.test/b.xml.gz"), h.epgUrls)
    }

    @Test fun parsesAllAttributes() {
        val e = parseRes("basic.m3u").second[0]
        assertEquals("one.test", e.tvgId)
        assertEquals("One HD", e.tvgName)
        assertEquals("http://img.test/one.png", e.logo)
        assertEquals("News, World", e.group)
        assertEquals(7, e.chno)
        assertEquals("default", e.catchup)
        assertEquals(3, e.catchupDays)
        assertEquals("http://cu.test/{utc}", e.catchupSource)
        // The name is text after the FIRST unquoted comma, so it may contain commas.
        assertEquals("News, Weather & Sport", e.name)
        assertEquals("http://s.test/one.ts", e.url)
    }

    @Test fun commaInsideQuotedAttribute() {
        assertEquals("News, World", parseRes("basic.m3u").second[0].group)
    }

    @Test fun extvlcoptUserAgentAndReferrer() {
        val entries = parseRes("basic.m3u").second
        assertEquals("UA1", entries[0].userAgent)
        assertEquals("R1", entries[0].referrer)
        assertNull(entries[1].referrer)
        assertNull(entries[3].userAgent)
    }

    @Test fun kodipropUserAgent() {
        val e = parseRes("basic.m3u").second[1]
        assertEquals("UA2", e.userAgent)
        assertNull(parseRes("basic.m3u").second[2].userAgent)
    }

    @Test fun extgrpUsedWhenNoGroupTitle() {
        val entries = parseRes("basic.m3u").second
        assertEquals("Sports", entries[2].group)
        assertNull(entries[3].group)
    }

    @Test fun bomCrlfAndMissingHeader() {
        val (h, entries) = parseRes("messy.m3u")
        assertEquals(emptyList<String>(), h.epgUrls)
        assertEquals(listOf("Alpha", "Beta"), entries.map { it.name })
        assertEquals("a.test", entries[0].tvgId)
        assertEquals("G", entries[0].group)
        assertEquals("http://s.test/a.ts", entries[0].url)
    }

    @Test fun extinfWithoutUrlSkipped() {
        val entries = parseRes("messy.m3u").second
        assertEquals(2, entries.size)
        assertEquals(listOf("Alpha", "Beta"), entries.map { it.name })
    }

    @Test fun nonIntegerNumericAttributesAreNull() {
        val e = parseRes("messy.m3u").second[1]
        assertNull(e.chno)
        assertNull(e.catchupDays)
    }

    @Test fun missingDurationAndBlankNameTolerated() {
        val (_, entries) = parse("#EXTINF: tvg-id=\"z\", Padded Name \nhttp://s.test/z\n")
        assertEquals("Padded Name", entries.single().name)
        assertEquals("z", entries.single().tvgId)
    }

    @Test fun headerWithBothEpgAttributesKeepsAllDeduplicated() {
        val (h, _) = parse("#EXTM3U url-tvg=\"http://e.test/a.xml, http://e.test/b.xml\" x-tvg-url=\"http://e.test/b.xml,http://e.test/c.xml\"\n")
        assertEquals(listOf("http://e.test/a.xml", "http://e.test/b.xml", "http://e.test/c.xml"), h.epgUrls)
    }

    @Test fun headerWithXTvgUrlOnly() {
        val (h, _) = parse("#EXTM3U x-tvg-url=\"http://e.test/x.xml\"\n")
        assertEquals(listOf("http://e.test/x.xml"), h.epgUrls)
    }

    @Test fun lowercaseHeaderRecognised() {
        val (h, _) = parse("#extm3u url-tvg=\"http://e.test/l.xml\"\n")
        assertEquals(listOf("http://e.test/l.xml"), h.epgUrls)
    }

    @Test fun streamsLargeFileWithoutBuffering() {
        val n = 20_000
        val pin = PipedInputStream(8192)
        val pout = PipedOutputStream(pin)
        val writer = thread(isDaemon = true) {
            pout.bufferedWriter().use { w ->
                w.write("#EXTM3U\n")
                for (i in 0 until n) {
                    w.write("#EXTINF:-1 tvg-id=\"id$i\" group-title=\"G${i % 10}\",Channel $i\n")
                    w.write("http://s.test/$i.ts\n")
                }
            }
        }
        var count = 0
        M3uParser.parse(pin) { count++ }
        writer.join()
        assertEquals(n, count)
    }
}
