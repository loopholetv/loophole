package io.github.loopholetv.ui.guide

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.loopholetv.ui.onboarding.PillButton
import io.github.loopholetv.ui.theme.Tokens
import io.github.loopholetv.update.UpdateAction
import io.github.loopholetv.update.UpdateCardUi
import kotlinx.coroutines.delay

/** What a D-pad key does on the update card. */
internal enum class CardKey { PASS, STAY, LEAVE, BANNER }

/**
 * Up stays on the card, Down leaves it (back to where focus was in the guide), Left on the first
 * and Right on the last button stay; Left/Right between buttons move as usual. With the cast
 * banner above the card ([bannerAbove]), Up and Left from the first button go to it instead.
 */
internal fun cardKeyFor(key: Key, buttonIndex: Int, buttonCount: Int, bannerAbove: Boolean = false): CardKey = when (key) {
    Key.DirectionUp -> if (bannerAbove) CardKey.BANNER else CardKey.STAY
    Key.DirectionDown -> CardKey.LEAVE
    Key.DirectionLeft -> when {
        buttonIndex > 0 -> CardKey.PASS
        bannerAbove -> CardKey.BANNER
        else -> CardKey.STAY
    }
    Key.DirectionRight -> if (buttonIndex >= buttonCount - 1) CardKey.STAY else CardKey.PASS
    else -> CardKey.PASS
}

/**
 * The small update card in the top-right of the programme details. [primaryFocus] is on the
 * first button. When the buttons change while the card has focus (Update becomes Cancel, ...),
 * focus moves to the new first button, or [onLeave] runs when there is none.
 */
@Composable
internal fun UpdateCard(
    card: UpdateCardUi,
    progress: Int?,
    primaryFocus: FocusRequester,
    onAction: (UpdateAction) -> Unit,
    onLeave: () -> Unit,
    onFocusChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    /** The cast banner's button above the card, when it shows. */
    banner: FocusRequester? = null,
) {
    var hasFocus by remember { mutableStateOf(false) }
    var focusedIndex by remember { mutableIntStateOf(0) }
    val actions = card.buttons.map { it.second }
    LaunchedEffect(actions) {
        if (!hasFocus) return@LaunchedEffect
        if (actions.isEmpty()) {
            onLeave()
            return@LaunchedEffect
        }
        repeat(20) {
            if (primaryFocus.tryFocus()) return@LaunchedEffect
            delay(50)
        }
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier
            .widthIn(max = 340.dp)
            .background(Tokens.SurfaceRaised, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .onFocusChanged {
                hasFocus = it.hasFocus
                onFocusChange(it.hasFocus)
            }
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (cardKeyFor(e.key, focusedIndex, card.buttons.size, bannerAbove = banner != null)) {
                    CardKey.PASS -> false
                    CardKey.STAY -> true
                    CardKey.LEAVE -> { onLeave(); true }
                    CardKey.BANNER -> { banner?.tryFocus(); true }
                }
            },
    ) {
        Text(
            card.message,
            style = MaterialTheme.typography.titleSmall,
            color = Tokens.OnBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (progress != null) {
            Box(Modifier.width(220.dp).height(4.dp).background(Tokens.Surface, RoundedCornerShape(2.dp))) {
                Box(
                    Modifier.fillMaxWidth(progress.coerceIn(0, 100) / 100f).fillMaxHeight()
                        .background(Tokens.Accent, RoundedCornerShape(2.dp)),
                )
            }
        }
        if (card.buttons.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                card.buttons.forEachIndexed { i, (label, action) ->
                    PillButton(
                        label = label,
                        selected = false,
                        containerColor = Tokens.Surface,
                        modifier = if (i == 0) Modifier.focusRequester(primaryFocus) else Modifier,
                        onFocus = { focusedIndex = i },
                        onClick = { onAction(action) },
                    )
                }
            }
        }
    }
}
