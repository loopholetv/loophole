package io.github.loopholetv.cast.relay

import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import java.io.Closeable
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Thrown by [TsReader.read] when the producer has overwritten data the reader had not read yet. */
class SlowReaderException : IOException("Reader fell behind the relay and was dropped")

/**
 * Fans one MPEG-TS byte stream out to several readers.
 *
 * Thread-safety contract: [write] is called from one producer thread at a time. [reader], [close] and [readers] may be
 * called from any thread. Each [TsReader] is read from one thread at a time; [TsReader.close] may be called from any
 * thread and wakes a reader blocked in [TsReader.read].
 *
 * The stream is kept in a fixed ring of [CHUNK_BYTES] chunks addressed by absolute byte position: chunk `k` holds bytes
 * `[k * CHUNK_BYTES, (k + 1) * CHUNK_BYTES)` and lives in slot `k % chunkCount`. Each slot is allocated once and
 * reused, so memory is bounded by the capacity and writing allocates nothing.
 *
 * Before the producer starts overwriting a slot it advances [oldest] past the chunk that slot held; a reader copies
 * outside the lock and re-checks [oldest] afterwards, so a reader that was overtaken during (or before) its copy throws
 * [SlowReaderException] instead of returning overwritten bytes.
 *
 * Unpaced (the default), the producer never waits for readers. When [paced], the producer waits instead of
 * overtaking the primary reader (see [reader]), keeping about a chunk of headroom, and waits while no primary reader is
 * attached; other readers are still dropped when overtaken. That suits a source that arrives faster than real time.
 *
 * Only whole 188-byte packets enter the ring, so every chunk boundary is a packet boundary. A sync byte (0x47) is
 * trusted as a packet start, at the beginning of the stream or after lost sync, only once sync bytes also sit 188 and
 * 376 bytes later. The latest valid PAT and the PMTs it lists are cached and replayed to each new reader first.
 */
class TsRelay internal constructor(
    capacityBytes: Int,
    private val clock: () -> Long,
    private val joinBehindMs: Long,
    private val paced: Boolean,
    private val beforeCopy: (() -> Unit)?,
) {
    constructor(
        capacityBytes: Int = 12 * 1024 * 1024,
        clock: () -> Long = SystemClock::elapsedRealtime,
        joinBehindMs: Long = 2_000,
        paced: Boolean = false,
    ) : this(capacityBytes, clock, joinBehindMs, paced, null)

    /** Capacity rounded down to whole chunks, with a floor of two (one being written, one readable). */
    internal val chunkCount = maxOf(2, capacityBytes / CHUNK_BYTES)

    private val lock = ReentrantLock()
    private val dataWritten: Condition = lock.newCondition()
    private val roomFreed: Condition = lock.newCondition()

    // Guarded by lock. The producer also reads head and the current chunk outside the lock; only it changes them.
    private val chunks = arrayOfNulls<ByteArray>(chunkCount)
    private val chunkStartedAt = LongArray(chunkCount)
    private var head = 0L
    private var oldest = 0L
    private var openReaders = 0
    private var primary: TsReader? = null
    private var writerWaiting = false
    private var readersWaiting = 0
    @Volatile private var closed = false

    // Latest PAT and the PMTs it lists, guarded by lock.
    private val pat = ByteArray(PACKET)
    private var hasPat = false
    private val pmtPids = IntArray(MAX_PMTS)
    private var pmtCount = 0
    private val pmts = Array(MAX_PMTS) { ByteArray(PACKET) }
    private val hasPmt = BooleanArray(MAX_PMTS)

    // Producer-only state.
    private var locked = false
    private val scan = ByteArray(SCAN_BYTES)
    private var scanLength = 0
    private val partial = ByteArray(PACKET)
    private var partialLength = 0
    private val parsedPids = IntArray(MAX_PMTS)

    @VisibleForTesting internal val allocatedChunks: Int get() = lock.withLock { chunks.count { it != null } }
    @VisibleForTesting internal val written: Long get() = lock.withLock { head }
    @VisibleForTesting internal val isWriterWaiting: Boolean get() = lock.withLock { writerWaiting }
    @VisibleForTesting internal val waitingReaders: Int get() = lock.withLock { readersWaiting }

    /** Readers created and not yet closed. */
    val readers: Int get() = lock.withLock { openReaders }

    /**
     * Appends [len] bytes of [bytes] from [off]. Unpaced, this never blocks on readers. Paced, it blocks until a primary
     * reader is attached and has room, or until [close]; an interrupt while blocked throws [InterruptedIOException].
     */
    fun write(bytes: ByteArray, off: Int, len: Int) {
        checkRange(bytes, off, len)
        if (paced) lock.withLock { while (!closed && primary == null) awaitRoom() }
        val end = off + len
        var i = off
        while (i < end && !closed) {
            i = if (locked) writeLocked(bytes, i, end) else scanForSync(bytes, i, end)
        }
    }

    /**
     * A new reader positioned at the packet-aligned chunk written about [joinBehindMs] ago, or at the oldest data kept
     * if everything is newer. The cached PAT and PMTs come first. A reader created after [close] is already at the end.
     *
     * A [primary] reader is the one a paced relay waits for (the casting TV's own player). A newer primary reader takes
     * over from an older one, which carries on as an ordinary reader. Unpaced, [primary] has no effect.
     *
     * Paced, a new reader joins at the start of the packet the primary reader is in, so everyone stays with the player
     * that sets the pace instead of running ahead of it. With [at], an absolute stream position still kept, the reader
     * resumes exactly there and gets no tables (a reconnect continuing its own bytes); otherwise [at] is ignored.
     */
    fun reader(primary: Boolean = false, at: Long? = null): TsReader = lock.withLock {
        openReaders++
        val primaryAt = this.primary?.position?.takeIf { paced && it >= oldest }
        val reader = when {
            closed -> TsReader(this, head, EMPTY)
            at != null && at in oldest..head -> TsReader(this, at, EMPTY)
            primaryAt != null -> TsReader(this, primaryAt - primaryAt % PACKET, tablesSnapshot())
            else -> TsReader(this, joinPosition(clock() - joinBehindMs), tablesSnapshot())
        }
        if (primary) {
            this.primary = reader
            roomFreed.signalAll()
        }
        reader
    }

    /** Ends the stream: readers get what was already written, then -1. A blocked paced writer returns; later writes are ignored. */
    fun close() = lock.withLock {
        closed = true
        dataWritten.signalAll()
        roomFreed.signalAll()
    }

    // Producer side: sync

    /** Writes packets while in sync; returns where sync was lost (just past the offending byte) or [end]. */
    private fun writeLocked(src: ByteArray, start: Int, end: Int): Int {
        var i = start
        while (i < end && !closed) {
            if (partialLength > 0) {
                val n = minOf(PACKET - partialLength, end - i)
                System.arraycopy(src, i, partial, partialLength, n)
                partialLength += n
                i += n
                if (partialLength == PACKET) {
                    partialLength = 0
                    appendPackets(partial, 0, PACKET)
                }
                continue
            }
            if (src[i] != SYNC) {
                locked = false
                return i + 1
            }
            var j = i
            while (j + PACKET <= end && src[j] == SYNC) j += PACKET
            if (j > i) {
                appendPackets(src, i, j - i)
                i = j
            } else {
                System.arraycopy(src, i, partial, 0, end - i)
                partialLength = end - i
                i = end
            }
        }
        return end
    }

    /** Buffers input until a sync byte is confirmed 188 and 376 bytes later, then writes in sync. */
    private fun scanForSync(src: ByteArray, start: Int, end: Int): Int {
        var i = start
        while (true) {
            val n = minOf(SCAN_BYTES - scanLength, end - i)
            System.arraycopy(src, i, scan, scanLength, n)
            scanLength += n
            i += n
            dropScan(firstSync(0))
            if (scanLength < SCAN_BYTES) {
                if (i < end) continue
                return i
            }
            if (scan[PACKET] != SYNC || scan[2 * PACKET] != SYNC) {
                dropScan(firstSync(1))
                continue
            }
            locked = true
            val buffered = scanLength
            scanLength = 0
            writeLocked(scan, 0, buffered) // aligned by construction, so sync holds
            return i
        }
    }

    /** Index of the first sync byte in the scan buffer at or after [from], or its length if there is none. */
    private fun firstSync(from: Int): Int {
        for (k in from until scanLength) if (scan[k] == SYNC) return k
        return scanLength
    }

    private fun dropScan(count: Int) {
        if (count == 0) return
        System.arraycopy(scan, count, scan, 0, scanLength - count)
        scanLength -= count
    }

    // Producer side: ring

    /** [len] is a whole number of sync-aligned packets. */
    private fun appendPackets(src: ByteArray, off: Int, len: Int) {
        var p = off
        while (p < off + len) {
            if (src[p + 1].toInt() and PUSI != 0) cacheTable(src, p)
            p += PACKET
        }
        var o = off
        var remaining = len
        while (remaining > 0) {
            val h = head
            val chunkIndex = h / CHUNK_BYTES
            val slot = (chunkIndex % chunkCount).toInt()
            val inChunk = (h % CHUNK_BYTES).toInt()
            val target = if (inChunk == 0) startChunk(chunkIndex, slot) ?: return else chunks[slot]!!
            val n = minOf(remaining, CHUNK_BYTES - inChunk)
            System.arraycopy(src, o, target, inChunk, n)
            lock.withLock {
                if (closed) return
                head = h + n
                dataWritten.signalAll()
            }
            o += n
            remaining -= n
        }
    }

    /** Invalidates the chunk this slot held before any of it is overwritten (paced: once the primary is clear). */
    private fun startChunk(chunkIndex: Long, slot: Int): ByteArray? {
        val fresh = if (chunks[slot] == null) ByteArray(CHUNK_BYTES) else null
        return lock.withLock {
            if (paced && chunkIndex >= chunkCount) {
                // Keep the primary at least one chunk clear of the chunk this one replaces.
                val clearOf = (chunkIndex - chunkCount + 2) * CHUNK_BYTES
                while (!closed && (primary?.position ?: -1L) < clearOf) awaitRoom()
            }
            if (closed) return null
            if (chunkIndex >= chunkCount) oldest = maxOf(oldest, (chunkIndex - chunkCount + 1) * CHUNK_BYTES)
            chunkStartedAt[slot] = clock()
            if (fresh != null) chunks[slot] = fresh
            chunks[slot]
        }
    }

    /** Called with lock held. */
    private fun awaitRoom() {
        writerWaiting = true
        try {
            roomFreed.await()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Interrupted while waiting for the primary reader")
        } finally {
            writerWaiting = false
        }
    }

    // Producer side: PAT/PMT cache

    private fun cacheTable(src: ByteArray, p: Int) {
        if (src[p + 1].toInt() and TRANSPORT_ERROR != 0) return
        val pid = pidOf(src, p)
        if (pid == PAT_PID) {
            val count = parsePat(src, p)
            if (count < 0) return
            lock.withLock {
                System.arraycopy(src, p, pat, 0, PACKET)
                hasPat = true
                if (!samePids(count)) {
                    System.arraycopy(parsedPids, 0, pmtPids, 0, count)
                    pmtCount = count
                    hasPmt.fill(false)
                }
            }
            return
        }
        for (k in 0 until pmtCount) {
            // pmtPids only changes on this thread, so reading it outside the lock is safe.
            if (pmtPids[k] == pid) {
                if (sectionStart(src, p, PMT_TABLE_ID, MIN_PMT_SECTION) < 0) return
                lock.withLock {
                    System.arraycopy(src, p, pmts[k], 0, PACKET)
                    hasPmt[k] = true
                }
                return
            }
        }
    }

    private fun samePids(count: Int): Boolean {
        if (count != pmtCount) return false
        for (k in 0 until count) if (parsedPids[k] != pmtPids[k]) return false
        return true
    }

    /** Reads the PMT PIDs of a single-packet PAT into [parsedPids]; returns their count, or -1 if it is not usable. */
    private fun parsePat(src: ByteArray, p: Int): Int {
        val section = sectionStart(src, p, PAT_TABLE_ID, MIN_PAT_SECTION)
        if (section < 0) return -1
        val programsEnd = minOf(section + 3 + sectionLength(src, section) - CRC_BYTES, p + PACKET)
        var count = 0
        var e = section + 8
        while (e + 4 <= programsEnd && count < MAX_PMTS) {
            val program = ((src[e].toInt() and 0xFF) shl 8) or (src[e + 1].toInt() and 0xFF)
            if (program != 0) parsedPids[count++] = ((src[e + 2].toInt() and 0x1F) shl 8) or (src[e + 3].toInt() and 0xFF)
            e += 4
        }
        return count
    }

    /**
     * Start of the section that begins in the packet at [p], or -1 unless it is a current [tableId] section with the
     * syntax bit set, at least [minLength] long, and with its fixed header inside the packet.
     */
    private fun sectionStart(src: ByteArray, p: Int, tableId: Int, minLength: Int): Int {
        val end = p + PACKET
        var payload = p + 4
        val adaptation = (src[p + 3].toInt() shr 4) and 0x3
        if (adaptation and 0x1 == 0) return -1
        if (adaptation and 0x2 != 0) payload += 1 + (src[payload].toInt() and 0xFF)
        if (payload >= end) return -1
        val section = payload + 1 + (src[payload].toInt() and 0xFF)
        if (section + 8 > end) return -1
        if (src[section].toInt() and 0xFF != tableId) return -1
        if (src[section + 1].toInt() and SECTION_SYNTAX == 0) return -1
        if (src[section + 5].toInt() and CURRENT_NEXT == 0) return -1
        if (sectionLength(src, section) < minLength) return -1
        return section
    }

    private fun sectionLength(src: ByteArray, section: Int) =
        ((src[section + 1].toInt() and 0x0F) shl 8) or (src[section + 2].toInt() and 0xFF)

    // Reader side

    /** Called with lock held. */
    private fun joinPosition(target: Long): Long {
        if (head == 0L) return 0L
        val firstChunk = oldest / CHUNK_BYTES
        val lastChunk = (head - 1) / CHUNK_BYTES
        var start = firstChunk
        for (c in lastChunk downTo firstChunk) {
            if (chunkStartedAt[(c % chunkCount).toInt()] <= target) {
                start = c
                break
            }
        }
        // Once the ring has wrapped, the oldest chunk is the next one overwritten: leave a chunk of headroom.
        if (oldest > 0 && start == firstChunk && firstChunk < lastChunk) start++
        return start * CHUNK_BYTES
    }

    /** Called with lock held. */
    private fun tablesSnapshot(): ByteArray {
        if (!hasPat) return EMPTY
        val out = ByteArray(PACKET * (1 + hasPmt.count { it }))
        System.arraycopy(pat, 0, out, 0, PACKET)
        var o = PACKET
        for (k in 0 until pmtCount) {
            if (hasPmt[k]) {
                System.arraycopy(pmts[k], 0, out, o, PACKET)
                o += PACKET
            }
        }
        return out
    }

    internal fun checkNotOvertaken(position: Long) = lock.withLock {
        if (position < oldest) throw SlowReaderException()
    }

    /** Copies from the reader's position and advances it; returns the count, 0 on timeout, or -1 at the end. */
    internal fun copyFrom(reader: TsReader, buf: ByteArray, off: Int, len: Int, timeoutMs: Long): Int {
        val position = reader.position
        val chunk: ByteArray
        val inChunk: Int
        val n: Int
        lock.withLock {
            var waitNanos = TimeUnit.MILLISECONDS.toNanos(maxOf(0L, timeoutMs))
            while (true) {
                if (reader.isClosed) return -1
                if (position < oldest) throw SlowReaderException()
                if (position < head) break
                if (closed) return -1
                if (waitNanos <= 0L) return 0
                waitNanos = awaitData(waitNanos)
            }
            chunk = chunks[((position / CHUNK_BYTES) % chunkCount).toInt()]!!
            inChunk = (position % CHUNK_BYTES).toInt()
            n = minOf(len.toLong(), head - position, (CHUNK_BYTES - inChunk).toLong()).toInt()
        }
        beforeCopy?.invoke()
        System.arraycopy(chunk, inChunk, buf, off, n)
        lock.withLock {
            // oldest only moves in whole chunks and is advanced before a slot is overwritten, so if it has not passed
            // position now, nothing was overwritten while copying.
            if (position < oldest) throw SlowReaderException()
            reader.position = position + n
            if (reader === primary) roomFreed.signalAll()
        }
        return n
    }

    /** Called with lock held. */
    private fun awaitData(nanos: Long): Long {
        readersWaiting++
        try {
            return dataWritten.awaitNanos(nanos)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Interrupted while waiting for stream data")
        } finally {
            readersWaiting--
        }
    }

    internal fun release(reader: TsReader) = lock.withLock {
        openReaders--
        if (primary === reader) primary = null
        dataWritten.signalAll()
    }

    companion object {
        private const val PACKET = 188
        const val SYNC: Byte = 0x47
        /** 348 TS packets, just under 64 KiB. */
        const val CHUNK_BYTES = PACKET * 348

        /** A candidate sync byte plus the two packets that confirm it. */
        private const val SCAN_BYTES = 2 * PACKET + 1
        private const val PAT_PID = 0
        private const val TRANSPORT_ERROR = 0x80
        private const val PUSI = 0x40
        private const val SECTION_SYNTAX = 0x80
        private const val CURRENT_NEXT = 0x01
        private const val PAT_TABLE_ID = 0x00
        private const val PMT_TABLE_ID = 0x02
        private const val CRC_BYTES = 4
        private const val MIN_PAT_SECTION = 5 + CRC_BYTES
        private const val MIN_PMT_SECTION = 9 + CRC_BYTES
        private const val MAX_PMTS = 8
        private val EMPTY = ByteArray(0)

        private fun pidOf(src: ByteArray, p: Int) = ((src[p + 1].toInt() and 0x1F) shl 8) or (src[p + 2].toInt() and 0xFF)

        internal fun checkRange(bytes: ByteArray, off: Int, len: Int) {
            if (off < 0 || len < 0 || off > bytes.size - len) {
                throw IndexOutOfBoundsException("off=$off len=$len size=${bytes.size}")
            }
        }
    }
}

/** One reader's view of a [TsRelay]. Read from one thread at a time; [close] may be called from any thread. */
class TsReader internal constructor(
    private val relay: TsRelay,
    start: Long,
    private val tables: ByteArray,
) : Closeable {
    /** Absolute stream position of the next ring byte; changed by the relay under its lock. */
    internal var position = start
    private var tablesRead = 0

    /** The absolute stream position this reader's ring bytes start from (after its [prefixBytes]). */
    val startPosition: Long = start

    /** How many bytes of cached PAT/PMT come before the ring bytes. */
    val prefixBytes: Int get() = tables.size
    @Volatile internal var isClosed = false
        private set

    /**
     * Reads up to [len] bytes into [buf]. Returns the count, -1 at the end of the stream (or once closed), or 0 if no
     * data arrived within [timeoutMs]; a [timeoutMs] of 0 or less returns at once instead of waiting. Throws
     * [SlowReaderException] once the producer has overtaken this reader.
     */
    fun read(buf: ByteArray, off: Int, len: Int, timeoutMs: Long = 8_000): Int {
        TsRelay.checkRange(buf, off, len)
        if (isClosed) return -1
        if (len == 0) return 0
        if (tablesRead < tables.size) {
            relay.checkNotOvertaken(position)
            val n = minOf(len, tables.size - tablesRead)
            System.arraycopy(tables, tablesRead, buf, off, n)
            tablesRead += n
            return n
        }
        return relay.copyFrom(this, buf, off, len, timeoutMs)
    }

    override fun close() {
        synchronized(this) {
            if (isClosed) return
            isClosed = true
        }
        relay.release(this)
    }
}
