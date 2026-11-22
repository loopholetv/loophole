package io.github.loopholetv.record

import io.github.loopholetv.data.db.ChannelEntity
import io.github.loopholetv.data.db.PlaylistEntity
import io.github.loopholetv.data.db.PlaylistType
import java.net.URI

/** Playlists on one provider share its connection limit. Each file playlist is its own provider. */
fun providerHost(p: PlaylistEntity): String {
    val address = when (p.type) {
        PlaylistType.M3U_FILE -> return "file:${p.id}"
        PlaylistType.XTREAM -> p.xtreamServer
        PlaylistType.STALKER -> p.portalEndpoint?.takeIf { it.isNotBlank() } ?: p.url
        PlaylistType.M3U -> p.url
    }.orEmpty().trim()
    val host = runCatching { URI(address).host }.getOrNull()
        ?: runCatching { URI("http://$address").host }.getOrNull()
    return (host ?: address).lowercase()
}

private val QUALITY_SUFFIX = Regex("(^|[^a-z0-9])(hd|fhd|uhd|4k)\\W*$")

fun channelNameKey(name: String): String =
    name.lowercase().replace(QUALITY_SUFFIX, "").filter { it.isLetterOrDigit() }

/** Two guide ids that differ are never the same channel; the name is compared only when one side has no guide id. */
fun sameChannel(a: ChannelEntity, b: ChannelEntity): Boolean {
    val ga = a.guideEpgId?.trim().orEmpty()
    val gb = b.guideEpgId?.trim().orEmpty()
    if (ga.isNotEmpty() && gb.isNotEmpty()) return ga.equals(gb, ignoreCase = true)
    val key = channelNameKey(a.name)
    return key.isNotEmpty() && key == channelNameKey(b.name)
}
