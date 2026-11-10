package io.github.loopholetv.update

import com.squareup.moshi.JsonReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** The newest stable release: its version and its one APK asset. */
data class Release(val version: SemVer, val apkUrl: String, val size: Long)

const val GITHUB_LATEST_RELEASE_URL = "https://api.github.com/repos/loopholetv/loophole/releases/latest"

/** Largest release JSON read (release notes included); GitHub's are a few KB. */
private const val MAX_BODY_BYTES = 1L shl 20

/**
 * Reads the latest release from the GitHub REST API (`/releases/latest` already leaves out drafts
 * and pre-releases). [url] is read per call so a debug build can point it elsewhere.
 */
class ReleaseClient(
    private val http: OkHttpClient,
    private val url: () -> String,
    private val userAgent: String,
) {
    /** Null on any network, HTTP or parse failure, and when the release has no usable APK asset. */
    suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url())
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", userAgent)
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body.source()
                // Buffers the whole body (up to the cap) before parsing.
                if (body.request(MAX_BODY_BYTES + 1)) null else parseRelease(JsonReader.of(body))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
}

/** Reads `tag_name` and the first asset named `*.apk` (its download URL and size). */
internal fun parseRelease(reader: JsonReader): Release? {
    if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) return null
    var tag: String? = null
    var apk: Pair<String, Long>? = null
    reader.beginObject()
    while (reader.hasNext()) {
        when (reader.nextName()) {
            "tag_name" -> tag = reader.stringOrNull()
            "assets" -> apk = readApkAsset(reader)
            else -> reader.skipValue()
        }
    }
    reader.endObject()
    val version = tag?.let(SemVer::parse) ?: return null
    val (url, size) = apk ?: return null
    return Release(version, url, size)
}

private fun readApkAsset(reader: JsonReader): Pair<String, Long>? {
    if (reader.peek() != JsonReader.Token.BEGIN_ARRAY) {
        reader.skipValue()
        return null
    }
    var found: Pair<String, Long>? = null
    reader.beginArray()
    while (reader.hasNext()) {
        if (found != null || reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
            reader.skipValue()
            continue
        }
        var name: String? = null
        var url: String? = null
        var size: Long? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "name" -> name = reader.stringOrNull()
                "browser_download_url" -> url = reader.stringOrNull()
                "size" -> size = if (reader.peek() == JsonReader.Token.NUMBER) reader.nextLong() else { reader.skipValue(); null }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        if (name != null && name.endsWith(".apk", ignoreCase = true)) {
            // The first APK decides: a bad one is not replaced by a later asset.
            found = if (url != null && isHttpUrl(url) && size != null && size > 0) url to size else null
            if (found == null) return skipRest(reader)
        }
    }
    reader.endArray()
    return found
}

private fun skipRest(reader: JsonReader): Pair<String, Long>? {
    while (reader.hasNext()) reader.skipValue()
    reader.endArray()
    return null
}

private fun isHttpUrl(url: String): Boolean =
    url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)

private fun JsonReader.stringOrNull(): String? =
    if (peek() == JsonReader.Token.STRING) nextString() else { skipValue(); null }
