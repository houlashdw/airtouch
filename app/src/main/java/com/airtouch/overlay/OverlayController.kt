package com.airtouch.overlay

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import com.airtouch.core.AirTouchState
import com.airtouch.core.AirTouchState.Mode
import android.graphics.Path
import android.graphics.RectF
import com.airtouch.keyboard.KeyCode
import com.airtouch.keyboard.KeyboardLayout
import com.airtouch.keyboard.Lang
import com.airtouch.gesture.AirGesture
import com.airtouch.gesture.SystemAction
import com.airtouch.ui.ToggleActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.hypot

/**
 * Два окна поверх всего:
 * 1. Плавающая кнопка (перетаскивается; тап = вкл/выкл режима жестов). Цвет = состояние.
 * 2. Прозрачный некликабельный слой с точкой-трекером ладони и предупреждением о слабом свете.
 *
 * TYPE_ACCESSIBILITY_OVERLAY доступен только из AccessibilityService и считается доверенным
 * окном, поэтому касания сквозь слой трекера проходят без ограничений Android 12+.
 */
class OverlayController(private val service: AccessibilityService) {

    private val wm = service.getSystemService(WindowManager::class.java)
    private val density = service.resources.displayMetrics.density
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var trackerView: TrackerView? = null
    private var buttonView: TextView? = null
    private var keyboardView: AirKeyboardView? = null
    private val kbParams = WindowManager.LayoutParams(
        0, 0,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }
    private lateinit var buttonParams: WindowManager.LayoutParams

    fun show() {
        if (trackerView != null) return
        trackerView = TrackerView(service).also { wm.addView(it, trackerParams()) }
        buttonView = createButton().also { wm.addView(it, buttonParams) }
        scope.launch { AirTouchState.status.collect { render(it) } }
    }

    fun hide() {
        scope.cancel()
        trackerView?.let { runCatching { wm.removeView(it) } }
        buttonView?.let { runCatching { wm.removeView(it) } }
        keyboardView?.let { runCatching { wm.removeView(it) } }
        keyboardView = null
        trackerView = null
        buttonView = null
    }

    private fun render(status: AirTouchState.Status) {
        (buttonView?.background as? GradientDrawable)?.setColor(
            when (status.mode) {
                Mode.OFF -> 0x99505560.toInt()
                Mode.ACTIVE -> if (status.handVisible) 0xE616A34A.toInt() else 0xE622C55E.toInt()
                Mode.IDLE -> 0xE6F59E0B.toInt()
                Mode.PAUSED -> 0xCC3B82F6.toInt()
                Mode.ERROR -> 0xE6DC2626.toInt()
            },
        )
        trackerView?.update(status)
        renderKeyboard(status)
    }

    /** Воздушная клавиатура — ровно поверх системной (её границы приходят из AirInputService). */
    private fun renderKeyboard(s: AirTouchState.Status) {
        val show = s.keyboardVisible && s.isRunning && s.kbRight > s.kbLeft && s.kbBottom > s.kbTop
        if (!show) {
            keyboardView?.let { runCatching { wm.removeView(it) } }
            keyboardView = null
            return
        }
        val w = s.kbRight - s.kbLeft
        val h = s.kbBottom - s.kbTop
        val changed = kbParams.x != s.kbLeft || kbParams.y != s.kbTop || kbParams.width != w || kbParams.height != h
        kbParams.x = s.kbLeft
        kbParams.y = s.kbTop
        kbParams.width = w
        kbParams.height = h
        val view = keyboardView
        if (view == null) {
            keyboardView = AirKeyboardView(service).also { wm.addView(it, kbParams) }
        } else if (changed) {
            wm.updateViewLayout(view, kbParams)
        }
        keyboardView?.update(s)
    }

    private fun trackerParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }

    @SuppressLint("ClickableViewAccessibility") // performClick вызывается вручную в DragOrTap
    private fun createButton(): TextView {
        val size = (56 * density).toInt()
        val bounds = wm.currentWindowMetrics.bounds
        buttonParams = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bounds.width() - size - (8 * density).toInt()
            y = (bounds.height() * 0.35f).toInt()
        }
        return TextView(service).apply {
            text = "✋"
            textSize = 24f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
            elevation = 6 * density
            contentDescription = "AirTouch: включить или выключить жесты"
            setOnTouchListener(DragOrTap())
        }
    }

    private fun toggle() {
        // Через видимую activity: камеру в foreground service можно запустить только с переднего плана.
        service.startActivity(
            Intent(service, ToggleActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
        )
    }

    private inner class DragOrTap : View.OnTouchListener {
        private val slop = ViewConfiguration.get(service).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = buttonParams.x; startY = buttonParams.y
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && hypot(dx, dy) > slop) dragging = true
                    if (dragging) {
                        buttonParams.x = startX + dx.toInt()
                        buttonParams.y = startY + dy.toInt()
                        wm.updateViewLayout(v, buttonParams)
                    }
                }
                MotionEvent.ACTION_UP -> if (!dragging) {
                    v.performClick()
                    toggle()
                }
            }
            return true
        }
    }
}

/** Курсор, точка-трекер ладони, вспышка при свайпе и плашка «мало света». */
private class TrackerView(context: Context) : View(context) {

    private val d = resources.displayMetrics.density
    private var status = AirTouchState.Status()

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC22C55E.toInt() }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3 * d; color = 0xFFFFFFFF.toInt()
    }
    private val flashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF22C55E.toInt() }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textSize = 28 * d; textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val cursorRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3 * d; color = 0xFFFFFFFF.toInt()
    }
    private val cursorFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x993B82F6.toInt() }
    private val cursorPressed = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2563EB.toInt() }
    private val holdTrack = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 6 * d; color = 0x55FFFFFF
    }
    private val holdArc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 6 * d; strokeCap = Paint.Cap.ROUND; color = 0xFFF59E0B.toInt()
    }
    private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textSize = 16 * d; textAlign = Paint.Align.CENTER; isFakeBoldText = true
        setShadowLayer(4 * d, 0f, 0f, 0xCC000000.toInt())
    }
    private val bigLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textSize = 26 * d; textAlign = Paint.Align.CENTER; isFakeBoldText = true
        setShadowLayer(6 * d, 0f, 0f, 0xCC000000.toInt())
    }
    private val arcRect = RectF()
    private val bannerBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6B45309.toInt() }
    private val bannerText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textSize = 14 * d; textAlign = Paint.Align.CENTER
    }

    fun update(s: AirTouchState.Status) {
        status = s
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val s = status

        if (s.isRunning && s.lowLight) {
            val text = "Мало света — жесты распознаются хуже"
            val cx = width / 2f
            val top = 56 * d
            val half = bannerText.measureText(text) / 2 + 16 * d
            canvas.drawRoundRect(cx - half, top, cx + half, top + 32 * d, 16 * d, 16 * d, bannerBg)
            canvas.drawText(text, cx, top + 21 * d, bannerText)
        }

        // Вспышка «Домой / Назад / Недавние» по центру экрана.
        val actionAge = SystemClock.uptimeMillis() - s.lastActionAt
        if (s.lastAction != null && actionAge in 0 until ACTION_FLASH_MS) {
            bigLabel.alpha = (255 * (1 - actionAge / ACTION_FLASH_MS.toFloat())).toInt()
            canvas.drawText(label(s.lastAction), width / 2f, height * 0.45f, bigLabel)
            postInvalidateOnAnimation()
        }

        if (s.mode != Mode.ACTIVE || !s.handVisible) return

        val px = s.palmX * width
        val py = s.palmY * height

        // Кольцо удержания позы.
        if (s.holdAction != null) {
            val r = 28 * d
            arcRect.set(px - r, py - r, px + r, py + r)
            canvas.drawOval(arcRect, holdTrack)
            canvas.drawArc(arcRect, -90f, 360f * s.holdProgress.coerceIn(0f, 1f), false, holdArc)
            canvas.drawText(label(s.holdAction), px, py - r - 10 * d, labelText)
            return
        }


        if (s.keyboardVisible) return // над клавиатурой свой курсор

        if (s.cursorVisible) {
            val cx = s.cursorX * width
            val cy = s.cursorY * height
            if (s.pinching) {
                canvas.drawCircle(cx, cy, 11 * d, cursorPressed)
                canvas.drawCircle(cx, cy, 11 * d, cursorRing)
            } else {
                canvas.drawCircle(cx, cy, 16 * d, cursorFill)
                canvas.drawCircle(cx, cy, 16 * d, cursorRing)
            }
            return
        }

        val x = s.palmX * width
        val y = s.palmY * height

        val age = SystemClock.uptimeMillis() - s.lastGestureAt
        if (s.lastGesture != null && age in 0 until FLASH_MS) {
            val k = age / FLASH_MS.toFloat()
            flashPaint.alpha = (160 * (1 - k)).toInt()
            canvas.drawCircle(x, y, (14 + 46 * k) * d, flashPaint)
            canvas.drawText(arrow(s.lastGesture), x, y - 28 * d, arrowPaint)
            postInvalidateOnAnimation()
        }
        canvas.drawCircle(x, y, 10 * d, dotPaint)
        canvas.drawCircle(x, y, 10 * d, ringPaint)
    }

    private fun label(a: SystemAction) = when (a) {
        SystemAction.HOME -> "⌂ Домой"
        SystemAction.BACK -> "← Назад"
        SystemAction.RECENTS -> "▣ Недавние"
    }

    private fun arrow(g: AirGesture) = when (g) {
        AirGesture.SwipeLeft -> "←"
        AirGesture.SwipeRight -> "→"
        AirGesture.SwipeUp -> "↑"
        AirGesture.SwipeDown -> "↓"
    }

    private companion object {
        const val FLASH_MS = 350L
        const val ACTION_FLASH_MS = 700L
    }
}

/** Воздушная клавиатура: клавиши, подсветка клавиши под рукой, след росчерка, подсказки, курсор. */
private class AirKeyboardView(context: Context) : View(context) {

    private val d = resources.displayMetrics.density
    private var status = AirTouchState.Status()

    private val bg = Paint().apply { color = 0xF2121418.toInt() }
    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2B3038.toInt() }
    private val specialPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1E2228.toInt() }
    private val hoverPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2563EB.toInt() }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textAlign = Paint.Align.CENTER }
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99FFFFFF.toInt(); textAlign = Paint.Align.CENTER }
    private val sugg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val divider = Paint().apply { color = 0x33FFFFFF; strokeWidth = 1 * d }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 6 * d; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        color = 0xCC60A5FA.toInt()
    }
    private val cursorRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3 * d; color = 0xFFFFFFFF.toInt() }
    private val cursorFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x8860A5FA.toInt() }
    private val cursorPressed = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3B82F6.toInt() }
    private val rect = RectF()
    private val path = Path()

    fun update(s: AirTouchState.Status) {
        status = s
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val s = status
        val view = s.keyboard
        val layout = KeyboardLayout.of(view?.lang ?: Lang.RU)
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bg)

        val pad = 3 * d
        val keyH = h / layout.rows
        text.textSize = minOf(keyH * 0.42f, w / layout.cols * 0.6f)
        sugg.textSize = text.textSize * 0.9f
        hint.textSize = text.textSize * 0.7f

        for (key in layout.keys) {
            rect.set((key.cx - key.w / 2) * w + pad, (key.cy - key.h / 2) * h + pad, (key.cx + key.w / 2) * w - pad, (key.cy + key.h / 2) * h - pad)
            val baseline = rect.centerY() + text.textSize * 0.35f
            if (key.code == KeyCode.SUGGESTION) {
                val word = view?.suggestions?.getOrNull(key.index)
                if (word != null) {
                    if (view?.hover == key) canvas.drawRoundRect(rect, 8 * d, 8 * d, hoverPaint)
                    canvas.drawText(word, rect.centerX(), baseline, sugg)
                } else if (key.index == 1) {
                    canvas.drawText("🤏 щипок и веди по буквам", rect.centerX(), baseline, hint)
                }
                if (key.index > 0) canvas.drawLine(rect.left - pad, rect.top + pad, rect.left - pad, rect.bottom - pad, divider)
                continue
            }
            val paint = when {
                view?.hover == key -> hoverPaint
                key.code == KeyCode.CHAR -> keyPaint
                else -> specialPaint
            }
            canvas.drawRoundRect(rect, 8 * d, 8 * d, paint)
            canvas.drawText(key.label, rect.centerX(), baseline, text)
        }

        val stroke = view?.stroke.orEmpty()
        if (stroke.size >= 2) {
            path.reset()
            path.moveTo(stroke[0].first * w, stroke[0].second * h)
            for (i in 1 until stroke.size) path.lineTo(stroke[i].first * w, stroke[i].second * h)
            canvas.drawPath(path, strokePaint)
        }

        if (s.kbCursorVisible) {
            val cx = s.kbCursorX * w
            val cy = s.kbCursorY * h
            if (s.kbPinching) {
                canvas.drawCircle(cx, cy, 9 * d, cursorPressed)
            } else {
                canvas.drawCircle(cx, cy, 13 * d, cursorFill)
            }
            canvas.drawCircle(cx, cy, if (s.kbPinching) 9 * d else 13 * d, cursorRing)
        }
    }
}
