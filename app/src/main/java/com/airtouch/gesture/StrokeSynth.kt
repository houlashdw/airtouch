package com.airtouch.gesture

import kotlin.math.abs
import kotlin.math.hypot

/** Что отправить на экран после отпускания щипка. Координаты — доли экрана. */
sealed interface Synth {
    data class LongPress(val x: Float, val y: Float) : Synth
    data class Flick(val x0: Float, val y0: Float, val x1: Float, val y1: Float) : Synth
    data object Nothing : Synth
}

/**
 * Палец НЕ лежит на экране, пока сведены пальцы: росчерк записывается, а при разжатии
 * отправляется один чистый жест. Поэтому долгое нажатие «само» случиться не может —
 * только если человек намеренно держал щипок неподвижно ([TouchEvent.Down.longPress]).
 */
object StrokeSynth {
    /** Сдвиг меньше этого — не свайп. */
    const val MIN_FLICK = 0.06f
    /** Свайп всегда не короче этого, чтобы лента гарантированно перелистнулась. */
    const val MIN_LENGTH = 0.32f

    fun decide(x0: Float, y0: Float, x1: Float, y1: Float, longPress: Boolean): Synth {
        var dx = x1 - x0
        var dy = y1 - y0
        val d = hypot(dx, dy)
        if (d < MIN_FLICK) return if (longPress) Synth.LongPress(x0, y0) else Synth.Nothing
        // Почти вертикально/горизонтально — выпрямляем: чище для лент и каруселей.
        // Ленты листают вертикально — косой мах вверх/вниз не должен становиться горизонтальным
        // (переключать вкладки). Горизонталь — только явно горизонтальный мах.
        if (abs(dx) > 1.8f * abs(dy)) dy = 0f else if (abs(dy) > 0.6f * abs(dx)) dx = 0f
        val len = hypot(dx, dy)
        val scale = maxOf(d, MIN_LENGTH) / len
        var ex = x0 + dx * scale
        var ey = y0 + dy * scale
        // Не выходим за экран и не начинаем у самого края (системные жесты).
        val sx = x0.coerceIn(0.08f, 0.92f)
        val sy = y0.coerceIn(0.12f, 0.88f)
        ex = (ex + (sx - x0)).coerceIn(0.05f, 0.95f)
        ey = (ey + (sy - y0)).coerceIn(0.05f, 0.95f)
        return Synth.Flick(sx, sy, ex, ey)
    }
}
