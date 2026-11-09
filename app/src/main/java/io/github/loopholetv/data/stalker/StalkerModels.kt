package io.github.loopholetv.data.stalker

import io.github.loopholetv.data.db.PlaylistEntity

/**
 * One MAC portal account. The identity fields ([serial], [deviceId], [deviceId2], [signature]) are
 * sent only when the user typed them; they are never generated. Every value
 * except [portalUrl]'s path is a secret for `redact()`.
 */
data class PortalConfig(
    val portalUrl: String,
    val mac: String,
    val login: String? = null,
    val password: String? = null,
    val serial: String? = null,
    val deviceId: String? = null,
    val deviceId2: String? = null,
    val signature: String? = null,
) {
    /** Never includes a secret. */
    override fun toString() = "PortalConfig(***)"
}

/** Blank stored values count as "not typed". */
fun PlaylistEntity.portalConfig(): PortalConfig = PortalConfig(
    portalUrl = url.orEmpty().trim(),
    mac = mac.orEmpty().trim(),
    login = portalLogin.blankToNull(),
    password = portalPassword.blankToNull(),
    serial = portalSerial.blankToNull(),
    deviceId = portalDeviceId.blankToNull(),
    deviceId2 = portalDeviceId2.blankToNull(),
    signature = portalSignature.blankToNull(),
)

private val MAC_REGEX = Regex("""^[0-9A-Fa-f]{2}([:-]?)[0-9A-Fa-f]{2}(?:\1[0-9A-Fa-f]{2}){4}$""")

/** `00:1a:79:ab:cd:ef`, `00-1A-79-AB-CD-EF` or `001A79ABCDEF` → `00:1A:79:AB:CD:EF`; anything else → null. */
fun normaliseMac(input: String): String? {
    val s = input.trim()
    if (!MAC_REGEX.matches(s)) return null
    return s.filter { it != ':' && it != '-' }.uppercase().chunked(2).joinToString(":")
}

/** The stock server accepts only the Infomir OUI; other MACs are a warning, not an error. */
fun isInfomirMac(mac: String): Boolean = (normaliseMac(mac) ?: mac.uppercase()).startsWith("00:1A:79")

/**
 * A live portal session. [token] and [random] change in place when a call re-handshakes after an
 * auth-failure body; [StalkerClient] never persists them, [StalkerSessions] does.
 */
class StalkerSession(
    val endpoint: String,
    token: String,
    random: String?,
    watchdogSeconds: Int,
) {
    @Volatile var token: String = token
        internal set
    @Volatile var random: String? = random
        internal set
    @Volatile var watchdogSeconds: Int = watchdogSeconds
        internal set
    /**
     * Bumped by every re-handshake of this session. A caller that saw generation N re-handshakes only
     * if it is still N, so waiters don't repeat it when the portal hands back the same token.
     */
    @Volatile var generation: Int = 0
        internal set

    /** Never includes the token. */
    override fun toString() = "StalkerSession(watchdogSeconds=$watchdogSeconds)"
}

/**
 * One `itv` row. [id] is the stable key; [cmd] is data, kept raw (with its solution
 * prefix) and resolved at play time — through `create_link` when [needsLink].
 */
data class StalkerChannel(
    val id: String,
    val name: String,
    val number: Int?,
    val cmd: String,
    val genre: String?,
    val logo: String?,
    val xmltvId: String?,
    val needsLink: Boolean,
)

/** What [StalkerClient.epg] needs to know about a channel id to name its guide channel. */
data class StalkerChannelMeta(val name: String?, val xmltvId: String?)

/**
 * Refused account, blocked device, wrong login, `cut_off`, or a session that still fails after one
 * re-handshake. [retryable] is true only for the last case: a fresh session may still help.
 */
class StalkerAuthException(message: String, val retryable: Boolean = false) : Exception(message)

/** Transport failure, with its [kind]. Fixed text. */
class StalkerUnreachableException(message: String, val kind: Kind = Kind.BROKEN) : Exception(message) {
    enum class Kind {
        /** Connection refused, unknown host, no route: every path on this host fails the same way. */
        REFUSED,
        /** Connect or read timeout. */
        TIMEOUT,
        /** Reset, closed mid-body or another transport failure. */
        BROKEN,
    }
}

/** Not a portal, wrong endpoint, unexpected HTTP status or JSON shape. */
class StalkerProtocolException(message: String) : Exception(message)

/** `create_link` gave no playable URL. [code]: `limit`, `nothing_to_play`, `link_fault` or `empty`. */
class StalkerLinkException(val code: String) : Exception("The portal gave no link for this channel ($code)")

internal fun String?.blankToNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

// The only accessors the client uses for typed values: blank or whitespace is "not typed", so the
// parameter is omitted. An empty device id would lock the device out for good.
internal val PortalConfig.typedLogin: String? get() = login.blankToNull()
internal val PortalConfig.typedPassword: String? get() = password?.takeIf { it.isNotEmpty() }
internal val PortalConfig.typedSerial: String? get() = serial.blankToNull()
internal val PortalConfig.typedDeviceId: String? get() = deviceId.blankToNull()
internal val PortalConfig.typedDeviceId2: String? get() = deviceId2.blankToNull()
internal val PortalConfig.typedSignature: String? get() = signature.blankToNull()
