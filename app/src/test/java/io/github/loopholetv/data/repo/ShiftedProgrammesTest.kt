package io.github.loopholetv.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.db.ChannelEntity
import io.github.loopholetv.data.db.ProgrammeEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class ShiftedProgrammesTest {
    private lateinit var db: AppDatabase
    private lateinit var shifted: ShiftedProgrammes

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        shifted = ShiftedProgrammes(db.programmeDao())
    }

    @After fun tearDown() = db.close()

    private fun at(hhmm: String): Instant = Instant.parse("2026-11-07T$hhmm:00Z")

    private fun programme(epgId: String, from: String, to: String, title: String) = ProgrammeEntity(
        epgChannelId = epgId, start = at(from), stop = at(to), title = title,
        subTitle = null, description = null, category = null, generation = 1,
    )

    private suspend fun store(vararg p: ProgrammeEntity) = db.programmeDao().insertAll(p.toList())

    private fun channel(guideEpgId: String?, shift: Int) = ChannelEntity(
        playlistId = 1, key = "k", number = 1, name = "C", streamUrl = "http://stream.example.test/1.ts", logo = null,
        groupTitle = "G", groupOrder = 0, sortOrder = 0, tvgId = null, tvgName = null, userAgent = null, referrer = null,
        catchupType = null, catchupDays = null, catchupSource = null, epgChannelId = guideEpgId, epgShiftMinutes = shift,
    )

    private fun List<ProgrammeEntity>.spans() = map { "${it.title} ${it.start}-${it.stop}" }

    @Test fun windowShiftsQueryAndResult() = runTest {
        store(programme("a", "10:00", "11:00", "A"), programme("a", "12:00", "13:00", "B"))
        val key = GuideKey("a", 60)

        val result = shifted.inWindow(listOf(key), at("11:00"), at("12:00")).first()

        assertEquals(listOf("A ${at("11:00")}-${at("12:00")}"), result.getValue(key).spans())
    }

    @Test fun sameEpgIdTwoShiftsSeparate() = runTest {
        store(programme("a", "09:00", "10:00", "Z"), programme("a", "10:00", "11:00", "A"))
        val plain = GuideKey("a", 0)
        val later = GuideKey("a", 60)
        val earlier = GuideKey("a", -30)

        val result = shifted.inWindow(listOf(plain, later, earlier, plain), at("10:00"), at("11:00")).first()

        assertEquals(setOf(plain, later, earlier), result.keys)
        assertEquals(listOf("A ${at("10:00")}-${at("11:00")}"), result.getValue(plain).spans())
        assertEquals(listOf("Z ${at("10:00")}-${at("11:00")}"), result.getValue(later).spans())
        // −30: the window reads 10:30–11:30 stored, so A (10:00–11:00) shows 09:30–10:30.
        assertEquals(listOf("A ${at("09:30")}-${at("10:30")}"), result.getValue(earlier).spans())
    }

    @Test fun nowAndNextUsesShift() = runTest {
        store(programme("a", "09:00", "10:00", "Z"), programme("a", "10:00", "11:00", "A"), programme("a", "11:00", "12:00", "B"))

        val now = shifted.nowAndNext(channel("a", 60), at("10:30"))
        assertEquals(listOf("Z ${at("10:00")}-${at("11:00")}", "A ${at("11:00")}-${at("12:00")}"), now.spans())

        assertEquals(listOf("A", "B"), shifted.nowAndNext(channel("a", 0), at("10:30")).map { it.title })
        assertEquals(emptyList<ProgrammeEntity>(), shifted.nowAndNext(channel(null, 60), at("10:30")))
    }

    @Test fun chunksOver900Ids() = runTest {
        store(programme("e0", "10:00", "11:00", "first"), programme("e999", "10:00", "11:00", "last"))
        val keys = (0 until 1000).map { GuideKey("e$it", 0) }

        val result = shifted.inWindow(keys, at("10:00"), at("11:00")).first()

        assertEquals(1000, result.size)
        assertEquals(listOf("first"), result.getValue(GuideKey("e0", 0)).map { it.title })
        assertEquals(listOf("last"), result.getValue(GuideKey("e999", 0)).map { it.title })
        assertTrue(result.getValue(GuideKey("e500", 0)).isEmpty())
    }

    @Test fun guideKeyAndShiftedBy() {
        assertNull(channel(null, 30).guideKey)
        assertEquals(GuideKey("a", 30), channel("a", 30).guideKey)
        val p = programme("a", "10:00", "11:00", "A").shiftedBy(-90)
        assertEquals(at("08:30"), p.start)
        assertEquals(at("09:30"), p.stop)
    }

    @Test fun noKeysGivesEmptyMap() = runTest {
        assertEquals(emptyMap<GuideKey, List<ProgrammeEntity>>(), shifted.inWindow(emptyList(), at("10:00"), at("11:00")).first())
    }
}
