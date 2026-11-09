package com.airtouch.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.airtouch.gesture.AirGesture
import com.airtouch.gesture.TouchEvent
import com.airtouch.overlay.OverlayController

/**
 * Мост «жест → система». Только выполняет действия, содержимое экрана не читает.
 *
 * - свайпы — через dispatchGesture (работают в любом приложении, как настоящий палец);
 * - курсор: тап, долгое нажатие и перетаскивание — «продолжаемыми» штрихами
 *   (StrokeDescription.willContinue), то есть виртуальный палец лежит на экране, пока сжат щипок;
 * - «назад» — performGlobalAction(GLOBAL_ACTION_BACK) (задел под жест «V»);
 * - плавающая кнопка и точка-трекер — окна TYPE_ACCESSIBILITY_OVERLAY, им не нужно
 *   разрешение «Поверх других приложений».
 */
class AirInputService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private var overlay: OverlayController? = null
    private var drag: DragSession? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        overlay = OverlayController(this).also { it.show() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        drag?.end(null)
        drag = null
        overlay?.hide()
        overlay = null
        if (instance === this) instance = null
        GestureCameraService.stop(this) // без службы жесты некуда отправлять
    }

    /** Можно вызывать с любого потока. */
    fun perform(gesture: AirGesture) {
        main.post {
            when (gesture) {
                // «Палец» движется туда же, куда рука: мах влево = листнуть на следующую страницу.
                AirGesture.SwipeLeft -> swipe(0.80f, 0.50f, 0.20f, 0.50f)
                AirGesture.SwipeRight -> swipe(0.20f, 0.50f, 0.80f, 0.50f)
                AirGesture.SwipeUp -> swipe(0.50f, 0.75f, 0.50f, 0.25f)
                AirGesture.SwipeDown -> swipe(0.50f, 0.25f, 0.50f, 0.75f)
            }
        }
    }

    fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    /** События курсора. Можно вызывать с любого потока. */
    fun touch(event: TouchEvent) {
        main.post {
            val p = toScreen(event.x, event.y)
            when (event) {
                is TouchEvent.Tap -> tap(p)
                is TouchEvent.Down -> {
                    drag?.end(null)
                    drag = DragSession(p).also { it.begin() }
                }
                is TouchEvent.Move -> drag?.moveTo(p)
                is TouchEvent.Up -> {
                    drag?.end(p)
                    drag = null
                }
            }
        }
    }

    /** Отпустить виртуальный палец (рука пропала, экран погас, режим выключен). */
    fun cancelTouch() {
        main.post {
            drag?.end(null)
            drag = null
        }
    }

    private fun toScreen(x: Float, y: Float): PointF {
        val b = getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        return PointF(
            (x * b.width()).coerceIn(1f, b.width() - 2f),
            (y * b.height()).coerceIn(1f, b.height() - 2f),
        )
    }

    private fun tap(p: PointF) {
        val path = Path().apply { moveTo(p.x, p.y) }
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, TAP_MS))
                .build(),
            null, null,
        )
    }

    /**
     * «Палец на экране» на время щипка: цепочка коротких штрихов, каждый продолжает предыдущий.
     * Следующий кусок отправляется, когда система закончила предыдущий (onCompleted),
     * поэтому палец не отрывается, пока сессия не завершена.
     */
    private inner class DragSession(start: PointF) {
        private var last = start
        private var target = start
        private var ending = false
        private var finished = false
        private var stroke: GestureDescription.StrokeDescription? = null

        fun begin() {
            val path = Path().apply { moveTo(last.x, last.y) }
            val s = GestureDescription.StrokeDescription(path, 0, SEGMENT_MS, true)
            stroke = s
            send(s)
        }

        fun moveTo(p: PointF) {
            target = p
        }

        /** p == null — отпустить там, где палец сейчас. */
        fun end(p: PointF?) {
            if (p != null) target = p
            ending = true
        }

        private fun next() {
            if (finished) return
            val prev = stroke ?: return
            val from = last
            val to = target
            val isFinal = ending
            val path = Path().apply {
                moveTo(from.x, from.y)
                lineTo(to.x, to.y)
            }
            val s = prev.continueStroke(path, 0, SEGMENT_MS, !isFinal)
            last = to
            stroke = s
            if (isFinal) finished = true
            send(s)
        }

        private fun send(s: GestureDescription.StrokeDescription) {
            val ok = dispatchGesture(
                GestureDescription.Builder().addStroke(s).build(),
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) = next()
                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        finished = true
                    }
                },
                main,
            )
            if (!ok) finished = true
        }
    }

    /** Координаты — доли экрана. Не начинаем у самого края, чтобы не задеть системный жест «назад». */
    private fun swipe(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long = 200) {
        val bounds = getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        val path = Path().apply {
            moveTo(fromX * w, fromY * h)
            lineTo(toX * w, toY * h)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        dispatchGesture(gesture, null, null)
    }

    companion object {
        private const val TAP_MS = 50L
        /** Длина одного куска перетаскивания; ~частота кадров камеры. */
        private const val SEGMENT_MS = 40L

        @Volatile
        var instance: AirInputService? = null
            private set

        val isConnected: Boolean get() = instance != null

        /** Включена ли служба в системных настройках (для мастера настройки). */
        fun isEnabledInSettings(context: Context): Boolean {
            val me = ComponentName(context, AirInputService::class.java)
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
