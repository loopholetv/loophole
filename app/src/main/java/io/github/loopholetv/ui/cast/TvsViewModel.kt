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
import kotlinx.coroutines.flow.Flow
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

    /** Another TV on the home network. */
    data class Peer(val id: String, val name: String, val status: CastStatus) : TvRow {
        override val key: String get() = "peer:$id"
    }
}

/** The panel: this TV, then the others by name; [emptyText] when no other TV is around. */
data class TvsUi(val rows: List<TvRow>, val emptyText: String?)

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
    val others = peers.sortedWith(byName).map { TvRow.Peer(it.id, it.name, it.status) }
    return TvsUi(listOf(self) + others, emptyText = if (others.isEmpty()) NO_TVS_TEXT else null)
}

/**
 * The TVs panel: this TV and the other TVs on the home network with what each does. OK on a TV
 * that casts joins it; OK on this TV while it casts stops the cast. [playing] is whether anything
 * plays here (this TV shows as watching then).
 */
class TvsViewModel(
    private val cast: CastView = NoCast,
    playing: Flow<Boolean> = flowOf(false),
) : ViewModel() {
    val ui: StateFlow<TvsUi> = combine(cast.state, playing.distinctUntilChanged(), cast.peers, ::tvsUi)
        .stateIn(viewModelScope, SharingStarted.Eagerly, tvsUi(cast.state.value, false, cast.peers.value))

    /**
     * OK on [row]; true when the panel should close (a cast was joined). Decided by what the TV
     * does now, not by the row, which may be a moment old.
     */
    fun onOk(row: TvRow): Boolean = when (row) {
        is TvRow.ThisTv -> {
            if (cast.state.value is CastState.Casting) cast.stopCasting()
            false
        }
        is TvRow.Peer -> {
            val casting = cast.peers.value.any { it.id == row.id && it.status == CastStatus.CASTING }
            if (casting) cast.join(row.id)
            casting
        }
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
                ) as T
            }
        }
    }
}
