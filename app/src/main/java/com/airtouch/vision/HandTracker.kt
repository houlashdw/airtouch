package com.airtouch.vision

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.airtouch.gesture.FrameMotion
import com.airtouch.gesture.HandFrame
import com.airtouch.gesture.Landmark
import java.util.concurrent.ConcurrentHashMap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult
import java.io.Closeable

/**
 * MediaPipe Gesture Recognizer в режиме LIVE_STREAM: 21 точка руки + обученный классификатор
 * поз (Open_Palm, Closed_Fist, Pointing_Up, Victory, Thumb_Up…). Модель обучена на тысячах рук и
 * различает позы надёжнее самодельных правил. Всё считается на устройстве; GPU → CPU fallback.
 *
 * [onResult] вызывается на внутреннем потоке MediaPipe: null — руки в кадре нет.
 */
class HandTracker(
    context: Context,
    private val onResult: (HandFrame?) -> Unit,
    private val onError: (Throwable) -> Unit,
) : Closeable {

    /** Какой делегат реально работает — показываем в приложении для диагностики. */
    var usingGpu: Boolean = true
        private set

    private val recognizer: GestureRecognizer = try {
        build(context, Delegate.GPU)
    } catch (e: Exception) {
        Log.w(TAG, "GPU delegate unavailable, falling back to CPU", e)
        usingGpu = false
        build(context, Delegate.CPU)
    }

    private fun build(context: Context, delegate: Delegate): GestureRecognizer {
        val base = BaseOptions.builder()
            .setModelAssetPath(MODEL_ASSET)
            .setDelegate(delegate)
            .build()
        val options = GestureRecognizer.GestureRecognizerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(1)
            .setMinHandDetectionConfidence(0.4f)
            .setMinHandPresenceConfidence(0.4f)
            .setMinTrackingConfidence(0.4f)
            .setResultListener { result: GestureRecognizerResult, image: MPImage -> handle(result, image) }
            .setErrorListener { e -> onError(e) }
            .build()
        return GestureRecognizer.createFromOptions(context, options)
    }

    /** Движение телефона на кадре, по метке времени: кладём при отправке, забираем с результатом. */
    private val motions = ConcurrentHashMap<Long, FrameMotion>()

    /** timestampMs должен строго возрастать — это требование LIVE_STREAM. */
    fun detect(bitmap: Bitmap, timestampMs: Long, motion: FrameMotion = FrameMotion.STILL) {
        if (motion !== FrameMotion.STILL) {
            motions[timestampMs] = motion
            if (motions.size > 64) motions.keys.filter { it < timestampMs - 2000 }.forEach { motions.remove(it) }
        }
        recognizer.recognizeAsync(BitmapImageBuilder(bitmap).build(), timestampMs)
    }

    private fun handle(result: GestureRecognizerResult, image: MPImage) {
        val hands = result.landmarks()
        val motion = motions.remove(result.timestampMs()) ?: FrameMotion.STILL
        if (hands.isEmpty()) {
            onResult(null)
            return
        }
        val points = hands[0].map { Landmark(it.x(), it.y(), it.z()) }
        val top = result.gestures().firstOrNull()?.firstOrNull()
        onResult(
            HandFrame(
                timestampMs = result.timestampMs(),
                landmarks = points,
                imageWidth = image.width,
                imageHeight = image.height,
                label = top?.categoryName(),
                labelScore = top?.score() ?: 0f,
                motion = motion,
            ),
        )
    }

    override fun close() = recognizer.close()

    private companion object {
        const val TAG = "AirTouch/HandTracker"
        /** Скачивается Gradle-задачей downloadHandModel при первой сборке. */
        const val MODEL_ASSET = "gesture_recognizer.task"
    }
}
