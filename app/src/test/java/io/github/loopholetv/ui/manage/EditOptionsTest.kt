package io.github.loopholetv.ui.manage

import io.github.loopholetv.data.db.GroupSort
import io.github.loopholetv.data.repo.SHIFT_CHOICES
import io.github.loopholetv.ui.common.channelOptionsTitle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EditOptionsTest {
    private fun labels(
        channel: io.github.loopholetv.data.db.ChannelEntity,
        hidden: Boolean = false,
        canMove: Boolean = true,
    ) = channelEditOptions(channel, hidden = hidden, canMove = canMove) {}.map { it.label }

    @Test fun channelOptionLabelsExact() {
        val ch = testChannel(name = "BBC One", customName = "Beeb", customLogo = "http://example.test/l.png", shift = 90)
        assertEquals(
            listOf(
                "Hide channel",
                "Rename",
                "Restore original name (BBC One)",
                "Change logo",
                "Restore original logo",
                "Choose TV guide",
                "Guide time shift (+1:30)",
                "Video decoder (Same as Settings)",
                "Audio decoder (Same as Settings)",
                "External player (Off)",
                "Move",
            ),
            labels(ch),
        )
    }

    @Test fun restoreOnlyWhenEdited() {
        assertEquals(
            listOf(
                "Hide channel", "Rename", "Change logo", "Choose TV guide", "Guide time shift (Off)",
                "Video decoder (Same as Settings)", "Audio decoder (Same as Settings)", "External player (Off)", "Move",
            ),
            labels(testChannel()),
        )
    }

    @Test fun moveOnlyWhenAllowed() {
        assertFalse("Move" in labels(testChannel(), canMove = false))
        assertTrue("Move" in labels(testChannel(), canMove = true))
    }

    @Test fun hiddenChannelOffersShow() {
        val l = labels(testChannel(visible = false), hidden = true)
        assertEquals("Show channel", l.first())
        assertFalse("Hide channel" in l)
    }

    /** A channel hidden only through its group (visible = false, own hidden = false) offers Hide. */
    @Test fun hiddenGroupChannelOffersHideNotShow() {
        val l = labels(testChannel(visible = false), hidden = false)
        assertEquals("Hide channel", l.first())
        assertFalse("Show channel" in l)
    }

    @Test fun optionsReportTheirEdit() {
        val picked = mutableListOf<ChannelEdit>()
        val ch = testChannel(customName = "Beeb", customLogo = "http://example.test/l.png")
        channelEditOptions(ch, hidden = false, canMove = true) { picked += it }.forEach { it.onClick() }
        assertEquals(
            listOf(
                ChannelEdit.Hide, ChannelEdit.Rename, ChannelEdit.RestoreName, ChannelEdit.Logo,
                ChannelEdit.RestoreLogo, ChannelEdit.ChooseGuide, ChannelEdit.TimeShift,
                ChannelEdit.VideoDecoder, ChannelEdit.AudioDecoder, ChannelEdit.ExternalPlayer, ChannelEdit.Move,
            ),
            picked,
        )
        picked.clear()
        channelEditOptions(ch, hidden = true, canMove = false) { picked += it }.first().onClick()
        assertEquals(listOf<ChannelEdit>(ChannelEdit.Show), picked)
    }

    @Test fun shiftTextFormats() {
        assertEquals("Off", shiftText(0))
        assertEquals("+0:30", shiftText(30))
        assertEquals("+1:30", shiftText(90))
        assertEquals("−1:00", shiftText(-60))
        assertEquals("−0:30", shiftText(-30))
        assertEquals("+12:00", shiftText(720))
        assertEquals("−12:00", shiftText(-720))
        assertEquals(SHIFT_CHOICES.size, SHIFT_CHOICES.map(::shiftText).distinct().size)
    }

    @Test fun groupOptionLabelsExact() {
        assertEquals(
            listOf("Hide group", "Rename group", "Move group", "Sort channels (My order)"),
            groupEditOptions(hidden = false, sort = GroupSort.CUSTOM) {}.map { it.label },
        )
        assertEquals(
            listOf("Show group", "Rename group", "Move group", "Sort channels (Playlist order)"),
            groupEditOptions(hidden = true, sort = GroupSort.PLAYLIST) {}.map { it.label },
        )
        assertEquals(
            "Sort channels (Name A–Z)",
            groupEditOptions(hidden = false, sort = GroupSort.NAME) {}.last().label,
        )
        val picked = mutableListOf<GroupEdit>()
        groupEditOptions(hidden = true, sort = GroupSort.NAME) { picked += it }.forEach { it.onClick() }
        assertEquals(listOf(GroupEdit.Show, GroupEdit.Rename, GroupEdit.Move, GroupEdit.Sort), picked)
    }

    @Test fun sortChoicesInOrder() {
        assertEquals(listOf("Playlist order", "Name A–Z", "My order"), SORT_CHOICES.map(::sortText))
        assertEquals(GroupSort.entries.toList(), SORT_CHOICES)
    }

    @Test fun titleUsesGuideNumberAndDisplayName() {
        assertEquals("105  Beeb", channelOptionsTitle(testChannel(number = 5, customName = "Beeb")))
        assertEquals("105  BBC One", channelOptionsTitle(testChannel(number = 5)))
    }

    // --- ChannelEditActions -------------------------------------------------------------------

    private class Harness(scope: TestScope) {
        val edits = FakeEditActions()
        val refreshed = mutableListOf<Long>()
        val actions = ChannelEditActions(edits, scope) { refreshed += it }
        var done = 0
        val onDone: () -> Unit = { done++ }
    }

    @Test fun directEditsWriteThenFinish() = runTest {
        val h = Harness(this)
        val ch = testChannel()
        h.actions.setHidden(ch, true, h.onDone)
        h.actions.restoreName(ch, h.onDone)
        h.actions.restoreLogo(ch, h.onDone)
        assertEquals(0, h.done)
        advanceUntilIdle()
        assertEquals(listOf("setHidden(1:k1, true)", "rename(1:k1, null)", "setLogo(1:k1, null)"), h.edits.calls)
        assertEquals(3, h.done)
    }

    @Test fun blankTextRestores() = runTest {
        val h = Harness(this)
        val ch = testChannel(customName = "Beeb", customLogo = "http://example.test/l.png")
        h.actions.rename(ch, "   ", h.onDone)
        h.actions.setLogo(ch, " ", h.onDone)
        h.actions.rename(ch, "  Beeb 2 ", h.onDone)
        h.actions.setLogo(ch, " http://example.test/n.png ", h.onDone)
        advanceUntilIdle()
        assertEquals(
            listOf(
                "rename(1:k1, null)", "setLogo(1:k1, null)",
                "rename(1:k1, Beeb 2)", "setLogo(1:k1, http://example.test/n.png)",
            ),
            h.edits.calls,
        )
        assertEquals(4, h.done)
    }

    @Test fun shiftAndGroupEdits() = runTest {
        val h = Harness(this)
        h.actions.setShift(testChannel(), -90, h.onDone)
        h.actions.setGroupHidden(1, "News", true, h.onDone)
        h.actions.renameGroup(1, "News", " ", h.onDone)
        h.actions.renameGroup(1, "News", " Headlines ", h.onDone)
        h.actions.setGroupSort(1, "News", GroupSort.NAME, h.onDone)
        advanceUntilIdle()
        assertEquals(
            listOf(
                "setShift(1:k1, -90)", "setGroupHidden(1, News, true)", "renameGroup(1, News, null)",
                "renameGroup(1, News, Headlines)", "setGroupSort(1, News, NAME)",
            ),
            h.edits.calls,
        )
        assertEquals(5, h.done)
    }

    /** A pick that changes the guide id starts one EPG update, after the picker has closed. */
    @Test fun epgPickThatChangesTheGuideStartsOneRefresh() = runTest {
        val h = Harness(this)
        var doneBeforeRefresh = false
        h.actions.pickEpg(testChannel(epgChannelId = "bbc1.uk"), "other.id") {
            h.done++
            doneBeforeRefresh = h.refreshed.isEmpty()
        }
        advanceUntilIdle()
        assertEquals(listOf("setEpgOverride(1:k1, other.id)"), h.edits.calls)
        assertEquals(listOf(1L), h.refreshed)
        assertEquals(1, h.done)
        assertTrue(doneBeforeRefresh)
    }

    @Test fun epgAutomaticUndoingAnOverrideRefreshes() = runTest {
        val h = Harness(this)
        h.actions.pickEpg(testChannel(epgChannelId = "bbc1.uk", guideEpgId = "other.id"), null, h.onDone)
        advanceUntilIdle()
        assertEquals(listOf("setEpgOverride(1:k1, null)"), h.edits.calls)
        assertEquals(listOf(1L), h.refreshed)
    }

    @Test fun epgPickWithoutChangeDoesNotRefresh() = runTest {
        val h = Harness(this)
        // Automatic on a channel that has no override.
        h.actions.pickEpg(testChannel(epgChannelId = "bbc1.uk"), null, h.onDone)
        // The current override again.
        h.actions.pickEpg(testChannel(epgChannelId = "bbc1.uk", guideEpgId = "other.id"), "other.id", h.onDone)
        // The automatic id picked by hand: same guide id, its programmes are already stored.
        h.actions.pickEpg(testChannel(epgChannelId = "bbc1.uk"), "bbc1.uk", h.onDone)
        advanceUntilIdle()
        assertTrue(h.refreshed.isEmpty())
        assertEquals(3, h.done)
    }

    @Test fun currentOverrideIsTheGuideIdOnlyWhenItDiffers() {
        assertEquals(null, testChannel(epgChannelId = "a").currentEpgOverride)
        assertEquals("b", testChannel(epgChannelId = "a", guideEpgId = "b").currentEpgOverride)
        assertEquals("b", testChannel(epgChannelId = null, guideEpgId = "b").currentEpgOverride)
    }

    @Test fun logoAcceptsOnlyHttpLinks() {
        assertEquals("http://example.test/l.png", logoUrlOrNull("  http://example.test/l.png "))
        assertEquals("https://example.test/a/b.svg?x=1", logoUrlOrNull("https://example.test/a/b.svg?x=1"))
        assertEquals("HTTPS://Example.test/l.png", logoUrlOrNull("HTTPS://Example.test/l.png"))
        for (bad in listOf(
            "", "   ", "example.test/logo.png", "htp://example.test/l.png", "logo", "http://",
            "https:///l.png", "file:///sdcard/l.png", "content://media/1", "ftp://example.test/l.png",
            "javascript:alert(1)", "http://example.test/my logo.png", "http://@example.test/l.png",
        )) assertNull(bad, logoUrlOrNull(bad))
        val longest = "http://example.test/" + "a".repeat(MAX_LOGO_URL_LENGTH - 20)
        assertEquals(MAX_LOGO_URL_LENGTH, longest.length)
        assertEquals(longest, logoUrlOrNull(longest))
        assertNull(logoUrlOrNull(longest + "a"))
        // Blank is acceptable (it restores); invalid text is not.
        assertTrue(logoTextAcceptable(""))
        assertTrue(logoTextAcceptable("  "))
        assertTrue(logoTextAcceptable("http://example.test/l.png"))
        assertFalse(logoTextAcceptable("example.test/logo.png"))
    }

    @Test fun invalidLogoTextWritesNothing() = runTest {
        val h = Harness(this)
        h.actions.setLogo(testChannel(customLogo = "http://example.test/l.png"), "file:///sdcard/x.png", h.onDone)
        advanceUntilIdle()
        assertTrue(h.edits.calls.isEmpty())
        assertEquals(1, h.done)
    }
}
