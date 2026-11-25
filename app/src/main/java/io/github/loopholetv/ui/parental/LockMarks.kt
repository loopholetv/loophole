package io.github.loopholetv.ui.parental

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import io.github.loopholetv.data.db.ChannelRef
import io.github.loopholetv.data.db.LockDao
import io.github.loopholetv.data.parental.LockState
import io.github.loopholetv.ui.theme.Tokens
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** Whether Lock and Unlock rows are offered: there is no PIN, or it has been entered. */
fun lockAvailable(state: LockState): Boolean = state is LockState.NoPin || state is LockState.Unlocked

/** The side menu's label for the lock item, or null when it isn't listed (no PIN). */
fun lockRailLabel(state: LockState): String? = when (state) {
    LockState.NoPin -> null
    LockState.Locked -> "Show locked channels"
    is LockState.Unlocked -> "Hide locked channels"
}

/**
 * What choosing the side menu's lock item does: ask for the PIN while locked, lock again while
 * unlocked, nothing without a PIN.
 */
suspend fun lockRailAction(state: LockState, ask: suspend () -> Boolean, relock: suspend () -> Unit) {
    when (state) {
        LockState.NoPin -> Unit
        LockState.Locked -> ask()
        is LockState.Unlocked -> relock()
    }
}

/**
 * Which things carry their own lock, for rows to mark with a padlock and for menus to say "Lock" or
 * "Unlock". One set per screen rather than a lookup per row. The sets are empty unless the PIN has
 * been entered: locked things aren't listed otherwise, so there is nothing to mark.
 *
 * [channels] holds live channels and films by their key, [groups] groups and categories by title,
 * [shows] shows by their key.
 */
@Immutable
class LockMarks(
    /** Lock and Unlock rows may be offered (see [lockAvailable]). */
    val lockAvailable: Boolean = false,
    /** A PIN exists and has been entered (the film and show pages offer their lock only then). */
    val unlocked: Boolean = false,
    private val channels: Set<ChannelRef> = emptySet(),
    private val groups: Set<ChannelRef> = emptySet(),
    private val shows: Set<ChannelRef> = emptySet(),
) {
    /** The channel or film has its own lock. */
    fun channel(playlistId: Long, key: String): Boolean = channels.isNotEmpty() && ChannelRef(playlistId, key) in channels

    fun group(playlistId: Long, title: String): Boolean = groups.isNotEmpty() && ChannelRef(playlistId, title) in groups

    /** Some playlist's group of that title is locked. */
    fun categoryTitle(title: String): Boolean = groups.any { it.key == title }

    fun show(playlistId: Long, key: String): Boolean = shows.isNotEmpty() && ChannelRef(playlistId, key) in shows

    /** The channel's own lock or its group's lock. */
    fun channelOrGroup(playlistId: Long, key: String, groupTitle: String): Boolean =
        channel(playlistId, key) || group(playlistId, groupTitle)

    companion object {
        val NONE = LockMarks()

        fun of(
            state: LockState,
            channels: Collection<ChannelRef>,
            groups: Collection<ChannelRef>,
            shows: Collection<ChannelRef>,
        ): LockMarks {
            val open = state is LockState.Unlocked
            return LockMarks(
                lockAvailable = lockAvailable(state),
                unlocked = open,
                channels = if (open) channels.toSet() else emptySet(),
                groups = if (open) groups.toSet() else emptySet(),
                shows = if (open) shows.toSet() else emptySet(),
            )
        }
    }
}

/** [LockMarks] following the lock [state] and the locked rows. */
fun lockMarksFlow(state: Flow<LockState>, dao: LockDao): Flow<LockMarks> =
    combine(state, dao.lockedChannels(), dao.lockedGroups(), dao.lockedShows()) { s, c, g, sh -> LockMarks.of(s, c, g, sh) }

val LocalLockMarks = compositionLocalOf { LockMarks.NONE }

private const val LOCK_PATH =
    "M18,8h-1V6c0,-2.76 -2.24,-5 -5,-5S7,3.24 7,6v2H6c-1.1,0 -2,0.9 -2,2v10c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V10" +
        "c0,-1.1 -0.9,-2 -2,-2zM12,17c-1.1,0 -2,-0.9 -2,-2s0.9,-2 2,-2 2,0.9 2,2 -0.9,2 -2,2zM15.1,8H8.9V6" +
        "c0,-1.71 1.39,-3.1 3.1,-3.1 1.71,0 3.1,1.39 3.1,3.1v2z"

private val PadlockIcon: ImageVector by lazy {
    ImageVector.Builder("Locked", 24.dp, 24.dp, 24f, 24f)
        .addPath(pathData = addPathNodes(LOCK_PATH), fill = SolidColor(Color.White))
        .build()
}

/** The small padlock drawn after the name of something locked. */
@Composable
fun Padlock(modifier: Modifier = Modifier, tint: Color = Tokens.Accent) {
    Icon(PadlockIcon, contentDescription = "Locked", tint = tint, modifier = modifier.size(16.dp))
}
