package io.github.loopholetv.update

const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

/**
 * Whether the start-up check is due: never when turned off; else when there is no successful
 * check yet, the last one is [intervalMs] old, or the clock has gone back past it.
 */
fun isCheckDue(enabled: Boolean, lastCheckMs: Long?, nowMs: Long, intervalMs: Long = CHECK_INTERVAL_MS): Boolean {
    if (!enabled) return false
    if (lastCheckMs == null) return true
    return nowMs < lastCheckMs || nowMs - lastCheckMs >= intervalMs
}

/** The result of an explicit "Check for updates". */
sealed interface CheckResult {
    data object UpToDate : CheckResult
    data class Available(val release: Release) : CheckResult
    data object Failed : CheckResult
}

/**
 * Release checks against [currentVersion]. Only successful checks are recorded, so a failed one
 * is retried at the next start.
 */
class UpdateChecker(
    private val latest: suspend () -> Release?,
    private val prefs: UpdatePrefs,
    private val currentVersion: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * The start-up check (at most once per [CHECK_INTERVAL_MS]). Returns the release to offer:
     * the one just found or remembered from the last check, unless it is not newer than this
     * build or the user chose Later for it. Null when [enabled] is false.
     */
    suspend fun onStart(enabled: Boolean): Release? {
        if (!enabled) return null
        val before = prefs.read()
        if (isCheckDue(true, before.lastCheckMs, clock())) {
            latest()?.let { prefs.recordCheck(clock(), it.takeIf(::isNewer)) }
        }
        val record = prefs.read()
        if (record.later) return null
        return record.offer?.takeIf(::isNewer)
    }

    /** "Check for updates" in About: ignores the interval (and the setting). */
    suspend fun checkNow(): CheckResult {
        val release = latest() ?: return CheckResult.Failed
        val newer = release.takeIf(::isNewer)
        prefs.recordCheck(clock(), newer)
        return if (newer != null) CheckResult.Available(newer) else CheckResult.UpToDate
    }

    /** Hides [Release] until the next successful check. */
    suspend fun later() = prefs.setLater(true)

    /** The release remembered by the last check, if still newer (ignores Later). */
    suspend fun rememberedOffer(): Release? = prefs.read().offer?.takeIf(::isNewer)

    private fun isNewer(r: Release): Boolean {
        val current = SemVer.parse(currentVersion) ?: return false
        return r.version > current
    }
}
