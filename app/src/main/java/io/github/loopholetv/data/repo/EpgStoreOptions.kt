package io.github.loopholetv.data.repo

import io.github.loopholetv.data.settings.AUTO_PAST_DAYS
import io.github.loopholetv.data.settings.AppSettings
import io.github.loopholetv.data.settings.effectivePastDays

/** What an EPG refresh keeps: [pastDays] of already-ended programmes (0 = none beyond a short grace) and, optionally, descriptions. */
data class EpgStoreOptions(val pastDays: Int = 0, val storeDescriptions: Boolean = true)

/**
 * The options for one refresh. With the automatic past-days setting the history follows the longest archive
 * among the visible catch-up channels ([maxCatchupDays] is only asked then); no such channel keeps none.
 */
suspend fun epgStoreOptions(settings: AppSettings, maxCatchupDays: suspend () -> Int?): EpgStoreOptions =
    EpgStoreOptions(
        pastDays = effectivePastDays(
            settings.epgPastDays,
            if (settings.epgPastDays == AUTO_PAST_DAYS) maxCatchupDays() else null,
        ),
        storeDescriptions = settings.epgStoreDescriptions,
    )
