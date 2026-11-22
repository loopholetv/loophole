package io.github.loopholetv.record

import io.github.loopholetv.player.ResolvedStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@OptIn(ExperimentalCoroutinesApi::class)
class RecorderTest {
    private lateinit var server: MockWebServer
    private val client = OkHttpClient()
    private val delays = mutableListOf<Long>()
    private val written = AtomicLong()
    private val out = ByteArrayOutputStream()
    private val sinksOpened = AtomicInteger()
    private val formats = mutableListOf<String>()

    @Before fun start() {
        server = MockWebServer().also { it.start() }
    }

    @After fun stop() {
        server.shutdown()
    }

    private fun ts(packets: Int, seed: Int = 0) =
        ByteArray(packets * 188) { if (it % 188 == 0) 0x47 else (seed + it).toByte() }

    private fun body(bytes: ByteArray, code: Int = 200, type: String = "video/mp2t") =
        MockResponse().setResponseCode(code).setHeader("Content-Type", type).setBody(Buffer().write(bytes))

    private fun stream() = ResolvedStream(server.url("/live/1.ts").toString(), "TestUA", mapOf("X-Test" to "yes"))

    private fun recorder(clock: () -> Long = { 1L }) = Recorder(client, clock) { delays += it }

    private fun countingSink(into: OutputStream = out) = RecordSink(into) { written.addAndGet(it) }

    private suspend fun Recorder.go(
        until: () -> Long,
        openSink: suspend () -> RecordSink = { sinksOpened.incrementAndGet(); countingSink() },
    ) = record(open = { stream() }, until = until, openSink = openSink, onFormat = { formats += it })

    private fun writtenReaches(n: Long): () -> Long = { if (written.get() >= n) 0L else Long.MAX_VALUE }

    @Test fun copiesTsUntilDeadline() = runTest {
        val data = ts(1000)
        server.enqueue(body(data))
        val result = recorder().go(until = writtenReaches(40_000))
        result as RecordOutcome.Finished
        assertEquals(0, result.gaps)
        assertEquals(out.size().toLong(), result.bytes)
        assertTrue(result.bytes >= 40_000)
        assertTrue(data.copyOf(out.size()).contentEquals(out.toByteArray()))
        assertEquals(listOf("ts"), formats)
        val req = server.takeRequest()
        assertEquals("TestUA", req.getHeader("User-Agent"))
        assertEquals("yes", req.getHeader("X-Test"))
    }

    @Test fun reconnectsAfterDropAndCountsGap() = runTest {
        val a = ts(348, 1) // 65424 bytes
        val b = ts(348, 2)
        server.enqueue(body(a))
        // The new connection starts in the middle of a packet; the file must resume on a packet boundary.
        server.enqueue(body(ByteArray(100) { 0x11 } + b))
        val result = recorder().go(until = writtenReaches(a.size.toLong() + b.size))
        assertEquals(RecordOutcome.Finished(a.size.toLong() + b.size, 1), result)
        assertTrue((a + b).contentEquals(out.toByteArray()))
        assertEquals(listOf(500L), delays)
        assertEquals(2, server.requestCount)
    }

    private suspend fun Recorder.dropsOf(until: () -> Long): Pair<RecordOutcome, List<Boolean>> {
        val drops = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
        val outcome = record(
            open = { stream() }, until = until, openSink = { countingSink() }, onDropped = { drops += it },
        )
        return outcome to drops.toList()
    }

    @Test fun aCompleteBodyIsReportedAsACleanEnd() = runTest {
        server.enqueue(body(ts(10)))
        server.enqueue(body(ts(10)))
        // The first connection delivers everything and ends; the deadline comes with the second.
        val (_, drops) = recorder().dropsOf(until = writtenReaches(20 * 188))
        assertEquals(true, drops.first())
        assertEquals(1, drops.size)
    }

    @Test fun aBodyCutShortIsReportedAsBroken() = runTest {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "video/mp2t").setBody(Buffer().write(ts(10)))
                .setHeader("Content-Length", 20 * 188).setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_END),
        )
        server.enqueue(body(ts(10, 3)))
        val (_, drops) = recorder().dropsOf(until = writtenReaches(20 * 188))
        assertEquals(false, drops.first())
    }

    @Test fun anEmptyOkIsNeverACleanEnd() = runTest {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "video/mp2t")) }
        val (_, drops) = recorder().dropsOf(until = { Long.MAX_VALUE })
        assertTrue(true !in drops)
    }

    @Test fun refusedThreeTimesGivesRefused() = runTest {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(509)) }
        val result = recorder().go(until = { Long.MAX_VALUE })
        assertEquals(RecordOutcome.Refused(509), result)
        assertEquals(3, server.requestCount)
        assertEquals(listOf(REFUSED_WINDOW_MS / 2, REFUSED_WINDOW_MS / 2), delays)
        assertEquals(0, sinksOpened.get())
    }

    @Test fun errorPageIsRefusedNotRecorded() = runTest {
        repeat(3) { server.enqueue(body("<html>busy</html>".toByteArray(), type = "text/html")) }
        val result = recorder().go(until = { Long.MAX_VALUE })
        assertEquals(RecordOutcome.Refused(null), result)
        assertEquals(3, server.requestCount)
        assertEquals(0, sinksOpened.get())
        assertEquals(0, out.size())
    }

    @Test fun storageFullStops() = runTest {
        server.enqueue(body(ts(1000)))
        val full = object : OutputStream() {
            override fun write(b: Int) = throw IOException("write failed: ENOSPC (No space left on device)")
        }
        val result = recorder().go(until = { Long.MAX_VALUE }, openSink = { countingSink(full) })
        assertEquals(RecordOutcome.StorageFull, result)
    }

    @Test fun writeErrorReopensSinkThenContinues() = runTest {
        server.enqueue(body(ts(5000)))
        var ticks = 0L
        val clock = { ticks += 2_000; ticks }
        val flaky = object : OutputStream() {
            override fun write(b: Int) = throw IOException("nas blip")
        }
        val opens = AtomicInteger()
        val result = recorder(clock).go(
            until = writtenReaches(100_000),
            openSink = { if (opens.getAndIncrement() == 0) countingSink(flaky) else countingSink() },
        )
        result as RecordOutcome.Finished
        assertEquals(1, result.gaps)
        assertEquals(2, opens.get())
        assertTrue(result.bytes >= 100_000)
        assertEquals(out.size().toLong(), result.bytes)
    }

    @Test fun writeErrorForThirtySecondsGivesStorageError() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = body(ts(40))
        }
        var ticks = 0L
        val clock = { ticks += 1_000; ticks }
        val broken = object : OutputStream() {
            override fun write(b: Int) = throw IOException("gone")
        }
        val result = recorder(clock).go(until = { Long.MAX_VALUE }, openSink = { countingSink(broken) })
        assertTrue(result.toString(), result is RecordOutcome.StorageError)
        assertTrue(ticks >= 30_000)
    }

    @Test fun extendMovesDeadline() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = body(ts(300))
        }
        var ticks = 0L
        var deadline = 1_000L
        val result = recorder { ticks += 100; ticks }.go(until = {
            // The viewer extends the recording shortly before it would have ended.
            if (ticks >= 900) deadline = 3_000L
            deadline
        })
        assertTrue(result is RecordOutcome.Finished)
        assertTrue("stopped at $ticks", ticks >= 3_000L)
    }

    @Test fun cancellationClosesUpstreamAndKeepsBytes() = runTest {
        server.enqueue(body(ts(2000)).throttleBody(2_000, 50, TimeUnit.MILLISECONDS))
        val closed = AtomicInteger()
        val sink = RecordSink(object : OutputStream() {
            override fun write(b: Int) { out.write(b) }
            override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len) }
            override fun close() { closed.incrementAndGet() }
        }) { written.addAndGet(it) }
        val job = async { recorder().go(until = { Long.MAX_VALUE }, openSink = { sink }) }
        withContext(Dispatchers.IO) {
            val limit = System.currentTimeMillis() + 10_000
            while (written.get() == 0L && System.currentTimeMillis() < limit) Thread.sleep(10)
        }
        assertTrue(written.get() > 0)
        job.cancel()
        try {
            job.await()
            fail("expected cancellation")
        } catch (_: CancellationException) {
        }
        assertEquals(1, closed.get())
        assertTrue(out.size() > 0)
    }

    @Test fun keepAliveRunsWhileRecording() = runTest {
        server.enqueue(body(ts(100)))
        val pinged = AtomicInteger()
        val result = recorder().record(
            open = { stream() },
            until = writtenReaches(1),
            openSink = { countingSink() },
            keepAlive = { pinged.incrementAndGet(); kotlinx.coroutines.awaitCancellation() },
        )
        assertTrue(result is RecordOutcome.Finished)
        assertEquals(1, pinged.get())
    }

    @Test fun extensionForSniffsTheFirstBytes() {
        val r = recorder()
        assertEquals("ts", r.extensionFor(ts(4), "http://h/a"))
        val mp4 = byteArrayOf(0, 0, 0, 0x18) + "ftypisom".toByteArray()
        assertEquals("mp4", r.extensionFor(mp4, "http://h/a"))
        assertEquals("mp4", r.extensionFor(ByteArray(0), "http://h/movie.mp4?token=1"))
        assertEquals("ts", r.extensionFor(ByteArray(0), "http://h/live/1"))
        assertFalse(formats.isNotEmpty())
    }

    @Test fun unexpectedSinkErrorsBecomeWriteFailuresWithoutTheirMessage() {
        val sink = RecordSink(object : OutputStream() {
            override fun write(b: Int) = throw SecurityException("secret://share/path")
        }) { }
        try {
            sink.write(ByteArray(4), 0, 4)
            fail("expected a write failure")
        } catch (e: StorageWriteException) {
            assertFalse(e.message.orEmpty().contains("secret"))
        }
    }
}
