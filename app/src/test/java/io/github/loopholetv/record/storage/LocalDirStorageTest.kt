package io.github.loopholetv.record.storage

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalDirStorageTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun storage() = LocalDirStorage(tmp.root, "Internal storage", "internal") { it.length() }

    @Test
    fun appendThenReadFromOffset() = runBlocking {
        val s = storage()
        s.openAppend("a.ts").use { it.write(byteArrayOf(1, 2, 3)) }
        s.openAppend("a.ts").use { it.write(byteArrayOf(4, 5)) }
        assertEquals(5L, s.size("a.ts"))
        val rest = s.openRead("a.ts", 2).use { it.readBytes() }
        assertArrayEquals(byteArrayOf(3, 4, 5), rest)
        assertTrue(File(tmp.root, "Loophole Recordings/a.ts").exists())
    }

    @Test
    fun testWritesAndRemovesProbe() = runBlocking {
        val s = storage()
        assertEquals(StorageResult.Ok, s.test())
        assertNull(s.size(".loophole-test"))
        assertFalse(File(tmp.root, "Loophole Recordings/.loophole-test").exists())
    }

    @Test
    fun sizeNullWhenMissing() = runBlocking {
        assertNull(storage().size("nope.ts"))
    }

    @Test
    fun deleteReturnsFalseWhenMissing() = runBlocking {
        val s = storage()
        assertFalse(s.delete("nope.ts"))
        s.openAppend("x.ts").use { it.write(1) }
        assertTrue(s.delete("x.ts"))
        assertNull(s.size("x.ts"))
    }

    @Test
    fun freeBytesReturnsTheInjectedValueIncludingUnknown() = runBlocking {
        assertEquals(42L, LocalDirStorage(tmp.root, "x", "x") { 42L }.freeBytes())
        assertNull(LocalDirStorage(tmp.root, "x", "x") { null }.freeBytes())
    }
}
