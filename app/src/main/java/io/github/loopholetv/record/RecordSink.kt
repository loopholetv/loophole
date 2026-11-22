package io.github.loopholetv.record

import com.emc.ecs.nfsclient.nfs.NfsException
import com.emc.ecs.nfsclient.nfs.NfsStatus
import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMBApiException
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.io.OutputStream

/** The storage has no room left (local disk or a share). Not an [IOException], so a read loop never swallows it. */
class StorageFullException(cause: Throwable? = null) : RuntimeException("Storage is full", cause)

/** A write to the storage failed for a reason other than space; it may come back (a network blip). */
class StorageWriteException(cause: Throwable? = null) : RuntimeException("Couldn't write to the storage", cause)

/** The storage stayed unreachable for the whole retry period. */
internal class StorageUnreachableException : RuntimeException("The storage stopped responding")

private const val MAX_CAUSE_DEPTH = 8

internal fun isNoSpace(e: Throwable): Boolean = generateSequence(e) { it.cause }.take(MAX_CAUSE_DEPTH).any {
    val m = it.message.orEmpty()
    m.contains("ENOSPC") || m.contains("No space", ignoreCase = true) ||
        (it is SMBApiException && it.status == NtStatus.STATUS_DISK_FULL) ||
        (it is NfsException && (it.status == NfsStatus.NFS3ERR_NOSPC || it.status == NfsStatus.NFS3ERR_DQUOT))
}

/** One open recording file. Failures are reported as [StorageFullException] or [StorageWriteException]. */
class RecordSink(private val out: OutputStream, private val onBytes: (Long) -> Unit) {
    fun write(buf: ByteArray, off: Int, len: Int) {
        try {
            out.write(buf, off, len)
        } catch (e: IOException) {
            throw map(e)
        } catch (e: RuntimeException) {
            throw mapUnexpected(e)
        }
        onBytes(len.toLong())
    }

    fun close() {
        try {
            out.close()
        } catch (e: IOException) {
            throw map(e)
        } catch (e: RuntimeException) {
            throw mapUnexpected(e)
        }
    }

    private fun map(e: Throwable): RuntimeException =
        if (isNoSpace(e)) StorageFullException(e) else StorageWriteException(e)

    /** A storage library's own runtime errors (SMB status codes, a revoked folder permission) are write failures. */
    private fun mapUnexpected(e: RuntimeException): RuntimeException = when (e) {
        is StorageFullException, is StorageWriteException, is CancellationException -> e
        else -> map(e)
    }
}

/**
 * Writes the recording through a [RecordSink] and rides out a storage outage: after a failed write the sink is
 * re-opened every [REOPEN_MS]; meanwhile what arrives is read and discarded (one gap per outage), and after
 * [OUTAGE_MS] the outage becomes final. A full storage is final at once.
 */
internal class RecordWriter(
    private val openSink: suspend () -> RecordSink,
    private val clock: () -> Long,
) {
    var bytes = 0L
        private set
    var gaps = 0
        private set

    private var sink: RecordSink? = null
    private var outageStart = NONE
    private var lastTry = NONE
    private var gapCounted = false
    private var realign = false
    private var carry = ByteArray(0)

    /** Set while a raw MPEG-TS stream is being copied: after a discontinuity the file resumes on a packet boundary. */
    var tsMode = false

    /** Input was lost or skipped here (a reconnect): the next bytes written must start a fresh packet. */
    fun markDiscontinuity() {
        realign = true
        carry = ByteArray(0)
    }

    suspend fun write(buf: ByteArray, off: Int, len: Int) {
        val s = ensureSink()
        if (s == null) {
            discard()
            return
        }
        var data = buf
        var from = off
        var count = len
        if (realign) {
            if (tsMode) {
                val joined = carry + buf.copyOfRange(off, off + len)
                val k = syncOffset(joined)
                if (k < 0) {
                    carry = joined.copyOfRange(maxOf(0, joined.size - TS_PACKET), joined.size)
                    return
                }
                data = joined
                from = k
                count = joined.size - k
            }
            realign = false
            carry = ByteArray(0)
        }
        try {
            s.write(data, from, count)
            bytes += count
            outageStart = NONE
            gapCounted = false
        } catch (e: StorageWriteException) {
            runCatching { s.close() }
            sink = null
            lastTry = clock()
            // A re-opened sink that fails again is the same outage, so the 30 s keep counting.
            if (outageStart == NONE) outageStart = lastTry
            discard()
        }
    }

    /** Closes the file. A failure to close surfaces like a failed write. */
    fun close() {
        val s = sink ?: return
        sink = null
        s.close()
    }

    /** The first offset holding a sync byte that repeats one packet later, or -1. */
    private fun syncOffset(d: ByteArray): Int {
        for (k in 0 until d.size - TS_PACKET) if (d[k] == TS_SYNC && d[k + TS_PACKET] == TS_SYNC) return k
        return -1
    }

    private fun discard() {
        markDiscontinuity()
        if (!gapCounted) {
            gapCounted = true
            gaps++
        }
    }

    private suspend fun ensureSink(): RecordSink? {
        sink?.let { return it }
        val now = clock()
        if (outageStart != NONE && now - lastTry < REOPEN_MS) {
            giveUpIfLate(now)
            return null
        }
        lastTry = now
        try {
            return openSink().also { sink = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: StorageFullException) {
            throw e
        } catch (e: Exception) {
            if (outageStart == NONE) outageStart = now
            giveUpIfLate(now)
            return null
        }
    }

    private fun giveUpIfLate(now: Long) {
        if (now - outageStart >= OUTAGE_MS) throw StorageUnreachableException()
    }

    private companion object {
        const val TS_PACKET = 188
        const val TS_SYNC: Byte = 0x47
        const val NONE = Long.MIN_VALUE
        const val REOPEN_MS = 5_000L
        const val OUTAGE_MS = 30_000L
    }
}
