package io.github.loopholetv.ui.files

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext

private const val ANY_TYPE = "*/*"

/**
 * Packages that claim `ACTION_OPEN_DOCUMENT` without being a picker: Android TV's framework stubs
 * only show "You don't have an app that can do this" and return a cancel.
 */
private val STUB_PICKER_PACKAGES = setOf(
    "com.android.tv.frameworkpackagestubs",
    "com.android.frameworkpackagestubs",
)

/** True when one of the [handlerPackages] for `ACTION_OPEN_DOCUMENT` is a real document picker. */
internal fun isRealDocumentPicker(handlerPackages: List<String>): Boolean =
    handlerPackages.any { it !in STUB_PICKER_PACKAGES }

/**
 * True when a real picker (not a stub) handles `ACTION_OPEN_DOCUMENT` for openable files of any
 * type. Many TV builds ship without DocumentsUI, or with only a stub, so the in-app
 * [FileBrowser] is the fallback. (`.m3u` MIME types vary, so the picker asks for any type.)
 */
fun isDocumentPickerAvailable(pm: PackageManager): Boolean {
    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(ANY_TYPE)
    @Suppress("DEPRECATION") // The flags overload is API 33+; minSdk is 25.
    val handlers = runCatching { pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY) }.getOrDefault(emptyList())
    return isRealDocumentPicker(handlers.mapNotNull { it.activityInfo?.packageName })
}

/** [ActivityResultContracts.OpenDocument] limited to documents that can be opened as a stream. */
private class OpenPlaylistDocument : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).addCategory(Intent.CATEGORY_OPENABLE)
}

/**
 * Returns a launcher for the system document picker. A picked document's read grant is made
 * persistable so refreshes can re-read it, then [onPicked] gets its `content://` location. A
 * cancelled pick is ignored (that also covers the stale cancel a failed launch can deliver).
 * Without a picker, [onUnavailable] is called instead (show the [FileBrowser]).
 */
@Composable
fun rememberPlaylistFilePicker(onPicked: (location: String) -> Unit, onUnavailable: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val picked by rememberUpdatedState(onPicked)
    val unavailable by rememberUpdatedState(onUnavailable)
    val contract = remember { OpenPlaylistDocument() }
    val launcher = rememberLauncherForActivityResult(contract) { uri ->
        handlePickResult(
            uri,
            takeGrant = { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) },
            onPicked = { picked(it) },
        )
    }
    return remember(launcher) {
        {
            launchOrFallback(
                available = isDocumentPickerAvailable(context.packageManager),
                launch = { launcher.launch(arrayOf(ANY_TYPE)) },
                onUnavailable = { unavailable() },
            )
        }
    }
}

/** Launches the picker when [available]; otherwise, or when the launch finds no activity, [onUnavailable]. */
internal fun launchOrFallback(available: Boolean, launch: () -> Unit, onUnavailable: () -> Unit) {
    if (!available) return onUnavailable()
    try {
        launch()
    } catch (e: ActivityNotFoundException) {
        onUnavailable()
    }
}

/**
 * A picker result: null (cancelled, or the stale cancel a failed launch can deliver) is ignored.
 * Otherwise the read grant is made persistable so refreshes can re-read it. Some providers don't
 * offer persistable grants: the pick still imports, and later refreshes use the last-good copy.
 */
internal fun handlePickResult(uri: Uri?, takeGrant: (Uri) -> Unit, onPicked: (String) -> Unit) {
    if (uri == null) return
    runCatching { takeGrant(uri) }
    onPicked(uri.toString())
}
