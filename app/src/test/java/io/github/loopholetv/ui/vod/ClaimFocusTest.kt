package io.github.loopholetv.ui.vod

import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClaimFocusTest {
    @get:Rule val compose = createComposeRule()

    @Test fun givingUpStillEndsTheClaim() {
        var ended = 0
        // Attached to nothing, so it can never take focus.
        val requester = FocusRequester()
        compose.setContent { ClaimFocus(claim = true, requester = requester, onClaimed = { ended++ }) }

        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()

        assertEquals(1, ended)
    }
}
