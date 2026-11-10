package io.github.loopholetv.ui.player

import android.text.format.DateFormat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.loopholetv.LoopholeApp
import io.github.loopholetv.data.db.ChannelScope
import io.github.loopholetv.data.db.ref
import io.github.loopholetv.ui.guide.GuideFormat
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.ZoneId

/** Builds [PlayerViewModel] from the app container (see [PlayerViewModel.factory]). */
internal fun playerViewModelFactory(channelId: Long, scope: ChannelScope, archiveFrom: Instant?): ViewModelProvider.Factory =
    viewModelFactory {
        initializer {
            val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as LoopholeApp
            val c = app.container
            val format = GuideFormat(DateFormat.is24HourFormat(app), ZoneId.systemDefault())
            PlayerViewModel(
                channelId = channelId,
                scope = scope,
                playback = c.playback,
                channels = DaoPlayerChannels(c.db.channelDao(), c.scopedChannels),
                nowAndNext = c.shiftedProgrammes::nowAndNext,
                formatClock = { "${format.date(it)}  ${format.time(it)}" },
                favouriteRefs = c.favourites.refs,
                toggleFavourite = c.favourites::toggle,
                recent = c.history.recent(),
                startGate = c.startGate,
                settings = c.settingsStore.settings.map { it.playback },
                updatePlayback = { t -> c.settingsStore.update { it.copy(playback = t(it.playback)) } },
                channelPlayback = { c.channelPlayback.observe(it.ref) },
                setAspect = { ch, mode -> c.channelEdits.setAspect(ch.ref, mode) },
                sleepTimer = c.sleepTimer,
                previewHold = c.previewHold,
                pipSupported = c.pipSupported,
                savedState = createSavedStateHandle(),
                pipGate = c.pipGate,
                remote = c.settingsStore.settings.map { it.remote },
                archiveFrom = archiveFrom,
                format = format,
            )
        }
    }
