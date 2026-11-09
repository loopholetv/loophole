package io.github.loopholetv.data.xmltv

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xmlpull.v1.XmlPullParserException

class XmltvParserTest {
    private fun res(name: String): InputStream = javaClass.classLoader!!.getResourceAsStream("fixtures/$name")!!

    private fun parse(input: InputStream): Pair<List<XmltvChannel>, List<XmltvProgramme>> {
        val channels = mutableListOf<XmltvChannel>()
        val programmes = mutableListOf<XmltvProgramme>()
        XmltvParser.parse(input, { channels += it }, { programmes += it })
        return channels to programmes
    }

    private fun parseText(xml: String) = parse(ByteArrayInputStream(xml.toByteArray()))

    private fun List<XmltvProgramme>.byTitle(t: String) = first { it.title == t }

    @Test fun parsesChannelsWithMultipleDisplayNames() {
        val (channels, _) = parse(res("epg.xml"))
        assertEquals(2, channels.size)
        assertEquals("news.example", channels[0].id)
        assertEquals(listOf("Example News", "EXN", "101"), channels[0].displayNames)
        assertEquals("http://example.test/logos/news.png", channels[0].icon)
        assertNull(channels[1].icon)
    }

    @Test fun parsesProgrammeWithOffset() {
        val p = parse(res("epg.xml")).second.byTitle("Morning Bulletin")
        assertEquals(Instant.parse("2026-11-07T00:30:00Z"), p.start)
        assertEquals(Instant.parse("2026-11-07T01:00:00Z"), p.stop)
        assertEquals("news.example", p.channelId)
        assertEquals("Headlines", p.subTitle)
        assertEquals("The latest news.", p.description)
        assertEquals("News", p.category)
    }

    @Test fun timeWithoutOffsetIsUtc() {
        val p = parse(res("epg.xml")).second.byTitle("UTC Show")
        assertEquals(Instant.parse("2026-11-07T13:00:00Z"), p.start)
        val q = parseText(
            """<tv><programme start="20261107113000" stop="20261107120000" channel="c"><title>X</title></programme></tv>""",
        ).second.single()
        assertEquals(Instant.parse("2026-11-07T11:30:00Z"), q.start)
    }

    @Test fun secondsAreOptional() {
        val p = parse(res("epg.xml")).second.byTitle("Minutes Only")
        assertEquals(Instant.parse("2026-11-07T07:00:00Z"), p.start)
        assertEquals(Instant.parse("2026-11-07T08:00:00Z"), p.stop)
    }

    @Test fun dstBoundaryOffsetsRespected() {
        val p = parse(res("epg.xml")).second.byTitle("DST Show")
        assertEquals(Instant.parse("2026-10-03T15:59:00Z"), p.start)
        assertEquals(Instant.parse("2026-10-03T16:00:00Z"), p.stop)
        assertEquals(60L, p.stop.epochSecond - p.start.epochSecond)
    }

    @Test fun stopBeforeOrEqualStartDropped() {
        val titles = parse(res("epg.xml")).second.map { it.title }
        assertTrue("Zero Length" !in titles)
        assertTrue("Backwards" !in titles)
    }

    @Test fun missingStopDropped() {
        val titles = parse(res("epg.xml")).second.map { it.title }
        assertTrue("No Stop" !in titles)
    }

    @Test fun unparsableStartDroppedWithoutThrowing() {
        val titles = parse(res("epg.xml")).second.map { it.title }
        assertTrue("Bad Start" !in titles)
        assertEquals(
            listOf("Morning Bulletin", "Tom & Jerry", "UTC Show", "DST Show", "Minutes Only"),
            titles,
        )
    }

    @Test fun gzipDetectedByMagicBytes() {
        val plain = parse(res("epg.xml"))
        val gz = parse(res("epg.xml.gz"))
        assertEquals(plain, gz)
        assertEquals(5, gz.second.size)
    }

    @Test fun htmlEntitiesAndCdataDecoded() {
        val ps = parse(res("epg.xml")).second
        val p = ps.byTitle("Tom & Jerry")
        assertEquals("Cat & mouse <chase> classic", p.description)
    }

    @Test fun firstNonBlankTitleWins() {
        // The German title is blank; English one is picked.
        assertEquals("Tom & Jerry", parse(res("epg.xml")).second[1].title)
    }

    @Test fun unknownElementsIgnored() {
        val (_, ps) = parse(res("epg.xml"))
        // Programme after the one containing credits/rating/icon/video must still parse.
        assertEquals(Instant.parse("2026-11-07T13:00:00Z"), ps.byTitle("UTC Show").start)
        assertNull(ps.byTitle("Tom & Jerry").category)
    }

    @Test fun parseTimeTable() {
        val cases = mapOf(
            "20261107113000 -0800" to "2026-11-07T19:30:00Z",
            "20261107113000 +0530" to "2026-11-07T06:00:00Z",
            "20261107113000 +11:00" to "2026-11-07T00:30:00Z",
            "20261107113000 Z" to "2026-11-07T11:30:00Z",
            "20261107113000 z" to "2026-11-07T11:30:00Z",
            "20261107113000 UTC" to "2026-11-07T11:30:00Z",
            "20261107113000 utc" to "2026-11-07T11:30:00Z",
            "20261107113000 GMT" to "2026-11-07T11:30:00Z",
            "20261107113000 gmt" to "2026-11-07T11:30:00Z",
            "20261107113000UTC" to "2026-11-07T11:30:00Z",
            "20261107113000 +11" to "2026-11-07T00:30:00Z",
            "20261107113000 -08" to "2026-11-07T19:30:00Z",
            "20261107113000+1100" to "2026-11-07T00:30:00Z",
            "202611071130 +1100" to "2026-11-07T00:30:00Z",
            "20261107113000" to "2026-11-07T11:30:00Z",
        )
        for ((raw, expected) in cases) {
            assertEquals(raw, Instant.parse(expected), XmltvParser.parseTime(raw))
        }
    }

    @Test fun parseTimeInvalidReturnsNull() {
        listOf(
            "20261307113000 +1100", "20261107243000 +1100", "20261107113000 +9900",
            "20261107113000 +1160", "garbage", "", "2026", "20261107113000 PST",
        ).forEach { assertNull(it, XmltvParser.parseTime(it)) }
        assertNull(XmltvParser.parseTime(null))
    }

    @Test fun invalidDateProgrammeDroppedRestParses() {
        val xml = """<tv>
            <programme start="20261307113000 +1100" stop="20261107120000 +1100" channel="c"><title>Bad</title></programme>
            <programme start="20261107113000 +9900" stop="20261107120000 +1100" channel="c"><title>BadZone</title></programme>
            <programme start="20261107113000 UTC" stop="20261107120000 GMT" channel="c"><title>Good</title></programme>
            </tv>"""
        val ps = parseText(xml).second
        assertEquals(listOf("Good"), ps.map { it.title })
        assertEquals(Instant.parse("2026-11-07T12:00:00Z"), ps.single().stop)
    }

    @Test fun veryLongDescIsTruncatedAndParsingContinues() {
        val long = "x".repeat(200_000)
        val xml = """<tv>
            <programme start="20261107113000" stop="20261107120000" channel="c"><title>A</title><desc>$long</desc><category>Cat</category></programme>
            <programme start="20261107120000" stop="20261107130000" channel="c"><title>B</title></programme>
            </tv>"""
        val ps = parseText(xml).second
        assertEquals(listOf("A", "B"), ps.map { it.title })
        assertEquals(65_536, ps[0].description!!.length)
        assertEquals("Cat", ps[0].category)
    }

    @Test fun displayNamesCappedAtSixteen() {
        val names = (1..20).joinToString("") { "<display-name>N$it</display-name>" }
        val xml = """<tv><channel id="a">$names<icon src="http://example.test/i.png"/></channel>
            <channel id="b"><display-name>Z</display-name></channel></tv>"""
        val (channels, _) = parseText(xml)
        assertEquals((1..16).map { "N$it" }, channels[0].displayNames)
        assertEquals("http://example.test/i.png", channels[0].icon)
        assertEquals(2, channels.size)
    }

    @Test(expected = XmltvTooLargeException::class)
    fun inflatedSizeLimitEnforcedForGzip() {
        XmltvParser.parse(res("epg.xml.gz"), {}, {}, maxInflatedBytes = 1024)
    }

    @Test(expected = XmltvTooLargeException::class)
    fun inflatedSizeLimitEnforcedForPlain() {
        XmltvParser.parse(res("epg.xml"), {}, {}, maxInflatedBytes = 1024)
    }

    @Test fun plainFixtureUnderLimitStillParses() {
        var n = 0
        XmltvParser.parse(res("epg.xml"), {}, { n++ }, maxInflatedBytes = 1L shl 20)
        assertEquals(5, n)
    }

    @Test fun doctypeIsToleratedAndNotResolved() {
        val xml = """<?xml version="1.0"?>
            <!DOCTYPE tv SYSTEM "http://example.test/xmltv.dtd">
            <tv><channel id="a"><display-name>A</display-name></channel></tv>"""
        assertEquals("a", parseText(xml).first.single().id)
    }

    @Test(expected = XmlPullParserException::class)
    fun malformedDocumentThrows() {
        parseText("<tv><channel id=\"a\"><display-name>A</tv>")
    }
}
