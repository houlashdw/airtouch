package com.airtouch.gesture

import org.junit.Assert.assertEquals
import org.junit.Test

/** Новая схема: жесты по движению — мах любой формой, тык, «V» неподвижно, курсор отдельным режимом. */
class GestureEngineTest {

    private val palm = booleanArrayOf(true, true, true, true)
    private val finger = booleanArrayOf(true, false, false, false)
    private val vSign = booleanArrayOf(true, true, false, false)
    private val three = booleanArrayOf(true, true, true, false)
    private val fist = booleanArrayOf(false, false, false, false)

    private inner class Sim(cursor: Boolean = false, mode: ControlMode = ControlMode.GESTURES) {
        val engine = GestureEngine(EngineConfig(mode = mode, cursorEnabled = cursor, engageMs = 0))
        val swipes = mutableListOf<AirGesture>()
        val touches = mutableListOf<TouchEvent>()
        val actions = mutableListOf<SystemAction>()
        var t = 0L
        var x = 0.5f
        var y = 0.5f
        var size = 0.15f
        var tilt = 1f

        fun collect(out: EngineOutput) {
            out.swipe?.let { swipes += it }
            out.touch?.let { touches += it }
            out.action?.let { actions += it }
        }

        fun move(
            tx: Float, ty: Float, ms: Long, pose: BooleanArray,
            pinch: Boolean = false, toSize: Float = size, toTilt: Float = tilt,
        ) = apply {
            val steps = (ms / 33).coerceAtLeast(1)
            val sx = x; val sy = y; val ss = size; val st = tilt
            for (i in 1..steps) {
                t += 33
                val k = i.toFloat() / steps
                val hand = syntheticHand(
                    sx + (tx - sx) * k, sy + (ty - sy) * k, pose,
                    s = ss + (toSize - ss) * k, pinch = pinch, tiltY = st + (toTilt - st) * k,
                )
                collect(engine.process(HandFrame(t, hand)))
            }
            x = tx; y = ty; size = toSize; tilt = toTilt
        }

        fun hold(ms: Long, pose: BooleanArray, pinch: Boolean = false) = move(x, y, ms, pose, pinch)
        fun resize(toSize: Float, ms: Long, pose: BooleanArray) = move(x, y, ms, pose, toSize = toSize)
        fun missing() = apply { t += 33; collect(engine.onHandMissing(t)) }
        fun lose() = apply { t += 33; collect(engine.onHandLost()) }
    }

    private fun kinds(list: List<TouchEvent>) = list.map { it::class.simpleName }

    // ---------- Махи любой формой ----------

    @Test
    fun swipe_worksWithAnyHandShape() {
        for (pose in listOf(palm, finger, vSign, three, fist)) {
            val sim = Sim().apply { x = 0.2f }.hold(300, pose).move(0.8f, 0.5f, 250, pose).hold(300, pose)
            assertEquals("pose=${pose.toList()}", listOf(AirGesture.SwipeRight), sim.swipes)
            assertEquals(emptyList<SystemAction>(), sim.actions)
        }
    }

    @Test
    fun fastFlickOutOfFrame_counts() {
        val sim = Sim().hold(300, palm).move(0.65f, 0.5f, 66, palm).missing()
        assertEquals(listOf(AirGesture.SwipeRight), sim.swipes)
    }

    @Test
    fun droppingHandOutOfFrame_isNotSwipeDown() {
        val sim = Sim().hold(300, palm).move(0.5f, 0.6f, 66, palm).missing().lose()
        assertEquals(emptyList<AirGesture>(), sim.swipes)
    }

    // ---------- Тык ----------

    @Test
    fun quickPoke_goesHome() {
        val sim = Sim().hold(400, palm).resize(0.20f, 165, palm).resize(0.15f, 200, palm).hold(300, palm)
        assertEquals(listOf(SystemAction.HOME), sim.actions)
        assertEquals(emptyList<AirGesture>(), sim.swipes)
    }

    @Test
    fun poke_worksWithFistToo() {
        val sim = Sim().hold(400, fist).resize(0.20f, 165, fist).resize(0.15f, 200, fist).hold(300, fist)
        assertEquals(listOf(SystemAction.HOME), sim.actions)
    }

    @Test
    fun pokeAndHold_opensRecents_notHome() {
        val sim = Sim().hold(400, palm).resize(0.20f, 165, palm).hold(900, palm).resize(0.15f, 200, palm).hold(300, palm)
        assertEquals(listOf(SystemAction.RECENTS), sim.actions)
    }

    @Test
    fun slowlyBringingHandCloser_isNotPoke() {
        val sim = Sim().hold(400, palm).resize(0.21f, 1500, palm).hold(800, palm)
        assertEquals(emptyList<SystemAction>(), sim.actions)
    }

    @Test
    fun tiltingWrist_isNotPoke() {
        // Кисть наклонили и вернули: длина ладони менялась, ширина — нет.
        val sim = Sim().hold(400, palm).move(0.5f, 0.5f, 165, palm, toTilt = 0.75f).move(0.5f, 0.5f, 165, palm, toTilt = 1f).hold(1000, palm)
        assertEquals(emptyList<SystemAction>(), sim.actions)
    }

    @Test
    fun handAppearingCloseToCamera_isNotPoke() {
        val sim = Sim().apply { size = 0.22f }.hold(600, palm)
        assertEquals(emptyList<SystemAction>(), sim.actions)
    }

    // ---------- «V» = назад ----------

    @Test
    fun vHeldStill_goesBack_once() {
        val sim = Sim().hold(300, palm).hold(1200, vSign).hold(300, palm)
        assertEquals(listOf(SystemAction.BACK), sim.actions)
    }

    @Test
    fun vMoving_isSwipe_notBack() {
        val sim = Sim().apply { x = 0.2f }.hold(300, vSign).move(0.8f, 0.5f, 250, vSign).hold(200, palm)
        assertEquals(listOf(AirGesture.SwipeRight), sim.swipes)
        assertEquals(emptyList<SystemAction>(), sim.actions)
    }

    @Test
    fun quickV_doesNothing() {
        val sim = Sim().hold(300, palm).hold(250, vSign).hold(300, palm)
        assertEquals(emptyList<SystemAction>(), sim.actions)
    }

    @Test
    fun vFlicker_doesNotBreakHold() {
        val sim = Sim().hold(300, palm).hold(250, vSign).hold(33, palm).hold(300, vSign).hold(200, palm)
        assertEquals(listOf(SystemAction.BACK), sim.actions)
    }

    // ---------- Курсор (отдельный режим) ----------

    @Test
    fun cursorOff_noCursorEver() {
        val sim = Sim(cursor = false).hold(500, finger).hold(200, finger, pinch = true).hold(200, finger)
        assertEquals(null, sim.engine.cursor)
        assertEquals(emptyList<TouchEvent>(), sim.touches)
    }

    @Test
    fun cursorOn_fingerMovesCursor_palmStillSwipes() {
        val sim = Sim(cursor = true).apply { x = 0.2f }.hold(300, finger).move(0.8f, 0.5f, 250, finger).hold(300, finger)
        assertEquals(emptyList<AirGesture>(), sim.swipes)
        assert(sim.engine.cursor!!.x > 0.6f)
        val sim2 = Sim(cursor = true).apply { x = 0.2f }.hold(300, palm).move(0.8f, 0.5f, 250, palm).hold(300, palm)
        assertEquals(listOf(AirGesture.SwipeRight), sim2.swipes)
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
    }

    @Test
    fun handLostWhileDragging_releasesFinger() {
        val sim = Sim(cursor = true).hold(300, finger).hold(100, finger, pinch = true)
            .move(0.5f, 0.3f, 300, finger, pinch = true).lose()
        assertEquals("Up", kinds(sim.touches).last())
    }

    // ---------- Режим «щипок как палец» ----------

    private fun pinchSim() = Sim(mode = ControlMode.PINCH)

    @Test
    fun pinchMode_wavingAndPokingDoNothing() {
        val sim = pinchSim().apply { x = 0.2f }
            .hold(300, palm).move(0.8f, 0.5f, 250, palm).move(0.2f, 0.5f, 250, palm)  // махи
            .resize(0.21f, 165, palm).hold(900, palm).resize(0.15f, 200, palm)        // тык и держать
            .hold(1200, vSign).hold(1200, fist)                                       // «V», кулак
        assertEquals(emptyList<AirGesture>(), sim.swipes)
        assertEquals(emptyList<SystemAction>(), sim.actions)
        assertEquals(emptyList<TouchEvent>(), sim.touches)
        assert(sim.engine.cursor != null) { "рука двигает курсор" }
    }

    @Test
    fun pinchMode_anyHandPoseMovesCursor() {
        for (pose in listOf(palm, finger, fist, vSign)) {
            val sim = pinchSim().apply { x = 0.3f }.hold(200, pose).move(0.7f, 0.5f, 300, pose).hold(300, pose)
            assert(sim.engine.cursor!!.x > 0.6f) { "pose=${pose.toList()} cursor=${sim.engine.cursor}" }
        }
    }

    @Test
    fun pinchMode_tapAndScroll() {
        val sim = pinchSim().hold(300, finger).hold(130, finger, pinch = true).hold(200, finger)
            .hold(100, finger, pinch = true).move(0.5f, 0.25f, 300, finger, pinch = true).hold(200, finger)
        val k = kinds(sim.touches)
        assertEquals("Tap", k.first())
        assertEquals("Down", k[1])
        assertEquals("Up", k.last())
    }

    @Test
    fun pinchMode_pauseBeforeMoving_isStillScroll_notLongPress() {
        // Ошибка из жизни: щипок, пауза ~0.45 с, потом повёл. Палец не должен лечь до начала движения.
        val sim = pinchSim().hold(300, finger).hold(450, finger, pinch = true)
        assertEquals(emptyList<TouchEvent>(), sim.touches) // пока стоим — ничего не отправлено
        sim.move(0.5f, 0.2f, 250, finger, pinch = true).hold(200, finger)
        val k = kinds(sim.touches)
        assertEquals("Down", k.first())
        assert(k.count { it == "Move" } >= 3) { "$k" }
        assertEquals("Up", k.last())
    }

    // ---------- «Я просто пользуюсь телефоном» ----------

    @Test
    fun handCloseToCamera_isIgnored() {
        // Палец/рука у экрана: в кадре огромная — не жест.
        val e = GestureEngine(EngineConfig(mode = ControlMode.PINCH))
        var t = 0L
        val outs = (0 until 60).map { i ->
            t += 33
            e.process(HandFrame(t, syntheticHand(0.5f, 0.55f, finger, s = 0.45f, pinch = i in 30..34)))
        }
        assert(outs.all { it.touch == null && it.action == null }) { "near hand must do nothing" }
        assertEquals(null, e.cursor)
    }

    @Test
    fun handCutByFrameEdge_isIgnored() {
        val e = GestureEngine(EngineConfig(mode = ControlMode.PINCH))
        var t = 0L
        repeat(60) { i -> t += 33; e.process(HandFrame(t, syntheticHand(0.5f, 0.97f, finger, pinch = i in 30..34))) }
        assertEquals(null, e.cursor)
    }

    @Test
    fun handMustSettleBeforeControlling() {
        val e = GestureEngine(EngineConfig(mode = ControlMode.PINCH))
        var t = 0L
        val early = (0 until 10).map { i -> t += 33; e.process(HandFrame(t, syntheticHand(0.5f, 0.5f, finger, pinch = i in 4..7))) }
        assert(early.all { it.touch == null }) { "first 0.5 s nothing happens" }
        assertEquals(null, e.cursor)
        repeat(20) { t += 33; e.process(HandFrame(t, syntheticHand(0.5f, 0.5f, finger))) }
        assert(e.cursor != null) { "after settling cursor appears" }
    }

    @Test
    fun touchingScreen_suppressesAirInput() {
        val e = GestureEngine(EngineConfig(mode = ControlMode.PINCH, engageMs = 0))
        var t = 0L
        repeat(10) { t += 33; e.process(HandFrame(t, syntheticHand(0.5f, 0.5f, finger))) }
        e.suppressedUntil = t + 2000
        val outs = (0 until 20).map { i -> t += 33; e.process(HandFrame(t, syntheticHand(0.5f, 0.5f, finger, pinch = i in 5..9))) }
        assert(outs.all { it.touch == null })
        assertEquals(null, e.cursor)
    }

    @Test
    fun pinchMode_relaxedHandWithThumbOnIndex_doesNothing() {
        // Из видео: рука просто висит, большой палец лежит у указательного — это НЕ щипок.
        val sim = pinchSim().hold(3000, finger, pinch = true).move(0.3f, 0.3f, 600, finger, pinch = true).hold(1500, finger, pinch = true)
        assertEquals(emptyList<TouchEvent>(), sim.touches)
    }

    @Test
    fun pinchMode_holdingPinchOneSecond_isNotLongPressYet() {
        val sim = pinchSim().hold(300, finger).hold(1000, finger, pinch = true).hold(200, finger)
        assertEquals(listOf("Tap"), kinds(sim.touches))
    }

    @Test
    fun pinchMode_slowTapStillTaps() {
        val sim = pinchSim().hold(300, finger).hold(450, finger, pinch = true).hold(200, finger)
        assertEquals(listOf("Tap"), kinds(sim.touches))
    }

    @Test
    fun pinchMode_lowerHandToBottom_showsDock_andTapRunsAction() {
        // Опустили руку в самый низ (курсор упирается в край), подождали — панель выехала.
        val sim = pinchSim().apply { x = 0.2f; y = 0.5f }.hold(200, finger).move(0.2f, 0.86f, 300, finger)
        var dock = false
        repeat(15) { sim.t += 33; dock = sim.engine.process(HandFrame(sim.t, syntheticHand(0.2f, 0.86f, finger))).dock }
        assert(dock) { "dock should be visible" }
        // Щипок на левой кнопке — «назад», без тапа по приложению.
        sim.hold(130, finger, pinch = true).hold(200, finger)
        assertEquals(listOf(SystemAction.BACK), sim.actions)
        assertEquals(emptyList<TouchEvent>(), sim.touches)
    }

    @Test
    fun pinchMode_draggingDownDoesNotOpenDock() {
        val sim = pinchSim().hold(300, finger).hold(100, finger, pinch = true)
            .move(0.5f, 0.97f, 300, finger, pinch = true).hold(600, finger, pinch = true).hold(200, finger)
        assertEquals(emptyList<SystemAction>(), sim.actions)
        assertEquals("Up", kinds(sim.touches).last())
    }

    // ---------- Воздушная клавиатура ----------

    @Test
    fun keyboardMode_noSwipesNoPoke_butPinchTypesAndVCloses() {
        val sim = Sim().apply { engine.keyboardMode = true; x = 0.2f }
            .hold(300, palm).move(0.8f, 0.5f, 250, palm).hold(300, palm)        // мах — не листает
            .resize(0.20f, 165, palm).resize(0.15f, 200, palm).hold(300, palm)  // тык — не «домой»
        assertEquals(emptyList<AirGesture>(), sim.swipes)
        assertEquals(emptyList<SystemAction>(), sim.actions)
        sim.hold(200, finger).hold(130, finger, pinch = true).hold(200, finger) // щипок — нажатие
        assertEquals(listOf("Tap"), kinds(sim.touches))
        assert(sim.engine.cursor != null)
        sim.hold(800, vSign)                                                    // ✌️ — закрыть
        assertEquals(listOf(SystemAction.BACK), sim.actions)
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
