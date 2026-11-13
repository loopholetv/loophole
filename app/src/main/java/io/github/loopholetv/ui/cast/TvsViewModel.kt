package io.github.loopholetv.ui.cast

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import io.github.loopholetv.LoopholeApp
import io.github.loopholetv.cast.CastPeer
import io.github.loopholetv.cast.CastState
import io.github.loopholetv.cast.CastStatus
import io.github.loopholetv.cast.CastView
import io.github.loopholetv.cast.NoCast
import io.github.loopholetv.player.PlaybackState
import io.github.loopholetv.setup.NoSetup
import io.github.loopholetv.setup.SetupController
import io.github.loopholetv.setup.SetupView
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

const val NO_TVS_TEXT = "No other TVs found. Open Loophole on another TV on the same network."
const val STOP_CASTING_LABEL = "Stop casting"
const val THIS_TV_LABEL = "This TV"

/** One row of the TVs panel. */
sealed interface TvRow {
    /** A stable key for the list. */
    val key: String

    /** This TV, always first; [canStop] while it casts (OK stops the cast). */
    data class ThisTv(val status: CastStatus, val canStop: Boolean) : TvRow {
        override val key: String get() = "this"
    }

    /** Another TV on the home network; [setup] as in [CastPeer.setup] (0: it can't copy setups). */
    data class Peer(val id: String, val name: String, val status: CastStatus, val setup: Int = 0) : TvRow {
        override val key: String get() = "peer:$id"
    }
}

/** The panel: this TV, then the others by name; [emptyText] when no other TV is around. */
data class TvsUi(val rows: List<TvRow>, val emptyText: String?)

/** What OK on another TV offers. */
enum class TvAction { JOIN, COPY_FROM, SEND_TO }

fun actionLabel(action: TvAction): String = when (action) {
    TvAction.JOIN -> "Join cast"
    TvAction.COPY_FROM -> "Copy this TV's setup"
    TvAction.SEND_TO -> "Send my setup to this TV"
}

/**
 * The menu for one TV: [actions] in order, the first focused ([TvAction.JOIN] only while that TV
 * casts). [olderVersion] is set when that TV can't copy setups; the copy actions then do nothing.
 */
data class TvMenu(val peerId: String, val name: String, val actions: List<TvAction>, val olderVersion: String?)

internal fun tvMenu(peer: CastPeer): TvMenu = TvMenu(
    peerId = peer.id,
    name = peer.name,
    actions = listOfNotNull(TvAction.JOIN.takeIf { peer.status == CastStatus.CASTING }, TvAction.COPY_FROM, TvAction.SEND_TO),
    olderVersion = if (peer.setup < 1) SetupController.olderVersion(peer.name) else null,
)

fun statusLabel(status: CastStatus): String = when (status) {
    CastStatus.CASTING -> "Casting"
    CastStatus.WATCHING -> "Watching"
    CastStatus.IDLE -> "Idle"
}

/** What this TV does: casting, watching (a cast or anything else) or idle. */
internal fun thisTvStatus(state: CastState, playing: Boolean): CastStatus = when (state) {
    is CastState.Casting -> CastStatus.CASTING
    is CastState.Following -> CastStatus.WATCHING
    CastState.Off -> if (playing) CastStatus.WATCHING else CastStatus.IDLE
}

private val byName = compareBy<CastPeer, String>(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.id }

internal fun tvsUi(state: CastState, playing: Boolean, peers: List<CastPeer>): TvsUi {
    val self = TvRow.ThisTv(thisTvStatus(state, playing), canStop = state is CastState.Casting)
    val others = peers.sortedWith(byName).map { TvRow.Peer(it.id, it.name, it.status, it.setup) }
    return TvsUi(listOf(self) + others, emptyText = if (others.isEmpty()) NO_TVS_TEXT else null)
}

/**
 * The TVs panel: this TV and the other TVs on the home network with what each does. OK on
 * another TV opens its [menu]: join its cast, copy its setup here, or send this TV's setup to it.
 * OK on this TV while it casts stops the cast. [playing] is whether anything plays here (this TV
 * shows as watching then).
 */
class TvsViewModel(
    private val cast: CastView = NoCast,
    playing: Flow<Boolean> = flowOf(false),
    private val setup: SetupView = NoSetup,
) : ViewModel() {
    val ui: StateFlow<TvsUi> = combine(cast.state, playing.distinctUntilChanged(), cast.peers, ::tvsUi)
        .stateIn(viewModelScope, SharingStarted.Eagerly, tvsUi(cast.state.value, false, cast.peers.value))

    private val menuFor = MutableStateFlow<String?>(null)

    /** The open menu, kept up to date with what that TV does; null when closed or the TV left. */
    val menu: StateFlow<TvMenu?> = combine(menuFor, cast.peers, ::currentMenu)
        .stateIn(viewModelScope, SharingStarted.Eagerly, currentMenu(menuFor.value, cast.peers.value))

    private fun currentMenu(id: String?, peers: List<CastPeer>): TvMenu? =
        id?.let { peers.firstOrNull { p -> p.id == it } }?.let(::tvMenu)

    /** OK on [row]; true when the panel should close. Another TV's row opens its [menu]. */
    fun onOk(row: TvRow): Boolean = when (row) {
        is TvRow.ThisTv -> {
            if (cast.state.value is CastState.Casting) cast.stopCasting()
            false
        }
        is TvRow.Peer -> {
            menuFor.value = row.id
            false
        }
    }

    /**
     * OK on [action] in the open menu; true when the panel should close (a cast was joined or a
     * copy started). Decided by what the TV does now, not by the menu, which may be a moment old.
     */
    fun onAction(action: TvAction): Boolean {
        val id = menuFor.value ?: return false
        val peer = cast.peers.value.firstOrNull { it.id == id } ?: return false
        val act = when (action) {
            TvAction.JOIN -> peer.status == CastStatus.CASTING
            TvAction.COPY_FROM, TvAction.SEND_TO -> peer.setup >= 1
        }
        if (!act) return false
        when (action) {
            TvAction.JOIN -> cast.join(id)
            TvAction.COPY_FROM -> setup.copyFrom(id)
            TvAction.SEND_TO -> setup.sendTo(id)
        }
        menuFor.value = null
        return true
    }

    /** Back to the list. */
    fun closeMenu() {
        menuFor.value = null
    }

    companion object {
        val Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as LoopholeApp
                val c = app.container
                return TvsViewModel(
                    cast = c.castController,
                    playing = c.playback.state.map { it is PlaybackState.Buffering || it is PlaybackState.Playing },
                    setup = c.setup,
                ) as T
            }
        }
    }
}
