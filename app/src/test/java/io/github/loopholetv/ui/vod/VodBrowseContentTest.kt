package io.github.loopholetv.ui.vod

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.paging.PagingData
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.loopholetv.data.vod.HiddenCategory
import kotlinx.coroutines.flow.flowOf
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

    private fun show(rows: List<VodRowModel>, seed: String? = null, hide: (String) -> Unit = {}) {
        compose.setContent {
            VodBrowseContent(
                heading = "Series", rows = rows, hidden = emptyList<HiddenCategory>(),
                onHideCategory = hide, onShowCategory = {}, onOpen = { _, _ -> }, onBack = {}, lastOpenedSeed = seed,
            )
        }
    }

    @Test fun gridFirstScreenFocusesItsFirstCard() {
        show(listOf(VodRowModel.Grid("All shows", flowOf(PagingData.from(cards("Show", 12))))))
        settle("Show 0")

        compose.onNodeWithText("Show 0").assertIsFocused()
    }

    @Test fun missingSavedCardFallsBackToTheFirstCard() {
        val gone = cardId("Action", PosterModel(1, "gone", "Gone", null, null))
        show(listOf(paged("Action", "Act"), paged("Drama", "Dra")), seed = gone)
        settle("Act 0")

        compose.onNodeWithText("Act 0").assertIsFocused()
    }

    @Test fun savedCardInARowBelowAGridTakesFocus() {
        val saved = cardId("Crime", PosterModel(1, "Cri1", "Cri 1", null, null))
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
        compose.onNodeWithText("Hide category").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()

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
        fun press(key: Key) {
            compose.onNode(isFocused()).performKeyInput { pressKey(key) }
            compose.waitForIdle()
        }
        press(Key.DirectionDown)
        repeat(20) { press(Key.DirectionRight) }
        compose.onNodeWithText("Wid 0").assertDoesNotExist()
        press(Key.DirectionUp)

        press(Key.Menu)
        compose.onNodeWithText("Hide category").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()

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
        compose.onNodeWithText("Hide category").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()

        compose.onNodeWithText("Show hidden categories").assertIsFocused()
        compose.onAllNodes(isFocused()).assertCountEquals(1)

        compose.onNodeWithText("Show hidden categories").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        compose.onNodeWithText("Action").performKeyInput { pressKey(Key.DirectionCenter) }
        settle("Act 0")
        compose.onNodeWithText("Act 0").assertIsFocused()
    }
}
