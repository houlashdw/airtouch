package com.airtouch.core

import com.airtouch.gesture.AirGesture
import com.airtouch.gesture.HandPose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Общее состояние в одном процессе: камера-сервис пишет, оверлей и экран настроек читают.
 */
object AirTouchState {

    enum class Mode {
        OFF,
        /** Камера работает на полной частоте. */
        ACTIVE,
        /** Руки нет > [Timeouts.NO_HAND_IDLE_MS]: обрабатываем каждый N-й кадр, точка скрыта. */
        IDLE,
        /** Экран выключен: камера на паузе, включится сама вместе с экраном. */
        PAUSED,
        ERROR,
    }

    data class Status(
        val mode: Mode = Mode.OFF,
        val handVisible: Boolean = false,
        /** Центр ладони в координатах кадра (0..1), уже отзеркален под пользователя. */
        val palmX: Float = 0.5f,
        val palmY: Float = 0.5f,
        val lowLight: Boolean = false,
        /** Что сейчас видит камера — для подсказки в приложении. */
        val pose: HandPose? = null,
        /** Курсор (режим курсора), доли экрана. */
        val cursorVisible: Boolean = false,
        val cursorX: Float = 0.5f,
        val cursorY: Float = 0.5f,
        val pinching: Boolean = false,
        val lastGesture: AirGesture? = null,
        val lastGestureAt: Long = 0L,
        val error: String? = null,
    ) {
        val isRunning: Boolean get() = mode == Mode.ACTIVE || mode == Mode.IDLE || mode == Mode.PAUSED
    }

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    fun update(transform: (Status) -> Status) = _status.update(transform)
}

/** Все тайминги энергосбережения — в одном месте. */
object Timeouts {
    /** Рука не видна столько — переходим в экономичный режим. */
    const val NO_HAND_IDLE_MS = 3_000L
    /** Рука пропала меньше чем на столько — считаем, что это смаз кадра, а не уход из кадра. */
    const val HAND_LOST_GRACE_MS = 300L
    /** В экономичном режиме обрабатываем каждый N-й кадр (~7 FPS вместо 30). */
    const val ECONOMY_FRAME_STRIDE = 4L
    /** Проверяем освещённость раз в N кадров. */
    const val LUMA_EVERY_N_FRAMES = 15L
    /** Средняя яркость 0..255, ниже которой предупреждаем о слабом свете. */
    const val LOW_LIGHT_LUMA = 45f
}
