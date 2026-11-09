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
import com.airtouch.gesture.AirGesture
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

        if (s.mode != Mode.ACTIVE || !s.handVisible) return

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

    private fun arrow(g: AirGesture) = when (g) {
        AirGesture.SwipeLeft -> "←"
        AirGesture.SwipeRight -> "→"
        AirGesture.SwipeUp -> "↑"
        AirGesture.SwipeDown -> "↓"
    }

    private companion object {
        const val FLASH_MS = 350L
    }
}
