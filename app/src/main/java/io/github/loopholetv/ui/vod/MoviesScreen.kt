package io.github.loopholetv.ui.vod

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.loopholetv.data.vod.VodKind

/** Films from the playlists, in rows by category. */
@Composable
fun MoviesScreen(
    onOpenFilm: (playlistId: Long, key: String) -> Unit,
    onBack: () -> Unit,
) {
    val vm: VodBrowseViewModel = viewModel(factory = VodBrowseViewModel.Factory(VodKind.MOVIE))
    VodBrowse(heading = "Movies", vm = vm, onOpen = onOpenFilm, onBack = onBack)
}
