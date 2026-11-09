package io.github.loopholetv.ui.files

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.loopholetv.ui.theme.Tokens
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

internal const val LOOPHOLE_FOLDER = "Loophole folder"
internal const val DOWNLOAD_ROOT = "Download"
internal const val USB_DRIVE = "USB drive"
private const val ROOTS_TITLE = "Choose a playlist file"
private const val UP_LABEL = ".."

/**
 * The browser's roots: Download, each mounted removable volume and the Loophole folder
 * (app-specific external storage, readable without any permission).
 */
@Suppress("DEPRECATION") // getExternalStoragePublicDirectory: still the Download path on every API level.
internal fun fileBrowserRoots(context: Context): List<FileRoot> = buildList {
    add(FileRoot(DOWNLOAD_ROOT, Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), alwaysReadable = false))
    val sm = context.getSystemService(StorageManager::class.java)
    runCatching { sm?.storageVolumes.orEmpty() }.getOrDefault(emptyList())
        .filter { it.isRemovable && it.state in setOf(Environment.MEDIA_MOUNTED, Environment.MEDIA_MOUNTED_READ_ONLY) }
        .forEach { v ->
            val dir = if (Build.VERSION.SDK_INT >= 30) v.directory else v.uuid?.let { File("/storage/$it") }
            if (dir != null) {
                val label = runCatching { v.getDescription(context) }.getOrNull()?.takeIf { it.isNotBlank() } ?: USB_DRIVE
                add(FileRoot(label, dir, alwaysReadable = false))
            }
        }
    context.getExternalFilesDir(null)?.let { add(FileRoot(LOOPHOLE_FOLDER, it, alwaysReadable = true)) }
}

private fun needsStoragePermission(context: Context): Boolean =
    Build.VERSION.SDK_INT <= 32 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED

private sealed interface BrowserRow {
    val key: String

    data class Root(val root: FileRoot) : BrowserRow {
        override val key get() = "root:" + root.dir.path
    }

    data object Up : BrowserRow {
        override val key get() = "up"
    }

    data class Entry(val entry: FileEntry) : BrowserRow {
        override val key get() = "file:" + entry.file.path
    }
}

/**
 * The in-app file browser, for TVs without a system document picker. OK on a folder enters
 * it, OK on a playlist file calls [onPick] with its absolute path. Back goes up one level; Back at
 * the roots calls [onCancel]. Opening Download or a USB drive asks for the storage permission on
 * API 32 and below; anything unreadable shows where to copy the file instead.
 */
@Composable
fun FileBrowser(onPick: (path: String) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val pick by rememberUpdatedState(onPick)
    val cancel by rememberUpdatedState(onCancel)
    val scope = rememberCoroutineScope()
    // One listing at a time, off the main thread.
    val io: CoroutineDispatcher = remember { Dispatchers.IO.limitedParallelism(1) }
    val model = remember { FileBrowserModel(roots = { fileBrowserRoots(context) }, list = { it.listFiles()?.toList() }) }
    val state by model.state.collectAsStateWithLifecycle()
    fun onIo(block: FileBrowserModel.() -> Unit) { scope.launch(io) { model.block() } }

    var pendingRoot by remember { mutableStateOf<FileRoot?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Granted or not: an unreadable folder explains what to do.
        pendingRoot?.let { r -> onIo { open(r) } }
        pendingRoot = null
    }

    // The roots come from StorageManager and external storage: read them off the main thread.
    LaunchedEffect(model) { onIo { refresh() } }

    BackHandler {
        if (state is FileBrowserUi.Folder) onIo { up() } else cancel()
    }

    val rows: List<BrowserRow> = when (val s = state) {
        FileBrowserUi.Loading -> emptyList()
        is FileBrowserUi.Roots -> s.roots.map { BrowserRow.Root(it) }
        is FileBrowserUi.Folder -> listOf(BrowserRow.Up) + s.entries.map { BrowserRow.Entry(it) }
    }

    // Where focus lands after a move: the folder we came out of, else the first entry.
    // Plain holder (not snapshot state): only read while computing the next focus target.
    val previous = remember { object { var value: FileBrowserUi? = null } }
    val focusIndex = remember(state) {
        val prev = previous.value
        val idx = when (val s = state) {
            FileBrowserUi.Loading -> 0
            is FileBrowserUi.Roots -> s.roots.indexOfFirst { prev is FileBrowserUi.Folder && it == prev.root }.coerceAtLeast(0)
            is FileBrowserUi.Folder -> {
                val back = rows.indexOfFirst { prev is FileBrowserUi.Folder && it is BrowserRow.Entry && it.entry.file == prev.dir }
                if (back >= 0) back else if (rows.size > 1) 1 else 0
            }
        }
        previous.value = state
        idx
    }
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(state) {
        if (rows.isEmpty()) return@LaunchedEffect
        listState.scrollToItem(focusIndex)
        repeat(20) {
            if (runCatching { focus.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
            delay(16)
        }
    }

    Box(Modifier.fillMaxSize().background(Tokens.Background).padding(horizontal = 58.dp, vertical = 36.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth(0.6f)) {
            val s = state
            Text(
                text = if (s is FileBrowserUi.Folder) s.dir.path else ROOTS_TITLE,
                style = MaterialTheme.typography.titleLarge,
                color = Tokens.OnBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (s is FileBrowserUi.Folder && s.hint != null) {
                Text(s.hint, style = MaterialTheme.typography.bodyMedium, color = Tokens.Muted)
            }
            LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(rows, key = { _, row -> row.key }) { index, row ->
                    val mod = if (index == focusIndex) Modifier.focusRequester(focus) else Modifier
                    when (row) {
                        is BrowserRow.Root -> BrowserRowItem(
                            label = row.root.label,
                            detail = row.root.dir.path.takeIf { row.root.alwaysReadable },
                            modifier = mod,
                        ) {
                            if (!row.root.alwaysReadable && needsStoragePermission(context)) {
                                pendingRoot = row.root
                                permission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                            } else {
                                onIo { open(row.root) }
                            }
                        }
                        BrowserRow.Up -> BrowserRowItem(label = UP_LABEL, detail = null, modifier = mod) { onIo { up() } }
                        is BrowserRow.Entry -> BrowserRowItem(
                            label = if (row.entry.isDir) "${row.entry.name}/" else row.entry.name,
                            detail = null,
                            modifier = mod,
                        ) {
                            if (row.entry.isDir) onIo { enter(row.entry) } else pick(row.entry.file.absolutePath)
                        }
                    }
                }
            }
        }
    }
}

/** A focusable row: a light pill with dark text when focused (the app's focus style). */
@Composable
private fun BrowserRowItem(label: String, detail: String?, modifier: Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .background(if (focused) Tokens.FocusFill else Color.Transparent, RoundedCornerShape(22.dp))
            .padding(horizontal = 18.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (focused) Tokens.OnFocus else Tokens.OnBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (detail != null) {
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = if (focused) Tokens.OnFocus else Tokens.Muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
