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
import com.airtouch.keyboard.Lang
import com.airtouch.gesture.AirGesture
import com.airtouch.gesture.DOCK_ACTIONS
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
    /** Окно 1×1 px, которое узнаёт о любом касании экрана пальцем (ACTION_OUTSIDE). */
    private var touchWatcher: View? = null

    private fun watcherParams() = WindowManager.LayoutParams(
        1, 1,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }
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
        touchWatcher = View(service).also {
            it.setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                    com.airtouch.core.TouchGuard.onOutsideTouch(android.os.SystemClock.uptimeMillis())
                }
                false
            }
            wm.addView(it, watcherParams())
        }
        scope.launch { AirTouchState.status.collect { render(it) } }
    }

    fun hide() {
        scope.cancel()
        trackerView?.let { runCatching { wm.removeView(it) } }
        buttonView?.let { runCatching { wm.removeView(it) } }
        keyboardView?.let { runCatching { wm.removeView(it) } }
        keyboardView = null
        touchWatcher?.let { runCatching { wm.removeView(it) } }
        touchWatcher = null
        trackerView = null
        buttonView = null
    }

    private fun render(status: AirTouchState.Status) {
        (buttonView?.background as? GradientDrawable)?.setColor(
            when (status.mode) {
                Mode.OFF -> 0x66303338
                Mode.ACTIVE -> if (status.handVisible) 0xCC10B981.toInt() else 0x8810B981.toInt()
                Mode.IDLE -> 0x66303338
                Mode.PAUSED -> 0x66303338
                Mode.ERROR -> 0xCCEF4444.toInt()
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
        val size = (34 * density).toInt()
        val bounds = wm.currentWindowMetrics.bounds
        buttonParams = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Полкнопки за левым краем — не перекрывает кнопки приложений (лайк, комментарии справа).
            x = -size / 2
            y = (bounds.height() * 0.22f).toInt()
        }
        return TextView(service).apply {
            text = ""
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setStroke((1.5f * density).toInt(), 0x66FFFFFF)
            }
            alpha = 0.7f
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
    // Курсор — маленькая белая точка с мягкой тенью; при щипке сжимается.
    private val cursorRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = 0x55000000
    }
    private val cursorFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xF2FFFFFF.toInt(); setShadowLayer(4 * d, 0f, 1 * d, 0x66000000)
    }
    private val cursorPressed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); setShadowLayer(6 * d, 0f, 0f, 0xAA3B82F6.toInt())
    }
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
    private val glow = Paint().apply { color = 0xE6FFF6E0.toInt() }
    private val dockBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6121418.toInt() }
    private val dockBtn = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2B3038.toInt() }
    private val dockHover = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2563EB.toInt() }
    private val dockIcon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textSize = 30 * d; textAlign = Paint.Align.CENTER
    }
    private val dockText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt(); textSize = 12 * d; textAlign = Paint.Align.CENTER
    }
    private val dockRect = RectF()
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

        // Темно — светлая рамка по краям экрана подсвечивает руку (как вспышка для селфи).
        if (s.isRunning && s.lowLight && s.screenLight && !s.keyboardVisible) {
            val w = 36 * d
            canvas.drawRect(0f, 0f, width.toFloat(), w, glow)
            canvas.drawRect(0f, height - w, width.toFloat(), height.toFloat(), glow)
            canvas.drawRect(0f, 0f, w, height.toFloat(), glow)
            canvas.drawRect(width - w, 0f, width.toFloat(), height.toFloat(), glow)
        }

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

        if (s.dockVisible) drawDock(canvas, s)

        if (s.cursorVisible) {
            val cx = s.cursorX * width
            val cy = s.cursorY * height
            val r = if (s.pinching) 4.5f * d else 6.5f * d
            canvas.drawCircle(cx, cy, r, if (s.pinching) cursorPressed else cursorFill)
            canvas.drawCircle(cx, cy, r, cursorRing)
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

    /** Нижняя панель: ◁ ○ ▢ 🔔 — большие кнопки, нажимаются щипком. */
    private fun drawDock(canvas: Canvas, s: AirTouchState.Status) {
        val top = height * DOCK_TOP
        canvas.drawRect(0f, top, width.toFloat(), height.toFloat(), dockBg)
        val n = DOCK_ACTIONS.size
        val cellW = width / n.toFloat()
        val pad = 8 * d
        val hovered = if (s.cursorVisible && s.cursorY >= DOCK_TOP) (s.cursorX * n).toInt().coerceIn(0, n - 1) else -1
        DOCK_ACTIONS.forEachIndexed { i, a ->
            dockRect.set(i * cellW + pad, top + pad, (i + 1) * cellW - pad, height - pad - 24 * d)
            canvas.drawRoundRect(dockRect, 16 * d, 16 * d, if (i == hovered) dockHover else dockBtn)
            val icon = when (a) {
                SystemAction.BACK -> "◁"
                SystemAction.HOME -> "○"
                SystemAction.RECENTS -> "▢"
                SystemAction.NOTIFICATIONS -> "🔔"
            }
            val name = when (a) {
                SystemAction.BACK -> "Назад"
                SystemAction.HOME -> "Домой"
                SystemAction.RECENTS -> "Недавние"
                SystemAction.NOTIFICATIONS -> "Уведомления"
            }
            canvas.drawText(icon, dockRect.centerX(), dockRect.centerY() + dockIcon.textSize * 0.2f, dockIcon)
            canvas.drawText(name, dockRect.centerX(), dockRect.bottom - 8 * d, dockText)
        }
    }

    private fun label(a: SystemAction) = when (a) {
        SystemAction.HOME -> "⌂ Домой"
        SystemAction.BACK -> "← Назад"
        SystemAction.RECENTS -> "▢ Недавние"
        SystemAction.NOTIFICATIONS -> "🔔 Уведомления"
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
        /** Совпадает с EngineConfig.dockTop. */
        const val DOCK_TOP = 0.84f
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
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bg)
        val cells = view?.cells ?: com.airtouch.keyboard.ZoneLayout.cells(Lang.RU, emptyList())
        val pad = 4 * d
        val zoneH = h * 0.21f
        text.textSize = zoneH * 0.26f
        sugg.textSize = h * 0.18f * 0.36f
        hint.textSize = sugg.textSize * 0.75f

        cells.forEachIndexed { i, c ->
            rect.set(c.left * w + pad, c.top * h + pad, c.right * w - pad, c.bottom * h - pad)
            val hovered = view?.hover == i
            when (c.kind) {
                com.airtouch.keyboard.KbCell.Kind.SUGGESTION -> {
                    if (c.label.isNotEmpty()) {
                        if (hovered) canvas.drawRoundRect(rect, 12 * d, 12 * d, hoverPaint)
                        else if (c.index == 0) canvas.drawRoundRect(rect, 12 * d, 12 * d, specialPaint)
                        canvas.drawText(c.label, rect.centerX(), rect.centerY() + sugg.textSize * 0.35f, sugg)
                    } else if (c.index == 1 && (view?.typedLetters ?: 0) == 0) {
                        canvas.drawText("🤏 щипок по зоне с буквой", rect.centerX(), rect.centerY() + hint.textSize * 0.35f, hint)
                    }
                }
                com.airtouch.keyboard.KbCell.Kind.ZONE -> {
                    canvas.drawRoundRect(rect, 18 * d, 18 * d, if (hovered) hoverPaint else keyPaint)
                    // Буквы зоны крупно и с разрядкой.
                    canvas.drawText(c.label.toList().joinToString(" "), rect.centerX(), rect.centerY() + text.textSize * 0.35f, text)
                }
                else -> {
                    canvas.drawRoundRect(rect, 12 * d, 12 * d, if (hovered) hoverPaint else specialPaint)
                    canvas.drawText(c.label, rect.centerX(), rect.centerY() + sugg.textSize * 0.35f, sugg)
                }
            }
        }

        // Сколько букв уже набрано — точки над подсказками.
        val n = view?.typedLetters ?: 0
        for (k in 0 until n) canvas.drawCircle(w / 2 - (n - 1) * 7 * d + k * 14 * d, 6 * d, 3 * d, sugg)

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
