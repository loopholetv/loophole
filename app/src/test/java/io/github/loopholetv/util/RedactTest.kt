package io.github.loopholetv.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RedactTest {
    @Test fun xtreamPathCredentialsRedacted() = assertEquals(
        "http://host.test:8080/live/***/***/123.ts",
        redact("http://host.test:8080/live/user1/pass1/123.ts"))
    @Test fun queryCredentialsRedacted() = assertEquals(
        "http://host.test/get.php?username=***&password=***&type=m3u",
        redact("http://host.test/get.php?username=u&password=p&type=m3u"))
    @Test fun userInfoRedacted() = assertEquals("http://***@host.test/a.m3u", redact("http://bob:pw@host.test/a.m3u"))
    @Test fun tokenPathSegmentRedacted() = assertEquals(
        "http://relay.test:8081/***/m3u", redact("http://relay.test:8081/abcd1234/m3u"))
    @Test fun urlInsideSentence() = assertEquals(
        "Failed: http://h.test/live/***/***/1.ts timed out",
        redact("Failed: http://h.test/live/a/b/1.ts timed out"))
    @Test fun plainTextUntouched() = assertEquals("No URL here", redact("No URL here"))
    @Test fun queryKeysAreCaseInsensitiveAndOtherParamsKept() = assertEquals(
        "http://h.test/p?Token=***&type=m3u&KEY=***",
        redact("http://h.test/p?Token=abc&type=m3u&KEY=xyz"))
    @Test fun trailingPunctuationIsNotPartOfUrl() = assertEquals(
        "See http://h.test/live/***/***/1.ts.",
        redact("See http://h.test/live/a/b/1.ts."))
    @Test fun everyUrlInTextIsRedacted() = assertEquals(
        "http://***@a.test/x.m3u and https://b.test/get.php?password=***",
        redact("http://u:p@a.test/x.m3u and https://b.test/get.php?password=zzz"))
    @Test fun movieAndSeriesPathsRedacted() = assertEquals(
        "http://h.test/movie/***/***/9.mkv http://h.test/series/***/***/4.mp4",
        redact("http://h.test/movie/u/p/9.mkv http://h.test/series/u/p/4.mp4"))
    @Test fun shortOrPlainPathSegmentsKept() = assertEquals(
        "http://h.test/channels/tv24/list.m3u8",
        redact("http://h.test/channels/tv24/list.m3u8"))

    // S1: literal secrets and extra path prefixes
    @Test fun literalSecretsRedactedAnywhere() = assertEquals(
        "error for *** at http://h.test/a/***/b",
        redact("error for hunter at http://h.test/a/hunter/b", listOf("hunter")))
    @Test fun encodedSecretsRedacted() = assertEquals(
        "see http://h.test/a/***/b and http://h.test/c/***/d",
        redact("see http://h.test/a/p%40ss%20word/b and http://h.test/c/p%40ss+word/d", listOf("p@ss word")))
    @Test fun blankSecretsIgnored() = assertEquals("No URL here", redact("No URL here", listOf("", "  ")))
    @Test fun extraCredentialPrefixesRedacted() = assertEquals(
        "http://h.test/hls/***/***/1.ts http://h.test/hlsr/***/***/1.ts http://h.test/play/***/***/1.ts http://h.test/streaming/***/***/1.ts",
        redact("http://h.test/hls/a/b/1.ts http://h.test/hlsr/a/b/1.ts http://h.test/play/a/b/1.ts http://h.test/streaming/a/b/1.ts"))
    // S2: query keys and nested URLs
    @Test fun queryKeysMatchBySubstringAndDecoding() = assertEquals(
        "http://h.test/p?api_key=***&access_token=***&Auth=***&pwd=***&sig=***&us%65rname=***&type=m3u",
        redact("http://h.test/p?api_key=a&access_token=b&Auth=c&pwd=d&sig=e&us%65rname=f&type=m3u"))
    @Test fun encodedUrlInQueryValueRedacted() = assertEquals(
        "http://h.test/p?url=http://***@x.test/live/***/***/1.ts&n=1",
        redact("http://h.test/p?url=http%3A%2F%2Fu%3Ap%40x.test%2Flive%2Fa%2Fb%2F1.ts&n=1"))
    @Test fun plainUrlInQueryValueRedacted() = assertEquals(
        "http://h.test/p?next=http://***@x.test/a.m3u",
        redact("http://h.test/p?next=http://u:p@x.test/a.m3u"))
    // S3: two segments after a prefix are always masked
    @Test fun prefixSegmentsMaskedEvenWhenLastOrDotted() = assertEquals(
        "http://h.test/live/***/*** http://h.test/live/***/***/1.ts http://h.test/movie/***/***",
        redact("http://h.test/live/user.name/pass http://h.test/live/a/b.c/1.ts http://h.test/movie/u/p.q"))
    // S4: fragment
    @Test fun fragmentSecretsRedacted() = assertEquals(
        "http://h.test/p?x=1#token=***&y=2 http://h.test/q#frag",
        redact("http://h.test/p?x=1#token=abc&y=2 http://h.test/q#frag"))
    // Other schemes and token stems with extensions
    @Test fun otherSchemesRedacted() = assertEquals(
        "rtsp://***@cam.test:554/stream rtmp://h.test/app/***.m3u8 rtmps://x.test/get?token=*** rtp://***@x.test:5004 udp://h.test:1234/live/***/***/9",
        redact("rtsp://u:p@cam.test:554/stream rtmp://h.test/app/abc123def456.m3u8 rtmps://x.test/get?token=t rtp://u:p@x.test:5004 udp://h.test:1234/live/a/b/9"))
    @Test fun tokenStemKeepsExtension() = assertEquals(
        "http://h.test/***.m3u8 http://h.test/list.m3u8 http://h.test/123.ts",
        redact("http://h.test/abc123def456.m3u8 http://h.test/list.m3u8 http://h.test/123.ts"))

    @Test fun macMaskedEverywhere() {
        val hexPair = Regex("[0-9A-Fa-f]{2}(?:[:-]|%3[Aa])[0-9A-Fa-f]{2}")
        listOf(
            "mac 00:1A:79:12:34:AB end",
            "mac 00-1a-79-12-34-ab end",
            "http://h.test/portal?x=1&mac=00%3A1A%3A79%3A12%3A34%3AAB",
            "http://h.test/portal?q=00%3a1a%3a79%3a12%3a34%3aab",
            "cookie: stb=00:1A:79:12:34:AB; x",
        ).forEach { input -> assertEquals(input, false, hexPair.containsMatchIn(redact(input))) }
        assertEquals("cookie: stb=***; x", redact("cookie: stb=00:1A:79:12:34:AB; x"))
    }

    @Test fun macQueryKeyMasked() =
        assertEquals("http://h.test/c?mac=***&type=stb", redact("http://h.test/c?mac=whatever&type=stb"))

    @Test fun macKeyMatchedOnTokenBoundaries() {
        assertEquals("http://h.test/c?macro=1&machine=2", redact("http://h.test/c?macro=1&machine=2"))
        assertEquals("http://h.test/c?stb_mac=***", redact("http://h.test/c?stb_mac=x"))
        assertEquals("http://h.test/c?mac_address=***", redact("http://h.test/c?mac_address=x"))
        assertEquals("http://h.test/c?device-mac=***", redact("http://h.test/c?device-mac=x"))
    }

    // Over-masking is the safe side: a MAC inside a longer separated run is still masked;
    // only a run glued to more hex digits (no separator) is left alone.
    @Test fun macInsideLongerHexRun() {
        assertEquals("***:CD", redact("00:1A:79:12:34:AB:CD"))
        assertEquals("0001A7912345AB", redact("0001A7912345AB"))
    }

    @Test fun igmpUrlRedactedStructurally() =
        assertEquals("igmp://***@239.1.1.1:1234", redact("igmp://user@239.1.1.1:1234"))

    @Test fun portalTokenAndProxyMaskedWhenGivenAsSecrets() = assertEquals(
        "Bearer *** via *** enc ***",
        redact("Bearer tok/en+1 via 192.168.1.1:4022 enc tok%2Fen%2B1", listOf("tok/en+1", "192.168.1.1:4022")))

    @Test fun archiveUrlShapesRedacted() {
        val urls = listOf(
            "http://h.test:8080/timeshift/archiveuser/archivepass/60/2026-11-10:20-00/42.m3u8",
            "http://h.test/streaming/timeshift.php?username=archiveuser&password=archivepass&stream=42&start=2026-11-10:20-00",
            "http://h.test/live/42/index.m3u8?utc=1762804800&lutc=1762812000&token=archivetoken",
            "http://h.test/live/42/timeshift_abs-1762804800.ts?token=archivetoken",
        )
        for (u in urls) {
            val out = redact("Failed to open $u after 3 tries")
            for (secret in listOf("archiveuser", "archivepass", "archivetoken")) {
                assertFalse("$secret leaked: $out", secret in out)
            }
        }
    }
}
