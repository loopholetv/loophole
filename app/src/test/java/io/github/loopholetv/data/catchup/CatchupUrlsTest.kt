package io.github.loopholetv.data.catchup

import io.github.loopholetv.util.redact
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatchupUrlsTest {
    private val from = Instant.ofEpochSecond(1794340800)
    private val until = Instant.ofEpochSecond(1794344400)
    private val now = Instant.ofEpochSecond(1794350000)
    private val utc: ZoneId = ZoneOffset.UTC

    private fun m3u(
        mode: CatchupMode,
        stream: String,
        source: String? = null,
        zone: ZoneId = utc,
        correction: Duration = Duration.ZERO,
    ) = CatchupUrls.m3u(CatchupSpec(mode, 3, source, false), stream, from, until, now, zone, correction)

    private fun fill(template: String, zone: ZoneId) = CatchupUrls.fill(template, from, until, now, zone)

    @Test fun defaultFillsEveryPlaceholder() = assertEquals(
        "http://example.test/a?a=1794340800&b=1794344400&c=1794350000&d=3600&e=60&f=9200&g=9200",
        m3u(
            CatchupMode.DEFAULT, "http://example.test/live.m3u8",
            "http://example.test/a?a={utc}&b={utcend}&c={lutc}&d={duration}&e={duration:60}&f=\${offset}&g={offset:1}",
        ),
    )

    @Test fun dollarForms() = assertEquals(
        "http://example.test/a?s=1794340800&e=1794344400&n=1794350000&t=1794350000&d=3600",
        m3u(
            CatchupMode.DEFAULT, "http://example.test/live.m3u8",
            "http://example.test/a?s=\${start}&e=\${end}&n=\${now}&t=\${timestamp}&d=\${duration}",
        ),
    )

    @Test fun datePlaceholdersUseZoneAndCorrection() {
        val adelaide = ZoneId.of("Australia/Adelaide")
        assertEquals("2026-11-11:06-30", fill("{Y}-{m}-{d}:{H}-{M}", adelaide))
        assertEquals(
            "2026-11-11:05-00",
            m3u(CatchupMode.DEFAULT, "http://example.test/live.m3u8", "{Y}-{m}-{d}:{H}-{M}", adelaide, Duration.ofMinutes(-90)),
        )
        assertEquals("202611102000", fill("{utc:YmdHM}", utc))
        assertEquals("20261110-20-00", fill("{utc:Ymd-H-M}", utc))
        assertEquals("20261110200000", fill("\${start:YmdHMS}", utc))
        assertEquals("2026-11-10:21", fill("{utcend:Y-m-d:H}", utc))
        assertEquals("20261110", fill("{lutc:Ymd}", utc))
        assertEquals("200000", fill("{H}{M}{S}", utc))
    }

    @Test fun unknownPlaceholdersAreLeftAlone() =
        assertEquals("a={catchup-id}&b={nope:1}", fill("a={catchup-id}&b={nope:1}", utc))

    @Test fun durationNeverNegative() {
        val early = Instant.ofEpochSecond(1794340700)
        assertEquals("0", CatchupUrls.fill("{duration}", from, until, early, utc))
    }

    @Test fun appendAndShift() {
        assertEquals(
            "http://example.test/c.m3u8?s=1794340800",
            m3u(CatchupMode.APPEND, "http://example.test/c.m3u8", "?s={utc}"),
        )
        assertEquals(
            "http://example.test/c.ts?tok=1&utc=1794340800&lutc=1794350000",
            m3u(CatchupMode.SHIFT, "http://example.test/c.ts?tok=1"),
        )
        assertEquals(
            "http://example.test/c.ts?utc=1794340800&lutc=1794350000",
            m3u(CatchupMode.SHIFT, "http://example.test/c.ts"),
        )
    }

    @Test fun correctionShiftsEpochValues() = assertEquals(
        "http://example.test/c.ts?utc=1794337200&lutc=1794350000",
        m3u(CatchupMode.SHIFT, "http://example.test/c.ts", correction = Duration.ofHours(-1)),
    )

    @Test fun missingSourceGivesNoUrl() {
        assertNull(m3u(CatchupMode.DEFAULT, "http://example.test/c.m3u8"))
        assertNull(m3u(CatchupMode.APPEND, "http://example.test/c.m3u8"))
    }

    @Test fun flussonicRewrites() {
        assertEquals(
            "http://example.test/ch/timeshift_abs-1794340800.ts?t=1",
            m3u(CatchupMode.FLUSSONIC_TS, "http://example.test/ch/mpegts?t=1"),
        )
        assertEquals(
            "http://example.test/ch/timeshift_rel-9200.m3u8?t=1",
            m3u(CatchupMode.FLUSSONIC_HLS, "http://example.test/ch/index.m3u8?t=1"),
        )
        assertEquals(
            "http://example.test/ch/mono-timeshift_rel-9200.m3u8",
            m3u(CatchupMode.FLUSSONIC_HLS, "http://example.test/ch/mono.m3u8"),
        )
        assertEquals(
            "http://example.test/ch/timeshift_abs-1794340800.ts",
            m3u(CatchupMode.FLUSSONIC_TS, "http://example.test/ch/other.ts"),
        )
        assertEquals(
            "http://example.test/ch/timeshift_rel-9200.m3u8?t=1",
            m3u(CatchupMode.FLUSSONIC_HLS, "http://example.test/ch/video.m3u8?t=1"),
        )
    }

    @Test fun xcModeUrlShapes() {
        assertEquals(
            "http://example.test/timeshift/u/p/60/2026-11-10:20-00/42.ts",
            m3u(CatchupMode.XC, "http://example.test/live/u/p/42.ts"),
        )
        assertEquals(
            "http://example.test/timeshift/u/p/60/2026-11-10:20-00/42.m3u8",
            m3u(CatchupMode.XC, "http://example.test/live/u/p/42.m3u8"),
        )
        assertNull(m3u(CatchupMode.XC, "http://example.test/live/u/p/42.ts?x=1"))
    }

    @Test fun xtreamStartInServerZoneAcrossDst() {
        val london = ZoneId.of("Europe/London")
        fun start(instant: String): String {
            val a = XtreamArchive("http://example.test", "u", "p", "42", london, "ts")
            val at = Instant.parse(instant)
            val url = CatchupUrls.xtream(a, at, at.plusSeconds(3600), at.plusSeconds(7200))
            return url.substringAfter("/60/").substringBefore("/")
        }
        assertEquals("2026-10-25:01-30", start("2026-10-25T00:30:00Z"))
        assertEquals("2026-10-25:01-30", start("2026-10-25T01:30:00Z"))
    }

    @Test fun xtreamDurationCappedAtNow() {
        val a = XtreamArchive("http://example.test", "u", "p", "42", utc, "ts")
        val url = CatchupUrls.xtream(
            a, Instant.parse("2026-11-10T20:00:40Z"), Instant.parse("2026-11-10T21:00:00Z"),
            Instant.parse("2026-11-10T20:10:30Z"),
        )
        assertEquals("http://example.test/timeshift/u/p/11/2026-11-10:20-00/42.ts", url)
    }

    @Test fun xtreamDurationIsAtLeastOneMinute() {
        val a = XtreamArchive("http://example.test", "u", "p", "42", utc, "ts")
        val url = CatchupUrls.xtream(a, from, from, now)
        assertTrue(url, url.contains("/p/1/"))
    }

    @Test fun credentialsPathEncoded() {
        val a = XtreamArchive("http://example.test:8080", "a/b", "p@ss word", "42", utc, "m3u8")
        val url = CatchupUrls.xtream(a, from, until, now)
        assertEquals("http://example.test:8080/timeshift/a%2Fb/p@ss%20word/60/2026-11-10:20-00/42.m3u8", url)
        assertEquals(6, url.removePrefix("http://example.test:8080/").split('/').size)
    }

    @Test fun xcFromStreamUrlAcceptsUserNamedLive() {
        val a = CatchupUrls.xcFromStreamUrl("http://example.test/live/live/pw/9.ts", utc)!!
        assertEquals(listOf("live", "pw", "9"), listOf(a.user, a.pass, a.streamId))
    }

    @Test fun xcFromStreamUrlParsesParts() {
        val a = CatchupUrls.xcFromStreamUrl("https://example.test:8443/live/user/pw/77.m3u8", utc)!!
        assertEquals(XtreamArchive("https://example.test:8443", "user", "pw", "77", utc, "m3u8"), a)
        assertEquals("ts", CatchupUrls.xcFromStreamUrl("http://example.test/user/pw/77", utc)!!.ext)
        assertNull(CatchupUrls.xcFromStreamUrl("http://example.test/live/u/p/42.ts?x=1", utc))
    }

    @Test fun archiveUrlsAreRedacted() {
        val xc = m3u(CatchupMode.XC, "http://example.test/live/secretuser/secretpass/42.ts")!!
        val shift = m3u(CatchupMode.SHIFT, "http://example.test/c.ts?token=abc123")!!
        val fluss = m3u(CatchupMode.FLUSSONIC_TS, "http://example.test/ch/mpegts?token=abc123")!!
        val flussHls = m3u(CatchupMode.FLUSSONIC_HLS, "http://example.test/ch/index.m3u8?token=abc123")!!
        val legacy = "http://example.test/streaming/timeshift.php?username=secretuser&password=secretpass&stream=42" +
            "&start=2026-11-10:20-00&duration=60"
        for (url in listOf(xc, shift, fluss, flussHls, legacy)) {
            val shown = redact(url)
            for (secret in listOf("secretuser", "secretpass", "abc123")) {
                assertFalse("$secret leaked in $shown", shown.contains(secret))
            }
        }
        assertEquals("http://example.test/timeshift/***/***/60/2026-11-10:20-00/42.ts", redact(xc))
        assertEquals("http://example.test/c.ts?token=***&utc=1794340800&lutc=1794350000", redact(shift))
        assertEquals("http://example.test/ch/***.ts?token=***", redact(fluss))
        assertEquals("http://example.test/ch/***.m3u8?token=***", redact(flussHls))
        assertEquals(
            "http://example.test/streaming/***?username=***&password=***&stream=42" +
                "&start=2026-11-10:20-00&duration=60",
            redact(legacy),
        )
    }

    @Test fun archiveToStringHidesSecrets() {
        val a = XtreamArchive("http://example.test", "secretuser", "secretpass", "42", utc, "ts")
        val shown = a.toString()
        assertFalse(shown.contains("secretuser"))
        assertFalse(shown.contains("secretpass"))
        assertTrue(shown.contains("42"))
        val spec = CatchupSpec(CatchupMode.SHIFT, 3, "http://example.test/c.ts?token=abc123", false)
        assertFalse(spec.toString().contains("abc123"))
        assertFalse(spec.toString().contains("example.test"))
    }

    @Test fun encodedCredentialsInLiveUrlAreEncodedOnce() {
        val live = "http://example.test/live/a%40b/p%20w%25/42.ts"
        val a = CatchupUrls.xcFromStreamUrl(live, utc)!!
        assertEquals("a@b", a.user)
        assertEquals("p w%", a.pass)
        assertEquals(
            "http://example.test/timeshift/a@b/p%20w%25/60/2026-11-10:20-00/42.ts",
            m3u(CatchupMode.XC, live),
        )
    }

    @Test fun plusInCredentialsStaysAPlus() =
        assertEquals("a+b", CatchupUrls.xcFromStreamUrl("http://example.test/live/a+b/p/1.ts", utc)!!.user)

    @Test fun malformedServerGivesNoCatchupAndNoUrl() {
        val bad = "http://example.test:99999/live/u/p/42.ts"
        assertNull(CatchupUrls.xcFromStreamUrl(bad, utc))
        assertNull(catchupSpec("xc", 3, null, bad))
        assertNull(m3u(CatchupMode.XC, bad))
    }

    @Test fun liveAsFirstSegmentIsNotACredential() {
        assertNull(CatchupUrls.xcFromStreamUrl("http://example.test/live/p/42", utc))
    }

    @Test fun offsetAndEndStayInsideTheProgramme() {
        val during = Instant.ofEpochSecond(1794341400)
        assertEquals("600", CatchupUrls.fill("{duration}", from, until, during, utc))
        assertEquals("1794341400", CatchupUrls.fill("{utcend}", from, until, during, utc))
        assertEquals("0", CatchupUrls.fill("\${offset}", from, until, Instant.ofEpochSecond(1794340700), utc))
    }
}
