package io.github.loopholetv.data.repo

import io.github.loopholetv.data.m3u.M3uEntry
import io.github.loopholetv.data.m3u.M3uParser
import io.github.loopholetv.data.xtream.XtreamClient
import io.github.loopholetv.data.xtream.XtreamCredentials
import io.github.loopholetv.data.xtream.XtreamLiveStream
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.BufferedSource
import java.io.Closeable

/** An opened source (HTTP status / login already checked) whose channels can be streamed once. */
internal interface ChannelSource : Closeable {
    /** Streams channels to [emit] (called on an IO thread). Returns the source's EPG URLs, newline-joined. */
    suspend fun stream(emit: (ImportedChannel) -> Unit): String

    /** The error to report when [stream] produced no usable channel. */
    fun noChannelsError(): ImportError

    override fun close() {}
}

// --- M3U ---

private const val SNIFF_BYTES = 64L * 1024

internal class M3uSourceOpener(private val http: OkHttpClient) {
    /** [userAgent] is sent as `User-Agent` (playlist > general > Loophole/<version>). */
    suspend fun open(url: String, userAgent: String? = null): ChannelSource {
        val httpUrl = withHttpScheme(url).toHttpUrlOrNull()
            ?: throw ImportFailure(ImportError.Unreachable, "That address isn't a valid http(s) link.")
        val request = Request.Builder().url(httpUrl)
            .apply { if (!userAgent.isNullOrBlank()) header("User-Agent", userAgent) }
            .build()
        val call = http.newCall(request)
        val response = blockingCall(call) { call.execute() }
        try {
            when (val code = response.code) {
                401, 403 -> throw ImportFailure(ImportError.AuthFailed, "Server refused access (HTTP $code)")
                !in 200..299 -> throw ImportFailure(ImportError.Http(code), "Server returned HTTP $code")
            }
            val sniff = blockingCall(call) { sniff(response.body.source()) }
            if (sniff.looksLikeMarkup) {
                throw ImportFailure(ImportError.NotAPlaylist, "The address returned a web page, not an M3U playlist")
            }
            return M3uSource(call, response, sniff.looksLikePlaylist)
        } catch (e: Throwable) {
            response.close()
            throw e
        }
    }
}

/**
 * [looksLikePlaylist]: blank body, an `#EXTM3U` first line, or any `#EXTINF` / URL line, so an
 * import without usable channels is reported as EmptyPlaylist rather than NotAPlaylist.
 */
internal class Sniff(val looksLikeMarkup: Boolean, val looksLikePlaylist: Boolean)

/** Looks at the first [SNIFF_BYTES] without consuming them. Shared by the URL and local-file sources. */
internal fun sniff(source: BufferedSource): Sniff {
    val peek = source.peek()
    peek.request(SNIFF_BYTES)
    val lines = peek.buffer.readUtf8(minOf(peek.buffer.size, SNIFF_BYTES))
        .trimStart('\uFEFF').lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    val first = lines.firstOrNull()
    return Sniff(
        looksLikeMarkup = first?.startsWith('<') == true,
        looksLikePlaylist = first == null || first.startsWith("#EXTM3U", ignoreCase = true) || lines.any {
            it.startsWith("#EXTINF", ignoreCase = true) || (!it.startsWith('#') && it.contains("://"))
        },
    )
}

private class M3uSource(
    private val call: Call,
    private val response: Response,
    private val sniffedAsPlaylist: Boolean,
) : ChannelSource {
    @Volatile private var rawEntries = 0

    override suspend fun stream(emit: (ImportedChannel) -> Unit): String {
        val header = blockingCall(call) {
            M3uParser.parse(response.body.byteStream()) { entry ->
                rawEntries++
                entry.toImported()?.let(emit)
            }
        }
        return header.epgUrls.joinToString("\n")
    }

    override fun noChannelsError(): ImportError =
        if (rawEntries > 0 || sniffedAsPlaylist) ImportError.EmptyPlaylist else ImportError.NotAPlaylist

    override fun close() = response.close()
}

internal fun M3uEntry.toImported(): ImportedChannel? {
    val displayName = name.blankToNull() ?: tvgName.blankToNull() ?: return null
    return ImportedChannel(
        key = url,
        name = displayName,
        streamUrl = url,
        preferredNumber = chno,
        logo = logo.blankToNull(),
        group = group.blankToNull(),
        tvgId = tvgId.blankToNull(),
        tvgName = tvgName.blankToNull(),
        userAgent = userAgent.blankToNull(),
        referrer = referrer.blankToNull(),
        catchupType = catchup.blankToNull(),
        catchupDays = catchupDays,
        catchupSource = catchupSource.blankToNull(),
    )
}

/**
 * Runs blocking [block] on IO. If the coroutine is cancelled meanwhile, [call] is cancelled so the
 * blocked socket read returns; on normal completion the call is left alone (its body may still be read).
 */
internal suspend fun <T> blockingCall(call: Call, block: () -> T): T = coroutineScope {
    val scope = this
    val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() } finally { if (!scope.isActive) call.cancel() }
    }
    try {
        withContext(Dispatchers.IO) { block() }
    } finally {
        watcher.cancel()
    }
}

// --- Xtream ---

internal class XtreamSourceOpener(private val client: XtreamClient) {
    /** Logs in and loads categories (both outside the import transaction). */
    suspend fun open(creds: XtreamCredentials, userAgent: String? = null): ChannelSource {
        client.authenticate(creds, userAgent)
        val categories = client.liveCategories(creds, userAgent).associate { it.id to it.name }
        return object : ChannelSource {
            override suspend fun stream(emit: (ImportedChannel) -> Unit): String {
                client.liveStreams(creds, userAgent) { s -> s.toImported(creds, categories)?.let(emit) }
                return client.epgUrl(creds)
            }

            override fun noChannelsError(): ImportError = ImportError.EmptyPlaylist
        }
    }

    private fun XtreamLiveStream.toImported(creds: XtreamCredentials, categories: Map<String, String>): ImportedChannel? {
        val displayName = name.blankToNull() ?: return null
        return ImportedChannel(
            key = streamId.toString(),
            name = displayName,
            streamUrl = client.liveUrl(creds, streamId),
            preferredNumber = num,
            logo = icon.blankToNull(),
            group = categoryId?.let { categories[it] }.blankToNull(),
            tvgId = epgChannelId.blankToNull(),
            tvgName = null,
            userAgent = null,
            referrer = null,
            catchupType = if (tvArchive) "xc" else null,
            catchupDays = tvArchiveDays,
            catchupSource = null,
        )
    }
}

// --- redaction secrets ---

private const val MIN_LITERAL_SECRET = 3

internal fun xtreamSecrets(c: XtreamCredentials) = listOf(c.username, c.password)

/** A local file location (path or `content://` URI) and its decoded form: paths may hold tokens or names. */
internal fun localSecrets(location: String): List<String> {
    val t = location.trim()
    val decoded = runCatching { java.net.URLDecoder.decode(t, "UTF-8") }.getOrNull()
    return listOfNotNull(t, decoded).filter { it.length >= MIN_LITERAL_SECRET }.distinct()
}

/**
 * Literal secrets of a URL (M3U playlist or EPG): userinfo, query values and token-like path
 * segments. Very short values are left to the structural redaction so that they don't mangle the
 * rest of the message.
 */
internal fun m3uSecrets(url: String): List<String> {
    val u = withHttpScheme(url).toHttpUrlOrNull() ?: return emptyList()
    val parts = buildList {
        add(u.username)
        add(u.password)
        for (i in 0 until u.querySize) u.queryParameterValue(i)?.let(::add)
        u.pathSegments.filter { s -> s.length >= 6 && s.any { it.isDigit() } }.forEach(::add)
    }
    return parts.filter { it.length >= MIN_LITERAL_SECRET }.distinct()
}
