package io.github.loopholetv.record

enum class Use { PREVIEW, PLAYER, CAST, RECORDING }

data class StreamUse(val host: String, val what: Use, val channelId: Long? = null)

sealed interface StartDecision {
    data object Start : StartDecision
    data object TakePreview : StartDecision
    data class TakeWatched(val channelId: Long) : StartDecision
    data class Warn(val stops: Use) : StartDecision
    data object OverLimit : StartDecision
}

/** Whether a recording may open a connection on [host] now, and what it has to take over if not. */
fun decideStart(host: String, limit: Int, uses: List<StreamUse>, watchingSameChannel: Long?): StartDecision {
    val here = uses.filter { it.host == host }
    if (here.size < limit) return StartDecision.Start
    if (watchingSameChannel != null &&
        here.any { it.what != Use.RECORDING && it.channelId == watchingSameChannel }
    ) {
        return StartDecision.TakeWatched(watchingSameChannel)
    }
    val others = here.filter { it.what != Use.RECORDING }
    if (others.isNotEmpty() && others.all { it.what == Use.PREVIEW }) return StartDecision.TakePreview
    val busy = others.firstOrNull { it.what == Use.PLAYER || it.what == Use.CAST }
    if (busy != null) return StartDecision.Warn(busy.what)
    return StartDecision.OverLimit
}
