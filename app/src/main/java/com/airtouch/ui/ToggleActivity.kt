package com.airtouch.ui

import android.app.Activity
import android.content.Intent
import com.airtouch.service.AirInputService
import com.airtouch.service.GestureCameraService

/**
 * Прозрачная activity без UI, которую открывает плавающая кнопка.
 *
 * Зачем: Android 11+ даёт доступ к камере из foreground service, только если сервис запущен,
 * пока приложение на переднем плане. Эта activity на ~0.3 с делает нас «видимыми»,
 * запускает/останавливает сервис и закрывается. Пользователь ничего не замечает.
 */
class ToggleActivity : Activity() {

    private var handled = false

    override fun onResume() {
        super.onResume()
        if (handled) return
        handled = true

        when {
            GestureCameraService.isRunning -> GestureCameraService.stop(this)
            !GestureCameraService.hasCameraPermission(this) || !AirInputService.isConnected -> {
                // Чего-то не хватает — открываем мастер настройки.
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            else -> GestureCameraService.start(this)
        }
        // Даём сервису вызвать startForeground, пока мы ещё на переднем плане.
        window.decorView.postDelayed({ finish() }, 300)
    }
}
