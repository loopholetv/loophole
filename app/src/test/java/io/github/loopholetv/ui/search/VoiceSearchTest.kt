package io.github.loopholetv.ui.search

import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.speech.RecognizerIntent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class VoiceSearchTest {
    private val pm = ApplicationProvider.getApplicationContext<android.content.Context>().packageManager

    @Test fun unavailableWithoutRecogniser() {
        assertFalse(isVoiceSearchAvailable(pm))
    }

    @Test fun availableWhenRecogniserResolves() {
        val info = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply { packageName = "com.example.voice"; name = "Recognise" }
        }
        shadowOf(pm).addResolveInfoForIntent(voiceSearchIntent(), info)
        assertTrue(isVoiceSearchAvailable(pm))
    }

    @Test fun intentAsksForOneFreeFormResult() {
        val i = voiceSearchIntent()
        assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH, i.action)
        assertEquals(RecognizerIntent.LANGUAGE_MODEL_FREE_FORM, i.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL))
        assertEquals("Say a channel or programme name", i.getStringExtra(RecognizerIntent.EXTRA_PROMPT))
        assertEquals(1, i.getIntExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 0))
    }
}
