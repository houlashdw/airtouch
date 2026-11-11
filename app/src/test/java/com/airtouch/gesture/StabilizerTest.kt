package com.airtouch.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sin

class StabilizerTest {

    // ---------- ShakeCompensator ----------

    @Test
    fun learnsScaleAndSign_fromShakeOnly() {
        val c = ShakeCompensator()
        val k = -0.9f // настоящий коэффициент: знак отрицательный, модуль ниже prior
        var residual = 0f
        var n = 0
        for (i in 0 until 90) {
            val rot = 0.02f * sin(i * 0.7f)
            val d = k * rot // рука неподвижна, картинку двигает только камера
            val (cx, _) = c.compensate(d, 0f, rot, 0f)
            if (i >= 45) { residual += abs(cx); n++ }
        }
        assertTrue("k must be learned, got ${c.kX}", abs(c.kX - k) < 0.12f)
        assertTrue("shake must be removed, residual ${residual / n}", residual / n < 0.002f)
    }

    @Test
    fun deliberateHandMotion_withoutRotation_isUntouched() {
        val c = ShakeCompensator()
        val (x, y) = c.compensate(0.05f, -0.03f, 0f, 0f)
        assertEquals(0.05f, x, 1e-6f)
        assertEquals(-0.03f, y, 1e-6f)
        assertEquals(0f, c.kX, 0f)
    }

    @Test
    fun handMotionSurvivesCompensation_whileShaking() {
        val c = ShakeCompensator()
        val k = 1.1f
        // Сначала учимся на тряске…
        for (i in 0 until 60) {
            val rot = 0.02f * sin(i * 0.9f)
            c.compensate(k * rot, 0f, rot, 0f)
        }
        // …потом рука едет вправо с постоянной скоростью на фоне той же тряски.
        var sum = 0f
        for (i in 0 until 60) {
            val rot = 0.02f * sin(i * 0.9f)
            val (cx, _) = c.compensate(0.01f + k * rot, 0f, rot, 0f)
            sum += cx
        }
        assertEquals("net hand motion must be kept", 0.6f, sum, 0.08f)
    }

    // ---------- WalkDetector ----------

    @Test
    fun walking_isUnsteady_standingIsNot() {
        val w = WalkDetector()
        var t = 0L
        // Стоим: |a| ≈ 9.81 с небольшим шумом.
        for (i in 0 until 120) { t += 10; w.add(t, 0.1f * sin(i * 0.3f), 0f, 9.81f) }
        assertFalse("standing must be steady, std=${w.std}", w.unsteady)
        // Идём: шаг ~2 Гц с размахом ±3 м/с².
        for (i in 0 until 150) { t += 10; w.add(t, 0f, 0f, 9.81f + 3f * sin(i * 0.126f)) }
        assertTrue("walking must be unsteady, std=${w.std}", w.unsteady)
        // Остановились — гистерезис, но в итоге успокаивается.
        for (i in 0 until 200) { t += 10; w.add(t, 0f, 0f, 9.81f) }
        assertFalse("after stopping must be steady again", w.unsteady)
    }

    // ---------- GyroAccumulator ----------

    @Test
    fun gyro_integratesAndMapsAxes() {
        val g = GyroAccumulator()
        g.add(0L, 0f, 0f, 0f)
        for (i in 1..10) g.add(i * 10_000_000L, 1f, 2f, 3f) // 100 мс по 1/2/3 рад/с
        val r = g.take()
        assertEquals(0.1f, r[0], 1e-3f)
        assertEquals(0.2f, r[1], 1e-3f)
        assertEquals(0.3f, r[2], 1e-3f)
        assertEquals(0f, g.take()[0], 0f)
        // Портрет: горизонталь кадра = ось y устройства; альбом — ось x.
        assertEquals(0.2f to 0.1f, GyroAccumulator.toFrameAxes(floatArrayOf(0.1f, 0.2f, 0.3f), 0))
        assertEquals(0.1f to 0.2f, GyroAccumulator.toFrameAxes(floatArrayOf(0.1f, 0.2f, 0.3f), 1))
    }

    // ---------- PinchCursor с тряской ----------

    private fun shakyRun(compensate: Boolean, unsteady: Boolean): Float {
        val cursor = PinchCursor(CursorConfig(shakeCompensation = compensate))
        val k = 1.0f
        var t = 0L
        var travel = 0f
        var prev: Cursor? = null
        var prevAngle = 0f
        for (i in 0 until 240) {
            t += 33
            val angle = 0.025f * sin(i * 0.8f) // тряска ~4 Гц: угол камеры от среднего
            val rot = angle - prevAngle // гироскоп даёт поворот ЗА КАДР
            prevAngle = angle
            val shift = k * angle // кадр 480×640: короткая сторона — x, поэтому сдвиг в долях x
            val hand = syntheticHand(0.5f + shift, 0.5f, s = 0.15f)
            cursor.process(HandFrame(t, hand, 480, 640, motion = FrameMotion(rotX = rot, unsteady = unsteady)))
            val c = cursor.cursor!!
            if (prev != null && i > 60) travel += hypot(c.x - prev.x, c.y - prev.y)
            prev = c
        }
        return travel
    }

    @Test
    fun cursorJitter_isMuchSmaller_withCompensation() {
        val raw = shakyRun(compensate = false, unsteady = false)
        val fixed = shakyRun(compensate = true, unsteady = false)
        assertTrue("without compensation cursor must jitter (travel=$raw)", raw > 0.3f)
        assertTrue("compensation must cut jitter: $fixed vs $raw", fixed < raw * 0.35f)
    }

    @Test
    fun unsteadyMode_alsoDampsJitter_withoutGyro() {
        val cursor1 = PinchCursor(CursorConfig(shakeCompensation = false))
        val cursor2 = PinchCursor(CursorConfig(shakeCompensation = false))
        var t = 0L
        var travel1 = 0f
        var travel2 = 0f
        var p1: Cursor? = null
        var p2: Cursor? = null
        for (i in 0 until 240) {
            t += 33
            val shift = 0.012f * sin(i * 0.8f) // дрожь от шагов, гироскопа нет
            val hand = syntheticHand(0.5f + shift, 0.5f, s = 0.15f)
            cursor1.process(HandFrame(t, hand, 480, 640, motion = FrameMotion(unsteady = false)))
            cursor2.process(HandFrame(t, hand, 480, 640, motion = FrameMotion(unsteady = true)))
            val c1 = cursor1.cursor!!
            val c2 = cursor2.cursor!!
            if (i > 60) {
                p1?.let { travel1 += hypot(c1.x - it.x, c1.y - it.y) }
                p2?.let { travel2 += hypot(c2.x - it.x, c2.y - it.y) }
            }
            p1 = c1
            p2 = c2
        }
        assertTrue("unsteady mode must damp small jitter: $travel2 vs $travel1", travel2 < travel1 * 0.5f)
    }

    // ---------- Ориентация ----------

    @Test
    fun axisGains_followScreenAspect() {
        assertEquals(1f to 0.55f, PinchCursor.axisGains(CursorConfig()))
        val (px, py) = PinchCursor.axisGains(CursorConfig(screenAspect = 19.5f / 9f))
        assertEquals(1f, px, 0f)
        assertEquals(0.55f, py, 0.01f)
        val (lx, ly) = PinchCursor.axisGains(CursorConfig(screenAspect = 9f / 19.5f))
        assertEquals(0.55f, lx, 0.01f)
        assertEquals(1f, ly, 0f)
        assertEquals(1f to 1f, PinchCursor.axisGains(CursorConfig(screenAspect = 1f)))
    }

    @Test
    fun landscape_horizontalMotion_isSlowerThanPortrait() {
        // Одно и то же движение руки в одном и том же кадре; меняется только экран.
        fun run(aspect: Float, w: Int = 480, h: Int = 640): Float {
            val cursor = PinchCursor(CursorConfig(screenAspect = aspect))
            var t = 0L
            for (i in 0 until 20) {
                t += 33
                cursor.process(HandFrame(t, syntheticHand(0.3f + i * 0.002f, 0.5f, s = 0.15f), w, h))
            }
            return cursor.cursor!!.x - 0.5f
        }
        val portrait = run(19.5f / 9f)
        val landscape = run(9f / 19.5f)
        assertTrue("portrait must move right: $portrait", portrait > 0.05f)
        assertTrue("landscape x must be slower: $landscape vs $portrait", landscape < portrait * 0.75f && landscape > 0f)
    }
}
