package io.github.loopholetv.record.storage

import com.emc.ecs.nfsclient.mount.MountException
import com.emc.ecs.nfsclient.mount.MountStatus
import com.emc.ecs.nfsclient.nfs.NfsException
import com.emc.ecs.nfsclient.nfs.NfsStatus
import com.emc.ecs.nfsclient.rpc.RpcException
import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.protocol.transport.TransportException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException

const val NFS_REFUSED_MESSAGE = "Your NAS refused Loophole. On the NAS, allow 'insecure' ports (above 1024) for this export."
const val SMB_AUTH_MESSAGE = "The NAS refused the user name or password."
const val SHARE_NOT_FOUND_MESSAGE = "Couldn't find that share on the NAS."
const val EXPORT_NOT_FOUND_MESSAGE = "Couldn't find that export on the NAS."
const val NETWORK_WRITE_MESSAGE = "Couldn't write to the network folder."

private const val MAX_CAUSE_DEPTH = 8

private val SMB_AUTH_STATUSES = setOf(
    NtStatus.STATUS_LOGON_FAILURE,
    NtStatus.STATUS_ACCESS_DENIED,
    NtStatus.STATUS_ACCOUNT_DISABLED,
    NtStatus.STATUS_PASSWORD_EXPIRED,
    NtStatus.STATUS_LOGON_TYPE_NOT_GRANTED,
)

private val SMB_SHARE_STATUSES = setOf(NtStatus.STATUS_BAD_NETWORK_NAME, NtStatus.STATUS_BAD_NETWORK_PATH)

/**
 * Plain words for a storage failure. Only the error's type and status are read, never its message, so
 * nothing the library put in a message (a user name, a password) can reach the screen.
 */
fun storageMessage(e: Throwable, kind: StorageKind, host: String): String {
    val chain = generateSequence(e) { it.cause }.take(MAX_CAUSE_DEPTH).toList()
    chain.firstNotNullOfOrNull { classify(it, kind) }?.let { return it.text(host) }
    return NETWORK_WRITE_MESSAGE
}

private enum class Kind(val text: (String) -> String) {
    SMB_AUTH({ SMB_AUTH_MESSAGE }),
    SHARE_MISSING({ SHARE_NOT_FOUND_MESSAGE }),
    EXPORT_MISSING({ EXPORT_NOT_FOUND_MESSAGE }),
    NFS_REFUSED({ NFS_REFUSED_MESSAGE }),
    UNREACHABLE({ host -> "Couldn't reach the NAS at $host." }),
}

private fun classify(t: Throwable, kind: StorageKind): Kind? = when (t) {
    is SMBApiException -> when (t.status) {
        in SMB_AUTH_STATUSES -> Kind.SMB_AUTH
        in SMB_SHARE_STATUSES -> Kind.SHARE_MISSING
        else -> null
    }
    is NfsException -> if (t.status == NfsStatus.NFS3ERR_PERM || t.status == NfsStatus.NFS3ERR_ACCES) Kind.NFS_REFUSED else null
    is MountException -> when (t.status) {
        MountStatus.MNT3ERR_PERM, MountStatus.MNT3ERR_ACCES -> Kind.NFS_REFUSED
        MountStatus.MNT3ERR_NOENT, MountStatus.MNT3ERR_NOTDIR -> Kind.EXPORT_MISSING
        else -> null
    }
    is RpcException -> if (t.message.orEmpty().contains("AUTH", ignoreCase = true)) Kind.NFS_REFUSED else Kind.UNREACHABLE
    is TransportException, is SocketTimeoutException, is TimeoutException, is ConnectException,
    is UnknownHostException, is NoRouteToHostException -> Kind.UNREACHABLE
    is SocketException -> Kind.UNREACHABLE
    else -> null
}.takeIf { it == null || it.appliesTo(kind) }

private fun Kind.appliesTo(kind: StorageKind): Boolean = when (this) {
    Kind.SMB_AUTH, Kind.SHARE_MISSING -> kind != StorageKind.NFS
    Kind.EXPORT_MISSING, Kind.NFS_REFUSED -> kind != StorageKind.SMB
    Kind.UNREACHABLE -> true
}

/** Wraps [block]'s failure in an [IOException] carrying only [storageMessage]. */
internal inline fun <T> mapStorageErrors(kind: StorageKind, host: String, block: () -> T): T = try {
    block()
} catch (e: java.util.concurrent.CancellationException) {
    throw e
} catch (e: Exception) {
    throw IOException(storageMessage(e, kind, host))
}

/**
 * Opens a stream off the main thread. The open itself is not interrupted; if the caller is cancelled while
 * it runs, the coroutine machinery drops the result, so the stream (and the connection behind it) is
 * closed here instead of leaking.
 */
internal suspend fun <T : java.io.Closeable> openStream(block: () -> T): T {
    var opened: T? = null
    try {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
            block().also { opened = it }
        }
    } catch (e: java.util.concurrent.CancellationException) {
        opened?.let { runCatching { it.close() } }
        throw e
    }
}
