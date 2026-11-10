package io.github.loopholetv.update

import android.annotation.SuppressLint
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.io.File
import java.security.MessageDigest

/** What the update check compares for an APK: package name, version code and signer digests (SHA-256, hex). */
data class PackageFacts(val packageName: String, val versionCode: Long, val signers: Set<String>)

enum class VerifyResult { OK, UNREADABLE, WRONG_PACKAGE, NOT_NEWER, WRONG_SIGNER }

/** Whether the downloaded [apk] may replace [installed]; [apk] null means it could not be parsed. */
fun verifyUpdate(apk: PackageFacts?, installed: PackageFacts): VerifyResult = when {
    apk == null -> VerifyResult.UNREADABLE
    apk.packageName != installed.packageName -> VerifyResult.WRONG_PACKAGE
    apk.versionCode <= installed.versionCode -> VerifyResult.NOT_NEWER
    !sameSigners(apk.signers, installed.signers) -> VerifyResult.WRONG_SIGNER
    else -> VerifyResult.OK
}

/** The same non-empty set of signing certificates (by digest). */
fun sameSigners(a: Set<String>, b: Set<String>): Boolean = a.isNotEmpty() && a == b

fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/**
 * Reads [PackageFacts] through [PackageManager]: GET_SIGNING_CERTIFICATES from API 28 (current
 * signers only, not the rotation history), GET_SIGNATURES below. Some API 28 builds return no
 * signing info for an archive; GET_SIGNATURES is then tried as well.
 */
class PackageFactsReader(private val pm: PackageManager, private val packageName: String) {

    fun installed(): PackageFacts? = runCatching {
        facts(pm.getPackageInfo(packageName, signatureFlags()))
    }.getOrNull()?.takeIf { it.signers.isNotEmpty() }

    fun archive(file: File): PackageFacts? = runCatching {
        val path = file.absolutePath
        val first = pm.getPackageArchiveInfo(path, signatureFlags())?.let(::facts)
        if (first != null && first.signers.isEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.legacyArchiveInfo(path)?.let(::facts) ?: first
        } else {
            first
        }
    }.getOrNull()

    private fun facts(info: PackageInfo): PackageFacts =
        PackageFacts(info.packageName, versionCodeOf(info), signersOf(info).mapTo(HashSet()) { sha256Hex(it.toByteArray()) })

    @Suppress("DEPRECATION")
    private fun versionCodeOf(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun signersOf(info: PackageInfo): List<Signature> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners?.let { return it.toList() }
        }
        return info.signatures?.toList().orEmpty()
    }

    @Suppress("DEPRECATION")
    private fun signatureFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    // GET_SIGNATURES is deprecated but still reports the APK's signers.
    @SuppressLint("PackageManagerGetSignatures")
    @Suppress("DEPRECATION")
    private fun PackageManager.legacyArchiveInfo(path: String): PackageInfo? =
        getPackageArchiveInfo(path, PackageManager.GET_SIGNATURES)?.takeIf { !it.signatures.isNullOrEmpty() }
}
