package io.github.loopholetv.record.storage

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Recordings in a folder the user picked through the system file picker (a USB drive). */
class SafTreeStorage(private val context: Context, private val treeUri: Uri) : RecordingStorage {
    override val id: String = "usb:" + Integer.toHexString(treeUri.toString().hashCode())
    override val label: String = "USB drive"

    private val resolver get() = context.contentResolver

    override suspend fun test(): StorageResult = withContext(Dispatchers.IO) {
        var probe: DocumentFile? = null
        try {
            val folder = ensureFolder()
            folder.findFile(PROBE_NAME)?.delete()
            probe = folder.createFile("application/octet-stream", PROBE_NAME)
                ?: return@withContext StorageResult.Failed("Could not create a file on the drive")
            resolver.openOutputStream(probe.uri, "wa")?.use { it.write("loophole".toByteArray()) }
                ?: return@withContext StorageResult.Failed("Could not write to the drive")
            val back = resolver.openInputStream(probe.uri)?.use { it.readBytes().decodeToString() }
            if (back == "loophole") StorageResult.Ok else StorageResult.Failed("Could not read back what was written")
        } catch (e: Exception) {
            StorageResult.Failed(e.message ?: "Could not use the drive")
        } finally {
            runCatching { probe?.delete() }
        }
    }

    override suspend fun freeBytes(): Long? = withContext(Dispatchers.IO) {
        runCatching {
            val volume = DocumentsContract.getTreeDocumentId(treeUri).substringBefore(':')
            val path = if (volume == "primary") "/storage/emulated/0" else "/storage/$volume"
            val file = File(path)
            if (file.exists()) StatFs(path).availableBytes else null
        }.getOrNull()
    }

    override suspend fun openAppend(name: String): OutputStream = withContext(Dispatchers.IO) {
        val folder = ensureFolder()
        val file = folder.findFile(name) ?: folder.createFile(mimeTypeFor(name), name)
            ?: throw IOException("Could not create $name")
        resolver.openOutputStream(file.uri, "wa") ?: throw IOException("Could not open $name")
    }

    override suspend fun size(name: String): Long? = withContext(Dispatchers.IO) {
        findExisting(name)?.takeIf { it.isFile }?.length()
    }

    override suspend fun openRead(name: String, offset: Long): InputStream = withContext(Dispatchers.IO) {
        val file = findExisting(name) ?: throw IOException("$name is missing")
        val input = resolver.openInputStream(file.uri) ?: throw IOException("Could not open $name")
        var left = offset
        while (left > 0) {
            val skipped = input.skip(left)
            if (skipped <= 0) break
            left -= skipped
        }
        input
    }

    override suspend fun delete(name: String): Boolean = withContext(Dispatchers.IO) {
        findExisting(name)?.delete() ?: false
    }

    private fun root(): DocumentFile = DocumentFile.fromTreeUri(context, treeUri)
        ?: throw IOException("The drive is not available")

    private fun findExisting(name: String): DocumentFile? = root().findFile(RECORDINGS_FOLDER)?.findFile(name)

    private fun ensureFolder(): DocumentFile {
        val root = root()
        return root.findFile(RECORDINGS_FOLDER)?.takeIf { it.isDirectory }
            ?: root.createDirectory(RECORDINGS_FOLDER)
            ?: throw IOException("Cannot create $RECORDINGS_FOLDER")
    }
}

private fun mimeTypeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "ts" -> "video/mp2t"
    "mp4" -> "video/mp4"
    else -> "application/octet-stream"
}
