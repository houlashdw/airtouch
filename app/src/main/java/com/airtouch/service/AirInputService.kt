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
import com.airtouch.gesture.SystemAction
import com.airtouch.gesture.TouchEvent
import com.airtouch.overlay.OverlayController

/**
 * Мост «жест → система». Только выполняет действия, содержимое экрана не читает.
 *
 * - свайпы — через dispatchGesture (работают в любом приложении, как настоящий палец);
 * - курсор: тап, долгое нажатие и перетаскивание — «продолжаемыми» штрихами
 *   (StrokeDescription.willContinue), то есть виртуальный палец лежит на экране, пока сжат щипок;
 * - домой / назад / недавние — performGlobalAction.
 * - плавающая кнопка и точка-трекер — окна TYPE_ACCESSIBILITY_OVERLAY, им не нужно
 *   разрешение «Поверх других приложений».
 */
class AirInputService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private var overlay: OverlayController? = null
    private var drag: TouchSession? = null

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
                    drag = TouchSession(listOf(p)).also { it.begin() }
                }
                is TouchEvent.Move -> drag?.moveTo(listOf(p))
                is TouchEvent.Up -> {
                    drag?.end(listOf(p))
                    drag = null
                }
            }
        }
    }

    /** Отпустить виртуальные пальцы (рука пропала, экран погас, режим выключен). */
    fun cancelTouch() {
        main.post {
            drag?.end(null)
            drag = null
        }
    }

    /** Домой / назад / недавние. Можно вызывать с любого потока. */
    fun system(action: SystemAction) {
        main.post {
            performGlobalAction(
                when (action) {
                    SystemAction.HOME -> GLOBAL_ACTION_HOME
                    SystemAction.BACK -> GLOBAL_ACTION_BACK
                    SystemAction.RECENTS -> GLOBAL_ACTION_RECENTS
                },
            )
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
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build(),
            null, null,
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
     * Виртуальные пальцы на экране (1 — перетаскивание, 2 — масштаб): цепочка коротких штрихов,
     * каждый продолжает предыдущий (willContinue). Следующий кусок отправляется, когда система
     * закончила предыдущий (onCompleted), поэтому пальцы не отрываются, пока сессия не завершена.
     */
    private inner class TouchSession(start: List<PointF>) {
        private var last = start
        private var target = start
        private var ending = false
        private var finished = false
        private var strokes: List<GestureDescription.StrokeDescription> = emptyList()

        fun begin() {
            strokes = last.map { p ->
                GestureDescription.StrokeDescription(Path().apply { moveTo(p.x, p.y) }, 0, SEGMENT_MS, true)
            }
            send()
        }

        fun moveTo(points: List<PointF>) {
            if (points.size == last.size) target = points
        }

        /** points == null — отпустить там, где пальцы сейчас. */
        fun end(points: List<PointF>?) {
            if (points != null && points.size == last.size) target = points
            ending = true
        }

        private fun next() {
            if (finished || strokes.isEmpty()) return
            val from = last
            val to = target
            val isFinal = ending
            strokes = strokes.mapIndexed { i, prev ->
                val path = Path().apply {
                    moveTo(from[i].x, from[i].y)
                    lineTo(to[i].x, to[i].y)
                }
                prev.continueStroke(path, 0, SEGMENT_MS, !isFinal)
            }
            last = to
            if (isFinal) finished = true
            send()
        }

        private fun send() {
            val builder = GestureDescription.Builder()
            strokes.forEach { builder.addStroke(it) }
            val ok = dispatchGesture(
                builder.build(),
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
