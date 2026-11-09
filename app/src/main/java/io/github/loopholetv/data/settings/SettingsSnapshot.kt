package io.github.loopholetv.data.settings

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** How long [SettingsSnapshot.await] waits for the first value before using defaults. */
const val SETTINGS_FIRST_VALUE_TIMEOUT_MS = 1_000L

/**
 * The latest [AppSettings], readable without suspending ([current]) or after the first value has
 * arrived ([await], for play-time stream resolution right after process start). [scope] owns the
 * collection. A read error (DataStore IOException) never crashes the app: the last good value, or
 * the defaults, stay in place.
 */
class SettingsSnapshot(settings: Flow<AppSettings>, scope: CoroutineScope) {
    @Volatile
    var current: AppSettings = AppSettings()
        private set

    private val ready = CompletableDeferred<Unit>()

    init {
        scope.launch {
            settings
                .catch { /* keep the last good value (or the defaults) */ }
                .onCompletion { ready.complete(Unit) }
                .collect {
                    current = it
                    ready.complete(Unit)
                }
        }
    }

    /** [current] once the first value has arrived, or the defaults after [SETTINGS_FIRST_VALUE_TIMEOUT_MS]. */
    suspend fun await(): AppSettings {
        withTimeoutOrNull(SETTINGS_FIRST_VALUE_TIMEOUT_MS) { ready.await() }
        return current
    }
}
