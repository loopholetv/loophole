package io.github.loopholetv.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.room.withTransaction
import io.github.loopholetv.LoopholeApp
import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.db.ChannelEntity
import io.github.loopholetv.data.db.PlaylistEntity
import io.github.loopholetv.data.db.PlaylistType
import io.github.loopholetv.data.db.ProgrammeEntity
import io.github.loopholetv.data.repo.ChannelOverlay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Debug-only synthetic guide for profiling: [channels] channels (default [CHANNELS]) split over two
 * playlists, [NAME] and [NAME_2] (the first gets the extra one when odd), each in groups of about
 * [CHANNELS_PER_GROUP], with 48 h of 30-min programmes (12 h back, 36 h ahead; every 5th channel,
 * from the third, has 20-min ones instead, so some rows have a programme that ends between the
 * window start and "now"; every 5th from the fourth has 20-min ones on the half hours, leaving a
 * 10-min gap before each half hour), written straight
 * through the DAOs and then run through [ChannelOverlay.refreshAll] (so the second playlist's guide
 * numbers continue after the first's). Every 25th channel has no guide mapping (shows "No
 * information"). Stream URLs point at a closed local port, so the preview fails fast. Re-seeding
 * replaces both seed playlists (and their edits).
 *
 * `adb shell am broadcast -n io.github.loopholetv/.debug.DebugSeedReceiver [--ei channels 10000]`
 */
class DebugSeeder(private val db: AppDatabase) {
    suspend fun seed(now: Instant = Instant.now(), channels: Int = CHANNELS): Int {
        require(channels >= 2) { "Need at least one channel per seed playlist" }
        val startAll = now.truncatedTo(ChronoUnit.HOURS).minus(Duration.ofHours(12))
        val slots = 96
        var programmes = 0
        db.withTransaction {
            val playlists = db.playlistDao()
            db.openHelper.writableDatabase.execSQL("DELETE FROM playlists WHERE name IN ('$NAME', '$NAME_2')")
            db.openHelper.writableDatabase.execSQL("DELETE FROM programmes WHERE epgChannelId LIKE 'seed.%'")
            val generation = (db.programmeDao().maxGeneration() ?: 0) + 1
            val batch = ArrayList<ProgrammeEntity>(5_000)
            val firstCount = channels - channels / 2
            var global = 0
            for ((name, count) in listOf(NAME to firstCount, NAME_2 to channels / 2)) {
                val playlistId = playlists.insert(
                    PlaylistEntity(
                        name = name, type = PlaylistType.M3U, url = "http://127.0.0.1:9/seed.m3u",
                        xtreamServer = null, xtreamUser = null, xtreamPass = null, epgUrlsFromSource = "",
                        manualEpgUrl = null, lastRefreshedAt = now, lastError = null,
                    ),
                )
                val rows = (0 until count).map { i ->
                    val g = global + i
                    val group = i / CHANNELS_PER_GROUP
                    ChannelEntity(
                        playlistId = playlistId, key = "seed-$g", number = i + 1, name = "Seed Channel ${g + 1}",
                        streamUrl = "http://127.0.0.1:9/seed/$g.ts", logo = null,
                        groupTitle = "Seed group ${group + 1}", groupOrder = group, sortOrder = i,
                        tvgId = null, tvgName = null, userAgent = null, referrer = null,
                        catchupType = null, catchupDays = null, catchupSource = null,
                        epgChannelId = if (g % 25 == 24) null else "seed.$g",
                    )
                }
                global += count
                rows.chunked(500).forEach { db.channelDao().insertAll(it) }

                for (c in rows) {
                    val epg = c.epgChannelId ?: continue
                    // Off-grid rows: 20-min programmes, so their blocks don't line up with the half hours.
                    val minutes = if (c.number % 5 == 3) 20L else 30L
                    // Gap rows: 20 min on each half hour, then nothing until the next one.
                    val length = if (c.number % 5 == 4) 20L else minutes
                    for (s in 0 until (slots * 30 / minutes).toInt()) {
                        val start = startAll.plus(Duration.ofMinutes(minutes * s))
                        batch += ProgrammeEntity(
                            epgChannelId = epg, start = start, stop = start.plus(Duration.ofMinutes(length)),
                            title = "Programme ${s + 1} on ${c.name}",
                            subTitle = null,
                            description = if (s % 3 == 0) null else "Synthetic description for slot ${s + 1}.",
                            category = null, generation = generation,
                        )
                        if (batch.size == 5_000) {
                            db.programmeDao().insertAll(batch)
                            programmes += batch.size
                            batch.clear()
                        }
                    }
                }
            }
            db.programmeDao().insertAll(batch)
            programmes += batch.size
            ChannelOverlay(db).refreshAll()
        }
        return programmes
    }

    companion object {
        const val NAME = "Debug seed"
        const val NAME_2 = "Debug seed 2"
        const val CHANNELS = 1_500
        const val CHANNELS_PER_GROUP = 100
        /** Int extra: total channel count over both playlists. */
        const val EXTRA_CHANNELS = "channels"
    }
}

class DebugSeedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext as LoopholeApp
        app.applicationScope.launch {
            try {
                val started = System.nanoTime()
                val channels = intent.getIntExtra(DebugSeeder.EXTRA_CHANNELS, DebugSeeder.CHANNELS).coerceAtLeast(2)
                val n = DebugSeeder(app.container.db).seed(channels = channels)
                Log.i(TAG, "Seeded $channels channels in 2 playlists, $n programmes in ${(System.nanoTime() - started) / 1_000_000} ms")
            } catch (e: Exception) {
                Log.e(TAG, "Seeding failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "LoopholeSeed"
    }
}
