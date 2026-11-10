package io.github.loopholetv.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateUrlsTest {
    private val strict = UpdateUrlPolicy.GITHUB_ONLY

    @Test
    fun githubHostsOverHttpsAreAllowed() {
        assertTrue(strict.allows("https://api.github.com/repos/loopholetv/loophole/releases/latest"))
        assertTrue(strict.allows("https://github.com/loopholetv/loophole/releases/download/v0.4.2/loophole.apk"))
        assertTrue(strict.allows("https://objects.githubusercontent.com/x"))
        assertTrue(strict.allows("https://release-assets.githubusercontent.com/x"))
    }

    @Test
    fun httpIsRejected() {
        assertFalse(strict.allows("http://api.github.com/repos/loopholetv/loophole/releases/latest"))
        assertFalse(strict.allows("http://github.com/a.apk"))
    }

    @Test
    fun otherHostsAreRejected() {
        assertFalse(strict.allows("https://evil.example/a.apk"))
        assertFalse(strict.allows("https://github.com.evil.example/a.apk"))
        assertFalse(strict.allows("https://raw.githubusercontent.com/a.apk"))
        assertFalse(strict.allows("https://10.0.2.2:8765/a.apk"))
        assertFalse(strict.allows("not a url"))
        assertFalse(strict.allows("ftp://github.com/a.apk"))
    }

    @Test
    fun theDebugPolicyAllowsAnyHttpUrlOnly() {
        assertTrue(UpdateUrlPolicy.ANY_HTTP.allows("http://10.0.2.2:8765/latest"))
        assertFalse(UpdateUrlPolicy.ANY_HTTP.allows("ftp://10.0.2.2/a.apk"))
    }
}
