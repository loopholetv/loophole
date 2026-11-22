package io.github.loopholetv.ui.guide

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.loopholetv.data.db.ChannelEntity
import io.github.loopholetv.data.db.ProgrammeEntity
import io.github.loopholetv.record.Mark
import io.github.loopholetv.ui.onboarding.PillButton
import io.github.loopholetv.ui.theme.Tokens

/** A programme's details open for [programme] on [channel]. */
data class DetailsTarget(val channel: ChannelEntity, val programme: ProgrammeEntity)

/** What the details dialog's booking buttons do. */
enum class DetailsButton(val label: String) {
    REMIND("Remind me"),
    REMIND_SERIES("Remind series"),
    RECORD("Record"),
    RECORD_SERIES("Record series"),
    CANCEL_REMINDER("Cancel reminder"),
    CANCEL_SERIES_REMINDER("Cancel series reminder"),
    CANCEL_RECORDING("Cancel recording"),
    CANCEL_SERIES("Cancel series"),
}

/** The four booking buttons of a future programme; each turns into its "Cancel ..." when [booked] has that booking. */
fun detailsButtons(booked: CellBookings): List<DetailsButton> = listOf(
    if (booked.reminder) DetailsButton.CANCEL_REMINDER else DetailsButton.REMIND,
    if (booked.reminderSeries) DetailsButton.CANCEL_SERIES_REMINDER else DetailsButton.REMIND_SERIES,
    if (booked.recording) DetailsButton.CANCEL_RECORDING else DetailsButton.RECORD,
    if (booked.recordingSeries) DetailsButton.CANCEL_SERIES else DetailsButton.RECORD_SERIES,
)

/** Runs what [button] says for [p] on [c]. */
fun GuideViewModel.onDetailsButton(c: ChannelEntity, p: ProgrammeEntity, button: DetailsButton) {
    when (button) {
        DetailsButton.REMIND -> remind(c, p)
        DetailsButton.REMIND_SERIES -> remindSeries(c, p)
        DetailsButton.RECORD -> record(c, p)
        DetailsButton.RECORD_SERIES -> recordSeries(c, p)
        DetailsButton.CANCEL_REMINDER -> cancel(c, p, Mark.REMINDER)
        DetailsButton.CANCEL_SERIES_REMINDER -> cancel(c, p, Mark.REMINDER_SERIES)
        DetailsButton.CANCEL_RECORDING -> cancel(c, p, Mark.RECORDING)
        DetailsButton.CANCEL_SERIES -> cancel(c, p, Mark.RECORDING_SERIES)
    }
}

/**
 * A programme's details. A programme that has not started also offers the booking buttons (the first
 * has focus); [warning] shows under them after a booking that has a caveat.
 */
@Composable
fun ProgrammeDetailsDialog(
    programme: ProgrammeEntity,
    format: GuideFormat,
    /** What is booked on the showing; null until it is looked up (no booking buttons until then). */
    booked: CellBookings?,
    bookable: Boolean,
    warning: String?,
    onButton: (DetailsButton) -> Unit,
    onClose: () -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(booked == null) { if (booked != null || !bookable) first.tryFocus() }
    Dialog(onDismissRequest = onClose) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .width(520.dp)
                .background(Tokens.SurfaceRaised, RoundedCornerShape(12.dp))
                .padding(28.dp),
        ) {
            Text(programme.title, style = MaterialTheme.typography.headlineSmall, color = Tokens.OnBackground)
            Text(format.range(programme), style = MaterialTheme.typography.bodyMedium, color = Tokens.Accent)
            Text(
                text = programme.description?.takeIf { it.isNotBlank() } ?: "No description",
                style = MaterialTheme.typography.bodyMedium,
                color = Tokens.Muted,
            )
            if (bookable && booked != null) {
                detailsButtons(booked).forEachIndexed { i, button ->
                    PillButton(
                        button.label,
                        selected = false,
                        modifier = Modifier.then(if (i == 0) Modifier.focusRequester(first) else Modifier),
                        onClick = { onButton(button) },
                    )
                }
                if (warning != null) {
                    Text("⚠ $warning", style = MaterialTheme.typography.bodyMedium, color = Tokens.Error)
                }
            }
            Button(
                onClick = onClose,
                modifier = Modifier.padding(top = 8.dp).then(if (bookable) Modifier else Modifier.focusRequester(first)),
            ) { Text("Close") }
        }
    }
}
