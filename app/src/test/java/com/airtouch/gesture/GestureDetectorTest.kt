package com.airtouch.gesture

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/**
 * Юнит-тесты логики свайпа на синтетической руке (JVM, без устройства).
 * Запуск: ./gradlew :app:testDebugUnitTest
 */
class GestureDetectorTest {

    // ---------- Синтетическая рука ----------

    /** Ладонь пальцами вверх. [extended] — [указательный, средний, безымянный, мизинец]. */
    private fun hand(
        cx: Float,
        cy: Float,
        extended: BooleanArray = booleanArrayOf(true, true, true, true),
        s: Float = 0.15f,
    ): List<Landmark> {
        val pts = MutableList(Lm.COUNT) { Landmark(cx, cy) }
        pts[Lm.WRIST] = Landmark(cx, cy + 0.5f * s)
        // большой палец
        for (i in 1..4) pts[i] = Landmark(cx - 0.3f * s - 0.12f * s * i, cy + 0.35f * s - 0.1f * s * i)
        val mcpIds = intArrayOf(Lm.INDEX_MCP, Lm.MIDDLE_MCP, Lm.RING_MCP, Lm.PINKY_MCP)
        val xOff = floatArrayOf(-0.3f, -0.1f, 0.1f, 0.3f)
        for (f in 0 until 4) {
            val x = cx + xOff[f] * s
            val base = mcpIds[f]
            pts[base] = Landmark(x, cy - 0.1f * s)
            if (extended[f]) {
                pts[base + 1] = Landmark(x, cy - 0.4f * s)
                pts[base + 2] = Landmark(x, cy - 0.6f * s)
                pts[base + 3] = Landmark(x, cy - 0.8f * s)
            } else { // палец согнут: кончик ближе к запястью, чем PIP
                pts[base + 1] = Landmark(x, cy - 0.35f * s)
                pts[base + 2] = Landmark(x, cy - 0.2f * s)
                pts[base + 3] = Landmark(x, cy - 0.05f * s)
            }
        }
        return pts
    }

    private val open = booleanArrayOf(true, true, true, true)
    private val fist = booleanArrayOf(false, false, false, false)

    /** Сценарий движения руки при 30 FPS. */
    private inner class Sim(config: SwipeConfig = SwipeConfig()) {
        val detector = GestureDetector(config)
        val events = mutableListOf<AirGesture>()
        var t = 0L
        var x = 0.5f
        var y = 0.5f
        var pose = open

        fun at(nx: Float, ny: Float) = apply { x = nx; y = ny }

        fun hold(ms: Long) = move(x, y, ms)

        fun move(tx: Float, ty: Float, ms: Long, jitter: Float = 0f, seed: Int = 1, eased: Boolean = false) = apply {
            val rnd = Random(seed)
            val steps = (ms / FRAME_MS).coerceAtLeast(1)
            val sx = x
            val sy = y
            for (i in 1..steps) {
                t += FRAME_MS
                val lin = i.toFloat() / steps
                // eased = как живая рука: разгон и торможение (smoothstep)
                val k = if (eased) lin * lin * (3 - 2 * lin) else lin
                val jx = if (jitter > 0) (rnd.nextFloat() * 2 - 1) * jitter else 0f
                val jy = if (jitter > 0) (rnd.nextFloat() * 2 - 1) * jitter else 0f
                feed(sx + (tx - sx) * k + jx, sy + (ty - sy) * k + jy)
            }
            x = tx
            y = ty
        }

        fun lose() = apply { t += FRAME_MS; detector.onHandLost() }

        private fun feed(px: Float, py: Float) {
            detector.process(HandFrame(t, hand(px, py, pose)))?.let { events += it }
        }
    }

    // ---------- Свайпы влево/вправо (MVP) ----------

    @Test
    fun swipeRight_firesExactlyOnce() {
        val sim = Sim().at(0.2f, 0.5f).hold(300).move(0.8f, 0.5f, 250).hold(300)
        assertEquals(listOf(AirGesture.SwipeRight), sim.events)
    }

    @Test
    fun swipeLeft_firesExactlyOnce() {
        val sim = Sim().at(0.8f, 0.5f).hold(300).move(0.2f, 0.5f, 250).hold(300)
        assertEquals(listOf(AirGesture.SwipeLeft), sim.events)
    }

    @Test
    fun pauseBeforeSwipe_doesNotDiluteVelocity() {
        // Долгое удержание + быстрый, но не очень длинный мах.
        val sim = Sim().at(0.3f, 0.5f).hold(1000).move(0.7f, 0.5f, 200).hold(300)
        assertEquals(listOf(AirGesture.SwipeRight), sim.events)
    }

    @Test
    fun slowDrift_isNotASwipe() {
        val sim = Sim().at(0.1f, 0.5f).hold(200).move(0.9f, 0.5f, 3000).hold(200)
        assertEquals(emptyList<AirGesture>(), sim.events)
    }

    @Test
    fun slowDrift_isNotASwipe_evenWithoutDeadZone() {
        val sim = Sim(SwipeConfig(deadZone = 0f)).at(0.1f, 0.5f).move(0.9f, 0.5f, 3000)
        assertEquals(emptyList<AirGesture>(), sim.events)
    }

    @Test
    fun handTremor_isIgnored() {
        val sim = Sim().at(0.5f, 0.5f).move(0.5f, 0.5f, 3000, jitter = 0.012f, seed = 42)
        assertEquals(emptyList<AirGesture>(), sim.events)
    }

    @Test
    fun fist_doesNotSwipe() {
        val sim = Sim().apply { pose = fist }.at(0.2f, 0.5f).hold(200).move(0.8f, 0.5f, 250)
        assertEquals(emptyList<AirGesture>(), sim.events)
    }

    @Test
    fun returnStroke_isSuppressed() {
        // Мах вправо и сразу возврат руки назад — должен быть ровно один свайп.
        val sim = Sim().at(0.2f, 0.5f).hold(300)
            .move(0.8f, 0.5f, 250)
            .move(0.2f, 0.5f, 300)
            .hold(500)
        assertEquals(listOf(AirGesture.SwipeRight), sim.events)
    }

    @Test
    fun returnStroke_isSuppressed_withNaturalTurnaround() {
        // Реалистично: рука тормозит, на мгновение замирает и возвращается.
        for (pauseMs in longArrayOf(0, 33, 66, 99)) {
            val sim = Sim().at(0.2f, 0.5f).hold(300)
                .move(0.8f, 0.5f, 250, eased = true)
                .hold(pauseMs)
                .move(0.2f, 0.5f, 350, eased = true)
                .hold(500)
            assertEquals("pause=$pauseMs", listOf(AirGesture.SwipeRight), sim.events)
        }
    }

    @Test
    fun secondSwipe_worksAfterHandRests() {
        val sim = Sim().at(0.2f, 0.5f).hold(300)
            .move(0.8f, 0.5f, 250).hold(600)
            .move(0.2f, 0.5f, 250).hold(300)
        assertEquals(listOf(AirGesture.SwipeRight, AirGesture.SwipeLeft), sim.events)
    }

    @Test
    fun handLost_resetsCooldown() {
        val sim = Sim().at(0.2f, 0.5f).hold(200).move(0.8f, 0.5f, 250)
            .lose()
            .move(0.2f, 0.5f, 250)
        assertEquals(listOf(AirGesture.SwipeRight, AirGesture.SwipeLeft), sim.events)
    }

    @Test
    fun diagonalMove_isIgnored() {
        val sim = Sim().at(0.2f, 0.25f).hold(200).move(0.6f, 0.65f, 250).hold(200)
        assertEquals(emptyList<AirGesture>(), sim.events)
    }

    // ---------- Настройки ----------

    @Test
    fun sensitivity_controlsRequiredDistance() {
        fun shortSwipe(cfg: SwipeConfig) =
            Sim(cfg).at(0.4f, 0.5f).hold(200).move(0.6f, 0.5f, 150).hold(200).events

        assertEquals(emptyList<AirGesture>(), shortSwipe(SwipeConfig(sensitivity = 1f)))
        assertEquals(listOf(AirGesture.SwipeRight), shortSwipe(SwipeConfig(sensitivity = 2f)))
    }

    @Test
    fun topThirdZone_ignoresHandLowInFrame() {
        val cfg = SwipeConfig(activationZone = ActivationZone.TOP_THIRD)
        val low = Sim(cfg).at(0.2f, 0.7f).hold(200).move(0.8f, 0.7f, 250).events
        val high = Sim(cfg).at(0.2f, 0.2f).hold(200).move(0.8f, 0.2f, 250).events
        assertEquals(emptyList<AirGesture>(), low)
        assertEquals(listOf(AirGesture.SwipeRight), high)
    }

    @Test
    fun verticalSwipes_canBeDisabled() {
        val on = Sim().at(0.5f, 0.8f).hold(200).move(0.5f, 0.3f, 250).events
        val off = Sim(SwipeConfig(enableVertical = false)).at(0.5f, 0.8f).hold(200).move(0.5f, 0.3f, 250).events
        assertEquals(listOf(AirGesture.SwipeUp), on)
        assertEquals(emptyList<AirGesture>(), off)
    }

    // ---------- Классификатор позы ----------

    @Test
    fun poseClassifier_recognisesBasicPoses() {
        fun pose(e: BooleanArray) = HandPoseClassifier.classify(HandFrame(0, hand(0.5f, 0.5f, e)))
        assertEquals(HandPose.OPEN_PALM, pose(open))
        assertEquals(HandPose.FIST, pose(fist))
        assertEquals(HandPose.POINTING, pose(booleanArrayOf(true, false, false, false)))
        assertEquals(HandPose.V_SIGN, pose(booleanArrayOf(true, true, false, false)))
    }

    private companion object {
        const val FRAME_MS = 33L
    }
}
