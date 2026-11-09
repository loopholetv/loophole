package io.github.loopholetv.data.repo

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs the app-start update ([RefreshCoordinator.runStartup]) once per process. Runs in [scope] (the
 * application scope), never blocks the UI and never throws: anything unexpected is swallowed (without
 * logging, since messages may embed source URLs).
 */
class StartupRefresher(
    private val run: suspend () -> Unit,
    private val scope: CoroutineScope,
) {
    constructor(coordinator: RefreshCoordinator, scope: CoroutineScope) : this({ coordinator.runStartup() }, scope)

    private val started = AtomicBoolean(false)

    /** Idempotent: only the first call launches work. Returns the job (null on repeat calls). */
    fun start(): Job? {
        if (!started.compareAndSet(false, true)) return null
        return scope.launch {
            try {
                run()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Intentionally ignored: see class doc.
            }
        }
    }
}
