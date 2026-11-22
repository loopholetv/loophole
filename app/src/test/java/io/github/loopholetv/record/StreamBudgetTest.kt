package io.github.loopholetv.record

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamBudgetTest {
    private val a = "a.test"
    private val b = "b.test"
    private fun use(what: Use, host: String = a, channelId: Long? = null) = StreamUse(host, what, channelId)

    @Test fun underLimitStarts() {
        assertEquals(StartDecision.Start, decideStart(a, 2, listOf(use(Use.PLAYER)), watchingSameChannel = 7L))
        assertEquals(StartDecision.Start, decideStart(a, 1, emptyList(), null))
    }

    @Test fun watchedChannelIsTakenOver() {
        assertEquals(
            StartDecision.TakeWatched(7L),
            decideStart(a, 1, listOf(use(Use.PLAYER, channelId = 7L)), watchingSameChannel = 7L),
        )
        assertEquals(
            StartDecision.TakeWatched(7L),
            decideStart(a, 2, listOf(use(Use.RECORDING), use(Use.CAST, channelId = 7L)), watchingSameChannel = 7L),
        )
    }

    @Test fun watchingAnotherChannelOrOnlyRecordingsIsNotTakenOver() {
        // A different channel is held: the normal rules apply.
        assertEquals(
            StartDecision.Warn(Use.PLAYER),
            decideStart(a, 1, listOf(use(Use.PLAYER, channelId = 8L)), watchingSameChannel = 7L),
        )
        // The watched channel's use is on another host, or is itself a recording: nothing to take over.
        assertEquals(
            StartDecision.OverLimit,
            decideStart(a, 1, listOf(use(Use.RECORDING, channelId = 7L)), watchingSameChannel = 7L),
        )
        assertEquals(
            StartDecision.OverLimit,
            decideStart(a, 1, listOf(use(Use.RECORDING)), watchingSameChannel = 7L),
        )
        assertEquals(
            StartDecision.TakePreview,
            decideStart(a, 1, listOf(use(Use.PREVIEW)), watchingSameChannel = 7L),
        )
    }

    @Test fun onlyPreviewIsTaken() {
        assertEquals(StartDecision.TakePreview, decideStart(a, 1, listOf(use(Use.PREVIEW)), null))
        assertEquals(
            StartDecision.TakePreview,
            decideStart(a, 2, listOf(use(Use.PREVIEW), use(Use.PREVIEW)), null),
        )
        assertEquals(
            StartDecision.TakePreview,
            decideStart(a, 2, listOf(use(Use.PREVIEW), use(Use.RECORDING)), null),
        )
    }

    @Test fun playerOrCastWarns() {
        assertEquals(StartDecision.Warn(Use.PLAYER), decideStart(a, 1, listOf(use(Use.PLAYER)), null))
        assertEquals(StartDecision.Warn(Use.CAST), decideStart(a, 1, listOf(use(Use.CAST)), null))
        assertEquals(
            StartDecision.Warn(Use.PLAYER),
            decideStart(a, 2, listOf(use(Use.PREVIEW), use(Use.PLAYER)), null),
        )
    }

    @Test fun onlyRecordingsFillTheLimit() {
        assertEquals(StartDecision.OverLimit, decideStart(a, 1, listOf(use(Use.RECORDING)), null))
        assertEquals(
            StartDecision.OverLimit,
            decideStart(a, 2, listOf(use(Use.RECORDING), use(Use.RECORDING)), null),
        )
    }

    @Test fun otherHostIsNotCounted() {
        // Starting a recording on A (limit 1) while a player holds host B: A is free.
        assertEquals(StartDecision.Start, decideStart(a, 1, listOf(use(Use.PLAYER, b)), null))
        assertEquals(StartDecision.Start, decideStart(a, 1, listOf(use(Use.PLAYER, b), use(Use.CAST, b)), 7L))
    }
}
