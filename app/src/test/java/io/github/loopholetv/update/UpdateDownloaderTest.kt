package io.github.loopholetv.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class UpdateDownloaderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var dir: File
    private val bytes = ByteArray(300_000) { (it % 251).toByte() }

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        dir = File(tmp.root, "updates")
    }

    @After
    fun tearDown() = server.shutdown()

    private fun release(size: Long = bytes.size.toLong()) =
        Release(SemVer(0, 4, 2), server.url("/a.apk").toString(), size)

    private fun downloader(
        policy: UpdateUrlPolicy = UpdateUrlPolicy.ANY_HTTP,
        freeSpace: (File) -> Long = { Long.MAX_VALUE },
    ) = UpdateDownloader(OkHttpClient(), dir, "Loophole/0.4.1", policy, freeSpace)

    private suspend fun assertFails(block: suspend () -> Unit): Exception {
        try {
            block()
        } catch (e: IOException) {
            return e
        }
        fail("expected a failure")
        error("unreachable")
    }

    @Test
    fun aUrlThePolicyRejectsIsNeverRequested() = runTest {
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        assertTrue(assertFails { downloader(UpdateUrlPolicy.GITHUB_ONLY).download(release()) {} } is BlockedUpdateUrlException)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun tooLittleFreeSpaceFailsBeforeDownloading() = runTest {
        val size = bytes.size.toLong()
        val needed = 2 * size + SPACE_MARGIN_BYTES
        assertTrue(assertFails { downloader(freeSpace = { needed - 1 }).download(release()) {} } is NotEnoughSpaceException)
        assertEquals(0, server.requestCount)
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        downloader(freeSpace = { needed }).download(release()) {}
    }

    @Test
    fun anOversizedReleaseIsRefused() = runTest {
        assertFails { downloader().download(release(size = MAX_APK_BYTES + 1)) {} }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aContentLengthOtherThanTheReleaseSizeFails() = runTest {
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        assertFails { downloader().download(release(size = bytes.size - 1L)) {} }
        assertTrue(dir.list().isNullOrEmpty())
    }

    @Test
    fun theDigestIsCheckedWhenTheReleaseHasOne() = runTest {
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        assertFails { downloader().download(release().copy(sha256 = "00".repeat(32))) {} }
        assertTrue(dir.list().isNullOrEmpty())
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        val file = downloader().download(release().copy(sha256 = sha256Hex(bytes))) {}
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test
    fun cancellingStopsTheCallAtOnceAndLeavesNothing() = runBlocking {
        // 1 KB a second: without Call.cancel() this would take minutes.
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)).throttleBody(1024, 1, TimeUnit.SECONDS))
        val job = launch(Dispatchers.Default) { downloader().download(release()) {} }
        delay(500)
        val started = System.nanoTime()
        job.cancelAndJoin()
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(3))
        assertTrue(dir.list().isNullOrEmpty())
    }

    @Test
    fun downloadsWithProgressAndReplacesStaleFiles() = runTest {
        dir.mkdirs()
        File(dir, "loophole-0.4.0.apk").writeText("old")
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        val progress = mutableListOf<Int>()
        val file = downloader().download(release()) { progress += it }
        assertArrayEquals(bytes, file.readBytes())
        assertEquals("loophole-0.4.2.apk", file.name)
        assertEquals(listOf(file.name), dir.list()!!.toList())
        assertEquals(0, progress.first())
        assertEquals(100, progress.last())
        assertEquals(progress.sorted(), progress)
        assertEquals("Loophole/0.4.1", server.takeRequest().getHeader("User-Agent"))
        assertEquals(file, downloader().existing(release()))
    }

    @Test
    fun aShortDownloadFailsAndLeavesNothing() = runTest {
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        try {
            downloader().download(release(size = bytes.size + 10L)) {}
            fail("expected a failure")
        } catch (_: IOException) {
        }
        assertTrue(dir.list().isNullOrEmpty())
        assertNull(downloader().existing(release(size = bytes.size + 10L)))
    }

    @Test
    fun anHttpErrorFails() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        try {
            downloader().download(release()) {}
            fail("expected a failure")
        } catch (_: IOException) {
        }
        assertFalse(downloader().fileFor(release()).exists())
    }
}
