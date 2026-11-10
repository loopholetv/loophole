package io.github.loopholetv.ui.guide

import io.github.loopholetv.data.db.ProgrammeEntity
import io.github.loopholetv.ui.nav.ExitConfirm
import io.github.loopholetv.ui.nav.ExitDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class GuideNavigationTest {
    private val zone = ZoneId.of("Australia/Sydney")

    private fun at(h: Int, m: Int): Instant = ZonedDateTime.of(2026, 11, 9, h, m, 0, 0, zone).toInstant()

    private fun p(id: Long, from: Instant, to: Instant) = ProgrammeEntity(
        id = id, epgChannelId = "a", start = from, stop = to, title = "t$id",
        subTitle = null, description = null, category = null, generation = 1,
    )

    // --- Up/Down lands on the programme at the focus time, never one that ended before now ---

    @Test fun entryClampsAnAnchorBeforeNowToNow() {
        // Window from 19:30, now 19:45. The row above selected 19:30–20:00 (anchor 19:30). This row has
        // 20-min blocks: 19:20–19:40 ended before now, so focus goes to 19:40–20:00 (airing).
        val row = listOf(p(1, at(19, 20), at(19, 40)), p(2, at(19, 40), at(20, 0)), p(3, at(20, 0), at(20, 20)))
        assertEquals(1, entryIndex(row, anchor = at(19, 30), now = at(19, 45)))
    }

    @Test fun entryNeverLandsOnAProgrammeThatEndedBeforeNow() {
        val now = at(19, 45)
        val row = listOf(p(1, at(19, 0), at(19, 40)), p(2, at(19, 40), at(20, 10)), p(3, at(20, 10), at(21, 0)))
        for (anchor in listOf(null, at(19, 0), at(19, 30), at(19, 39), now)) {
            val i = entryIndex(row, anchor, now)
            assertTrue("anchor $anchor landed on ${row[i]}", row[i].stop > now)
        }
    }

    @Test fun entryKeepsAFutureAnchor() {
        // Right moved focus to 20:10: Up/Down keep that time (the existing Right/Left behaviour).
        val row = listOf(p(1, at(19, 40), at(20, 0)), p(2, at(20, 0), at(20, 30)), p(3, at(20, 30), at(21, 0)))
        assertEquals(1, entryIndex(row, anchor = at(20, 10), now = at(19, 45)))
        assertEquals(2, entryIndex(row, anchor = at(20, 30), now = at(19, 45)))
    }

    @Test fun entryInAGapTakesTheNextProgrammeNotAnEarlierOne() {
        // Nothing covers 19:45 on this row: the next block, not the one that ended at 19:40.
        val row = listOf(p(1, at(19, 0), at(19, 40)), p(2, at(20, 0), at(20, 30)))
        assertEquals(1, entryIndex(row, anchor = at(19, 30), now = at(19, 45)))
    }

    @Test fun entryWithEverythingEndedTakesTheLastAndEmptyIsMinusOne() {
        val row = listOf(p(1, at(19, 0), at(19, 20)), p(2, at(19, 20), at(19, 40)))
        assertEquals(1, entryIndex(row, anchor = at(19, 30), now = at(19, 45)))
        assertEquals(-1, entryIndex(emptyList(), anchor = at(19, 30), now = at(19, 45)))
    }

    // --- Entering rows never moves the focus time (an EPG gap can't drift it) ---

    @Test fun downFromAGapRowLandsOnTheProgrammeAiringNowInTheNextRow() {
        val now = at(19, 45)
        // Row A has a gap at now (18:00–19:40, then 20:00–21:00); row B is fully covered.
        val rowA = listOf(p(1, at(18, 0), at(19, 40)), p(2, at(20, 0), at(21, 0)))
        val rowB = listOf(p(3, at(19, 30), at(20, 0)), p(4, at(20, 0), at(20, 30)))
        val rowC = listOf(p(5, at(19, 0), at(20, 0)), p(6, at(20, 0), at(20, 30)))
        var anchor: Instant? = at(19, 30) // the row above selected 19:30–20:00
        val a = enterRow(rowA, anchor, now)
        assertEquals(1, a.index) // the next block: nothing airs on row A
        anchor = a.anchor
        val b = enterRow(rowB, anchor, now)
        assertEquals("row B lands on the block airing now", 0, b.index)
        anchor = b.anchor
        assertEquals(0, enterRow(rowC, anchor, now).index)
    }

    @Test fun enteringARowKeepsTheFocusTimeSetByRight() {
        val now = at(19, 45)
        val row = listOf(p(1, at(19, 30), at(20, 15)), p(2, at(20, 15), at(21, 0)))
        // Right moved focus to 20:30 on the row above; this row's 19:30–20:15 doesn't cover it.
        val e = enterRow(row, at(20, 30), now)
        assertEquals(1, e.index)
        assertEquals(at(20, 30), e.anchor)
    }

    // --- Left never selects a programme that has ended ---

    @Test fun leftOntoAProgrammeThatEndedRevealsTheGroups() {
        val now = at(19, 45)
        val row = listOf(p(1, at(19, 20), at(19, 40)), p(2, at(19, 40), at(20, 0)), p(3, at(20, 0), at(20, 20)))
        assertEquals(StripMove.ExitLeft, stripMove(row, selected = 1, step = -1, now = now))
        // Ended exactly at now counts as ended.
        val edge = listOf(p(1, at(19, 20), at(19, 45)), p(2, at(19, 45), at(20, 0)))
        assertEquals(StripMove.ExitLeft, stripMove(edge, selected = 1, step = -1, now = now))
    }

    @Test fun leftAndRightOtherwiseMoveOrReachTheEdge() {
        val now = at(19, 45)
        val row = listOf(p(1, at(19, 40), at(20, 0)), p(2, at(20, 0), at(20, 20)), p(3, at(20, 20), at(21, 0)))
        assertEquals(StripMove.Select(1), stripMove(row, selected = 2, step = -1, now = now))
        assertEquals(StripMove.Select(0), stripMove(row, selected = 1, step = -1, now = now))
        assertEquals(StripMove.Edge, stripMove(row, selected = 0, step = -1, now = now))
        assertEquals(StripMove.Select(2), stripMove(row, selected = 1, step = 1, now = now))
        assertEquals(StripMove.Edge, stripMove(row, selected = 2, step = 1, now = now))
        assertEquals(StripMove.Edge, stripMove(row, selected = -1, step = 1, now = now))
    }

    @Test fun rightAndLeftSetTheFocusTimeToTheBlockStartWithinTheWindow() {
        val window = TimeWindow(at(19, 30), at(21, 30))
        assertEquals(at(19, 30), anchorForStep(p(1, at(19, 0), at(20, 0)), window))
        assertEquals(at(20, 0), anchorForStep(p(2, at(20, 0), at(20, 30)), window))
    }

    // --- Back order ---

    @Test fun backWalksFromAProgrammeToGroupsToRailToExit() {
        var clock = 0L
        val exit = ExitConfirm(clock = { clock })
        var area = GuideArea.Grid
        val seen = mutableListOf<String>()
        repeat(4) {
            when (val back = guideBackFor(area, windowShifted = false)) {
                GuideBack.Exit -> seen += exit.onBack().name.also { clock += 500 }
                else -> {
                    seen += back.name
                    area = focusAfter(back) ?: area
                }
            }
        }
        assertEquals(listOf("ShowGroups", "OpenRail", ExitDecision.SHOW_HINT.name, ExitDecision.EXIT.name), seen)
    }

    @Test fun backWhileShiftedSnapsToNowFirstThenFollowsTheOrder() {
        assertEquals(GuideBack.SnapToNow, guideBackFor(GuideArea.Grid, windowShifted = true))
        assertEquals(null, focusAfter(GuideBack.SnapToNow))
        // Snapped back: the next Back from the grid shows the groups.
        assertEquals(GuideBack.ShowGroups, guideBackFor(GuideArea.Grid, windowShifted = false))
        // Focus elsewhere snaps too (it stays where it is).
        assertEquals(GuideBack.SnapToNow, guideBackFor(GuideArea.Groups, windowShifted = true))
        assertEquals(GuideBack.SnapToNow, guideBackFor(GuideArea.Rail, windowShifted = true))
    }

    @Test fun backFromThePreviewShowsGroupsAndFromNowhereExits() {
        assertEquals(GuideBack.ShowGroups, guideBackFor(GuideArea.Preview, windowShifted = false))
        assertEquals(GuideBack.Exit, guideBackFor(GuideArea.None, windowShifted = false))
        assertEquals(GuideBack.Exit, guideBackFor(GuideArea.Rail, windowShifted = false))
    }

    // --- The groups column collapses while focus is in the grid ---

    @Test fun groupsCollapseInTheGridAndShowInTheColumn() {
        assertFalse(groupsShownFor(GuideArea.Grid, shown = true))
        assertTrue(groupsShownFor(GuideArea.Groups, shown = false))
        // On the way to the rail or preview the layout stays as it was.
        for (area in listOf(GuideArea.Rail, GuideArea.Preview, GuideArea.None)) {
            assertTrue(groupsShownFor(area, shown = true))
            assertFalse(groupsShownFor(area, shown = false))
        }
    }

    @Test fun backThenRightRoundTripShowsThenCollapsesGroups() {
        var shown = groupsShownFor(GuideArea.Grid, shown = true)
        assertFalse(shown)
        val back = guideBackFor(GuideArea.Grid, windowShifted = false)
        shown = groupsShownFor(focusAfter(back)!!, shown)
        assertTrue(shown)
        // Right (or OK) from the group: focus enters the grid again.
        shown = groupsShownFor(GuideArea.Grid, shown)
        assertFalse(shown)
    }

    // --- Exactly 8 rows at 1080p, the preview takes the rest ---

    @Test fun previewIsSizedSoExactlyEightRowsFitBelowTheHeader() {
        // 1080p TV at 320 dpi: 540 dp tall, less the screen's 12 dp top and 12 dp bottom padding.
        val content = 516.dp
        val preview = guidePreviewHeight(content)
        val rowsArea = content - preview - PREVIEW_GAP - GROUP_NAME_HEIGHT - HEADER_HEIGHT
        assertEquals(guideRowsHeight(GUIDE_ROWS), rowsArea)
        assertEquals(8 * ROW_HEIGHT.value + 7 * ROW_GAP.value, rowsArea.value)
        // Room for an 8th whole row, none for a 9th.
        assertTrue(rowsArea < guideRowsHeight(GUIDE_ROWS + 1))
        assertEquals(141f, preview.value)
        // Larger than 0.3.1's first cut (64 dp), smaller than 0.3.0's 170 dp.
        assertTrue(preview > 64.dp && preview < 170.dp)
    }

    @Test fun previewNeverCollapsesOnASmallScreen() {
        assertEquals(PREVIEW_MIN_HEIGHT, guidePreviewHeight(300.dp))
    }

    // --- Programmes are loaded for the rows around those on screen (one query per range) ---

    @Test fun keyRangeCoversTheVisibleRowsWithAChunkEitherSide() {
        for (first in 0 until 500) {
            val range = guideKeyRange(first, itemCount = 1_000)
            assertTrue("first $first: $range", first in range && first + 10 in range)
            assertTrue("rows below $first: $range", range.last - (first + 10) >= ID_CHUNK - 11)
            assertTrue(range.count() <= 3 * ID_CHUNK)
        }
    }

    @Test fun keyRangeOnlyChangesAtChunkBoundariesAndStopsAtTheEnd() {
        assertEquals(guideKeyRange(31, 1_000), guideKeyRange(59, 1_000))
        assertEquals(0 until 90, guideKeyRange(0, 1_000))
        assertEquals(30 until 120, guideKeyRange(60, 1_000))
        assertEquals(0 until 12, guideKeyRange(3, 12))
        assertEquals(IntRange.EMPTY, guideKeyRange(0, 0))
    }

    // --- Past navigation on channels with an archive ---

    @Test fun leftIntoPastOnCatchupRow() {
        val now = at(19, 45)
        val earliest = now.minus(java.time.Duration.ofDays(1))
        val row = listOf(p(1, at(19, 0), at(19, 40)), p(2, at(19, 40), at(20, 0)))
        // From the airing block, Left selects the programme that has ended.
        assertEquals(StripMove.Select(0), stripMove(row, selected = 1, step = -1, now = now, earliest = earliest))
        // At the row's start it shifts the window back.
        assertEquals(StripMove.Edge, stripMove(row, selected = 0, step = -1, now = now, earliest = earliest))
    }

    @Test fun leftAtEarliestExits() {
        val now = at(19, 45)
        val earliest = at(19, 0)
        val row = listOf(p(1, at(18, 30), at(19, 0)), p(2, at(19, 0), at(19, 40)), p(3, at(19, 40), at(20, 0)))
        // A programme that stopped at or before the archive limit can't be reached: as Left at "now".
        assertEquals(StripMove.ExitLeft, stripMove(row, selected = 1, step = -1, now = now, earliest = earliest))
        assertEquals(StripMove.Select(1), stripMove(row, selected = 2, step = -1, now = now, earliest = earliest))
    }

    @Test fun leftOnNonCatchupRowUnchanged() {
        val now = at(19, 45)
        val row = listOf(p(1, at(19, 0), at(19, 40)), p(2, at(19, 40), at(20, 0)))
        // Without an archive, Left onto a programme that has ended goes to the groups.
        assertEquals(StripMove.ExitLeft, stripMove(row, selected = 1, step = -1, now = now, earliest = null))
    }

    @Test fun leftOnNonCatchupRowInPastExits() {
        // Window in the past (reached on another row), now 19:45: every block here has ended.
        val now = at(19, 45)
        val row = listOf(p(1, at(15, 0), at(16, 0)), p(2, at(16, 0), at(17, 0)))
        assertEquals(StripMove.ExitLeft, stripMove(row, selected = 1, step = -1, now = now, earliest = null))
        assertEquals(StripMove.Select(1), stripMove(row, selected = 0, step = 1, now = now, earliest = null))
    }

    @Test fun entryAtNowStillClamps() {
        // In the now window, an anchor in a programme that ended still lands on the airing one.
        val row = listOf(p(1, at(19, 20), at(19, 40)), p(2, at(19, 40), at(20, 0)))
        assertEquals(1, entryIndex(row, anchor = at(19, 30), now = at(19, 45), pastWindow = false))
    }

    @Test fun pastWindowDownLandsAtAnchor() {
        // Looking at the past, Up/Down keep the same time: the block holding the anchor.
        val row = listOf(p(1, at(15, 0), at(16, 0)), p(2, at(16, 0), at(17, 0)), p(3, at(17, 0), at(18, 0)))
        assertEquals(1, entryIndex(row, anchor = at(16, 30), now = at(19, 45), pastWindow = true))
        assertEquals(RowEntry(1, at(16, 30)), enterRow(row, at(16, 30), at(19, 45), pastWindow = true))
    }
}
