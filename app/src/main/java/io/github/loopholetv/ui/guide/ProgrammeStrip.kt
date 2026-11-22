package io.github.loopholetv.ui.guide

import android.graphics.Paint
import android.view.KeyEvent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import io.github.loopholetv.data.db.ProgrammeEntity
import io.github.loopholetv.record.Mark
import io.github.loopholetv.ui.common.KeyPhase
import io.github.loopholetv.ui.common.OkPressAction
import io.github.loopholetv.ui.common.OkPressTracker
import io.github.loopholetv.ui.common.isOkKey
import io.github.loopholetv.ui.theme.Tokens
import java.time.Instant

class StripKeys(
    /**
     * Moves the window by half hours at a strip's edge, no further back than the row's archive
     * limit `earliest` (null: "now"); false at the limits (guide end, that limit).
     */
    val shift: (steps: Int, earliest: Instant?) -> Boolean,
    /** Left at "now" (first block of the now window, or an empty strip): focus the groups column. */
    val exitLeft: () -> Boolean,
)

/**
 * Shared by all strips: the text paint, the time column Up/Down keeps to. Labels are drawn with a
 * plain [Paint] (glyph advances only) because a Compose text layout per new block made each window
 * shift miss the frame on a Fire TV Stick.
 */
class StripStyle(
    val paint: Paint,
    val textColor: Int,
    val focusedTextColor: Int,
    /** Labels of programmes that have ended and can't be played. */
    val dimTextColor: Int,
    /** Start time of the last selected block; the row entered by Up/Down selects the block under it. */
    val anchor: MutableState<Instant?>,
    /** Drawn before the title of a programme that can be watched from the archive. */
    val rewind: RewindGlyph,
    /** Drawn before the title of a programme with a reminder or a recording booked. */
    val markers: MarkGlyphs,
) {
    private val metrics = paint.fontMetrics
    val ellipsisWidth: Float = paint.measureText(ELLIPSIS)

    /** Baseline that centres a line of text in a box of [height]. */
    fun baseline(height: Float): Float = (height - metrics.ascent - metrics.descent) / 2f
}

private const val ELLIPSIS = "…"

/** The rewind mark's box. */
val REWIND_GLYPH_WIDTH = 14.dp
val REWIND_GLYPH_HEIGHT = 10.dp
val REWIND_GLYPH_GAP = 6.dp

/** Two left-pointing triangles filling a [width] × [height] box (the catch-up mark). */
fun rewindGlyphPath(width: Float, height: Float): Path = Path().apply {
    val mid = width / 2f
    moveTo(mid, 0f)
    lineTo(mid, height)
    lineTo(0f, height / 2f)
    close()
    moveTo(width, 0f)
    lineTo(width, height)
    lineTo(mid, height / 2f)
    close()
}

/** The catch-up mark, built once per density and drawn without allocating. */
class RewindGlyph(val width: Float, val height: Float, val gap: Float) {
    val path: Path = rewindGlyphPath(width, height)
}

/** The booking markers' box (a clock or a disc) and the series sign after it. */
val MARK_GLYPH_SIZE = 10.dp
val MARK_GLYPH_GAP = 4.dp

/**
 * The reminder and recording markers, built once per density. A reminder is a clock, a recording a red
 * disc, a recording now a disc with a ring around it; a series adds a small ring after the sign.
 */
class MarkGlyphs(val size: Float, val gap: Float) {
    val clock: Path = Path().apply {
        val r = size / 2f
        // Face as a ring: the outer circle minus the inner one.
        fillType = PathFillType.EvenOdd
        addOval(Rect(0f, 0f, size, size))
        addOval(Rect(size * 0.15f, size * 0.15f, size * 0.85f, size * 0.85f))
        // Hands: up from the centre, then right.
        val t = size * 0.1f
        addRect(Rect(r - t / 2f, size * 0.25f, r + t / 2f, r + t / 2f))
        addRect(Rect(r - t / 2f, r - t / 2f, size * 0.75f, r + t / 2f))
    }
    val disc: Path = Path().apply { addOval(Rect(size * 0.15f, size * 0.15f, size * 0.85f, size * 0.85f)) }
    val ring: Path = Path().apply {
        fillType = PathFillType.EvenOdd
        addOval(Rect(0f, 0f, size, size))
        addOval(Rect(size * 0.15f, size * 0.15f, size * 0.85f, size * 0.85f))
    }
    val seriesRing: Path = Path().apply {
        fillType = PathFillType.EvenOdd
        val s = size * 0.6f
        addOval(Rect(0f, 0f, s, s))
        addOval(Rect(s * 0.2f, s * 0.2f, s * 0.8f, s * 0.8f))
    }
    val seriesWidth: Float = size * 0.6f

    /** Width taken by the sign for [mark] with its gap. */
    fun width(mark: Mark): Float = size + gap + if (mark.isSeries()) seriesWidth + gap / 2f else 0f
}

private fun Mark.isSeries() = this == Mark.REMINDER_SERIES || this == Mark.RECORDING_SERIES

private val Radius = 4.dp
private val Gap = 2.dp
private val TextInset = 10.dp
private const val NO_INFO = "No information"

/**
 * One row's programmes inside [window], drawn on a single focusable node (one node per row keeps
 * the grid cheap on Fire TV sticks). It is the row's only focus stop. Left/Right move the selection
 * within the row, then move the window at its edges (never before the row's archive limit, the
 * start of [playable], or "now" without catch-up); Left at that limit goes to the groups column;
 * Up/Down is ordinary focus search. OK clicks on release; holding OK (or Menu) calls [onLongPress]
 * instead. [programmes] must already be limited to the window; empty draws a "No information"
 * block, or a blank one while the row is [loading]. Programmes that have ended show the rewind mark
 * when their start is in [playable], and are dimmed when it isn't.
 */
@Composable
fun ProgrammeStrip(
    programmes: List<ProgrammeEntity>,
    window: TimeWindow,
    now: Instant,
    style: StripStyle,
    keys: StripKeys,
    onFocused: (ProgrammeEntity?) -> Unit,
    onClick: (ProgrammeEntity?) -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    /** The start times the row's archive can play ([playableRange]); null without catch-up. */
    playable: PlayableRange? = null,
    /** The window starts before "now": Up/Down into this row keep the focus time as it is. */
    pastWindow: Boolean = false,
    /** Held OK or Menu (the row's channel options); a hold never also clicks. */
    onLongPress: () -> Unit = {},
    /** What is booked, by programme start (this row's guide channel only; empty when nothing is). */
    marks: Map<Instant, Mark> = emptyMap(),
) {
    // How far back this row goes (as rowEarliest); null: no further than "now".
    val earliest = playable?.from
    var focused by remember { mutableStateOf(false) }
    val okPress = remember { OkPressTracker() }
    val layouts = remember { LayoutCache() }
    var selectedId by remember { mutableStateOf<Long?>(null) }
    // After a shift at an edge, the selection moves on to the neighbour the shift brought in, so
    // every press advances (not every other one). Applied once the shifted list arrives.
    var pendingStep by remember { mutableIntStateOf(0) }
    val selected = when {
        !focused || programmes.isEmpty() -> -1
        // After a guide refresh the selected id may be gone: fall back to the block under the anchor.
        else -> programmes.indexOfFirst { it.id == selectedId }
            .takeIf { it >= 0 } ?: entryIndex(programmes, style.anchor.value, now, pastWindow)
    }

    /** Left/Right (or a step after an edge shift): the focus time moves to the block's start. */
    fun select(i: Int) {
        val p = programmes[i]
        selectedId = p.id
        style.anchor.value = anchorForStep(p, window)
        onFocused(p)
    }

    /** Coming into the row (Up/Down, or the selection gone): the focus time stays (see enterRow). */
    fun enter() {
        val entry = enterRow(programmes, style.anchor.value, now, pastWindow)
        val p = programmes[entry.index]
        selectedId = p.id
        onFocused(p)
    }

    /**
     * In-row move. Past an edge the window shifts that way (the neighbour is selected once it
     * arrives). The window never goes before the row's limit ([earliest], or "now"): Left there
     * leaves for the groups column, and Right at the guide's end does nothing.
     */
    fun move(step: Int, selected: Int): Boolean {
        pendingStep = 0
        when (val m = stripMove(programmes, selected, step, now, earliest)) {
            is StripMove.Select -> {
                select(m.index)
                return true
            }
            // Left onto a programme the row can't go back to: as Left at "now".
            StripMove.ExitLeft -> return keys.exitLeft()
            StripMove.Edge -> Unit
        }
        if (keys.shift(step, earliest)) {
            pendingStep = step
            return true
        }
        // At the guide's end Right is consumed (nothing to the right); at the row's limit Left exits.
        return step > 0 || keys.exitLeft()
    }

    // On a new list: apply a pending step from the last edge shift; and if the selected block has
    // gone (window snapped back to now, guide refresh) select and report the block under the anchor,
    // so the info area never describes a block that is no longer shown.
    LaunchedEffect(programmes) {
        if (!focused || programmes.isEmpty()) return@LaunchedEffect
        val i = programmes.indexOfFirst { it.id == selectedId }
        val target = i + pendingStep
        val step = pendingStep
        pendingStep = 0
        when {
            i < 0 -> enter()
            // Shifted back: never onto a block before the row's limit (ended, without catch-up).
            step != 0 && target in programmes.indices &&
                (step > 0 || programmes[target].stop > (earliest ?: now)) -> select(target)
        }
    }

    Box(
        modifier
            .onFocusChanged { state ->
                focused = state.isFocused
                pendingStep = 0
                if (!state.isFocused) return@onFocusChanged
                if (programmes.isEmpty()) {
                    onFocused(null)
                } else {
                    enter()
                }
            }
            .onKeyEvent { e ->
                val down = e.type == KeyEventType.KeyDown
                when (e.key) {
                    Key.DirectionRight -> down && move(+1, selected)
                    Key.DirectionLeft -> down && move(-1, selected)
                    else -> {
                        val code = e.key.nativeKeyCode
                        val isOk = isOkKey(code)
                        val isMenu = code == KeyEvent.KEYCODE_MENU
                        if (!isOk && !isMenu) return@onKeyEvent false
                        // OK clicks on release; held (first repeat) or Menu, it long-presses instead.
                        val phase = if (down) KeyPhase.Down else if (e.type == KeyEventType.KeyUp) KeyPhase.Up else null
                        when (phase?.let { okPress.onKey(it, isOk, isMenu, e.nativeKeyEvent.repeatCount) }) {
                            OkPressAction.Click -> onClick(programmes.getOrNull(selected))
                            OkPressAction.LongClick -> onLongPress()
                            null -> Unit
                        }
                        true
                    }
                }
            }
            .focusable()
            .drawBehind { drawStrip(programmes, window, now, selected, focused, loading, playable, marks, style, layouts) },
    )
}


private fun DrawScope.drawStrip(
    programmes: List<ProgrammeEntity>,
    window: TimeWindow,
    now: Instant,
    selected: Int,
    focused: Boolean,
    loading: Boolean,
    playable: PlayableRange?,
    marks: Map<Instant, Mark>,
    style: StripStyle,
    layouts: LayoutCache,
) {
    val radius = CornerRadius(Radius.toPx())
    val gap = Gap.toPx()
    val inset = TextInset.toPx()
    if (programmes.isEmpty()) {
        block(0f, size.width, if (focused) Tokens.FocusFill else Tokens.Surface, radius)
        if (!loading) label(NO_INFO_ID, NO_INFO, 0f, size.width, inset, LabelTone.Plain, focused, false, null, style, layouts)
        return
    }
    layouts.trimTo(programmes.size)
    for (i in programmes.indices) {
        val p = programmes[i]
        val x = blockStartFraction(p, window) * size.width
        val w = blockWidthFraction(p, window) * size.width - gap
        if (w <= 0f) continue
        val isSelected = i == selected
        val ended = p.stop <= now
        // Instant comparisons only: nothing is allocated per block.
        val archived = ended && playable != null && p.start in playable
        val fill = when {
            isSelected -> Tokens.FocusFill
            p.isAiring(now) -> Tokens.SurfaceRaised
            ended && !archived -> Tokens.SurfaceDim
            else -> Tokens.Surface
        }
        block(x, w, fill, radius)
        val tone = if (ended && !archived) LabelTone.Dim else LabelTone.Plain
        label(p.id, p.title, x, w, inset, tone, isSelected, archived, marks[p.start], style, layouts)
    }
}

private enum class LabelTone { Plain, Dim }

private fun DrawScope.block(x: Float, w: Float, color: Color, radius: CornerRadius) {
    drawRoundRect(color, topLeft = Offset(x, 0f), size = Size(w, size.height), cornerRadius = radius)
}

private fun DrawScope.label(
    id: Long,
    text: String,
    x: Float,
    w: Float,
    inset: Float,
    tone: LabelTone,
    highlighted: Boolean,
    rewind: Boolean,
    mark: Mark?,
    style: StripStyle,
    layouts: LayoutCache,
) {
    var left = x + inset
    var room = (w - 2 * inset).toInt()
    // The booking mark goes first, when the block has room for it.
    if (mark != null) {
        val markers = style.markers
        val taken = markers.width(mark)
        if (room >= taken + 8) {
            drawMark(mark, left, markers, highlighted)
            left += taken
            room -= taken.toInt()
        }
    }
    val glyph = style.rewind
    // The rewind mark goes before the title, when the block has room for it.
    if (rewind && room >= glyph.width + glyph.gap + 8) {
        translate(left, (size.height - glyph.height) / 2f) {
            drawPath(glyph.path, if (highlighted) Tokens.OnFocus else Tokens.Accent)
        }
        left += glyph.width + glyph.gap
        room -= (glyph.width + glyph.gap).toInt()
    }
    if (room < 8) return
    val shown = layouts.get(id, room) ?: fit(text, room.toFloat(), style).also { layouts.put(id, room, it) }
    if (shown.isEmpty()) return
    val paint = style.paint
    paint.color = when {
        highlighted -> style.focusedTextColor
        tone == LabelTone.Dim -> style.dimTextColor
        else -> style.textColor
    }
    drawContext.canvas.nativeCanvas.drawText(shown, left, style.baseline(size.height), paint)
}

private fun DrawScope.drawMark(mark: Mark, left: Float, g: MarkGlyphs, highlighted: Boolean) {
    val top = (size.height - g.size) / 2f
    val red = Tokens.Error
    translate(left, top) {
        when (mark) {
            Mark.REMINDER, Mark.REMINDER_SERIES -> drawPath(g.clock, if (highlighted) Tokens.OnFocus else Tokens.Accent)
            Mark.RECORDING, Mark.RECORDING_SERIES -> drawPath(g.disc, red)
            Mark.RECORDING_NOW -> {
                drawPath(g.disc, red)
                drawPath(g.ring, red)
            }
        }
        if (mark.isSeries()) {
            translate(g.size + g.gap / 2f, g.size - g.seriesWidth) {
                drawPath(g.seriesRing, if (mark == Mark.RECORDING_SERIES) red else if (highlighted) Tokens.OnFocus else Tokens.Accent)
            }
        }
    }
}

/** [text] cut to [room] pixels with a trailing ellipsis when it does not fit. */
private fun fit(text: String, room: Float, style: StripStyle): String {
    val paint = style.paint
    if (paint.measureText(text) <= room) return text
    val n = paint.breakText(text, true, room - style.ellipsisWidth, null)
    return if (n <= 0) "" else text.substring(0, n).trimEnd() + ELLIPSIS
}

/**
 * Per-strip fitted labels by (programme id, width): redraws after a window shift or a selection
 * change reuse them without measuring text again.
 */
private class LayoutCache {
    private class Entry(val room: Int, val text: String)

    private val map = HashMap<Long, Entry>()

    fun get(id: Long, room: Int): String? = map[id]?.takeIf { it.room == room }?.text

    fun put(id: Long, room: Int, text: String) {
        map[id] = Entry(room, text)
    }

    /** Keeps memory bounded as the window moves (a row shows only a handful of blocks). */
    fun trimTo(visible: Int) {
        if (map.size > visible * 4 + 8) map.clear()
    }
}

private const val NO_INFO_ID = Long.MIN_VALUE
