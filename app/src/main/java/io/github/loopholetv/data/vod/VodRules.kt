package io.github.loopholetv.data.vod

/** Tunable thresholds for on-demand (film and series) content. */
const val RECENT_DAYS = 14
const val RECENT_LIMIT = 50

/** Fraction of the duration after which an item counts as watched. */
const val WATCHED_FRACTION = 0.9

/** Positions before this many milliseconds are treated as not started. */
const val NOT_STARTED_MS = 30_000L

/** How often playback progress is saved, in milliseconds. */
const val SAVE_INTERVAL_MS = 10_000L

/** Progress rows whose item no longer exists are dropped after this many days. */
const val ORPHAN_PROGRESS_DAYS = 90

/** At or above this many shows, the "All shows" list is shown as a grid. */
const val ALL_SHOWS_GRID_THRESHOLD = 200
