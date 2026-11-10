package io.github.loopholetv.data.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class AppSettingsTest {
    @Test fun effectivePastDaysCases() {
        assertEquals(3, effectivePastDays(AUTO_PAST_DAYS, 3))
        assertEquals(0, effectivePastDays(AUTO_PAST_DAYS, null))
        assertEquals(7, effectivePastDays(AUTO_PAST_DAYS, 14))
        assertEquals(2, effectivePastDays(2, 7))
        assertEquals(0, effectivePastDays(0, 7))
    }
}
