package com.airtouch.gesture

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot

/** Касание экрана, которое имитирует курсор. Координаты — доли экрана 0..1. */
sealed interface TouchEvent {
    val x: Float
    val y: Float

    data class Tap(override val x: Float, override val y: Float) : TouchEvent
    data class Down(override val x: Float, override val y: Float) : TouchEvent
    data class Move(override val x: Float, override val y: Float) : TouchEvent
    data class Up(override val x: Float, override val y: Float) : TouchEvent
}

data class Cursor(val x: Float, val y: Float, val pinching: Boolean)

data class CursorConfig(
    /** Щипок: расстояние большой–указательный / длина ладони меньше этого… */
    val pinchOn: Float = 0.38f,
    /** …и отпускание — больше этого (гистерезис, чтобы не дребезжало). */
    val pinchOff: Float = 0.55f,
    /** Сколько кадров подряд нужно, чтобы поверить в смену щипка. */
    val debounceFrames: Int = 2,
    /** Щипок короче этого и без сдвига = тап. Дольше = палец «лёг» на экран (долгое нажатие/drag). */
    val tapMaxMs: Long = 280,
    /** Сдвиг курсора (доля экрана), после которого щипок становится перетаскиванием. */
    val dragStartDistance: Float = 0.03f,
    /** Какая часть кадра камеры растягивается на весь экран: не нужно тянуться к краям. */
    val regionLeft: Float = 0.15f,
    val regionRight: Float = 0.85f,
    val regionTop: Float = 0.12f,
    val regionBottom: Float = 0.72f,
)

/**
 * Курсор «как мышь»: точка между большим и указательным пальцами.
 * Щипок = палец на экране: быстро сжал-разжал → тап; сжал и повёл → прокрутка/перетаскивание;
 * сжал и держишь → долгое нажатие.
 */
class PinchCursor(config: CursorConfig = CursorConfig()) {

    @Volatile
    var config: CursorConfig = config

    private enum class Touch { NONE, PENDING, DRAGGING }

    private val fx = OneEuroFilter()
    private val fy = OneEuroFilter()
    private var pinched = false
    private var flipFrames = 0
    private var touch = Touch.NONE
    private var downAt = 0L
    private var downX = 0f
    private var downY = 0f

    var cursor: Cursor? = null
        private set

    /** Пальцы сжаты или «палец на экране» — курсорный режим нельзя прерывать. */
    val isTouching: Boolean get() = pinched || touch != Touch.NONE

    /** Сбросить всё. Если палец «лежал» на экране — вернёт Up, чтобы его отпустить. */
    fun reset(): TouchEvent? {
        val up = if (touch == Touch.DRAGGING) cursor?.let { TouchEvent.Up(it.x, it.y) } else null
        touch = Touch.NONE
        pinched = false
        flipFrames = 0
        fx.reset()
        fy.reset()
        cursor = null
        return up
    }

    fun process(frame: HandFrame): TouchEvent? {
        val cfg = config
        val t = frame.timestampMs

        val ratio = pinchRatio(frame)
        val wantPinched = if (pinched) ratio < cfg.pinchOff else ratio < cfg.pinchOn
        if (wantPinched != pinched) {
            if (++flipFrames >= cfg.debounceFrames) {
                pinched = wantPinched
                flipFrames = 0
            }
        } else {
            flipFrames = 0
        }

        val lm = frame.landmarks
        val mx = (lm[Lm.THUMB_TIP].x + lm[Lm.INDEX_TIP].x) / 2f
        val my = (lm[Lm.THUMB_TIP].y + lm[Lm.INDEX_TIP].y) / 2f
        val sx = ((mx - cfg.regionLeft) / (cfg.regionRight - cfg.regionLeft)).coerceIn(0f, 1f)
        val sy = ((my - cfg.regionTop) / (cfg.regionBottom - cfg.regionTop)).coerceIn(0f, 1f)
        val x = fx.filter(sx, t)
        val y = fy.filter(sy, t)
        cursor = Cursor(x, y, pinched)

        return when (touch) {
            Touch.NONE -> {
                if (pinched) {
                    touch = Touch.PENDING
                    downAt = t
                    downX = x
                    downY = y
                }
                null
            }
            Touch.PENDING -> when {
                !pinched -> {
                    touch = Touch.NONE
                    TouchEvent.Tap(downX, downY)
                }
                hypot(x - downX, y - downY) > cfg.dragStartDistance || t - downAt > cfg.tapMaxMs -> {
                    touch = Touch.DRAGGING
                    TouchEvent.Down(downX, downY)
                }
                else -> null
            }
            Touch.DRAGGING -> if (!pinched) {
                touch = Touch.NONE
                TouchEvent.Up(x, y)
            } else {
                TouchEvent.Move(x, y)
            }
        }
    }

    companion object {
        /** Расстояние между кончиками большого и указательного относительно длины ладони. */
        fun pinchRatio(frame: HandFrame): Float =
            frame.dist(Lm.THUMB_TIP, Lm.INDEX_TIP) / frame.dist(Lm.WRIST, Lm.MIDDLE_MCP).coerceAtLeast(1e-4f)
    }
}

/**
 * One Euro Filter (Casiez et al., 2012): почти не дрожит в покое и почти не отстаёт при быстром
 * движении — стандарт для курсоров по трекингу.
 */
class OneEuroFilter(
    private val minCutoff: Float = 1.2f,
    private val beta: Float = 4f,
    private val dCutoff: Float = 1f,
) {
    private var prev: Float? = null
    private var dPrev = 0f
    private var tPrev = 0L

    fun reset() {
        prev = null
        dPrev = 0f
    }

    fun filter(x: Float, tMs: Long): Float {
        val p = prev
        if (p == null) {
            prev = x
            tPrev = tMs
            return x
        }
        val dt = (tMs - tPrev).coerceAtLeast(1L) / 1000f
        tPrev = tMs
        val dx = (x - p) / dt
        val dHat = dPrev + alpha(dCutoff, dt) * (dx - dPrev)
        dPrev = dHat
        val cutoff = minCutoff + beta * abs(dHat)
        val out = p + alpha(cutoff, dt) * (x - p)
        prev = out
        return out
    }

    private fun alpha(cutoff: Float, dt: Float): Float {
        val tau = 1f / (2f * PI.toFloat() * cutoff)
        return 1f / (1f + tau / dt)
    }
}
