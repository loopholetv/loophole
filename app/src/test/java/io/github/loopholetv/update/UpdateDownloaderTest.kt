package io.github.loopholetv.update

import kotlinx.coroutines.test.runTest
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

    private fun downloader() = UpdateDownloader(OkHttpClient(), dir, "Loophole/0.4.1")

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
