package io.github.loopholetv.record.storage

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Folder, inside the chosen location, that holds every recording. */
const val RECORDINGS_FOLDER = "Loophole Recordings"

/** Name of the file written, read back and removed by [RecordingStorage.test]. */
const val PROBE_NAME = ".loophole-test"

private const val MAX_NAME_LENGTH = 180
private val NAME_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HHmm")
private const val SEPARATOR = " - "
private val UNSAFE_CHARS = Regex("""[\\/:*?"<>|]""")

sealed interface StorageResult {
    data object Ok : StorageResult
    data class Failed(val message: String) : StorageResult
}

interface RecordingStorage {
    /** Stable and secret-free, e.g. "smb://host/share/folder", "usb:<hash>", "internal". */
    val id: String
    val label: String

    /** Writes, reads back and deletes [PROBE_NAME]. */
    suspend fun test(): StorageResult
    suspend fun freeBytes(): Long?

    /** Creates the file if absent. */
    suspend fun openAppend(name: String): OutputStream

    /** Null when the file is missing. */
    suspend fun size(name: String): Long?
    suspend fun openRead(name: String, offset: Long): InputStream
    suspend fun delete(name: String): Boolean
}

/** How long a free-space read may take before the free space counts as unknown. */
const val FREE_SPACE_TIMEOUT_MS = 3_000L

/**
 * [read] bounded to [timeoutMs]: null when it fails or takes longer. It runs detached from the caller, so a
 * blocking network call that ignores cancellation is left to finish on its own instead of holding the caller.
 */
suspend fun freeBytesWithin(timeoutMs: Long = FREE_SPACE_TIMEOUT_MS, read: suspend () -> Long?): Long? {
    val result = CompletableDeferred<Long?>()
    val job = CoroutineScope(currentCoroutineContext().minusKey(Job) + SupervisorJob()).launch {
        result.complete(runCatching { read() }.getOrNull())
    }
    return withTimeoutOrNull(timeoutMs) { result.await() }.also { if (job.isActive) job.cancel() }
}

/** A storage that is not available yet; every operation fails and [test] says why. */
class UnavailableStorage(
    override val id: String,
    override val label: String,
    private val reason: String = "Not available",
) : RecordingStorage {
    override suspend fun test(): StorageResult = StorageResult.Failed(reason)
    override suspend fun freeBytes(): Long? = null
    override suspend fun openAppend(name: String): OutputStream = throw java.io.IOException(reason)
    override suspend fun size(name: String): Long? = null
    override suspend fun openRead(name: String, offset: Long): InputStream = throw java.io.IOException(reason)
    override suspend fun delete(name: String): Boolean = false
}

/**
 * "<Channel> - <Title> - <yyyy-MM-dd HHmm>.<ext>". Characters not allowed in file names become `_` and the
 * whole name, extension included, is at most 180 characters: the title is shortened first. The caller adds
 * " (2)", " (3)" when [RecordingStorage.size] says the name is taken.
 */
fun recordingFileName(channel: String, title: String, start: Instant, zone: ZoneId, ext: String): String {
    val tail = " - ${NAME_TIME.format(start.atZone(zone))}.$ext"
    val budget = (MAX_NAME_LENGTH - tail.length - SEPARATOR.length).coerceAtLeast(2)
    var cleanChannel = clean(channel)
    var cleanTitle = clean(title)
    if (cleanChannel.length + SEPARATOR.length + cleanTitle.length > budget + SEPARATOR.length) {
        val titleRoom = (budget - cleanChannel.length).coerceAtLeast(0)
        if (titleRoom > 0) {
            cleanTitle = cleanTitle.take(titleRoom).trimEnd()
        } else {
            cleanTitle = ""
            cleanChannel = cleanChannel.take(budget).trimEnd()
        }
    }
    return if (cleanTitle.isEmpty()) cleanChannel + tail else cleanChannel + SEPARATOR + cleanTitle + tail
}

private fun clean(s: String) = s.replace(UNSAFE_CHARS, "_").trim()
