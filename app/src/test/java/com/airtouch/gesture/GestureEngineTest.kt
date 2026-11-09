package com.airtouch.gesture

import org.junit.Assert.assertEquals
import org.junit.Test

/** Курсор, щипок и разделение жестов, чтобы они не путались между собой. */
class GestureEngineTest {

    private val palm = booleanArrayOf(true, true, true, true)
    private val finger = booleanArrayOf(true, false, false, false)
    private val fist = booleanArrayOf(false, false, false, false)

    private inner class Sim(cursor: Boolean) {
        val engine = GestureEngine(EngineConfig(cursorEnabled = cursor))
        val swipes = mutableListOf<AirGesture>()
        val touches = mutableListOf<TouchEvent>()
        var t = 0L
        var x = 0.5f
        var y = 0.5f

        fun move(tx: Float, ty: Float, ms: Long, pose: BooleanArray, pinch: Boolean = false) = apply {
            val steps = (ms / 33).coerceAtLeast(1)
            val sx = x
            val sy = y
            for (i in 1..steps) {
                t += 33
                val k = i.toFloat() / steps
                val out = engine.process(HandFrame(t, syntheticHand(sx + (tx - sx) * k, sy + (ty - sy) * k, pose, pinch = pinch)))
                out.swipe?.let { swipes += it }
                out.touch?.let { touches += it }
            }
            x = tx
            y = ty
        }

        fun hold(ms: Long, pose: BooleanArray, pinch: Boolean = false) = move(x, y, ms, pose, pinch)

        fun lose() = apply { t += 33; engine.onHandLost()?.let { touches += it } }
    }

    private fun kinds(list: List<TouchEvent>) = list.map { it::class.simpleName }

    @Test
    fun cursorOff_fingerSwipes() {
        val sim = Sim(cursor = false).apply { x = 0.2f }.hold(300, finger).move(0.8f, 0.5f, 250, finger).hold(300, finger)
        assertEquals(listOf(AirGesture.SwipeRight), sim.swipes)
        assertEquals(null, sim.engine.cursor)
    }

    @Test
    fun cursorOn_palmStillSwipes() {
        val sim = Sim(cursor = true).apply { x = 0.2f }.hold(300, palm).move(0.8f, 0.5f, 250, palm).hold(300, palm)
        assertEquals(listOf(AirGesture.SwipeRight), sim.swipes)
        assertEquals(emptyList<TouchEvent>(), sim.touches)
    }

    @Test
    fun cursorOn_fingerMovesCursorWithoutSwipingOrTouching() {
        val sim = Sim(cursor = true).apply { x = 0.2f }.hold(300, finger).move(0.8f, 0.5f, 250, finger).hold(300, finger)
        assertEquals(emptyList<AirGesture>(), sim.swipes)
        assertEquals(emptyList<TouchEvent>(), sim.touches)
        val c = sim.engine.cursor!!
        assert(c.x > 0.6f) { "cursor should follow finger to the right, was ${c.x}" }
    }

    @Test
    fun quickPinch_isTap() {
        val sim = Sim(cursor = true).hold(300, finger).hold(130, finger, pinch = true).hold(200, finger)
        assertEquals(listOf("Tap"), kinds(sim.touches))
    }

    @Test
    fun pinchAndMove_isDrag() {
        val sim = Sim(cursor = true).hold(300, finger)
            .hold(100, finger, pinch = true)
            .move(0.5f, 0.25f, 400, finger, pinch = true)
            .hold(200, finger)
        val k = kinds(sim.touches)
        assertEquals("Down", k.first())
        assertEquals("Up", k.last())
        assert(k.count { it == "Move" } >= 5) { "expected moves, got $k" }
        assertEquals(emptyList<AirGesture>(), sim.swipes)
    }

    @Test
    fun pinchHeldStill_becomesLongPress() {
        val sim = Sim(cursor = true).hold(300, finger).hold(800, finger, pinch = true).hold(200, finger)
        val k = kinds(sim.touches)
        assertEquals("Down", k.first())
        assertEquals("Up", k.last())
    }

    @Test
    fun handLostWhileDragging_releasesFinger() {
        val sim = Sim(cursor = true).hold(300, finger).hold(100, finger, pinch = true)
            .move(0.5f, 0.3f, 300, finger, pinch = true).lose()
        assertEquals("Up", kinds(sim.touches).last())
    }

    @Test
    fun fist_doesNothing() {
        val sim = Sim(cursor = true).apply { x = 0.2f }.hold(300, fist).move(0.8f, 0.5f, 250, fist)
        assertEquals(emptyList<AirGesture>(), sim.swipes)
        assertEquals(emptyList<TouchEvent>(), sim.touches)
        assertEquals(null, sim.engine.cursor)
    }

    @Test
    fun oneEuroFilter_smoothsJitterButFollowsMotion() {
        val f = OneEuroFilter()
        var t = 0L
        var maxDev = 0f
        for (i in 0 until 60) { t += 33; val v = f.filter(0.5f + if (i % 2 == 0) 0.01f else -0.01f, t); if (i > 10) maxDev = maxOf(maxDev, kotlin.math.abs(v - 0.5f)) }
        assert(maxDev < 0.006f) { "jitter not damped: $maxDev" }
        var v = 0f
        for (i in 0 until 15) { t += 33; v = f.filter(0.9f, t) }
        assert(v > 0.85f) { "filter lags too much: $v" }
    }
}
