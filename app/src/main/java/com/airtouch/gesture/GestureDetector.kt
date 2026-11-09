package com.airtouch.gesture

import kotlin.math.abs
import kotlin.math.hypot

/** Жесты, которые детектор отдаёт наружу. AirInputService превращает их в системные события. */
sealed interface AirGesture {
    data object SwipeLeft : AirGesture
    data object SwipeRight : AirGesture
    data object SwipeUp : AirGesture
    data object SwipeDown : AirGesture
}

/** Где в кадре рука вообще считается «активной». */
enum class ActivationZone {
    FULL,
    /** Только верхняя треть кадра — рука внизу (например, при селфи) игнорируется. */
    TOP_THIRD;

    fun contains(normalizedY: Float): Boolean = when (this) {
        FULL -> true
        TOP_THIRD -> normalizedY <= 1f / 3f
    }
}

data class SwipeConfig(
    /** 0.5…2.0. Больше — короче нужен мах. */
    val sensitivity: Float = 1f,
    /** Длина маха при sensitivity = 1, в долях короткой стороны кадра. */
    val baseDistance: Float = 0.25f,
    /** Мёртвая зона: сдвиги центра ладони меньше этого считаются дрожанием и гасятся. */
    val deadZone: Float = 0.012f,
    /** Окно, в котором ищем мах. Медленный дрейф дольше окна свайпом не станет. */
    val windowMs: Long = 450,
    /** Минимальная средняя скорость маха, коротких сторон кадра в секунду. */
    val minVelocity: Float = 0.8f,
    /** Основная ось должна доминировать: |dx| >= axisDominance * |dy| (и наоборот для вертикали). */
    val axisDominance: Float = 1.7f,
    /** После свайпа: минимальная пауза… */
    val cooldownMs: Long = 400,
    /** …и рука должна замереть на столько, прежде чем снова ловить мах (гасит «обратный ход»). */
    val rearmStillMs: Long = 120,
    /** Скорость, ниже которой рука считается неподвижной. */
    val stillVelocity: Float = 0.45f,
    /** Сглаживание EMA, 0..1 (1 = без сглаживания). */
    val smoothing: Float = 0.6f,
    val enableVertical: Boolean = true,
    val activationZone: ActivationZone = ActivationZone.FULL,
) {
    val requiredDistance: Float get() = baseDistance / sensitivity.coerceIn(0.25f, 4f)
}

/**
 * Распознавание свайпа ладонью или пальцем (кулак игнорируется).
 *
 * Алгоритм:
 * 1. Берём центр ладони, сглаживаем EMA и гасим дрожание мёртвой зоной.
 * 2. Держим историю точек за последние [SwipeConfig.windowMs].
 * 3. Свайп = смещение от самой старой точки окна >= requiredDistance, по доминирующей оси,
 *    со средней скоростью >= minVelocity.
 * 4. После срабатывания — кулдаун до тех пор, пока рука не замрёт (или не уйдёт из кадра),
 *    иначе возврат руки в исходное положение дал бы ложный обратный свайп.
 *
 * Не потокобезопасен: вызывать из одного потока (результат-листенер MediaPipe).
 */
class GestureDetector(config: SwipeConfig = SwipeConfig()) {

    @Volatile
    var config: SwipeConfig = config

    private data class Sample(val t: Long, val x: Float, val y: Float)

    private enum class State { READY, COOLDOWN }

    private val history = ArrayDeque<Sample>()
    private var smoothed: Sample? = null
    private var state = State.READY
    private var cooldownUntil = 0L
    private var stillSince: Long? = null

    /** Последняя сглаженная позиция ладони (нормализованная) — для точки-трекера. */
    var lastPalm: Landmark? = null
        private set

    var lastPose: HandPose = HandPose.OTHER
        private set

    /** Рука пропала из кадра: всё сбрасываем, в т.ч. кулдаун — следующий мах можно делать сразу. */
    fun onHandLost() {
        history.clear()
        smoothed = null
        state = State.READY
        stillSince = null
        lastPalm = null
    }

    fun process(frame: HandFrame): AirGesture? {
        val cfg = config
        val t = frame.timestampMs
        val pose = HandPoseClassifier.classify(frame)
        lastPose = pose

        // Координаты в единицах короткой стороны кадра: оси сопоставимы между собой.
        val raw = frame.palmCenter
        val rx = raw.x * frame.xScale
        val ry = raw.y * frame.yScale

        val prev = smoothed
        var cur = if (prev == null) {
            Sample(t, rx, ry)
        } else {
            val a = cfg.smoothing.coerceIn(0.05f, 1f)
            Sample(t, prev.x + a * (rx - prev.x), prev.y + a * (ry - prev.y))
        }
        // Мёртвая зона: микросдвиг «прилипает» к предыдущей точке.
        if (prev != null && hypot(cur.x - prev.x, cur.y - prev.y) < cfg.deadZone) {
            cur = Sample(t, prev.x, prev.y)
        }
        val speed = if (prev != null && t > prev.t) {
            hypot(cur.x - prev.x, cur.y - prev.y) / ((t - prev.t) / 1000f)
        } else 0f
        smoothed = cur
        lastPalm = Landmark(cur.x / frame.xScale, cur.y / frame.yScale)

        if (state == State.COOLDOWN) {
            if (t >= cooldownUntil && speed < cfg.stillVelocity) {
                val since = stillSince ?: t.also { stillSince = it }
                if (t - since >= cfg.rearmStillMs) {
                    state = State.READY
                    stillSince = null
                    history.clear()
                }
            } else {
                stillSince = null
            }
            return null
        }

        // Мах ладонью или пальцем — оба считаются. Кулак зарезервирован под drag (этап 3).
        if (pose == HandPose.FIST || !cfg.activationZone.contains(raw.y)) {
            history.clear()
            return null
        }

        history.addLast(cur)
        while (history.isNotEmpty() && t - history.first().t > cfg.windowMs) history.removeFirst()

        // Перебираем возможные начала маха внутри окна (их ~10–15): пауза перед махом
        // не должна «размазывать» среднюю скорость.
        val gesture = history.firstNotNullOfOrNull { start -> classifySegment(start, cur, cfg) }

        if (gesture != null) {
            state = State.COOLDOWN
            cooldownUntil = t + cfg.cooldownMs
            stillSince = null
            history.clear()
        }
        return gesture
    }

    private fun classifySegment(start: Sample, end: Sample, cfg: SwipeConfig): AirGesture? {
        val dtMs = end.t - start.t
        if (dtMs < MIN_SWIPE_DURATION_MS) return null
        val dtSec = dtMs / 1000f
        val dx = end.x - start.x
        val dy = end.y - start.y
        val required = cfg.requiredDistance
        return when {
            abs(dx) >= required && abs(dx) >= cfg.axisDominance * abs(dy) &&
                abs(dx) / dtSec >= cfg.minVelocity ->
                if (dx < 0) AirGesture.SwipeLeft else AirGesture.SwipeRight

            cfg.enableVertical && abs(dy) >= required && abs(dy) >= cfg.axisDominance * abs(dx) &&
                abs(dy) / dtSec >= cfg.minVelocity ->
                if (dy < 0) AirGesture.SwipeUp else AirGesture.SwipeDown

            else -> null
        }
    }

    private companion object {
        /** Меньше ~2 кадров при 30 FPS — это скачок трекинга, а не мах. */
        const val MIN_SWIPE_DURATION_MS = 60L
    }
}
