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

/** Most shows listed in the Continue watching row. */
const val CONTINUE_LIMIT = 20

/** The "Next episode" button appears this long before the end of a normal episode. */
const val NEXT_WINDOW_MS = 120_000L

/** Episodes shorter than this use a fraction of their length for the next-episode window. */
const val SHORT_EPISODE_MS = 600_000L

/** Fraction of a short episode's length, at the end, in which the next-episode button shows. */
const val SHORT_EPISODE_WINDOW_FRACTION = 0.2

/** The "Next episode" button hides itself after this long if it is not used. */
const val NEXT_PILL_HIDE_MS = 10_000L

/** Seconds counted down before the next episode starts by itself. */
const val COUNTDOWN_SECONDS = 10

/** After this many episodes in a row that start by themselves, ask whether anyone is still watching. */
const val STILL_WATCHING_AFTER = 3

/** The "Still watching?" prompt stops playback after this long without an answer. */
const val STILL_WATCHING_TIMEOUT_MS = 60_000L
