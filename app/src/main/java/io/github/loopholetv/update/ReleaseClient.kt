package io.github.loopholetv.update

import com.squareup.moshi.JsonReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The newest stable release: its version and its one APK asset. [sha256] is the asset digest
 * (lowercase hex) when GitHub reports one.
 */
data class Release(val version: SemVer, val apkUrl: String, val size: Long, val sha256: String? = null)

const val GITHUB_LATEST_RELEASE_URL = "https://api.github.com/repos/loopholetv/loophole/releases/latest"

/** Largest APK accepted (from the release JSON and from the download's Content-Length). */
const val MAX_APK_BYTES = 200L * 1024 * 1024

/** Largest release JSON read (release notes included); GitHub's are a few KB. */
private const val MAX_BODY_BYTES = 1L shl 20

/**
 * Reads the latest release from the GitHub REST API (`/releases/latest` already leaves out drafts
 * and pre-releases). [url] is read per call so a debug build can point it elsewhere. [http] should
 * come from [updateHttpClient] with the same [policy], which also checks every redirect hop.
 */
class ReleaseClient(
    private val http: OkHttpClient,
    private val url: () -> String,
    private val userAgent: String,
    private val policy: UpdateUrlPolicy = UpdateUrlPolicy.GITHUB_ONLY,
) {
    /** Null on any network, HTTP or parse failure, and when the release has no usable APK asset. */
    suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        try {
            val endpoint = url()
            if (!policy.allows(endpoint)) return@withContext null
            val request = Request.Builder()
                .url(endpoint)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", userAgent)
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body.source()
                // Buffers the whole body (up to the cap) before parsing.
                if (body.request(MAX_BODY_BYTES + 1)) null else parseRelease(JsonReader.of(body), policy)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
}

/** Reads `tag_name` and the first asset named `*.apk` (its download URL, size and digest). */
internal fun parseRelease(reader: JsonReader, policy: UpdateUrlPolicy): Release? {
    if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) return null
    var tag: String? = null
    var apk: Asset? = null
    reader.beginObject()
    while (reader.hasNext()) {
        when (reader.nextName()) {
            "tag_name" -> tag = reader.stringOrNull()
            "assets" -> apk = readApkAsset(reader, policy)
            else -> reader.skipValue()
        }
    }
    reader.endObject()
    val version = tag?.let(SemVer::parse) ?: return null
    val asset = apk ?: return null
    return Release(version, asset.url, asset.size, asset.sha256)
}

private class Asset(val url: String, val size: Long, val sha256: String?)

private val SHA256_DIGEST = Regex("^sha256:([0-9a-fA-F]{64})$")

private fun readApkAsset(reader: JsonReader, policy: UpdateUrlPolicy): Asset? {
    if (reader.peek() != JsonReader.Token.BEGIN_ARRAY) {
        reader.skipValue()
        return null
    }
    var found: Asset? = null
    reader.beginArray()
    while (reader.hasNext()) {
        if (found != null || reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
            reader.skipValue()
            continue
        }
        var name: String? = null
        var url: String? = null
        var size: Long? = null
        var digest: String? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "name" -> name = reader.stringOrNull()
                "browser_download_url" -> url = reader.stringOrNull()
                "size" -> size = if (reader.peek() == JsonReader.Token.NUMBER) reader.nextLong() else { reader.skipValue(); null }
                "digest" -> digest = reader.stringOrNull()
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        if (name != null && name.endsWith(".apk", ignoreCase = true)) {
            // The first APK decides: a bad one is not replaced by a later asset.
            found = if (url != null && policy.allows(url) && size != null && size in 1..MAX_APK_BYTES) {
                Asset(url, size, digest?.trim()?.let { SHA256_DIGEST.matchEntire(it) }?.groupValues?.get(1)?.lowercase())
            } else {
                null
            }
            if (found == null) return skipRest(reader)
        }
    }
    reader.endArray()
    return found
}

private fun skipRest(reader: JsonReader): Asset? {
    while (reader.hasNext()) reader.skipValue()
    reader.endArray()
    return null
}

private fun JsonReader.stringOrNull(): String? =
    if (peek() == JsonReader.Token.STRING) nextString() else { skipValue(); null }
