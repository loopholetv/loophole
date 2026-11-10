package io.github.loopholetv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.loopholetv.player.SleepState
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.loopholetv.ui.theme.Tokens

const val SLEEP_WARNING_HINT = "Press any button to keep watching."

/** "Sleep timer: stopping in m:ss" (60 s reads 1:00, 59 s reads 0:59). */
fun sleepWarningText(secondsLeft: Int): String {
    val s = secondsLeft.coerceAtLeast(0)
    return "Sleep timer: stopping in ${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

/** The warning card for [sleep]; only this reads the seconds left, so only it recomposes as they count. */
@Composable
fun SleepWarningHost(sleep: StateFlow<SleepState>, modifier: Modifier = Modifier) {
    val state by sleep.collectAsStateWithLifecycle()
    (state as? SleepState.Warning)?.let { SleepWarning(it.secondsLeft, modifier) }
}

/** The card shown during the sleep timer's last minute; any button keeps watching. */
@Composable
fun SleepWarning(secondsLeft: Int, modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
            .background(Tokens.SurfaceRaised, RoundedCornerShape(12.dp))
            .padding(horizontal = 24.dp, vertical = 14.dp),
    ) {
        Text(sleepWarningText(secondsLeft), style = MaterialTheme.typography.titleMedium, color = Tokens.OnBackground)
        Text(SLEEP_WARNING_HINT, style = MaterialTheme.typography.bodyMedium, color = Tokens.Muted)
    }
}
