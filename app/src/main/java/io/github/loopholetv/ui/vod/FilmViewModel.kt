package io.github.loopholetv.ui.vod

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.loopholetv.LoopholeApp
import io.github.loopholetv.data.vod.VodKind
import io.github.loopholetv.data.vod.VodProgressRepository
import io.github.loopholetv.data.vod.VodRef
import io.github.loopholetv.data.vod.VodRepository
import io.github.loopholetv.data.vod.resumeFrom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * What the film page shows. [resumeMs] is where a started film resumes (null: not started) and
 * [fraction] how far along it is, when the length is known.
 */
data class FilmUi(
    val title: String,
    val category: String,
    val logo: String?,
    val resumeMs: Long?,
    val fraction: Float?,
)

/** A button on the film page and where it starts playback, in ms. */
data class FilmButton(val label: String, val startMs: Long)

/** [Play] for a film that has not been started; otherwise [Resume from ...] and [Start over]. */
fun filmButtons(ui: FilmUi): List<FilmButton> =
    if (ui.resumeMs == null) listOf(FilmButton("Play", 0L))
    else listOf(FilmButton(resumeLabel(ui.resumeMs), ui.resumeMs), FilmButton("Start over", 0L))

/** "Resume from 42:10", or "Resume from 1:02:03" from an hour on. */
fun resumeLabel(positionMs: Long): String {
    val total = positionMs / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return "Resume from " + if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** One film, with where its playback would resume. [ui] is null while loading and when the film is gone. */
class FilmViewModel(
    playlistId: Long,
    key: String,
    repo: VodRepository,
    progress: VodProgressRepository,
) : ViewModel() {
    private val _ui = MutableStateFlow<FilmUi?>(null)
    private val _loaded = MutableStateFlow(false)
    val ui: StateFlow<FilmUi?> = _ui

    /** Whether [ui] has been read from the database yet: a null [ui] before that is only "loading". */
    val loaded: StateFlow<Boolean> = _loaded

    init {
        viewModelScope.launch {
            combine(repo.film(playlistId, key), progress.observe(VodRef(VodKind.MOVIE, playlistId, key))) { detail, p ->
                detail?.let {
                    val resume = resumeFrom(p)
                    val duration = p?.durationMs
                    FilmUi(
                        title = it.film.name,
                        category = it.film.category,
                        logo = it.film.logo,
                        resumeMs = resume,
                        fraction = if (resume != null && duration != null && duration > 0) {
                            (resume.toFloat() / duration).coerceIn(0f, 1f)
                        } else null,
                    )
                }
            }.collect {
                _ui.value = it
                _loaded.value = true
            }
        }
    }
}

fun filmViewModelFactory(playlistId: Long, key: String): ViewModelProvider.Factory = viewModelFactory {
    initializer {
        val c = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as LoopholeApp).container
        FilmViewModel(playlistId, key, c.vod, c.vodProgress)
    }
}
