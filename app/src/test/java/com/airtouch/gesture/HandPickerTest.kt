package com.airtouch.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HandPickerTest {
    private fun frame(t: Long, x: Float, y: Float, s: Float) = HandFrame(t, syntheticHand(x, y, s = s), 480, 640)

    @Test
    fun withoutLock_picksLargestHand() {
        val p = HandPicker()
        val far = frame(0, 0.2f, 0.3f, s = 0.06f)   // чужая рука вдали
        val near = frame(0, 0.7f, 0.6f, s = 0.18f)  // своя, у камеры
        assertEquals(1, p.pick(listOf(far, near), 0))
    }

    @Test
    fun locked_followsOwnHand_evenWhenStrangerIsBigger() {
        val p = HandPicker()
        assertEquals(0, p.pick(listOf(frame(0, 0.5f, 0.5f, 0.15f)), 0))
        // Своя рука чуть сдвинулась; чужая появилась крупнее, но далеко от прошлого места.
        val own = frame(33, 0.52f, 0.51f, 0.15f)
        val stranger = frame(33, 0.1f, 0.9f, 0.22f)
        assertEquals(0, p.pick(listOf(own, stranger), 33))
        assertEquals(1, p.pick(listOf(stranger, own), 66))
    }

    @Test
    fun tinyFarHands_areIgnored() {
        val p = HandPicker()
        assertEquals(-1, p.pick(listOf(frame(0, 0.5f, 0.5f, 0.03f)), 0))
    }

    @Test
    fun ownHandGone_strangerDoesNotTakeOver_untilLockExpires() {
        val p = HandPicker(keepMs = 1000)
        p.pick(listOf(frame(0, 0.5f, 0.5f, 0.15f)), 0)
        val stranger = frame(100, 0.05f, 0.95f, 0.15f)
        assertEquals(-1, p.pick(listOf(stranger), 100))
        // Прошло больше keepMs без своей руки — захват снят, берём кого есть.
        assertEquals(0, p.pick(listOf(stranger), 1200))
        assertTrue(p.locked)
    }
}
