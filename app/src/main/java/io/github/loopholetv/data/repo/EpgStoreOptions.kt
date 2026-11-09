package io.github.loopholetv.data.repo

/** What an EPG refresh keeps: [pastDays] of already-ended programmes (0 = none beyond a short grace) and, optionally, descriptions. */
data class EpgStoreOptions(val pastDays: Int = 0, val storeDescriptions: Boolean = true)
