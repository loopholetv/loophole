package io.github.loopholetv.ui.guide

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.loopholetv.ui.onboarding.PillButton
import io.github.loopholetv.ui.player.CAST_STOP_LABEL
import io.github.loopholetv.ui.player.QuickItem
import io.github.loopholetv.ui.player.quickIcon
import io.github.loopholetv.ui.theme.Tokens

/** The guide's banner text while this TV casts. */
const val CASTING_BANNER = "Casting"

/** What a D-pad key does on the cast banner. */
internal enum class CastBannerKey { PASS, STAY, DOWN, LEFT }

/**
 * Up and Right stay on the banner; Down goes on to the update card below it, or leaves (back to
 * where focus was in the guide) when there is none; Left goes to the preview.
 */
internal fun castBannerKeyFor(key: Key): CastBannerKey = when (key) {
    Key.DirectionUp, Key.DirectionRight -> CastBannerKey.STAY
    Key.DirectionDown -> CastBannerKey.DOWN
    Key.DirectionLeft -> CastBannerKey.LEFT
    else -> CastBannerKey.PASS
}

/**
 * "Casting" with a Stop casting pill, at the top end of the programme details while this TV casts,
 * above the update card. Only as tall as the pill, so both fit beside the details. [focus] is on
 * the pill.
 */
@Composable
internal fun CastBanner(
    focus: FocusRequester,
    onStop: () -> Unit,
    onDown: () -> Unit,
    onLeft: () -> Unit,
    onFocusChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier
            .background(Tokens.SurfaceRaised, RoundedCornerShape(50))
            .padding(start = 14.dp)
            .onFocusChanged { onFocusChange(it.hasFocus) }
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (castBannerKeyFor(e.key)) {
                    CastBannerKey.PASS -> false
                    CastBannerKey.STAY -> true
                    CastBannerKey.DOWN -> { onDown(); true }
                    CastBannerKey.LEFT -> { onLeft(); true }
                }
            },
    ) {
        Icon(quickIcon(QuickItem.CAST), contentDescription = null, tint = Tokens.Accent, modifier = Modifier.size(20.dp))
        Text(CASTING_BANNER, style = MaterialTheme.typography.titleSmall, color = Tokens.OnBackground, maxLines = 1)
        PillButton(
            label = CAST_STOP_LABEL,
            selected = false,
            containerColor = Tokens.Surface,
            modifier = Modifier.focusRequester(focus),
            onClick = onStop,
        )
    }
}
