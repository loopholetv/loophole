package io.github.loopholetv.record.storage

/** Where recordings are written. [NONE] means no storage is set up yet. */
enum class StorageKind { NONE, SMB, NFS, USB, INTERNAL }
