package io.github.loopholetv.data.parental

import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString

/** PBKDF2-HMAC-SHA256 of a PIN with a random salt. Only the salt and the hash are ever stored. */
object PinHasher {
    private const val ITERATIONS = 20_000
    private const val KEY_BITS = 256

    /** The length of a salt, and of a hash (the derived key), in bytes. */
    const val SALT_BYTES = 16
    const val HASH_BYTES = KEY_BITS / 8
    private val random = SecureRandom()

    /** A fresh 16-byte salt, Base64. */
    fun newSalt(): String = encode(ByteArray(SALT_BYTES).also(random::nextBytes))

    /** The 32-byte derived key of [pin] under [salt] (as made by [newSalt]), Base64. */
    fun hash(pin: String, salt: String): String =
        encode(derive(pin, requireNotNull(decode(salt)) { "Salt is not Base64" }))

    fun matches(pin: String, salt: String, hash: String): Boolean {
        val expected = hash.decodeBase64()?.toByteArray() ?: return false
        val saltBytes = decode(salt) ?: return false
        return MessageDigest.isEqual(derive(pin, saltBytes), expected)
    }

    internal fun derive(pin: String, salt: ByteArray): ByteArray = try {
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_BITS)).encoded
    } catch (_: NoSuchAlgorithmException) {
        // Android 7.x has no PBKDF2WithHmacSHA256; this gives the same key from HmacSHA256.
        pbkdf2(pin.toByteArray(Charsets.UTF_8), salt, ITERATIONS)
    }

    /** PBKDF2 for a key of one HMAC block (32 bytes), which is all [KEY_BITS] needs. */
    internal fun pbkdf2(password: ByteArray, salt: ByteArray, iterations: Int = ITERATIONS): ByteArray {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(password, "HmacSHA256")) }
        var u = mac.doFinal(salt + byteArrayOf(0, 0, 0, 1))
        val out = u.copyOf()
        repeat(iterations - 1) {
            u = mac.doFinal(u)
            for (i in out.indices) out[i] = (out[i].toInt() xor u[i].toInt()).toByte()
        }
        return out
    }

    private fun encode(bytes: ByteArray) = bytes.toByteString().base64()

    private fun decode(text: String) = text.decodeBase64()?.toByteArray()
}
