package io.github.loopholetv.update

import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

private const val HOUR = 60L * 60 * 1000

class UpdateCheckerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val newer = Release(SemVer(0, 4, 2), "https://example.org/a.apk", 10)
    private val same = Release(SemVer(0, 4, 1), "https://example.org/b.apk", 10)

    private var now = 1_000_000_000L
    private var calls = 0
    private var response: Release? = newer

    private fun prefs(scope: CoroutineScope) = UpdatePrefs(
        PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = scope,
        ) { File(tmp.root, "settings.preferences_pb") },
    )

    private fun checker(prefs: UpdatePrefs) =
        UpdateChecker(latest = { calls++; response }, prefs = prefs, currentVersion = "0.4.1", clock = { now })

    @Test
    fun dueRules() {
        assertFalse(isCheckDue(enabled = false, lastCheckMs = null, nowMs = 0))
        assertTrue(isCheckDue(enabled = true, lastCheckMs = null, nowMs = 0))
        assertFalse(isCheckDue(true, lastCheckMs = 0, nowMs = 24 * HOUR - 1))
        assertTrue(isCheckDue(true, lastCheckMs = 0, nowMs = 24 * HOUR))
        // The clock went back: check again rather than wait for it to catch up.
        assertTrue(isCheckDue(true, lastCheckMs = 10 * HOUR, nowMs = 5 * HOUR))
    }

    @Test
    fun checksAtMostOnceADay() = runTest {
        val p = prefs(backgroundScope)
        val c = checker(p)
        assertEquals(newer, c.onStart(enabled = true))
        now += 23 * HOUR
        // Not checked again, but the remembered release is still offered.
        assertEquals(newer, c.onStart(enabled = true))
        assertEquals(1, calls)
        now += HOUR
        c.onStart(enabled = true)
        assertEquals(2, calls)
    }

    @Test
    fun turnedOffNeitherChecksNorOffers() = runTest {
        val c = checker(prefs(backgroundScope))
        assertNull(c.onStart(enabled = false))
        assertEquals(0, calls)
    }

    @Test
    fun aFailedCheckIsNotRecordedSoTheNextStartRetries() = runTest {
        val p = prefs(backgroundScope)
        val c = checker(p)
        response = null
        assertNull(c.onStart(enabled = true))
        assertNull(p.read().lastCheckMs)
        now += HOUR
        response = newer
        assertEquals(newer, c.onStart(enabled = true))
        assertEquals(2, calls)
        assertEquals(now, p.read().lastCheckMs)
    }

    @Test
    fun aReleaseThatIsNotNewerIsNotOffered() = runTest {
        response = same
        val p = prefs(backgroundScope)
        assertNull(checker(p).onStart(enabled = true))
        assertNull(p.read().offer)
        assertEquals(now, p.read().lastCheckMs)
    }

    @Test
    fun laterHidesTheOfferUntilTheNextSuccessfulCheck() = runTest {
        val p = prefs(backgroundScope)
        val c = checker(p)
        assertEquals(newer, c.onStart(enabled = true))
        c.later()
        now += 2 * HOUR
        assertNull(c.onStart(enabled = true))
        // A day later the check fails: still hidden.
        now += 24 * HOUR
        response = null
        assertNull(c.onStart(enabled = true))
        // The next start's check succeeds: offered again.
        now += HOUR
        response = newer
        assertEquals(newer, c.onStart(enabled = true))
    }

    @Test
    fun checkNowIgnoresTheIntervalAndReportsEachOutcome() = runTest {
        val p = prefs(backgroundScope)
        val c = checker(p)
        c.onStart(enabled = true)
        c.later()
        assertEquals(CheckResult.Available(newer), c.checkNow())
        assertEquals(2, calls)
        // A manual check counts as a check: Later is cleared.
        assertFalse(p.read().later)
        response = same
        assertEquals(CheckResult.UpToDate, c.checkNow())
        assertNull(p.read().offer)
        val last = p.read().lastCheckMs
        now += HOUR
        response = null
        assertEquals(CheckResult.Failed, c.checkNow())
        assertEquals(last, p.read().lastCheckMs)
    }

    @Test
    fun anInstalledUpdateRetiresTheRememberedOffer() = runTest {
        val p = prefs(backgroundScope)
        checker(p).onStart(enabled = true)
        val updated = UpdateChecker({ calls++; newer }, p, currentVersion = "0.4.2", clock = { now })
        now += HOUR
        assertNull(updated.onStart(enabled = true))
        assertNull(updated.rememberedOffer())
    }
}
