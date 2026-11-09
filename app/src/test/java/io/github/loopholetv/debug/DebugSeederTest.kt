package io.github.loopholetv.debug

import io.github.loopholetv.data.db.inMemoryDb
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
class DebugSeederTest {
    @Test fun seedsTwoPlaylistsWithContinuedNumbersAndReseedReplacesBoth() = runTest(timeout = 60.seconds) {
        val db = inMemoryDb()
        try {
            val seeder = DebugSeeder(db)
            val now = Instant.parse("2026-11-09T12:00:00Z")
            seeder.seed(now, channels = 11)
            seeder.seed(now, channels = 11)
            val playlists = db.playlistDao().all().first()
            assertEquals(listOf(DebugSeeder.NAME, DebugSeeder.NAME_2), playlists.map { it.name })
            val first = db.channelDao().channelsOf(playlists[0].id)
            val second = db.channelDao().channelsOf(playlists[1].id)
            assertEquals((1..6).toList(), first.map { it.guideNumber })
            assertEquals((1..5).toList(), second.map { it.number })
            assertEquals((7..11).toList(), second.map { it.guideNumber })
        } finally {
            db.close()
        }
    }
}
