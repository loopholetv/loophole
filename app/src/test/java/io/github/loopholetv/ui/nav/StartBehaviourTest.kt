package io.github.loopholetv.ui.nav

import io.github.loopholetv.data.db.ChannelEntity
import io.github.loopholetv.data.db.ChannelScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StartBehaviourTest {
    private val channel = ChannelEntity(
        id = 7, playlistId = 1, key = "k", number = 1, name = "One", streamUrl = "http://x/1",
        logo = null, groupTitle = "News", groupOrder = 0, sortOrder = 0,
        tvgId = null, tvgName = null, userAgent = null, referrer = null, catchupType = null,
        catchupDays = null, catchupSource = null, epgChannelId = null,
    )

    private fun resolve(
        enabled: Boolean = true,
        latest: ChannelEntity? = channel,
        scope: ChannelScope = ChannelScope.Favourites,
        contains: Boolean = true,
    ) = runBlocking { resolveStartPlayback(enabled, { latest }, scope, { _, _ -> contains }) }

    @Test fun disabledReturnsNull() = assertNull(resolve(enabled = false))

    @Test fun noHistoryOpensGuide() = assertNull(resolve(latest = null))

    @Test fun lastChannelGoneOpensGuide() = assertNull(resolve(latest = null, scope = ChannelScope.All))

    @Test fun keepsLastScopeWhenItContainsChannel() =
        assertEquals(StartPlayback(7, ChannelScope.Favourites), resolve())

    @Test fun fallsBackToAllChannels() =
        assertEquals(StartPlayback(7, ChannelScope.All), resolve(contains = false))

    @Test fun savedScopeIsResolvedBeforeContains() {
        // A legacy "g:News" scope is Group(0, "News"): only its resolved form can contain the channel.
        val legacy = ChannelScope.Group(0, "News")
        val resolved = ChannelScope.Group(1, "News")
        val start = runBlocking {
            resolveStartPlayback(
                true, { channel }, legacy, { _, scope -> scope == resolved },
                resolveScope = { if (it == legacy) resolved else it },
            )
        }
        assertEquals(StartPlayback(7, resolved), start)
        val gone = runBlocking {
            resolveStartPlayback(true, { channel }, legacy, { _, scope -> scope == resolved }, resolveScope = { ChannelScope.All })
        }
        assertEquals(StartPlayback(7, ChannelScope.All), gone)
    }

    @Test fun startSlotIsTakenOnceAtTheGuide() {
        val slot = StartSlot(StartPlayback(7, ChannelScope.All))
        assertEquals(StartPlayback(7, ChannelScope.All), slot.take(Routes.GUIDE))
        // Guide composed again (e.g. after onboarding -> new playlist): nothing stale to replay.
        assertNull(slot.take(Routes.GUIDE))
    }

    @Test fun startSlotIsDroppedWhenTheGuideIsNotCurrent() {
        // The no-playlist fallback already moved to onboarding: the start channel is dropped, so
        // adding a playlist later can't open it.
        val slot = StartSlot(StartPlayback(7, ChannelScope.All))
        assertNull(slot.take(Routes.ONBOARDING))
        assertNull(slot.take(Routes.GUIDE))
    }

    @Test fun emptyStartSlotNeverNavigates() = assertNull(StartSlot(null).take(Routes.GUIDE))
}
