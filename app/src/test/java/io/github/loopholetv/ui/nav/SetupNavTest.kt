package io.github.loopholetv.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SetupNavTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var nav: NavHostController

    private fun start(startDestination: String) {
        compose.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = startDestination) {
                for (route in listOf(Routes.ONBOARDING, Routes.SETUP_COPY_FROM, Routes.GUIDE, Routes.SEARCH, Routes.TVS)) {
                    composable(route) { Box {} }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun returnToGuidePopsWhatIsAboveIt() {
        start(Routes.GUIDE)
        compose.runOnUiThread {
            nav.navigate(Routes.SEARCH)
            nav.navigate(Routes.TVS)
        }
        compose.waitForIdle()

        compose.runOnUiThread { nav.returnToGuide() }
        compose.waitForIdle()

        assertEquals(Routes.GUIDE, nav.currentDestination?.route)
        assertNull("nothing is left under the guide", nav.previousBackStackEntry)
    }

    @Test
    fun returnToGuideFromFirstStartRemovesOnboarding() {
        start(Routes.ONBOARDING)
        compose.runOnUiThread { nav.openCopyFrom() }
        compose.waitForIdle()
        assertEquals(Routes.SETUP_COPY_FROM, nav.currentDestination?.route)

        compose.runOnUiThread { nav.returnToGuide() }
        compose.waitForIdle()

        assertEquals(Routes.GUIDE, nav.currentDestination?.route)
        assertNull(nav.previousBackStackEntry)
    }

    @Test
    fun returnToGuideOnTheGuideDoesNothing() {
        start(Routes.GUIDE)
        val before = nav.currentBackStackEntry

        compose.runOnUiThread { nav.returnToGuide() }
        compose.waitForIdle()

        assertEquals(before, nav.currentBackStackEntry)
    }
}
