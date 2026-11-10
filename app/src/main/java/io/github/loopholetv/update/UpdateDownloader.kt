package io.github.loopholetv.update

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** Free space kept on top of the APK and the installer's own copy of it. */
const val SPACE_MARGIN_BYTES = 50L * 1024 * 1024

/** The device has too little free space for the download plus the installer's copy. */
class NotEnoughSpaceException : IOException("Not enough free space")

/** Where release APKs are downloaded; the [Updater] works against this. */
interface UpdateFiles {
    /** The finished file for [r], if it is there with the expected size. */
    fun existing(r: Release): File?

    /** Deletes every downloaded or partial file. */
    fun clear()

    /**
     * Downloads [r], reporting whole percents to [onProgress]. Throws on any failure (IOException,
     * [NotEnoughSpaceException]) and on cancellation, leaving no partial file behind.
     */
    suspend fun download(r: Release, onProgress: (Int) -> Unit): File
}

/**
 * Downloads release APKs into [dir] (the app's cache `updates/`), one release at a time. Each
 * download writes its own uniquely named `.part` file and renames only that one once the size (and
 * the digest, when the release has one) matches. [http] should come from [updateHttpClient].
 */
class UpdateDownloader(
    private val http: OkHttpClient,
    private val dir: File,
    private val userAgent: String,
    private val policy: UpdateUrlPolicy = UpdateUrlPolicy.GITHUB_ONLY,
    /** Bytes that can still be written in a directory (see [freeBytesFor]). */
    private val freeSpace: (File) -> Long,
) : UpdateFiles {
    fun fileFor(r: Release): File = File(dir, "loophole-${r.version.display}.apk")

    override fun existing(r: Release): File? = fileFor(r).takeIf { it.isFile && it.length() == r.size }

    override fun clear() {
        dir.listFiles()?.forEach { it.deleteRecursively() }
    }

    override suspend fun download(r: Release, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        if (!policy.allows(r.apkUrl) || r.size !in 1..MAX_APK_BYTES) throw BlockedUpdateUrlException()
        clear()
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("No update directory")
        // The installer copies the APK again into its session.
        if (freeSpace(dir) < 2 * r.size + SPACE_MARGIN_BYTES) throw NotEnoughSpaceException()
        val target = fileFor(r)
        val part = File.createTempFile("loophole-", ".part", dir)
        try {
            fetch(r, part, onProgress)
            if (!part.renameTo(target)) throw IOException("Rename failed")
            target
        } catch (e: Throwable) {
            part.delete()
            // A read aborted by Call.cancel() surfaces as an IOException: report the cancellation.
            coroutineContext.ensureActive()
            throw e
        }
    }

    /** Streams [r] into [part]; cancelling the coroutine cancels the HTTP call at once. */
    private suspend fun fetch(r: Release, part: File, onProgress: (Int) -> Unit) = coroutineScope {
        val request = Request.Builder().url(r.apkUrl).header("User-Agent", userAgent).build()
        val call = http.newCall(request)
        // A blocked read only notices cancellation through Call.cancel(), from another thread.
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            withContext(Dispatchers.IO) {
                call.execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val length = response.body.contentLength()
                    if (length != -1L && length != r.size) throw IOException("Unexpected size")
                    val total = r.size
                    val digest = MessageDigest.getInstance("SHA-256")
                    var done = 0L
                    var lastPercent = -1
                    onProgress(0)
                    response.body.byteStream().use { input ->
                        part.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                coroutineContext.ensureActive()
                                val n = input.read(buf)
                                if (n < 0) break
                                done += n
                                if (done > total) throw IOException("Larger than expected")
                                out.write(buf, 0, n)
                                digest.update(buf, 0, n)
                                val percent = (done * 100 / total).toInt()
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    onProgress(percent)
                                }
                            }
                            out.fd.sync()
                        }
                    }
                    if (done != total) throw IOException("Incomplete download")
                    val expected = r.sha256
                    if (expected != null && hex(digest.digest()) != expected) throw IOException("Digest mismatch")
                }
            }
        } finally {
            watcher.cancel()
        }
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
