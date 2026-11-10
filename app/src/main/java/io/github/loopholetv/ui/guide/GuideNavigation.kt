package io.github.loopholetv.ui.guide

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.loopholetv.data.db.ProgrammeEntity
import java.time.Duration
import java.time.Instant

/** Guide rows on screen at 1080p. */
const val GUIDE_ROWS = 8

/** Between guide rows. */
val ROW_GAP = 3.dp

/** Between the preview row and the guide below it. */
val PREVIEW_GAP = 8.dp

/** The current group's name, above the channel list. */
val GROUP_NAME_HEIGHT = 22.dp

/** The preview never gets smaller than this (a small screen shows fewer rows instead). */
val PREVIEW_MIN_HEIGHT = 64.dp

/** Height of [rows] guide rows and the gaps between them. */
fun guideRowsHeight(rows: Int): Dp = ROW_HEIGHT * rows + ROW_GAP * (rows - 1)

/**
 * The preview row's height in a guide screen [contentHeight] tall: whatever is left once the group
 * name, the header and exactly [GUIDE_ROWS] rows have their room, so those rows fit whole below
 * the header and no part of another one shows (141 dp at 1080p). The details sit beside it.
 */
fun guidePreviewHeight(contentHeight: Dp): Dp =
    (contentHeight - PREVIEW_GAP - GROUP_NAME_HEIGHT - HEADER_HEIGHT - guideRowsHeight(GUIDE_ROWS))
        .coerceAtLeast(PREVIEW_MIN_HEIGHT)

/**
 * The block a strip selects when focus enters it (Up/Down from another row, or any other way in):
 * the one covering the focus time [anchor]. In the "now" window ([pastWindow] false) the anchor is
 * clamped to be no earlier than [now], so focus never lands on a programme that ended before the
 * now line; looking at the past, the anchor is kept, so Up/Down stay at the same time. In a gap at
 * that time: the next programme still to end after it. Only when every block shown has ended: the
 * last one. -1 when there are no blocks.
 */
internal fun entryIndex(
    programmes: List<ProgrammeEntity>,
    anchor: Instant?,
    now: Instant,
    pastWindow: Boolean = false,
): Int {
    if (programmes.isEmpty()) return -1
    val at = when {
        anchor == null -> now
        !pastWindow && anchor < now -> now
        else -> anchor
    }
    val covering = programmes.indexOfFirst { it.start <= at && it.stop > at }
    if (covering >= 0) return covering
    val next = programmes.indexOfFirst { it.stop > at }
    return if (next >= 0) next else programmes.lastIndex
}

/** A row entered by Up/Down (or focus coming in any other way): the block to select and the focus time to keep. */
internal data class RowEntry(val index: Int, val anchor: Instant?)

/**
 * Entering a row selects [entryIndex]'s block but keeps the focus time: only Left/Right move it
 * ([anchorForStep]). Otherwise a gap at now (entry takes the next block) would drift the focus
 * time into the future, and the rows after it would skip the programme airing now.
 */
internal fun enterRow(
    programmes: List<ProgrammeEntity>,
    anchor: Instant?,
    now: Instant,
    pastWindow: Boolean = false,
): RowEntry = RowEntry(entryIndex(programmes, anchor, now, pastWindow), anchor)

/** The focus time after Left/Right selects [p]: its start (within the window). */
internal fun anchorForStep(p: ProgrammeEntity, window: TimeWindow): Instant = maxOf(p.start, window.start)

/** What Left/Right does inside a strip. */
internal sealed interface StripMove {
    /** Select the block at [index]. */
    data class Select(val index: Int) : StripMove

    /** Past the row's edge: shift the window (at the row's limit Left leaves for the groups instead). */
    data object Edge : StripMove

    /** Left onto a programme the row can't go back to: as Left at "now" (the groups column). */
    data object ExitLeft : StripMove
}

/**
 * Left/Right from block [selected] by [step]. A row goes back no further than [earliest], its
 * archive limit (null: no catch-up, so no further than [now]). The window starts on the half hour
 * before that limit, so it can show blocks that stopped before it (EPGs that don't run on half
 * hours): Left onto one of those acts as Left at "now".
 */
internal fun stripMove(
    programmes: List<ProgrammeEntity>,
    selected: Int,
    step: Int,
    now: Instant,
    earliest: Instant? = null,
): StripMove {
    val target = selected + step
    if (selected < 0 || target !in programmes.indices) return StripMove.Edge
    if (step < 0 && programmes[target].stop <= (earliest ?: now)) return StripMove.ExitLeft
    return StripMove.Select(target)
}

/** Where focus is on the guide screen. */
enum class GuideArea { Grid, Groups, Rail, Preview, None }

/** What Back does on the guide; [Exit] leaves it to the app (the confirm-exit hint, then exit). */
enum class GuideBack { SnapToNow, ShowGroups, OpenRail, Exit }

/**
 * Back on the guide, outside Move mode and dialogs (they keep their own Back): a window shifted
 * away from now (into the past or the future) snaps back to now first, wherever focus is; then
 * programme (or preview) → groups list → side rail → exit.
 */
fun guideBackFor(area: GuideArea, windowShifted: Boolean): GuideBack = when {
    windowShifted -> GuideBack.SnapToNow
    area == GuideArea.Grid || area == GuideArea.Preview -> GuideBack.ShowGroups
    area == GuideArea.Groups -> GuideBack.OpenRail
    else -> GuideBack.Exit
}

/** Where focus is after [back] (null: it stays where it was). */
fun focusAfter(back: GuideBack): GuideArea? = when (back) {
    GuideBack.ShowGroups -> GuideArea.Groups
    GuideBack.OpenRail -> GuideArea.Rail
    GuideBack.SnapToNow, GuideBack.Exit -> null
}

/**
 * Whether the groups column is shown once focus is in [area]: it collapses completely while
 * focus is in the grid (programmes and channels), shows while focus is in it, and elsewhere (rail,
 * preview, nothing) stays as it was, so the layout doesn't jump on the way there.
 */
fun groupsShownFor(area: GuideArea, shown: Boolean): Boolean = when (area) {
    GuideArea.Grid -> false
    GuideArea.Groups -> true
    else -> shown
}

/** Rows per chunk of guide keys loaded around the first visible row. */
internal const val ID_CHUNK = 30

/**
 * The list indexes whose programmes are loaded: the chunk holding [firstVisible] plus one chunk
 * either side (3 × [ID_CHUNK] = 90 rows, well under the DAO cap), so the rows on screen always
 * have a full chunk loaded below them and the range (one query) changes only every [ID_CHUNK] rows.
 */
internal fun guideKeyRange(firstVisible: Int, itemCount: Int, chunk: Int = ID_CHUNK): IntRange {
    val from = ((firstVisible / chunk) * chunk - chunk).coerceAtLeast(0)
    val to = minOf(itemCount, from + 3 * chunk)
    return from until to
}

/** Where a page key leaves the list: the first row to show and the row to focus. */
internal data class PageTarget(val firstVisible: Int, val focus: Int)

/**
 * A page key moving the list by [delta] rows: the first visible row moves by [delta] (kept to a
 * full page of [rows] inside [itemCount]) and focus keeps its place on screen, so it stays on a
 * row that is shown. [focused] -1 (the focused row not loaded) focuses the new page's first row.
 */
internal fun pageTarget(firstVisible: Int, focused: Int, itemCount: Int, delta: Int, rows: Int = GUIDE_ROWS): PageTarget {
    if (itemCount <= 0) return PageTarget(0, -1)
    val first = (firstVisible + delta).coerceIn(0, (itemCount - rows).coerceAtLeast(0))
    val focus = if (focused < 0) first else (focused + delta).coerceIn(0, itemCount - 1)
    return PageTarget(first, focus)
}

/**
 * The strips' focus time after the window jumped from [before] to [after] (a whole day, or back to
 * now): the same time of day on the new window, so focus stays in the same column. No focus time
 * yet ([anchor] null) means "now", so the first jump keeps the current time of day. Null (the
 * default, "now") when that time isn't in the new window.
 */
internal fun anchorAfterJump(anchor: Instant?, now: Instant, before: TimeWindow, after: TimeWindow): Instant? {
    val moved = (anchor ?: now).plus(Duration.between(before.start, after.start))
    return moved.takeIf(after::contains)
}
