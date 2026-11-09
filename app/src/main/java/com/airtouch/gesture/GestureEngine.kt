package com.airtouch.gesture

data class EngineConfig(
    val swipe: SwipeConfig = SwipeConfig(),
    /** Режим курсора: палец/щипок управляют курсором, махи — только ладонью. */
    val cursorEnabled: Boolean = false,
    val cursor: CursorConfig = CursorConfig(),
)

data class EngineOutput(val swipe: AirGesture? = null, val touch: TouchEvent? = null)

/**
 * Решает, какой жест сейчас показывают, чтобы они не путались между собой:
 *
 * | Рука                         | Курсор выкл.   | Курсор вкл.                     |
 * |------------------------------|----------------|---------------------------------|
 * | Ладонь                       | махи           | махи                            |
 * | Палец / прочее               | махи           | курсор                          |
 * | Щипок (большой+указательный) | махи           | нажатие / прокрутка / удержание |
 * | Кулак                        | ничего         | ничего                          |
 *
 * Начатый щипок не прерывается, даже если поза «поплыла», пока пальцы не разжаты.
 */
class GestureEngine(config: EngineConfig = EngineConfig()) {

    @Volatile
    var config: EngineConfig = config

    private val swipes = GestureDetector()
    private val pointer = PinchCursor()
    private var cursorMode = false

    val lastPalm: Landmark? get() = swipes.lastPalm
    val lastPose: HandPose get() = swipes.lastPose

    /** Курсор, если сейчас курсорный режим, иначе null. */
    val cursor: Cursor? get() = if (cursorMode) pointer.cursor else null

    /** Рука ушла из кадра. Если «палец лежал» на экране — вернёт Up. */
    fun onHandLost(): TouchEvent? {
        swipes.onHandLost()
        cursorMode = false
        return pointer.reset()
    }

    fun process(frame: HandFrame): EngineOutput {
        val cfg = config
        swipes.config = cfg.swipe.copy(fingerSwipes = !cfg.cursorEnabled)
        pointer.config = cfg.cursor

        if (!cfg.cursorEnabled) {
            val up = if (cursorMode) pointer.reset() else null
            cursorMode = false
            return EngineOutput(swipe = swipes.process(frame), touch = up)
        }

        val pose = HandPoseClassifier.classify(frame)
        val pinchShape = PinchCursor.pinchRatio(frame) < cfg.cursor.pinchOn
        val busy = pointer.isTouching

        // Ладонь → махи. Детектор свайпов видит каждый кадр, чтобы корректно «взводиться».
        val swipe = swipes.process(frame)
        if (!busy && !pinchShape && pose == HandPose.OPEN_PALM) {
            val up = if (cursorMode) pointer.reset() else null
            cursorMode = false
            return EngineOutput(swipe = swipe, touch = up)
        }

        if (!busy && !pinchShape && pose == HandPose.FIST) {
            cursorMode = false
            pointer.reset()
            return EngineOutput()
        }

        cursorMode = true
        return EngineOutput(touch = pointer.process(frame))
    }
}
