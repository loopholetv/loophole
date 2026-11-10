package io.github.loopholetv.ui.player

import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.loopholetv.data.settings.audioDelayText
import io.github.loopholetv.ui.common.HeldKeyGate
import io.github.loopholetv.ui.common.KeyPhase
import io.github.loopholetv.ui.common.isOkKey
import io.github.loopholetv.ui.common.okPress
import io.github.loopholetv.ui.guide.tryFocus
import io.github.loopholetv.ui.theme.Tokens
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val TileWidth = 150.dp
private val TileHeight = 112.dp
private val TileShape = RoundedCornerShape(12.dp)
private val PanelWidth = 320.dp

const val AUDIO_DELAY_HINT = "Left/Right to change · OK to finish"

/**
 * The quick menu: a row of tiles along the bottom of the picture (icon, name, current value) and,
 * above the tile picked, its list or the audio delay stepper. Its own window: Back closes the list,
 * then the menu; Menu closes it at once. The rest of the held OK that opened it is swallowed.
 */
@Composable
fun QuickMenu(
    ui: QuickMenuUi,
    onPick: (QuickItem) -> Unit,
    onChoice: (QuickItem, String) -> Unit,
    onStep: (Int) -> Unit,
    onClosePanel: () -> Unit,
    onDismiss: () -> Unit,
) {
    val gate = remember { HeldKeyGate() }
    Dialog(
        onDismissRequest = { if (ui.panel != null) onClosePanel() else onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // A strip along the bottom that leaves the picture undimmed.
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            window?.setDimAmount(0f)
            window?.setGravity(Gravity.BOTTOM)
            window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val tileX = remember { mutableStateMapOf<QuickItem, Int>() }
        var width by remember { mutableIntStateOf(0) }
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { width = it.width }
                .onPreviewKeyEvent { e ->
                    val code = e.key.nativeKeyCode
                    val phase = when (e.type) {
                        KeyEventType.KeyDown -> KeyPhase.Down
                        KeyEventType.KeyUp -> KeyPhase.Up
                        else -> return@onPreviewKeyEvent false
                    }
                    if (code == KeyEvent.KEYCODE_MENU) {
                        if (phase == KeyPhase.Down && e.nativeKeyEvent.repeatCount == 0) onDismiss()
                        return@onPreviewKeyEvent true
                    }
                    !gate.allows(phase, isOkKey(code), e.nativeKeyEvent.repeatCount)
                }
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f))))
                .padding(start = 48.dp, end = 48.dp, top = 40.dp, bottom = 28.dp),
        ) {
            ui.panel?.let { panel ->
                val item = (panel as? QuickPanel.Choices)?.item ?: QuickItem.AUDIO_DELAY
                val panelPx = with(LocalDensity.current) { PanelWidth.roundToPx() }
                val startPx = with(LocalDensity.current) { 48.dp.roundToPx() }
                val x = ((tileX[item] ?: startPx) - startPx).coerceIn(0, (width - 2 * startPx - panelPx).coerceAtLeast(0))
                Box(Modifier.offset { IntOffset(x, 0) }) {
                    when (panel) {
                        is QuickPanel.Choices -> ChoiceList(panel, onChoice)
                        is QuickPanel.Delay -> DelayStepper(panel.ms, onStep, onClosePanel)
                    }
                }
            }
            Tiles(ui, onPick, onPosition = { item, x -> tileX[item] = x })
        }
    }
}

@Composable
private fun Tiles(ui: QuickMenuUi, onPick: (QuickItem) -> Unit, onPosition: (QuickItem, Int) -> Unit) {
    val listState = rememberLazyListState()
    val requesters = remember { QuickItem.entries.associateWith { FocusRequester() } }
    val panelOpen = ui.panel != null
    LaunchedEffect(panelOpen, ui.focus) {
        if (panelOpen) return@LaunchedEffect
        val index = ui.entries.indexOfFirst { it.item == ui.focus }.coerceAtLeast(0)
        if (listState.layoutInfo.visibleItemsInfo.none { it.index == index }) listState.scrollToItem(index)
        repeat(20) {
            if (requesters.getValue(ui.focus).tryFocus()) return@LaunchedEffect
            delay(30)
        }
    }
    LazyRow(state = listState, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        itemsIndexed(ui.entries, key = { _, e -> e.item }) { _, entry ->
            QuickTile(
                entry = entry,
                modifier = Modifier
                    .focusRequester(requesters.getValue(entry.item))
                    .onGloballyPositioned { onPosition(entry.item, it.positionInRoot().x.roundToInt()) },
                onClick = { onPick(entry.item) },
            )
        }
    }
}

@Composable
private fun QuickTile(entry: QuickEntry, modifier: Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val primary = if (focused) Tokens.OnFocus else Tokens.OnBackground
    val secondary = if (focused) Tokens.OnFocus.copy(alpha = 0.7f) else Tokens.Muted
    Column(
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .size(TileWidth, TileHeight)
            .okPress(onClick, null)
            .focusable(interactionSource = interaction)
            .background(if (focused) Tokens.FocusFill else Tokens.Surface.copy(alpha = 0.92f), TileShape)
            .padding(12.dp),
    ) {
        Icon(quickIcon(entry.item), contentDescription = null, tint = primary, modifier = Modifier.size(26.dp))
        Text(
            text = entry.label,
            style = MaterialTheme.typography.labelLarge,
            color = primary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        entry.value?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun PanelCard(content: @Composable () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .width(PanelWidth)
            .background(Tokens.SurfaceRaised, RoundedCornerShape(12.dp))
            .padding(10.dp),
    ) { content() }
}

@Composable
private fun ChoiceList(panel: QuickPanel.Choices, onChoice: (QuickItem, String) -> Unit) {
    val requesters = remember(panel.item, panel.options.size) { List(panel.options.size) { FocusRequester() } }
    LaunchedEffect(panel.item, requesters) {
        val index = panel.options.indexOfFirst { it.selected }.coerceAtLeast(0)
        repeat(20) {
            if (requesters.getOrNull(index)?.tryFocus() != false) return@LaunchedEffect
            delay(30)
        }
    }
    var focusedIndex by remember(panel.item) { mutableIntStateOf(0) }
    PanelCard {
        // Focus stays in the list while it is open: no D-pad move leaves it (Back closes it).
        val trap = Modifier.onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (e.key.nativeKeyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> true
                KeyEvent.KEYCODE_DPAD_UP -> focusedIndex == 0
                KeyEvent.KEYCODE_DPAD_DOWN -> focusedIndex == panel.options.lastIndex
                else -> false
            }
        }
        Column(trap.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            panel.options.forEachIndexed { i, choice ->
                val interaction = remember { MutableInteractionSource() }
                val focused by interaction.collectIsFocusedAsState()
                val color = if (focused) Tokens.OnFocus else Tokens.OnBackground
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(requesters[i])
                        .onFocusChanged { if (it.isFocused) focusedIndex = i }
                        .okPress({ onChoice(panel.item, choice.key) }, null)
                        .focusable(interactionSource = interaction)
                        .background(if (focused) Tokens.FocusFill else Color.Transparent, RoundedCornerShape(8.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = choice.label,
                        style = MaterialTheme.typography.titleSmall,
                        color = color,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (choice.selected) Text("✓", style = MaterialTheme.typography.titleSmall, color = if (focused) color else Tokens.Accent)
                }
            }
        }
    }
}

@Composable
private fun DelayStepper(ms: Int, onStep: (Int) -> Unit, onDone: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(20) {
            if (focus.tryFocus()) return@LaunchedEffect
            delay(30)
        }
    }
    PanelCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focus)
                .onKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (e.key.nativeKeyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> { onStep(-1); true }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> { onStep(+1); true }
                        // Up/Down have nowhere to go while the stepper is open.
                        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> true
                        else -> false
                    }
                }
                .okPress(onDone, null)
                .focusable()
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text("Audio delay  ${audioDelayText(ms)}", style = MaterialTheme.typography.titleMedium, color = Tokens.OnBackground)
            Text(AUDIO_DELAY_HINT, style = MaterialTheme.typography.bodySmall, color = Tokens.Muted, modifier = Modifier.padding(top = 6.dp))
        }
    }
}
