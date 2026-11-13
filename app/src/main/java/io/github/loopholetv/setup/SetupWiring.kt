package io.github.loopholetv.setup

import kotlinx.coroutines.CancellationException

/**
 * What this TV does once a copied setup has replaced its own (the sending TV already has its
 * answer): forgets the replaced playlists' portal sessions, then updates every playlist and
 * guide. The importer has already removed the replaced playlists' file copies and file grants.
 * A session that can't be forgotten doesn't stop the update; nothing is logged.
 */
internal suspend fun afterSetupImported(
    result: SetupImportResult.Ok,
    invalidateSession: (playlistId: Long) -> Unit,
    refreshAll: suspend () -> Unit,
) {
    for (removed in result.removed) {
        try {
            invalidateSession(removed.id)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The session ends with its playlist row anyway; carry on with the others.
        }
    }
    refreshAll()
}
