package io.github.loopholetv.ui.vod

import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.loopholetv.LoopholeApp
import io.github.loopholetv.data.vod.VodRef

/** Builds [VodPlayerViewModel] from the app container. */
internal fun vodPlayerViewModelFactory(ref: VodRef, startMs: Long): ViewModelProvider.Factory = viewModelFactory {
    initializer {
        val c = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as LoopholeApp).container
        VodPlayerViewModel(
            ref = ref,
            startMs = startMs,
            playback = c.playback,
            items = c.vod::playable,
            progress = c.vodProgress,
            next = c.vod::nextEpisode,
            label = { next -> c.vod.episodeInfo(next)?.let { countdownLabel(it.season, it.episode, it.name) } },
            autoPlay = { c.settingsSnapshot.current.playback.autoPlayNext },
            nowMs = SystemClock::elapsedRealtime,
        )
    }
}
