package io.github.loopholetv.record.storage

import com.emc.ecs.nfsclient.nfs.io.Nfs3File
import com.emc.ecs.nfsclient.nfs.io.NfsFileInputStream
import com.emc.ecs.nfsclient.nfs.io.NfsFileOutputStream
import com.emc.ecs.nfsclient.nfs.nfs3.Nfs3
import com.emc.ecs.nfsclient.rpc.CredentialUnix
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val NFS_RETRIES = 3
private const val NFS_BUFFER_BYTES = 256 * 1024

/**
 * Recordings in a folder on an NFSv3 export. Signs in with AUTH_SYS as uid 0, gid 0 (what a NAS maps to its
 * own account unless the export squashes it). Free space comes from FSSTAT.
 */
class NfsStorage(
    private val host: String,
    export: String,
    folder: String,
) : RecordingStorage {
    private val exportPath = "/" + export.split('/').filter { it.isNotBlank() }.joinToString("/")
    private val folderParts = folder.split('/', '\\').filter { it.isNotBlank() }
    private val directory = "/" + (folderParts + RECORDINGS_FOLDER).joinToString("/")

    override val id: String = "nfs://$host$exportPath" + folderParts.joinToString("") { "/$it" }
    override val label: String = "Network folder on $host"

    override fun toString(): String = "NfsStorage($id)"

    private fun pathOf(name: String) = "$directory/$name"

    private fun mount(): Nfs3 = Nfs3(host, exportPath, CredentialUnix(0, 0, null), NFS_RETRIES)

    private inline fun <T> io(block: (Nfs3) -> T): T = mapStorageErrors(StorageKind.NFS, host) { block(mount()) }

    private fun Nfs3.ensureFolder() {
        val dir = newFile(directory)
        if (!dir.exists()) dir.mkdirs()
    }

    override suspend fun test(): StorageResult = withContext(Dispatchers.IO) {
        try {
            io { nfs ->
                nfs.ensureFolder()
                val probe = nfs.newFile(pathOf(PROBE_NAME))
                try {
                    probe.createNewFile()
                    NfsFileOutputStream(probe, 0, NFS_BUFFER_BYTES).use { it.write(PROBE_CONTENT) }
                    val back = NfsFileInputStream(probe, 0, NFS_BUFFER_BYTES).use { it.readBytes() }
                    if (back.contentEquals(PROBE_CONTENT)) StorageResult.Ok else StorageResult.Failed("Could not read back what was written")
                } finally {
                    runCatching { probe.delete() }
                }
            }
        } catch (e: IOException) {
            StorageResult.Failed(e.message ?: NETWORK_WRITE_MESSAGE)
        }
    }

    override suspend fun freeBytes(): Long? = withContext(Dispatchers.IO) {
        try {
            io { nfs -> nfs.nfsFsStat.fbytes.takeIf { it >= 0 } }
        } catch (e: IOException) {
            null
        }
    }

    override suspend fun openAppend(name: String): OutputStream = openStream {
        io { nfs ->
            nfs.ensureFolder()
            val file = nfs.newFile(pathOf(name))
            if (!file.exists()) file.createNewFile()
            NfsFileOutputStream(file, file.lengthEx(), NFS_BUFFER_BYTES)
        }
    }

    override suspend fun size(name: String): Long? = withContext(Dispatchers.IO) {
        io { nfs -> nfs.newFile(pathOf(name)).takeIf { it.exists() }?.lengthEx() }
    }

    override suspend fun openRead(name: String, offset: Long): InputStream = openStream {
        io { nfs -> NfsFileInputStream(nfs.newFile(pathOf(name)), offset, NFS_BUFFER_BYTES) }
    }

    override suspend fun delete(name: String): Boolean = withContext(Dispatchers.IO) {
        io { nfs ->
            val file: Nfs3File = nfs.newFile(pathOf(name))
            if (!file.exists()) false else { file.delete(); true }
        }
    }

    private companion object {
        val PROBE_CONTENT = "loophole".toByteArray()
    }
}
