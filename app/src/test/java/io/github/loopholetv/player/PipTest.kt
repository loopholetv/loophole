package io.github.loopholetv.player

import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class PipTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    private fun setFeature(on: Boolean) =
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE, on)

    @Test
    fun pipUnsupportedWithoutFeature() {
        setFeature(false)
        assertFalse(pipSupported(context))
    }

    @Test
    fun pipSupportedWithFeature() {
        setFeature(true)
        assertTrue(pipSupported(context))
    }

    @Test
    @Config(sdk = [25])
    fun pipUnsupportedBelowApi26() {
        setFeature(true)
        assertFalse(pipSupported(context))
    }

    @Test
    fun enterReturnsFalseWhenUnsupported() {
        setFeature(false)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        assertFalse(enterPictureInPicture(activity))
    }

    @Test
    fun pipGateStartsClosed() {
        assertFalse(PipGate().playerShowing)
    }
}
