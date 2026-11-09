package com.airtouch.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.airtouch.gesture.ActivationZone
import com.airtouch.gesture.EngineConfig
import com.airtouch.gesture.SwipeConfig

/** Пользовательские настройки. Сервис подписан на изменения и применяет их на лету. */
class AirTouchSettings(context: Context) {

    val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var sensitivity: Float
        get() = prefs.getFloat(KEY_SENSITIVITY, 1f)
        set(v) = prefs.edit { putFloat(KEY_SENSITIVITY, v) }

    var deadZone: Float
        get() = prefs.getFloat(KEY_DEAD_ZONE, SwipeConfig().deadZone)
        set(v) = prefs.edit { putFloat(KEY_DEAD_ZONE, v) }

    var topThirdOnly: Boolean
        get() = prefs.getBoolean(KEY_TOP_THIRD, false)
        set(v) = prefs.edit { putBoolean(KEY_TOP_THIRD, v) }

    var verticalSwipes: Boolean
        get() = prefs.getBoolean(KEY_VERTICAL, true)
        set(v) = prefs.edit { putBoolean(KEY_VERTICAL, v) }

    var useFrontCamera: Boolean
        get() = prefs.getBoolean(KEY_FRONT_CAMERA, true)
        set(v) = prefs.edit { putBoolean(KEY_FRONT_CAMERA, v) }

    var cursorEnabled: Boolean
        get() = prefs.getBoolean(KEY_CURSOR, true)
        set(v) = prefs.edit { putBoolean(KEY_CURSOR, v) }

    var invertSwipes: Boolean
        get() = prefs.getBoolean(KEY_INVERT, false)
        set(v) = prefs.edit { putBoolean(KEY_INVERT, v) }

    fun engineConfig(): EngineConfig = EngineConfig(swipe = swipeConfig(), cursorEnabled = cursorEnabled)

    fun swipeConfig(): SwipeConfig = SwipeConfig(
        sensitivity = sensitivity,
        deadZone = deadZone,
        enableVertical = verticalSwipes,
        activationZone = if (topThirdOnly) ActivationZone.TOP_THIRD else ActivationZone.FULL,
    )

    companion object {
        private const val NAME = "airtouch"
        const val KEY_SENSITIVITY = "sensitivity"
        const val KEY_DEAD_ZONE = "dead_zone"
        const val KEY_TOP_THIRD = "top_third_only"
        const val KEY_VERTICAL = "vertical_swipes"
        const val KEY_FRONT_CAMERA = "front_camera"
        const val KEY_CURSOR = "cursor_mode"
        const val KEY_INVERT = "invert_swipes"
    }
}
