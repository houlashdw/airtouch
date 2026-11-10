package com.airtouch.gesture

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot

/** Касание экрана, которое имитирует курсор. Координаты — доли экрана 0..1. */
sealed interface TouchEvent {
    val x: Float
    val y: Float

    data class Tap(override val x: Float, override val y: Float) : TouchEvent
    /** [longPress] — палец «лёг» по таймеру удержания (а не потому, что рука пошла). */
    data class Down(override val x: Float, override val y: Float, val longPress: Boolean = false) : TouchEvent
    data class Move(override val x: Float, override val y: Float) : TouchEvent
    data class Up(override val x: Float, override val y: Float) : TouchEvent
}

data class Cursor(val x: Float, val y: Float, val pinching: Boolean)

data class CursorConfig(
    /** Щипок: расстояние большой–указательный / длина ладони меньше этого… */
    val pinchOn: Float = 0.25f,
    /** …и отпускание — больше этого (гистерезис, чтобы не дребезжало). */
    val pinchOff: Float = 0.40f,
    /** Сколько кадров подряд нужно, чтобы поверить в смену щипка. */
    val debounceFrames: Int = 3,
    /**
     * Щипок засчитывается, только если перед ним пальцы были явно разведены (не дальше этого по времени).
     * Расслабленная рука, где большой палец просто лежит у указательного, щипком не считается.
     */
    val openBeforeMs: Long = 700,
    val openRatio: Float = 0.6f,
    /**
     * Щипок без движения дольше этого = палец «лёг» на экран (долгое нажатие).
     * Пока рука не двинулась и время не вышло, палец на экран НЕ кладём: иначе приложение
     * (YouTube и т.п.) успевает засчитать долгое нажатие, пока рука только собирается листать.
     */
    val longPressMs: Long = 1200,
    /** Сдвиг курсора (доля экрана), после которого щипок становится перетаскиванием. */
    val dragStartDistance: Float = 0.03f,
    /** Какая часть кадра камеры растягивается на весь экран: не нужно тянуться к краям. */
    val regionLeft: Float = 0.08f,
    val regionRight: Float = 0.92f,
    val regionTop: Float = 0.08f,
    val regionBottom: Float = 0.80f,
    /** Скачок курсора больше этого за кадр — сбой трекинга, кадр пропускаем. */
    val maxJump: Float = 0.2f,
    /** Рука сдвинулась больше этого за ~250 мс до щипка — нажатия не будет (курсор ехал, щипок случайный). */
    val stillForTap: Float = 0.04f,
)

/**
 * Курсор «как мышь». Щипок = палец на экране: сжал-разжал без движения → тап;
 * сжал и повёл → прокрутка/перетаскивание (палец ложится в момент начала движения);
 * сжал и держишь неподвижно [CursorConfig.longPressMs] → палец лёг → долгое нажатие.
 */
class PinchCursor(config: CursorConfig = CursorConfig()) {

    @Volatile
    var config: CursorConfig = config

    private enum class Touch { NONE, PENDING, DRAGGING }

    // Сглаживание сильнее, чем у махов: курсору важна точность, а не мгновенность.
    private val fx = OneEuroFilter(minCutoff = 0.6f, beta = 2.5f)
    private val fy = OneEuroFilter(minCutoff = 0.6f, beta = 2.5f)
    private var lastOpenAt = Long.MIN_VALUE / 2
    /** Щипок «из ниоткуда» (пальцы не разводили) — игнорируем до разжатия. */
    private var ignoring = false
    private var rawPrev: Pair<Float, Float>? = null
    private var jumpFrames = 0
    private var movingAtPinch = false
    /** Позиции курсора за последние ~300 мс — чтобы нажать туда, где курсор был ДО щипка. */
    private val trail = ArrayDeque<Triple<Long, Float, Float>>()
    private var pinched = false
    private var flipFrames = 0
    private var touch = Touch.NONE
    private var downAt = 0L
    private var downX = 0f
    private var downY = 0f

    var cursor: Cursor? = null
        private set

    /** Пальцы сжаты или «палец на экране» — курсорный режим нельзя прерывать. */
    val isTouching: Boolean get() = (pinched && !ignoring) || touch != Touch.NONE

    /** Сбросить всё. Если палец «лежал» на экране — вернёт Up, чтобы его отпустить. */
    fun reset(): TouchEvent? {
        val up = if (touch == Touch.DRAGGING) cursor?.let { TouchEvent.Up(it.x, it.y) } else null
        touch = Touch.NONE
        pinched = false
        flipFrames = 0
        fx.reset()
        fy.reset()
        trail.clear()
        rawPrev = null
        ignoring = false
        cursor = null
        return up
    }

    fun process(frame: HandFrame): TouchEvent? {
        val cfg = config
        val t = frame.timestampMs

        val ratio = pinchRatio(frame)
        if (ratio >= cfg.openRatio) lastOpenAt = t
        val wantPinched = if (pinched) ratio < cfg.pinchOff else ratio < cfg.pinchOn
        if (wantPinched != pinched) {
            if (++flipFrames >= cfg.debounceFrames) {
                pinched = wantPinched
                flipFrames = 0
            }
        } else {
            flipFrames = 0
        }

        // Якорь — между костяшкой и средним суставом указательного: при щипке сгибается кончик,
        // а эта точка почти не сдвигается, поэтому курсор не «прыгает» в момент нажатия.
        val lm = frame.landmarks
        val mx = (lm[Lm.INDEX_MCP].x + lm[Lm.INDEX_PIP].x) / 2f
        val my = (lm[Lm.INDEX_MCP].y + lm[Lm.INDEX_PIP].y) / 2f
        val sx = ((mx - cfg.regionLeft) / (cfg.regionRight - cfg.regionLeft)).coerceIn(0f, 1f)
        val sy = ((my - cfg.regionTop) / (cfg.regionBottom - cfg.regionTop)).coerceIn(0f, 1f)
        // Сбой трекинга: курсор «телепортировался» — не верим 1–2 кадрам.
        val rp = rawPrev
        if (rp != null && hypot(sx - rp.first, sy - rp.second) > cfg.maxJump && jumpFrames < 2) {
            jumpFrames++
            return null
        }
        jumpFrames = 0
        rawPrev = sx to sy
        val x = fx.filter(sx, t)
        val y = fy.filter(sy, t)
        cursor = Cursor(x, y, pinched)
        trail.addLast(Triple(t, x, y))
        while (trail.isNotEmpty() && t - trail.first().first > 300) trail.removeFirst()

        return when (touch) {
            Touch.NONE -> {
                if (!pinched) ignoring = false
                if (pinched && !ignoring && t - lastOpenAt > cfg.openBeforeMs) ignoring = true
                if (pinched && !ignoring) {
                    touch = Touch.PENDING
                    downAt = t
                    // Сведение пальцев слегка тянет руку — берём точку ~120 мс назад.
                    val before = trail.lastOrNull { t - it.first >= PINCH_LOOKBACK_MS } ?: trail.first()
                    downX = before.second
                    downY = before.third
                    movingAtPinch = trail.filter { t - it.first <= 250 }.any { hypot(it.second - x, it.third - y) > cfg.stillForTap }
                }
                null
            }
            Touch.PENDING -> when {
                !pinched -> {
                    touch = Touch.NONE
                    if (movingAtPinch) null else TouchEvent.Tap(downX, downY)
                }
                hypot(x - downX, y - downY) > cfg.dragStartDistance -> {
                    touch = Touch.DRAGGING
                    TouchEvent.Down(downX, downY)
                }
                t - downAt > cfg.longPressMs && !movingAtPinch -> {
                    touch = Touch.DRAGGING
                    TouchEvent.Down(downX, downY, longPress = true)
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
        private const val PINCH_LOOKBACK_MS = 120L

        /** Расстояние между кончиками большого и указательного относительно длины ладони. */
        fun pinchRatio(frame: HandFrame): Float =
            frame.dist(Lm.THUMB_TIP, Lm.INDEX_TIP) / frame.dist(Lm.WRIST, Lm.MIDDLE_MCP).coerceAtLeast(1e-4f)

        /**
         * Похоже на щипок: кончики сведены И указательный вытянут вперёд за костяшку.
         * Второе условие отсекает кулак, где большой палец тоже лежит у указательного.
         */
        fun isPinchShape(frame: HandFrame, threshold: Float): Boolean =
            pinchRatio(frame) < threshold &&
                frame.dist(Lm.WRIST, Lm.INDEX_TIP) > frame.dist(Lm.WRIST, Lm.INDEX_MCP) * 1.25f
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
