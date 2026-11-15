package io.github.loopholetv.phone

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PhoneJobsTest {
    private fun TestScope.jobs() = PhoneJobs(backgroundScope)

    @Test fun runsOneAtATime() = runTest {
        val jobs = jobs()
        val log = mutableListOf<String>()
        val firstGate = CompletableDeferred<Unit>()
        jobs.enqueue(PhoneJobKind.REFRESH, 1, "One") {
            log += "start 1"
            firstGate.await()
            log += "end 1"
            PhoneJobPhase.Done(1)
        }
        jobs.enqueue(PhoneJobKind.REFRESH, 2, "Two") {
            log += "start 2"
            PhoneJobPhase.Done(2)
        }
        runCurrent()
        assertEquals(listOf("start 1"), log)
        assertEquals("One", jobs.current.value?.name)
        firstGate.complete(Unit)
        runCurrent()
        assertEquals(listOf("start 1", "end 1", "start 2"), log)
        assertEquals(PhoneJobPhase.Done(2), jobs.current.value?.phase)
    }

    @Test fun aJobIsCurrentBeforeItLeavesTheQueue() = runTest {
        val jobs = jobs()
        // Seen the moment the queue changes: a read there must find the job that left it.
        val seen = mutableListOf<Pair<Int, Long?>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            jobs.queued.collect { seen += it to jobs.current.value?.id }
        }
        val id = jobs.enqueue(PhoneJobKind.ADD, null, "New") { PhoneJobPhase.Done(1) }
        runCurrent()
        assertEquals(listOf(0 to null, 1 to null, 0 to id), seen)
    }

    @Test fun secondAddQueues() = runTest {
        val jobs = jobs()
        val gate = CompletableDeferred<Unit>()
        jobs.enqueue(PhoneJobKind.ADD, null, "First") { progress ->
            progress(PhoneJobPhase.LoadingChannels(40))
            gate.await()
            PhoneJobPhase.Done(7)
        }
        val second = jobs.enqueue(PhoneJobKind.ADD, null, "Second") { PhoneJobPhase.Done(8) }
        runCurrent()
        assertEquals(1, jobs.queued.value)
        assertEquals(PhoneJobPhase.LoadingChannels(40), jobs.current.value?.phase)
        gate.complete(Unit)
        runCurrent()
        assertEquals(0, jobs.queued.value)
        val done = jobs.current.value!!
        assertEquals(second, done.id)
        // An add's playlist is known once it is done.
        assertEquals(8L, done.playlistId)
    }

    @Test fun busyCoversRunningAndQueued() = runTest {
        val jobs = jobs()
        val gate = CompletableDeferred<Unit>()
        jobs.enqueue(PhoneJobKind.REFRESH, 1, "One") { gate.await(); PhoneJobPhase.Done(1) }
        jobs.enqueue(PhoneJobKind.EDIT, 2, "Two") { PhoneJobPhase.Done(2) }
        assertTrue(jobs.isBusy(1))
        assertTrue(jobs.isBusy(2))
        runCurrent()
        assertTrue(jobs.isBusy(1))
        assertTrue(jobs.isBusy(2))
        assertFalse(jobs.isBusy(3))
        gate.complete(Unit)
        runCurrent()
        assertFalse(jobs.isBusy(1))
        assertFalse(jobs.isBusy(2))
    }

    @Test fun failureKeepsFixedCopy() = runTest {
        val jobs = jobs()
        jobs.enqueue(PhoneJobKind.REFRESH, 1, "One") { throw IllegalStateException("http://user:secret@host/") }
        runCurrent()
        val phase = jobs.current.value?.phase as PhoneJobPhase.Failed
        assertEquals("Something went wrong while loading the playlist.", phase.message)
        assertFalse(jobs.isBusy(1))
        // The queue keeps going after a failure.
        jobs.enqueue(PhoneJobKind.REFRESH, 2, "Two") { PhoneJobPhase.Done(2) }
        runCurrent()
        assertEquals(PhoneJobPhase.Done(2), jobs.current.value?.phase)
    }

    @Test fun strayCancellationFailsAndQueueGoesOn() = runTest {
        val jobs = jobs()
        jobs.enqueue(PhoneJobKind.REFRESH, 1, "One") { throw CancellationException("x") }
        runCurrent()
        assertEquals(PhoneJobPhase.Failed("Something went wrong while loading the playlist."), jobs.current.value?.phase)
        assertFalse(jobs.isBusy(1))
        jobs.enqueue(PhoneJobKind.REFRESH, 2, "Two") { throw OutOfMemoryError() }
        jobs.enqueue(PhoneJobKind.REFRESH, 3, "Three") { PhoneJobPhase.Done(3) }
        runCurrent()
        assertEquals(PhoneJobPhase.Done(3), jobs.current.value?.phase)
        assertFalse(jobs.isBusy(2))
    }

    @Test fun claimedPlaylistIsBusyUntilTheJobEnds() = runTest {
        val jobs = jobs()
        val gate = CompletableDeferred<Unit>()
        jobs.enqueue(PhoneJobKind.ADD, null, "New") { progress ->
            progress.claim(9)
            progress(PhoneJobPhase.LoadingGuide(0))
            gate.await()
            PhoneJobPhase.Done(9)
        }
        runCurrent()
        assertTrue(jobs.isBusy(9))
        assertEquals(9L, jobs.current.value?.playlistId)
        gate.complete(Unit)
        runCurrent()
        assertFalse(jobs.isBusy(9))
    }

    @Test fun finishedJobClearsAfter30s() = runTest {
        val jobs = jobs()
        jobs.enqueue(PhoneJobKind.REFRESH, 1, "One") { PhoneJobPhase.Done(1) }
        runCurrent()
        assertNotNull(jobs.current.value)
        advanceTimeBy(29_999)
        runCurrent()
        assertEquals(PhoneJobPhase.Done(1), jobs.current.value?.phase)
        advanceTimeBy(2)
        runCurrent()
        assertNull(jobs.current.value)
    }

    @Test fun nextJobIsNotClearedByTheLastOnesTimer() = runTest {
        val jobs = jobs()
        jobs.enqueue(PhoneJobKind.REFRESH, 1, "One") { PhoneJobPhase.Done(1) }
        runCurrent()
        advanceTimeBy(20_000)
        val gate = CompletableDeferred<Unit>()
        jobs.enqueue(PhoneJobKind.REFRESH, 2, "Two") { gate.await(); PhoneJobPhase.Done(2) }
        runCurrent()
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals("Two", jobs.current.value?.name)
        gate.complete(Unit)
    }

    @Test fun cancelledCallerDoesNotCancelJob() = runTest {
        val jobs = jobs()
        val gate = CompletableDeferred<Unit>()
        val caller = launch {
            jobs.enqueue(PhoneJobKind.ADD, null, "One") { gate.await(); PhoneJobPhase.Done(5) }
            gate.await() // The request waits too, then the phone goes away.
        }
        runCurrent()
        caller.cancelAndJoin()
        gate.complete(Unit)
        runCurrent()
        assertEquals(PhoneJobPhase.Done(5), jobs.current.value?.phase)
    }
}
