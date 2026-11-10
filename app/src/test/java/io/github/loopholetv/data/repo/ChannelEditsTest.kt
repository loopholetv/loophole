package io.github.loopholetv.data.repo

import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.db.GroupPrefsEntity
import io.github.loopholetv.data.db.GroupSort
import io.github.loopholetv.data.db.inMemoryDb
import io.github.loopholetv.data.settings.AspectMode
import io.github.loopholetv.data.settings.DecoderChoice
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
class ChannelEditsTest {
    private lateinit var db: AppDatabase
    private lateinit var edits: ChannelEdits
    private lateinit var p: OverlayPlaylist

    @Before fun setUp() {
        db = inMemoryDb()
        edits = ChannelEdits(db, ChannelOverlay(db))
        p = OverlayPlaylist(db)
    }

    @After fun tearDown() {
        p.close()
        db.close()
    }

    /** Group titles of the playlist in their current order (group_prefs position, then source order). */
    private suspend fun groupOrder(): List<String> {
        val prefs = db.groupPrefsDao().forPlaylist(p.id).associateBy { it.groupTitle }
        return p.all().groupBy { it.groupTitle }
            .map { (title, cs) -> Triple(title, prefs[title]?.position, cs.minOf { it.groupOrder }) }
            .sortedWith(compareBy<Triple<String, Int?, Int>>({ it.second == null }, { it.second }, { it.third }))
            .map { it.first }
    }

    @Test fun renameToBlankOrOriginalRestores() = runTest(timeout = 60.seconds) {
        p.add(entries("A", "B"))
        val a = p.ref("A")
        edits.rename(a, "Alpha")
        assertEquals("Alpha", p.channel("A").customName)
        assertEquals("Alpha", db.channelPrefsDao().get(a.playlistId, a.key)?.customName)

        edits.rename(a, "  ")
        assertNull(p.channel("A").customName)
        assertNull(db.channelPrefsDao().get(a.playlistId, a.key))

        edits.rename(a, "Alpha")
        edits.rename(a, "A")
        assertNull(p.channel("A").customName)
        assertNull(db.channelPrefsDao().get(a.playlistId, a.key))

        edits.rename(a, "Alpha")
        edits.rename(a, null)
        assertNull(db.channelPrefsDao().get(a.playlistId, a.key))

        // A row with another edit stays, without the name.
        edits.setHidden(a, true)
        edits.rename(a, "Alpha")
        edits.rename(a, "")
        assertEquals(true, db.channelPrefsDao().get(a.playlistId, a.key)?.hidden)
        assertNull(db.channelPrefsDao().get(a.playlistId, a.key)?.customName)
        edits.setHidden(a, false)
        assertNull(db.channelPrefsDao().get(a.playlistId, a.key))

        // Logo and guide id: blank restores.
        edits.setLogo(a, "http://logo.example.test/a.png")
        assertEquals("http://logo.example.test/a.png", p.channel("A").customLogo)
        edits.setLogo(a, " ")
        assertNull(p.channel("A").customLogo)
        edits.setEpgOverride(a, "a.uk")
        assertEquals("a.uk", p.channel("A").guideEpgId)
        edits.setEpgOverride(a, null)
        assertNull(p.channel("A").guideEpgId)
        assertNull(db.channelPrefsDao().get(a.playlistId, a.key))
    }

    @Test fun moveMaterialisesCustomOrderAndStopsAtEnds() = runTest(timeout = 60.seconds) {
        p.add(entries("A", "B", "C"))
        assertTrue(edits.move(p.channel("C"), -1))
        assertEquals(listOf("A", "C", "B"), p.order())
        assertEquals(GroupSort.CUSTOM, db.groupPrefsDao().get(p.id, "G")?.sortMode)
        assertEquals(listOf(0, 1, 2), listOf("A", "C", "B").map { db.channelPrefsDao().get(p.id, p.channel(it).key)?.position })

        assertFalse(edits.move(p.channel("A"), -1))
        assertFalse(edits.move(p.channel("B"), 1))
        assertEquals(listOf("A", "C", "B"), p.order())

        assertTrue(edits.move(p.channel("A"), 1))
        assertEquals(listOf("C", "A", "B"), p.order())
        // Numbers never change.
        assertEquals(listOf(1, 2, 3), listOf("A", "B", "C").map { p.channel(it).guideNumber })
    }

    @Test fun moveSkipsHiddenNeighbours() = runTest(timeout = 60.seconds) {
        p.add(entries("A", "B", "C"))
        edits.setHidden(p.ref("B"), true)
        assertTrue(edits.move(p.channel("C"), -1))
        assertEquals(listOf("C", "B", "A"), p.order())
    }

    @Test fun nameSortRanksByDisplayNameIgnoringCase() = runTest(timeout = 60.seconds) {
        p.add(entries("delta", "Bravo", "alpha", "Charlie"))
        edits.setGroupSort(p.id, "G", GroupSort.NAME)
        assertEquals(listOf("alpha", "Bravo", "Charlie", "delta"), p.order())

        // A rename re-ranks a NAME group by the shown name.
        edits.rename(p.ref("delta"), "Able")
        assertEquals(listOf("delta", "alpha", "Bravo", "Charlie"), p.order())

        // Equal names fall back to the number.
        edits.rename(p.ref("Charlie"), "ALPHA")
        assertEquals(listOf("delta", "alpha", "Charlie", "Bravo"), p.order())

        edits.setGroupSort(p.id, "G", GroupSort.PLAYLIST)
        assertEquals(listOf("delta", "Bravo", "alpha", "Charlie"), p.order())
        assertNull(db.groupPrefsDao().get(p.id, "G"))

        // The sort survives a refresh, and new channels slot in by name.
        edits.setGroupSort(p.id, "G", GroupSort.NAME)
        p.refresh(entries("delta", "Bravo", "alpha", "Charlie", "Baker"))
        assertEquals(listOf("delta", "alpha", "Charlie", "Baker", "Bravo"), p.order())
    }

    @Test fun newChannelInCustomGroupGoesLast() = runTest(timeout = 60.seconds) {
        p.add(entries("A", "B", "C"))
        assertTrue(edits.move(p.channel("C"), -1)) // A, C, B
        p.refresh(entries("N", "A", "B", "C", "M"))
        assertEquals(listOf("A", "C", "B", "N", "M"), p.order())
        // Moving next to a channel without a position materialises the whole order again.
        assertTrue(edits.move(p.channel("N"), -1))
        assertEquals(listOf("A", "C", "N", "B", "M"), p.order())
        assertEquals(4, db.channelPrefsDao().get(p.id, p.channel("M").key)?.position)
    }

    @Test fun moveGroupSwapsWithNeighbourAndStopsAtEnds() = runTest(timeout = 60.seconds) {
        p.add(listOf(Entry("A", "News"), Entry("B", "Sport"), Entry("C", "Film")))
        assertEquals(listOf("News", "Sport", "Film"), groupOrder())

        assertTrue(edits.moveGroup(p.id, "Film", -1))
        assertEquals(listOf("News", "Film", "Sport"), groupOrder())
        assertEquals(listOf(0, 1, 2), listOf("News", "Film", "Sport").map { db.groupPrefsDao().get(p.id, it)?.position })

        assertFalse(edits.moveGroup(p.id, "News", -1))
        assertFalse(edits.moveGroup(p.id, "Sport", 1))
        assertFalse(edits.moveGroup(p.id, "Nope", 1))

        assertTrue(edits.moveGroup(p.id, "News", 1))
        assertEquals(listOf("Film", "News", "Sport"), groupOrder())

        // A group's other edits stay independent of its position.
        edits.renameGroup(p.id, "News", "Headlines")
        assertEquals("Headlines", db.groupPrefsDao().get(p.id, "News")?.customName)
        edits.renameGroup(p.id, "News", "News")
        assertNull(db.groupPrefsDao().get(p.id, "News")?.customName)
        assertEquals(1, db.groupPrefsDao().get(p.id, "News")?.position)
    }

    @Test fun moveBetweenEqualPositionsAfterRegroupReorders() = runTest(timeout = 60.seconds) {
        p.add(
            listOf(
                Entry("A", "News"), Entry("B", "News"), Entry("C", "News"),
                Entry("D", "Sport"), Entry("E", "Sport"), Entry("F", "Sport"), Entry("G", "Sport"),
            ),
        )
        assertTrue(edits.move(p.channel("B"), -1)) // News: B0, A1, C2
        assertTrue(edits.move(p.channel("E"), -1)) // Sport: E0, D1, F2, G3
        // The provider moves C (position 2) into Sport, where F also has position 2.
        p.refresh(
            listOf(
                Entry("A", "News"), Entry("B", "News"),
                Entry("D", "Sport"), Entry("E", "Sport"), Entry("F", "Sport"), Entry("G", "Sport"), Entry("C", "Sport"),
            ),
        )
        assertEquals(listOf("E", "D", "F", "C", "G"), p.order("Sport"))
        assertTrue(edits.move(p.channel("C"), -1))
        assertEquals(listOf("E", "D", "C", "F", "G"), p.order("Sport"))
        assertTrue(edits.move(p.channel("C"), 1))
        assertEquals(listOf("E", "D", "F", "C", "G"), p.order("Sport"))
    }

    @Test fun moveBetweenEqualPositionsAfterReturnReorders() = runTest(timeout = 60.seconds) {
        p.add(entries("A", "B", "C", "D"))
        assertTrue(edits.move(p.channel("D"), -1)) // A0, B1, D2, C3
        p.refresh(entries("A", "C", "D"))
        // Back to playlist order (A, C, D), then a move re-materialises: C0, A1, D2; the vanished B keeps 1.
        edits.setGroupSort(p.id, "G", GroupSort.PLAYLIST)
        assertTrue(edits.move(p.channel("A"), 1))
        p.refresh(entries("A", "B", "C", "D"))
        assertEquals(listOf("C", "A", "B", "D"), p.order())
        assertTrue(edits.move(p.channel("B"), -1))
        assertEquals(listOf("C", "B", "A", "D"), p.order())
    }

    @Test fun moveGroupBetweenEqualPositionsReorders() = runTest(timeout = 60.seconds) {
        p.add(listOf(Entry("A", "News"), Entry("B", "Sport"), Entry("C", "Film")))
        db.groupPrefsDao().upsert(GroupPrefsEntity(p.id, "News", position = 0))
        db.groupPrefsDao().upsert(GroupPrefsEntity(p.id, "Sport", position = 1))
        db.groupPrefsDao().upsert(GroupPrefsEntity(p.id, "Film", position = 1))
        assertEquals(listOf("News", "Sport", "Film"), groupOrder())
        assertTrue(edits.moveGroup(p.id, "Film", -1))
        assertEquals(listOf("News", "Film", "Sport"), groupOrder())
    }

    @Test fun moveGroupSkipsGroupsWithNoVisibleChannel() = runTest(timeout = 60.seconds) {
        p.add(listOf(Entry("A", "News"), Entry("B", "Sport"), Entry("C", "Film")))
        edits.setHidden(p.ref("B"), true) // Sport has no visible channel, so the guide doesn't list it
        assertTrue(edits.moveGroup(p.id, "Film", -1))
        // Film swaps with News; the unlisted Sport keeps its place between them.
        assertEquals(listOf("Film", "Sport", "News"), groupOrder())
    }

    @Test fun channelEditTouchesOnlyItsRow() = runTest(timeout = 60.seconds) {
        p.add(entries("A", "B"))
        val b = p.channel("B")
        db.openHelper.writableDatabase.execSQL("UPDATE channels SET customLogo = 'sentinel' WHERE id = ${b.id}")
        edits.setHidden(p.ref("A"), true)
        edits.setLogo(p.ref("A"), "http://logo.example.test/a.png")
        edits.rename(p.ref("A"), "Alpha")
        edits.setShift(p.ref("A"), 30)
        edits.setEpgOverride(p.ref("A"), "a.uk")
        assertEquals("sentinel", p.channel("B").customLogo)
    }

    @Test fun playbackPrefsWrittenAndClearedToDefault() = runTest(timeout = 60.seconds) {
        p.add(entries("A"))
        val a = p.ref("A")
        edits.setVideoDecoder(a, DecoderChoice.SOFTWARE)
        assertEquals(DecoderChoice.SOFTWARE, db.channelPrefsDao().get(a.playlistId, a.key)?.videoDecoder)
        edits.setVideoDecoder(a, null)
        assertNull(db.channelPrefsDao().get(a.playlistId, a.key))

        edits.setAudioDecoder(a, DecoderChoice.HARDWARE)
        assertEquals(DecoderChoice.HARDWARE, db.channelPrefsDao().get(a.playlistId, a.key)?.audioDecoder)
        edits.setExternalPlayer(a, "org.example.player")
        assertEquals("org.example.player", db.channelPrefsDao().get(a.playlistId, a.key)?.externalPlayer)
        edits.setAudioDecoder(a, null)
        assertNull(db.channelPrefsDao().get(a.playlistId, a.key)?.audioDecoder)
        edits.setExternalPlayer(a, "  ")
        assertNull(db.channelPrefsDao().get(a.playlistId, a.key))

        // A playback pref sits beside other edits and clearing it keeps them.
        edits.setHidden(a, true)
        edits.setAspect(a, AspectMode.ZOOM)
        edits.setAspect(a, null)
        val row = db.channelPrefsDao().get(a.playlistId, a.key)!!
        assertTrue(row.hidden)
        assertNull(row.aspectMode)
    }

    @Test fun aspectFitStoredAsNull() = runTest(timeout = 60.seconds) {
        p.add(entries("A"))
        val a = p.ref("A")
        edits.setAspect(a, AspectMode.RATIO_4_3)
        assertEquals(AspectMode.RATIO_4_3, db.channelPrefsDao().get(a.playlistId, a.key)?.aspectMode)
        edits.setAspect(a, AspectMode.FIT)
        assertNull(db.channelPrefsDao().get(a.playlistId, a.key))
    }

    @Test fun playbackEditDoesNotRederiveChannel() = runTest(timeout = 60.seconds) {
        p.add(entries("A"))
        val a = p.channel("A")
        // A sentinel only a re-derive would overwrite (no logo pref exists for A).
        db.openHelper.writableDatabase.execSQL("UPDATE channels SET customLogo = 'sentinel' WHERE id = ${a.id}")
        val before = p.channel("A")
        edits.setVideoDecoder(p.ref("A"), DecoderChoice.SOFTWARE)
        edits.setAudioDecoder(p.ref("A"), DecoderChoice.SOFTWARE)
        edits.setExternalPlayer(p.ref("A"), "ask")
        edits.setAspect(p.ref("A"), AspectMode.STRETCH)
        assertEquals(before, p.channel("A"))
        assertEquals("sentinel", p.channel("A").customLogo)
    }

    @Test fun shiftOutsideChoicesRejected() = runTest(timeout = 60.seconds) {
        assertEquals(30, SHIFT_STEP_MINUTES)
        assertEquals(49, SHIFT_CHOICES.size)
        assertEquals(-720, SHIFT_CHOICES.first())
        assertEquals(720, SHIFT_CHOICES.last())
        p.add(entries("A"))
        val a = p.ref("A")
        assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { edits.setShift(a, 45) } }
        assertNull(db.channelPrefsDao().get(a.playlistId, a.key))
        edits.setShift(a, -90)
        assertEquals(-90, p.channel("A").epgShiftMinutes)
        edits.setShift(a, 0)
        assertEquals(0, p.channel("A").epgShiftMinutes)
        assertNull(db.channelPrefsDao().get(a.playlistId, a.key))
    }
}
