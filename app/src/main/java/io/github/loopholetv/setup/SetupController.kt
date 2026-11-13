package io.github.loopholetv.setup

import io.github.loopholetv.cast.CastPeer
import io.github.loopholetv.cast.PeerTable
import io.github.loopholetv.cast.net.LocalHttpServer
import java.io.File
import java.io.OutputStream
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where a copy between TVs stands, on this TV. */
sealed interface SetupState {
    data object Idle : SetupState

    /**
     * This TV is the one being overwritten: show [code] until [expiresAtMs] (on the controller's
     * clock). [fromName] is the other TV. [toString] leaves the code out.
     */
    class ShowingCode(val code: String, val fromName: String, val expiresAtMs: Long) : SetupState {
        override fun equals(other: Any?) = other is ShowingCode &&
            other.code == code && other.fromName == fromName && other.expiresAtMs == expiresAtMs
        override fun hashCode() = (code.hashCode() * 31 + fromName.hashCode()) * 31 + expiresAtMs.hashCode()
        override fun toString() = "ShowingCode(fromName=$fromName, expiresAtMs=$expiresAtMs)"
    }

    /**
     * This TV sends its setup: ask for the code [peerName] shows. [asked] means [peerName] asked
     * for this TV's setup. After a wrong code, [triesLeft] and [error] say so.
     */
    data class EnterCode(val peerName: String, val asked: Boolean, val triesLeft: Int?, val error: String?) : SetupState

    data class Working(val text: String) : SetupState

    /**
     * The copy is over; [copied] when it went through. [received] when this TV's own setup was
     * replaced (the TV that sent its setup has [copied] without [received]).
     */
    data class Done(val message: String, val copied: Boolean, val received: Boolean = false) : SetupState
}

/**
 * Copies a setup between two TVs on the home network. The TV being overwritten always shows a
 * 4-digit code; the other TV has it typed in, seals its setup with it and sends it. Three wrong
 * codes cancel the copy (counted on both TVs), a request lasts [lifetimeMs], and either TV can
 * cancel. Whenever this TV ends a copy, it tells the other TV.
 *
 * - [sendTo]: this TV's setup goes to the peer, which shows the code; this TV asks for it.
 * - [copyFrom]: the peer's setup comes here. This TV shows the code; the peer asks for it.
 *
 * [host] serves the other TVs' side: call [register] once to add its routes to [server].
 * [export], [import] and [stageFile] are the setup exporter's `export`, the importer's `import`
 * and the playlist file opener's `newTemp`. [onImported] runs after this TV's setup was replaced
 * and the sending TV has its answer (refresh, sessions, back to the guide). [clock] must be
 * monotonic. [selfId] is this TV's cast id and [selfName] its announced name. [hasPlaylists]
 * says whether this TV has a playlist: a TV without one never sends its setup, which would only
 * clear the other TV's playlists, and refuses to be asked for it.
 *
 * Callable from any thread. Crypto, encoding and imports run on [Dispatchers.IO]. No failure in
 * a copy escapes into [scope]: it ends the copy with [FAILED] instead.
 */
class SetupController internal constructor(
    scope: CoroutineScope,
    private val server: LocalHttpServer,
    private val table: PeerTable,
    http: okhttp3.OkHttpClient,
    private val export: suspend (fromName: String) -> SetupExportResult,
    import: suspend (SetupBundle) -> SetupImportResult,
    stageFile: () -> Pair<File, OutputStream>?,
    clock: () -> Long,
    private val selfId: suspend () -> String,
    private val selfName: () -> String,
    private val onImported: suspend (SetupImportResult.Ok) -> Unit,
    private val hasPlaylists: suspend () -> Boolean,
    private val lifetimeMs: Long,
    availableMemory: () -> Long,
    newBuffer: (Int) -> ByteArray,
    collectGarbage: () -> Unit,
    private val noticeMs: Long,
) : SetupView {
    constructor(
        scope: CoroutineScope,
        server: LocalHttpServer,
        table: PeerTable,
        http: okhttp3.OkHttpClient,
        export: suspend (fromName: String) -> SetupExportResult,
        import: suspend (SetupBundle) -> SetupImportResult,
        stageFile: () -> Pair<File, OutputStream>?,
        clock: () -> Long,
        selfId: suspend () -> String,
        selfName: () -> String,
        onImported: suspend (SetupImportResult.Ok) -> Unit,
        hasPlaylists: suspend () -> Boolean,
        lifetimeMs: Long = SetupRequest.LIFETIME_MS,
    ) : this(
        scope, server, table, http, export, import, stageFile, clock, selfId, selfName, onImported, hasPlaylists, lifetimeMs,
        SetupHost::availableHeap, ::ByteArray, { System.gc() }, NOTICE_MS,
    )

    private val client = SetupClient(http)
    private val listener = HostListener()
    val host = SetupHost(
        peerById = ::peer,
        isPeerHost = { h -> table.peers.value.any { it.host == h } },
        clock = clock,
        stageFile = stageFile,
        import = import,
        listener = listener,
        hasPlaylists = hasPlaylists,
        lifetimeMs = lifetimeMs,
        availableMemory = availableMemory,
        newBuffer = newBuffer,
        collectGarbage = collectGarbage,
    )
    private val lock get() = host.lock

    /** The copy's own work: a failure here ends the copy instead of reaching [scope]. */
    private val work = CoroutineScope(
        scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]) +
            CoroutineExceptionHandler { context, _ -> recover(context[Owner]?.slot) },
    )

    /** Fire-and-forget work whose failure changes nothing (telling a peer, after-import actions). */
    private val quiet = CoroutineScope(
        scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]) + CoroutineExceptionHandler { _, _ -> },
    )

    private val _state = MutableStateFlow<SetupState>(SetupState.Idle)
    override val state: StateFlow<SetupState> = _state.asStateFlow()

    /** The copy a coroutine works for, so a failure ends that copy and no other. */
    private class Owner(val slot: SetupHost.Slot) : AbstractCoroutineContextElement(Owner) {
        companion object Key : CoroutineContext.Key<Owner>
    }

    /** The setup being sent; cancelled by [cancel]. Offers and asks are left to finish instead. */
    private var sendJob: Job? = null
    private var timer: Job? = null
    private var registered = false

    /** Adds the setup routes to the server. Later calls do nothing. */
    fun register() = synchronized(lock) {
        if (registered) return
        registered = true
        host.register(server)
    }

    /**
     * While [paused] (this TV is in picture-in-picture, where the setup screens can't show), other
     * TVs' offers and asks are answered busy. A copy already under way carries on.
     */
    fun setPaused(paused: Boolean) = synchronized(lock) { host.paused = paused }

    internal val paused: Boolean get() = synchronized(lock) { host.paused }

    private fun peer(id: String): CastPeer? = table.peers.value.firstOrNull { it.id == id }

    /** "Send my setup to this TV": the peer shows a code, then this TV asks for it. */
    override fun sendTo(peerId: String): Unit = synchronized(lock) {
        if (busyWith(peerId)) return
        val peer = checkPeer(peerId) ?: return
        val s = host.startSending(peer) ?: return
        _state.value = SetupState.Working(connecting(peer.name))
        work.launch(Owner(s)) {
            // An empty setup would only clear the other TV's playlists: it isn't offered at all.
            if (!hasPlaylists()) {
                synchronized(lock) {
                    if (host.end(s, SetupHost.End.CANCELLED)) {
                        _state.value = SetupState.Done(noPlaylists(selfName()), copied = false)
                    }
                }
                return@launch
            }
            // Never cancelled: if this TV gave up meanwhile, the answer is still needed to tell the peer.
            val answer = client.offer(peer, selfId())
            synchronized(lock) {
                val id = answer.id
                if (host.current() !== s) {
                    if (answer.code == 200 && id != null) tell(peer, id, SetupHost.End.CANCELLED)
                    return@launch
                }
                if (answer.code == 200 && id != null) {
                    s.id = id
                    _state.value = SetupState.EnterCode(peer.name, asked = false, triesLeft = null, error = null)
                    expireAfter(s, lifetimeMs + SENDER_GRACE_MS)
                } else {
                    host.end(s, SetupHost.End.FAILED)
                    _state.value = SetupState.Done(refusal(answer, peer.name), copied = false)
                }
            }
        }
        Unit
    }

    /** "Copy this TV's setup" (and onboarding): this TV shows a code; the peer asks for it. */
    override fun copyFrom(peerId: String): Unit = synchronized(lock) {
        if (busyWith(peerId)) return
        val peer = checkPeer(peerId) ?: return
        val s = host.startReceiving(peer) ?: return
        _state.value = SetupState.Working(connecting(peer.name))
        work.launch(Owner(s)) {
            // Never cancelled, as in sendTo.
            val answer = client.ask(peer, selfId(), s.request.id)
            synchronized(lock) {
                if (host.current() !== s) {
                    if (answer.code == 200) tell(peer, s.request.id, SetupHost.End.CANCELLED)
                    return@launch
                }
                if (answer.code == 200) {
                    _state.value = SetupState.ShowingCode(s.request.code, peer.name, s.request.expiresAtMs)
                    expireAfter(s, lifetimeMs)
                } else {
                    host.end(s, SetupHost.End.FAILED)
                    _state.value = SetupState.Done(refusal(answer, peer.name), copied = false)
                }
            }
        }
        Unit
    }

    /**
     * True while a copy is under way. A request for the TV it is with changes nothing; one for any
     * other TV shows [FINISH_FIRST] for a moment, then the copy's own state again.
     */
    private fun busyWith(peerId: String): Boolean {
        if (host.current() == null) return false
        val shown = _state.value
        val name = peer(peerId)?.name
        val sameTv = name != null && when (shown) {
            is SetupState.ShowingCode -> shown.fromName == name
            is SetupState.EnterCode -> shown.peerName == name
            is SetupState.Working -> shown.text == connecting(name)
            else -> false
        }
        if (sameTv || shown is SetupState.Done) return true
        val notice = SetupState.Done(FINISH_FIRST, copied = false)
        _state.value = notice
        quiet.launch {
            delay(noticeMs)
            synchronized(lock) { if (_state.value === notice && host.current() != null) _state.value = shown }
        }
        return true
    }

    /** The peer, when a copy with it can start; otherwise null, with the reason shown. */
    private fun checkPeer(peerId: String): CastPeer? {
        val peer = peer(peerId)
        if (peer == null) {
            _state.value = SetupState.Done(GONE, copied = false)
            return null
        }
        if (peer.setup < 1) {
            _state.value = SetupState.Done(olderVersion(peer.name), copied = false)
            return null
        }
        return peer
    }

    /** The code the peer shows, while this TV is asking for it. Anything but four digits is ignored. */
    override fun enterCode(code: String): Unit = synchronized(lock) {
        val asking = _state.value as? SetupState.EnterCode ?: return
        val s = host.current() as? SetupHost.Slot.Sending ?: return
        val id = s.id ?: return
        if (code.length != CODE_LENGTH || code.any { it !in '0'..'9' }) return
        if (sendJob?.isActive == true) return
        val peer = s.peer
        _state.value = SetupState.Working(COPYING)
        sendJob = work.launch(Owner(s)) {
            val sent = send(peer, id, code)
            synchronized(lock) { settle(s, asking, sent) }
        }
    }

    /** What a send's answer means for this TV. */
    private fun settle(s: SetupHost.Slot.Sending, asking: SetupState.EnterCode, sent: Sent) {
        if (host.current() !== s) return
        val answer = sent.answer
        val peer = s.peer
        val id = s.id ?: return
        when {
            sent.nothingToSend -> {
                // The playlists went while the code was being typed in.
                endAndTell(s, id, SetupHost.End.CANCELLED)
                _state.value = SetupState.Done(noPlaylists(selfName()), copied = false)
            }
            answer.code == 200 -> {
                host.finish(s)
                stopTimer()
                _state.value = SetupState.Done(copiedTo(peer.name, sent.skipped), copied = true)
            }
            answer.code == 409 && answer.error == SetupHost.WRONG_CODE -> {
                s.wrongCodes++
                val left = SetupRequest.MAX_ATTEMPTS - s.wrongCodes
                if (left <= 0) {
                    // Out of tries, whatever the other TV says.
                    endAndTell(s, id, SetupHost.End.CANCELLED)
                    _state.value = SetupState.Done(CANCELLED, copied = false)
                } else {
                    val shown = minOf(left, answer.left ?: left).coerceAtLeast(0)
                    _state.value = asking.copy(triesLeft = shown, error = wrongCode(shown))
                }
            }
            else -> {
                val reason = if (answer.error == SetupHost.End.EXPIRED.wire) SetupHost.End.EXPIRED else SetupHost.End.CANCELLED
                endAndTell(s, id, reason)
                _state.value = SetupState.Done(sendFailure(answer, peer.name), copied = false)
            }
        }
    }

    private fun endAndTell(s: SetupHost.Slot, id: String, reason: SetupHost.End) {
        host.end(s, reason)
        stopTimer()
        tell(s.peer, id, reason)
    }

    /** [nothingToSend]: the export had no playlists, so nothing was sent. */
    private class Sent(val answer: SetupClient.Answer, val skipped: Int, val nothingToSend: Boolean = false)

    /**
     * Exports, seals and sends this TV's setup; one with no playlists isn't sent. The export's
     * staged files go whatever happens.
     */
    private suspend fun send(peer: CastPeer, id: String, code: String): Sent = withContext(Dispatchers.IO) {
        val exported = try {
            export(selfName())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return@withContext Sent(SetupClient.Answer(500, null), 0)
        }
        try {
            if (exported.bundle.playlists.isEmpty()) {
                return@withContext Sent(SetupClient.Answer(0, null), 0, nothingToSend = true)
            }
            val sealed = try {
                val plain = SetupJson.encode(exported.bundle)
                try {
                    SetupCrypto.seal(plain, code, SetupCrypto.aadFor(id))
                } finally {
                    plain.fill(0)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@withContext Sent(SetupClient.Answer(500, null), exported.skippedLargeFiles)
            }
            Sent(client.sendBundle(peer, id, sealed), exported.skippedLargeFiles)
        } finally {
            withContext(NonCancellable) { exported.bundle.discardFiles() }
        }
    }

    /**
     * Stops the copy: tells the other TV and goes back to [SetupState.Idle] (from [SetupState.Done]
     * too). An offer or ask still on its way is left to arrive, and the other TV is told then.
     * Cancelling an offered code also refuses further offers from that TV until the app restarts.
     * Does nothing once a received setup is being imported.
     */
    override fun cancel(): Unit = synchronized(lock) {
        val s = host.current()
        if (s is SetupHost.Slot.Receiving && s.accepted) return
        sendJob?.cancel()
        sendJob = null
        stopTimer()
        if (s != null) {
            val showing = _state.value is SetupState.ShowingCode
            host.end(s, SetupHost.End.CANCELLED)
            if (s is SetupHost.Slot.Receiving && s.offered && showing) host.block(s.peer)
            s.id?.let { id -> tell(s.peer, id, SetupHost.End.CANCELLED) }
        }
        _state.value = SetupState.Idle
    }

    private fun expireAfter(s: SetupHost.Slot, ms: Long) {
        stopTimer()
        timer = work.launch(Owner(s)) {
            delay(ms)
            synchronized(lock) {
                if (!host.end(s, SetupHost.End.EXPIRED)) return@launch
                sendJob?.cancel()
                _state.value = SetupState.Done(TIMED_OUT, copied = false)
                s.id?.let { id -> tell(s.peer, id, SetupHost.End.EXPIRED) }
            }
        }
    }

    private fun stopTimer() {
        timer?.cancel()
        timer = null
    }

    /** Best effort: lets [peer] stop showing request [id]. */
    private fun tell(peer: CastPeer, id: String, reason: SetupHost.End) {
        quiet.launch { client.cancel(peer, id, reason) }
    }

    /**
     * A coroutine of copy [owner] failed unexpectedly: free its slot, tell the peer, show the
     * failure. A failure from a copy that has already ended changes nothing.
     */
    private fun recover(owner: SetupHost.Slot?) = synchronized(lock) {
        val s = host.current()
        if (s == null || s !== owner) return
        // A received setup being imported finishes or fails on its own.
        if (s is SetupHost.Slot.Receiving && s.accepted) return
        sendJob = null
        stopTimer()
        if (host.abandon(s, notify = false)) s.id?.let { tell(s.peer, it, SetupHost.End.CANCELLED) }
        _state.value = SetupState.Done(FAILED, copied = false)
    }

    /** The other TVs' side, reported by [host] with its lock held. */
    private inner class HostListener : SetupHost.Listener {
        override fun offered(slot: SetupHost.Slot.Receiving) {
            _state.value = SetupState.ShowingCode(slot.request.code, slot.peer.name, slot.request.expiresAtMs)
            expireAfter(slot, lifetimeMs)
        }

        override fun asked(slot: SetupHost.Slot.Sending) {
            _state.value = SetupState.EnterCode(slot.peer.name, asked = true, triesLeft = null, error = null)
            expireAfter(slot, lifetimeMs + SENDER_GRACE_MS)
        }

        override fun copying(slot: SetupHost.Slot.Receiving) {
            stopTimer()
            _state.value = SetupState.Working(COPYING)
        }

        override fun copied(slot: SetupHost.Slot.Receiving, result: SetupImportResult.Ok) {
            _state.value = SetupState.Done(copiedFrom(slot.peer.name, result.skippedFiles), copied = true, received = true)
            quiet.launch(NonCancellable) { onImported(result) }
        }

        override fun ended(slot: SetupHost.Slot, reason: SetupHost.End) {
            sendJob?.cancel()
            sendJob = null
            stopTimer()
            val message = when (reason) {
                SetupHost.End.CANCELLED -> CANCELLED
                SetupHost.End.EXPIRED -> TIMED_OUT
                SetupHost.End.FAILED -> FAILED
            }
            _state.value = SetupState.Done(message, copied = false)
        }
    }

    override fun toString() = "SetupController(state=${_state.value}, host=$host)"

    companion object {
        private const val CODE_LENGTH = 4

        /** The sending TV waits a little longer than the request lives, so the receiver's end arrives first. */
        private const val SENDER_GRACE_MS = 5_000L

        /** How long [FINISH_FIRST] shows before the copy's own state comes back. */
        const val NOTICE_MS = 2_500L

        const val CANCELLED = "Copy cancelled."
        const val TIMED_OUT = "The request timed out."
        const val FAILED = "Couldn't copy the setup."
        const val GONE = "That TV is no longer on the network."
        const val COPYING = "Copying the setup…"
        const val FINISH_FIRST = "Finish or cancel the current copy first."

        fun connecting(name: String) = "Connecting to $name…"
        fun olderVersion(name: String) = "$name is on an older version of Loophole."
        fun unreachable(name: String) = "Couldn't reach $name."
        fun busy(name: String) = "$name is busy. Try again in a moment."
        fun refused(name: String) = "$name isn't taking requests right now. Try again later."
        fun noPlaylists(name: String) = "$name has no playlists to copy."

        fun wrongCode(left: Int) = if (left == 1) "Wrong code. 1 try left." else "Wrong code. $left tries left."

        fun skippedFiles(n: Int) =
            if (n == 1) "1 playlist from a file was too large to copy." else "$n playlists from files were too large to copy."

        fun copiedFrom(name: String, skipped: Int) = withSkipped("Setup copied from $name.", skipped)
        fun copiedTo(name: String, skipped: Int) = withSkipped("Setup copied to $name.", skipped)

        private fun withSkipped(message: String, skipped: Int) =
            if (skipped > 0) "$message ${skippedFiles(skipped)}" else message

        /** Why an offer or ask didn't go through. */
        internal fun refusal(answer: SetupClient.Answer, name: String) = when (answer.code) {
            0 -> unreachable(name)
            404, 405 -> olderVersion(name)
            409 -> if (answer.error == SetupHost.NO_PLAYLISTS) noPlaylists(name) else busy(name)
            403, 429 -> refused(name)
            else -> FAILED
        }

        /** Why sending the setup didn't go through. */
        internal fun sendFailure(answer: SetupClient.Answer, name: String) = when {
            answer.code == 0 -> unreachable(name)
            answer.error == SetupHost.End.EXPIRED.wire -> TIMED_OUT
            answer.code == 410 -> CANCELLED
            else -> FAILED
        }
    }
}
