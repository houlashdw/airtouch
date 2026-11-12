package com.airtouch.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GrabDepthTest {
    private fun fist(t: Long, s: Float) = HandFrame(t, syntheticHand(0.5f, 0.5f, s = s, extended = booleanArrayOf(false, false, false, false)), 480, 640)
    private fun open(t: Long, s: Float) = HandFrame(t, syntheticHand(0.5f, 0.5f, s = s), 480, 640)

    private fun run(d: GrabDepthDetector, frames: List<Pair<HandFrame, HandPose>>): List<SystemAction> =
        frames.mapNotNull { d.process(it.first, it.second).action }

    @Test
    fun fistPullBack_isRecents_holdIsNotifications() {
        val d = GrabDepthDetector()
        var t = 0L
        val seq = ArrayList<Pair<HandFrame, HandPose>>()
        repeat(4) { t += 33; seq += fist(t, 0.2f) to HandPose.FIST }      // сжал
        repeat(6) { t += 33; seq += fist(t, 0.2f - it * 0.012f) to HandPose.FIST } // тянет назад: 0.2 → 0.14
        val first = run(d, seq)
        assertEquals(listOf(SystemAction.RECENTS), first)
        val hold = ArrayList<Pair<HandFrame, HandPose>>()
        repeat(25) { t += 33; hold += fist(t, 0.14f) to HandPose.FIST }   // держит ~800 мс
        assertEquals(listOf(SystemAction.NOTIFICATIONS), run(d, hold))
        // Разжал — всё сброшено, повтора нет.
        t += 33
        assertNull(d.process(open(t, 0.14f), HandPose.OPEN_PALM).action)
    }

    @Test
    fun fistPush_isBack() {
        val d = GrabDepthDetector()
        var t = 0L
        val seq = ArrayList<Pair<HandFrame, HandPose>>()
        repeat(4) { t += 33; seq += fist(t, 0.14f) to HandPose.FIST }
        repeat(6) { t += 33; seq += fist(t, 0.14f + it * 0.012f) to HandPose.FIST } // к экрану: 0.14 → 0.2
        assertEquals(listOf(SystemAction.BACK), run(d, seq))
    }

    @Test
    fun openHandDepthChange_doesNothing() {
        val d = GrabDepthDetector()
        var t = 0L
        val seq = ArrayList<Pair<HandFrame, HandPose>>()
        repeat(12) { t += 33; seq += open(t, 0.2f - it * 0.008f) to HandPose.OPEN_PALM }
        assertEquals(emptyList<SystemAction>(), run(d, seq))
    }

    @Test
    fun swipeDistance_scalesWithPalm() {
        val cfg = SwipeConfig()
        assertEquals(cfg.requiredDistance, requiredFor(cfg, 0f), 1e-6f)
        assertEquals(cfg.requiredDistance, requiredFor(cfg, 0.3f), 1e-6f) // близкая рука: потолок
        assertEquals(0.088f, requiredFor(cfg, 0.08f), 1e-3f)              // далёкая: короче
        assertEquals(cfg.minDistance, requiredFor(cfg, 0.02f), 1e-6f)     // но не короче пола
    }
}
