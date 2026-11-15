package io.github.loopholetv.phone

import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import java.io.IOException
import java.net.InetAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.Buffer

/** A phone that has been paired. [hash] is the SHA-256 of its token; the token itself is never kept. */
data class PairedPhone(val hash: String, val name: String, val lastUsedMs: Long) {
    override fun toString() = "PairedPhone(name=$name, lastUsedMs=$lastUsedMs)"
}

interface PairingStore {
    suspend fun pairedPhones(): List<PairedPhone>
    val pairedCount: Flow<Int>
    suspend fun savePairedPhones(list: List<PairedPhone>)
    suspend fun phonePort(): Int?
    suspend fun savePhonePort(port: Int)
}

sealed interface PairResult {
    data class Paired(val token: String) : PairResult {
        override fun toString() = "Paired"
    }

    data object WrongCode : PairResult
    data object NotShowing : PairResult
    data object TooMany : PairResult
}

/**
 * Pairs phones with the TV through a 4-digit code that exists only while the pairing screen is
 * open. A paired phone gets a long random token; only its hash is stored. Safe to call from
 * several server threads.
 */
class PhonePairing(
    private val store: PairingStore,
    private val clock: () -> Long,
    private val random: SecureRandom = SecureRandom(),
) {
    private val state = Any()
    private val storeLock = Mutex()
    private val attempts = HashMap<String, ArrayDeque<Long>>()
    private var wrongCodes = 0

    private val _code = MutableStateFlow<String?>(null)
    val code: StateFlow<String?> = _code.asStateFlow()

    private val _connected = MutableSharedFlow<Unit>(extraBufferCapacity = 64)

    /** Emits once for each successful pairing. */
    val connected: SharedFlow<Unit> = _connected.asSharedFlow()

    fun open() = synchronized(state) {
        wrongCodes = 0
        _code.value = newCode()
    }

    fun close() = synchronized(state) {
        wrongCodes = 0
        _code.value = null
    }

    suspend fun pair(code: String, remote: InetAddress, userAgent: String?): PairResult {
        val early = synchronized(state) {
            val now = clock()
            attempts.values.forEach { q -> q.removeAll { now - it >= RATE_WINDOW_MS } }
            attempts.values.removeAll { it.isEmpty() }
            val recent = attempts.getOrPut(remote.hostAddress.orEmpty()) { ArrayDeque() }
            when {
                recent.size >= MAX_ATTEMPTS -> PairResult.TooMany
                else -> {
                    recent.addLast(now)
                    checkCode(code)
                }
            }
        }
        if (early != null) return early

        val token = newToken()
        val phone = PairedPhone(hashToken(token), phoneName(userAgent), clock())
        storeLock.withLock {
            val kept = store.pairedPhones().filterNot { it.hash == phone.hash } + phone
            store.savePairedPhones(kept.sortedByDescending { it.lastUsedMs }.take(MAX_PHONES))
        }
        _connected.tryEmit(Unit)
        return PairResult.Paired(token)
    }

    /** Null when the code is right; otherwise the refusal. Called with [state] held. */
    private fun checkCode(given: String): PairResult? {
        val current = _code.value ?: return PairResult.NotShowing
        val same = MessageDigest.isEqual(
            given.toByteArray(Charsets.UTF_8),
            current.toByteArray(Charsets.UTF_8),
        )
        if (same) {
            wrongCodes = 0
            return null
        }
        if (++wrongCodes >= MAX_WRONG_CODES) {
            wrongCodes = 0
            _code.value = newCode()
        }
        return PairResult.WrongCode
    }

    /** Whether [token] belongs to a paired phone. Notes the use at most once a minute. */
    suspend fun isPaired(token: String?): Boolean {
        if (token.isNullOrEmpty()) return false
        val hash = hashToken(token).toByteArray(Charsets.UTF_8)
        return storeLock.withLock {
            val phones = store.pairedPhones()
            val now = clock()
            var found = false
            val updated = phones.map { p ->
                if (MessageDigest.isEqual(hash, p.hash.toByteArray(Charsets.UTF_8))) {
                    found = true
                    if (now - p.lastUsedMs >= TOUCH_INTERVAL_MS) p.copy(lastUsedMs = now) else p
                } else {
                    p
                }
            }
            if (found && updated != phones) store.savePairedPhones(updated)
            found
        }
    }

    suspend fun forget(token: String) {
        val hash = hashToken(token)
        storeLock.withLock {
            val phones = store.pairedPhones()
            val kept = phones.filterNot { MessageDigest.isEqual(hash.toByteArray(), it.hash.toByteArray()) }
            if (kept.size != phones.size) store.savePairedPhones(kept)
        }
    }

    suspend fun forgetAll() {
        storeLock.withLock { store.savePairedPhones(emptyList()) }
    }

    private fun newCode(): String = random.nextInt(10_000).toString().padStart(4, '0')

    private fun newToken(): String {
        val bytes = ByteArray(TOKEN_BYTES).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun hashToken(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAX_WRONG_CODES = 3
        const val MAX_ATTEMPTS = 10
        const val RATE_WINDOW_MS = 60_000L
        const val TOUCH_INTERVAL_MS = 60_000L
        const val MAX_PHONES = 20
        const val TOKEN_BYTES = 32
    }
}

/** A short label for a phone's browser, such as "Chrome on Android". "Browser" when nothing is known. */
fun phoneName(userAgent: String?): String {
    val ua = userAgent.orEmpty()
    val browser = when {
        "Edg/" in ua || "EdgA/" in ua || "EdgiOS/" in ua -> "Edge"
        "OPR/" in ua -> "Opera"
        "SamsungBrowser" in ua -> "Samsung Internet"
        "Firefox/" in ua || "FxiOS/" in ua -> "Firefox"
        "Chrome/" in ua || "CriOS/" in ua -> "Chrome"
        "Safari/" in ua -> "Safari"
        else -> null
    } ?: return "Browser"
    val os = when {
        "iPhone" in ua -> "iPhone"
        "iPad" in ua -> "iPad"
        "Android" in ua -> "Android"
        "Windows" in ua -> "Windows"
        "Mac OS X" in ua || "Macintosh" in ua -> "Mac"
        "CrOS" in ua -> "ChromeOS"
        "Linux" in ua -> "Linux"
        else -> null
    }
    return if (os == null) browser else "$browser on $os"
}

internal fun encodePairings(list: List<PairedPhone>): String {
    val buffer = Buffer()
    JsonWriter.of(buffer).use { w ->
        w.beginArray()
        for (p in list) {
            w.beginObject()
            w.name("hash").value(p.hash)
            w.name("name").value(p.name)
            w.name("lastUsedMs").value(p.lastUsedMs)
            w.endObject()
        }
        w.endArray()
    }
    return buffer.readUtf8()
}

/** Unreadable or missing JSON gives an empty list: the phones just have to pair again. */
internal fun decodePairings(json: String?): List<PairedPhone> {
    if (json.isNullOrBlank()) return emptyList()
    return try {
        val out = ArrayList<PairedPhone>()
        JsonReader.of(Buffer().writeUtf8(json)).use { r ->
            r.beginArray()
            while (r.hasNext()) {
                var hash: String? = null
                var name = ""
                var last = 0L
                r.beginObject()
                while (r.hasNext()) {
                    when (r.nextName()) {
                        "hash" -> hash = r.nextString()
                        "name" -> name = r.nextString()
                        "lastUsedMs" -> last = r.nextLong()
                        else -> r.skipValue()
                    }
                }
                r.endObject()
                if (hash != null) out += PairedPhone(hash, name, last)
            }
            r.endArray()
        }
        out
    } catch (_: IOException) {
        emptyList()
    } catch (_: RuntimeException) {
        emptyList()
    }
}
