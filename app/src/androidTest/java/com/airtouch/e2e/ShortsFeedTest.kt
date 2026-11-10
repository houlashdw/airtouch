package com.airtouch.e2e

import android.app.UiAutomation
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.airtouch.debug.TestFeedActivity
import com.airtouch.gesture.ControlMode
import com.airtouch.gesture.CursorConfig
import com.airtouch.gesture.EngineConfig
import com.airtouch.gesture.GestureEngine
import com.airtouch.gesture.HandFrame
import com.airtouch.service.AirInputService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Лента как YouTube Shorts: флик щипком листает на следующее видео и НЕ открывает меню. */
@RunWith(AndroidJUnit4::class)
class ShortsFeedTest {

    private val ui = InstrumentationRegistry.getInstrumentation()
        .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private lateinit var scenario: ActivityScenario<TestFeedActivity>
    private lateinit var service: AirInputService
    private val finger = booleanArrayOf(true, false, false, false)
    private val c = CursorConfig()

    private fun sh(cmd: String) = ui.executeShellCommand(cmd).use { pfd ->
        android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
    }

    @Before
    fun setUp() {
        sh("settings put secure enabled_accessibility_services com.airtouch/com.airtouch.service.AirInputService")
        sh("settings put secure accessibility_enabled 1")
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (AirInputService.instance == null && SystemClock.uptimeMillis() < deadline) Thread.sleep(200)
        service = AirInputService.instance ?: error("AirInputService did not connect")
        TestFeedActivity.resetCounters()
        scenario = ActivityScenario.launch(TestFeedActivity::class.java)
        Thread.sleep(1000)
    }

    @After
    fun tearDown() = scenario.close()

    private fun play(script: List<Triple<Float, Float, Boolean>>) {
        val engine = GestureEngine(EngineConfig(mode = ControlMode.PINCH, engageMs = 0, maxPalmSize = 1f, cursor = CursorConfig(relative = false)))
        for ((sx, sy, pinch) in script) {
            val cx = c.regionLeft + sx * (c.regionRight - c.regionLeft) + 0.045f
            val cy = c.regionTop + sy * (c.regionBottom - c.regionTop) + 0.0375f
            val out = engine.process(HandFrame(SystemClock.uptimeMillis(), syntheticHand(cx, cy, finger, pinch = pinch)))
            out.touch?.let { service.touch(it) }
            Thread.sleep(33)
        }
    }

    private fun still(x: Float, y: Float, ms: Long, pinch: Boolean) = List((ms / 33).toInt()) { Triple(x, y, pinch) }
    private fun line(x: Float, y0: Float, y1: Float, ms: Long, pinch: Boolean): List<Triple<Float, Float, Boolean>> {
        val n = (ms / 33).toInt()
        return List(n) { i -> Triple(x, y0 + (y1 - y0) * (i + 1) / n, pinch) }
    }

    private fun page(): Int {
        var p = -1
        scenario.onActivity { p = it.currentPage }
        return p
    }

    @Test
    fun flickUp_goesToNextVideo_noMenu() {
        play(still(0.4f, 0.7f, 400, false) + still(0.4f, 0.7f, 100, true) + line(0.4f, 0.7f, 0.35f, 230, true) + still(0.4f, 0.35f, 300, false))
        Thread.sleep(1500)
        assertEquals("page after flick (stats ${AirInputService.Stats})", 1, page())
        assertEquals("menu opens", 0, TestFeedActivity.menuOpens.get())
    }

    @Test
    fun hesitantFlick_pauseThenFlick_noMenu() {
        // Как в жалобе: щипок, замешкался ~0.45 с, потом листнул.
        play(still(0.4f, 0.7f, 400, false) + still(0.4f, 0.7f, 450, true) + line(0.4f, 0.7f, 0.35f, 230, true) + still(0.4f, 0.35f, 300, false))
        Thread.sleep(1500)
        assertEquals("page after flick (stats ${AirInputService.Stats})", 1, page())
        assertEquals("menu opens", 0, TestFeedActivity.menuOpens.get())
    }

    @Test
    fun twoFlicksInARow_twoVideosForward() {
        val one = still(0.4f, 0.7f, 100, true) + line(0.4f, 0.7f, 0.35f, 230, true) + still(0.4f, 0.35f, 150, false) +
            line(0.4f, 0.35f, 0.7f, 300, false) + still(0.4f, 0.7f, 300, false)
        play(still(0.4f, 0.7f, 400, false) + one + one)
        Thread.sleep(1500)
        assertEquals("page after two flicks", 2, page())
        assertEquals("menu opens", 0, TestFeedActivity.menuOpens.get())
    }

    @Test
    fun restingPinchWithJitter_doesNothing_noMenu() {
        // Как на видео: пальцы сведены, рука чуть дрогнула и замерла на 1.5 с — меню открываться не должно.
        play(still(0.4f, 0.5f, 400, false) + still(0.4f, 0.5f, 100, true) + line(0.4f, 0.5f, 0.535f, 100, true) +
            still(0.4f, 0.535f, 1500, true) + still(0.4f, 0.535f, 300, false))
        Thread.sleep(1500)
        assertEquals("menu opens", 0, TestFeedActivity.menuOpens.get())
        assertEquals("still on first video", 0, page())
    }

    @Test
    fun quickPinch_pausesVideo_noMenu() {
        play(still(0.4f, 0.5f, 400, false) + still(0.4f, 0.5f, 130, true) + still(0.4f, 0.5f, 300, false))
        Thread.sleep(800)
        assertEquals("taps", 1, TestFeedActivity.pauses.get())
        assertEquals("menu opens", 0, TestFeedActivity.menuOpens.get())
        assertEquals("still on first video", 0, page())
    }
}
