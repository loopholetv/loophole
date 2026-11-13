package io.github.loopholetv.ui.player

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.loopholetv.ui.theme.Tokens
import kotlinx.coroutines.delay

private const val CAST_LABEL_SHOW_MS = 5_000L
private const val CAST_LABEL_ANIMATION_MS = 300

/**
 * The state of a label that steps out of the way ([CastLabelTimer]): one delayed wake-up per
 * change, nothing per frame. Null while [text] is null.
 */
@Composable
fun rememberCastLabelState(text: String?, count: Int?, key: Any?, showMs: Long): CastLabelState? {
    val timer = remember(showMs) { CastLabelTimer(SystemClock::elapsedRealtime, showMs) }
    var state by remember { mutableStateOf<CastLabelState?>(null) }
    LaunchedEffect(text, count, key, showMs) {
        state = timer.update(text, count, key)
        // Wake up once, for the time the clock says is left.
        while (state?.expanded == true) {
            delay(timer.remainingMs().coerceAtLeast(1L))
            state = timer.tick()
        }
    }
    return state
}

/**
 * "Casting to 2 TVs" for 5 s, then a small teal dot with the number of TVs; any change expands it
 * again. Small, over the video, never focused.
 */
@Composable
fun CastingMarker(
    followers: Int,
    modifier: Modifier = Modifier,
    onExpandedChange: (Boolean) -> Unit = {},
) {
    val state = rememberCastLabelState(castingLabel(followers), followers, null, CAST_LABEL_SHOW_MS)
    val expandedNow = state?.expanded ?: true
    LaunchedEffect(expandedNow) { onExpandedChange(expandedNow) }
    if (state == null) return
    AnimatedContent(
        targetState = state.expanded,
        modifier = modifier,
        transitionSpec = {
            fadeIn(tween(CAST_LABEL_ANIMATION_MS)) togetherWith fadeOut(tween(CAST_LABEL_ANIMATION_MS)) using
                SizeTransform(clip = false) { _, _ -> tween(CAST_LABEL_ANIMATION_MS) }
        },
        label = "castingMarker",
    ) { expanded ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (expanded) 8.dp else 6.dp),
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(if (expanded) 8.dp else 16.dp))
                .padding(horizontal = if (expanded) 12.dp else 10.dp, vertical = 6.dp),
        ) {
            if (expanded) {
                Icon(quickIcon(QuickItem.CAST), contentDescription = null, tint = Tokens.Accent, modifier = Modifier.size(18.dp))
                Text(state.text, style = MaterialTheme.typography.labelLarge, color = Tokens.OnBackground, maxLines = 1)
            } else {
                Box(
                    Modifier
                        .size(10.dp)
                        .background(Tokens.Accent, CircleShape)
                        .semantics { contentDescription = state.text },
                )
                if ((state.count ?: 0) > 0) {
                    Text("${state.count}", style = MaterialTheme.typography.labelLarge, color = Tokens.OnBackground, maxLines = 1)
                }
            }
        }
    }
}
