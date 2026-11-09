package com.airtouch.gesture

import kotlin.math.abs
import kotlin.math.hypot

/** Свайпы, которые детектор отдаёт наружу. AirInputService превращает их в системные события. */
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
    val baseDistance: Float = 0.18f,
    /** Вертикальный мах должен быть длиннее: подъём/опускание руки чаще бывает случайным. */
    val verticalDistanceFactor: Float = 1.3f,
    /** Мёртвая зона: сдвиги центра ладони меньше этого считаются дрожанием и гасятся. */
    val deadZone: Float = 0.012f,
    /** Окно, в котором ищем мах. Медленный дрейф дольше окна свайпом не станет. */
    val windowMs: Long = 400,
    /** Минимальная средняя скорость маха, коротких сторон кадра в секунду. */
    val minVelocity: Float = 0.7f,
    /** Основная ось должна доминировать: |dx| >= axisDominance * |dy| (и наоборот для вертикали). */
    val axisDominance: Float = 1.8f,
    /** Повтор в ту же сторону — не раньше чем через столько (листать подряд можно без пауз). */
    val sameDirectionCooldownMs: Long = 350,
    /** Другие направления после свайпа заблокированы столько — это гасит обратный ход руки… */
    val otherDirectionBlockMs: Long = 700,
    /** …или пока рука не замрёт на столько. */
    val rearmStillMs: Long = 120,
    /** Скорость, ниже которой рука считается неподвижной. */
    val stillVelocity: Float = 0.45f,
    /**
     * Рука только появилась — свайпы не ловим, пока она не замрёт на [rearmStillMs]
     * или не пройдёт столько времени. Иначе подъём руки в кадр = ложный «свайп вверх».
     */
    val armMs: Long = 300,
    /** Сглаживание EMA, 0..1 (1 = без сглаживания). */
    val smoothing: Float = 0.6f,
    val enableVertical: Boolean = true,
    /** true — махать можно и пальцем; false — только ладонью (палец занят курсором). */
    val fingerSwipes: Boolean = true,
    val activationZone: ActivationZone = ActivationZone.FULL,
) {
    val requiredDistance: Float get() = baseDistance / sensitivity.coerceIn(0.25f, 4f)
}

/**
 * Распознавание свайпа.
 *
 * 1. Центр ладони сглаживается EMA, дрожание гасится мёртвой зоной.
 * 2. Рука «взводится»: после появления в кадре нужно на миг замереть (или подождать [SwipeConfig.armMs]).
 * 3. Свайп = быстрый сдвиг по доминирующей оси от любой точки окна до текущей.
 * 4. После свайпа: та же сторона снова доступна через 350 мс (листаем подряд),
 *    обратная — только после паузы руки или через 700 мс (обратный ход не срабатывает).
 *
 * Не потокобезопасен: вызывать из одного потока.
 */
class GestureDetector(config: SwipeConfig = SwipeConfig()) {

    @Volatile
    var config: SwipeConfig = config

    private data class Sample(val t: Long, val x: Float, val y: Float)

    private val history = ArrayDeque<Sample>()
    private var smoothed: Sample? = null
    private var stillSince: Long? = null
    private var validSince: Long? = null
    private var armed = false
    private var lastSwipe: AirGesture? = null
    private var lastSwipeAt = 0L

    /** Последняя сглаженная позиция ладони (нормализованная) — для точки-трекера. */
    var lastPalm: Landmark? = null
        private set

    var lastPose: HandPose = HandPose.OTHER
        private set

    /** Рука пропала из кадра: всё сбрасываем, включая блокировки направлений. */
    fun onHandLost() {
        history.clear()
        smoothed = null
        stillSince = null
        validSince = null
        armed = false
        lastSwipe = null
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
        if (prev != null && hypot(cur.x - prev.x, cur.y - prev.y) < cfg.deadZone) {
            cur = Sample(t, prev.x, prev.y)
        }
        val speed = if (prev != null && t > prev.t) {
            hypot(cur.x - prev.x, cur.y - prev.y) / ((t - prev.t) / 1000f)
        } else 0f
        smoothed = cur
        lastPalm = Landmark(cur.x / frame.xScale, cur.y / frame.yScale)

        if (speed < cfg.stillVelocity) {
            if (stillSince == null) stillSince = t
        } else {
            stillSince = null
        }

        val poseOk = if (cfg.fingerSwipes) pose != HandPose.FIST else pose == HandPose.OPEN_PALM
        if (!poseOk || !cfg.activationZone.contains(raw.y)) {
            history.clear()
            validSince = null
            armed = false
            return null
        }

        if (!armed) {
            val since = validSince ?: t.also { validSince = it }
            val rested = stillSince?.let { t - it >= cfg.rearmStillMs } == true
            if (rested || t - since >= cfg.armMs) {
                armed = true
                history.clear()
            } else {
                return null
            }
        }

        history.addLast(cur)
        while (history.isNotEmpty() && t - history.first().t > cfg.windowMs) history.removeFirst()

        var blocked = false
        var gesture: AirGesture? = null
        for (start in history) {
            val candidate = classifySegment(start, cur, cfg) ?: continue
            if (isAllowed(candidate, t, cfg)) {
                gesture = candidate
                break
            }
            blocked = true
        }

        when {
            gesture != null -> {
                lastSwipe = gesture
                lastSwipeAt = t
                history.clear()
            }
            // Заблокированное движение (обратный ход, хвост того же маха) «съедаем»,
            // чтобы оно не сложилось со следующим.
            blocked -> {
                history.clear()
                history.addLast(cur)
            }
        }
        return gesture
    }

    private fun isAllowed(dir: AirGesture, t: Long, cfg: SwipeConfig): Boolean {
        val last = lastSwipe ?: return true
        val dt = t - lastSwipeAt
        if (dir == last) return dt >= cfg.sameDirectionCooldownMs
        val restedSinceSwipe = stillSince?.let { it >= lastSwipeAt && t - it >= cfg.rearmStillMs } == true
        return dt >= cfg.otherDirectionBlockMs || restedSinceSwipe
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

            cfg.enableVertical && abs(dy) >= required * cfg.verticalDistanceFactor &&
                abs(dy) >= cfg.axisDominance * abs(dx) && abs(dy) / dtSec >= cfg.minVelocity ->
                if (dy < 0) AirGesture.SwipeUp else AirGesture.SwipeDown

            else -> null
        }
    }

    private companion object {
        /** Меньше ~2 кадров при 30 FPS — это скачок трекинга, а не мах. */
        const val MIN_SWIPE_DURATION_MS = 60L
    }
}
