package io.github.loopholetv.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionsTest {
    @Test
    fun parsesTagsWithAndWithoutPrefix() {
        assertEquals(SemVer(0, 4, 2), SemVer.parse("v0.4.2"))
        assertEquals(SemVer(1, 10, 0), SemVer.parse("1.10.0"))
        assertEquals(SemVer(0, 4, 2, "test"), SemVer.parse("0.4.2-test"))
        assertEquals(SemVer(2, 0, 1), SemVer.parse(" V2.0.1 "))
        assertEquals(SemVer(1, 2, 3), SemVer.parse("1.2.3+build.5"))
    }

    @Test
    fun rejectsMalformedTags() {
        listOf("", "v", "1.2", "1.2.x", "latest", "v1.2.3.4", "1..3", "-1.2.3", "v1.2.3-").forEach {
            assertNull(it, SemVer.parse(it))
        }
    }

    @Test
    fun comparesNumericallyNotLexically() {
        assertTrue(SemVer(0, 10, 0) > SemVer(0, 9, 9))
        assertTrue(SemVer(1, 0, 0) > SemVer(0, 99, 99))
        assertTrue(SemVer(0, 4, 10) > SemVer(0, 4, 2))
    }

    @Test
    fun aPreReleaseIsOlderThanItsRelease() {
        assertTrue(SemVer(0, 4, 2) > SemVer(0, 4, 2, "test"))
        assertTrue(SemVer(0, 4, 2, "test") > SemVer(0, 4, 1))
        assertTrue(SemVer(0, 4, 2, "beta") > SemVer(0, 4, 2, "alpha"))
    }

    @Test
    fun isNewerOnlyForAStrictlyHigherVersion() {
        assertTrue(isNewerVersion("v0.4.2", "0.4.1"))
        assertTrue(isNewerVersion("v0.5.0", "0.4.1"))
        assertFalse(isNewerVersion("v0.4.1", "0.4.1"))
        assertFalse(isNewerVersion("v0.4.0", "0.4.1"))
        assertTrue(isNewerVersion("v0.4.2", "0.4.2-test"))
        assertFalse(isNewerVersion("v0.4.1", "0.4.2-test"))
    }

    @Test
    fun anUnreadableVersionIsNeverNewer() {
        assertFalse(isNewerVersion("nightly", "0.4.1"))
        assertFalse(isNewerVersion("v0.5.0", "dev"))
    }

    @Test
    fun displayDropsThePrefix() {
        assertEquals("0.4.2", SemVer.parse("v0.4.2")!!.display)
        assertEquals("0.4.2-test", SemVer.parse("v0.4.2-test")!!.display)
    }
}
