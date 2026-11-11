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
    val pinchOn: Float = 0.30f,
    /** …и отпускание — больше этого (гистерезис, чтобы не дребезжало). */
    val pinchOff: Float = 0.42f,
    /** Сколько кадров подряд нужно, чтобы поверить в смену щипка (2 ≈ 65 мс: клик отзывчивый). */
    val debounceFrames: Int = 2,
    /** Щипок короче этого и почти без движения — тап, даже если рука перед ним шевелилась. */
    val tapMaxMs: Long = 450,
    /**
     * Первые миллисекунды после щипка перетаскивание начинается только с [dragGraceMul]-кратного
     * сдвига: сглаживание ещё «доезжает» после прицеливания, и без этого клик превращался в
     * микроскопическое перетаскивание, которое приложение не считает нажатием.
     */
    val dragGraceMs: Long = 160,
    val dragGraceMul: Float = 2.5f,
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
    /**
     * Курсор как тачпад: двигается от ДВИЖЕНИЯ руки, а не от её места в кадре.
     * Ракурс телефона и где рука в кадре — неважно; рука ушла — курсор остался на месте.
     */
    val relative: Boolean = true,
    /** Сколько экрана проезжает курсор за движение руки на одну длину ладони: медленно… */
    val gainSlow: Float = 0.35f,
    /** …и быстро (ускорение как у мышки). */
    val gainFast: Float = 1.5f,
    /** Скорость руки (ладоней в секунду), при которой ускорение максимальное. */
    val fastSpeed: Float = 5f,
    /** Экран высокий — по вертикали курсор едет медленнее, чтобы движение ощущалось одинаково. */
    val verticalGain: Float = 0.55f,
    /**
     * Высота экрана / ширина. 0 — неизвестно (берётся [verticalGain], как для телефона 9:19.5).
     * Задано — коэффициенты по осям считаются сами: длинная ось всегда медленнее, чтобы одно и то же
     * движение руки проезжало одинаковую долю экрана и в портрете, и в альбоме.
     */
    val screenAspect: Float = 0f,
    /** Телефон трясётся (ходьба): мёртвая зона и порог «рука стояла» во столько раз больше… */
    val unsteadyDeadZoneMul: Float = 2.5f,
    val unsteadyStillMul: Float = 1.6f,
    /** …сглаживание сильнее: частота среза One Euro ниже и меньше растёт со скоростью… */
    val unsteadyMinCutoff: Float = 0.25f,
    val unsteadyBeta: Float = 0.5f,
    /** …и щипок должен продержаться на кадр дольше. */
    val unsteadyDebounceExtra: Int = 1,
    /** Вычитать поворот телефона (гироскоп) из движения руки. */
    val shakeCompensation: Boolean = true,
    /** Микродвижения меньше этой доли ладони за кадр — дрожание, курсор стоит. */
    val deadZonePalm: Float = 0.015f,
    /** Ниже этой линии экрана (открыта обычная клавиатура) курсор медленнее — чтобы попадать в клавиши. */
    val precisionTop: Float = 2f,
    val precisionGain: Float = 0.55f,
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
    private val fx = OneEuroFilter(minCutoff = STEADY_CUTOFF, beta = STEADY_BETA)
    private val fy = OneEuroFilter(minCutoff = STEADY_CUTOFF, beta = STEADY_BETA)
    private val shake = ShakeCompensator()
    /** Телефон трясся на последнем кадре (для индикации). */
    var unsteady = false
        private set
    private var lastOpenAt = Long.MIN_VALUE / 2
    /** Щипок «из ниоткуда» (пальцы не разводили) — игнорируем до разжатия. */
    private var ignoring = false
    private var rawPrev: Pair<Float, Float>? = null
    private var jumpFrames = 0
    private var movingAtPinch = false
    // Тачпад: позиция курсора переживает пропажу руки.
    private var relX = 0.5f
    private var relY = 0.5f
    private var relPrev: Pair<Float, Float>? = null
    private var relPrevT = 0L
    private var lastRaw: Pair<Float, Float>? = null
    /** «Виртуальный» курсор без упора в края — по нему считаем росчерк (у края экрана курсор стоит, а рука идёт). */
    private var virtX = 0.5f
    private var virtY = 0.5f
    private var virtAtDownX = 0f
    private var virtAtDownY = 0f
    private val vtrail = ArrayDeque<Triple<Long, Float, Float>>()
    /** Насколько рука «додавила» курсор вниз, когда он уже упёрся в нижний край (доли экрана). */
    private var lastDrag: Pair<Float, Float>? = null
    var bottomPush = 0f
        private set
    private var accX = 0f
    private var accY = 0f
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
        // Рука пропала посреди росчерка (вылетела за кадр) — отпускаем там, куда росчерк дошёл.
        val up = if (touch == Touch.DRAGGING) lastDrag?.let { TouchEvent.Up(it.first, it.second) } else null
        lastDrag = null
        touch = Touch.NONE
        pinched = false
        flipFrames = 0
        fx.reset()
        fy.reset()
        shake.reset()
        trail.clear()
        vtrail.clear()
        rawPrev = null
        relPrev = null
        lastRaw = null
        ignoring = false
        cursor = null
        return up
    }

    fun process(frame: HandFrame): TouchEvent? {
        val cfg = config
        val t = frame.timestampMs
        val motion = frame.motion
        unsteady = motion.unsteady
        val deadZone = if (unsteady) cfg.deadZonePalm * cfg.unsteadyDeadZoneMul else cfg.deadZonePalm
        val stillForTap = if (unsteady) cfg.stillForTap * cfg.unsteadyStillMul else cfg.stillForTap
        val dragStart = if (unsteady) cfg.dragStartDistance * cfg.unsteadyStillMul else cfg.dragStartDistance
        val debounce = if (unsteady) cfg.debounceFrames + cfg.unsteadyDebounceExtra else cfg.debounceFrames
        val cutoff = if (unsteady) cfg.unsteadyMinCutoff else STEADY_CUTOFF
        val beta = if (unsteady) cfg.unsteadyBeta else STEADY_BETA
        fx.minCutoff = cutoff; fx.beta = beta
        fy.minCutoff = cutoff; fy.beta = beta
        val (gainX, gainY) = axisGains(cfg)

        val ratio = pinchRatio(frame)
        if (ratio >= cfg.openRatio) lastOpenAt = t
        val wantPinched = if (pinched) ratio < cfg.pinchOff else ratio < cfg.pinchOn
        if (wantPinched != pinched) {
            if (++flipFrames >= debounce) {
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
        val x: Float
        val y: Float
        if (cfg.relative) {
            // Координаты в единицах короткой стороны кадра, движение — в длинах ладони:
            // одинаково на 20 см и на метре от камеры.
            // Путь руки копим сразу в длинах ладони и уже его сглаживаем — так фильтр одинаково
            // ведёт себя для близкой и далёкой руки.
            val palm = frame.dist(Lm.WRIST, Lm.MIDDLE_MCP).coerceAtLeast(0.02f)
            val rx = mx * frame.xScale
            val ry = my * frame.yScale
            val lr = lastRaw
            if (lr != null) {
                var mdx = rx - lr.first
                var mdy = ry - lr.second
                if (cfg.shakeCompensation) {
                    // Тряска телефона: поворот камеры сдвигает ВСЮ картинку вместе с рукой — вычитаем.
                    val (cx, cy) = shake.compensate(mdx, mdy, motion.rotX, motion.rotY)
                    mdx = cx
                    mdy = cy
                }
                val ddx = mdx / palm
                val ddy = mdy / palm
                if (hypot(ddx, ddy) <= 1.5f) { accX += ddx; accY += ddy } // больше — сбой трекинга
            }
            lastRaw = rx to ry
            val fxv = fx.filter(accX, t)
            val fyv = fy.filter(accY, t)
            val prev = relPrev
            if (prev != null) {
                var dx = fxv - prev.first
                var dy = fyv - prev.second
                val dist = hypot(dx, dy)
                if (dist > 1.5f || dist < deadZone) { dx = 0f; dy = 0f } // сбой трекинга / дрожание
                val dt = (t - relPrevT).coerceAtLeast(1L) / 1000f
                val k = ((dist / dt) / cfg.fastSpeed).coerceIn(0f, 1f)
                val gain = (cfg.gainSlow + (cfg.gainFast - cfg.gainSlow) * k) *
                    if (relY >= cfg.precisionTop) cfg.precisionGain else 1f
                relX = (relX + dx * gain * gainX).coerceIn(0f, 1f)
                relY = (relY + dy * gain * gainY).coerceIn(0f, 1f)
                virtX += dx * gain * gainX
                virtY += dy * gain * gainY
                if (relY >= 0.999f && dy > 0) bottomPush += dy * gain * gainY
                else if (relY < 0.98f) bottomPush = 0f
            }
            relPrev = fxv to fyv
            relPrevT = t
            x = relX
            y = relY
        } else {
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
            x = fx.filter(sx, t)
            y = fy.filter(sy, t)
        }
        cursor = Cursor(x, y, pinched)
        if (!cfg.relative) { virtX = x; virtY = y }
        vtrail.addLast(Triple(t, virtX, virtY))
        while (vtrail.isNotEmpty() && t - vtrail.first().first > 300) vtrail.removeFirst()
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
                    // Рука ехала перед щипком (прицеливание на ходу) — это ещё не повод терять клик:
                    // тап решается по движению ВО ВРЕМЯ щипка. Здесь — только защита от долгого нажатия.
                    movingAtPinch = vtrail.filter { t - it.first <= 250 }.any { hypot(it.second - virtX, it.third - virtY) > stillForTap }
                    val vb = vtrail.lastOrNull { t - it.first >= PINCH_LOOKBACK_MS } ?: vtrail.first()
                    virtAtDownX = vb.second
                    virtAtDownY = vb.third
                }
                null
            }
            Touch.PENDING -> when {
                !pinched -> {
                    touch = Touch.NONE
                    val moved = hypot(virtX - virtAtDownX, virtY - virtAtDownY)
                    // Короткий щипок почти на месте — всегда тап. Рука до него могла и двигаться.
                    // Порог тот же, что не дал начаться перетаскиванию в льготное окно.
                    val quick = t - downAt <= cfg.tapMaxMs && moved <= dragStart * cfg.dragGraceMul
                    if (quick || !movingAtPinch) TouchEvent.Tap(downX, downY) else null
                }
                hypot(virtX - virtAtDownX, virtY - virtAtDownY) >
                    dragStart * (if (t - downAt < cfg.dragGraceMs) cfg.dragGraceMul else 1f) -> {
                    lastDrag = downX to downY
                    touch = Touch.DRAGGING
                    TouchEvent.Down(downX, downY)
                }
                t - downAt > cfg.longPressMs && !movingAtPinch -> {
                    lastDrag = downX to downY
                    touch = Touch.DRAGGING
                    TouchEvent.Down(downX, downY, longPress = true)
                }
                else -> null
            }
            // Путь росчерка — от точки щипка на сдвиг «виртуального» курсора (может выходить за экран:
            // упёрлись в край, а рука продолжает — росчерк всё равно засчитается).
            Touch.DRAGGING -> {
                val ex = downX + (virtX - virtAtDownX)
                val ey = downY + (virtY - virtAtDownY)
                lastDrag = ex to ey
                if (!pinched) {
                    touch = Touch.NONE
                    TouchEvent.Up(ex, ey)
                } else {
                    TouchEvent.Move(ex, ey)
                }
            }
        }
    }

    companion object {
        /** Точку тапа берём чуть раньше момента подтверждения: сведение пальцев тянет руку. ≈ дебаунс + 1 кадр. */
        private const val PINCH_LOOKBACK_MS = 100L
        private const val STEADY_CUTOFF = 0.6f
        private const val STEADY_BETA = 2.5f

        /**
         * Множители скорости курсора по осям экрана. Длинная сторона медленнее: для 9:19.5 это
         * привычные 1 × 0.55, для альбома 19.5:9 — 0.55 × 1, для квадрата — 1 × 1.
         */
        fun axisGains(cfg: CursorConfig): Pair<Float, Float> {
            val a = cfg.screenAspect
            if (a <= 0f) return 1f to cfg.verticalGain
            val slow = { ratio: Float -> (1.2f / ratio).coerceIn(0.45f, 1f) }
            return if (a >= 1f) 1f to slow(a) else slow(1f / a) to 1f
        }

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
    @Volatile var minCutoff: Float = 1.2f,
    @Volatile var beta: Float = 4f,
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
