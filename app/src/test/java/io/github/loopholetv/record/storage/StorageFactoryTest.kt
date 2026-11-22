package io.github.loopholetv.record.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.loopholetv.data.settings.AppSettings
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StorageFactoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val placeholder = { _: AppSettings -> UnavailableStorage("smb://h/s", "NAS") }
    private val factory = StorageFactory(context, placeholder, placeholder)

    @Test
    fun noneGivesNull() {
        assertNull(factory.current(AppSettings(recordStorage = StorageKind.NONE)))
    }

    @Test
    fun usbWithoutTreeGivesNull() {
        assertNull(factory.current(AppSettings(recordStorage = StorageKind.USB, usbTreeUri = "")))
    }

    @Test
    fun internalIsLocalAndById() {
        val s = factory.current(AppSettings(recordStorage = StorageKind.INTERNAL))
        assertNotNull(s)
        assertEquals("internal", s!!.id)
        assertEquals("Internal storage", s.label)
        assertEquals("internal", factory.byId("internal", AppSettings())?.id)
    }

    @Test
    fun smbUsesTheLambdaAndFailsTest() = runBlocking {
        val s = factory.current(AppSettings(recordStorage = StorageKind.SMB))!!
        assertTrue(s.test() is StorageResult.Failed)
    }

    @Test
    fun byIdOnlyForConfiguredStorage() {
        val smb = AppSettings(recordStorage = StorageKind.SMB)
        assertEquals("smb://h/s", factory.byId("smb://h/s", smb)?.id)
        assertNull(factory.byId("smb://h/s", AppSettings(recordStorage = StorageKind.NONE)))
        assertNull(factory.byId("smb://other/x", smb))
    }

    @Test
    fun fileNameSanitisedAndDated() {
        val name = recordingFileName(
            "Film4", "What? Now/Then", Instant.parse("2026-11-22T21:00:00Z"), ZoneId.of("Europe/London"), "ts",
        )
        assertEquals("Film4 - What_ Now_Then - 2026-11-22 2100.ts", name)
    }

    @Test
    fun fileNameTrimmedKeepingExtension() {
        val name = recordingFileName(
            "C", "x".repeat(400), Instant.parse("2026-11-22T21:00:00Z"), ZoneId.of("UTC"), "ts",
        )
        assertEquals(180, name.length)
        assertTrue(name.endsWith(" - 2026-11-22 2100.ts"))
    }

    @Test
    fun fileNameHasNoSpaceBeforeDateWhenTitleIsCut() {
        val name = recordingFileName(
            "C", "word ".repeat(100), Instant.parse("2026-11-22T21:00:00Z"), ZoneId.of("UTC"), "ts",
        )
        assertTrue(name.length <= 180)
        assertTrue(name.endsWith("word - 2026-11-22 2100.ts"))
    }

    @Test
    fun fileNameShortensAVeryLongChannelAndKeepsTheDate() {
        val name = recordingFileName(
            "c".repeat(400), "Title", Instant.parse("2026-11-22T21:00:00Z"), ZoneId.of("UTC"), "ts",
        )
        assertTrue(name.length <= 180)
        assertTrue(name.endsWith(" - 2026-11-22 2100.ts"))
    }
}
