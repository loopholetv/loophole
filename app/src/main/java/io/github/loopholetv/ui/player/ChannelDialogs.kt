package io.github.loopholetv.ui.player

import io.github.loopholetv.data.db.ChannelEntity
import io.github.loopholetv.ui.manage.ChannelEdit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The player's channel dialogs: a channel's options (held OK / Menu) and the edit picked from them.
 * Only one shows at a time, and a held OK never stacks a second one.
 */
internal class ChannelDialogs(private val hideOverlay: () -> Unit) {
    private val _optionsFor = MutableStateFlow<ChannelEntity?>(null)
    val optionsFor: StateFlow<ChannelEntity?> = _optionsFor.asStateFlow()

    private val _editing = MutableStateFlow<ChannelEditing?>(null)
    val editing: StateFlow<ChannelEditing?> = _editing.asStateFlow()

    val isOpen: Boolean get() = _optionsFor.value != null || _editing.value != null

    fun openOptions(channel: ChannelEntity) {
        // Already open (e.g. the tail of the held press that opened it): keep that channel. Nor
        // while an edit's dialog is up (a held OK must not open a second panel under it).
        if (isOpen) return
        // The dialog is its own window: nothing of the overlay may stay up (or time out) under it.
        hideOverlay()
        _optionsFor.value = channel
    }

    fun dismissOptions() {
        _optionsFor.value = null
    }

    /** An edit row of the options: closes the options, opens the edit. */
    fun pickEdit(channel: ChannelEntity, edit: ChannelEdit) {
        _optionsFor.value = null
        _editing.value = ChannelEditing(channel, edit)
    }

    /** The edit that just finished, closing it; null when none was open. */
    fun takeDone(): ChannelEditing? = _editing.value?.also { _editing.value = null }

    fun closeAll() {
        _optionsFor.value = null
        _editing.value = null
    }
}
