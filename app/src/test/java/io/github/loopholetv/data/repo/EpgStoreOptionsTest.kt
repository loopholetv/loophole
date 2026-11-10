package io.github.loopholetv.data.repo

import io.github.loopholetv.data.settings.AUTO_PAST_DAYS
import io.github.loopholetv.data.settings.AppSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class EpgStoreOptionsTest {
    @Test fun autoPastDaysFollowsCatchupChannels() = runBlocking {
        val auto = AppSettings(epgPastDays = AUTO_PAST_DAYS)
        assertEquals(3, epgStoreOptions(auto) { 3 }.pastDays)
        assertEquals(0, epgStoreOptions(auto) { null }.pastDays)
        assertEquals(7, epgStoreOptions(auto) { 30 }.pastDays)
    }

    @Test fun explicitSettingIgnoresChannels() = runBlocking {
        var asked = false
        val o = epgStoreOptions(AppSettings(epgPastDays = 2, epgStoreDescriptions = false)) { asked = true; 5 }
        assertEquals(EpgStoreOptions(pastDays = 2, storeDescriptions = false), o)
        assertEquals(false, asked)
    }
}
