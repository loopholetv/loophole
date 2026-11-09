package io.github.loopholetv.ui.guide

import io.github.loopholetv.data.db.ChannelScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuideMoveKeysTest {
    @Test fun firstPressMovesOnePlace() {
        assertEquals(-1, moveStepFor(up = true, down = false, keyDown = true, repeatCount = 0))
        assertEquals(1, moveStepFor(up = false, down = true, keyDown = true, repeatCount = 0))
    }

    @Test fun heldKeyRepeatsAreIgnored() {
        assertEquals(0, moveStepFor(up = true, down = false, keyDown = true, repeatCount = 1))
        assertEquals(0, moveStepFor(up = false, down = true, keyDown = true, repeatCount = 7))
    }

    @Test fun keyUpAndOtherKeysDoNotMove() {
        assertEquals(0, moveStepFor(up = true, down = false, keyDown = false, repeatCount = 0))
        assertEquals(0, moveStepFor(up = false, down = false, keyDown = true, repeatCount = 0))
    }

    @Test fun removalFocusGoesToNextRowThenPreviousThenGroup() {
        assertEquals(RemovalFocus.Row(3), removalFocusFor(listOf(1L, 2L, 3L), 2))
        assertEquals(RemovalFocus.Row(2), removalFocusFor(listOf(1L, 2L, 3L), 3))
        // The only favourite: the selected group, before the row goes.
        assertEquals(RemovalFocus.Group, removalFocusFor(listOf(5L), 5))
        // Not among the loaded rows: stay on it (no move needed before removing).
        assertEquals(RemovalFocus.Stay, removalFocusFor(listOf(1L, 2L), 9))
    }

    @Test fun hidingAGroupMovesFocusToTheNextGroupThenThePrevious() {
        val fav = GroupItem(ChannelScope.Favourites, "Favourites", 0)
        val all = GroupItem(ChannelScope.All, "All channels", 3)
        val news = GroupItem(ChannelScope.Group(1, "News"), "News", 2, "P")
        val sport = GroupItem(ChannelScope.Group(1, "Sport"), "Sport", 1, "P")
        val groups = listOf(fav, all, news, sport)
        assertEquals(sport, groupFocusAfterHiding(groups, news))
        assertEquals(news, groupFocusAfterHiding(groups, sport))
        assertNull(groupFocusAfterHiding(groups, GroupItem(ChannelScope.Group(2, "Gone"), "Gone", 1)))
    }
}
