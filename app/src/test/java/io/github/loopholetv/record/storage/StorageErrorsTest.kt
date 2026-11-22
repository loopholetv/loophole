package io.github.loopholetv.record.storage

import com.emc.ecs.nfsclient.mount.MountException
import com.emc.ecs.nfsclient.mount.MountStatus
import com.emc.ecs.nfsclient.nfs.NfsException
import com.emc.ecs.nfsclient.nfs.NfsStatus
import com.emc.ecs.nfsclient.rpc.RpcException
import com.emc.ecs.nfsclient.rpc.RpcStatus
import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMB2MessageCommandCode
import com.hierynomus.mssmb2.SMBApiException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class StorageErrorsTest {
    private fun smb(status: NtStatus, message: String = "failed") =
        SMBApiException(status.value, SMB2MessageCommandCode.SMB2_TREE_CONNECT, message, null)

    private val nfsRefusal = "Your NAS refused Loophole. On the NAS, allow 'insecure' ports (above 1024) for this export."

    @Test
    fun smbLogonFailureIsAuthRefusal() {
        assertEquals("The NAS refused the user name or password.", storageMessage(smb(NtStatus.STATUS_LOGON_FAILURE), StorageKind.SMB, "nas"))
    }

    @Test
    fun smbBadNetworkNameIsShareNotFound() {
        assertEquals("Couldn't find that share on the NAS.", storageMessage(smb(NtStatus.STATUS_BAD_NETWORK_NAME), StorageKind.SMB, "nas"))
    }

    @Test
    fun nfsMountNoEntIsExportNotFound() {
        val e = MountException(MountStatus.MNT3ERR_NOENT, "no such export")
        assertEquals("Couldn't find that export on the NAS.", storageMessage(e, StorageKind.NFS, "nas"))
    }

    @Test
    fun timeoutIsUnreachable() {
        assertEquals("Couldn't reach the NAS at nas.", storageMessage(SocketTimeoutException("timed out"), StorageKind.SMB, "nas"))
    }

    @Test
    fun connectAndUnknownHostAreUnreachable() {
        assertEquals("Couldn't reach the NAS at 10.0.0.9.", storageMessage(ConnectException("refused"), StorageKind.NFS, "10.0.0.9"))
        assertEquals("Couldn't reach the NAS at nas.", storageMessage(UnknownHostException("nas"), StorageKind.SMB, "nas"))
    }

    @Test
    fun unreachableIsFoundInTheCauseChain() {
        val e = IOException("wrapped", SocketTimeoutException("timed out"))
        assertEquals("Couldn't reach the NAS at nas.", storageMessage(e, StorageKind.SMB, "nas"))
    }

    @Test
    fun nfsPermissionAndAccessAreTheRefusalCopy() {
        assertEquals(nfsRefusal, storageMessage(NfsException(NfsStatus.NFS3ERR_PERM, "perm"), StorageKind.NFS, "nas"))
        assertEquals(nfsRefusal, storageMessage(NfsException(NfsStatus.NFS3ERR_ACCES, "acces"), StorageKind.NFS, "nas"))
        assertEquals(nfsRefusal, storageMessage(MountException(MountStatus.MNT3ERR_ACCES, "acces"), StorageKind.NFS, "nas"))
    }

    @Test
    fun rpcAuthErrorIsTheRefusalCopy() {
        val e = RpcException(RpcStatus.NETWORK_ERROR, "RPC_AUTH_ERROR: AUTH_TOOWEAK")
        assertEquals(nfsRefusal, storageMessage(e, StorageKind.NFS, "nas"))
    }

    @Test
    fun anythingElseIsTheGenericWrite() {
        assertEquals("Couldn't write to the network folder.", storageMessage(IllegalStateException("boom"), StorageKind.SMB, "nas"))
        assertEquals("Couldn't write to the network folder.", storageMessage(NfsException(NfsStatus.NFS3ERR_NOSPC, "full"), StorageKind.NFS, "nas"))
    }

    @Test
    fun messageNeverContainsPassword() {
        val pw = "s3cret-pw"
        val errors = listOf(
            smb(NtStatus.STATUS_LOGON_FAILURE, "bad $pw"),
            smb(NtStatus.STATUS_BAD_NETWORK_NAME, "bad $pw"),
            smb(NtStatus.STATUS_OBJECT_NAME_NOT_FOUND, "bad $pw"),
            SocketTimeoutException("timeout $pw"),
            IOException("io $pw"),
            NfsException(NfsStatus.NFS3ERR_ACCES, "acces $pw"),
            RuntimeException("rt $pw", IOException(pw)),
        )
        for (kind in listOf(StorageKind.SMB, StorageKind.NFS)) for (e in errors) {
            assertFalse(storageMessage(e, kind, "nas").contains(pw))
        }
    }
}
