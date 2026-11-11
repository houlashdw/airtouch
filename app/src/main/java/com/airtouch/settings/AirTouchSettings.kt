package com.airtouch.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.airtouch.gesture.ActivationZone
import com.airtouch.gesture.ControlMode
import com.airtouch.gesture.CursorConfig
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
        get() = prefs.getBoolean(KEY_CURSOR, false)
        set(v) = prefs.edit { putBoolean(KEY_CURSOR, v) }

    var controlMode: ControlMode
        get() = if (prefs.getString(KEY_MODE, null) == ControlMode.GESTURES.name) ControlMode.GESTURES else ControlMode.PINCH
        set(v) = prefs.edit { putString(KEY_MODE, v.name) }

    var screenLight: Boolean
        get() = prefs.getBoolean(KEY_SCREEN_LIGHT, true)
        set(v) = prefs.edit { putBoolean(KEY_SCREEN_LIGHT, v) }

    var airKeyboard: Boolean
        get() = prefs.getBoolean(KEY_AIR_KEYBOARD, false)
        set(v) = prefs.edit { putBoolean(KEY_AIR_KEYBOARD, v) }

    /** Стабилизация при ходьбе: гироскоп вычитает тряску телефона, акселерометр включает устойчивый режим. */
    var stabilize: Boolean
        get() = prefs.getBoolean(KEY_STABILIZE, true)
        set(v) = prefs.edit { putBoolean(KEY_STABILIZE, v) }

    var invertSwipes: Boolean
        get() = prefs.getBoolean(KEY_INVERT, false)
        set(v) = prefs.edit { putBoolean(KEY_INVERT, v) }

    fun engineConfig(): EngineConfig =
        EngineConfig(
            mode = controlMode, swipe = swipeConfig(), cursorEnabled = cursorEnabled,
            cursor = CursorConfig(shakeCompensation = stabilize),
        )

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
        const val KEY_AIR_KEYBOARD = "air_keyboard"
        const val KEY_MODE = "control_mode"
        const val KEY_SCREEN_LIGHT = "screen_light"
        const val KEY_STABILIZE = "stabilize"
    }
}
