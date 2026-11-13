package io.github.loopholetv.ui.vod

import androidx.compose.ui.test.junit4.createComposeRule
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
}
