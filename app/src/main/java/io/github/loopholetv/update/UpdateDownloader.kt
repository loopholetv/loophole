package io.github.loopholetv.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * Downloads release APKs into [dir] (the app's cache `updates/`), one file at a time: any other
 * file there is deleted first. Writes to a `.part` file and renames it once the size matches.
 */
class UpdateDownloader(
    private val http: OkHttpClient,
    private val dir: File,
    private val userAgent: String,
) {
    fun fileFor(r: Release): File = File(dir, "loophole-${r.version.display}.apk")

    /** The finished file for [r], if it is there with the expected size. */
    fun existing(r: Release): File? = fileFor(r).takeIf { it.isFile && it.length() == r.size }

    /** Deletes everything in [dir]. */
    fun clear() {
        dir.listFiles()?.forEach { it.deleteRecursively() }
    }

    /**
     * Downloads [r], reporting whole percents to [onProgress] as they change. Throws on any
     * failure (IOException) and on cancellation, leaving no partial file behind.
     */
    suspend fun download(r: Release, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        clear()
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("No update directory")
        val target = fileFor(r)
        val part = File(dir, target.name + ".part")
        try {
            val request = Request.Builder().url(r.apkUrl).header("User-Agent", userAgent).build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val total = r.size
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
            }
            if (!part.renameTo(target)) throw IOException("Rename failed")
            target
        } catch (e: Throwable) {
            part.delete()
            target.delete()
            throw e
        }
    }
}
