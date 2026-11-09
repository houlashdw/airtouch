package com.airtouch.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.airtouch.core.AirTouchState
import com.airtouch.keyboard.TextSink
import com.airtouch.settings.AirTouchSettings
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
    private lateinit var settings: AirTouchSettings

    /** Поле ввода, где стоит курсор, — сюда печатает воздушная клавиатура. */
    val textSink: TextSink = FocusedFieldSink()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        settings = AirTouchSettings(this)
        overlay = OverlayController(this).also { it.show() }
        updateKeyboardState()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> updateKeyboardState()
        }
    }

    /** Открыта системная клавиатура → показываем поверх неё воздушную, в тех же границах. */
    private fun updateKeyboardState() {
        val ime = if (::settings.isInitialized && settings.airKeyboard) {
            try {
                windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            } catch (e: Exception) {
                null
            }
        } else null
        val r = Rect()
        ime?.getBoundsInScreen(r)
        val visible = ime != null && r.width() > 0 && r.height() > 0
        val s = AirTouchState.status.value
        if (s.keyboardVisible == visible && s.kbLeft == r.left && s.kbTop == r.top &&
            s.kbRight == r.right && s.kbBottom == r.bottom
        ) return
        AirTouchState.update {
            it.copy(keyboardVisible = visible, kbLeft = r.left, kbTop = r.top, kbRight = r.right, kbBottom = r.bottom)
        }
    }

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
                    SystemAction.NOTIFICATIONS -> GLOBAL_ACTION_NOTIFICATIONS
                },
            )
        }
    }

    private fun toScreen(x: Float, y: Float): PointF {
        val b = getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        return PointF(
            (x * b.width()).coerceIn(1f, b.width() - 2f),
            (y * b.height()).coerceIn(1f, b.height() - 2f),
        )
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
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        Stats.segmentsCompleted++
                        next()
                    }
                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        Stats.segmentsCancelled++
                        Log.w(TAG, "touch segment cancelled (ending=$ending)")
                        finished = true
                    }
                },
                main,
            )
            if (!ok) {
                Stats.dispatchFailed++
                Log.w(TAG, "dispatchGesture rejected")
                finished = true
            }
        }
    }

    /**
     * Печать в поле ввода через специальные возможности: читаем текст и позицию курсора,
     * вставляем/стираем и ставим курсор после вставки. Работает в обычных полях; в некоторых
     * веб-страницах и играх поля «нестандартные» — там текст может не вставиться.
     */
    private inner class FocusedFieldSink : TextSink {
        private fun field(): AccessibilityNodeInfo? =
            findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.also { it.refresh() }?.takeIf { it.isEditable && !it.isPassword }

        private fun state(n: AccessibilityNodeInfo): Triple<String, Int, Int> {
            val text = if (n.isShowingHintText) "" else n.text?.toString() ?: ""
            var start = n.textSelectionStart
            var end = n.textSelectionEnd
            if (start < 0 || start > text.length) start = text.length
            if (end < start || end > text.length) end = start
            return Triple(text, start, end)
        }

        override fun textBeforeCursor(): String? {
            val n = field() ?: return null
            val (text, start, _) = state(n)
            return text.substring(0, start)
        }

        override fun insert(text: String) {
            val n = field() ?: return
            val (cur, start, end) = state(n)
            set(n, cur.substring(0, start) + text + cur.substring(end), start + text.length)
        }

        override fun deleteBefore(count: Int) {
            val n = field() ?: return
            val (cur, start, end) = state(n)
            if (start != end) {
                set(n, cur.removeRange(start, end), start)
            } else {
                val from = (start - count).coerceAtLeast(0)
                set(n, cur.removeRange(from, start), from)
            }
        }

        override fun enter() {
            field()?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }

        private fun set(n: AccessibilityNodeInfo, text: String, cursor: Int) {
            n.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                },
            )
            n.performAction(
                AccessibilityNodeInfo.ACTION_SET_SELECTION,
                Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
                },
            )
        }
    }

    /** Счётчики для диагностики (сквозные тесты печатают их при ошибке). */
    object Stats {
        @Volatile var segmentsCompleted = 0
        @Volatile var segmentsCancelled = 0
        @Volatile var dispatchFailed = 0
        override fun toString() = "segments ok=$segmentsCompleted cancelled=$segmentsCancelled rejected=$dispatchFailed"
    }

    companion object {
        private const val TAG = "AirTouch/Input"
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
