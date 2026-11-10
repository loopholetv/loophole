package io.github.loopholetv.data.repo

import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.db.ChannelRef
import io.github.loopholetv.data.db.inMemoryDb
import io.github.loopholetv.data.settings.AspectMode
import io.github.loopholetv.data.settings.DecoderChoice
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
class ChannelPlaybackTest {
    private lateinit var db: AppDatabase
    private lateinit var edits: ChannelEdits
    private lateinit var playback: ChannelPlayback
    private lateinit var p: OverlayPlaylist

    @Before fun setUp() {
        db = inMemoryDb()
        edits = ChannelEdits(db, ChannelOverlay(db))
        playback = ChannelPlayback(db.channelPrefsDao())
        p = OverlayPlaylist(db)
    }

    @After fun tearDown() {
        p.close()
        db.close()
    }

    @Test fun missingRowIsNone() = runTest(timeout = 60.seconds) {
        p.add(entries("A"))
        assertEquals(ChannelPlaybackPrefs.NONE, playback.get(p.ref("A")))
        assertEquals(ChannelPlaybackPrefs.NONE, playback.get(ChannelRef(999, "nope")))
        assertEquals(ChannelPlaybackPrefs.NONE, playback.observe(p.ref("A")).first())

        // A row holding only other edits still reads as no playback override.
        edits.setHidden(p.ref("A"), true)
        assertEquals(ChannelPlaybackPrefs.NONE, playback.get(p.ref("A")))
    }

    @Test fun observeEmitsAfterEdit() = runTest(timeout = 60.seconds) {
        p.add(entries("A"))
        val a = p.ref("A")
        assertEquals(ChannelPlaybackPrefs.NONE, playback.observe(a).first())
        val next = async { playback.observe(a).first { it != ChannelPlaybackPrefs.NONE } }
        edits.setVideoDecoder(a, DecoderChoice.SOFTWARE)
        assertEquals(ChannelPlaybackPrefs(video = DecoderChoice.SOFTWARE), next.await())

        edits.setAudioDecoder(a, DecoderChoice.HARDWARE)
        edits.setExternalPlayer(a, "ask")
        edits.setAspect(a, AspectMode.ZOOM)
        val all = ChannelPlaybackPrefs(DecoderChoice.SOFTWARE, DecoderChoice.HARDWARE, "ask", AspectMode.ZOOM)
        assertEquals(all, playback.get(a))
        assertEquals(all, playback.observe(a).first())
    }
}
