package com.airtouch.e2e

import android.app.UiAutomation
import android.content.Context
import android.os.SystemClock
import android.view.inputmethod.InputMethodManager
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.airtouch.core.AirTouchState
import com.airtouch.debug.TestScreenActivity
import com.airtouch.gesture.ControlMode
import com.airtouch.gesture.CursorConfig
import com.airtouch.gesture.EngineConfig
import com.airtouch.gesture.GestureEngine
import com.airtouch.gesture.HandFrame
import com.airtouch.gesture.SystemAction
import com.airtouch.keyboard.Dictionary
import com.airtouch.keyboard.GlideDecoder
import com.airtouch.keyboard.KeyboardController
import com.airtouch.keyboard.KeyboardLayout
import com.airtouch.keyboard.Lang
import com.airtouch.gesture.TouchEvent
import com.airtouch.service.AirInputService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Сквозная проверка на настоящем Android (эмулятор в CI), всё кроме камеры:
 * синтетическая рука (30 кадров/с, реальное время) → GestureEngine → AirInputService
 * (dispatchGesture / ACTION_SET_TEXT / performGlobalAction) → настоящие View тестового экрана.
 */
@RunWith(AndroidJUnit4::class)
class AirPipelineTest {

    private val instr = InstrumentationRegistry.getInstrumentation()
    // Без этого флага UiAutomation глушит остальные службы спец. возможностей — и нашу тоже.
    private val ui: UiAutomation = instr.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private lateinit var scenario: ActivityScenario<TestScreenActivity>
    private lateinit var service: AirInputService

    private fun sh(cmd: String) {
        ui.executeShellCommand(cmd).use { pfd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
        }
    }

    @Before
    fun setUp() {
        sh("settings put secure enabled_accessibility_services com.airtouch/com.airtouch.service.AirInputService")
        sh("settings put secure accessibility_enabled 1")
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (AirInputService.instance == null && SystemClock.uptimeMillis() < deadline) Thread.sleep(200)
        service = AirInputService.instance ?: error("AirInputService did not connect")
        TestScreenActivity.resetCounters()
        scenario = ActivityScenario.launch(TestScreenActivity::class.java)
        Thread.sleep(1000)
    }

    @After
    fun tearDown() {
        scenario.close()
    }

    // ---------- синтетическая рука ----------

    private val finger = booleanArrayOf(true, false, false, false)
    private val cursorCfg = CursorConfig()

    /** Где должна быть рука в кадре, чтобы курсор оказался в точке экрана (sx, sy). */
    private fun handFor(sx: Float, sy: Float): Pair<Float, Float> {
        val ax = cursorCfg.regionLeft + sx * (cursorCfg.regionRight - cursorCfg.regionLeft)
        val ay = cursorCfg.regionTop + sy * (cursorCfg.regionBottom - cursorCfg.regionTop)
        // Якорь курсора — середина костяшки и сустава указательного синтетической руки.
        return (ax + 0.3f * 0.15f) to (ay + 0.25f * 0.15f)
    }

    private inner class Hand(sx: Float, sy: Float) {
        val engine = GestureEngine(EngineConfig(mode = ControlMode.PINCH, engageMs = 0, maxPalmSize = 1f))
        val touches = mutableListOf<TouchEvent>()
        var x = sx
        var y = sy

        private fun frame(pinch: Boolean) {
            val (cx, cy) = handFor(x, y)
            val out = engine.process(HandFrame(SystemClock.uptimeMillis(), syntheticHand(cx, cy, finger, pinch = pinch)))
            out.touch?.let { touches += it; service.touch(it) }
            out.action?.let { service.system(it) }
            Thread.sleep(33)
        }

        fun hold(ms: Long, pinch: Boolean = false) = apply { repeat((ms / 33).toInt().coerceAtLeast(1)) { frame(pinch) } }

        fun move(tx: Float, ty: Float, ms: Long, pinch: Boolean) = apply {
            val steps = (ms / 33).toInt().coerceAtLeast(1)
            val sx = x
            val sy = y
            for (i in 1..steps) {
                x = sx + (tx - sx) * i / steps
                y = sy + (ty - sy) * i / steps
                frame(pinch)
            }
        }
    }

    private fun scrollY(): Int {
        var v = 0
        scenario.onActivity { v = it.scroll.scrollY }
        return v
    }

    // ---------- тесты ----------

    @Test
    fun quickPinch_clicksButton() {
        Hand(0.4f, 0.15f).hold(400).hold(130, pinch = true).hold(300)
        Thread.sleep(500)
        assertEquals("button clicks", 1, TestScreenActivity.clicks.get())
        assertEquals("long presses", 0, TestScreenActivity.longPresses.get())
    }

    @Test
    fun pinchPauseThenDrag_scrollsList_withoutLongPress() {
        // Сценарий из жалобы: щипок, пауза ~0.45 с, потом повёл вверх.
        val hand = Hand(0.4f, 0.9f).hold(400).hold(450, pinch = true).move(0.4f, 0.45f, 350, pinch = true).hold(300)
        Thread.sleep(1000)
        val y = scrollY()
        assertTrue("list should scroll, scrollY=$y touches=${hand.touches.map { it::class.simpleName }}", y > 150)
        assertEquals("long presses", 0, TestScreenActivity.longPresses.get())
        assertEquals("row clicks", 0, TestScreenActivity.rowClicks.get())
    }

    @Test
    fun quickFlick_scrollsList() {
        val before = AirInputService.Stats.toString()
        val hand = Hand(0.4f, 0.9f).hold(400).hold(100, pinch = true).move(0.4f, 0.5f, 200, pinch = true).hold(300)
        Thread.sleep(1500)
        val y = scrollY()
        assertTrue(
            "flick should scroll, scrollY=$y touches=${hand.touches} before=[$before] after=[${AirInputService.Stats}]",
            y > 150,
        )
        assertEquals("long presses", 0, TestScreenActivity.longPresses.get())
    }

    @Test
    fun mediumFlick_scrollsList() {
        val hand = Hand(0.4f, 0.9f).hold(400).hold(100, pinch = true).move(0.4f, 0.5f, 400, pinch = true).hold(300)
        Thread.sleep(1500)
        val y = scrollY()
        assertTrue("medium flick should scroll, scrollY=$y touches=${hand.touches} stats=[${AirInputService.Stats}]", y > 150)
    }

    @Test
    fun pinchHeldStill_isLongPress() {
        Hand(0.3f, 0.7f).hold(400).hold(2200, pinch = true).hold(300)
        Thread.sleep(1500)
        assertTrue("long press expected", TestScreenActivity.longPresses.get() >= 1)
        assertEquals("no scroll", 0, scrollY())
    }

    @Test
    fun wavingWithoutPinch_doesNothing() {
        Hand(0.2f, 0.6f).hold(300).move(0.6f, 0.6f, 200, pinch = false).move(0.2f, 0.4f, 200, pinch = false)
            .move(0.5f, 0.9f, 200, pinch = false).hold(500)
        Thread.sleep(500)
        assertEquals(0, TestScreenActivity.clicks.get() + TestScreenActivity.rowClicks.get() + TestScreenActivity.longPresses.get())
        assertEquals(0, scrollY())
    }

    @Test
    fun airKeyboard_glideTyping_insertsIntoField() {
        scenario.onActivity { it.edit.requestFocus() }
        Thread.sleep(300)
        val ctx = instr.targetContext
        val decoders = Lang.values().associateWith { lang ->
            ctx.assets.open(Dictionary.assetName(lang)).bufferedReader().use {
                GlideDecoder(KeyboardLayout.of(lang), Dictionary.parse(it))
            }
        }
        val kb = KeyboardController(service.textSink, { decoders[it] })
        fun glide(word: String) {
            val pts = word.map { c -> kb.layout.keys.first { it.char == c } }.map { it.cx to it.cy }
            kb.onTouch(TouchEvent.Down(pts[0].first, pts[0].second))
            pts.drop(1).forEach { kb.onTouch(TouchEvent.Move(it.first, it.second)) }
            kb.onTouch(TouchEvent.Up(pts.last().first, pts.last().second))
            Thread.sleep(200)
        }
        glide("привет")
        glide("мир")
        Thread.sleep(300)
        var text = ""
        scenario.onActivity { text = it.edit.text.toString() }
        assertEquals("Привет мир ", text)
    }

    @Test
    fun systemKeyboardOpening_isDetected() {
        scenario.onActivity {
            it.edit.requestFocus()
            it.getSystemService(InputMethodManager::class.java).showSoftInput(it.edit, InputMethodManager.SHOW_IMPLICIT)
        }
        val deadline = SystemClock.uptimeMillis() + 8000
        while (!AirTouchState.status.value.keyboardVisible && SystemClock.uptimeMillis() < deadline) Thread.sleep(200)
        val s = AirTouchState.status.value
        assertTrue("IME window should be detected: $s", s.keyboardVisible && s.kbBottom > s.kbTop)
    }

    @Test
    fun backAction_closesScreen() {
        service.system(SystemAction.BACK)
        val deadline = SystemClock.uptimeMillis() + 5000
        while (scenario.state != Lifecycle.State.DESTROYED && SystemClock.uptimeMillis() < deadline) Thread.sleep(200)
        assertEquals(Lifecycle.State.DESTROYED, scenario.state)
    }
}
