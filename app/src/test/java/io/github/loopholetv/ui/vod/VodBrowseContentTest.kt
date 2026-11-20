package io.github.loopholetv.ui.vod

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.paging.PagingData
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.loopholetv.data.db.KeyRef
import io.github.loopholetv.data.vod.HiddenCategory
import io.github.loopholetv.data.vod.VodKind
import io.github.loopholetv.data.vod.VodRef
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

// Its own Robolectric sandbox: after other Compose tests in the same sandbox, paging never presented its
// first page here (the collector got the event but itemCount stayed 0), which depended on test order.
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(instrumentedPackages = ["io.github.loopholetv.ui.vod.sandbox"])
class VodBrowseContentTest {
    @get:Rule val compose = createComposeRule()

    private fun cards(prefix: String, n: Int) = List(n) { PosterModel(1, "$prefix$it", "$prefix $it", null, null) }
    private fun paged(title: String, prefix: String, n: Int = 3) =
        VodRowModel.Paged(title, title, flowOf(PagingData.from(cards(prefix, n))))

    /** Waits for the paged rows to deliver their cards (they arrive asynchronously), then for focus to settle. */
    private fun settle(firstCard: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithText(firstCard).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    /** Presses [key] on the focused node: the one in the menu while a menu is open (the card under it keeps its focus too). */
    private fun press(key: Key) {
        val inMenu = isFocused() and hasAnyAncestor(isDialog())
        val target = if (compose.onAllNodes(inMenu).fetchSemanticsNodes().isNotEmpty()) inMenu else isFocused()
        compose.onNode(target).performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    /** Moves down the open menu to [label] and presses OK on it, as the remote does. */
    private fun choose(label: String) {
        repeat(6) {
            if (compose.onAllNodes(isFocused() and hasText(label) and hasAnyAncestor(isDialog())).fetchSemanticsNodes().isNotEmpty()) {
                press(Key.DirectionCenter)
                return
            }
            press(Key.DirectionDown)
        }
        throw AssertionError("No menu option $label")
    }

    private fun show(rows: List<VodRowModel>, seed: String? = null, slot: Int = -1, hide: (String) -> Unit = {}) {
        compose.setContent {
            VodBrowseContent(
                heading = "Series", rows = rows, hidden = emptyList<HiddenCategory>(),
                onHideCategory = hide, onShowCategory = {}, onOpen = { _, _ -> }, onBack = {},
                lastOpenedSeed = seed, lastOpenedSlotSeed = slot,
            )
        }
    }

    @Test fun gridFirstScreenFocusesItsFirstCard() {
        show(listOf(VodRowModel.Grid("All shows", flowOf(PagingData.from(cards("Show", 12))))))
        settle("Show 0")

        compose.onNodeWithText("Show 0").assertIsFocused()
    }

    @Test fun missingSavedCardFallsBackToTheFirstCard() {
        val gone = cardId(rowId(paged("Action", "Act")), PosterModel(1, "gone", "Gone", null, null))
        show(listOf(paged("Action", "Act"), paged("Drama", "Dra")), seed = gone)
        settle("Act 0")

        compose.onNodeWithText("Act 0").assertIsFocused()
    }

    @Test fun savedCardInARowBelowAGridTakesFocus() {
        val saved = cardId(rowId(paged("Crime", "Cri")), PosterModel(1, "Cri1", "Cri 1", null, null))
        show(
            listOf(VodRowModel.Grid("All shows", flowOf(PagingData.from(cards("Show", 40)))), paged("Crime", "Cri")),
            seed = saved,
        )
        settle("Cri 1")

        compose.onNodeWithText("Cri 1").assertIsFocused()
    }

    @Test fun hidingTheFocusedCategoryLeavesACardFocused() {
        var rows by mutableStateOf(listOf(paged("Action", "Act"), paged("Drama", "Dra")))
        compose.setContent {
            VodBrowseContent(
                heading = "Movies", rows = rows, hidden = emptyList(),
                onHideCategory = { title -> rows = rows.filterNot { it.title == title } },
                onShowCategory = {}, onOpen = { _, _ -> }, onBack = {},
            )
        }
        settle("Act 0")
        compose.onNodeWithText("Act 0").assertIsFocused()

        compose.onNodeWithText("Act 0").performKeyInput { pressKey(Key.Menu) }
        compose.waitForIdle()
        choose("Hide category")

        compose.onNodeWithText("Dra 0").assertIsFocused()
        compose.onAllNodes(isFocused()).assertCountEquals(1)
    }

    @Test fun aRowScrolledSidewaysFocusesItsFirstCardWhenItTakesFocus() {
        var rows by mutableStateOf(listOf(paged("Action", "Act"), paged("Wide", "Wid", n = 30), paged("Drama", "Dra")))
        compose.setContent {
            VodBrowseContent(
                heading = "Movies", rows = rows, hidden = emptyList(),
                onHideCategory = { title -> rows = rows.filterNot { it.title == title } },
                onShowCategory = {}, onOpen = { _, _ -> }, onBack = {},
            )
        }
        settle("Act 0")
        press(Key.DirectionDown)
        repeat(20) { press(Key.DirectionRight) }
        compose.onNodeWithText("Wid 0").assertDoesNotExist()
        press(Key.DirectionUp)

        press(Key.Menu)
        choose("Hide category")

        compose.onNodeWithText("Wid 0").assertIsFocused()
    }

    @Test fun everyCategoryHiddenOffersToShowThemWithFocus() {
        compose.setContent {
            VodBrowseContent(
                heading = "Movies", rows = emptyList(), hidden = listOf(HiddenCategory("Action", listOf(1L))), allHidden = true,
                onHideCategory = {}, onShowCategory = {}, onOpen = { _, _ -> }, onBack = {},
            )
        }
        compose.waitForIdle()

        compose.onNodeWithText("All categories are hidden").assertExists()
        compose.onNodeWithText("Show hidden categories").assertIsFocused()
        compose.onNodeWithText("Show hidden categories").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        compose.onNodeWithText("Action").assertIsFocused()
    }

    @Test fun hidingTheLastCategoryFocusesTheEmptyState() {
        var rows by mutableStateOf(listOf<VodRowModel>(paged("Action", "Act")))
        var hidden by mutableStateOf(emptyList<HiddenCategory>())
        compose.setContent {
            VodBrowseContent(
                heading = "Movies", rows = rows, hidden = hidden, allHidden = rows.isEmpty() && hidden.isNotEmpty(),
                onHideCategory = { title ->
                    rows = rows.filterNot { it.title == title }
                    hidden = hidden + HiddenCategory(title, listOf(1L))
                },
                onShowCategory = { c ->
                    hidden = hidden - c
                    rows = listOf(paged(c.title, "Act"))
                },
                onOpen = { _, _ -> }, onBack = {},
            )
        }
        settle("Act 0")

        compose.onNodeWithText("Act 0").performKeyInput { pressKey(Key.Menu) }
        compose.waitForIdle()
        choose("Hide category")

        compose.onNodeWithText("Show hidden categories").assertIsFocused()
        compose.onAllNodes(isFocused()).assertCountEquals(1)

        compose.onNodeWithText("Show hidden categories").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        compose.onNodeWithText("Action").performKeyInput { pressKey(Key.DirectionCenter) }
        settle("Act 0")
        compose.onNodeWithText("Act 0").assertIsFocused()
    }

    private fun continueCards(n: Int) = List(n) {
        PosterModel(1, "c$it", "Con $it", null, "S1 E$it", fraction = 0.5f, play = VodPlay(VodRef(VodKind.EPISODE, 1, "e$it"), 1_000L * it))
    }

    @Test fun okOnContinueCardPlays() {
        val cards = continueCards(2)
        var played: VodPlay? = null
        var opened: String? = null
        compose.setContent {
            VodBrowseContent(
                heading = "Series", rows = listOf(VodRowModel.Continue(CONTINUE_ROW_TITLE, cards), paged("Drama", "Dra")),
                hidden = emptyList(), onHideCategory = {}, onShowCategory = {},
                onOpen = { _, key -> opened = key }, onBack = {}, onPlay = { played = it },
            )
        }
        settle("Con 0")
        compose.onNodeWithText("Con 0").assertIsFocused()

        compose.onNodeWithText("Con 0").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()

        assertEquals(cards[0].play, played)
        assertEquals(null, opened)
    }

    @Test fun longPressMenuOffersMyListToggle() {
        val recent = cards("Rec", 2)
        val changes = mutableListOf<Pair<String, Boolean>>()
        compose.setContent {
            VodBrowseContent(
                heading = "Movies", rows = listOf(VodRowModel.Recent(RECENT_ROW_TITLE, recent)),
                hidden = emptyList(), onHideCategory = {}, onShowCategory = {}, onOpen = { _, _ -> }, onBack = {},
                myListKeys = setOf(KeyRef(1, "Rec0")), onSetInMyList = { card, inList -> changes += card.key to inList },
            )
        }
        settle("Rec 0")

        compose.onNodeWithText("Rec 0").performKeyInput { pressKey(Key.Menu) }
        compose.waitForIdle()
        compose.onNodeWithText("Add to My list").assertDoesNotExist()
        compose.onNodeWithText("Hide category").assertDoesNotExist()
        choose("Remove from My list")

        press(Key.DirectionRight)
        compose.onNodeWithText("Rec 1").assertIsFocused()
        compose.onNodeWithText("Rec 1").performKeyInput { pressKey(Key.Menu) }
        compose.waitForIdle()
        compose.onNodeWithText("Remove from My list").assertDoesNotExist()
        choose("Add to My list")

        assertEquals(listOf("Rec0" to false, "Rec1" to true), changes)
    }

    @Test fun removeFromContinueCallsDismiss() {
        var rows by mutableStateOf<List<VodRowModel>>(listOf(VodRowModel.Continue(CONTINUE_ROW_TITLE, continueCards(3))))
        val dismissed = mutableListOf<String>()
        compose.setContent {
            VodBrowseContent(
                heading = "Series", rows = rows, hidden = emptyList(), onHideCategory = {}, onShowCategory = {},
                onOpen = { _, _ -> }, onBack = {},
                onDismissContinue = { card ->
                    dismissed += card.key
                    rows = listOf(VodRowModel.Continue(CONTINUE_ROW_TITLE, (rows[0] as VodRowModel.Continue).cards - card))
                },
            )
        }
        settle("Con 0")

        compose.onNodeWithText("Con 0").performKeyInput { pressKey(Key.Menu) }
        compose.waitForIdle()
        compose.onNodeWithText("Open page").assertExists()
        compose.onNodeWithText("Add to My list").assertExists()
        choose("Remove from Continue watching")

        assertEquals(listOf("c0"), dismissed)
        compose.onNodeWithText("Con 1").assertIsFocused()
    }

    @Test fun openPageOnAContinueCardOpensTheTitle() {
        var opened: Pair<Long, String>? = null
        var played: VodPlay? = null
        compose.setContent {
            VodBrowseContent(
                heading = "Series", rows = listOf(VodRowModel.Continue(CONTINUE_ROW_TITLE, continueCards(2))),
                hidden = emptyList(), onHideCategory = {}, onShowCategory = {},
                onOpen = { p, key -> opened = p to key }, onBack = {}, onPlay = { played = it },
            )
        }
        settle("Con 0")

        compose.onNodeWithText("Con 0").performKeyInput { pressKey(Key.Menu) }
        compose.waitForIdle()
        choose("Open page")

        assertEquals(1L to "c0", opened)
        assertEquals(null, played)
    }

    @Test fun restoreFallsBackToSameSlotInContinueRow() {
        val all = continueCards(4)
        val row = VodRowModel.Continue(CONTINUE_ROW_TITLE, all - all[1])
        show(listOf(row, paged("Drama", "Dra")), seed = cardId(rowId(row), all[1]), slot = 1)
        settle("Con 0")

        compose.onNodeWithText("Con 2").assertIsFocused()
    }

    @Test fun restoreFallsBackToTheLastCardWhenTheSlotIsPastTheEnd() {
        val all = continueCards(3)
        val row = VodRowModel.Continue(CONTINUE_ROW_TITLE, all - all[2])
        show(listOf(row, paged("Drama", "Dra")), seed = cardId(rowId(row), all[2]), slot = 2)
        settle("Con 0")

        compose.onNodeWithText("Con 1").assertIsFocused()
    }

    @Test fun categoryNamedLikeAFixedRowKeepsItsOwnIdentity() {
        val mine = VodRowModel.MyList(MY_LIST_ROW_TITLE, cards("Mine", 2))
        val category = paged(MY_LIST_ROW_TITLE, "Cat")
        val saved = cardId(rowId(category), PosterModel(1, "Cat1", "Cat 1", null, null))
        show(listOf(mine, category), seed = saved)
        settle("Cat 1")

        compose.onNodeWithText("Cat 1").assertIsFocused()
        press(Key.Menu)
        compose.onNodeWithText("Hide category").assertExists()
        choose("Add to My list")
        compose.onNodeWithText("Hide category").assertDoesNotExist()
        compose.onNodeWithText("Cat 1").assertIsFocused()

        press(Key.DirectionUp)
        compose.onNode(isFocused() and hasText("Mine", substring = true)).assertExists()
        press(Key.Menu)
        compose.onNodeWithText("Remove from My list").assertExists()
        compose.onNodeWithText("Hide category").assertDoesNotExist()
    }

    @Test fun removingTheLastContinueCardFocusesTheOneBefore() {
        var rows by mutableStateOf<List<VodRowModel>>(listOf(VodRowModel.Continue(CONTINUE_ROW_TITLE, continueCards(3))))
        compose.setContent {
            VodBrowseContent(
                heading = "Series", rows = rows, hidden = emptyList(), onHideCategory = {}, onShowCategory = {},
                onOpen = { _, _ -> }, onBack = {},
                onDismissContinue = { card -> rows = listOf(VodRowModel.Continue(CONTINUE_ROW_TITLE, (rows[0] as VodRowModel.Continue).cards - card)) },
            )
        }
        settle("Con 0")
        press(Key.DirectionRight)
        press(Key.DirectionRight)
        compose.onNodeWithText("Con 2").assertIsFocused()

        press(Key.Menu)
        choose("Remove from Continue watching")

        compose.onNodeWithText("Con 1").assertIsFocused()
        compose.onAllNodes(isFocused()).assertCountEquals(1)
    }

    @Test fun removingTheOnlyMyListCardFocusesTheRowInItsPlace() {
        var rows by mutableStateOf<List<VodRowModel>>(listOf(VodRowModel.MyList(MY_LIST_ROW_TITLE, cards("Mine", 1)), paged("Drama", "Dra")))
        compose.setContent {
            VodBrowseContent(
                heading = "Movies", rows = rows, hidden = emptyList(), onHideCategory = {}, onShowCategory = {},
                onOpen = { _, _ -> }, onBack = {},
                onSetInMyList = { _, inList -> if (!inList) rows = rows.drop(1) },
            )
        }
        settle("Mine 0")

        press(Key.Menu)
        choose("Remove from My list")

        compose.onNodeWithText("Dra 0").assertIsFocused()
        compose.onAllNodes(isFocused()).assertCountEquals(1)
    }
}
