package io.github.loopholetv.data.repo

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import io.github.loopholetv.data.m3u.M3uParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.BufferedSource
import okio.buffer
import okio.source
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.util.UUID

private const val COULD_NOT_OPEN = "Couldn't open the file."
private const val COULD_NOT_READ = "Couldn't read the file."
private const val TEMP_PREFIX = ".tmp-"
private const val ORPHAN_AGE_MS = 10L * 60 * 1000

/**
 * Opens local M3U files: a `content://` document (persisted SAF grant), a `file://` URI or an
 * absolute path. Locations are never passed through [withHttpScheme].
 *
 * Every read of the file itself is teed into [copiesDir]; after the import commits, the caller
 * keeps it as the playlist's last-good copy ([LocalM3uSource.keepCopyAs]). When the file can't be
 * opened later (USB stick removed, grant revoked, file moved), that copy is read instead.
 *
 * Error messages never contain the location (paths may hold tokens).
 */
internal class LocalM3uOpener(
    private val resolver: ContentResolver,
    private val copiesDir: File,
    private val clock: () -> Instant = Instant::now,
    /** Opens a copy temp for writing (a test seam for a full disk). */
    private val copyOutput: (File) -> OutputStream = { FileOutputStream(it) },
) {
    /**
     * Opens [location] on IO, or the last-good copy of [playlistId] if the file can't be opened,
     * and sniffs it like a URL import. Throws [ImportFailure]: Unreachable ("Couldn't open the
     * file.") when neither opens, NotAPlaylist for markup.
     */
    suspend fun open(location: String, playlistId: Long?): LocalM3uSource = withContext(Dispatchers.IO) {
        val direct = try {
            openStream(location)
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        } catch (e: IllegalArgumentException) {
            null // some DocumentsProviders, for a stale document id (volume remounted)
        } catch (e: CancellationException) {
            throw e // an IllegalStateException too: cancellation is not an unreadable file
        } catch (e: IllegalStateException) {
            null
        }
        val stream: InputStream
        val usedCopyFrom: Instant?
        if (direct != null) {
            stream = direct
            usedCopyFrom = null
        } else {
            val copy = playlistId?.let(::copyFile)?.takeIf { it.isFile }
                ?: throw ImportFailure(ImportError.Unreachable, COULD_NOT_OPEN)
            usedCopyFrom = Instant.ofEpochMilli(copy.lastModified())
            stream = try {
                copy.inputStream()
            } catch (e: IOException) {
                throw ImportFailure(ImportError.Unreachable, COULD_NOT_OPEN)
            }
        }
        val source = stream.source().buffer()
        try {
            val sniff = try {
                sniff(source)
            } catch (e: IOException) {
                throw ImportFailure(ImportError.Unreachable, COULD_NOT_READ)
            }
            if (sniff.looksLikeMarkup) {
                throw ImportFailure(ImportError.NotAPlaylist, "The file is a web page, not an M3U playlist")
            }
            LocalM3uSource(source, sniff.looksLikePlaylist, usedCopyFrom, copies = this@LocalM3uOpener.takeIf { usedCopyFrom == null })
        } catch (e: Throwable) {
            source.close()
            throw e
        }
    }

    /** Removes the last-good copy of [playlistId] (playlist deleted). File IO: call off the main thread. */
    fun deleteCopy(playlistId: Long) {
        copyFile(playlistId).delete()
    }

    /** Gives back the persisted read grant of a `content://` [location]; other locations: no-op. */
    fun release(location: String) {
        if (!location.trim().startsWith("content://", ignoreCase = true)) return
        runCatching { resolver.releasePersistableUriPermission(Uri.parse(location.trim()), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    /**
     * A fresh temp file for a tee'd read and its output stream, or null when it can't be created
     * (the import then goes ahead without updating the copy). Also removes temps orphaned by a
     * process that died mid-import.
     */
    internal fun newTemp(): Pair<File, OutputStream>? {
        copiesDir.mkdirs()
        val staleBefore = clock().toEpochMilli() - ORPHAN_AGE_MS
        copiesDir.listFiles().orEmpty()
            .filter { it.name.startsWith(TEMP_PREFIX) && it.lastModified() < staleBefore }
            .forEach { it.delete() }
        val file = File(copiesDir, TEMP_PREFIX + UUID.randomUUID())
        return try {
            file to copyOutput(file)
        } catch (e: IOException) {
            file.delete()
            null
        }
    }

    /** Replaces [playlistId]'s copy with [temp] by a rename within [copiesDir] (atomic on Linux). */
    internal fun keep(temp: File, playlistId: Long) {
        temp.setLastModified(clock().toEpochMilli())
        if (!temp.renameTo(copyFile(playlistId))) temp.delete()
    }

    /** Where [playlistId]'s last-good copy lives (a setup copied from another TV is read from it too). */
    internal fun copyFile(playlistId: Long) = File(copiesDir, "$playlistId.m3u")

    /**
     * The file's raw bytes as a stream, else [playlistId]'s last-good copy; null when neither
     * opens. No sniffing and no copy tee. Call on IO.
     */
    internal fun openRaw(location: String, playlistId: Long): InputStream? {
        fun <T> orNull(open: () -> T): T? = try {
            open()
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: IllegalStateException) {
            null
        }
        return orNull { openStream(location) } ?: orNull { copyFile(playlistId).takeIf { it.isFile }?.inputStream() }
    }

    /** Throws [FileNotFoundException] / [SecurityException] / [IOException] when it can't be opened. */
    private fun openStream(location: String): InputStream {
        val t = location.trim()
        return when {
            t.startsWith("content://", ignoreCase = true) ->
                resolver.openInputStream(Uri.parse(t)) ?: throw FileNotFoundException()
            t.startsWith("file:", ignoreCase = true) ->
                File(Uri.parse(t).path ?: throw FileNotFoundException()).inputStream()
            File(t).isAbsolute -> File(t).inputStream()
            else -> throw FileNotFoundException()
        }
    }
}

/**
 * One opened local file whose channels can be streamed once.
 *
 * [usedCopyFrom]: the last-good copy's modified time when the file itself couldn't be opened and
 * the copy is read instead; null when the file was read.
 */
internal class LocalM3uSource(
    private val source: BufferedSource,
    private val sniffedAsPlaylist: Boolean,
    val usedCopyFrom: Instant?,
    /** Receives a tee of the bytes read; null when the copy itself is being read. */
    private val copies: LocalM3uOpener?,
) : ChannelSource {
    @Volatile private var rawEntries = 0
    @Volatile private var temp: File? = null
    @Volatile private var complete = false

    override suspend fun stream(emit: (ImportedChannel) -> Unit): String = withContext(Dispatchers.IO) {
        val (tempFile, out) = copies?.newTemp() ?: (null to null)
        temp = tempFile
        // A failing copy (full disk) only stops the copying; the import goes on from the file.
        val copy = out?.let(::BestEffortOutput)
        val header = try {
            parse(if (copy != null) TeeInputStream(source.inputStream(), copy) else source.inputStream(), emit)
        } catch (e: IOException) {
            tempFile?.delete()
            // Never the exception message: it may name the path.
            throw ImportFailure(ImportError.Unreachable, COULD_NOT_READ)
        } finally {
            copy?.close() // a failing close (unflushed bytes) also marks the copy broken
        }
        if (copy?.broken == true) {
            tempFile?.delete()
            temp = null
        }
        complete = true
        header.epgUrls.joinToString("\n")
    }

    private fun parse(input: InputStream, emit: (ImportedChannel) -> Unit) =
        M3uParser.parse(input) { entry ->
            rawEntries++
            entry.toImported()?.let(emit)
        }

    override fun noChannelsError(): ImportError =
        if (rawEntries > 0 || sniffedAsPlaylist) ImportError.EmptyPlaylist else ImportError.NotAPlaylist

    /**
     * Keeps what [stream] read as [playlistId]'s last-good copy. Call after the import transaction
     * committed. No-op when the copy itself was read or the read didn't finish.
     */
    fun keepCopyAs(playlistId: Long) {
        val t = temp ?: return
        val store = copies ?: return
        if (!complete) return
        temp = null
        store.keep(t, playlistId)
    }

    /** Closes the file and deletes an unkept temp copy. */
    override fun close() {
        runCatching { source.close() }
        temp?.delete()
        temp = null
    }
}

/** Swallows write failures: after the first one it stops writing and sets [broken]. */
private class BestEffortOutput(private val out: OutputStream) : OutputStream() {
    @Volatile var broken = false
        private set

    private inline fun attempt(block: () -> Unit) {
        if (broken) return
        try { block() } catch (e: IOException) { broken = true }
    }

    override fun write(b: Int) = attempt { out.write(b) }
    override fun write(b: ByteArray, off: Int, len: Int) = attempt { out.write(b, off, len) }
    override fun flush() = attempt { out.flush() }
    override fun close() {
        try { out.close() } catch (e: IOException) { broken = true }
    }
}

/** Copies every byte read from [input] to [copy]. */
private class TeeInputStream(input: InputStream, private val copy: OutputStream) : FilterInputStream(input) {
    override fun read(): Int {
        val b = super.read()
        if (b >= 0) copy.write(b)
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) copy.write(b, off, n)
        return n
    }

    /** Skipped bytes must still reach the copy, so skipping reads. */
    override fun skip(n: Long): Long {
        if (n <= 0) return 0
        val buf = ByteArray(minOf(n, 8192L).toInt())
        val r = read(buf, 0, buf.size)
        return if (r < 0) 0 else r.toLong()
    }

    override fun markSupported() = false
}

/** The document's display name for a `content://` [location], else its last path segment. Queries the provider: call off the main thread. */
fun localFileDisplayName(resolver: ContentResolver, location: String): String {
    val t = location.trim()
    if (t.startsWith("content://", ignoreCase = true)) {
        val uri = Uri.parse(t)
        val name = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
            }
        }.getOrNull()
        return name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/').orEmpty()
    }
    val path = if (t.startsWith("file:", ignoreCase = true)) Uri.parse(t).path.orEmpty() else t
    return path.trimEnd('/').substringAfterLast('/')
}
