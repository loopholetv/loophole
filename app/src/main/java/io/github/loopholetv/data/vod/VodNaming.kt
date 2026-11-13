package io.github.loopholetv.data.vod

enum class VodKind { MOVIE, EPISODE }

/** Identifies one on-demand item within a playlist. */
data class VodRef(val kind: VodKind, val playlistId: Long, val key: String)

data class EpisodeNumber(val season: Int, val episode: Int)

/** Maps a playlist `tvg-type` value to a kind; anything other than movie or series is not on-demand. */
fun vodKindOf(tvgType: String?): VodKind? = when (tvgType?.trim()?.lowercase()) {
    "movie" -> VodKind.MOVIE
    "series" -> VodKind.EPISODE
    else -> null
}

/** Stable identity of a stream URL: the URL without its query string and fragment. */
fun vodKey(url: String): String = url.substringBefore('#').substringBefore('?')

private val EPISODE_NUMBER = Regex("""S(\d{1,3})\s*E(\d{1,4})""", RegexOption.IGNORE_CASE)
private val SHOW_NAME_TRIM = " -:|.([".toCharArray()

fun parseEpisodeNumber(name: String): EpisodeNumber? {
    val m = EPISODE_NUMBER.find(name) ?: return null
    return EpisodeNumber(m.groupValues[1].toInt(), m.groupValues[2].toInt())
}

/** Text before the season/episode marker, without trailing separators; null when there is no marker or no text. */
fun showNameFrom(name: String): String? {
    val m = EPISODE_NUMBER.find(name) ?: return null
    return name.substring(0, m.range.first).trim { it.isWhitespace() || it in SHOW_NAME_TRIM }
        .takeIf { it.isNotEmpty() }
}

data class EpisodeShowInput(val key: String, val group: String, val name: String)

data class ShowAssignment(val showKey: String, val showName: String, val category: String?)

private val WHITESPACE = Regex("\\s+")

private fun normalise(s: String) = s.lowercase().trim().replace(WHITESPACE, " ")

/**
 * Decides which show each episode belongs to. A group whose episodes share (nearly) one show name is
 * itself the show; otherwise the group is a category and shows are split out by the name in each title.
 * The result is keyed by episode key.
 */
fun assignShows(episodes: List<EpisodeShowInput>): Map<String, ShowAssignment> {
    val result = HashMap<String, ShowAssignment>(episodes.size)
    for ((group, eps) in episodes.groupBy { it.group }) {
        val names = eps.mapNotNull { showNameFrom(it.name)?.let(::normalise) }
        val mostCommon = names.groupingBy { it }.eachCount().values.maxOrNull() ?: 0
        val showStyle = group.isNotBlank() && (names.distinct().size <= 1 || mostCommon >= 0.8 * names.size)
        for (e in eps) {
            result[e.key] = if (showStyle) {
                ShowAssignment("g:$group", group, null)
            } else {
                val n = showNameFrom(e.name) ?: e.name
                ShowAssignment("n:$group\u001F${normalise(n)}", n, group.ifBlank { null })
            }
        }
    }
    return result
}

/** Orders by season, then episode (items without either last), then original [position]. */
fun <T> sortEpisodes(items: List<T>, season: (T) -> Int?, episode: (T) -> Int?, position: (T) -> Int): List<T> =
    items.sortedWith(
        compareBy<T> { season(it) ?: Int.MAX_VALUE }
            .thenBy { episode(it) ?: Int.MAX_VALUE }
            .thenBy { position(it) },
    )
