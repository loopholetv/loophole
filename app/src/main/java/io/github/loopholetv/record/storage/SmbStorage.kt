package io.github.loopholetv.record.storage

import com.hierynomus.mserref.NtStatus
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2MessageCommandCode
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import java.io.Closeable
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val SMB_SEPARATOR = "\\"
private const val SMB_TIMEOUT_SECONDS = 20L
private const val OPEN_AS_FOR_APPEND = true

/** One open connection to a share. Paths are relative to the share root and use `\` as the separator. */
interface SmbSession : Closeable {
    /** Creates the folder and any missing parents; does nothing when it exists. */
    fun mkdirs(path: String)

    /** Creates the file if absent; writes go to its end. */
    fun openAppend(path: String): OutputStream
    fun size(path: String): Long?
    fun openRead(path: String, offset: Long): InputStream
    fun delete(path: String): Boolean
    fun freeBytes(): Long?
}

/** Connects and signs in; the real one wraps smbj, tests use a fake. */
interface SmbConnector {
    fun connect(host: String, share: String, user: String, password: String, domain: String): SmbSession
}

/** Recordings in a folder on an SMB share. Each operation opens its own connection; streams close it when closed. */
class SmbStorage(
    private val host: String,
    private val share: String,
    folder: String,
    private val user: String,
    private val password: String,
    private val domain: String,
    private val connect: SmbConnector = RealSmbConnector,
) : RecordingStorage {
    private val folderParts = folder.split('/', '\\').filter { it.isNotBlank() }
    private val directory = (folderParts + RECORDINGS_FOLDER).joinToString(SMB_SEPARATOR)

    override val id: String = "smb://$host/$share" + folderParts.joinToString("") { "/$it" }
    override val label: String = "Network folder on $host"

    override fun toString(): String = "SmbStorage($id)"

    private fun pathOf(name: String) = directory + SMB_SEPARATOR + name

    private inline fun <T> io(block: () -> T): T = mapStorageErrors(StorageKind.SMB, host, block)

    private fun open(): SmbSession {
        // A password without a user name can never sign in; only a fully blank login means guest.
        if (user.isBlank() && password.isNotEmpty()) {
            throw SMBApiException(NtStatus.STATUS_LOGON_FAILURE.value, SMB2MessageCommandCode.SMB2_SESSION_SETUP, "No user name", null)
        }
        return connect.connect(host, share, user, password, domain)
    }

    /** Runs [block] on a fresh session that is closed afterwards. */
    private inline fun <T> withSession(block: (SmbSession) -> T): T = io { open().use(block) }

    override suspend fun test(): StorageResult = withContext(Dispatchers.IO) {
        try {
            withSession { s ->
                s.mkdirs(directory)
                val probe = pathOf(PROBE_NAME)
                try {
                    s.openAppend(probe).use { it.write(PROBE_CONTENT) }
                    val back = s.openRead(probe, 0).use { it.readBytes() }
                    if (back.contentEquals(PROBE_CONTENT)) StorageResult.Ok else StorageResult.Failed("Could not read back what was written")
                } finally {
                    runCatching { s.delete(probe) }
                }
            }
        } catch (e: IOException) {
            StorageResult.Failed(e.message ?: NETWORK_WRITE_MESSAGE)
        }
    }

    override suspend fun freeBytes(): Long? = withContext(Dispatchers.IO) {
        try {
            withSession { it.freeBytes() }
        } catch (e: IOException) {
            null
        }
    }

    override suspend fun openAppend(name: String): OutputStream = openStream {
        io {
            val session = open()
            try {
                session.mkdirs(directory)
                ClosingOutput(session.openAppend(pathOf(name)), session)
            } catch (e: Throwable) {
                session.close()
                throw e
            }
        }
    }

    override suspend fun size(name: String): Long? = withContext(Dispatchers.IO) {
        withSession { it.size(pathOf(name)) }
    }

    override suspend fun openRead(name: String, offset: Long): InputStream = openStream {
        io {
            val session = open()
            try {
                ClosingInput(session.openRead(pathOf(name), offset), session)
            } catch (e: Throwable) {
                session.close()
                throw e
            }
        }
    }

    override suspend fun delete(name: String): Boolean = withContext(Dispatchers.IO) {
        withSession { it.delete(pathOf(name)) }
    }

    private class ClosingOutput(out: OutputStream, private val session: Closeable) : FilterOutputStream(out) {
        override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
        override fun close() = try { super.close() } finally { session.close() }
    }

    private class ClosingInput(input: InputStream, private val session: Closeable) : FilterInputStream(input) {
        override fun close() = try { super.close() } finally { session.close() }
    }

    private companion object {
        val PROBE_CONTENT = "loophole".toByteArray()
    }
}

/** The smbj-backed connector. */
object RealSmbConnector : SmbConnector {
    override fun connect(host: String, share: String, user: String, password: String, domain: String): SmbSession {
        val config = SmbConfig.builder()
            .withTimeout(SMB_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .withSoTimeout(SMB_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
        val client = SMBClient(config)
        var connection: Connection? = null
        var session: Session? = null
        try {
            connection = client.connect(host)
            val auth = if (user.isBlank() && password.isEmpty()) AuthenticationContext.guest() else AuthenticationContext(user, password.toCharArray(), domain)
            session = connection.authenticate(auth)
            val disk = session.connectShare(share) as? DiskShare ?: throw IOException("Not a file share")
            return SmbjSession(client, connection, session, disk)
        } catch (e: Throwable) {
            runCatching { session?.close() }
            runCatching { connection?.close() }
            runCatching { client.close() }
            throw e
        }
    }
}

private class SmbjSession(
    private val client: SMBClient,
    private val connection: Connection,
    private val session: Session,
    private val share: DiskShare,
) : SmbSession {
    override fun mkdirs(path: String) {
        var current = ""
        for (part in path.split(SMB_SEPARATOR).filter { it.isNotEmpty() }) {
            current = if (current.isEmpty()) part else current + SMB_SEPARATOR + part
            if (!share.folderExists(current)) share.mkdir(current)
        }
    }

    override fun openAppend(path: String): OutputStream {
        val file = share.openFile(
            path,
            EnumSet.of(AccessMask.GENERIC_WRITE, AccessMask.GENERIC_READ),
            null,
            EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE),
            SMB2CreateDisposition.FILE_OPEN_IF,
            null,
        )
        return try {
            val out = file.getOutputStream(OPEN_AS_FOR_APPEND)
            object : FilterOutputStream(out) {
                override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
                override fun close() = try { super.close() } finally { file.close() }
            }
        } catch (e: Throwable) {
            runCatching { file.close() }
            throw e
        }
    }

    override fun size(path: String): Long? {
        if (!share.fileExists(path)) return null
        return share.getFileInformation(path).standardInformation.endOfFile
    }

    override fun openRead(path: String, offset: Long): InputStream {
        val file = share.openFile(
            path,
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE, SMB2ShareAccess.FILE_SHARE_DELETE),
            SMB2CreateDisposition.FILE_OPEN,
            null,
        )
        return object : InputStream() {
            private var position = offset
            private val one = ByteArray(1)

            override fun read(): Int = if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                val n = file.read(b, position, off, len)
                if (n > 0) position += n
                return if (n <= 0) -1 else n
            }

            override fun close() = file.close()
        }
    }

    override fun delete(path: String): Boolean {
        if (!share.fileExists(path)) return false
        share.rm(path)
        return true
    }

    override fun freeBytes(): Long? = share.shareInformation.callerFreeSpace.takeIf { it >= 0 }

    override fun close() {
        runCatching { share.close() }
        runCatching { session.close() }
        runCatching { connection.close() }
        runCatching { client.close() }
    }
}
