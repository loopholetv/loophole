package io.github.loopholetv.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import io.github.loopholetv.LoopholeApp
import io.github.loopholetv.data.db.PlaylistEntity
import io.github.loopholetv.data.db.PlaylistType
import io.github.loopholetv.data.repo.ImportError
import io.github.loopholetv.data.repo.ImportResult
import io.github.loopholetv.data.repo.PORTAL_ERROR_COPY
import io.github.loopholetv.data.repo.SourceEdit
import io.github.loopholetv.data.repo.errorCopy
import io.github.loopholetv.data.repo.localFileDisplayName
import io.github.loopholetv.data.stalker.PortalConfig
import io.github.loopholetv.data.stalker.isInfomirMac
import io.github.loopholetv.data.stalker.normaliseMac
import io.github.loopholetv.data.xtream.XtreamCredentials
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI

/** The four kinds of playlist the form offers, in on-screen order. */
enum class Mode(val label: String) { M3U("M3U link"), FILE("Local file"), XTREAM("Xtream Codes"), PORTAL("MAC portal") }

sealed interface Phase {
    data object Editing : Phase
    data class LoadingChannels(val count: Int) : Phase
    data class LoadingGuide(val programmes: Int = 0) : Phase
    data object Done : Phase
    data class Failed(val message: String) : Phase
}

/** What a MAC field starts with: the Infomir prefix most portals expect. */
const val MAC_PREFIX = "00:1A:79:"
const val INVALID_MAC = "Enter the MAC address as 12 letters or digits, e.g. 00:1A:79:12:34:56."
const val NON_INFOMIR_MAC = "This MAC doesn't start with 00:1A:79. Some portals only accept those."
const val FILE_UNREADABLE = "Couldn't open the file."

data class AddPlaylistUi(
    val mode: Mode = Mode.M3U,
    val name: String = "",
    val url: String = "",
    val server: String = "",
    val user: String = "",
    val pass: String = "",
    /** The picked `content://` URI or absolute path; [fileName] is what the user sees. */
    val fileLocation: String = "",
    val fileName: String = "",
    val portalUrl: String = "",
    val mac: String = MAC_PREFIX,
    val portalLogin: String = "",
    val portalPass: String = "",
    // What the user typed; the repository derives blanks from the MAC only when a new portal is added.
    val serial: String = "",
    val deviceId: String = "",
    val deviceId2: String = "",
    val signature: String = "",
    val moreOptions: Boolean = false,
    /** Set by a submit with an invalid MAC; cleared when the MAC or the kind changes. */
    val macError: String? = null,
    /** Counts blocked submits, so each one brings the MAC field back into view. */
    val macErrorShown: Int = 0,
    /** Editing an existing playlist (its name is changed in Settings, not here). */
    val editing: Boolean = false,
    /** False while an edited playlist is being read. */
    val loaded: Boolean = true,
    val phase: Phase = Phase.Editing,
) {
    val busy: Boolean
        get() = !loaded || phase is Phase.LoadingChannels || phase is Phase.LoadingGuide || phase is Phase.Done

    /** A complete MAC outside the Infomir range: a warning only. */
    val macWarning: String?
        get() = normaliseMac(mac)?.takeUnless(::isInfomirMac)?.let { NON_INFOMIR_MAC }

    /** Never includes an address, login, password, MAC or identity value. */
    override fun toString() = "AddPlaylistUi(mode=$mode, editing=$editing, loaded=$loaded, phase=$phase)"
}

/** Status line while the TV guide loads; [programmes] is the number stored so far. */
fun guideProgressText(programmes: Int): String =
    if (programmes > 0) "Loading TV guide… $programmes programmes" else "Loading TV guide…"

/** "my list.m3u" → "my list"; a name without an extension (or only one) stays; blank → "Playlist". */
fun fileBaseName(fileName: String): String {
    val t = fileName.trim()
    if (t.isEmpty()) return "Playlist"
    val dot = t.lastIndexOf('.')
    return if (dot > 0) t.substring(0, dot) else t
}

/**
 * The copy for a failed add or edit in [mode]. Never the raw message, except the fixed portal copy
 * ([PORTAL_ERROR_COPY], shared with Settings).
 */
internal fun failureCopy(mode: Mode, failure: ImportResult.Failure): String = when {
    mode == Mode.PORTAL && failure.message in PORTAL_ERROR_COPY -> failure.message
    mode == Mode.FILE && failure.error == ImportError.Unreachable -> FILE_UNREADABLE
    else -> errorCopy(failure.error)
}

class AddPlaylistViewModel(
    private val addM3u: suspend (name: String, url: String, progress: (Int) -> Unit) -> ImportResult,
    private val addXtream: suspend (name: String, creds: XtreamCredentials, progress: (Int) -> Unit) -> ImportResult,
    private val refreshEpg: suspend (playlistId: Long, progress: (Int) -> Unit) -> Unit,
    private val addLocalFile: suspend (name: String, location: String, progress: (Int) -> Unit) -> ImportResult =
        { _, _, _ -> ImportResult.Failure(ImportError.Unexpected, "") },
    private val addStalker: suspend (name: String, cfg: PortalConfig, progress: (Int) -> Unit) -> ImportResult =
        { _, _, _ -> ImportResult.Failure(ImportError.Unexpected, "") },
    private val updateSource: suspend (playlistId: Long, edit: SourceEdit, progress: (Int) -> Unit) -> ImportResult =
        { _, _, _ -> ImportResult.Failure(ImportError.Unexpected, "") },
    private val loadPlaylist: suspend (playlistId: Long) -> PlaylistEntity? = { null },
    private val fileDisplayName: suspend (location: String) -> String = { it.trimEnd('/').substringAfterLast('/') },
    private val editId: Long? = null,
    /**
     * Where an add or edit runs: the app-wide scope, so leaving the form (Back) never cancels it
     * halfway — the source is saved and the import finishes or stores its error for Settings.
     * The form shows the result while it is still open. Null (tests) = [viewModelScope].
     */
    private val appScope: CoroutineScope? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(AddPlaylistUi(editing = editId != null, loaded = editId == null))
    val state: StateFlow<AddPlaylistUi> = _state.asStateFlow()

    init {
        if (editId != null) viewModelScope.launch { load(editId) }
    }

    fun setMode(m: Mode) = edit { it.copy(mode = m, phase = Phase.Editing, macError = null) }
    fun setName(v: String) = edit { it.copy(name = v) }
    fun setUrl(v: String) = edit { it.copy(url = v) }
    fun setServer(v: String) = edit { it.copy(server = v) }
    fun setUser(v: String) = edit { it.copy(user = v) }
    fun setPass(v: String) = edit { it.copy(pass = v) }
    fun setPortalUrl(v: String) = edit { it.copy(portalUrl = v) }
    fun setMac(v: String) = edit { it.copy(mac = v, macError = null) }
    fun setPortalLogin(v: String) = edit { it.copy(portalLogin = v) }
    fun setPortalPass(v: String) = edit { it.copy(portalPass = v) }
    fun setSerial(v: String) = edit { it.copy(serial = v) }
    fun setDeviceId(v: String) = edit { it.copy(deviceId = v) }
    fun setDeviceId2(v: String) = edit { it.copy(deviceId2 = v) }
    fun setSignature(v: String) = edit { it.copy(signature = v) }
    fun toggleMoreOptions() = edit { it.copy(moreOptions = !it.moreOptions) }

    /** A file chosen in the system picker or the browser; its display name is looked up off the main thread. */
    fun setFile(location: String) {
        if (_state.value.busy) return
        _state.update { it.copy(fileLocation = location, fileName = "", phase = Phase.Editing) }
        viewModelScope.launch { showFileName(location) }
    }

    /** Edits are ignored while an import is running. */
    private fun edit(f: (AddPlaylistUi) -> AddPlaylistUi) = _state.update { if (it.busy) it else f(it) }

    private suspend fun showFileName(location: String) {
        val name = try {
            fileDisplayName(location)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            location.trimEnd('/').substringAfterLast('/')
        }
        _state.update { if (it.fileLocation == location) it.copy(fileName = name) else it }
    }

    private suspend fun load(id: Long) {
        val p = try {
            loadPlaylist(id)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (p == null) {
            _state.update { it.copy(phase = Phase.Failed(errorCopy(ImportError.Unexpected))) }
            return
        }
        _state.update { prefill(it, p).copy(loaded = true) }
        if (p.type == PlaylistType.M3U_FILE) showFileName(p.url.orEmpty())
    }

    /** The form as the stored playlist [p] fills it. Passwords are kept as stored (shown masked). */
    private fun prefill(ui: AddPlaylistUi, p: PlaylistEntity): AddPlaylistUi = when (p.type) {
        PlaylistType.M3U -> ui.copy(mode = Mode.M3U, url = p.url.orEmpty())
        PlaylistType.M3U_FILE -> ui.copy(mode = Mode.FILE, fileLocation = p.url.orEmpty())
        PlaylistType.XTREAM -> ui.copy(
            mode = Mode.XTREAM,
            server = p.xtreamServer.orEmpty(),
            user = p.xtreamUser.orEmpty(),
            pass = p.xtreamPass.orEmpty(),
        )
        PlaylistType.STALKER -> {
            val filled = ui.copy(
                mode = Mode.PORTAL,
                portalUrl = p.url.orEmpty(),
                mac = p.mac.orEmpty().ifBlank { MAC_PREFIX },
                portalLogin = p.portalLogin.orEmpty(),
                portalPass = p.portalPassword.orEmpty(),
                serial = p.portalSerial.orEmpty(),
                deviceId = p.portalDeviceId.orEmpty(),
                deviceId2 = p.portalDeviceId2.orEmpty(),
                signature = p.portalSignature.orEmpty(),
            )
            val extras = listOf(filled.portalLogin, filled.portalPass, filled.serial, filled.deviceId, filled.deviceId2, filled.signature)
            filled.copy(moreOptions = extras.any { it.isNotBlank() })
        }
    }

    fun submit() {
        val s = _state.value
        if (s.busy) return
        val check = when (val c = checkPlaylistForm(s.toFormInput())) {
            FormCheck.Incomplete -> return
            FormCheck.InvalidMac -> {
                _state.update { it.copy(macError = INVALID_MAC, macErrorShown = it.macErrorShown + 1) }
                return
            }
            is FormCheck.Ok -> c
        }
        _state.update { it.copy(phase = Phase.LoadingChannels(0)) }
        val onProgress = { n: Int -> _state.update { it.copy(phase = Phase.LoadingChannels(n)) } }
        // Not viewModelScope: Back must never cancel an add or edit halfway (see [appScope]).
        (appScope ?: viewModelScope).launch {
            val result = try {
                run(check, onProgress)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                ImportResult.Failure(ImportError.Unexpected, "")
            }
            when (result) {
                is ImportResult.Failure -> _state.update { it.copy(phase = Phase.Failed(failureCopy(s.mode, result))) }
                is ImportResult.Success -> {
                    if (editId == null) loadGuide(result.playlistId)
                    _state.update { it.copy(phase = Phase.Done) }
                }
                // A copied setup replaced the edited playlist (and closes this form): no error.
                ImportResult.Stopped -> _state.update { it.copy(phase = Phase.Done) }
            }
        }
    }

    private suspend fun run(check: FormCheck.Ok, onProgress: (Int) -> Unit): ImportResult {
        val source = check.source
        // The coordinator refreshes an edited playlist and then its guide.
        if (editId != null) return updateSource(editId, source, onProgress)
        return when (source) {
            is SourceEdit.M3uUrl -> addM3u(check.name, source.url, onProgress)
            is SourceEdit.LocalFile -> addLocalFile(check.name, source.location, onProgress)
            is SourceEdit.Xtream -> addXtream(check.name, source.creds, onProgress)
            is SourceEdit.Portal -> addStalker(check.name, source.cfg, onProgress)
        }
    }

    private suspend fun loadGuide(playlistId: Long) {
        _state.update { it.copy(phase = Phase.LoadingGuide()) }
        try {
            refreshEpg(playlistId) { n -> _state.update { it.copy(phase = Phase.LoadingGuide(n)) } }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Settings shows the EPG status; the channels are already imported.
        }
    }

    companion object {
        /** Host of [raw] (scheme optional), or "Playlist". */
        fun defaultName(raw: String): String {
            val t = raw.trim()
            val withScheme = if ("://" in t) t else "http://$t"
            val host = try { URI(withScheme).host } catch (_: Exception) { null }
            return if (host.isNullOrBlank()) "Playlist" else host
        }

        /** Adds a playlist ([editId] null, first start or Settings → Add playlist) or changes one's source. */
        fun factory(editId: Long?) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as LoopholeApp
                val c = app.container
                return AddPlaylistViewModel(
                    addM3u = { n, u, p -> c.playlistRepository.addM3u(n, u, p) },
                    addXtream = { n, cr, p -> c.playlistRepository.addXtream(n, cr, p) },
                    refreshEpg = { id, p -> c.epgRepository.refresh(id, p) },
                    addLocalFile = { n, l, p -> c.playlistRepository.addLocalFile(n, l, p) },
                    addStalker = { n, cfg, p -> c.playlistRepository.addStalker(n, cfg, p) },
                    updateSource = { id, e, p -> c.refreshCoordinator.updateSource(id, e, p) },
                    loadPlaylist = { id -> c.db.playlistDao().byId(id) },
                    fileDisplayName = { loc -> withContext(Dispatchers.IO) { localFileDisplayName(app.contentResolver, loc) } },
                    editId = editId,
                    appScope = app.applicationScope,
                ) as T
            }
        }

        val Factory: ViewModelProvider.Factory = factory(null)
    }
}
