package com.wanderwildwood.garo.media

import org.junit.Assert.assertEquals
import org.junit.Test

class ClockTest {
    @Test
    fun `lengths read the way a player shows them`() {
        assertEquals("0:00", Clock.format(0))
        assertEquals("0:42", Clock.format(42_000))
        assertEquals("0:43", Clock.format(42_600))
        assertEquals("3:07", Clock.format(187_000))
        assertEquals("1:02:15", Clock.format(3_735_000))
        assertEquals("0:00", Clock.format(-5))
    }
}
