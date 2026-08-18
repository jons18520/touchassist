package com.jons.touchassist.core

import com.jons.touchassist.core.AutoClickService.ClickTargetInfo
import com.jons.touchassist.core.AutoClickService.ClickType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetSchedulerTest {

    private val scheduler = TargetScheduler()

    private fun target(
        id: String,
        type: ClickType = ClickType.SINGLE,
        swipeDistance: Int = 0,
        swipeAngle: Int = 270
    ) = ClickTargetInfo(
        id = id,
        x = 0f,
        y = 0f,
        clickType = type,
        interval = 100L,
        swipeDistance = swipeDistance,
        swipeAngle = swipeAngle
    )

    @Test
    fun newTargetStarts() {
        val diff = scheduler.computeDiff(emptyMap(), listOf(target("a")), isClicking = true)

        assertEquals(listOf("a"), diff.targetsToStart.map { it.id })
        assertTrue(diff.singleJobIdsToCancel.isEmpty())
        assertTrue(diff.longPressJobIdsToCancel.isEmpty())
        assertTrue(diff.removedIds.isEmpty())
    }

    @Test
    fun removedTargetIsReported() {
        val old = mapOf("a" to target("a"), "b" to target("b"))

        val diff = scheduler.computeDiff(old, listOf(target("a")), isClicking = true)

        assertEquals(setOf("b"), diff.removedIds)
        assertTrue(diff.targetsToStart.isEmpty())
    }

    @Test
    fun typeSwitchCancelsOldAndStartsNew() {
        val old = mapOf("a" to target("a", type = ClickType.SINGLE))

        val diff = scheduler.computeDiff(
            old,
            listOf(target("a", type = ClickType.LONG_PRESS)),
            isClicking = true
        )

        assertEquals(listOf("a"), diff.targetsToStart.map { it.id })
        assertEquals(setOf("a"), diff.singleJobIdsToCancel)
        assertTrue(diff.longPressJobIdsToCancel.isEmpty())
    }

    @Test
    fun swipeParamChangeRestartsLongPress() {
        val old = mapOf(
            "a" to target("a", type = ClickType.LONG_PRESS, swipeDistance = 10, swipeAngle = 270)
        )

        val diff = scheduler.computeDiff(
            old,
            listOf(target("a", type = ClickType.LONG_PRESS, swipeDistance = 20, swipeAngle = 270)),
            isClicking = true
        )

        assertEquals(listOf("a"), diff.targetsToStart.map { it.id })
        assertEquals(setOf("a"), diff.longPressJobIdsToCancel)
        assertTrue(diff.singleJobIdsToCancel.isEmpty())
    }

    @Test
    fun noChangeDoesNothingWhenClicking() {
        val old = mapOf("a" to target("a"))

        val diff = scheduler.computeDiff(old, listOf(target("a")), isClicking = true)

        assertTrue(diff.targetsToStart.isEmpty())
        assertTrue(diff.singleJobIdsToCancel.isEmpty())
        assertTrue(diff.longPressJobIdsToCancel.isEmpty())
        assertTrue(diff.removedIds.isEmpty())
    }

    @Test
    fun notClickingOnlyReportsRemovals() {
        val old = mapOf("a" to target("a"), "b" to target("b"))

        val diff = scheduler.computeDiff(
            old,
            listOf(target("a", type = ClickType.LONG_PRESS)),
            isClicking = false
        )

        assertEquals(setOf("b"), diff.removedIds)
        assertTrue(diff.targetsToStart.isEmpty())
        assertTrue(diff.singleJobIdsToCancel.isEmpty())
        assertTrue(diff.longPressJobIdsToCancel.isEmpty())
    }
}
