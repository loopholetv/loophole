package io.github.loopholetv.setup

import io.github.loopholetv.cast.CastPeer
import io.github.loopholetv.cast.CastStatus
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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

/** The setup routes as another TV sees them, driven with plain HTTP. */
@RunWith(RobolectricTestRunner::class)
class SetupHostTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Volatile private var now = 1_000_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
    private val tvs = ArrayList<SetupTestTv>()

    @After fun tearDown() {
        tvs.forEach { it.close() }
        scope.cancel()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    /** The TV under test; it knows "x" at this machine's address and "far" elsewhere on the network. */
    private fun tv(): SetupTestTv = SetupTestTv("b", "Bedroom", tmp.root, scope, http, { now }).also {
        tvs += it
        it.addPlaylist("Old")
        it.seesPeer("x", "Living room", 1)
        it.seesPeer("far", "Kitchen", 1, host = "192.168.1.50")
    }

    private class Reply(val code: Int, val fields: SetupWire.Fields?)

    private fun post(tv: SetupTestTv, path: String, body: ByteArray, type: String = "application/json"): Reply {
        val request = Request.Builder().url("http://127.0.0.1:${tv.port}$path").post(body.toRequestBody(type.toMediaType())).build()
        return http.newCall(request).execute().use { Reply(it.code, SetupWire.parse(it.body.bytes())) }
    }

    private fun offer(tv: SetupTestTv, from: String = "x") = post(tv, SetupHost.OFFER, SetupWire.write("from" to from))

    private fun sealed(code: String, id: String) =
        SetupCrypto.seal(SetupJson.encode(SetupBundle(fromName = "x", playlists = emptyList(), favourites = emptyList(), settings = settings())), code, SetupCrypto.aadFor(id))

    private fun settings() = SetupSettings.from(io.github.loopholetv.data.settings.AppSettings())

    private fun bundle(tv: SetupTestTv, id: String?, body: ByteArray) =
        post(tv, SetupHost.BUNDLE + (id?.let { "?id=$it" } ?: ""), body, "application/octet-stream")

    @Test fun offerReturnsAnIdAndShowsTheCode() {
        val b = tv()
        val reply = offer(b)
        assertEquals(200, reply.code)
        val id = reply.fields!!.string("id")!!
        assertTrue(SetupHost.ID.matches(id))
        val showing = b.await { it is SetupState.ShowingCode } as SetupState.ShowingCode
        assertEquals("the name comes from the peer list", "Living room", showing.fromName)
        assertEquals(200, bundle(b, id, sealed(showing.code, id)).code)
        assertEquals(SetupState.Done("Setup copied from Living room.", copied = true, received = true), b.awaitDone())
        assertEquals(emptyList<String>(), b.playlistNames())
    }

    @Test fun offerFromAnUnknownTvOrAnotherAddressIs403() {
        val b = tv()
        assertEquals(403, offer(b, from = "stranger").code)
        assertEquals(403, offer(b, from = "far").code)
        assertEquals(SetupState.Idle, b.state)
        assertNull(b.controller.host.current())
    }

    @Test fun malformedOfferIs400() {
        val b = tv()
        assertEquals(400, post(b, SetupHost.OFFER, "not json".toByteArray()).code)
        assertEquals(400, post(b, SetupHost.OFFER, SetupWire.write("other" to "x")).code)
        assertEquals(413, post(b, SetupHost.OFFER, ByteArray(5_000)).code)
        assertEquals(400, post(b, SetupHost.ASK, SetupWire.write("from" to "x", "id" to "short")).code)
        assertEquals(SetupState.Idle, b.state)
    }

    @Test fun secondOfferWhileBusyIs409() {
        val b = tv()
        assertEquals(200, offer(b).code)
        val second = offer(b)
        assertEquals(409, second.code)
        assertEquals("busy", second.fields!!.string("error"))
        val ask = post(b, SetupHost.ASK, SetupWire.write("from" to "x", "id" to SetupHost.newRequestId()))
        assertEquals(409, ask.code)
    }

    @Test fun anAskToATvWithNoPlaylistsIs409NoPlaylists() {
        val b = SetupTestTv("b", "Bedroom", tmp.root, scope, http, { now }).also {
            tvs += it
            it.seesPeer("x", "Living room", 1)
        }
        val ask = post(b, SetupHost.ASK, SetupWire.write("from" to "x", "id" to SetupHost.newRequestId()))
        assertEquals(409, ask.code)
        assertEquals("no_playlists", ask.fields!!.string("error"))
        assertEquals(SetupState.Idle, b.state)
        assertNull(b.controller.host.current())
        // Once it has one, it can be asked.
        b.addPlaylist("Main")
        now += SetupHost.MIN_REQUEST_GAP_MS
        assertEquals(200, post(b, SetupHost.ASK, SetupWire.write("from" to "x", "id" to SetupHost.newRequestId())).code)
    }

    @Test fun aTvWithNoPlaylistsRefusesStrangersAndHastyAsksAsBefore() {
        val b = SetupTestTv("b", "Bedroom", tmp.root, scope, http, { now }).also {
            tvs += it
            it.seesPeer("x", "Living room", 1)
            it.seesPeer("far", "Kitchen", 1, host = "192.168.1.50")
        }
        fun ask(from: String) = post(b, SetupHost.ASK, SetupWire.write("from" to from, "id" to SetupHost.newRequestId()))

        // Who isn't admitted learns nothing about this TV's playlists.
        val stranger = ask("stranger")
        assertEquals(403, stranger.code)
        assertNull(stranger.fields?.string("error"))
        assertEquals(403, ask("far").code)

        // An admitted TV is told; asking again at once is refused as too soon, as for any ask.
        assertEquals("no_playlists", ask("x").fields!!.string("error"))
        assertEquals(429, ask("x").code)
        assertEquals(SetupState.Idle, b.state)
    }

    @Test fun offersAreRateLimitedPerAddress() {
        val b = tv()
        val id = offer(b).fields!!.string("id")!!
        assertEquals(200, post(b, SetupHost.CANCEL + "?id=$id", SetupWire.write("reason" to "cancelled")).code)
        assertEquals(SetupState.Done("Copy cancelled.", copied = false), b.awaitDone())
        assertEquals(429, offer(b).code)
        now += SetupHost.MIN_REQUEST_GAP_MS
        assertEquals(200, offer(b).code)
    }

    @Test fun bundleAndCancelWithoutOrWithUnknownIdAre404() {
        val b = tv()
        offer(b)
        val code = b.code()
        val body = sealed(code, "0123456789abcdef0123456789abcdef")
        assertEquals(404, bundle(b, null, body).code)
        assertEquals(404, bundle(b, "0123456789abcdef0123456789abcdef", body).code)
        assertEquals(404, post(b, SetupHost.CANCEL, SetupWire.write("reason" to "cancelled")).code)
        assertEquals(404, post(b, SetupHost.CANCEL + "?id=nope", SetupWire.write("reason" to "cancelled")).code)
        assertEquals(0, b.importCalls.get())
        assertTrue("the real request is untouched", b.state is SetupState.ShowingCode)
    }

    @Test fun bundleForAnEndedRequestIs410() {
        val b = tv()
        // Cancelled on this TV.
        val cancelled = offer(b).fields!!.string("id")!!
        val code = b.code()
        b.controller.cancel()
        val afterCancel = bundle(b, cancelled, sealed(code, cancelled))
        assertEquals(410, afterCancel.code)
        assertEquals("cancelled", afterCancel.fields!!.string("error"))
        assertEquals(410, post(b, SetupHost.CANCEL + "?id=$cancelled", SetupWire.write("reason" to "cancelled")).code)
        assertEquals(0, b.importCalls.get())
    }

    @Test fun bundleForAnExpiredOrUsedRequestIs410() {
        val b = tv()
        val expired = offer(b).fields!!.string("id")!!
        val code = b.code()
        now += SetupRequest.LIFETIME_MS
        repeat(2) {
            val reply = bundle(b, expired, sealed(code, expired))
            assertEquals(410, reply.code)
            assertEquals("expired", reply.fields!!.string("error"))
        }
        assertEquals(SetupState.Done("The request timed out.", copied = false), b.awaitDone())
        assertEquals(0, b.importCalls.get())

        now += SetupHost.MIN_REQUEST_GAP_MS
        val used = offer(b).fields!!.string("id")!!
        val usedCode = b.code()
        assertEquals(200, bundle(b, used, sealed(usedCode, used)).code)
        val again = bundle(b, used, sealed(usedCode, used))
        assertEquals(410, again.code)
        assertEquals(1, b.importCalls.get())
    }

    @Test fun wrongCodeIs409WithTriesLeftAndGarbageIs400() {
        val b = tv()
        val id = offer(b).fields!!.string("id")!!
        val code = b.code()
        val wrong = if (code == "0000") "0001" else "0000"
        val reply = bundle(b, id, sealed(wrong, id))
        assertEquals(409, reply.code)
        assertEquals("wrong_code", reply.fields!!.string("error"))
        assertEquals(2, reply.fields.int("left"))
        assertEquals("too short to be sealed", 400, bundle(b, id, ByteArray(8)).code)
        assertEquals(0, b.importCalls.get())
        assertTrue(b.state is SetupState.ShowingCode)
    }

    @Test fun rightCodeButGarbageInsideIsRefused() {
        val b = tv()
        val id = offer(b).fields!!.string("id")!!
        val code = b.code()
        val reply = bundle(b, id, SetupCrypto.seal("not a setup".toByteArray(), code, SetupCrypto.aadFor(id)))
        assertEquals(400, reply.code)
        assertEquals(SetupState.Done("Couldn't copy the setup.", copied = false), b.awaitDone())
        assertEquals(0, b.importCalls.get())
        assertEquals(listOf("Old"), b.playlistNames())
    }

    @Test fun cancelCarriesTheReason() {
        val b = tv()
        val id = offer(b).fields!!.string("id")!!
        b.code()
        assertEquals(200, post(b, SetupHost.CANCEL + "?id=$id", SetupWire.write("reason" to "expired")).code)
        assertEquals(SetupState.Done("The request timed out.", copied = false), b.awaitDone())
    }

    @Test fun noToStringPrintsCodesIdsOrBundles() {
        val code = "4821"
        val id = "0123456789abcdef0123456789abcdef"
        val peer = CastPeer("x", "Living room", "127.0.0.1", 1, CastStatus.IDLE, true, null, "s3cret-token", null, 0, setup = 1)
        val request = SetupRequest(id, code, { 0L }, 1_000L)
        val bundle = SetupBundle(fromName = "x", playlists = emptyList(), favourites = emptyList(), settings = settings())
        val plain = SetupJson.encode(bundle)
        val texts = listOf(
            SetupState.ShowingCode(code, "Living room", 1_000L),
            SetupState.EnterCode("Living room", true, 2, SetupController.wrongCode(2)),
            request,
            request.tryOpen(SetupCrypto.seal(plain, code, SetupCrypto.aadFor(id))),
            SetupHost.Slot.Receiving(peer, SetupRequest(id, code, { 0L }, 1_000L), offered = true),
            SetupHost.Slot.Sending(peer, id),
            SetupClient.Answer(200, SetupWire.parse(SetupWire.write("id" to id))),
            SetupWire.parse(SetupWire.write("id" to id)),
            tv().controller,
        ).map { it.toString() }
        val plainText = String(plain)
        for (text in texts) {
            assertFalse(text, text.contains(code))
            assertFalse(text, text.contains(id))
            assertFalse(text, text.contains("s3cret-token"))
            assertFalse(text, plainText.length > 8 && text.contains(plainText))
        }
    }

    @Test fun wrongCodesCountEvenWhenTheRequestExpires() {
        val b = tv()
        repeat(3) {
            val id = offer(b).fields!!.string("id")!!
            val code = b.code()
            val wrong = if (code == "0000") "0001" else "0000"
            repeat(2) { assertEquals(409, bundle(b, id, sealed(wrong, id)).code) }
            now += SetupRequest.LIFETIME_MS
            assertEquals(410, bundle(b, id, sealed(code, id)).code)
            b.awaitDone()
        }
        assertEquals("six wrong codes throttle the address", 429, offer(b).code)
        now += SetupHost.THROTTLE_MS
        assertEquals(200, offer(b).code)
    }

    @Test fun unknownAddressIsRefusedBeforeItsBodyArrives() {
        val b = SetupTestTv("b", "Bedroom", tmp.root, scope, http, { now }).also { tvs += it }
        b.seesPeer("far", "Kitchen", 1, host = "192.168.1.50")
        java.net.Socket(SetupTestTv.LOOPBACK, b.port).use { s ->
            s.soTimeout = 3_000
            // A body that never finishes: the refusal can't wait for it.
            s.getOutputStream().write("POST /setup/offer HTTP/1.1\r\nContent-Length: 100\r\n\r\n{".toByteArray())
            s.getOutputStream().flush()
            val reply = s.getInputStream().bufferedReader(Charsets.ISO_8859_1).readLine()
            assertTrue(reply, reply.startsWith("HTTP/1.1 403"))
        }
    }

    @Test fun cancelFromTheWrongTvIsRefusedBeforeItsBody() {
        val b = tv()
        offer(b)
        b.code()
        java.net.Socket(SetupTestTv.LOOPBACK, b.port).use { s ->
            s.soTimeout = 3_000
            s.getOutputStream().write("POST /setup/cancel?id=nope HTTP/1.1\r\nContent-Length: 100\r\n\r\n{".toByteArray())
            s.getOutputStream().flush()
            val reply = s.getInputStream().bufferedReader(Charsets.ISO_8859_1).readLine()
            assertTrue(reply, reply.startsWith("HTTP/1.1 404"))
        }
        assertTrue(b.state is SetupState.ShowingCode)
    }

    @Test fun ipv4MappedAddressesReadAsIpv4() {
        val mapped = ByteArray(16).also {
            it[10] = 0xFF.toByte()
            it[11] = 0xFF.toByte()
            it[12] = 192.toByte(); it[13] = 168.toByte(); it[14] = 1; it[15] = 9
        }
        val v6 = java.net.Inet6Address.getByAddress(null, mapped, null as java.net.NetworkInterface?)
        assertTrue(v6 is java.net.Inet6Address)
        assertEquals("192.168.1.9", SetupHost.ipv4Text(v6))
        assertEquals("127.0.0.1", SetupHost.ipv4Text(SetupTestTv.LOOPBACK))
        assertNull(SetupHost.ipv4Text(java.net.InetAddress.getByName("fe80::1")))
    }

    @Test fun memoryCheckScalesWithTheSetup() {
        val mb = 1024L * 1024
        var collected = 0
        val collect = { collected++; Unit }
        // A TV with a 128 MB heap and only 10 MB free still takes an ordinary setup.
        assertTrue(SetupHost.enoughMemory(300 * 1024L, { 10 * mb }, collect))
        assertEquals("no collection when there is room", 0, collected)
        // A large setup needs room for itself, its decrypted copy and as much again.
        assertFalse(SetupHost.enoughMemory(40 * mb, { 100 * mb }, collect))
        assertEquals("collected once before refusing", 1, collected)
        assertTrue(SetupHost.enoughMemory(40 * mb, { 120 * mb }, collect))
        // A reading that is short only until garbage is collected passes.
        var free = 1 * mb
        assertTrue(SetupHost.enoughMemory(300 * 1024L, { free }, { free = 64 * mb }))
    }

    @Test fun anOrdinarySetupGetsInOnALowHeapTv() {
        val b = tv()
        b.availableMemory = 6L * 1024 * 1024
        val id = offer(b).fields!!.string("id")!!
        val code = b.code()
        assertEquals(200, bundle(b, id, sealed(code, id)).code)
        assertEquals(0, b.gcCalls.get())
        assertTrue(b.awaitDone().copied)
    }

    @Test fun aShortHeapIsMeasuredAgainAfterACollection() {
        val b = tv()
        b.availableMemory = 1
        b.availableAfterGc = 64L * 1024 * 1024
        val id = offer(b).fields!!.string("id")!!
        val code = b.code()
        assertEquals(200, bundle(b, id, sealed(code, id)).code)
        assertEquals(1, b.gcCalls.get())
        assertTrue(b.awaitDone().copied)
    }
}
