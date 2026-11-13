package io.github.loopholetv.ui.vod

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.loopholetv.data.vod.VodKind

/** Shows from the playlists, in rows by category. */
@Composable
fun SeriesScreen(
    onOpenShow: (playlistId: Long, key: String) -> Unit,
    onBack: () -> Unit,
) {
    val vm: VodBrowseViewModel = viewModel(factory = VodBrowseViewModel.Factory(VodKind.EPISODE), key = "series")
    VodBrowse(heading = "Series", vm = vm, onOpen = onOpenShow, onBack = onBack)
}
