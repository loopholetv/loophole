package io.github.loopholetv.update

import io.github.loopholetv.update.UpdateEvent as E
import io.github.loopholetv.update.UpdateStatus as S
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateStatusTest {
    private val r = Release(SemVer(0, 4, 2), "https://example.org/a.apk", 100)

    private fun run(vararg events: E, from: S = S.None): S = events.fold(from) { s, e -> reduce(s, e) }

    @Test
    fun happyPath() {
        assertEquals(S.Available(r), run(E.Offered(r)))
        assertEquals(S.Downloading(r, 0), run(E.Offered(r), E.StartDownload))
        assertEquals(S.Downloading(r, 42), run(E.Offered(r), E.StartDownload, E.Progress(42)))
        assertEquals(S.Ready(r), run(E.Offered(r), E.StartDownload, E.Progress(100), E.Downloaded))
        assertEquals(S.Installing(r), run(E.InstallStarted, from = S.Ready(r)))
        assertEquals(S.None, run(E.InstallSucceeded, from = S.Installing(r)))
    }

    @Test
    fun laterHidesTheCard() {
        assertEquals(S.None, run(E.Later, from = S.Available(r)))
        assertEquals(S.None, run(E.Later, from = S.Ready(r)))
        assertEquals(S.None, run(E.Later, from = S.NeedsPermission(r)))
        assertEquals(S.None, run(E.Later, from = S.Failed(r)))
        // A running download is cancelled, not hidden.
        assertEquals(S.Downloading(r, 5), run(E.Later, from = S.Downloading(r, 5)))
    }

    @Test
    fun cancelAndFailuresDuringDownload() {
        assertEquals(S.Available(r), run(E.CancelDownload, from = S.Downloading(r, 30)))
        assertEquals(S.Failed(r), run(E.DownloadFailed, from = S.Downloading(r, 30)))
        assertEquals(S.Downloading(r, 0), run(E.StartDownload, from = S.Failed(r)))
        // Progress is clamped and ignored outside a download.
        assertEquals(S.Downloading(r, 100), run(E.Progress(130), from = S.Downloading(r, 1)))
        assertEquals(S.Ready(r), run(E.Progress(50), from = S.Ready(r)))
    }

    @Test
    fun permissionPath() {
        assertEquals(S.NeedsPermission(r), run(E.NoInstallPermission, from = S.Ready(r)))
        assertEquals(S.Installing(r), run(E.InstallStarted, from = S.NeedsPermission(r)))
    }

    @Test
    fun installOutcomes() {
        assertEquals(S.Ready(r), run(E.InstallCancelled, from = S.Installing(r)))
        assertEquals(S.Failed(r), run(E.InstallFailed, from = S.Installing(r)))
        assertEquals(S.Failed(r), run(E.VerifyFailed, from = S.Ready(r)))
        assertEquals(S.None, run(E.VerifyFailed, from = S.None))
    }

    @Test
    fun aNewOfferDoesNotInterruptADownloadOrInstall() {
        val r2 = r.copy(version = SemVer(0, 4, 3))
        assertEquals(S.Downloading(r, 9), run(E.Offered(r2), from = S.Downloading(r, 9)))
        assertEquals(S.Installing(r), run(E.Offered(r2), from = S.Installing(r)))
        assertEquals(S.Available(r2), run(E.Offered(r2), from = S.Available(r)))
    }

    @Test
    fun alreadyDownloadedGoesStraightToReady() {
        assertEquals(S.Ready(r), run(E.AlreadyDownloaded(r)))
        assertEquals(S.Downloading(r, 3), run(E.AlreadyDownloaded(r), from = S.Downloading(r, 3)))
    }

    @Test
    fun cardTextAndButtons() {
        assertNull(cardFor(S.None))
        assertEquals(
            UpdateCardUi("Loophole 0.4.2 is available", listOf("Update" to UpdateAction.UPDATE, "Later" to UpdateAction.LATER)),
            cardFor(S.Available(r)),
        )
        assertEquals(UpdateCardUi("Downloading… 42%", listOf("Cancel" to UpdateAction.CANCEL)), cardFor(S.Downloading(r, 42)))
        assertEquals("Ready to install", cardFor(S.Ready(r))!!.message)
        assertEquals(UpdateAction.INSTALL, cardFor(S.Ready(r))!!.buttons.first().second)
        assertEquals("Allow Loophole to install updates", cardFor(S.NeedsPermission(r))!!.message)
        assertEquals("Open settings", cardFor(S.NeedsPermission(r))!!.buttons.first().first)
        assertEquals("Update failed", cardFor(S.Failed(r))!!.message)
    }

    @Test
    fun backMeansLaterOrClose() {
        assertEquals(UpdateAction.LATER, backActionFor(S.Available(r)))
        assertEquals(UpdateAction.LATER, backActionFor(S.Ready(r)))
        assertEquals(UpdateAction.CLOSE, backActionFor(S.Failed(r)))
        assertNull(backActionFor(S.Downloading(r, 1)))
        assertNull(backActionFor(S.None))
    }

    @Test
    fun aboutTexts() {
        assertNull(aboutCheckText(AboutCheck.Idle))
        assertEquals("You're up to date", aboutCheckText(AboutCheck.UpToDate))
        assertEquals("Loophole 0.4.2 is available", aboutCheckText(AboutCheck.Available(r)))
        assertEquals("Couldn't check for updates", aboutCheckText(AboutCheck.Failed))
        assertEquals("Checking…", aboutCheckText(AboutCheck.Checking))
    }
}
