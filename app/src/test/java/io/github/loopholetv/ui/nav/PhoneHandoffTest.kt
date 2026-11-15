package io.github.loopholetv.ui.nav

import io.github.loopholetv.phone.PhoneJob
import io.github.loopholetv.phone.PhoneJobKind
import io.github.loopholetv.phone.PhoneJobPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneHandoffTest {
    private fun job(
        kind: PhoneJobKind = PhoneJobKind.ADD,
        phase: PhoneJobPhase = PhoneJobPhase.LoadingChannels(10),
        playlistId: Long? = null,
    ) = PhoneJob(id = 1, kind = kind, playlistId = playlistId, name = "Home", phase = phase)

    @Test fun addOnOnboardingOpensPhoneScreen() {
        assertEquals(PhoneHandoff.OPEN_PHONE_SCREEN, phoneHandoff(Routes.ONBOARDING, job()))
        assertEquals(PhoneHandoff.OPEN_PHONE_SCREEN, phoneHandoff(Routes.ONBOARDING, job(phase = PhoneJobPhase.LoadingGuide(5), playlistId = 3)))
    }

    @Test fun addOnCopyPickerOpensPhoneScreen() {
        assertEquals(PhoneHandoff.OPEN_PHONE_SCREEN, phoneHandoff(Routes.SETUP_COPY_FROM, job()))
    }

    @Test fun addOnFirstStartPhoneScreenStaysThere() {
        assertEquals(PhoneHandoff.NONE, phoneHandoff(Routes.SETUP_PHONE, job()))
        assertTrue(isFirstStartRoute(Routes.SETUP_PHONE))
    }

    @Test fun doneOnPhoneScreenGoesToGuide() {
        val done = job(phase = PhoneJobPhase.Done(3), playlistId = 3)
        assertEquals(PhoneHandoff.GO_TO_GUIDE, phoneHandoff(Routes.SETUP_PHONE, done))
        assertEquals(PhoneHandoff.GO_TO_GUIDE, phoneHandoff(Routes.ONBOARDING, done))
    }

    @Test fun failedAddStaysPut() {
        assertEquals(PhoneHandoff.NONE, phoneHandoff(Routes.SETUP_PHONE, job(phase = PhoneJobPhase.Failed("Couldn't load"))))
        assertEquals(PhoneHandoff.NONE, phoneHandoff(Routes.ONBOARDING, job(phase = PhoneJobPhase.Failed("Couldn't load"))))
    }

    @Test fun addFromGuideDoesNothing() {
        val done = job(phase = PhoneJobPhase.Done(3), playlistId = 3)
        for (route in listOf(Routes.GUIDE, Routes.SETTINGS, Routes.PHONE_SETUP, Routes.PLAYER, Routes.PLAYLIST_FORM)) {
            assertEquals(route, PhoneHandoff.NONE, phoneHandoff(route, job()))
            assertEquals(route, PhoneHandoff.NONE, phoneHandoff(route, done))
        }
    }

    @Test fun noJobOrNoRouteDoesNothing() {
        assertEquals(PhoneHandoff.NONE, phoneHandoff(Routes.ONBOARDING, null))
        assertEquals(PhoneHandoff.NONE, phoneHandoff(null, job()))
    }

    @Test fun eachJobMovesTheTvAtMostOncePerStep() {
        val once = PhoneHandoffOnce()
        val running = job()
        assertEquals(PhoneHandoff.OPEN_PHONE_SCREEN, once.next(Routes.ONBOARDING, running))
        // Back from the phone screen while it loads: the TV isn't sent there again.
        assertEquals(PhoneHandoff.NONE, once.next(Routes.ONBOARDING, running.copy(phase = PhoneJobPhase.LoadingGuide(9))))
        val done = running.copy(phase = PhoneJobPhase.Done(3), playlistId = 3)
        assertEquals(PhoneHandoff.GO_TO_GUIDE, once.next(Routes.SETUP_PHONE, done))
        // The same finished job again (re-emitted, or onboarding reached again) moves nothing.
        assertEquals(PhoneHandoff.NONE, once.next(Routes.SETUP_PHONE, done))
        assertEquals(PhoneHandoff.NONE, once.next(Routes.ONBOARDING, done))
        // A later job is its own.
        val next = PhoneJob(id = 2, kind = PhoneJobKind.ADD, playlistId = null, name = "Two", phase = PhoneJobPhase.LoadingChannels(0))
        assertEquals(PhoneHandoff.OPEN_PHONE_SCREEN, once.next(Routes.ONBOARDING, next))
    }

    @Test fun editOrRefreshNeverMoves() {
        for (kind in listOf(PhoneJobKind.EDIT, PhoneJobKind.REFRESH)) {
            for (route in listOf(Routes.ONBOARDING, Routes.SETUP_COPY_FROM, Routes.SETUP_PHONE)) {
                assertEquals(PhoneHandoff.NONE, phoneHandoff(route, job(kind = kind, playlistId = 3)))
                assertEquals(PhoneHandoff.NONE, phoneHandoff(route, job(kind = kind, phase = PhoneJobPhase.Done(3), playlistId = 3)))
            }
        }
    }
}
