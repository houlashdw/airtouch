package com.airtouch.vision

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import androidx.camera.core.ImageProxy

object FrameUtils {

    /**
     * Кадр CameraX → вертикальный Bitmap «как видит пользователь».
     * Фронтальная камера отдаёт неотзеркаленный кадр, поэтому зеркалим: тогда движение руки
     * вправо (с точки зрения человека) = рост x, и свайпы не перепутаны.
     */
    fun toUprightBitmap(image: ImageProxy, mirror: Boolean): Bitmap {
        val src = image.toBitmap()
        val rotation = image.imageInfo.rotationDegrees
        if (rotation == 0 && !mirror) return src
        val m = Matrix().apply {
            postRotate(rotation.toFloat())
            if (mirror) postScale(-1f, 1f)
        }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, false)
    }

    /** Средняя яркость 0..255 по сетке grid×grid точек — дёшево, раз в несколько кадров. */
    fun meanLuma(bitmap: Bitmap, grid: Int = 24): Float {
        var sum = 0f
        val stepX = (bitmap.width / grid).coerceAtLeast(1)
        val stepY = (bitmap.height / grid).coerceAtLeast(1)
        var n = 0
        var y = stepY / 2
        while (y < bitmap.height) {
            var x = stepX / 2
            while (x < bitmap.width) {
                val c = bitmap.getPixel(x, y)
                sum += 0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)
                n++
                x += stepX
            }
            y += stepY
        }
        return if (n == 0) 255f else sum / n
    }
}
