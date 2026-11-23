package io.github.loopholetv.data.vod

/** How the Sort & filter grid orders its titles. */
enum class VodSort { RECENT, RATING, YEAR, TITLE, RUNTIME }

/** The lowest TMDB rating a title may have; [value] is null for any rating. */
enum class RatingMin(val value: Double?) { ANY(null), SIX(6.0), SEVEN(7.0), EIGHT(8.0) }

/** Running time bands in minutes: under 90, 90 to 120 (both included), over 120. */
enum class RuntimeBand { ANY, UNDER_90, FROM_90_TO_120, OVER_120 }

/** The earliest decade offered by itself; earlier years share [BEFORE_1970]. */
private const val FIRST_DECADE = 1970

/**
 * A Movies or Series Sort & filter choice. [genres] are TMDB genre ids (a title matches if it has
 * any). A decade is its start year (1970, 1980…), and [BEFORE_1970] stands for every earlier year.
 */
data class VodFilter(
    val genres: Set<Int> = emptySet(),
    val decades: Set<Int> = emptySet(),
    val rating: RatingMin = RatingMin.ANY,
    val runtime: RuntimeBand = RuntimeBand.ANY,
    val sort: VodSort = VodSort.RECENT,
) {
    val isDefault: Boolean get() = this == VodFilter()

    /** Whether anything besides the sort is chosen; only then are titles without details left out. */
    val hasFilter: Boolean
        get() = genres.isNotEmpty() || decades.isNotEmpty() || rating != RatingMin.ANY || runtime != RuntimeBand.ANY

    /** A compact text form for preferences, read back by [decode]. */
    fun encode(): String = listOf(
        "g=" + genres.sorted().joinToString(","),
        "d=" + decades.sorted().joinToString(","),
        "r=" + rating.name,
        "t=" + runtime.name,
        "s=" + sort.name,
    ).joinToString(";")

    companion object {
        const val BEFORE_1970 = 0

        /** Reads [encode]'s form. Each part that is missing or can't be read falls back to its default. */
        fun decode(s: String?): VodFilter {
            if (s.isNullOrBlank()) return VodFilter()
            val parts = s.split(';').mapNotNull { part ->
                val eq = part.indexOf('=')
                if (eq <= 0) null else part.substring(0, eq) to part.substring(eq + 1)
            }.toMap()
            return VodFilter(
                genres = ints(parts["g"]).filter { it > 0 }.toSet(),
                decades = ints(parts["d"]).filter { it == BEFORE_1970 || (it >= FIRST_DECADE && it % 10 == 0) }.toSet(),
                rating = enumOr(parts["r"], RatingMin.ANY),
                runtime = enumOr(parts["t"], RuntimeBand.ANY),
                sort = enumOr(parts["s"], VodSort.RECENT),
            )
        }

        private fun ints(s: String?): List<Int> = s?.split(',')?.mapNotNull { it.trim().toIntOrNull() }.orEmpty()

        private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
            enumValues<E>().firstOrNull { it.name == name } ?: default
    }
}
