package io.github.loopholetv.update

import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UpdaterTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val apk = ByteArray(5_000) { it.toByte() }

    private class FakePlatform : InstallPlatform {
        var allowed = true
        var verifyResult = VerifyResult.OK
        val installs = mutableListOf<File>()
        var settingsOpened = 0
        override fun canInstall() = allowed
        override fun verify(file: File) = verifyResult
        override fun install(file: File) { installs += file }
        override fun openInstallSettings() { settingsOpened++ }
    }

    private val platform = FakePlatform()
    private var latest: Release? = null
    private var enabled = true
    private val gate = CompletableDeferred<Unit>().apply { complete(Unit) }

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        latest = Release(SemVer(0, 4, 2), server.url("/a.apk").toString(), apk.size.toLong())
    }

    @After
    fun tearDown() = server.shutdown()

    private fun prefs(scope: CoroutineScope) = UpdatePrefs(
        PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = scope,
        ) { File(tmp.root, "settings.preferences_pb") },
    )

    private val downloader by lazy { UpdateDownloader(OkHttpClient(), File(tmp.root, "updates"), "Loophole/0.4.1") }

    private fun TestScope.updater(p: UpdatePrefs = prefs(backgroundScope), gate: suspend () -> Unit = { this@UpdaterTest.gate.await() }) =
        Updater(
            scope = backgroundScope,
            checker = UpdateChecker({ latest }, p, "0.4.1", clock = { 1_000L }),
            downloader = downloader,
            platform = platform,
            enabled = { enabled },
            gate = gate,
        )

    @Test
    fun theCardWaitsForTheStartGate() = runTest {
        val held = CompletableDeferred<Unit>()
        val u = updater(gate = { held.await() })
        val job = u.onColdStart()
        advanceUntilIdle()
        assertEquals(UpdateStatus.None, u.status.value)
        held.complete(Unit)
        job.join()
        assertEquals(UpdateStatus.Available(latest!!), u.status.value)
    }

    @Test
    fun turnedOffShowsNothing() = runTest {
        enabled = false
        val u = updater()
        u.onColdStart().join()
        assertEquals(UpdateStatus.None, u.status.value)
    }

    @Test
    fun laterPersistsUntilTheNextCheck() = runTest {
        val p = prefs(backgroundScope)
        val first = updater(p)
        first.onColdStart().join()
        first.perform(UpdateAction.LATER)!!.join()
        assertEquals(UpdateStatus.None, first.status.value)
        // The next start, within the day: still hidden.
        val second = updater(p)
        second.onColdStart().join()
        assertEquals(UpdateStatus.None, second.status.value)
    }

    @Test
    fun focusIsClaimedOnce() = runTest {
        val u = updater()
        assertTrue(u.claimFirstFocus())
        assertFalse(u.claimFirstFocus())
    }

    @Test
    fun downloadVerifyAndInstall() = runTest {
        server.enqueue(MockResponse().setBody(Buffer().write(apk)))
        val u = updater()
        u.onColdStart().join()
        u.perform(UpdateAction.UPDATE)!!.join()
        assertTrue(u.status.value is UpdateStatus.Ready)
        u.perform(UpdateAction.INSTALL)!!.join()
        assertEquals(UpdateStatus.Installing(latest!!), u.status.value)
        assertEquals(1, platform.installs.size)
        u.onInstallOutcome(InstallOutcome.CANCELLED)
        assertEquals(UpdateStatus.Ready(latest!!), u.status.value)
        u.perform(UpdateAction.INSTALL)!!.join()
        u.onInstallOutcome(InstallOutcome.FAILED)
        assertEquals(UpdateStatus.Failed(latest!!), u.status.value)
    }

    @Test
    fun aFailedCheckOfTheDownloadDeletesItAndFails() = runTest {
        server.enqueue(MockResponse().setBody(Buffer().write(apk)))
        platform.verifyResult = VerifyResult.WRONG_SIGNER
        val u = updater()
        u.onColdStart().join()
        u.perform(UpdateAction.UPDATE)!!.join()
        assertTrue(u.status.value is UpdateStatus.Failed)
        assertEquals(null, downloader.existing(latest!!))
    }

    @Test
    fun withoutPermissionTheCardAsksAndTheInstallGoesOnOnReturn() = runTest {
        server.enqueue(MockResponse().setBody(Buffer().write(apk)))
        platform.allowed = false
        val u = updater()
        u.onColdStart().join()
        u.perform(UpdateAction.UPDATE)!!.join()
        assertTrue(u.status.value is UpdateStatus.Ready)
        u.perform(UpdateAction.INSTALL)
        assertEquals(UpdateStatus.NeedsPermission(latest!!), u.status.value)
        u.perform(UpdateAction.OPEN_SETTINGS)
        assertEquals(1, platform.settingsOpened)
        // Back without allowing: still asking.
        u.onResumed()
        assertEquals(UpdateStatus.NeedsPermission(latest!!), u.status.value)
        platform.allowed = true
        u.onResumed()!!.join()
        assertEquals(UpdateStatus.Installing(latest!!), u.status.value)
        assertEquals(1, platform.installs.size)
    }

    @Test
    fun anInstallWithNoReportedOutcomeReturnsToInstall() = runTest {
        server.enqueue(MockResponse().setBody(Buffer().write(apk)))
        val u = updater()
        u.onColdStart().join()
        u.perform(UpdateAction.UPDATE)!!.join()
        assertTrue(u.status.value is UpdateStatus.Ready)
        u.perform(UpdateAction.INSTALL)!!.join()
        u.onResumed()
        advanceTimeBy(LOST_RESULT_MS - 1)
        assertEquals(UpdateStatus.Installing(latest!!), u.status.value)
        advanceTimeBy(2)
        assertEquals(UpdateStatus.Ready(latest!!), u.status.value)
    }

    @Test
    fun aFinishedDownloadIsReadyAtTheNextStart() = runTest {
        server.enqueue(MockResponse().setBody(Buffer().write(apk)))
        val p = prefs(backgroundScope)
        val first = updater(p)
        first.onColdStart().join()
        first.perform(UpdateAction.UPDATE)!!.join()
        assertTrue(first.status.value is UpdateStatus.Ready)
        val second = updater(p)
        second.onColdStart().join()
        assertEquals(UpdateStatus.Ready(latest!!), second.status.value)
    }

    @Test
    fun aboutCheckStates() = runTest {
        val u = updater()
        latest = latest!!.copy(version = SemVer(0, 4, 1))
        u.checkNow()!!.join()
        assertEquals(AboutCheck.UpToDate, u.about.value)
        latest = null
        u.checkNow()!!.join()
        assertEquals(AboutCheck.Failed, u.about.value)
        val newer = Release(SemVer(0, 5, 0), server.url("/b.apk").toString(), 10)
        latest = newer
        u.checkNow()!!.join()
        assertEquals(AboutCheck.Available(newer), u.about.value)
        // The guide card offers it too.
        assertEquals(UpdateStatus.Available(newer), u.status.value)
    }

    @Test
    fun cancelReturnsToAvailable() = runTest {
        // A body that never ends: the download stays running until cancelled.
        server.enqueue(MockResponse().setBody(Buffer().write(apk.copyOf(100))).throttleBody(10, 1, java.util.concurrent.TimeUnit.SECONDS))
        val u = updater()
        u.onColdStart().join()
        u.perform(UpdateAction.UPDATE)
        assertTrue(u.status.value is UpdateStatus.Downloading)
        u.perform(UpdateAction.CANCEL)
        assertEquals(UpdateStatus.Available(latest!!), u.status.value)
    }
}
