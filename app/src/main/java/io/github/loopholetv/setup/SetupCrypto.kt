package io.github.loopholetv.setup

import androidx.annotation.WorkerThread
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Seals a byte array with a short numeric code.
 *
 * Layout of a sealed blob: salt (16) | nonce (12) | AES-GCM ciphertext + tag (16).
 * The key is PBKDF2-HMAC-SHA256 of the code. PBKDF2 is computed here on top of
 * [Mac] rather than through the platform's SecretKeyFactory, because the SHA-256
 * variant of that factory is missing before API 26 and every device must derive
 * the identical key.
 *
 * Key stretching makes [seal] and [open] slow by design (hundreds of ms on a TV);
 * call them from a background dispatcher.
 */
object SetupCrypto {
    private const val CODE_LENGTH = 4
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val KEY_BYTES = 32
    private const val ITERATIONS = 20_000
    private const val HMAC = "HmacSHA256"
    private const val HMAC_BYTES = 32

    /** Additional authenticated data that ties a sealed blob to one request. */
    fun aadFor(requestId: String): ByteArray = "loophole-setup-v1|$requestId".toByteArray(Charsets.UTF_8)

    private fun validCode(code: String) = code.length == CODE_LENGTH && code.all { it in '0'..'9' }

    fun newCode(random: SecureRandom = SecureRandom()): String =
        // nextInt(bound) rejects out-of-range draws, so every code is equally likely.
        random.nextInt(10_000).toString().padStart(CODE_LENGTH, '0')

    /**
     * Seals [plain] with a 4-digit [code]. [aad] is authenticated but not stored, so the
     * blob only opens with the same [aad] (see [aadFor]). Slow: call from a background dispatcher.
     * @throws IllegalArgumentException if [code] is not exactly four digits.
     */
    @WorkerThread
    fun seal(
        plain: ByteArray,
        code: String,
        aad: ByteArray = ByteArray(0),
        random: SecureRandom = SecureRandom(),
    ): ByteArray {
        require(validCode(code)) { "The setup code must be exactly four digits." }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val key = deriveKey(code, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad)
        val body = cipher.doFinal(plain)
        key.fill(0)
        return ByteBuffer.allocate(SALT_BYTES + NONCE_BYTES + body.size)
            .put(salt).put(nonce).put(body).array()
    }

    /**
     * Returns null for a wrong code or [aad], a damaged blob, a malformed code or anything
     * too short to be a sealed blob. Slow: call from a background dispatcher.
     */
    @WorkerThread
    fun open(sealed: ByteArray, code: String, aad: ByteArray = ByteArray(0)): ByteArray? {
        if (!validCode(code)) return null
        if (sealed.size < SALT_BYTES + NONCE_BYTES + TAG_BITS / 8) return null
        val salt = sealed.copyOfRange(0, SALT_BYTES)
        val nonce = sealed.copyOfRange(SALT_BYTES, SALT_BYTES + NONCE_BYTES)
        val key = deriveKey(code, salt)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(aad)
            cipher.doFinal(sealed, SALT_BYTES + NONCE_BYTES, sealed.size - SALT_BYTES - NONCE_BYTES)
        } catch (_: GeneralSecurityException) {
            null
        } finally {
            key.fill(0)
        }
    }

    private fun deriveKey(code: String, salt: ByteArray): ByteArray =
        pbkdf2Sha256(code.toByteArray(Charsets.UTF_8), salt, ITERATIONS, KEY_BYTES)

    /** PBKDF2 with HMAC-SHA256 (RFC 8018). */
    internal fun pbkdf2Sha256(password: ByteArray, salt: ByteArray, iterations: Int, length: Int): ByteArray {
        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(password, HMAC))
        val blocks = (length + HMAC_BYTES - 1) / HMAC_BYTES
        val out = ByteArray(blocks * HMAC_BYTES)
        for (block in 1..blocks) {
            mac.update(salt)
            mac.update(ByteBuffer.allocate(4).putInt(block).array())
            var u = mac.doFinal()
            val t = u.copyOf()
            for (i in 1 until iterations) {
                u = mac.doFinal(u)
                for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
            }
            System.arraycopy(t, 0, out, (block - 1) * HMAC_BYTES, HMAC_BYTES)
            t.fill(0)
            u.fill(0)
        }
        val result = out.copyOf(length)
        out.fill(0)
        return result
    }
}
