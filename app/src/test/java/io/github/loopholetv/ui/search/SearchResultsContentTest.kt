package io.github.loopholetv.ui.search

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.loopholetv.data.vod.FilmCard
import io.github.loopholetv.data.vod.ShowCard
import io.github.loopholetv.ui.guide.GuideFormat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class SearchResultsContentTest {
    @get:Rule val compose = createComposeRule()

    @Test fun filmAndShowResultsShowTheirBadge() {
        val ui = SearchUi.Results(
            channels = emptyList(),
            programmes = emptyList(),
            films = listOf(FilmCard(1, "f1", "Heat", null, "4K"), FilmCard(1, "f2", "Ronin", null)),
            shows = listOf(ShowCard(1, "s1", "The Office", null, null, "FHD")),
        )
        val requesters = HashMap<String, FocusRequester>()
        compose.setContent {
            Column {
                ResultsContent(
                    ui, Instant.EPOCH, GuideFormat(true, ZoneOffset.UTC, Locale.ENGLISH),
                    { requesters.getOrPut(it) { FocusRequester() } },
                    {}, {}, {}, {}, {}, {},
                )
            }
        }

        compose.onNodeWithText("4K", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("FHD", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Ronin", useUnmergedTree = true).assertExists()
    }
}
