package com.airtouch.core

import com.airtouch.gesture.AirGesture
import com.airtouch.gesture.HandPose
import com.airtouch.gesture.SystemAction
import com.airtouch.keyboard.KeyboardView
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
        /** Подсвечивать руку светлой рамкой экрана в темноте. */
        val screenLight: Boolean = true,
        /** Что сейчас видит камера — для подсказки в приложении. */
        val pose: HandPose? = null,
        /** Курсор (режим курсора), доли экрана. */
        val cursorVisible: Boolean = false,
        val cursorX: Float = 0.5f,
        val cursorY: Float = 0.5f,
        val pinching: Boolean = false,
        /** Идёт удержание позы (кольцо на экране). */
        val holdAction: SystemAction? = null,
        val holdProgress: Float = 0f,
        /** Нижняя панель кнопок (режим «щипок как палец»). */
        val dockVisible: Boolean = false,
        /** Реальная частота обработки кадров и задержка распознавания — для диагностики «тормозов». */
        val fps: Int = 0,
        /** Системная клавиатура открыта — поверх неё рисуем воздушную. Прямоугольник — в пикселях экрана. */
        val keyboardVisible: Boolean = false,
        val kbLeft: Int = 0,
        val kbTop: Int = 0,
        val kbRight: Int = 0,
        val kbBottom: Int = 0,
        val keyboard: KeyboardView? = null,
        /** Курсор над клавиатурой (доли её прямоугольника). */
        val kbCursorX: Float = 0.5f,
        val kbCursorY: Float = 0.5f,
        val kbCursorVisible: Boolean = false,
        val kbPinching: Boolean = false,
        val latencyMs: Int = 0,
        val lastAction: SystemAction? = null,
        val lastActionAt: Long = 0L,
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
    /** В экономичном режиме обрабатываем каждый N-й кадр (~15 FPS вместо 30) — руку подхватываем быстро. */
    const val ECONOMY_FRAME_STRIDE = 2L
    /** Проверяем освещённость раз в N кадров. */
    const val LUMA_EVERY_N_FRAMES = 15L
    /** Средняя яркость 0..255, ниже которой предупреждаем о слабом свете. */
    const val LOW_LIGHT_LUMA = 45f
    /** Ниже этой яркости кадр осветляется перед распознаванием (до ×4). */
    const val BRIGHTEN_BELOW_LUMA = 90f
    const val BRIGHTEN_TARGET_LUMA = 110f
    /** В режиме «камера на максимум» выходим из него только при такой яркости кадра. */
    const val DARK_EXIT_LUMA = 170f
}

/**
 * Пользователь трогает экран пальцами → воздушное управление на паузу, чтобы рука над экраном
 * не превращалась в курсор и щипки. Наши собственные (виртуальные) касания не в счёт.
 */
object TouchGuard {
    /** После настоящего касания молчим столько. */
    const val QUIET_MS = 2000L

    @Volatile var lastRealTouchAt = Long.MIN_VALUE / 2
    @Volatile var injectingUntil = 0L

    fun onOutsideTouch(now: Long) {
        if (now > injectingUntil) lastRealTouchAt = now
    }

    fun quietUntil(): Long = lastRealTouchAt + QUIET_MS
}
