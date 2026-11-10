package io.github.loopholetv.ui.player

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import io.github.loopholetv.data.db.ChannelEntity
import io.github.loopholetv.player.StreamTags
import io.github.loopholetv.ui.guide.GuideFormat
import java.time.ZoneOffset
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import io.github.loopholetv.ui.theme.LoopholeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-mdpi")
class CatchupBarTest {
    @get:Rule val compose = createComposeRule()

    private val bar = CatchupBarUi(
        title = "News", times = "20:00–21:00", elapsed = "12:34", total = "1:00:00", fraction = 0.2f,
        liveFraction = 0.5f, pendingFraction = null, pendingLabel = null, paused = false, behindLive = true,
    )

    @Test fun sixButtonsInOrderAndLiveLast() {
        val clicked = mutableListOf<TransportButton>()
        compose.setContent { LoopholeTheme { CatchupBar(bar, TransportButton.PREVIOUS, { clicked += it }) } }
        compose.waitForIdle()
        for (button in TransportButton.entries) {
            val node = compose.onNodeWithContentDescription(button.label)
            node.assertIsFocused()
            if (button != TransportButton.LIVE) node.performKeyInput { pressKey(Key.DirectionRight) }
        }
        compose.onNodeWithContentDescription("Live").performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(listOf(TransportButton.LIVE), clicked)
    }

    @Test fun barTicksRecomposeOnlyTheBar() {
        val channel = ChannelEntity(
            id = 1, playlistId = 1, key = "k1", number = 1, name = "One", streamUrl = "http://example.test/1", logo = null,
            groupTitle = "G", groupOrder = 0, sortOrder = 0, tvgId = null, tvgName = null, userAgent = null,
            referrer = null, catchupType = null, catchupDays = null, catchupSource = null, epgChannelId = null,
        )
        val player = PlayerUi(
            channel = channel, now = null, next = null, tags = StreamTags.NONE, overlayVisible = false,
            bannerVisible = true, numberEntry = null, message = null, error = null, clock = "20:12",
        )
        val format = GuideFormat(is24Hour = true, zone = ZoneOffset.UTC, locale = java.util.Locale.ENGLISH)
        val state = mutableStateOf(bar)
        var screenCompositions = 0
        compose.setContent {
            LoopholeTheme {
                SideEffect { screenCompositions++ }
                Overlay(
                    ui = player, showBar = true, catchup = { state.value }, onTransport = {}, row = ChannelRowUi.EMPTY,
                    format = format, onGuide = {}, favourites = emptySet(), onHistory = {}, onChannels = {},
                    onPlay = {}, onLongPress = {}, onRowEdge = {},
                )
            }
        }
        compose.waitForIdle()
        repeat(3) { i ->
            state.value = bar.copy(elapsed = "12:4$i")
            compose.waitForIdle()
        }
        compose.onNodeWithText("12:42").assertExists()
        assertEquals("the screen around the bar never recomposes on a tick", 1, screenCompositions)
    }
}
