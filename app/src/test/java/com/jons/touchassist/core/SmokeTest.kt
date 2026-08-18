package com.jons.touchassist.core

import org.junit.Assert.assertEquals
import org.junit.Test

class SmokeTest {

    @Test
    fun clickTargetInfoHasExpectedDefaults() {
        val target = AutoClickService.ClickTargetInfo(id = "t1", x = 10f, y = 20f)

        assertEquals("t1", target.id)
        assertEquals(10f, target.x, 0f)
        assertEquals(20f, target.y, 0f)
        assertEquals(AutoClickService.ClickType.SINGLE, target.clickType)
        assertEquals(100L, target.interval)
        assertEquals(0, target.swipeDistance)
        assertEquals(270, target.swipeAngle)
    }
}
