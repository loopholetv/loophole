package io.github.loopholetv.ui.manage

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import io.github.loopholetv.LoopholeApp
import io.github.loopholetv.data.db.ChannelEntity
import io.github.loopholetv.data.db.ChannelRef
import io.github.loopholetv.data.db.GroupSort
import io.github.loopholetv.data.db.displayName
import io.github.loopholetv.data.db.ref
import io.github.loopholetv.data.repo.ChannelEdits
import io.github.loopholetv.data.repo.SHIFT_CHOICES
import io.github.loopholetv.ui.common.ChannelOption
import io.github.loopholetv.ui.common.OPTION_MOVE
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.math.abs

// --- Labels -----------------------------------------------------------------------------------

/** Guide time shift as shown: "Off", "+0:30", "−1:00" (U+2212 minus), "+12:00". */
fun shiftText(minutes: Int): String {
    if (minutes == 0) return "Off"
    val sign = if (minutes > 0) "+" else "−"
    val m = abs(minutes)
    return "$sign${m / 60}:${(m % 60).toString().padStart(2, '0')}"
}

/** The "Sort channels" choices, in the order listed. */
val SORT_CHOICES: List<GroupSort> = listOf(GroupSort.PLAYLIST, GroupSort.NAME, GroupSort.CUSTOM)

fun sortText(sort: GroupSort): String = when (sort) {
    GroupSort.PLAYLIST -> "Playlist order"
    GroupSort.NAME -> "Name A–Z"
    GroupSort.CUSTOM -> "My order"
}

/** One of the channel edit options. */
sealed interface ChannelEdit {
    data object Hide : ChannelEdit
    data object Show : ChannelEdit
    data object Rename : ChannelEdit
    data object RestoreName : ChannelEdit
    data object Logo : ChannelEdit
    data object RestoreLogo : ChannelEdit
    data object ChooseGuide : ChannelEdit
    data object TimeShift : ChannelEdit
    data object Move : ChannelEdit
}

/**
 * The edit rows of a channel's options, in order. [hidden] is the channel's own hidden edit
 * (`EditorChannelRow.hidden`), not `visible`: a channel hidden only through its group still
 * offers "Hide channel". The guide and player pass false. "Move" only when [canMove].
 */
fun channelEditOptions(
    channel: ChannelEntity,
    hidden: Boolean,
    canMove: Boolean,
    onPick: (ChannelEdit) -> Unit,
): List<ChannelOption> = buildList {
    fun add(label: String, edit: ChannelEdit) = add(ChannelOption(label) { onPick(edit) })
    if (hidden) add("Show channel", ChannelEdit.Show) else add("Hide channel", ChannelEdit.Hide)
    add("Rename", ChannelEdit.Rename)
    if (channel.customName != null) add("Restore original name (${channel.name})", ChannelEdit.RestoreName)
    add("Change logo", ChannelEdit.Logo)
    if (channel.customLogo != null) add("Restore original logo", ChannelEdit.RestoreLogo)
    add(EPG_PICKER_TITLE, ChannelEdit.ChooseGuide)
    add("Guide time shift (${shiftText(channel.epgShiftMinutes)})", ChannelEdit.TimeShift)
    if (canMove) add(OPTION_MOVE, ChannelEdit.Move)
}

/** One of the group edit options. */
sealed interface GroupEdit {
    data object Hide : GroupEdit
    data object Show : GroupEdit
    data object Rename : GroupEdit
    data object Move : GroupEdit
    data object Sort : GroupEdit
}

fun groupEditOptions(hidden: Boolean, sort: GroupSort, onPick: (GroupEdit) -> Unit): List<ChannelOption> = buildList {
    fun add(label: String, edit: GroupEdit) = add(ChannelOption(label) { onPick(edit) })
    if (hidden) add("Show group", GroupEdit.Show) else add("Hide group", GroupEdit.Hide)
    add("Rename group", GroupEdit.Rename)
    add("Move group", GroupEdit.Move)
    add("Sort channels (${sortText(sort)})", GroupEdit.Sort)
}

/** Longest logo address accepted. */
const val MAX_LOGO_URL_LENGTH = 2048

/**
 * The trimmed logo address when it is an http or https link with a host and at most
 * [MAX_LOGO_URL_LENGTH] characters, else null (blank included: blank means "restore").
 */
fun logoUrlOrNull(text: String): String? {
    val t = text.trim()
    if (t.isEmpty() || t.length > MAX_LOGO_URL_LENGTH) return null
    // OkHttp is lenient ("https:///x" reads x as the host): insist on "http(s)://<host>" as typed.
    if (!LOGO_PREFIX.containsMatchIn(t) || t.any { it.isWhitespace() }) return null
    val url = t.toHttpUrlOrNull() ?: return null
    return t.takeIf { url.host.isNotEmpty() && (url.scheme == "http" || url.scheme == "https") }
}

private val LOGO_PREFIX = Regex("^https?://[^/?#@:]", RegexOption.IGNORE_CASE)

/** What the logo dialog may save: blank (restore the original) or a valid [logoUrlOrNull]. */
fun logoTextAcceptable(text: String): Boolean = text.isBlank() || logoUrlOrNull(text) != null

/** The manual guide id of [channel], or null when it follows the automatic match. */
val ChannelEntity.currentEpgOverride: String? get() = guideEpgId?.takeIf { it != epgChannelId }

// --- Writes -----------------------------------------------------------------------------------

/** The edits the dialogs make; [ChannelEdits] in the app ([asEditActions]), a fake in tests. */
interface EditActions {
    suspend fun setHidden(ref: ChannelRef, hidden: Boolean)
    suspend fun rename(ref: ChannelRef, name: String?)
    suspend fun setLogo(ref: ChannelRef, url: String?)
    suspend fun setEpgOverride(ref: ChannelRef, epgId: String?)
    suspend fun setShift(ref: ChannelRef, minutes: Int)
    suspend fun move(channel: ChannelEntity, direction: Int): Boolean
    suspend fun setGroupHidden(playlistId: Long, title: String, hidden: Boolean)
    suspend fun renameGroup(playlistId: Long, title: String, name: String?)
    suspend fun moveGroup(playlistId: Long, title: String, direction: Int): Boolean
    suspend fun setGroupSort(playlistId: Long, title: String, sort: GroupSort)
}

fun ChannelEdits.asEditActions(): EditActions = object : EditActions {
    private val e = this@asEditActions
    override suspend fun setHidden(ref: ChannelRef, hidden: Boolean) { e.setHidden(ref, hidden) }
    override suspend fun rename(ref: ChannelRef, name: String?) { e.rename(ref, name) }
    override suspend fun setLogo(ref: ChannelRef, url: String?) { e.setLogo(ref, url) }
    override suspend fun setEpgOverride(ref: ChannelRef, epgId: String?) { e.setEpgOverride(ref, epgId) }
    override suspend fun setShift(ref: ChannelRef, minutes: Int) { e.setShift(ref, minutes) }
    override suspend fun move(channel: ChannelEntity, direction: Int): Boolean = e.move(channel, direction)
    override suspend fun setGroupHidden(playlistId: Long, title: String, hidden: Boolean) {
        e.setGroupHidden(playlistId, title, hidden)
    }
    override suspend fun renameGroup(playlistId: Long, title: String, name: String?) { e.renameGroup(playlistId, title, name) }
    override suspend fun moveGroup(playlistId: Long, title: String, direction: Int): Boolean =
        e.moveGroup(playlistId, title, direction)
    override suspend fun setGroupSort(playlistId: Long, title: String, sort: GroupSort) { e.setGroupSort(playlistId, title, sort) }
}

/**
 * Runs each dialog's write in [scope], then calls its `onDone` (also when the write failed: the
 * dialog must still close; nothing is shown). Typed text is trimmed and blank restores the
 * original. [refreshEpg] starts a background EPG update for a playlist.
 */
class ChannelEditActions(
    private val edits: EditActions,
    private val scope: CoroutineScope,
    private val refreshEpg: (playlistId: Long) -> Unit,
) {
    fun setHidden(channel: ChannelEntity, hidden: Boolean, onDone: () -> Unit) =
        write(onDone) { edits.setHidden(channel.ref, hidden) }

    fun rename(channel: ChannelEntity, text: String, onDone: () -> Unit) =
        write(onDone) { edits.rename(channel.ref, text.cleaned()) }

    fun restoreName(channel: ChannelEntity, onDone: () -> Unit) = write(onDone) { edits.rename(channel.ref, null) }

    /** Blank restores the original logo; any other text that isn't a valid link changes nothing. */
    fun setLogo(channel: ChannelEntity, text: String, onDone: () -> Unit) =
        write(onDone) { if (logoTextAcceptable(text)) edits.setLogo(channel.ref, logoUrlOrNull(text)) }

    fun restoreLogo(channel: ChannelEntity, onDone: () -> Unit) = write(onDone) { edits.setLogo(channel.ref, null) }

    fun setShift(channel: ChannelEntity, minutes: Int, onDone: () -> Unit) =
        write(onDone) { edits.setShift(channel.ref, minutes) }

    /**
     * Sets the manual guide id ([epgId] null = "Automatic"). When the guide id the channel uses
     * changes, one background EPG update starts after [onDone]: the importer only stores
     * programmes for ids some channel uses, so the new one has none until then.
     */
    fun pickEpg(channel: ChannelEntity, epgId: String?, onDone: () -> Unit) {
        val chosen = epgId.cleaned()
        val changed = (chosen ?: channel.epgChannelId) != channel.guideEpgId
        write({ onDone(); if (changed) refreshEpg(channel.playlistId) }) { edits.setEpgOverride(channel.ref, chosen) }
    }

    fun setGroupHidden(playlistId: Long, title: String, hidden: Boolean, onDone: () -> Unit) =
        write(onDone) { edits.setGroupHidden(playlistId, title, hidden) }

    fun renameGroup(playlistId: Long, title: String, text: String, onDone: () -> Unit) =
        write(onDone) { edits.renameGroup(playlistId, title, text.cleaned()) }

    fun setGroupSort(playlistId: Long, title: String, sort: GroupSort, onDone: () -> Unit) =
        write(onDone) { edits.setGroupSort(playlistId, title, sort) }

    private fun String?.cleaned(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    private fun write(onDone: () -> Unit, block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A failed write leaves the edit as it was; the list shows the real state.
            }
            onDone()
        }
    }
}

// --- Hosts ------------------------------------------------------------------------------------

@Composable
private fun rememberEditActions(edits: ChannelEdits): ChannelEditActions {
    val app = LocalContext.current.applicationContext as LoopholeApp
    return remember(edits) {
        // The app's scope (on the main thread): an edit still finishes if the caller closes the
        // host (e.g. the player leaves) before the write is done.
        val scope = CoroutineScope(app.applicationScope.coroutineContext + Dispatchers.Main.immediate)
        ChannelEditActions(edits.asEditActions(), scope) { id ->
            app.applicationScope.launch { app.container.refreshCoordinator.refreshEpg(id) }
        }
    }
}

/**
 * Carries out [edit] on [channel]: opens its dialog (rename, logo, TV guide, time shift) or writes
 * at once (hide, show, restore), then calls [onDone]; dismissing a dialog also calls [onDone].
 * Null shows nothing. [ChannelEdit.Move] is not handled here: the caller enters its own move
 * mode; passing it just calls [onDone].
 */
@Composable
fun ChannelEditHost(edit: ChannelEdit?, channel: ChannelEntity, edits: ChannelEdits, onDone: () -> Unit) =
    ChannelEditHost(edit, channel, rememberEditActions(edits), onDone)

@Composable
internal fun ChannelEditHost(edit: ChannelEdit?, channel: ChannelEntity, actions: ChannelEditActions, onDone: () -> Unit) {
    when (edit) {
        null -> Unit
        ChannelEdit.Rename -> TextEntryDialog(
            title = "Rename channel",
            label = "Name",
            initial = channel.displayName,
            keyboardType = KeyboardType.Text,
            onSave = { actions.rename(channel, it, onDone) },
            onDismiss = onDone,
        )
        ChannelEdit.Logo -> TextEntryDialog(
            title = "Logo address",
            label = "Image link (http…)",
            initial = channel.customLogo.orEmpty(),
            keyboardType = KeyboardType.Uri,
            onSave = { actions.setLogo(channel, it, onDone) },
            onDismiss = onDone,
            accept = ::logoTextAcceptable,
            maxLength = MAX_LOGO_URL_LENGTH + 1, // one over, so an overlong paste shows as invalid
        )
        ChannelEdit.ChooseGuide -> EpgPickerDialog(
            playlistId = channel.playlistId,
            current = channel.currentEpgOverride,
            onPick = { actions.pickEpg(channel, it, onDone) },
            onDismiss = onDone,
        )
        ChannelEdit.TimeShift -> ChoiceDialog(
            title = "Guide time shift",
            options = SHIFT_CHOICES.map(::shiftText),
            selected = SHIFT_CHOICES.indexOf(channel.epgShiftMinutes).takeIf { it >= 0 } ?: SHIFT_CHOICES.indexOf(0),
            onPick = { i -> actions.setShift(channel, SHIFT_CHOICES[i], onDone) },
            onDismiss = onDone,
        )
        else -> LaunchedEffect(edit, channel.id) {
            when (edit) {
                ChannelEdit.Hide -> actions.setHidden(channel, true, onDone)
                ChannelEdit.Show -> actions.setHidden(channel, false, onDone)
                ChannelEdit.RestoreName -> actions.restoreName(channel, onDone)
                ChannelEdit.RestoreLogo -> actions.restoreLogo(channel, onDone)
                else -> onDone() // Move: the caller's move mode
            }
        }
    }
}

/**
 * Carries out a group [edit] on group [title] of [playlistId] ([label] its shown name, [sort] its
 * sort mode): "Rename group" and "Sort channels" open their dialogs, hide/show write at once; then
 * [onDone]. Like [ChannelEditHost], [GroupEdit.Move] is the caller's.
 */
@Composable
fun GroupEditHost(
    edit: GroupEdit?,
    playlistId: Long,
    title: String,
    label: String,
    sort: GroupSort,
    edits: ChannelEdits,
    onDone: () -> Unit,
) = GroupEditHost(edit, playlistId, title, label, sort, rememberEditActions(edits), onDone)

@Composable
internal fun GroupEditHost(
    edit: GroupEdit?,
    playlistId: Long,
    title: String,
    label: String,
    sort: GroupSort,
    actions: ChannelEditActions,
    onDone: () -> Unit,
) {
    when (edit) {
        null -> Unit
        GroupEdit.Rename -> TextEntryDialog(
            title = "Rename group",
            label = "Name",
            initial = label,
            keyboardType = KeyboardType.Text,
            onSave = { actions.renameGroup(playlistId, title, it, onDone) },
            onDismiss = onDone,
        )
        GroupEdit.Sort -> ChoiceDialog(
            title = "Sort channels",
            options = SORT_CHOICES.map(::sortText),
            selected = SORT_CHOICES.indexOf(sort),
            onPick = { i -> actions.setGroupSort(playlistId, title, SORT_CHOICES[i], onDone) },
            onDismiss = onDone,
        )
        else -> LaunchedEffect(edit, playlistId, title) {
            when (edit) {
                GroupEdit.Hide -> actions.setGroupHidden(playlistId, title, true, onDone)
                GroupEdit.Show -> actions.setGroupHidden(playlistId, title, false, onDone)
                else -> onDone() // Move: the caller's move mode
            }
        }
    }
}
