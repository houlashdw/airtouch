package com.airtouch.service

import android.content.Context
import android.provider.Settings
import android.util.Log

/**
 * Яркость экрана на 100% в темноте (экран подсвечивает руку) и возврат прежней.
 * Нужно разрешение «Изменение системных настроек» (Settings.System.canWrite).
 */
class ScreenBrightness(private val context: Context) {
    private var saved: Pair<Int, Int>? = null

    val allowed: Boolean get() = Settings.System.canWrite(context)

    fun set(max: Boolean) {
        if (!allowed) return
        val cr = context.contentResolver
        try {
            if (max && saved == null) {
                saved = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) to
                    Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128)
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, 255)
            } else if (!max) {
                val s = saved ?: return
                saved = null
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, s.second)
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, s.first)
            }
        } catch (e: Exception) {
            Log.w("AirTouch/Brightness", "brightness change failed", e)
        }
    }
}
