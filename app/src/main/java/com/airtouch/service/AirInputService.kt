package com.airtouch.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.airtouch.gesture.AirGesture
import com.airtouch.overlay.OverlayController

/**
 * Мост «жест → система». Только выполняет действия, содержимое экрана не читает.
 *
 * - свайпы — через dispatchGesture (работают в любом приложении, как настоящий палец);
 * - «назад» — performGlobalAction(GLOBAL_ACTION_BACK) (задел под жест «V»);
 * - плавающая кнопка и точка-трекер — окна TYPE_ACCESSIBILITY_OVERLAY, им не нужно
 *   разрешение «Поверх других приложений».
 */
class AirInputService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private var overlay: OverlayController? = null

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
