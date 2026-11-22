package io.github.loopholetv.record.storage

import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMB2MessageCommandCode
import com.hierynomus.mssmb2.SMBApiException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbStorageTest {
    private class FakeSession(private val files: MutableMap<String, ByteArray>, private val log: MutableList<String>) : SmbSession {
        var closed = false
        override fun mkdirs(path: String) { log += "mkdirs $path" }
        override fun openAppend(path: String): OutputStream {
            log += "append $path"
            return object : ByteArrayOutputStream() {
                override fun close() {
                    files[path] = (files[path] ?: ByteArray(0)) + toByteArray()
                }
            }
        }
        override fun size(path: String): Long? = files[path]?.size?.toLong()
        override fun openRead(path: String, offset: Long): InputStream =
            ByteArrayInputStream(files.getValue(path), offset.toInt(), files.getValue(path).size - offset.toInt())
        override fun delete(path: String): Boolean = files.remove(path) != null
        override fun freeBytes(): Long? = 1234L
        override fun close() { closed = true }
    }

    private class FakeConnector(val files: MutableMap<String, ByteArray> = mutableMapOf(), val failWith: Throwable? = null, val onConnect: () -> Unit = {}) : SmbConnector {
        val log = mutableListOf<String>()
        val sessions = mutableListOf<FakeSession>()
        override fun connect(host: String, share: String, user: String, password: String, domain: String): SmbSession {
            failWith?.let { throw it }
            onConnect()
            log += "connect $host $share $user $domain"
            return FakeSession(files, log).also { sessions += it }
        }
    }

    private fun storage(c: SmbConnector, folder: String = "tv") =
        SmbStorage("nas", "media", folder, "user", "s3cret", "HOME", c)

    private val dir = "tv\\$RECORDINGS_FOLDER"

    @Test
    fun appendOpensExistingFileForAppend() = runBlocking {
        val c = FakeConnector(mutableMapOf("$dir\\a.ts" to byteArrayOf(1, 2, 3)))
        val s = storage(c)
        s.openAppend("a.ts").use { it.write(byteArrayOf(4, 5)) }
        assertEquals(5L, s.size("a.ts"))
        assertArrayEquals(byteArrayOf(3, 4, 5), s.openRead("a.ts", 2).use { it.readBytes() })
        assertTrue(c.log.contains("append $dir\\a.ts"))
        assertTrue(c.log.contains("mkdirs $dir"))
        assertTrue(c.sessions.all { it.closed })
    }

    @Test
    fun testProbeIsDeleted() = runBlocking {
        val c = FakeConnector()
        assertEquals(StorageResult.Ok, storage(c).test())
        assertNull(c.files["$dir\\$PROBE_NAME"])
        assertFalse(c.files.containsKey("$dir\\$PROBE_NAME"))
    }

    @Test
    fun authFailureMapsToMessage() = runBlocking {
        val e = SMBApiException(NtStatus.STATUS_LOGON_FAILURE.value, SMB2MessageCommandCode.SMB2_SESSION_SETUP, "bad s3cret", null)
        val result = storage(FakeConnector(failWith = e)).test()
        assertEquals(StorageResult.Failed("The NAS refused the user name or password."), result)
    }

    @Test
    fun rootFolderPathsHaveNoLeadingSeparator() = runBlocking {
        val c = FakeConnector()
        storage(c, folder = "/").openAppend("x.ts").use { it.write(1) }
        assertTrue(c.log.contains("append $RECORDINGS_FOLDER\\x.ts"))
    }

    @Test
    fun idAndLabelAreSecretFree() {
        val s = storage(FakeConnector(), folder = "/tv/")
        assertEquals("smb://nas/media/tv", s.id)
        assertFalse((s.id + s.label + s.toString()).contains("s3cret"))
    }

    @Test
    fun freeBytesComesFromTheShareAndIsNullWhenUnreachable() = runBlocking {
        assertEquals(1234L, storage(FakeConnector()).freeBytes())
        assertNull(storage(FakeConnector(failWith = java.net.SocketTimeoutException())).freeBytes())
    }

    @Test
    fun writeFailureIsPlainWords() = runBlocking {
        val e = java.net.SocketTimeoutException("s3cret")
        val msg = runCatching { storage(FakeConnector(failWith = e)).openAppend("a.ts") }.exceptionOrNull()?.message
        assertEquals("Couldn't reach the NAS at nas.", msg)
    }

    @Test
    fun blankUserWithPasswordIsAnAuthFailureNotGuest() = runBlocking {
        val c = FakeConnector()
        val s = SmbStorage("nas", "media", "tv", "", "s3cret", "", c)
        assertEquals(StorageResult.Failed("The NAS refused the user name or password."), s.test())
        assertTrue(c.log.none { it.startsWith("connect") })
    }

    @Test
    fun probeIsDeletedEvenWhenReadBackFails() = runBlocking {
        val c = FakeConnector()
        val s = storage(c)
        // The fake returns exactly what was written, so force a failure by making read-back throw.
        val failing = object : SmbConnector {
            override fun connect(host: String, share: String, user: String, password: String, domain: String): SmbSession {
                val real = c.connect(host, share, user, password, domain)
                return object : SmbSession by real {
                    override fun openRead(path: String, offset: Long): InputStream = throw java.io.IOException("boom")
                }
            }
        }
        assertTrue(SmbStorage("nas", "media", "tv", "u", "p", "", failing).test() is StorageResult.Failed)
        assertFalse(c.files.containsKey("$dir\\$PROBE_NAME"))
    }

    @Test
    fun cancelledCallerClosesTheOpenedSession() = runBlocking {
        lateinit var job: kotlinx.coroutines.Job
        val c = FakeConnector(onConnect = { job.cancel() })
        job = launch(kotlinx.coroutines.Dispatchers.Default, start = kotlinx.coroutines.CoroutineStart.LAZY) { storage(c).openAppend("a.ts") }
        job.start()
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(1, c.sessions.size)
        assertTrue(c.sessions.all { it.closed })
    }
}
