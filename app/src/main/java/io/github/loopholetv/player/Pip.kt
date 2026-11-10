package io.github.loopholetv.player

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer

/** True when this device lets apps open a picture-in-picture window (API 26+ and the system feature). */
fun pipSupported(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

/** Puts [activity] into a 16:9 picture-in-picture window; false when unsupported or refused. */
fun enterPictureInPicture(activity: Activity): Boolean {
    if (!pipSupported(activity) || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
    val params = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
    return try {
        activity.enterPictureInPictureMode(params)
    } catch (_: IllegalStateException) {
        // Thrown when the activity cannot enter picture-in-picture right now (e.g. not resumed).
        false
    }
}

/** Whether the hosting activity is in picture-in-picture, updated as the mode changes. */
@Composable
fun rememberInPictureInPicture(): Boolean {
    val activity = LocalActivity.current as? ComponentActivity
    var inPip by remember(activity) { mutableStateOf(activity?.isInPictureInPictureMode == true) }
    DisposableEffect(activity) {
        if (activity == null) return@DisposableEffect onDispose {}
        val listener = Consumer<PictureInPictureModeChangedInfo> { inPip = it.isInPictureInPictureMode }
        activity.addOnPictureInPictureModeChangedListener(listener)
        // A mode change between the first read (composition) and registering the listener has no
        // callback: read the mode again now that changes are observed.
        inPip = activity.isInPictureInPictureMode
        onDispose { activity.removeOnPictureInPictureModeChangedListener(listener) }
    }
    return activity != null && inPip
}

/** Whether the full-screen player is on screen; read when the user leaves the app to decide on picture-in-picture. */
class PipGate {
    @Volatile var playerShowing: Boolean = false
}
