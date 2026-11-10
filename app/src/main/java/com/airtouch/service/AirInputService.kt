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
import com.airtouch.gesture.StrokeSynth
import com.airtouch.gesture.Synth
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
    /** Начало росчерка щипком (доли экрана) и было ли это намеренное удержание. */
    private var stroke: Triple<Float, Float, Boolean>? = null
    private val trail = ArrayList<Triple<Float, Float, Long>>()

    /** Весь путь щипка одним жестом с настоящей скоростью — для набора росчерком (Gboard и т.п.). */
    private fun replayTrail() {
        if (trail.size < 2) return
        val pts = trail.map { toScreen(it.first, it.second) }
        val path = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            for (i in 1 until pts.size) if (pts[i] != pts[i - 1]) lineTo(pts[i].x, pts[i].y)
        }
        val duration = (trail.last().third - trail.first().third).coerceIn(150L, 4000L)
        markInjecting()
        dispatchGesture(
            GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build(),
            null, null,
        )
    }
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
        val ime = try {
            windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        } catch (e: Exception) {
            null
        }
        val r = Rect()
        ime?.getBoundsInScreen(r)
        val imeVisible = ime != null && r.width() > 0 && r.height() > 0
        val air = imeVisible && ::settings.isInitialized && settings.airKeyboard
        val screenH = getSystemService(WindowManager::class.java).currentWindowMetrics.bounds.height().coerceAtLeast(1)
        val topFrac = if (imeVisible) r.top.toFloat() / screenH else 2f
        val s = AirTouchState.status.value
        if (s.keyboardVisible == air && s.imeTopFrac == topFrac && s.kbLeft == r.left && s.kbTop == r.top &&
            s.kbRight == r.right && s.kbBottom == r.bottom
        ) return
        AirTouchState.update {
            it.copy(
                keyboardVisible = air, imeTopFrac = topFrac,
                kbLeft = r.left, kbTop = r.top, kbRight = r.right, kbBottom = r.bottom,
            )
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
        stroke = null

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
                    stroke = Triple(event.x, event.y, event.longPress)
                    trail.clear(); trail += Triple(event.x, event.y, android.os.SystemClock.uptimeMillis())
                }
                is TouchEvent.Move -> trail += Triple(event.x, event.y, android.os.SystemClock.uptimeMillis())
                is TouchEvent.Up -> {
                    val s0 = stroke
                    stroke = null
                    trail += Triple(event.x, event.y, android.os.SystemClock.uptimeMillis())
                    if (s0 == null) return@post
                    val imeTop = AirTouchState.status.value.imeTopFrac
                    if (imeTop <= 1f && s0.second >= imeTop && !s0.third) {
                        // Росчерк по обычной клавиатуре — передаём весь путь, слово распознаёт сама клавиатура.
                        replayTrail()
                    } else {
                        play(StrokeSynth.decide(s0.first, s0.second, event.x, event.y, s0.third))
                    }
                }
            }
        }
    }

    /** Отпустить виртуальные пальцы (рука пропала, экран погас, режим выключен). */
    fun cancelTouch() {
        main.post {
            stroke = null
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

    /**
     * Доли экрана → ЦЕЛЫЕ пиксели. Это важно: продолжение жеста (continueStroke) Android принимает,
     * только если его начало в точности совпадает с концом предыдущего куска, а конец он пересчитывает
     * сам (a + (b − a)·t). На дробных координатах это даёт погрешность в 1e-5 пикселя — и система
     * отменяет продолжение («палец отрывается» через 40 мс). На целых числах расчёт точный.
     */
    private fun toScreen(x: Float, y: Float): PointF {
        val b = getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        return PointF(
            Math.round(x * b.width()).coerceIn(1, b.width() - 2).toFloat(),
            Math.round(y * b.height()).coerceIn(1, b.height() - 2).toFloat(),
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
        markInjecting(); dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build(),
            null, null,
        )
    }

    private fun play(synth: Synth) {
        when (synth) {
            is Synth.Flick -> {
                val a = toScreen(synth.x0, synth.y0)
                val b = toScreen(synth.x1, synth.y1)
                val path = Path().apply { moveTo(a.x, a.y); lineTo(b.x, b.y) }
                markInjecting()
                dispatchGesture(
                    GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, FLICK_MS)).build(),
                    null, null,
                )
            }
            is Synth.LongPress -> {
                val p = toScreen(synth.x, synth.y)
                markInjecting()
                dispatchGesture(
                    GestureDescription.Builder()
                        .addStroke(GestureDescription.StrokeDescription(Path().apply { moveTo(p.x, p.y) }, 0, LONG_PRESS_MS))
                        .build(),
                    null, null,
                )
            }
            Synth.Nothing -> Unit
        }
    }

    private fun tap(p: PointF) {
        val path = Path().apply { moveTo(p.x, p.y) }
        markInjecting(); dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, TAP_MS))
                .build(),
            null, null,
        )
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
        @Volatile var replays = 0
        override fun toString() =
            "segments ok=$segmentsCompleted cancelled=$segmentsCancelled rejected=$dispatchFailed replays=$replays"
    }

    /** Наши виртуальные касания тоже вызывают ACTION_OUTSIDE — их не считаем «пальцем на экране». */
    private fun markInjecting() {
        com.airtouch.core.TouchGuard.injectingUntil = android.os.SystemClock.uptimeMillis() + 600
    }

    companion object {
        private const val TAG = "AirTouch/Input"
        private const val TAP_MS = 50L
        /** Длина одного куска перетаскивания; ~частота кадров камеры. */
        private const val FLICK_MS = 180L
        private const val LONG_PRESS_MS = 800L

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
