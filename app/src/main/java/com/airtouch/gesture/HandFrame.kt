package com.airtouch.gesture

import kotlin.math.hypot
import kotlin.math.min

/** Одна точка руки MediaPipe в нормализованных координатах кадра (0..1). */
data class Landmark(val x: Float, val y: Float, val z: Float = 0f)

/** Индексы 21 точки MediaPipe Hands. */
object Lm {
    const val WRIST = 0
    const val THUMB_TIP = 4
    const val INDEX_MCP = 5
    const val INDEX_PIP = 6
    const val INDEX_TIP = 8
    const val MIDDLE_MCP = 9
    const val MIDDLE_PIP = 10
    const val MIDDLE_TIP = 12
    const val RING_MCP = 13
    const val RING_PIP = 14
    const val RING_TIP = 16
    const val PINKY_MCP = 17
    const val PINKY_PIP = 18
    const val PINKY_TIP = 20
    const val COUNT = 21
}

/**
 * Наблюдение одной руки в одном кадре.
 *
 * Координаты уже приведены к «зеркалу пользователя»: x растёт слева направо так, как человек
 * видит свою руку перед экраном (для фронтальной камеры кадр отзеркален в [com.airtouch.vision.FrameUtils]).
 *
 * Класс намеренно не зависит от Android и MediaPipe — вся логика жестов тестируется на JVM.
 */
class HandFrame(
    val timestampMs: Long,
    val landmarks: List<Landmark>,
    val imageWidth: Int = 480,
    val imageHeight: Int = 640,
) {
    init {
        require(landmarks.size == Lm.COUNT) { "MediaPipe hand has ${Lm.COUNT} landmarks, got ${landmarks.size}" }
    }

    /** Множители, переводящие нормализованные x/y в единицы «короткой стороны кадра», чтобы оси были сопоставимы. */
    val xScale: Float get() = imageWidth.toFloat() / min(imageWidth, imageHeight)
    val yScale: Float get() = imageHeight.toFloat() / min(imageWidth, imageHeight)

    /** Центр ладони (запястье + основания четырёх пальцев) в нормализованных координатах — стабильнее кончиков пальцев. */
    val palmCenter: Landmark by lazy {
        val ids = intArrayOf(Lm.WRIST, Lm.INDEX_MCP, Lm.MIDDLE_MCP, Lm.RING_MCP, Lm.PINKY_MCP)
        Landmark(
            x = ids.sumOf { landmarks[it].x.toDouble() }.toFloat() / ids.size,
            y = ids.sumOf { landmarks[it].y.toDouble() }.toFloat() / ids.size,
        )
    }

    /** Расстояние между двумя точками в единицах короткой стороны кадра. */
    fun dist(a: Int, b: Int): Float {
        val p = landmarks[a]
        val q = landmarks[b]
        return hypot((p.x - q.x) * xScale, (p.y - q.y) * yScale)
    }
}
