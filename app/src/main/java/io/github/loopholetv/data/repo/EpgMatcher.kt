package io.github.loopholetv.data.repo

import io.github.loopholetv.data.xmltv.XmltvChannel

/** The fields of a playlist channel used to find its guide channel. */
data class ChannelKeyView(val id: Long, val tvgId: String?, val tvgName: String?, val name: String)

/**
 * Matches playlist channels to XMLTV channels: an exact (case-insensitive) `tvg-id` == XMLTV id wins;
 * otherwise `normalise(tvgName ?: name)` must equal the normalised form of one of the XMLTV
 * channel's display names. Ties go to the first XMLTV channel in [match]'s map order.
 */
object EpgMatcher {
    private val COUNTRY_PREFIX = Regex("^[A-Za-z]{2,3}\\s*[:|\\-]\\s*")
    private val QUALITY_TAGS = Regex("\\b(hd|fhd|uhd|sd|4k|hevc|h265|raw|backup)\\b")
    private val NON_ALNUM = Regex("[^a-z0-9]")

    fun normalise(name: String): String = name.trim()
        .replace(COUNTRY_PREFIX, "") // "AU: ", "US| "
        .lowercase()
        .replace(QUALITY_TAGS, "")
        .replace(NON_ALNUM, "")

    /** Channel id → XMLTV id, for the channels that matched; unmatched channels are absent. */
    fun match(channels: List<ChannelKeyView>, epgChannels: Map<String, XmltvChannel>): Map<Long, String> =
        Index(channels).match(epgChannels)

    /**
     * Precomputed lookup over [channels] that can be fed XMLTV channels in several rounds (one per
     * source, or per late `<channel>`); a channel matched in an earlier round is never re-matched.
     */
    internal class Index(channels: List<ChannelKeyView>) {
        private val byTvgId = HashMap<String, MutableList<Long>>()
        private val byName = HashMap<String, MutableList<Long>>()
        private val matched = HashSet<Long>()

        init {
            for (c in channels) {
                c.tvgId?.takeIf { it.isNotBlank() }?.let { byTvgId.getOrPut(it.lowercase()) { ArrayList(1) } += c.id }
                normalise(c.tvgName ?: c.name).takeIf { it.isNotEmpty() }?.let { byName.getOrPut(it) { ArrayList(1) } += c.id }
            }
        }

        private val total = channels.size

        val hasUnmatched: Boolean get() = matched.size < total

        fun match(epgChannels: Map<String, XmltvChannel>): Map<Long, String> {
            val result = LinkedHashMap<Long, String>()
            fun assign(ids: List<Long>?, epgId: String) {
                ids?.forEach { if (it !in matched && it !in result) result[it] = epgId }
            }
            for (epgId in epgChannels.keys) assign(byTvgId[epgId.lowercase()], epgId)
            for ((epgId, channel) in epgChannels) {
                for (displayName in channel.displayNames) {
                    val key = normalise(displayName)
                    if (key.isNotEmpty()) assign(byName[key], epgId)
                }
            }
            matched += result.keys
            return result
        }

        /** Makes [channelIds] matchable again (their match came from a source that failed). */
        fun release(channelIds: Collection<Long>) {
            matched -= channelIds.toSet()
        }
    }
}
