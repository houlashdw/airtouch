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
    fun fistPush_isBack_notHome() {
        // Кулаком к экрану — это «схватил и толкнул» → назад; «домой» тыкают открытой ладонью.
        val sim = Sim().hold(400, fist).resize(0.20f, 165, fist).resize(0.15f, 200, fist).hold(300, fist)
        assertEquals(listOf(SystemAction.BACK), sim.actions)
    }

    @Test
    fun fistPull_isRecents() {
        val sim = Sim().hold(400, fist).resize(0.10f, 200, fist).hold(200, fist)
        assertEquals(listOf(SystemAction.RECENTS), sim.actions)
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

    // ---------- Курсор-тачпад ----------

    @Test
    fun trackpadCursor_handPositionInFrameDoesNotMatter() {
        // Одинаковое движение руки в разных местах кадра двигает курсор одинаково.
        fun shift(startX: Float, startY: Float): Float {
            val sim = pinchSim().apply { x = startX; y = startY }.hold(300, finger)
            val before = sim.engine.cursor!!.x
            sim.move(startX + 0.06f, startY, 300, finger).hold(200, finger)
            return sim.engine.cursor!!.x - before
        }
        val a = shift(0.3f, 0.35f)
        val b = shift(0.6f, 0.65f)
        assert(a > 0.05f && kotlin.math.abs(a - b) < 0.03f) { "a=$a b=$b" }
    }

    @Test
    fun trackpadCursor_sameMotionFarOrNear_sameShift() {
        fun shift(size: Float): Float {
            val sim = pinchSim().apply { this.size = size }.hold(300, finger)
            val before = sim.engine.cursor!!.x
            sim.move(0.5f + size * 0.5f, 0.5f, 300, finger).hold(200, finger) // полладони вправо
            return sim.engine.cursor!!.x - before
        }
        val near = shift(0.18f)
        val far = shift(0.07f) // рука далеко — в кадре меньше
        assert(near > 0.05f && kotlin.math.abs(near - far) < 0.04f) { "near=$near far=$far" }
    }

    @Test
    fun trackpadCursor_staysWhenHandLeavesAndReturnsElsewhere() {
        val sim = pinchSim().hold(300, finger).move(0.6f, 0.5f, 300, finger).hold(200, finger)
        val kept = sim.engine.cursor!!.x
        sim.lose()
        sim.x = 0.25f; sim.y = 0.7f // вернули руку совсем в другом месте кадра
        sim.hold(300, finger)
        assert(kotlin.math.abs(sim.engine.cursor!!.x - kept) < 0.02f) { "cursor jumped: $kept -> ${sim.engine.cursor}" }
    }

    @Test
    fun cursorRestingNearBottom_doesNotOpenDock() {
        // Листали и курсор просто оказался внизу — панель не должна выезжать.
        val sim = pinchSim().hold(200, finger)
        var y = 0.5f
        var dock = false
        while (sim.engine.cursor!!.y < 0.97f) { sim.t += 33; y += 0.004f; dock = dock || sim.engine.process(HandFrame(sim.t, syntheticHand(0.5f, y, finger))).dock }
        repeat(30) { sim.t += 33; dock = dock || sim.engine.process(HandFrame(sim.t, syntheticHand(0.5f, y, finger))).dock }
        assert(!dock) { "cursor just rests at the bottom" }
    }

    @Test
    fun flickAtScreenEdge_isStillAFlick_notATap() {
        // Курсор упёрся в верх экрана, а рука делает мах вверх щипком — раньше это было «нажатие» (пауза видео).
        val sim = pinchSim().hold(200, finger).move(0.5f, 0.2f, 400, finger).hold(300, finger) // курсор у верхнего края
        assert(sim.engine.cursor!!.y < 0.02f)
        sim.hold(100, finger, pinch = true).move(0.5f, 0.1f, 200, finger, pinch = true).hold(200, finger)
        val k = kinds(sim.touches)
        assertEquals(listOf("Down", "Up"), k.filter { it != "Move" })
        val up = sim.touches.last() as TouchEvent.Up
        assert(up.y < -0.05f) { "virtual end must go past the edge: $up" }
    }

    @Test
    fun trackpadCursor_isSlowerOverSystemKeyboard() {
        fun shift(precisionTop: Float): Float {
            val e = GestureEngine(EngineConfig(mode = ControlMode.PINCH, engageMs = 0, cursor = CursorConfig(precisionTop = precisionTop)))
            var t = 0L
            var x = 0.4f
            repeat(10) { t += 33; e.process(HandFrame(t, syntheticHand(x, 0.5f, finger))) }
            val before = e.cursor!!.x
            repeat(9) { t += 33; x += 0.006f; e.process(HandFrame(t, syntheticHand(x, 0.5f, finger))) }
            repeat(6) { t += 33; e.process(HandFrame(t, syntheticHand(x, 0.5f, finger))) }
            return e.cursor!!.x - before
        }
        val normal = shift(2f)
        val overKeyboard = shift(0f) // вся область — «над клавиатурой»
        assert(overKeyboard < normal * 0.7f && overKeyboard > 0f) { "normal=$normal kb=$overKeyboard" }
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
    fun pinchMode_pinchWhileHandMoving_doesNotTap() {
        // Курсор ехал, и пальцы случайно сошлись — нажатия быть не должно.
        val sim = pinchSim().apply { x = 0.45f }.hold(300, finger).move(0.5f, 0.5f, 300, finger)
            .move(0.51f, 0.5f, 130, finger, pinch = true).hold(300, finger)
        assertEquals(emptyList<String?>(), kinds(sim.touches).filter { it == "Tap" })
    }

    @Test
    fun pinchMode_slowTapStillTaps() {
        val sim = pinchSim().hold(300, finger).hold(450, finger, pinch = true).hold(200, finger)
        assertEquals(listOf("Tap"), kinds(sim.touches))
    }

    @Test
    fun pinchMode_lowerHandToBottom_showsDock_andTapRunsAction() {
        // Опустили руку в самый низ (курсор упирается в край), подождали — панель выехала.
        // Курсор-тачпад: повели руку влево (курсор к левому краю), потом вниз.
        val sim = pinchSim().hold(200, finger).move(0.2f, 0.5f, 300, finger).move(0.2f, 0.86f, 300, finger)
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
