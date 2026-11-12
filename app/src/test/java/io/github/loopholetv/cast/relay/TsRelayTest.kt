package io.github.loopholetv.cast.relay

import java.io.ByteArrayOutputStream
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TsRelayTest {
    private var now = 0L
    private fun relay(
        capacityBytes: Int = 12 * 1024 * 1024,
        joinBehindMs: Long = 2_000,
        paced: Boolean = false,
        beforeCopy: (() -> Unit)? = null,
    ) = TsRelay(capacityBytes, { now }, joinBehindMs, paced, beforeCopy)

    @Test fun readerGetsPatPmtFirst() {
        val relay = relay()
        val pat = patPacket(pmtPid = 0x1000)
        val pmt = tablePacket(pid = 0x1000)
        relay.write(pat)
        relay.write(pmt)
        var index = 0
        for (t in 0L..5_000L step 100) {
            now = t
            repeat(PACKETS_PER_TICK) { relay.write(videoPacket(index++)) }
        }
        val out = readAll(relay.reader())
        assertArrayEquals(pat, out.copyOfRange(0, PACKET))
        assertArrayEquals(pmt, out.copyOfRange(PACKET, 2 * PACKET))
        val first = indexOf(out, 2 * PACKET)
        assertTrue("joined at the live edge minus the join delay, not the start", first > 0)
        assertContiguousVideo(out, 2 * PACKET, first, index - 1)
    }

    @Test fun replaysTheLatestTables() {
        val relay = relay()
        relay.write(patPacket(pmtPid = 0x1000))
        relay.write(tablePacket(pid = 0x1000))
        val newPat = patPacket(pmtPid = 0x1001)
        val newPmt = tablePacket(pid = 0x1001, fill = 0x33)
        relay.write(newPat)
        relay.write(tablePacket(pid = 0x1000, fill = 0x22)) // no longer listed in the PAT
        relay.write(newPmt)
        var index = 0
        for (t in 0L..5_000L step 100) {
            now = t
            repeat(PACKETS_PER_TICK) { relay.write(videoPacket(index++)) }
        }
        val out = readAll(relay.reader())
        assertArrayEquals(newPat, out.copyOfRange(0, PACKET))
        assertArrayEquals(newPmt, out.copyOfRange(PACKET, 2 * PACKET))
        assertEquals(TsRelay.SYNC, out[2 * PACKET])
        assertEquals(VIDEO_PID, pidOf(out, 2 * PACKET))
    }

    @Test fun readerStartsAboutJoinBehind() {
        val relay = relay()
        var index = 0
        for (t in 0L..10_000L step 100) {
            now = t
            repeat(PACKETS_PER_TICK) { relay.write(videoPacket(index++)) }
        }
        val out = readAll(relay.reader())
        val first = indexOf(out, 0)
        val startByte = first.toLong() * PACKET
        val eightSecondMark = 80L * PACKETS_PER_TICK * PACKET
        assertTrue("started at byte $startByte", abs(startByte - eightSecondMark) <= TsRelay.CHUNK_BYTES)
        assertContiguousVideo(out, 0, first, index - 1)
    }

    @Test fun readerStartsAtOldestWhenHistoryIsShorterThanJoinBehind() {
        val relay = relay()
        var index = 0
        for (t in 0L..1_000L step 100) {
            now = t
            repeat(PACKETS_PER_TICK) { relay.write(videoPacket(index++)) }
        }
        val out = readAll(relay.reader())
        assertContiguousVideo(out, 0, 0, index - 1)
    }

    @Test fun joinLeavesHeadroomWhenTheRingHasWrapped() {
        val relay = relay(capacityBytes = 4 * TsRelay.CHUNK_BYTES)
        val total = 10 * TsRelay.CHUNK_BYTES / PACKET + 7
        now = 10_000 // everything is newer than the join point, so the reader falls back to the oldest kept
        for (i in 0 until total) relay.write(videoPacket(i))
        val out = readAll(relay.reader())
        val startByte = indexOf(out, 0).toLong() * PACKET
        val oldestKept = (total.toLong() * PACKET / TsRelay.CHUNK_BYTES - 3) * TsRelay.CHUNK_BYTES
        assertEquals(oldestKept + TsRelay.CHUNK_BYTES, startByte)
    }

    @Test fun twoReadersGetSameBytes() {
        val relay = relay()
        val a = relay.reader()
        val b = relay.reader()
        val written = ByteArrayOutputStream()
        var index = 0
        for (size in listOf(1, 7, 350, 2, 1_000, 13)) {
            val batch = ByteArrayOutputStream()
            repeat(size) { batch.write(videoPacket(index++)) }
            written.write(batch.toByteArray())
            relay.write(batch.toByteArray())
        }
        val outA = readAll(a)
        val outB = readAll(b)
        assertArrayEquals(written.toByteArray(), outA)
        assertArrayEquals(outA, outB)
    }

    @Test(timeout = 30_000) fun concurrentReadersSeeEveryPacketInOrder() {
        val relay = relay()
        val total = 6_000
        val readers = List(2) { relay.reader() }
        val failures = AtomicReference<Throwable?>()
        val results = arrayOfNulls<ByteArray>(2)
        val threads = readers.mapIndexed { i, reader ->
            Thread {
                try {
                    results[i] = readToEnd(reader)
                } catch (t: Throwable) {
                    failures.compareAndSet(null, t)
                }
            }.apply { start() }
        }
        val producer = Thread {
            var index = 0
            while (index < total) {
                val batch = ByteArrayOutputStream()
                repeat(minOf(1 + index % 17, total - index)) { batch.write(videoPacket(index++)) }
                relay.write(batch.toByteArray())
            }
            relay.close()
        }.apply { start() }
        producer.join()
        threads.forEach { it.join() }
        assertNull(failures.get())
        results.forEach { assertContiguousVideo(it!!, 0, 0, total - 1) }
    }

    @Test(timeout = 60_000) fun overtakenReaderNeverSeesOverwrittenBytes() {
        val total = 20_000
        val stream = ByteArray(total * PACKET).also { s -> for (i in 0 until total) videoPacket(i).copyInto(s, i * PACKET) }
        val maxWrite = PACKET * 29
        var completed = 0
        var dropped = 0
        for (round in 0 until 30) {
            val chunks = 2 + round % 4
            // Lag the producer may build up over the reader before it waits for it:
            //  - safe: the reader's chunk can never be reused, so the round always completes;
            //  - racing: past the safe bound, so reuse races with copies and the reader may or may not be dropped;
            //  - stalled: the reader starts only after everything is written, so it is always dropped.
            val safeLag = (chunks - 1L) * TsRelay.CHUNK_BYTES - maxWrite
            val mode = round % 5
            val maxLag = when (mode) {
                0, 1 -> safeLag
                2, 3 -> safeLag + maxWrite + (round % 3 + 1) * TsRelay.CHUNK_BYTES / 4
                else -> Long.MAX_VALUE
            }
            val relay = relay(capacityBytes = chunks * TsRelay.CHUNK_BYTES)
            val reader = relay.reader()
            val failure = AtomicReference<String?>()
            val consumed = AtomicLong()
            val done = AtomicBoolean()
            val wasDropped = AtomicBoolean()
            val start = CountDownLatch(if (mode == 4) 1 else 0)
            val consumer = Thread {
                val buf = ByteArray(4_093)
                var position = 0
                try {
                    start.await()
                    while (true) {
                        val n = reader.read(buf, 0, buf.size, timeoutMs = 10_000)
                        if (n < 0) break
                        for (k in 0 until n) {
                            if (buf[k] != stream[position + k]) {
                                failure.compareAndSet(null, "byte ${position + k} was overwritten data")
                                return@Thread
                            }
                        }
                        position += n
                        consumed.set(position.toLong())
                    }
                } catch (_: SlowReaderException) {
                    wasDropped.set(true) // being dropped is fine; returning overwritten bytes is not
                } finally {
                    done.set(true)
                }
            }.apply { start() }
            var off = 0
            while (off < stream.size) {
                while (!done.get() && off - consumed.get() > maxLag) Thread.yield()
                val len = minOf(PACKET * (1 + off / PACKET % 29), stream.size - off)
                relay.write(stream, off, len)
                off += len
            }
            relay.close()
            start.countDown()
            consumer.join()
            assertNull(failure.get())
            if (mode < 2) assertTrue("round $round within the safe lag completes", !wasDropped.get())
            if (wasDropped.get()) dropped++ else completed++
        }
        assertTrue("some rounds complete ($completed)", completed > 0)
        assertTrue("some rounds are dropped ($dropped)", dropped > 0)
    }

    @Test fun misalignedInputResyncsOn0x47() {
        val packets = List(10) { videoPacket(it) }
        val input = ByteArrayOutputStream().apply {
            write(junk(37, syncAt = intArrayOf(3, 20)))
            (0..2).forEach { write(packets[it]) }
            write(junk(5, syncAt = intArrayOf(2)))
            (3..5).forEach { write(packets[it]) }
            write(junk(190, syncAt = intArrayOf(1, 100, 189))) // 1 and 189 line up; 377 does not
            (6..8).forEach { write(packets[it]) }
            write(packets[9], 0, 100) // a trailing partial packet is held back
        }.toByteArray()
        val expected = ByteArrayOutputStream().apply { (0..8).forEach { write(packets[it]) } }.toByteArray()
        for (sizes in listOf(intArrayOf(1), intArrayOf(1, 50, 200, 3, 333, 188, 17), intArrayOf(input.size))) {
            val relay = relay()
            val reader = relay.reader()
            writeInPieces(relay, input, sizes)
            assertArrayEquals(expected, readAll(reader))
        }
    }

    @Test fun falseSyncInJunkIsNotTakenForAPacket() {
        val inputs = listOf(
            // A lone 0x47 just before the stream.
            junk(10, syncAt = intArrayOf(1)),
            // A 0x47 whose +376 lands on the first real packet, but whose +188 is junk.
            junk(5 + 2 * PACKET, syncAt = intArrayOf(5)),
        ).map { lead ->
            ByteArrayOutputStream().apply {
                write(lead)
                (0..4).forEach { write(videoPacket(it)) }
            }.toByteArray()
        }
        for (input in inputs) {
            for (sizes in listOf(intArrayOf(1), intArrayOf(2, 187, 189), intArrayOf(input.size))) {
                val relay = relay()
                val reader = relay.reader()
                writeInPieces(relay, input, sizes)
                assertContiguousVideo(readAll(reader), 0, 0, 4)
            }
        }
    }

    @Test fun lostSyncMidStreamIsRecoveredWithoutGarbage() {
        val relay = relay()
        val reader = relay.reader()
        (0..2).forEach { relay.write(videoPacket(it)) }
        // A truncated packet: its sync byte is genuine, but the next packet starts 100 bytes in.
        relay.write(videoPacket(3), 0, 100)
        (4..7).forEach { relay.write(videoPacket(it)) }
        val out = readAll(reader)
        assertContiguousVideo(out.copyOfRange(0, 3 * PACKET), 0, 0, 2)
        // The truncated packet is glued to the start of packet 4, then sync is lost and found again at packet 5.
        assertContiguousVideo(out, 4 * PACKET, 5, 7)
    }

    @Test fun ignoresBrokenTables() {
        val relay = relay()
        val pat = patPacket(pmtPid = 0x1000)
        val pmt = tablePacket(pid = 0x1000)
        relay.write(pat)
        relay.write(pmt)
        relay.write(patPacket(pmtPid = 0x1001).also { it[1] = (it[1].toInt() or 0x80).toByte() }) // transport error
        relay.write(patPacket(pmtPid = 0x1002).also { it[10] = 0xC0.toByte() }) // next, not current
        relay.write(patPacket(pmtPid = 0x1003).also { it[5] = 0x7E }) // not a PAT section
        relay.write(patPacket(pmtPid = 0x1004).also { it[4] = 0xB7.toByte() }) // section starts past the packet
        relay.write(tablePacket(pid = 0x1000, fill = 0x22).also { it[1] = (it[1].toInt() or 0x80).toByte() })
        relay.write(tablePacket(pid = 0x1000, fill = 0x23).also { it[10] = 0xC0.toByte() })
        relay.write(tablePacket(pid = 0x1000, fill = 0x24).also { it[5] = 0x7E })
        repeat(3) { relay.write(videoPacket(it)) }
        val out = readAll(relay.reader())
        assertArrayEquals(pat, out.copyOfRange(0, PACKET))
        assertArrayEquals(pmt, out.copyOfRange(PACKET, 2 * PACKET))
        assertArrayEquals(pat, out.copyOfRange(2 * PACKET, 3 * PACKET)) // the stream itself continues from the start
    }

    @Test(timeout = 10_000) fun slowReaderIsDroppedProducerNeverBlocks() {
        val relay = relay(capacityBytes = 64 * 1024)
        val stuck = relay.reader()
        val batch = ByteArrayOutputStream()
        val perWrite = 64 * 1024 / PACKET
        var index = 0
        var written = 0L
        while (written < 1024 * 1024) {
            batch.reset()
            repeat(perWrite) { batch.write(videoPacket(index++)) }
            relay.write(batch.toByteArray())
            written += perWrite * PACKET
        }
        assertThrows(SlowReaderException::class.java) { stuck.read(ByteArray(PACKET), 0, PACKET, timeoutMs = 0) }
        assertThrows(SlowReaderException::class.java) { stuck.read(ByteArray(PACKET), 0, PACKET, timeoutMs = 0) }
        assertTrue("memory stays bounded", relay.allocatedChunks <= 2)
        val fresh = readAll(relay.reader())
        assertContiguousVideo(fresh, 0, indexOf(fresh, 0), index - 1)
    }

    @Test fun readerOvertakenWhileCopyingThrows() {
        val perChunk = TsRelay.CHUNK_BYTES / PACKET
        var index = 0
        var lapped = false
        lateinit var relay: TsRelay
        // The producer laps the reader's chunk after the reader has checked its position but before it copies.
        relay = relay(capacityBytes = 2 * TsRelay.CHUNK_BYTES, beforeCopy = {
            if (!lapped) {
                lapped = true
                repeat(perChunk) { relay.write(videoPacket(index++)) }
            }
        })
        val reader = relay.reader()
        repeat(perChunk + 50) { relay.write(videoPacket(index++)) }
        assertThrows(SlowReaderException::class.java) { reader.read(ByteArray(1_000), 0, 1_000, timeoutMs = 0) }
    }

    @Test(timeout = 10_000) fun readerCloseFromAnotherThreadWakesBlockedRead() {
        val relay = relay()
        val reader = relay.reader()
        val result = AtomicReference<Int?>()
        val blocked = Thread { result.set(reader.read(ByteArray(PACKET), 0, PACKET, timeoutMs = 30_000)) }.apply { start() }
        awaitTrue { relay.waitingReaders == 1 }
        reader.close()
        blocked.join()
        assertEquals(-1, result.get())
        assertEquals(0, relay.readers)
    }

    @Test(timeout = 10_000) fun closeEndsReaders() {
        val relay = relay()
        val buffered = relay.reader()
        val waiting = relay.reader()
        repeat(10) { relay.write(videoPacket(it)) }
        readAll(waiting)
        val result = AtomicReference<Int?>()
        val blocked = Thread { result.set(waiting.read(ByteArray(PACKET), 0, PACKET, timeoutMs = 30_000)) }
        blocked.start()
        awaitTrue { relay.waitingReaders == 1 }
        relay.close()
        blocked.join()
        assertEquals(-1, result.get())
        // What was already written is still delivered, then the stream ends.
        val buf = ByteArray(10 * PACKET)
        var got = 0
        while (got < buf.size) got += buffered.read(buf, got, buf.size - got, timeoutMs = 0)
        assertContiguousVideo(buf, 0, 0, 9)
        assertEquals(-1, buffered.read(buf, 0, buf.size, timeoutMs = 0))
        assertEquals(-1, relay.reader().read(buf, 0, buf.size, timeoutMs = 0))
        relay.write(videoPacket(10)) // ignored once closed
        assertEquals(-1, buffered.read(buf, 0, buf.size, timeoutMs = 0))
    }

    @Test fun readTimesOutWithZero() {
        val relay = relay()
        val reader = relay.reader()
        val buf = ByteArray(PACKET)
        assertEquals(0, reader.read(buf, 0, buf.size, timeoutMs = 0))
        assertEquals(0, reader.read(buf, 0, buf.size, timeoutMs = 20))
        assertEquals(0, reader.read(buf, 0, buf.size, timeoutMs = -1))
        repeat(3) { relay.write(videoPacket(it)) } // the first packet is trusted once two more confirm it
        repeat(3) { assertEquals(PACKET, reader.read(buf, 0, buf.size, timeoutMs = 20)) }
        assertEquals(0, reader.read(buf, 0, buf.size, timeoutMs = 20))
    }

    @Test(timeout = 10_000) fun pacedWriteBlocksForPrimaryReader() {
        val relay = relay(capacityBytes = 4 * TsRelay.CHUNK_BYTES, paced = true)
        val primary = relay.reader(primary = true)
        val total = 10 * PER_CHUNK
        val producer = producer(relay, total)
        // Starting chunk 4 reuses chunk 0's slot, which needs the primary at least two chunks in.
        awaitTrue { relay.isWriterWaiting }
        assertEquals(4L * TsRelay.CHUNK_BYTES, relay.written)
        val first = readExactly(primary, TsRelay.CHUNK_BYTES)
        assertEquals(4L * TsRelay.CHUNK_BYTES, relay.written)
        val rest = readToEnd(primary)
        producer.join()
        assertNull(producer.failure.get())
        assertContiguousVideo(first + rest, 0, 0, total - 1)
    }

    @Test(timeout = 10_000) fun pacedStillDropsSlowFollower() {
        val relay = relay(capacityBytes = 4 * TsRelay.CHUNK_BYTES, paced = true)
        val primary = relay.reader(primary = true)
        val follower = relay.reader()
        val total = 10 * PER_CHUNK
        val producer = producer(relay, total)
        val out = readToEnd(primary)
        producer.join()
        assertContiguousVideo(out, 0, 0, total - 1)
        assertThrows(SlowReaderException::class.java) { follower.read(ByteArray(PACKET), 0, PACKET, timeoutMs = 0) }
    }

    @Test(timeout = 10_000) fun pacedWriterWaitsForAPrimaryReaderThenStartsItAtTheBeginning() {
        val relay = relay(capacityBytes = 4 * TsRelay.CHUNK_BYTES, paced = true)
        val follower = relay.reader() // only a primary reader lets the producer start
        val total = 6 * PER_CHUNK
        val producer = producer(relay, total)
        awaitTrue { relay.isWriterWaiting }
        assertEquals(0L, relay.written)
        now = 60_000
        val out = readToEnd(relay.reader(primary = true))
        producer.join()
        assertContiguousVideo(out, 0, 0, total - 1)
        follower.close()
    }

    @Test(timeout = 10_000) fun closeReleasesBlockedPacedWriter() {
        // Blocked with no primary reader.
        val idle = relay(paced = true)
        val waitingForReader = producer(idle, 3)
        awaitTrue { idle.isWriterWaiting }
        idle.close()
        waitingForReader.join()
        assertNull(waitingForReader.failure.get())
        assertEquals(0L, idle.written)

        // Blocked behind a primary reader that stopped reading.
        val stalled = relay(capacityBytes = 2 * TsRelay.CHUNK_BYTES, paced = true)
        stalled.reader(primary = true)
        val waitingForRoom = producer(stalled, 10 * PER_CHUNK)
        awaitTrue { stalled.isWriterWaiting }
        stalled.close()
        waitingForRoom.join()
        assertNull(waitingForRoom.failure.get())
        assertEquals(2L * TsRelay.CHUNK_BYTES, stalled.written)
    }

    @Test(timeout = 10_000) fun pacedWriteIsInterruptible() {
        val relay = relay(paced = true)
        val producer = producer(relay, 3)
        awaitTrue { relay.isWriterWaiting }
        producer.interrupt()
        producer.join()
        assertTrue(producer.failure.get() is InterruptedIOException)
    }

    @Test(timeout = 10_000) fun primaryClosingHandsPacingBackToNoReader() {
        val relay = relay(paced = true)
        val primary = relay.reader(primary = true)
        repeat(3) { relay.write(videoPacket(it)) }
        assertEquals(3, readAll(primary).size / PACKET)
        primary.close()
        val producer = producer(relay, 1)
        awaitTrue { relay.isWriterWaiting }
        relay.close()
        producer.join()
    }

    @Test fun readersCountsOpenReaders() {
        val relay = relay()
        assertEquals(0, relay.readers)
        val a = relay.reader()
        relay.reader()
        assertEquals(2, relay.readers)
        a.close()
        a.close()
        assertEquals(1, relay.readers)
        assertEquals(-1, a.read(ByteArray(PACKET), 0, PACKET, timeoutMs = 0))
    }

    @Test(timeout = 10_000) fun pacedReadersJoinWhereThePrimaryIs() {
        // Joining "0 ms behind" would put a reader at the newest chunk, far ahead of the primary.
        val relay = relay(capacityBytes = 8 * TsRelay.CHUNK_BYTES, joinBehindMs = 0, paced = true)
        val primary = relay.reader(primary = true)
        val producer = producer(relay, 20 * PER_CHUNK)
        // Mid-packet on purpose: the new reader starts at that packet's beginning.
        readExactly(primary, 1_000 * PACKET + 50)
        awaitTrue { relay.isWriterWaiting }
        assertTrue(relay.written > 3_000L * PACKET)
        val follower = relay.reader()
        assertEquals(1_000L * PACKET, follower.startPosition)
        assertEquals(1_000, indexOf(readExactly(follower, PACKET), 0))
        follower.close()
        relay.close()
        producer.join()
    }

    @Test fun readerResumesAtAGivenPositionWithoutTables() {
        val relay = relay(capacityBytes = 4 * TsRelay.CHUNK_BYTES)
        relay.write(patPacket(pmtPid = 0x1000))
        repeat(2 * PER_CHUNK) { relay.write(videoPacket(it)) }
        val fresh = relay.reader()
        assertEquals(PACKET, fresh.prefixBytes)
        val resumed = relay.reader(at = 300L * PACKET + 7)
        assertEquals(0, resumed.prefixBytes)
        assertEquals(300L * PACKET + 7, resumed.startPosition)
        val bytes = readExactly(resumed, 2 * PACKET)
        assertEquals(videoPacket(299).copyOfRange(7, PACKET).toList(), bytes.copyOfRange(0, PACKET - 7).toList())
        // Past the head, or already overwritten: an ordinary join instead.
        repeat(4 * PER_CHUNK) { relay.write(videoPacket(it)) }
        val gone = relay.reader(at = 10L * PACKET)
        assertEquals(PACKET, gone.prefixBytes)
        assertTrue(gone.startPosition > 10L * PACKET)
        assertTrue(relay.reader(at = relay.written + PACKET).startPosition <= relay.written)
    }

    // Helpers

    private fun TsRelay.write(bytes: ByteArray) = write(bytes, 0, bytes.size)

    private class Producer(val relay: TsRelay, val packets: Int) : Thread() {
        val failure = AtomicReference<Throwable?>()
        override fun run() {
            try {
                for (i in 0 until packets) videoPacket(i).let { relay.write(it, 0, it.size) }
                relay.close()
            } catch (t: Throwable) {
                failure.set(t)
            }
        }
    }

    private fun producer(relay: TsRelay, packets: Int) = Producer(relay, packets).apply { start() }

    /** Spins until [condition] holds; the test's timeout bounds it. Waits on state, never on the clock. */
    private fun awaitTrue(condition: () -> Boolean) {
        while (!condition()) Thread.yield()
    }

    private fun writeInPieces(relay: TsRelay, input: ByteArray, sizes: IntArray) {
        var off = 0
        var step = 0
        while (off < input.size) {
            val len = minOf(sizes[step++ % sizes.size], input.size - off)
            relay.write(input, off, len)
            off += len
        }
    }

    private fun junk(size: Int, syncAt: IntArray) = ByteArray(size).also { j -> syncAt.forEach { j[it] = TsRelay.SYNC } }

    private fun readExactly(reader: TsReader, count: Int): ByteArray {
        val out = ByteArray(count)
        var got = 0
        while (got < count) {
            val n = reader.read(out, got, count - got, timeoutMs = 10_000)
            assertTrue("stream ended early", n >= 0)
            got += n
        }
        return out
    }

    private fun readAll(reader: TsReader): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(5_000)
        while (true) {
            val n = reader.read(buf, 0, buf.size, timeoutMs = 0)
            if (n <= 0) return out.toByteArray()
            out.write(buf, 0, n)
        }
    }

    private fun readToEnd(reader: TsReader): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(3_001)
        while (true) {
            val n = reader.read(buf, 0, buf.size, timeoutMs = 10_000)
            if (n < 0) return out.toByteArray()
            out.write(buf, 0, n)
        }
    }

    private fun assertContiguousVideo(out: ByteArray, from: Int, firstIndex: Int, lastIndex: Int) {
        assertEquals("whole packets", 0, (out.size - from) % PACKET)
        assertEquals((lastIndex - firstIndex + 1) * PACKET, out.size - from)
        var p = from
        for (i in firstIndex..lastIndex) {
            assertArrayEquals("packet $i", videoPacket(i), out.copyOfRange(p, p + PACKET))
            p += PACKET
        }
    }

    private fun indexOf(out: ByteArray, at: Int): Int {
        assertEquals("packet aligned", TsRelay.SYNC, out[at])
        return ((out[at + 4].toInt() and 0xFF) shl 24) or ((out[at + 5].toInt() and 0xFF) shl 16) or
            ((out[at + 6].toInt() and 0xFF) shl 8) or (out[at + 7].toInt() and 0xFF)
    }

    private fun pidOf(out: ByteArray, at: Int) = ((out[at + 1].toInt() and 0x1F) shl 8) or (out[at + 2].toInt() and 0xFF)

    private companion object {
        const val PACKET = 188
        const val VIDEO_PID = 0x100
        const val PACKETS_PER_TICK = 100
        const val PER_CHUNK = TsRelay.CHUNK_BYTES / PACKET

        private fun packetByte(index: Int, offset: Int): Byte = when (offset) {
            0 -> TsRelay.SYNC
            1 -> (VIDEO_PID shr 8).toByte()
            2 -> VIDEO_PID.toByte()
            3 -> 0x10
            4 -> (index ushr 24).toByte()
            5 -> (index ushr 16).toByte()
            6 -> (index ushr 8).toByte()
            7 -> index.toByte()
            else -> (index * 31 + offset).toByte()
        }

        fun videoPacket(index: Int) = ByteArray(PACKET) { packetByte(index, it) }

        /** A single-program PAT section pointing at [pmtPid]. */
        fun patPacket(pmtPid: Int): ByteArray {
            val p = ByteArray(PACKET) { 0xFF.toByte() }
            val header = intArrayOf(
                0x47, 0x40, 0x00, 0x10, // sync, PUSI + PID 0, payload only
                0x00, // pointer field
                0x00, 0xB0, 0x0D, // table id, section length 13
                0x00, 0x01, 0xC1, 0x00, 0x00, // stream id, version, section numbers
                0x00, 0x01, 0xE0 or (pmtPid shr 8), pmtPid and 0xFF, // program 1 -> PMT PID
                0x12, 0x34, 0x56, 0x78, // CRC (not checked)
            )
            header.forEachIndexed { i, b -> p[i] = b.toByte() }
            return p
        }

        fun tablePacket(pid: Int, fill: Int = 0x02): ByteArray {
            val p = ByteArray(PACKET) { fill.toByte() }
            p[0] = 0x47
            p[1] = (0x40 or (pid shr 8)).toByte()
            p[2] = pid.toByte()
            val header = intArrayOf(
                0x10, // payload only
                0x00, // pointer field
                0x02, 0xB0, 0x0D, // PMT table id, section length 13
                0x00, 0x01, 0xC1, 0x00, 0x00, // program 1, current, section numbers
                0xE1, 0x00, 0xF0, 0x00, // PCR PID, no program info
            )
            header.forEachIndexed { i, b -> p[3 + i] = b.toByte() }
            return p
        }
    }
}
