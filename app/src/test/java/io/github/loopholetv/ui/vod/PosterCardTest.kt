package io.github.loopholetv.ui.vod

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PosterCardTest {
    @get:Rule val compose = createComposeRule()

    @Test fun nullLogoShowsTitle() {
        compose.setContent {
            PosterCard(title = "Heat", logo = null, subtitle = null, onClick = {}, onLongClick = {})
        }

        compose.onNodeWithText("Heat").assertIsDisplayed()
    }

    @Test fun showsProgressBarWhenFractionSet() {
        compose.setContent {
            PosterCard(title = "Heat", logo = null, subtitle = "S1 E2", fraction = 0.4f, onClick = {}, onLongClick = {})
        }

        compose.onNodeWithTag(POSTER_PROGRESS_TAG, useUnmergedTree = true).assertExists()
        compose.onNodeWithText("S1 E2", useUnmergedTree = true).assertExists()
    }

    @Test fun noProgressBarWithoutFraction() {
        compose.setContent {
            PosterCard(title = "Heat", logo = null, subtitle = null, onClick = {}, onLongClick = {})
        }

        compose.onNodeWithTag(POSTER_PROGRESS_TAG, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun programmeCardShowsTitleAndTime() {
        compose.setContent {
            ProgrammeCard(channelLogo = null, title = "Evening News", timeText = "Today, 20:00", onClick = {}, onLongClick = {})
        }

        compose.onNodeWithText("Evening News").assertIsDisplayed()
        compose.onNodeWithText("Today, 20:00").assertIsDisplayed()
    }
}
