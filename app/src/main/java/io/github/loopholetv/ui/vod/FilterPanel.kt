package io.github.loopholetv.ui.vod

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.loopholetv.data.tmdb.Genre
import io.github.loopholetv.data.vod.RatingMin
import io.github.loopholetv.data.vod.RuntimeBand
import io.github.loopholetv.data.vod.VodFilter
import io.github.loopholetv.data.vod.VodSort
import io.github.loopholetv.ui.common.RequestFocusOnOpen
import io.github.loopholetv.ui.settings.ActionRow
import io.github.loopholetv.ui.theme.Tokens
import java.time.Year

/** How many genres or decades the summary names before it says how many more there are. */
private const val SUMMARY_NAMED = 2

private const val CHOSEN_MARK = "✓"

/** The decade label of a start year, "Before 1970" for [VodFilter.BEFORE_1970]. */
internal fun decadeLabel(d: Int): String = if (d == VodFilter.BEFORE_1970) "Before 1970" else "${d}s"

internal fun ratingLabel(r: RatingMin): String = r.value?.let { "${it.toInt()}+" } ?: "Any"

internal fun runtimeLabel(r: RuntimeBand): String = when (r) {
    RuntimeBand.ANY -> "Any"
    RuntimeBand.UNDER_90 -> "Under 90 min"
    RuntimeBand.FROM_90_TO_120 -> "90 min–2 h"
    RuntimeBand.OVER_120 -> "Over 2 h"
}

internal fun sortLabel(s: VodSort): String = when (s) {
    VodSort.RECENT -> "Recently added"
    VodSort.RATING -> "Rating"
    VodSort.YEAR -> "Year"
    VodSort.TITLE -> "Title"
    VodSort.RUNTIME -> "Running time"
}

/** The sorts the panel offers; running time only where titles have one shown (Movies). */
fun sortChoices(offersRuntime: Boolean): List<VodSort> =
    VodSort.entries.filter { offersRuntime || it != VodSort.RUNTIME }

/** The decades the panel offers: this one back to the 1970s, then "Before 1970". */
internal fun decadeChoices(year: Int = Year.now().value): List<Int> =
    (year / 10 * 10 downTo 1970 step 10).toList() + VodFilter.BEFORE_1970

private fun named(labels: List<String>): String? = when {
    labels.isEmpty() -> null
    labels.size <= SUMMARY_NAMED -> labels.joinToString(", ")
    else -> "${labels.first()} +${labels.size - 1}"
}

/**
 * The filter bar's line for [f], e.g. "Action · ★7+ · by rating". Genres are named from
 * [genreNames] (one not known is left out), decades newest first; "Recently added" is not said.
 */
fun filterSummary(f: VodFilter, genreNames: Map<Int, String>): String = listOfNotNull(
    named(f.genres.mapNotNull { genreNames[it] }.sortedWith(String.CASE_INSENSITIVE_ORDER)),
    named(f.decades.sortedWith(compareBy<Int> { it == VodFilter.BEFORE_1970 }.thenByDescending { it }).map(::decadeLabel)),
    f.rating.value?.let { "★${it.toInt()}+" },
    runtimeLabel(f.runtime).takeIf { f.runtime != RuntimeBand.ANY },
    when (f.sort) {
        VodSort.RECENT -> null
        VodSort.RUNTIME -> "by running time"
        else -> "by " + sortLabel(f.sort).lowercase()
    },
).joinToString(" · ")

/**
 * The Sort & filter panel: it slides in from the right over the screen, in the Settings rows'
 * style. Each choice applies at once ([onChange]); Back closes it ([onClose]).
 */
@Composable
internal fun FilterPanel(
    filter: VodFilter,
    genres: List<Genre>,
    offersRuntime: Boolean,
    onChange: (VodFilter) -> Unit,
    onClose: () -> Unit,
) {
    val first = remember { FocusRequester() }
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
            AnimatedVisibility(visibleState = shown, enter = slideInHorizontally { it }) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .fillMaxWidth(0.4f)
                        .fillMaxHeight()
                        .background(Tokens.Surface)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 27.dp),
                ) {
                    Text(SORT_FILTER_LABEL, style = MaterialTheme.typography.titleLarge, color = Tokens.OnBackground)
                    var firstTaken = false
                    fun firstRow(): Modifier = if (firstTaken) Modifier else Modifier.focusRequester(first).also { firstTaken = true }

                    if (genres.isNotEmpty()) {
                        Heading("Genre")
                        genres.forEach { g ->
                            val on = g.id in filter.genres
                            Choice(g.name, on, firstRow()) {
                                onChange(filter.copy(genres = if (on) filter.genres - g.id else filter.genres + g.id))
                            }
                        }
                    }
                    Heading("Year")
                    decadeChoices().forEach { d ->
                        val on = d in filter.decades
                        Choice(decadeLabel(d), on, firstRow()) {
                            onChange(filter.copy(decades = if (on) filter.decades - d else filter.decades + d))
                        }
                    }
                    Heading("Rating")
                    RatingMin.entries.forEach { r ->
                        Choice(ratingLabel(r), filter.rating == r) { onChange(filter.copy(rating = r)) }
                    }
                    if (offersRuntime) {
                        Heading("Running time")
                        RuntimeBand.entries.forEach { r ->
                            Choice(runtimeLabel(r), filter.runtime == r) { onChange(filter.copy(runtime = r)) }
                        }
                    }
                    Heading("Sort by")
                    sortChoices(offersRuntime).forEach { s ->
                        Choice(sortLabel(s), filter.sort == s) { onChange(filter.copy(sort = s)) }
                    }
                }
            }
        }
    }
    RequestFocusOnOpen(first)
}

@Composable
private fun Heading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = Tokens.Muted,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

/** A checkbox or radio row: a tick marks what is chosen. */
@Composable
private fun Choice(label: String, chosen: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    ActionRow(label = label, onClick = onClick, modifier = modifier, trailing = if (chosen) CHOSEN_MARK else null)
}
