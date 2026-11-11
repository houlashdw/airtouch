package com.airtouch.gesture

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Что телефон знал о себе в момент кадра. Чистый Kotlin: сервис заполняет из датчиков,
 * логика курсора читает. Все значения по умолчанию — «телефон неподвижен», так что старые
 * тесты и синтетические кадры ничего не замечают.
 */
data class FrameMotion(
    /**
     * На сколько радиан камера повернулась с прошлого кадра по осям КАДРА (уже с учётом ориентации
     * экрана): [rotX] двигает картинку по горизонтали, [rotY] — по вертикали. Знак не важен —
     * [ShakeCompensator] подбирает его сам.
     */
    val rotX: Float = 0f,
    val rotY: Float = 0f,
    /** Телефон трясётся (идёте, едете) — курсору нужен устойчивый режим. */
    val unsteady: Boolean = false,
) {
    companion object {
        val STILL = FrameMotion()
    }
}

/**
 * Вычитает из движения руки ту часть, которую даёт поворот самого телефона (тряска при ходьбе).
 *
 * Сдвиг картинки от поворота камеры ≈ k·Δугол, где k — фокусное расстояние в единицах кадра,
 * а знак зависит от того, как оси гироскопа легли на кадр (ориентация, зеркало, модель телефона).
 * Чем угадывать, k для каждой оси оценивается на лету методом наименьших квадратов с забыванием:
 * пока рука висит спокойно, а телефон трясётся, сдвиг руки в кадре равен ровно k·Δугол.
 * Когда рука сама идёт, корреляция с гироскопом слабая, и оценка почти не меняется.
 */
class ShakeCompensator(
    /** Начальное предположение |k| (радиан → доли короткой стороны кадра). ~1.2 для фронталки ≈ 75°. */
    private val kPrior: Float = 1.2f,
    /** Поворот за кадр меньше этого — шум гироскопа, по нему k не учим. */
    private val minRot: Float = 0.004f,
    /** Забывание накопленной статистики за кадр (0.97 ≈ окно в ~1 с при 30 к/с). */
    private val decay: Float = 0.97f,
) {
    private class Axis {
        var sxy = 0f
        var sxx = 0f
        var k = 0f
        /** Сколько заметных поворотов уже видели — пока мало, доверяем только слабо. */
        var samples = 0
    }

    private val ax = Axis()
    private val ay = Axis()

    /** Текущие оценки k (для диагностики и тестов). */
    val kX: Float get() = ax.k
    val kY: Float get() = ay.k

    fun reset() {
        for (a in listOf(ax, ay)) { a.sxy = 0f; a.sxx = 0f; a.k = 0f; a.samples = 0 }
    }

    /**
     * [dx]/[dy] — сдвиг руки за кадр (доли короткой стороны кадра), [rotX]/[rotY] — поворот камеры
     * за тот же кадр. Возвращает сдвиг без вклада тряски.
     */
    fun compensate(dx: Float, dy: Float, rotX: Float, rotY: Float): Pair<Float, Float> =
        axis(ax, dx, rotX) to axis(ay, dy, rotY)

    private fun axis(a: Axis, d: Float, r: Float): Float {
        if (abs(r) >= minRot) {
            a.sxy = a.sxy * decay + d * r
            a.sxx = a.sxx * decay + r * r
            a.samples++
            val est = a.sxy / a.sxx
            // Не даём оценке убежать: модуль не больше 2·prior. Сам знак — свободный.
            val lim = kPrior * 2f
            val target = est.coerceIn(-lim, lim)
            // Первые кадры — осторожно: пока статистики мало, k плавно подтягивается к оценке.
            val trust = (a.samples / 8f).coerceAtMost(1f)
            a.k += (target - a.k) * 0.3f * trust
        }
        return d - a.k * r
    }
}

/**
 * «Иду или стою?» по акселерометру. Шаг даёт колебания |a| с размахом в единицы м/с²,
 * в покое в руке — доли. Смотрим стандартное отклонение модуля ускорения за ~1 с, с гистерезисом,
 * чтобы режим не дёргался на каждом шаге.
 */
class WalkDetector(
    private val windowMs: Long = 1200,
    private val onStd: Float = 1.3f,
    private val offStd: Float = 0.7f,
) {
    private val t = ArrayDeque<Long>()
    private val v = ArrayDeque<Float>()
    private var sum = 0.0
    private var sumSq = 0.0

    @Volatile
    var unsteady = false
        private set

    /** Стандартное отклонение модуля ускорения в окне (м/с²) — для диагностики. */
    @Volatile
    var std = 0f
        private set

    @Synchronized
    fun add(timeMs: Long, ax: Float, ay: Float, az: Float) {
        val m = sqrt(ax * ax + ay * ay + az * az)
        t.addLast(timeMs)
        v.addLast(m)
        sum += m
        sumSq += m.toDouble() * m
        while (t.isNotEmpty() && timeMs - t.first() > windowMs) {
            t.removeFirst()
            val old = v.removeFirst()
            sum -= old
            sumSq -= old.toDouble() * old
        }
        val n = v.size
        if (n < 8) return
        val mean = sum / n
        val varr = (sumSq / n - mean * mean).coerceAtLeast(0.0)
        std = sqrt(varr).toFloat()
        unsteady = if (unsteady) std > offStd else std > onStd
    }

    @Synchronized
    fun reset() {
        t.clear(); v.clear(); sum = 0.0; sumSq = 0.0; std = 0f; unsteady = false
    }
}

/**
 * Копилка поворотов гироскопа между кадрами. Датчик пишет сотни раз в секунду со своего потока,
 * камера забирает сумму раз в кадр.
 */
class GyroAccumulator {
    private var hasLast = false
    private var lastT = 0L
    private var rx = 0f
    private var ry = 0f
    private var rz = 0f

    /** [wx]/[wy]/[wz] — угловые скорости (рад/с) по осям устройства, [timeNs] — метка датчика. */
    @Synchronized
    fun add(timeNs: Long, wx: Float, wy: Float, wz: Float) {
        if (hasLast) {
            val dt = ((timeNs - lastT) / 1e9).toFloat().coerceIn(0f, 0.05f)
            rx += wx * dt
            ry += wy * dt
            rz += wz * dt
        }
        hasLast = true
        lastT = timeNs
    }

    /** Накопленный поворот по осям устройства с прошлого вызова; копилка обнуляется. */
    @Synchronized
    fun take(): FloatArray {
        val out = floatArrayOf(rx, ry, rz)
        rx = 0f; ry = 0f; rz = 0f
        return out
    }

    @Synchronized
    fun reset() {
        hasLast = false; lastT = 0L; rx = 0f; ry = 0f; rz = 0f
    }

    companion object {
        /**
         * Оси устройства → оси кадра. Поворот вокруг оси, идущей ВДОЛЬ длинной стороны экрана, сдвигает
         * картинку поперёк неё. [displayRotation] — Surface.ROTATION_0..3 (0/2 — портрет, 1/3 — альбом).
         * Знаки не важны: их подбирает [ShakeCompensator].
         */
        fun toFrameAxes(rot: FloatArray, displayRotation: Int): Pair<Float, Float> =
            if (displayRotation % 2 == 0) rot[1] to rot[0] else rot[0] to rot[1]
    }
}
