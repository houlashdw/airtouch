package com.airtouch.gesture

import kotlin.math.abs

/** Системные кнопки, которые вызываются жестами-удержаниями. */
enum class SystemAction { HOME, BACK, RECENTS }

/** Масштаб «нолик + к себе/от себя». factor > 1 — увеличить, < 1 — уменьшить (относительно начала). */
sealed interface ZoomEvent {
    data object Start : ZoomEvent
    data class Update(val factor: Float) : ZoomEvent
    data object End : ZoomEvent
}

/** Идёт удержание позы: кольцо на экране заполняется [progress] 0..1. */
data class Hold(val action: SystemAction, val progress: Float)

data class EngineConfig(
    val swipe: SwipeConfig = SwipeConfig(),
    /** Палец/щипок управляют курсором. Выключено — палец тоже машет. */
    val cursorEnabled: Boolean = true,
    val cursor: CursorConfig = CursorConfig(),
    val homeHoldMs: Long = 600,
    val backHoldMs: Long = 450,
    val recentsHoldMs: Long = 900,
    /** Изменение размера руки (к камере/от камеры), после которого «нолик» = масштаб, а не «домой». */
    val zoomStartChange: Float = 0.12f,
    /** Сколько кадров «чужой» позы терпим внутри жеста (позы на границе иногда мигают). */
    val poseToleranceFrames: Int = 2,
)

data class EngineOutput(
    val swipe: AirGesture? = null,
    val touch: TouchEvent? = null,
    val action: SystemAction? = null,
    val zoom: ZoomEvent? = null,
    val hold: Hold? = null,
)

/**
 * Разводит жесты по позам, чтобы они не путались:
 *
 * | Поза                                | Жест                                              |
 * |-------------------------------------|---------------------------------------------------|
 * | ✋ ладонь                           | мах = свайп ←→↑↓                                  |
 * | ☝️ указательный                     | курсор                                            |
 * | 🤏 щипок (большой + указательный)   | тап / вести = прокрутка / держать = долгое нажатие |
 * | 👌 «нолик» (щипок + 3 пальца вверх) | держать = домой; к себе/от себя = масштаб          |
 * | ✌️ «V»                              | держать = назад                                   |
 * | ✊ кулак                            | держать = недавние приложения                     |
 *
 * Начатый жест (щипок, «нолик», удержание) не прерывается из-за 1–2 кадров с другой позой.
 */
class GestureEngine(config: EngineConfig = EngineConfig()) {

    @Volatile
    var config: EngineConfig = config

    private enum class Route { NONE, SWIPE, CURSOR, OK, HOLD_BACK, HOLD_RECENTS }

    private val swipes = GestureDetector()
    private val pointer = PinchCursor()

    private var route = Route.NONE
    private var mismatch = 0

    // «нолик»
    private var okStartT = 0L
    private var okStartScale = 0f
    private var okScale = 0f
    private var zoomBase = 0f
    private var zooming = false

    // удержания
    private var holdStartT = 0L
    private var fired = false

    /** Центр ладони (кадр, 0..1) — для точки-трекера и кольца удержания. */
    var palm: Landmark? = null
        private set

    var lastPose: HandPose = HandPose.OTHER
        private set

    /** Курсор, если сейчас курсорный режим, иначе null. */
    val cursor: Cursor? get() = if (route == Route.CURSOR) pointer.cursor else null

    val isZooming: Boolean get() = route == Route.OK && zooming

    /** Первый кадр без руки: если это был резкий мах за край кадра — засчитать. */
    fun onHandMissing(t: Long): EngineOutput =
        if (route == Route.SWIPE) EngineOutput(swipe = swipes.checkExitFlick(t)) else EngineOutput()

    /** Рука ушла из кадра: закрыть все незавершённые жесты. */
    fun onHandLost(): EngineOutput {
        val out = endRoute()
        swipes.onHandLost()
        palm = null
        return out
    }

    fun process(frame: HandFrame): EngineOutput {
        val cfg = config
        swipes.config = cfg.swipe.copy(fingerSwipes = !cfg.cursorEnabled)
        pointer.config = cfg.cursor

        val pose = HandPoseClassifier.classify(frame)
        val ext = HandPoseClassifier.extendedFingers(frame)
        val pinch = PinchCursor.isPinchShape(frame, cfg.cursor.pinchOn)
        val okShape = pinch && ext[1] && ext[2] && ext[3]
        val wanted = when {
            okShape -> Route.OK
            pinch -> if (cfg.cursorEnabled) Route.CURSOR else Route.NONE
            pose == HandPose.OPEN_PALM -> Route.SWIPE
            pose == HandPose.V_SIGN -> Route.HOLD_BACK
            pose == HandPose.FIST -> Route.HOLD_RECENTS
            cfg.cursorEnabled -> Route.CURSOR
            else -> Route.SWIPE // курсор выключен: палец тоже машет
        }

        var prefix = EngineOutput()
        if (wanted == route) {
            mismatch = 0
        } else {
            // Не бросаем начатый жест: щипок держим до разжатия, удержания терпят 1–2 «мигания» позы.
            val keep = when (route) {
                Route.CURSOR -> pointer.isTouching
                Route.OK, Route.HOLD_BACK, Route.HOLD_RECENTS -> ++mismatch <= cfg.poseToleranceFrames
                else -> false
            }
            if (!keep) {
                prefix = endRoute()
                startRoute(wanted, frame)
            }
        }
        lastPose = pose

        val out = when (route) {
            Route.SWIPE -> EngineOutput(swipe = swipes.process(frame))
            Route.CURSOR -> EngineOutput(touch = pointer.process(frame))
            Route.OK -> processOk(frame, cfg)
            Route.HOLD_BACK -> processHold(frame.timestampMs, SystemAction.BACK, cfg.backHoldMs)
            Route.HOLD_RECENTS -> processHold(frame.timestampMs, SystemAction.RECENTS, cfg.recentsHoldMs)
            Route.NONE -> EngineOutput()
        }
        palm = if (route == Route.SWIPE) swipes.lastPalm else frame.palmCenter
        return merge(prefix, out)
    }

    private fun startRoute(r: Route, frame: HandFrame) {
        route = r
        mismatch = 0
        fired = false
        when (r) {
            Route.OK -> {
                okStartT = frame.timestampMs
                okStartScale = handScale(frame)
                okScale = okStartScale
                zooming = false
            }
            Route.HOLD_BACK, Route.HOLD_RECENTS -> holdStartT = frame.timestampMs
            else -> Unit
        }
    }

    /** Закрыть текущий жест: отпустить палец, закончить масштаб. */
    private fun endRoute(): EngineOutput {
        val out = when (route) {
            Route.CURSOR -> EngineOutput(touch = pointer.reset())
            Route.OK -> if (zooming) EngineOutput(zoom = ZoomEvent.End) else EngineOutput()
            Route.SWIPE -> {
                swipes.disarm()
                EngineOutput()
            }
            else -> EngineOutput()
        }
        if (route == Route.CURSOR) pointer.reset()
        zooming = false
        route = Route.NONE
        mismatch = 0
        return out
    }

    private fun processOk(frame: HandFrame, cfg: EngineConfig): EngineOutput {
        val t = frame.timestampMs
        okScale += 0.5f * (handScale(frame) - okScale)
        if (fired) return EngineOutput()

        if (!zooming && abs(okScale / okStartScale - 1f) > cfg.zoomStartChange) {
            zooming = true
            zoomBase = okScale
            return EngineOutput(zoom = ZoomEvent.Start)
        }
        if (zooming) {
            val r = okScale / zoomBase
            return EngineOutput(zoom = ZoomEvent.Update((r * r).coerceIn(0.25f, 4f)))
        }
        val progress = (t - okStartT).toFloat() / cfg.homeHoldMs
        if (progress >= 1f) {
            fired = true
            return EngineOutput(action = SystemAction.HOME)
        }
        return EngineOutput(hold = Hold(SystemAction.HOME, progress))
    }

    private fun processHold(t: Long, action: SystemAction, needMs: Long): EngineOutput {
        if (fired) return EngineOutput()
        val progress = (t - holdStartT).toFloat() / needMs
        if (progress >= 1f) {
            fired = true
            return EngineOutput(action = action)
        }
        return EngineOutput(hold = Hold(action, progress))
    }

    /** Размер руки в кадре: растёт, когда рука приближается к камере. */
    private fun handScale(frame: HandFrame): Float = frame.dist(Lm.WRIST, Lm.MIDDLE_MCP).coerceAtLeast(1e-4f)

    private fun merge(a: EngineOutput, b: EngineOutput) = EngineOutput(
        swipe = b.swipe ?: a.swipe,
        touch = a.touch ?: b.touch, // Up старого жеста важнее
        action = b.action ?: a.action,
        zoom = a.zoom ?: b.zoom,
        hold = b.hold,
    )
}
