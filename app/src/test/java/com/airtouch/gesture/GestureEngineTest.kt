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
        val actions = mutableListOf<SystemAction>()
        val zooms = mutableListOf<ZoomEvent>()
        var t = 0L
        var x = 0.5f
        var y = 0.5f
        var size = 0.15f

        fun collect(out: EngineOutput) {
            out.swipe?.let { swipes += it }
            out.touch?.let { touches += it }
            out.action?.let { actions += it }
            out.zoom?.let { zooms += it }
        }

        fun move(
            tx: Float, ty: Float, ms: Long, pose: BooleanArray,
            pinch: Boolean = false, toSize: Float = size,
        ) = apply {
            val steps = (ms / 33).coerceAtLeast(1)
            val sx = x
            val sy = y
            val ss = size
            for (i in 1..steps) {
                t += 33
                val k = i.toFloat() / steps
                val hand = syntheticHand(sx + (tx - sx) * k, sy + (ty - sy) * k, pose, s = ss + (toSize - ss) * k, pinch = pinch)
                collect(engine.process(HandFrame(t, hand)))
            }
            x = tx
            y = ty
            size = toSize
        }

        fun hold(ms: Long, pose: BooleanArray, pinch: Boolean = false) = move(x, y, ms, pose, pinch)

        fun missing() = apply { t += 33; collect(engine.onHandMissing(t)) }

        fun lose() = apply { t += 33; collect(engine.onHandLost()) }
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

    // ---------- Системные жесты ----------

    private val okSign = palm // + pinch = «нолик»: щипок и три пальца вверх
    private val vSign = booleanArrayOf(true, true, false, false)

    @Test
    fun okHeld_goesHome_once() {
        val sim = Sim(cursor = true).hold(300, palm).hold(1200, okSign, pinch = true).hold(300, palm)
        assertEquals(listOf(SystemAction.HOME), sim.actions)
        assertEquals(emptyList<AirGesture>(), sim.swipes)
        assertEquals(emptyList<TouchEvent>(), sim.touches)
        assertEquals(emptyList<ZoomEvent>(), sim.zooms)
    }

    @Test
    fun okPulledBack_zoomsOut_withoutGoingHome() {
        val sim = Sim(cursor = true).hold(300, palm)
            .hold(100, okSign, pinch = true)
            .move(0.5f, 0.5f, 400, okSign, pinch = true, toSize = 0.10f)
            .hold(300, okSign, pinch = true)
            .hold(200, palm)
        assertEquals(ZoomEvent.Start, sim.zooms.first())
        assertEquals(ZoomEvent.End, sim.zooms.last())
        val last = sim.zooms.filterIsInstance<ZoomEvent.Update>().last().factor
        assert(last < 0.8f) { "expected zoom out, factor=$last" }
        assertEquals(emptyList<SystemAction>(), sim.actions)
    }

    @Test
    fun okPushedForward_zoomsIn() {
        val sim = Sim(cursor = true).hold(300, palm)
            .hold(100, okSign, pinch = true)
            .move(0.5f, 0.5f, 400, okSign, pinch = true, toSize = 0.21f)
            .hold(200, palm)
        val last = sim.zooms.filterIsInstance<ZoomEvent.Update>().last().factor
        assert(last > 1.3f) { "expected zoom in, factor=$last" }
    }

    @Test
    fun vHeld_goesBack_once() {
        val sim = Sim(cursor = true).hold(300, palm).hold(1000, vSign).hold(300, palm)
        assertEquals(listOf(SystemAction.BACK), sim.actions)
    }

    @Test
    fun quickV_doesNothing() {
        val sim = Sim(cursor = true).hold(300, palm).hold(250, vSign).hold(300, palm)
        assertEquals(emptyList<SystemAction>(), sim.actions)
    }

    @Test
    fun fistHeld_opensRecents() {
        val sim = Sim(cursor = true).hold(300, palm).hold(1200, fist).hold(300, palm)
        assertEquals(listOf(SystemAction.RECENTS), sim.actions)
    }

    @Test
    fun posesFlicker_doesNotBreakHold() {
        // «V» держим, но на один кадр классификатор увидел ладонь — удержание не сбрасывается.
        val sim = Sim(cursor = true).hold(300, palm).hold(250, vSign).hold(33, palm).hold(250, vSign).hold(200, palm)
        assertEquals(listOf(SystemAction.BACK), sim.actions)
    }

    // ---------- Быстрые махи ----------

    @Test
    fun fastFlickOutOfFrame_counts() {
        // Резкий мах: камера успела увидеть 2 кадра, потом рука смазалась/вылетела.
        val sim = Sim(cursor = true).hold(300, palm).move(0.65f, 0.5f, 66, palm).missing()
        assertEquals(listOf(AirGesture.SwipeRight), sim.swipes)
    }

    @Test
    fun droppingHandOutOfFrame_isNotSwipeDown() {
        // Короткое резкое опускание и рука пропала: «вылет» засчитываем только по горизонтали.
        val sim = Sim(cursor = true).hold(300, palm).move(0.5f, 0.6f, 66, palm).missing().lose()
        assertEquals(emptyList<AirGesture>(), sim.swipes)
    }

    @Test
    fun fastFlick_twoFramesOnly_stillCounts() {
        // Очень быстрый, но широкий мах: всего 3 кадра.
        val sim = Sim(cursor = true).apply { x = 0.3f }.hold(300, palm).move(0.7f, 0.5f, 100, palm).hold(300, palm)
        assertEquals(listOf(AirGesture.SwipeRight), sim.swipes)
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
