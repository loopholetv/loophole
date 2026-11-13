package io.github.loopholetv.setup

import io.github.loopholetv.data.db.PlaylistType
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SetupControllerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Volatile private var now = 1_000_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient()
    private val tvs = ArrayList<SetupTestTv>()

    @After fun tearDown() {
        tvs.forEach { it.close() }
        scope.cancel()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    private fun tv(id: String, name: String) = SetupTestTv(id, name, tmp.root, scope, http, { now }).also { tvs += it }

    /** Two TVs that see each other: [first] has a setup of its own, [second] another. */
    private fun pair(): Pair<SetupTestTv, SetupTestTv> {
        val a = tv("a", "Living room")
        val b = tv("b", "Bedroom")
        a.sees(b)
        b.sees(a)
        a.addPlaylist("Main")
        a.addPlaylist("Film", PlaylistType.M3U_FILE, mediaFile("film.m3u").absolutePath)
        b.addPlaylist("Old")
        return a to b
    }

    private fun mediaFile(name: String) = File(tmp.root, "media/$name").apply {
        parentFile!!.mkdirs()
        writeText("#EXTM3U\n#EXTINF:-1,Film\nhttp://f.example.test/1.ts\n")
    }

    private fun wrong(code: String) = ((code.toInt() + 1) % 10_000).toString().padStart(4, '0')

    private fun enterCode(tv: SetupTestTv) = tv.await { it is SetupState.EnterCode } as SetupState.EnterCode

    @Test fun sendFlowCopiesWithRightCode() {
        val (a, b) = pair()
        a.controller.sendTo("b")
        val showing = b.await { it is SetupState.ShowingCode } as SetupState.ShowingCode
        assertEquals("Living room", showing.fromName)
        assertEquals(now + SetupRequest.LIFETIME_MS, showing.expiresAtMs)
        assertEquals(SetupState.EnterCode("Bedroom", asked = false, triesLeft = null, error = null), enterCode(a))

        a.controller.enterCode(showing.code)
        assertEquals(SetupState.Done("Setup copied from Living room.", copied = true, received = true), b.awaitDone())
        assertEquals(SetupState.Done("Setup copied to Bedroom.", copied = true), a.awaitDone())
        assertEquals(listOf("Main", "Film"), b.playlistNames())
        b.await { b.imported.size == 1 }
        assertEquals(1, b.importCalls.get())
        assertTrue("the sender's staged files go", a.temps().isEmpty())
        // The sender's own setup is untouched.
        assertEquals(listOf("Main", "Film"), a.playlistNames())
    }

    @Test fun copyFromFlowCopiesWithRightCode() {
        val (a, b) = pair()
        b.controller.copyFrom("a")
        val showing = b.await { it is SetupState.ShowingCode } as SetupState.ShowingCode
        assertEquals("Living room", showing.fromName)
        assertEquals(SetupState.EnterCode("Bedroom", asked = true, triesLeft = null, error = null), enterCode(a))

        a.controller.enterCode(showing.code)
        assertEquals(SetupState.Done("Setup copied from Living room.", copied = true, received = true), b.awaitDone())
        assertEquals(SetupState.Done("Setup copied to Bedroom.", copied = true), a.awaitDone())
        assertEquals(listOf("Main", "Film"), b.playlistNames())
        assertTrue(a.temps().isEmpty())
    }

    @Test fun aTvWithNoPlaylistsWontSendItsSetup() {
        val a = tv("a", "Living room")
        val b = tv("b", "Bedroom")
        a.sees(b)
        b.sees(a)
        b.addPlaylist("Old")

        a.controller.sendTo("b")
        assertEquals(SetupState.Done("Living room has no playlists to copy.", copied = false), a.awaitDone())
        assertEquals("the other TV was never asked to show a code", SetupState.Idle, b.state)
        assertNull(b.controller.host.current())
        assertNull("this TV is free for another copy", a.controller.host.current())
        assertEquals(listOf("Old"), b.playlistNames())
    }

    @Test fun playlistsRemovedWhileTheCodeIsTypedInAreNotSentEmpty() {
        val (a, b) = pair()
        a.controller.sendTo("b")
        val code = b.code()
        enterCode(a)
        kotlinx.coroutines.runBlocking { a.db.setupDao().deleteAllPlaylists() }

        a.controller.enterCode(code)
        assertEquals(SetupState.Done("Living room has no playlists to copy.", copied = false), a.awaitDone())
        assertEquals(SetupState.Done("Copy cancelled.", copied = false), b.awaitDone())
        assertEquals(0, b.importCalls.get())
        assertEquals(listOf("Old"), b.playlistNames())
    }

    @Test fun askingATvWithNoPlaylistsSaysItHasNoneToCopy() {
        val a = tv("a", "Living room")
        val b = tv("b", "Bedroom")
        a.sees(b)
        b.sees(a)
        b.addPlaylist("Old")

        b.controller.copyFrom("a")
        assertEquals(SetupState.Done("Living room has no playlists to copy.", copied = false), b.awaitDone())
        assertEquals("the asked TV shows nothing", SetupState.Idle, a.state)
        assertNull(a.controller.host.current())
        assertNull(b.controller.host.current())
        assertEquals(listOf("Old"), b.playlistNames())
    }

    @Test fun wrongCodeLeavesReceiverUntouched() {
        val (a, b) = pair()
        a.controller.sendTo("b")
        val code = b.code()
        enterCode(a)
        a.controller.enterCode(wrong(code))
        assertEquals(SetupState.EnterCode("Bedroom", false, 2, "Wrong code. 2 tries left."), a.await { it is SetupState.EnterCode && it.triesLeft != null })
        a.controller.enterCode(wrong(wrong(code)))
        assertEquals(SetupState.EnterCode("Bedroom", false, 1, "Wrong code. 1 try left."), a.await { it is SetupState.EnterCode && it.triesLeft == 1 })

        assertTrue(b.state is SetupState.ShowingCode)
        assertEquals(listOf("Old"), b.playlistNames())
        assertEquals(0, b.importCalls.get())
        assertTrue("no staged files on the sender", a.temps().isEmpty())
        assertTrue("none on the receiver", b.temps().isEmpty())

        // The right code still works on the last try.
        a.controller.enterCode(code)
        assertTrue(b.awaitDone().copied)
    }

    @Test fun thirdWrongCodeCancelsBothSides() {
        val (a, b) = pair()
        a.controller.sendTo("b")
        val code = b.code()
        enterCode(a)
        var guess = code
        repeat(2) {
            guess = wrong(guess)
            a.controller.enterCode(guess)
            val left = 2 - it
            a.await { s -> s is SetupState.EnterCode && s.triesLeft == left }
        }
        a.controller.enterCode(wrong(guess))
        assertEquals(SetupState.Done("Copy cancelled.", copied = false), a.awaitDone())
        assertEquals(SetupState.Done("Copy cancelled.", copied = false), b.awaitDone())
        assertEquals(listOf("Old"), b.playlistNames())
        assertEquals(0, b.importCalls.get())
        // Even the right code can't open it now, and nothing is left behind.
        assertEquals(null, b.controller.host.current())
        assertEquals(null, a.controller.host.current())
        assertTrue(a.temps().isEmpty() && b.temps().isEmpty())
    }

    @Test fun expiredRequestRefused() {
        val (a, b) = pair()
        a.controller.sendTo("b")
        val code = b.code()
        enterCode(a)
        now += SetupRequest.LIFETIME_MS
        a.controller.enterCode(code)
        assertEquals(SetupState.Done("The request timed out.", copied = false), a.awaitDone())
        assertEquals(SetupState.Done("The request timed out.", copied = false), b.awaitDone())
        assertEquals(listOf("Old"), b.playlistNames())
        assertEquals(0, b.importCalls.get())
    }

    @Test fun requestTimesOutOnBothSidesOnItsOwn() {
        val a = SetupTestTv("a", "Living room", tmp.root, scope, http, { now }, lifetimeMs = 300).also { tvs += it }
        val b = SetupTestTv("b", "Bedroom", tmp.root, scope, http, { now }, lifetimeMs = 300).also { tvs += it }
        a.sees(b)
        b.sees(a)
        a.addPlaylist("Main")
        a.controller.sendTo("b")
        b.code()
        assertEquals(SetupState.Done("The request timed out.", copied = false), b.awaitDone())
        assertEquals(SetupState.Done("The request timed out.", copied = false), a.awaitDone())
        assertEquals(null, a.controller.host.current())
    }

    @Test fun secondRequestWhileBusyRefused() {
        val (a, b) = pair()
        val c = tv("c", "Kitchen").also { it.addPlaylist("Main") }
        c.sees(b)
        b.sees(c)
        a.controller.sendTo("b")
        b.code()
        enterCode(a)

        c.controller.sendTo("b")
        assertEquals(SetupState.Done("Bedroom is busy. Try again in a moment.", copied = false), c.awaitDone())
        c.controller.cancel()
        c.controller.copyFrom("b")
        assertEquals(SetupState.Done("Bedroom is busy. Try again in a moment.", copied = false), c.awaitDone())
        assertEquals("the first request still stands", "Living room", (b.state as SetupState.ShowingCode).fromName)

        // A TV that is busy itself doesn't start another copy: it says so for a moment.
        a.sees(c)
        val before = a.state
        a.controller.sendTo("c")
        assertEquals(SetupState.Done("Finish or cancel the current copy first.", copied = false), a.state)
        a.controller.copyFrom("c")
        assertEquals(before, a.await { it is SetupState.EnterCode })
        assertTrue(c.state is SetupState.Done)
        // Asking again for the TV it is already copying with changes nothing.
        a.controller.sendTo("b")
        assertEquals(before, a.state)
        assertTrue("the copy itself still works", a.controller.host.current() != null)
    }

    @Test fun olderPeerReportsVersion() {
        val a = tv("a", "Living room").also { it.addPlaylist("Main") }
        val b = tv("b", "Bedroom")
        a.sees(b, setup = 0)
        a.controller.sendTo("b")
        assertEquals(SetupState.Done("Bedroom is on an older version of Loophole.", copied = false), a.awaitDone())
        a.controller.cancel()
        a.controller.copyFrom("b")
        assertEquals(SetupState.Done("Bedroom is on an older version of Loophole.", copied = false), a.awaitDone())
        assertTrue("never contacted", b.state is SetupState.Idle)

        // A TV that claims the version but has no setup routes reads the same.
        val bare = io.github.loopholetv.cast.net.LocalHttpServer(scope)
        try {
            val port = bare.start(listOf(SetupTestTv.LOOPBACK))
            a.seesPeer("old", "Kitchen", port)
            a.controller.cancel()
            a.controller.sendTo("old")
            assertEquals(SetupState.Done("Kitchen is on an older version of Loophole.", copied = false), a.awaitDone())
            assertEquals(null, a.controller.host.current())
        } finally {
            bare.stop()
        }
    }

    @Test fun cancelFromEitherSide() {
        // The receiving TV cancels on the code screen.
        val (a, b) = pair()
        a.controller.sendTo("b")
        b.code()
        enterCode(a)
        b.controller.cancel()
        assertEquals(SetupState.Idle, b.state)
        assertEquals(SetupState.Done("Copy cancelled.", copied = false), a.awaitDone())
        assertEquals(null, a.controller.host.current())

        // The sending TV cancels while asked for the code.
        val c = tv("c", "Kitchen")
        val d = tv("d", "Study")
        c.sees(d)
        d.sees(c)
        c.addPlaylist("Main")
        d.controller.copyFrom("c")
        d.code()
        enterCode(c)
        c.controller.cancel()
        assertEquals(SetupState.Idle, c.state)
        assertEquals(SetupState.Done("Copy cancelled.", copied = false), d.awaitDone())
        assertEquals(null, d.controller.host.current())

        // Done goes back to idle.
        d.controller.cancel()
        assertEquals(SetupState.Idle, d.state)
        listOf(a, b, c, d).forEach { assertTrue(it.temps().isEmpty()) }
    }

    @Test fun cancellingAnOfferedCodeRefusesThatTvForTheSession() {
        val (a, b) = pair()
        a.controller.sendTo("b")
        b.code()
        b.controller.cancel()
        a.awaitDone()
        now += SetupHost.MIN_REQUEST_GAP_MS
        a.controller.cancel()
        a.controller.sendTo("b")
        assertEquals(SetupState.Done("Bedroom isn't taking requests right now. Try again later.", copied = false), a.awaitDone())
        assertEquals(SetupState.Idle, b.state)
    }

    @Test fun offersThrottledAfterFailures() {
        val (a, b) = pair()
        fun failOnce() {
            a.controller.cancel()
            a.controller.sendTo("b")
            var guess = b.code()
            enterCode(a)
            repeat(SetupRequest.MAX_ATTEMPTS) {
                guess = wrong(guess)
                a.controller.enterCode(guess)
                a.await { s -> s is SetupState.Done || (s is SetupState.EnterCode && s.triesLeft == 2 - it) }
            }
            assertEquals(SetupState.Done("Copy cancelled.", copied = false), a.awaitDone())
            b.awaitDone()
        }
        failOnce()
        // One offer per address every 30 s.
        a.controller.cancel()
        a.controller.sendTo("b")
        assertEquals(SetupState.Done("Bedroom isn't taking requests right now. Try again later.", copied = false), a.awaitDone())

        now += SetupHost.MIN_REQUEST_GAP_MS
        failOnce()
        now += SetupHost.MIN_REQUEST_GAP_MS
        a.controller.cancel()
        a.controller.sendTo("b")
        assertEquals(SetupState.Done("Bedroom isn't taking requests right now. Try again later.", copied = false), a.awaitDone())
        assertFalse(b.state is SetupState.ShowingCode)

        now += SetupHost.THROTTLE_MS
        a.controller.cancel()
        a.controller.sendTo("b")
        b.code()
        enterCode(a)
    }

    @Test fun failedImportLeavesNoTempFiles() {
        val (a, b) = pair()
        b.importOverride = { SetupImportResult.Failed }
        a.controller.sendTo("b")
        val code = b.code()
        enterCode(a)
        a.controller.enterCode(code)
        assertEquals(SetupState.Done("Couldn't copy the setup.", copied = false), b.awaitDone())
        assertEquals(SetupState.Done("Couldn't copy the setup.", copied = false), a.awaitDone())
        assertEquals(1, b.importCalls.get())
        assertTrue(b.temps().isEmpty())
        assertTrue(a.temps().isEmpty())
        assertEquals(listOf("Old"), b.playlistNames())
    }

    @Test fun skippedFilePlaylistsAreCounted() {
        assertEquals("1 playlist from a file was too large to copy.", SetupController.skippedFiles(1))
        assertEquals("3 playlists from files were too large to copy.", SetupController.skippedFiles(3))
        assertEquals(
            "Setup copied from Den. 2 playlists from files were too large to copy.",
            SetupController.copiedFrom("Den", 2),
        )
        assertEquals("Setup copied from Den.", SetupController.copiedFrom("Den", 0))

        val (a, b) = pair()
        // A file playlist whose file is gone (and has no copy) is left out by the sender.
        a.addPlaylist("Lost", PlaylistType.M3U_FILE, File(tmp.root, "missing.m3u").absolutePath)
        a.controller.sendTo("b")
        val code = b.code()
        enterCode(a)
        a.controller.enterCode(code)
        assertEquals(
            SetupState.Done("Setup copied from Living room. 1 playlist from a file was too large to copy.", copied = true, received = true),
            b.awaitDone(),
        )
        assertEquals(
            SetupState.Done("Setup copied to Bedroom. 1 playlist from a file was too large to copy.", copied = true),
            a.awaitDone(),
        )
    }

    @Test fun unknownPeerIsReported() {
        val a = tv("a", "Living room")
        a.controller.sendTo("nobody")
        assertEquals(SetupState.Done("That TV is no longer on the network.", copied = false), a.awaitDone())
    }

    private fun guessTwiceThenCancel(a: SetupTestTv, b: SetupTestTv) {
        a.controller.cancel()
        a.controller.sendTo("b")
        var guess = b.code()
        enterCode(a)
        repeat(2) {
            guess = wrong(guess)
            a.controller.enterCode(guess)
            a.await { s -> s is SetupState.EnterCode && s.triesLeft == 2 - it }
        }
        a.controller.cancel()
        assertEquals(SetupState.Done("Copy cancelled.", copied = false), b.awaitDone())
    }

    @Test fun wrongCodesCountEvenWhenTheSenderCancels() {
        val (a, b) = pair()
        repeat(3) {
            guessTwiceThenCancel(a, b)
            now += SetupHost.MIN_REQUEST_GAP_MS
        }
        a.controller.cancel()
        a.controller.sendTo("b")
        assertEquals(SetupState.Done("Bedroom isn't taking requests right now. Try again later.", copied = false), a.awaitDone())
        assertFalse(b.state is SetupState.ShowingCode)
        assertEquals(0, b.importCalls.get())
    }

    @Test fun tooLittleMemoryRefusesTheSetupAndFreesTheSlot() {
        val (a, b) = pair()
        b.availableMemory = 1
        a.controller.sendTo("b")
        val code = b.code()
        enterCode(a)
        a.controller.enterCode(code)
        assertEquals(SetupState.Done("Couldn't copy the setup.", copied = false), b.awaitDone())
        assertEquals(SetupState.Done("Couldn't copy the setup.", copied = false), a.awaitDone())
        assertEquals(null, b.controller.host.current())
        assertEquals(null, a.controller.host.current())
        assertEquals(0, b.importCalls.get())
    }

    @Test fun runningOutOfMemoryWhileReadingFreesTheSlot() {
        val (a, b) = pair()
        b.bufferOverride = { throw OutOfMemoryError() }
        a.controller.sendTo("b")
        val code = b.code()
        enterCode(a)
        a.controller.enterCode(code)
        assertEquals(SetupState.Done("Couldn't copy the setup.", copied = false), b.awaitDone())
        assertEquals(SetupState.Done("Couldn't copy the setup.", copied = false), a.awaitDone())
        assertEquals(null, b.controller.host.current())

        // The next copy works.
        b.bufferOverride = null
        now += SetupHost.MIN_REQUEST_GAP_MS
        a.controller.cancel()
        a.controller.sendTo("b")
        val again = b.code()
        enterCode(a)
        a.controller.enterCode(again)
        assertTrue(b.awaitDone().copied)
    }

    @Test fun runningOutOfMemoryWhileDecodingFreesTheSlot() {
        val (a, b) = pair()
        b.stageOverride = { throw OutOfMemoryError() }
        a.controller.sendTo("b")
        val code = b.code()
        enterCode(a)
        a.controller.enterCode(code)
        assertEquals(SetupState.Done("Couldn't copy the setup.", copied = false), b.awaitDone())
        assertEquals(SetupState.Done("Couldn't copy the setup.", copied = false), a.awaitDone())
        assertEquals(null, b.controller.host.current())
        assertEquals(listOf("Old"), b.playlistNames())
        assertEquals(0, b.importCalls.get())
    }

    @Test fun aFailureOnTheSendingTvTellsTheReceiver() {
        val (a, b) = pair()
        a.exportOverride = { throw IllegalStateException("x") }
        a.controller.sendTo("b")
        val code = b.code()
        enterCode(a)
        a.controller.enterCode(code)
        assertEquals(SetupState.Done("Couldn't copy the setup.", copied = false), a.awaitDone())
        assertEquals(SetupState.Done("Copy cancelled.", copied = false), b.awaitDone())
        assertEquals(null, b.controller.host.current())
        assertEquals(null, a.controller.host.current())
    }

    @Test fun anErrorOnTheSendingTvEndsTheCopyInsteadOfEscaping() {
        val (a, b) = pair()
        a.exportOverride = { throw OutOfMemoryError() }
        a.controller.sendTo("b")
        val code = b.code()
        enterCode(a)
        a.controller.enterCode(code)
        assertEquals(SetupState.Done("Couldn't copy the setup.", copied = false), a.awaitDone())
        assertEquals(SetupState.Done("Copy cancelled.", copied = false), b.awaitDone())
        assertEquals(null, a.controller.host.current())
        assertTrue("the scope survives", scope.coroutineContext[kotlinx.coroutines.Job]!!.isActive)
    }

    @Test fun cancelWhileTheOfferIsOnItsWayStillTellsThePeer() {
        val (a, b) = pair()
        a.controller.sendTo("b")
        a.controller.cancel()
        assertEquals(SetupState.Idle, a.state)
        assertEquals(SetupState.Done("Copy cancelled.", copied = false), b.awaitDone())
        assertEquals(null, b.controller.host.current())
        assertEquals(SetupState.Idle, a.state)
    }

    @Test fun cancelWhileTheAskIsOnItsWayStillTellsThePeer() {
        val (a, b) = pair()
        b.controller.copyFrom("a")
        b.controller.cancel()
        assertEquals(SetupState.Idle, b.state)
        assertEquals(SetupState.Done("Copy cancelled.", copied = false), a.awaitDone())
        assertEquals(null, a.controller.host.current())
    }

    @Test fun aReceiverThatLiesAboutTriesLeftIsCappedAndCancelled() {
        val cancels = java.util.concurrent.atomic.AtomicInteger()
        val bundles = java.util.concurrent.atomic.AtomicInteger()
        val liar = io.github.loopholetv.cast.net.LocalHttpServer(scope)
        suspend fun json(res: io.github.loopholetv.cast.net.HttpResponse, code: Int, vararg fields: Pair<String, Any>) {
            val bytes = SetupWire.write(*fields)
            res.start(code, "application/json", bytes.size.toLong())
            res.body.write(bytes)
        }
        liar.route("POST", SetupHost.OFFER, 4096) { _, res -> json(res, 200, "id" to SetupHost.newRequestId()) }
        liar.route("POST", SetupHost.BUNDLE, SetupHost.MAX_BUNDLE_BYTES) { req, res ->
            req.body.readBytes()
            bundles.incrementAndGet()
            json(res, 409, "error" to "wrong_code", "left" to 5)
        }
        liar.route("POST", SetupHost.CANCEL, 4096) { _, res -> cancels.incrementAndGet(); json(res, 200) }
        try {
            val port = liar.start(listOf(SetupTestTv.LOOPBACK))
            val a = tv("a", "Living room").also { it.addPlaylist("Main") }
            a.seesPeer("liar", "Den", port)
            a.controller.sendTo("liar")
            enterCode(a)
            a.controller.enterCode("1111")
            assertEquals(SetupState.EnterCode("Den", false, 2, "Wrong code. 2 tries left."), a.await { it is SetupState.EnterCode && it.triesLeft != null })
            a.controller.enterCode("2222")
            assertEquals(SetupState.EnterCode("Den", false, 1, "Wrong code. 1 try left."), a.await { it is SetupState.EnterCode && it.triesLeft == 1 })
            a.controller.enterCode("3333")
            assertEquals(SetupState.Done("Copy cancelled.", copied = false), a.awaitDone())
            assertEquals(null, a.controller.host.current())
            SetupTestTv.eventually { cancels.get() == 1 }
            // No fourth try goes out.
            a.controller.enterCode("4444")
            assertEquals(3, bundles.get())
        } finally {
            liar.stop()
        }
    }

    @Test fun blockingIsByTvNotByAddress() {
        val (a, b) = pair()
        val c = tv("c", "Kitchen").also { it.addPlaylist("Main") }
        c.sees(b)
        b.sees(c)
        a.controller.sendTo("b")
        b.code()
        b.controller.cancel()
        a.awaitDone()
        now += SetupHost.MIN_REQUEST_GAP_MS
        // Another TV at the same address is still welcome.
        c.controller.sendTo("b")
        assertEquals("Kitchen", (b.await { it is SetupState.ShowingCode } as SetupState.ShowingCode).fromName)
    }

    @Test fun negativeTriesLeftFromAPeerAreNeverShown() {
        val liar = io.github.loopholetv.cast.net.LocalHttpServer(scope)
        suspend fun json(res: io.github.loopholetv.cast.net.HttpResponse, code: Int, vararg fields: Pair<String, Any>) {
            val bytes = SetupWire.write(*fields)
            res.start(code, "application/json", bytes.size.toLong())
            res.body.write(bytes)
        }
        liar.route("POST", SetupHost.OFFER, 4096) { _, res -> json(res, 200, "id" to SetupHost.newRequestId()) }
        liar.route("POST", SetupHost.BUNDLE, SetupHost.MAX_BUNDLE_BYTES) { req, res ->
            req.body.readBytes()
            json(res, 409, "error" to "wrong_code", "left" to -3)
        }
        liar.route("POST", SetupHost.CANCEL, 4096) { _, res -> json(res, 200) }
        try {
            val port = liar.start(listOf(SetupTestTv.LOOPBACK))
            val a = tv("a", "Living room").also { it.addPlaylist("Main") }
            a.seesPeer("liar", "Den", port)
            a.controller.sendTo("liar")
            enterCode(a)
            a.controller.enterCode("1111")
            assertEquals(SetupState.EnterCode("Den", false, 2, "Wrong code. 2 tries left."), a.await { it is SetupState.EnterCode && it.triesLeft != null })
        } finally {
            liar.stop()
        }
    }

    @Test fun aFailureFromAnEndedCopyLeavesTheNextOneAlone() {
        val (a, b) = pair()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        a.selfIdOverride = {
            if (calls.incrementAndGet() == 1) {
                release.await()
                throw IllegalStateException("x")
            }
            "a"
        }
        a.controller.sendTo("b")
        // The first offer must be the one that waits, whichever coroutine runs first.
        SetupTestTv.eventually { calls.get() == 1 }
        a.controller.cancel()
        a.controller.sendTo("b")
        b.code()
        val asking = enterCode(a)
        val slot = a.controller.host.current()
        // The first offer, long given up, fails only now.
        release.complete(Unit)
        Thread.sleep(300)
        assertEquals(asking, a.state)
        assertTrue(slot != null && a.controller.host.current() === slot)
    }

    @Test fun pausedTvRefusesOffersAndAsksAsBusy() {
        val (a, b) = pair()
        b.controller.setPaused(true)
        a.controller.sendTo("b")
        assertEquals(SetupState.Done(SetupController.busy("Bedroom"), copied = false), a.awaitDone())
        assertEquals(SetupState.Idle, b.state)
        a.controller.cancel()

        // Asking a paused TV for its setup is refused the same way.
        a.controller.setPaused(true)
        b.controller.setPaused(false)
        b.controller.copyFrom("a")
        assertEquals(SetupState.Done(SetupController.busy("Living room"), copied = false), b.awaitDone())
        assertEquals(SetupState.Idle, a.state)
        assertEquals(listOf("Old"), b.playlistNames())
    }

    @Test fun unpausedTvTakesRequestsAgain() {
        val (a, b) = pair()
        b.controller.setPaused(true)
        b.controller.setPaused(false)
        a.controller.sendTo("b")
        val code = b.code()
        enterCode(a)
        a.controller.enterCode(code)
        assertTrue(b.awaitDone().copied)
        assertEquals(listOf("Main", "Film"), b.playlistNames())
    }
}
