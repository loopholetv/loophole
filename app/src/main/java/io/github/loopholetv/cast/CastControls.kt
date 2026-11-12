package io.github.loopholetv.cast

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** What this TV is doing with casting. */
sealed interface CastState {
    data object Off : CastState

    /** This TV shares what it plays; [followers] other TVs are watching. */
    data class Casting(val followers: Int) : CastState

    /** This TV shows [peer]'s cast. */
    data class Following(val peer: CastPeer) : CastState
}

/** Another TV started casting while this one plays something: offer to watch it. */
data class CastInvite(val peer: CastPeer)

/** Starting and stopping a cast from this TV (the player's quick menu, the guide's banner). */
interface CastControls {
    val state: StateFlow<CastState>
    fun startCasting()
    fun stopCasting(reason: StopReason = StopReason.STOPPED)
}

/** Everything a screen needs about other TVs: the list, the invitation, end messages and following. */
interface CastView : CastControls {
    val peers: StateFlow<List<CastPeer>>
    val invite: StateFlow<CastInvite?>

    /** Short messages to show once, for example when a followed cast ends. */
    val messages: SharedFlow<String>
    fun join(peerId: String)
    fun leave()
    fun ignoreInvite()
}

/** No casting at all: always [CastState.Off], no TVs, nothing happens. A default for view models. */
object NoCast : CastView {
    override val state: StateFlow<CastState> = MutableStateFlow<CastState>(CastState.Off).asStateFlow()
    override val peers: StateFlow<List<CastPeer>> = MutableStateFlow<List<CastPeer>>(emptyList()).asStateFlow()
    override val invite: StateFlow<CastInvite?> = MutableStateFlow<CastInvite?>(null).asStateFlow()
    override val messages: SharedFlow<String> = MutableSharedFlow<String>().asSharedFlow()
    override fun startCasting() {}
    override fun stopCasting(reason: StopReason) {}
    override fun join(peerId: String) {}
    override fun leave() {}
    override fun ignoreInvite() {}
}
