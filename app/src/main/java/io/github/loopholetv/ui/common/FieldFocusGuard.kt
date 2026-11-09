package io.github.loopholetv.ui.common

/** A D-pad key (or IME Next) handled by the field this long ago still explains it losing focus. */
internal const val FIELD_MOVE_GRACE_MS = 500L

/** Focus lost this long after the keyboard was hidden is taken as caused by the hiding. */
internal const val FIELD_IME_HIDE_GRACE_MS = 1_500L

/** For this long after a stray loss, focus is put back on the field whenever it is elsewhere. */
internal const val FIELD_RESTORE_WINDOW_MS = 1_000L

/**
 * Whether a text field losing focus at [nowMs] is a stray loss to undo rather than a move the user
 * made. On a Fire TV Stick, Back hiding the keyboard sends focus to the top of the page (the
 * window re-enters its first focusable). The user's own moves out of a field (D-pad, IME Next)
 * pass through the field's key handler first ([lastMoveAtMs]); a loss without one, while typing or
 * just after the keyboard hid ([imeHiddenAtMs]), is stray.
 */
internal fun isStrayFieldFocusLoss(nowMs: Long, lastMoveAtMs: Long?, typing: Boolean, imeHiddenAtMs: Long?): Boolean {
    val moved = lastMoveAtMs != null && nowMs - lastMoveAtMs < FIELD_MOVE_GRACE_MS
    val imeJustHidden = imeHiddenAtMs != null && nowMs - imeHiddenAtMs < FIELD_IME_HIDE_GRACE_MS
    return !moved && (typing || imeJustHidden)
}

/**
 * One text field's focus bookkeeping (not Compose state: nothing is drawn from it). The field
 * reports focus, keyboard visibility and the user's own moves; a stray loss starts a restore of
 * [FIELD_RESTORE_WINDOW_MS], during which the field puts focus back on itself.
 */
internal class FieldGuard {
    var focused = false
        private set
    private var imeWasVisible = false
    private var imeHiddenAt: Long? = null
    private var lastMoveAt: Long? = null
    private var restoreUntil: Long? = null

    fun onImeVisible(visible: Boolean, nowMs: Long) {
        if (imeWasVisible && !visible && focused) imeHiddenAt = nowMs
        imeWasVisible = visible
    }

    /** A D-pad move, Tab, IME Next/Done/Search: the user leaving on purpose; ends any restore. */
    fun onUserMove(nowMs: Long) {
        lastMoveAt = nowMs
        restoreUntil = null
    }

    /** Returns true when a restore should start now (a stray loss). */
    fun onFocusChanged(isFocused: Boolean, nowMs: Long, typing: Boolean): Boolean {
        val was = focused
        focused = isFocused
        // Back on the field (not by a restore): an earlier keyboard hide explains nothing later.
        if (!was && isFocused && restoreUntil == null) imeHiddenAt = null
        if (!was || isFocused) return false
        // Lost again while restoring: the running restore puts it back.
        if (restoring(nowMs)) return false
        if (!isStrayFieldFocusLoss(nowMs, lastMoveAt, typing, imeHiddenAt)) return false
        restoreUntil = nowMs + FIELD_RESTORE_WINDOW_MS
        // Used up: this hide explains this loss only.
        imeHiddenAt = null
        return true
    }

    fun restoring(nowMs: Long): Boolean = restoreUntil?.let { nowMs < it } == true

    fun onRestoreEnded() {
        restoreUntil = null
    }
}
