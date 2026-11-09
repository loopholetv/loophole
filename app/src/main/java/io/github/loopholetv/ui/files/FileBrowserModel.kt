package io.github.loopholetv.ui.files

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** A place the browser starts from. [alwaysReadable]: the Loophole folder (app-specific, no permission). */
data class FileRoot(val label: String, val dir: File, val alwaysReadable: Boolean)

data class FileEntry(val name: String, val file: File, val isDir: Boolean)

sealed interface FileBrowserUi {
    /** Before the roots were first read ([FileBrowserModel.refresh]). */
    data object Loading : FileBrowserUi

    data class Roots(val roots: List<FileRoot>) : FileBrowserUi

    /** [hint]: shown when nothing is listed (unreadable or empty folder). */
    data class Folder(val root: FileRoot, val dir: File, val entries: List<FileEntry>, val hint: String?) : FileBrowserUi
}

internal const val NO_PLAYLIST_FILES = "No playlist files here"

internal fun copyHint(loopholePath: String?) =
    if (loopholePath.isNullOrEmpty()) "Can't read this folder. Copy the file into the Loophole folder."
    else "Can't read this folder. Copy the file into the Loophole folder: $loopholePath"

private val PLAYLIST_EXTENSIONS = setOf("m3u", "m3u8", "txt")

/**
 * The fallback file browser's navigation. It starts [FileBrowserUi.Loading]; [refresh] reads
 * the roots. [roots] is re-read each time the roots show (USB sticks come and go); [list] returns a
 * folder's children, or null when it can't be read. Construction does no IO; every other call
 * does, so run them off the main thread, one at a time.
 */
class FileBrowserModel(
    private val roots: () -> List<FileRoot>,
    private val list: (File) -> List<File>?,
) {
    private val _state = MutableStateFlow<FileBrowserUi>(FileBrowserUi.Loading)
    private var knownRoots: List<FileRoot>? = null
    val state: StateFlow<FileBrowserUi> = _state.asStateFlow()

    fun open(root: FileRoot) {
        _state.value = listing(root, root.dir)
    }

    /** Enters a folder; files are picked by the caller. */
    fun enter(entry: FileEntry) {
        val current = _state.value as? FileBrowserUi.Folder ?: return
        if (entry.isDir) _state.value = listing(current.root, entry.file)
    }

    /** One level up; from a root back to the roots. False when already at the roots (close instead). */
    fun up(): Boolean {
        val current = _state.value as? FileBrowserUi.Folder ?: return false
        val parent = current.dir.parentFile
        _state.value = if (current.dir == current.root.dir || parent == null || !isInside(parent, current.root.dir)) {
            loadRoots()
        } else {
            listing(current.root, parent)
        }
        return true
    }

    /** Lists the current folder (or roots) again, e.g. after the storage permission was granted. */
    fun refresh() {
        _state.value = when (val s = _state.value) {
            FileBrowserUi.Loading, is FileBrowserUi.Roots -> loadRoots()
            is FileBrowserUi.Folder -> listing(s.root, s.dir)
        }
    }

    private fun listing(root: FileRoot, dir: File): FileBrowserUi.Folder {
        val children = runCatching { list(dir) }.getOrNull()
        val entries = children.orEmpty()
            .filterNot { it.name.startsWith('.') }
            .mapNotNull { f ->
                when {
                    f.isDirectory -> FileEntry(f.name, f, isDir = true)
                    f.extension.lowercase() in PLAYLIST_EXTENSIONS -> FileEntry(f.name, f, isDir = false)
                    else -> null
                }
            }
            .sortedWith(compareBy<FileEntry> { !it.isDir }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        val hint = when {
            entries.isNotEmpty() -> null
            root.alwaysReadable -> NO_PLAYLIST_FILES
            else -> copyHint(loopholePath())
        }
        return FileBrowserUi.Folder(root, dir, entries, hint)
    }

    private fun loadRoots(): FileBrowserUi.Roots = FileBrowserUi.Roots(roots().also { knownRoots = it })

    /** From the roots last shown (read once if a folder was opened directly). */
    private fun loopholePath(): String? =
        (knownRoots ?: roots().also { knownRoots = it }).firstOrNull { it.alwaysReadable }?.dir?.path

    private fun isInside(dir: File, root: File): Boolean {
        val d = dir.absoluteFile.normalize().path
        val r = root.absoluteFile.normalize().path
        return d == r || d.startsWith(r.trimEnd('/') + "/")
    }
}
