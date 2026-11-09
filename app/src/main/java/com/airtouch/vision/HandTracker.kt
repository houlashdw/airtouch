package com.airtouch.vision

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.airtouch.gesture.HandFrame
import com.airtouch.gesture.Landmark
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.io.Closeable

/**
 * Обёртка над MediaPipe Hand Landmarker в режиме LIVE_STREAM.
 * Всё считается на устройстве; сначала пробуем GPU, при неудаче — CPU.
 *
 * [onResult] вызывается на внутреннем потоке MediaPipe: null — руки в кадре нет.
 */
class HandTracker(
    context: Context,
    private val onResult: (HandFrame?) -> Unit,
    private val onError: (Throwable) -> Unit,
) : Closeable {

    private val landmarker: HandLandmarker = try {
        build(context, Delegate.GPU)
    } catch (e: Exception) {
        Log.w(TAG, "GPU delegate unavailable, falling back to CPU", e)
        build(context, Delegate.CPU)
    }

    private fun build(context: Context, delegate: Delegate): HandLandmarker {
        val base = BaseOptions.builder()
            .setModelAssetPath(MODEL_ASSET)
            .setDelegate(delegate)
            .build()
        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(1)
            .setMinHandDetectionConfidence(0.5f)
            .setMinHandPresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setResultListener { result: HandLandmarkerResult, image: MPImage -> handle(result, image) }
            .setErrorListener { e -> onError(e) }
            .build()
        return HandLandmarker.createFromOptions(context, options)
    }

    /** timestampMs должен строго возрастать — это требование LIVE_STREAM. */
    fun detect(bitmap: Bitmap, timestampMs: Long) {
        landmarker.detectAsync(BitmapImageBuilder(bitmap).build(), timestampMs)
    }

    private fun handle(result: HandLandmarkerResult, image: MPImage) {
        val hands = result.landmarks()
        if (hands.isEmpty()) {
            onResult(null)
            return
        }
        val points = hands[0].map { Landmark(it.x(), it.y(), it.z()) }
        onResult(HandFrame(result.timestampMs(), points, image.width, image.height))
    }

    override fun close() = landmarker.close()

    private companion object {
        const val TAG = "AirTouch/HandTracker"
        /** Скачивается Gradle-задачей downloadHandModel при первой сборке. */
        const val MODEL_ASSET = "hand_landmarker.task"
    }
}
