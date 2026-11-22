package io.github.loopholetv.ui.vod

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.loopholetv.data.vod.VodKind
import io.github.loopholetv.data.vod.VodRef

/** Shows from the playlists, in rows by category. */
@Composable
fun SeriesScreen(
    onOpenShow: (playlistId: Long, key: String) -> Unit,
    onPlay: (VodRef, Long) -> Unit,
    onBack: () -> Unit,
    /** OK on a reminder card in My list: the guide, at that showing. */
    onOpenReminder: (ProgrammeTarget) -> Unit = {},
) {
    val vm: VodBrowseViewModel = viewModel(factory = VodBrowseViewModel.Factory(VodKind.EPISODE), key = "series")
    val starter = rememberVodStarter(rememberCast()) { ref, ms -> onPlay(ref, ms) }
    VodBrowse(heading = "Series", vm = vm, onOpen = onOpenShow, onPlay = { starter.start(it.ref, it.startMs) }, onBack = onBack, onOpenReminder = onOpenReminder)
}
