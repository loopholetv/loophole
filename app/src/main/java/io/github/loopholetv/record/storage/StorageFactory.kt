package io.github.loopholetv.record.storage

import android.content.Context
import androidx.core.net.toUri
import io.github.loopholetv.data.settings.AppSettings

/** Builds the storage chosen in settings, and re-opens the one a finished recording was written to. */
class StorageFactory(
    private val context: Context,
    private val smb: (AppSettings) -> RecordingStorage,
    private val nfs: (AppSettings) -> RecordingStorage,
) {
    fun current(s: AppSettings): RecordingStorage? = when (s.recordStorage) {
        StorageKind.NONE -> null
        StorageKind.INTERNAL -> LocalDirStorage.internal(context)
        StorageKind.USB -> s.usbTreeUri.takeIf { it.isNotBlank() }?.let { SafTreeStorage(context, it.toUri()) }
        StorageKind.SMB -> smb(s)
        StorageKind.NFS -> nfs(s)
    }

    /** The storage a recording was written to, while it is still the configured one or is internal. */
    fun byId(id: String, s: AppSettings): RecordingStorage? {
        current(s)?.takeIf { it.id == id }?.let { return it }
        return if (id == INTERNAL_ID) LocalDirStorage.internal(context) else null
    }

    private companion object {
        const val INTERNAL_ID = "internal"
    }
}
