package com.airtouch.gesture

import org.junit.Assert.assertEquals
import org.junit.Test

class StrokeSynthTest {
    @Test
    fun restingPinch_withoutIntent_isNothing_notLongPress() {
        // Палец «полежал» с микросдвигом — раньше это открывало меню YouTube.
        assertEquals(Synth.Nothing, StrokeSynth.decide(0.5f, 0.5f, 0.52f, 0.51f, longPress = false))
    }

    @Test
    fun deliberateHold_isLongPress() {
        assertEquals(Synth.LongPress(0.5f, 0.5f), StrokeSynth.decide(0.5f, 0.5f, 0.51f, 0.5f, longPress = true))
    }

    @Test
    fun shortUpwardStroke_becomesFullVerticalFlick() {
        val f = StrokeSynth.decide(0.5f, 0.7f, 0.53f, 0.62f, longPress = false) as Synth.Flick
        assert(kotlin.math.abs(f.x0 - f.x1) < 1e-6f) { "должен быть выпрямлен по вертикали: $f" }
        assert(f.y0 - f.y1 >= 0.3f) { "flick must be long enough to change the video: $f" }
    }

    @Test
    fun diagonalUpFlick_staysVertical_notTabSwitch() {
        val f = StrokeSynth.decide(0.5f, 0.7f, 0.62f, 0.55f, longPress = false) as Synth.Flick // dx=0.12, dy=-0.15
        assert(f.x0 == f.x1) { "must be vertical: $f" }
    }

    @Test
    fun flickStaysOnScreen() {
        val f = StrokeSynth.decide(0.5f, 0.15f, 0.5f, 0.02f, longPress = false) as Synth.Flick
        assert(f.y1 >= 0.05f && f.y0 <= 0.88f) { "$f" }
    }
}
