package io.github.loopholetv.ui.files

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class FilePickerTest {
    private val pm get() = ApplicationProvider.getApplicationContext<android.app.Application>().packageManager

    @Test fun pickerUnavailableWithoutResolver() {
        assertFalse(isDocumentPickerAvailable(pm))
    }

    @Test fun launchOrFallbackChoosesPath() {
        val log = mutableListOf<String>()
        launchOrFallback(available = false, launch = { log += "launch" }, onUnavailable = { log += "browser" })
        launchOrFallback(available = true, launch = { log += "launch" }, onUnavailable = { log += "browser" })
        launchOrFallback(available = true, launch = { throw android.content.ActivityNotFoundException() }, onUnavailable = { log += "browser" })
        assertEquals(listOf("browser", "launch", "browser"), log)
    }

    @Test fun pickResultIgnoresCancelAndTakesGrant() {
        val granted = mutableListOf<android.net.Uri>()
        val picked = mutableListOf<String>()
        handlePickResult(null, takeGrant = { granted += it }, onPicked = { picked += it })
        assertEquals(emptyList<String>(), picked)
        val uri = android.net.Uri.parse("content://io.github.loopholetv.test.files/doc/1")
        handlePickResult(uri, takeGrant = { granted += it; throw SecurityException("no persistable grant") }, onPicked = { picked += it })
        assertEquals(listOf(uri), granted)
        assertEquals(listOf(uri.toString()), picked)
    }

    private fun addOpenDocumentHandler(pkg: String, activity: String) {
        val component = ComponentName(pkg, activity)
        val shadow = shadowOf(pm)
        shadow.addActivityIfNotPresent(component)
        shadow.addIntentFilterForActivity(
            component,
            IntentFilter(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addCategory(Intent.CATEGORY_OPENABLE)
                addDataType("*/*")
            },
        )
    }

    @Test fun pickerAvailableWhenDocumentsUiResolves() {
        addOpenDocumentHandler("com.android.documentsui", "com.android.documentsui.picker.PickActivity")
        assertTrue(isDocumentPickerAvailable(pm))
    }

    @Test fun androidTvStubAloneIsNoPicker() {
        // Android TV's stub claims OPEN_DOCUMENT but only shows "You don't have an app that can do this".
        addOpenDocumentHandler("com.android.tv.frameworkpackagestubs", "com.android.tv.frameworkpackagestubs.Stubs\$DocumentsStub")
        assertFalse(isDocumentPickerAvailable(pm))
    }

    @Test fun stubPackagesAreNotPickers() {
        assertFalse(isRealDocumentPicker(listOf("com.android.tv.frameworkpackagestubs")))
        assertFalse(isRealDocumentPicker(listOf("com.android.frameworkpackagestubs")))
        assertFalse(isRealDocumentPicker(listOf("com.android.tv.frameworkpackagestubs", "com.android.frameworkpackagestubs")))
        assertFalse(isRealDocumentPicker(emptyList()))
        assertTrue(isRealDocumentPicker(listOf("com.android.documentsui")))
        assertTrue(isRealDocumentPicker(listOf("com.android.tv.frameworkpackagestubs", "com.google.android.documentsui")))
    }
}
