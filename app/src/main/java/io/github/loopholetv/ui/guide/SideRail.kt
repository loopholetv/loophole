package io.github.loopholetv.ui.guide

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.loopholetv.R
import io.github.loopholetv.ui.theme.Tokens

val RAIL_WIDTH = 56.dp
private val RAIL_EXPANDED_WIDTH = 210.dp
private val LOGO_HEIGHT = 32.dp
private val MARK_WIDTH = 36.dp
private val WORDMARK_WIDTH = 92.dp

enum class RailItem(val label: String, val path: String) {
    SEARCH(
        "Search",
        "M15.5,14h-0.79l-0.28,-0.27C15.41,12.59 16,11.11 16,9.5 16,5.91 13.09,3 9.5,3S3,5.91 3,9.5 5.91,16 9.5,16" +
            "c1.61,0 3.09,-0.59 4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5zM9.5,14C7.01,14 5,11.99 5,9.5" +
            "S7.01,5 9.5,5 14,7.01 14,9.5 11.99,14 9.5,14z",
    ),
    TV(
        "TV",
        "M21,3H3c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h5v2h8v-2h5c1.1,0 1.99,-0.9 1.99,-2L23,5c0,-1.1 -0.9,-2 -2,-2z" +
            "M21,17H3V5h18v12z",
    ),
    // A TV with waves in the corner: the other TVs in the house.
    TVS(
        "TVs",
        "M20,3H4C2.89,3 2,3.89 2,5v12c0,1.1 0.89,2 2,2h4v2h8v-2h4c1.1,0 1.99,-0.9 1.99,-2L22,5C22,3.89 21.1,3 20,3z" +
            "M20,17H4V5h16V17zM4,14v2h2C6,14.89 5.11,14 4,14zM4,11v1.43c1.97,0 3.57,1.6 3.57,3.57H9C9,13.24 6.76,11 4,11z" +
            "M4,8v1.45c3.61,0 6.55,2.93 6.55,6.55H12C12,11.58 8.42,8 4,8z",
    ),
    RECORDINGS("Recordings", "M12,12m-7,0a7,7 0,1 1,14 0a7,7 0,1 1,-14 0"),
    MY_LIST("My list", "M17,3H7c-1.1,0 -1.99,0.9 -1.99,2L5,21l7,-3 7,3V5c0,-1.1 -0.9,-2 -2,-2z"),
    SETTINGS(
        "Settings",
        "M19.14,12.94c0.04,-0.3 0.06,-0.61 0.06,-0.94c0,-0.32 -0.02,-0.64 -0.07,-0.94l2.03,-1.58c0.18,-0.14 0.23,-0.41 " +
            "0.12,-0.61l-1.92,-3.32c-0.12,-0.22 -0.37,-0.29 -0.59,-0.22l-2.39,0.96c-0.5,-0.38 -1.03,-0.7 -1.62,-0.94" +
            "L14.4,2.81c-0.04,-0.24 -0.24,-0.41 -0.48,-0.41h-3.84c-0.24,0 -0.43,0.17 -0.47,0.41L9.25,5.35" +
            "C8.66,5.59 8.12,5.92 7.63,6.29L5.24,5.33c-0.22,-0.08 -0.47,0 -0.59,0.22L2.74,8.87C2.62,9.08 2.66,9.34 " +
            "2.86,9.48l2.03,1.58C4.84,11.36 4.8,11.69 4.8,12s0.02,0.64 0.07,0.94l-2.03,1.58c-0.18,0.14 -0.23,0.41 " +
            "-0.12,0.61l1.92,3.32c0.12,0.22 0.37,0.29 0.59,0.22l2.39,-0.96c0.5,0.38 1.03,0.7 1.62,0.94l0.36,2.54" +
            "c0.05,0.24 0.24,0.41 0.48,0.41h3.84c0.24,0 0.44,-0.17 0.47,-0.41l0.36,-2.54c0.59,-0.24 1.13,-0.56 " +
            "1.62,-0.94l2.39,0.96c0.22,0.08 0.47,0 0.59,-0.22l1.92,-3.32c0.12,-0.22 0.07,-0.47 -0.12,-0.61" +
            "L19.14,12.94zM12,15.6c-1.98,0 -3.6,-1.62 -3.6,-3.6s1.62,-3.6 3.6,-3.6s3.6,1.62 3.6,3.6S13.98,15.6 12,15.6z",
    ),
    ;

    val icon: ImageVector by lazy {
        ImageVector.Builder(label, 24.dp, 24.dp, 24f, 24f)
            .addPath(pathData = addPathNodes(path), fill = SolidColor(Color.White))
            .build()
    }
}

/**
 * Thin icon rail; while it holds focus it widens into a labelled menu drawn over the content.
 * Entering it lands on TV (the current screen); Right leaves it for the guide ([onRight]).
 */
@Composable
fun SideRail(
    onSelect: (RailItem) -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
    /** Right from any item: back into the guide (as TV does), not focus search. */
    onRight: () -> Unit = {},
    /** On TV (the current screen): the guide's Back opens the rail through it. */
    entryFocus: FocusRequester = remember { FocusRequester() },
) {
    var expanded by remember { mutableStateOf(false) }
    val tv = entryFocus
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .fillMaxHeight()
            .width(if (expanded) RAIL_EXPANDED_WIDTH else RAIL_WIDTH)
            .background(if (expanded) Tokens.Surface else Tokens.Background)
            // Not focus search: from the rail the nearest node can be the preview.
            .onPreviewKeyEvent { e ->
                if (e.key != Key.DirectionRight) return@onPreviewKeyEvent false
                if (e.type == KeyEventType.KeyDown) onRight()
                true
            }
            .onFocusChanged {
                if (it.hasFocus && !expanded) onFocused()
                expanded = it.hasFocus
            }
            // Entering from the guide always lands on TV (the current screen), whatever row it came from.
            .focusProperties { onEnter = { tv.requestFocus() } }
            .focusGroup()
            .padding(vertical = 24.dp, horizontal = 6.dp),
    ) {
        RailLogo(expanded)
        Spacer(Modifier.height(10.dp))
        for (item in RailItem.entries) {
            RailButton(
                item = item,
                current = item == RailItem.TV,
                expanded = expanded,
                onClick = { onSelect(item) },
                modifier = Modifier
                    .then(if (item == RailItem.TV) Modifier.focusRequester(tv) else Modifier)
                    // Up/Down stop at the ends of the rail instead of escaping into the guide.
                    .focusProperties {
                        if (item == RailItem.entries.first()) up = FocusRequester.Cancel
                        if (item == RailItem.entries.last()) down = FocusRequester.Cancel
                    },
            )
            if (item == RailItem.SEARCH) Spacer(Modifier.height(12.dp))
        }
    }
}

/**
 * Decoration only (never focusable): the mark, centred over the icons, and beside it the
 * wordmark, which fades in when the rail expands. Clipped to the rail.
 */
@Composable
private fun RailLogo(expanded: Boolean) {
    // Fades in on expand; gone at once on collapse, together with the rest of the wide rail.
    val word by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = if (expanded) tween(durationMillis = 220) else snap(),
        label = "railWordmark",
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(LOGO_HEIGHT)
            .clipToBounds()
            .wrapContentWidth(Alignment.Start, unbounded = true)
            .padding(start = 4.dp),
    ) {
        Image(painterResource(R.drawable.loophole_mark), contentDescription = null, modifier = Modifier.size(MARK_WIDTH, LOGO_HEIGHT))
        if (word > 0f) {
            Image(
                painterResource(R.drawable.loophole_wordmark),
                contentDescription = null,
                modifier = Modifier
                    .size(WORDMARK_WIDTH, LOGO_HEIGHT)
                    .graphicsLayer {
                        alpha = word
                        translationX = -(1f - word) * 6.dp.toPx()
                    },
            )
        }
    }
}

@Composable
private fun RailButton(item: RailItem, current: Boolean, expanded: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val tint = when {
        focused -> Tokens.Accent
        current -> Tokens.OnBackground
        else -> Tokens.Muted
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .background(if (focused) Tokens.SurfaceRaised else Color.Transparent, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp),
    ) {
        Icon(item.icon, contentDescription = item.label, tint = tint, modifier = Modifier.size(24.dp))
        if (expanded) {
            Text(
                text = item.label,
                style = MaterialTheme.typography.titleSmall,
                color = tint,
                maxLines = 1,
                modifier = Modifier.padding(start = 14.dp),
            )
        }
    }
}
