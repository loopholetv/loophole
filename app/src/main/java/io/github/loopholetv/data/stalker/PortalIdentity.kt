package io.github.loopholetv.data.stalker

import java.security.MessageDigest

/** Device details a portal expects alongside the MAC. Every value is a secret: never logged. */
data class PortalIdentity(
    val serial: String,
    val deviceId: String,
    val deviceId2: String,
    val signature: String,
) {
    override fun toString() = "PortalIdentity(***)"
}

/** The details a set-top box would present for [mac] (any common MAC format), or null for an invalid MAC. */
fun deriveIdentity(mac: String): PortalIdentity? {
    val m = normaliseMac(mac) ?: return null
    val serial = hexUpper("MD5", m).take(SERIAL_LENGTH)
    val deviceId = hexUpper("SHA-256", m)
    return PortalIdentity(
        serial = serial,
        deviceId = deviceId,
        deviceId2 = deviceId,
        signature = hexUpper("SHA-256", serial + m),
    )
}

/** Fills only the blank identity fields from the MAC; typed values are kept. */
fun PortalConfig.withDerivedIdentity(): PortalConfig {
    val id = deriveIdentity(mac) ?: return this
    return copy(
        serial = typedSerial ?: id.serial,
        deviceId = typedDeviceId ?: id.deviceId,
        deviceId2 = typedDeviceId2 ?: id.deviceId2,
        signature = signature.blankToNull() ?: id.signature,
    )
}

private const val SERIAL_LENGTH = 13

private fun hexUpper(algorithm: String, input: String): String =
    MessageDigest.getInstance(algorithm).digest(input.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02X".format(it) }
