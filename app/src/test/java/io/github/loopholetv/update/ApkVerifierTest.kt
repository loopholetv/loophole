package io.github.loopholetv.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkVerifierTest {
    private val release = "a1".repeat(32)
    private val other = "b2".repeat(32)
    private val installed = PackageFacts("io.github.loopholetv", 7, setOf(release))

    @Test
    fun aNewerBuildFromTheSameSignerPasses() {
        assertEquals(VerifyResult.OK, verifyUpdate(PackageFacts("io.github.loopholetv", 8, setOf(release)), installed))
    }

    @Test
    fun anUnreadableApkFails() {
        assertEquals(VerifyResult.UNREADABLE, verifyUpdate(null, installed))
    }

    @Test
    fun anotherPackageFails() {
        assertEquals(VerifyResult.WRONG_PACKAGE, verifyUpdate(PackageFacts("com.example.other", 8, setOf(release)), installed))
    }

    @Test
    fun theSameOrAnOlderVersionCodeFails() {
        assertEquals(VerifyResult.NOT_NEWER, verifyUpdate(PackageFacts("io.github.loopholetv", 7, setOf(release)), installed))
        assertEquals(VerifyResult.NOT_NEWER, verifyUpdate(PackageFacts("io.github.loopholetv", 6, setOf(release)), installed))
    }

    @Test
    fun aDifferentSignerFails() {
        assertEquals(VerifyResult.WRONG_SIGNER, verifyUpdate(PackageFacts("io.github.loopholetv", 8, setOf(other)), installed))
        assertEquals(VerifyResult.WRONG_SIGNER, verifyUpdate(PackageFacts("io.github.loopholetv", 8, emptySet()), installed))
    }

    @Test
    fun signerSetsMustMatchExactly() {
        assertTrue(sameSigners(setOf(release), setOf(release)))
        assertTrue(sameSigners(setOf(release, other), setOf(other, release)))
        assertFalse(sameSigners(setOf(release), setOf(release, other)))
        assertFalse(sameSigners(setOf(release, other), setOf(release)))
        // Nothing to compare is never a match.
        assertFalse(sameSigners(emptySet(), emptySet()))
    }

    @Test
    fun digestIsLowercaseHexSha256() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256Hex("abc".toByteArray()),
        )
    }
}
