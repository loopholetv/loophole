package io.github.loopholetv.record.storage

import android.content.Context
import android.os.StatFs
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Recordings in a folder on the TV's own storage or a mounted volume. */
class LocalDirStorage(
    private val dir: File,
    override val label: String,
    override val id: String,
    private val freeSpace: (File) -> Long?,
) : RecordingStorage {
    private val folder get() = File(dir, RECORDINGS_FOLDER)

    override suspend fun test(): StorageResult = withContext(Dispatchers.IO) {
        var probe: File? = null
        try {
            probe = ensureFolder().resolve(PROBE_NAME)
            probe.writeText("loophole")
            if (probe.readText() == "loophole") StorageResult.Ok else StorageResult.Failed("Could not read back what was written")
        } catch (e: Exception) {
            StorageResult.Failed(e.message ?: "Could not write to this storage")
        } finally {
            probe?.delete()
        }
    }

    override suspend fun freeBytes(): Long? = withContext(Dispatchers.IO) { freeSpace(dir) }

    override suspend fun openAppend(name: String): OutputStream = withContext(Dispatchers.IO) {
        FileOutputStream(ensureFolder().resolve(name), true)
    }

    override suspend fun size(name: String): Long? = withContext(Dispatchers.IO) {
        folder.resolve(name).takeIf { it.isFile }?.length()
    }

    override suspend fun openRead(name: String, offset: Long): InputStream = withContext(Dispatchers.IO) {
        val raf = RandomAccessFile(folder.resolve(name), "r")
        raf.seek(offset)
        java.nio.channels.Channels.newInputStream(raf.channel)
    }

    override suspend fun delete(name: String): Boolean = withContext(Dispatchers.IO) {
        val f = folder.resolve(name)
        f.isFile && f.delete()
    }

    private fun ensureFolder(): File {
        val f = folder
        if (!f.isDirectory && !f.mkdirs() && !f.isDirectory) throw java.io.IOException("Cannot create $RECORDINGS_FOLDER")
        return f
    }

    companion object {
        fun internal(context: Context): LocalDirStorage? {
            val dir = context.getExternalFilesDir(null) ?: return null
            return LocalDirStorage(dir, "Internal storage", "internal") { dir -> runCatching { StatFs(dir.path).availableBytes }.getOrNull() }
        }
    }
}
