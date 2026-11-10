package io.github.loopholetv.ui.manage

import io.github.loopholetv.data.db.ChannelEntity
import io.github.loopholetv.data.db.ChannelRef
import io.github.loopholetv.data.db.GroupSort
import io.github.loopholetv.data.settings.DecoderChoice

/** Records every edit as a readable line, e.g. `rename(1:k1, null)`. */
internal class FakeEditActions : EditActions {
    val calls = mutableListOf<String>()
    var moveResult = true

    private fun ChannelRef.s() = "$playlistId:$key"

    override suspend fun setHidden(ref: ChannelRef, hidden: Boolean) { calls += "setHidden(${ref.s()}, $hidden)" }
    override suspend fun rename(ref: ChannelRef, name: String?) { calls += "rename(${ref.s()}, $name)" }
    override suspend fun setLogo(ref: ChannelRef, url: String?) { calls += "setLogo(${ref.s()}, $url)" }
    override suspend fun setEpgOverride(ref: ChannelRef, epgId: String?) { calls += "setEpgOverride(${ref.s()}, $epgId)" }
    override suspend fun setShift(ref: ChannelRef, minutes: Int) { calls += "setShift(${ref.s()}, $minutes)" }
    override suspend fun setVideoDecoder(ref: ChannelRef, choice: DecoderChoice?) { calls += "setVideoDecoder(${ref.s()}, $choice)" }
    override suspend fun setAudioDecoder(ref: ChannelRef, choice: DecoderChoice?) { calls += "setAudioDecoder(${ref.s()}, $choice)" }
    override suspend fun setExternalPlayer(ref: ChannelRef, choice: String?) { calls += "setExternalPlayer(${ref.s()}, $choice)" }
    override suspend fun move(channel: ChannelEntity, direction: Int): Boolean {
        calls += "move(${channel.key}, $direction)"
        return moveResult
    }
    override suspend fun setGroupHidden(playlistId: Long, title: String, hidden: Boolean) {
        calls += "setGroupHidden($playlistId, $title, $hidden)"
    }
    override suspend fun renameGroup(playlistId: Long, title: String, name: String?) {
        calls += "renameGroup($playlistId, $title, $name)"
    }
    override suspend fun moveGroup(playlistId: Long, title: String, direction: Int): Boolean {
        calls += "moveGroup($playlistId, $title, $direction)"
        return moveResult
    }
    override suspend fun setGroupSort(playlistId: Long, title: String, sort: GroupSort) {
        calls += "setGroupSort($playlistId, $title, $sort)"
    }
}

internal fun testChannel(
    id: Long = 1,
    name: String = "BBC One",
    customName: String? = null,
    customLogo: String? = null,
    visible: Boolean = true,
    epgChannelId: String? = "bbc1.uk",
    guideEpgId: String? = epgChannelId,
    shift: Int = 0,
    number: Int = id.toInt(),
    group: String = "News",
) = ChannelEntity(
    id = id, playlistId = 1, key = "k$id", number = number, name = name, streamUrl = "http://example.test/$id",
    logo = null, groupTitle = group, groupOrder = 0, sortOrder = id.toInt(), tvgId = null, tvgName = null,
    userAgent = null, referrer = null, catchupType = null, catchupDays = null, catchupSource = null,
    epgChannelId = epgChannelId, visible = visible, guideNumber = number + 100, customName = customName,
    customLogo = customLogo, guideEpgId = guideEpgId, epgShiftMinutes = shift,
)
