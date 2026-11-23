package io.github.loopholetv.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TmdbCreditTest {
    @get:Rule val compose = createComposeRule()

    @Test fun showsTheLogoBesideTheCredit() {
        compose.setContent { TmdbCredit() }

        compose.onNodeWithContentDescription("TMDB").assertIsDisplayed()
        compose.onNodeWithText(TMDB_CREDIT).assertIsDisplayed()
        compose.onNodeWithText("TMDB").assertDoesNotExist()
    }
}
