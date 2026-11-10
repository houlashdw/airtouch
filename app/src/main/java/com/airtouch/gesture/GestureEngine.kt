package com.airtouch.gesture

/** Системные кнопки. */
enum class SystemAction { HOME, BACK, RECENTS, NOTIFICATIONS }

/**
 * PINCH — «щипок как палец» (как Vision Pro / Quest): рука двигает курсор, всё делает только щипок,
 *         системные кнопки — на панели, которая выезжает, если опустить курсор к нижнему краю.
 *         Случайных срабатываний нет: без щипка ничего не нажимается.
 * GESTURES — жесты махами (мах, тык, «V»).
 */
enum class ControlMode { PINCH, GESTURES }

/** Кнопки нижней панели слева направо. */
val DOCK_ACTIONS = listOf(SystemAction.BACK, SystemAction.HOME, SystemAction.RECENTS, SystemAction.NOTIFICATIONS)

/** Идёт удержание: кольцо на экране заполняется [progress] 0..1. */
data class Hold(val action: SystemAction, val progress: Float)

data class EngineConfig(
    val mode: ControlMode = ControlMode.PINCH,
    /** Курсор у самого низа экрана столько — выезжает панель кнопок. */
    val dockEnterY: Float = 0.97f,
    val dockDwellMs: Long = 300,
    /**
     * Панель выезжает, только если курсор упёрся в низ И рука продолжила вести вниз ещё на столько
     * (доли экрана). Просто задержаться курсором внизу при листании — мало.
     */
    val dockPush: Float = 0.25f,
    /** Курсор поднялся выше — панель прячется. */
    val dockExitY: Float = 0.78f,
    /** Верхняя граница панели (доля экрана). */
    val dockTop: Float = 0.84f,
    val swipe: SwipeConfig = SwipeConfig(),
    /** Режим курсора: ☝️ палец — курсор, 🤏 щипок — нажатие. Выключен — все позы машут. */
    val cursorEnabled: Boolean = false,
    val cursor: CursorConfig = CursorConfig(),
    val poke: PokeConfig = PokeConfig(),
    /** ✌️ неподвижно столько — «назад». */
    val backHoldMs: Long = 450,
    /** Насколько «V» может сдвинуться и всё ещё считаться неподвижной (доли короткой стороны кадра). */
    val backMaxDrift: Float = 0.06f,
    /** Сколько кадров «чужой» позы терпим внутри удержания «V». */
    val poseToleranceFrames: Int = 2,
    /** Рука ближе этого (длина ладони в долях короткой стороны кадра) — это рука у экрана, а не жест. */
    val maxPalmSize: Float = 0.32f,
    /** Рука должна целиком быть в кадре (с таким отступом от краёв). */
    val edgeMargin: Float = 0.02f,
    /** Рука должна спокойно побыть в кадре столько, прежде чем начнёт управлять. */
    val engageMs: Long = 500,
)

data class EngineOutput(
    val swipe: AirGesture? = null,
    val touch: TouchEvent? = null,
    val action: SystemAction? = null,
    val hold: Hold? = null,
    /** Показана нижняя панель кнопок (режим PINCH). */
    val dock: Boolean = false,
)

/**
 * Жесты построены на ДВИЖЕНИИ, а не на форме руки — так их невозможно спутать:
 *
 * | Движение                         | Действие                 |
 * |----------------------------------|--------------------------|
 * | мах любой формой руки            | свайп ←→↑↓               |
 * | тык (рука к экрану и обратно)    | домой                    |
 * | тык и задержать руку у экрана    | недавние приложения      |
 * | ✌️ неподвижно                    | назад                    |
 * | режим курсора: ☝️ / 🤏           | курсор / нажатие, прокрутка, долгое нажатие |
 *
 * Приоритет: тык > курсор > мах > «V». Во время тыка махи не ловятся.
 */
class GestureEngine(config: EngineConfig = EngineConfig()) {

    @Volatile
    var config: EngineConfig = config

    private val swipes = GestureDetector()
    private val pointer = PinchCursor()
    private val poke = PokeDetector()
    private var cursorMode = false

    private var vSince: Long? = null
    private var vStart: Pair<Float, Float>? = null
    private var vFired = false
    private var vMismatch = 0

    private var dockVisible = false
    private var dockSince: Long? = null

    /** Центр ладони (кадр, 0..1) — для точки-трекера и кольца удержания. */
    var palm: Landmark? = null
        private set

    var lastPose: HandPose = HandPose.OTHER
        private set

    /** Открыта воздушная клавиатура: рука — курсор по клавишам, щипок — печать; махи и тык выключены. */
    @Volatile
    var keyboardMode = false

    /** Курсор, если сейчас курсорный режим, иначе null. */
    val cursor: Cursor? get() = if (cursorMode) pointer.cursor else null

    /** Первый кадр без руки: если это был резкий мах за край кадра — засчитать. */
    fun onHandMissing(t: Long): EngineOutput =
        if (!cursorMode) EngineOutput(swipe = swipes.checkExitFlick(t)) else EngineOutput()

    /** Рука ушла из кадра: закрыть все незавершённые жесты. */
    fun onHandLost(): EngineOutput {
        val up = pointer.reset()
        cursorMode = false
        swipes.onHandLost()
        poke.reset()
        resetV()
        palm = null
        dockVisible = false
        dockSince = null
        return EngineOutput(touch = up)
    }

    private var validSince: Long? = null

    /** Пауза снаружи: пользователь трогает экран пальцами — воздушное управление молчит. */
    @Volatile
    var suppressedUntil = 0L

    /**
     * Похоже ли это на жест в воздухе. Отсекаем то, что видно, когда человек просто пользуется
     * телефоном: рука вплотную к камере (огромная), рука срезана краем кадра (палец над экраном).
     */
    fun isAirHand(frame: HandFrame, cfg: EngineConfig = config): Boolean {
        if (frame.dist(Lm.WRIST, Lm.MIDDLE_MCP) > cfg.maxPalmSize) return false
        val m = cfg.edgeMargin
        return frame.landmarks.all { it.x in m..(1 - m) && it.y in m..(1 - m) }
    }

    fun process(frame: HandFrame): EngineOutput {
        val cfg = config
        val t = frame.timestampMs
        if (t < suppressedUntil || !isAirHand(frame, cfg)) {
            validSince = null
            return onHandLost()
        }
        val since = validSince ?: t.also { validSince = it }
        if (t - since < cfg.engageMs) {
            palm = frame.palmCenter
            return EngineOutput()
        }
        swipes.config = cfg.swipe.copy(anyPose = true)
        pointer.config = cfg.cursor
        poke.config = cfg.poke

        val pose = HandPoseClassifier.classify(frame)
        lastPose = pose
        palm = frame.palmCenter

        if (keyboardMode) {
            poke.reset()
            if (!cursorMode) {
                cursorMode = true
                swipes.disarm()
            }
            val touch = pointer.process(frame)
            val (action, hold) = if (pointer.isTouching) null to null else backHold(pose, frame, cfg)
            return EngineOutput(touch = touch, action = action, hold = hold)
        }

        if (cfg.mode == ControlMode.PINCH) return processPinch(frame, cfg)

        // 1. Тык — любой формой руки. Пока он идёт, всё остальное молчит.
        val pk = poke.process(frame)
        if (pk.active || pk.action != null) {
            swipes.disarm()
            resetV()
            val up = if (cursorMode && !pointer.isTouching) leaveCursor() else null
            return EngineOutput(touch = up, action = pk.action, hold = pk.hold)
        }

        // 2. Режим курсора: палец или щипок.
        val pinch = PinchCursor.isPinchShape(frame, cfg.cursor.pinchOn)
        if (cfg.cursorEnabled && (pointer.isTouching || pinch || pose == HandPose.POINTING)) {
            if (!cursorMode) {
                cursorMode = true
                swipes.disarm()
            }
            resetV()
            return EngineOutput(touch = pointer.process(frame))
        }
        val up = if (cursorMode) leaveCursor() else null

        // 3. Мах любой формой.
        val swipe = swipes.process(frame)
        palm = swipes.lastPalm ?: palm

        // 4. ✌️ неподвижно — назад.
        if (swipe != null) resetV()
        val (action, hold) = if (swipe == null) backHold(pose, frame, cfg) else null to null
        return EngineOutput(swipe = swipe, touch = up, action = action, hold = hold)
    }

    private fun backHold(pose: HandPose, frame: HandFrame, cfg: EngineConfig): Pair<SystemAction?, Hold?> {
        val t = frame.timestampMs
        if (pose != HandPose.V_SIGN) {
            if (++vMismatch > cfg.poseToleranceFrames) resetV()
            return null to null
        }
        vMismatch = 0
        val p = frame.palmCenter.x * frame.xScale to frame.palmCenter.y * frame.yScale
        val start = vStart
        if (vSince == null || start == null ||
            kotlin.math.hypot(p.first - start.first, p.second - start.second) > cfg.backMaxDrift
        ) {
            vSince = t
            vStart = p
        }
        val since = vSince!!
        val progress = (t - since).toFloat() / cfg.backHoldMs
        return when {
            vFired -> null to null
            progress >= 1f -> {
                vFired = true
                SystemAction.BACK to null
            }
            t - since > 120 -> null to Hold(SystemAction.BACK, progress)
            else -> null to null
        }
    }

    /** Режим «щипок как палец». */
    private fun processPinch(frame: HandFrame, cfg: EngineConfig): EngineOutput {
        val t = frame.timestampMs
        poke.reset()
        resetV()
        if (!cursorMode) {
            cursorMode = true
            swipes.disarm()
        }
        var touch = pointer.process(frame)
        val c = pointer.cursor

        // Панель кнопок: курсор «упёрся» в низ и задержался. Во время перетаскивания не показываем.
        if (c != null) {
            if (!dockVisible) {
                val pushed = if (cfg.cursor.relative) pointer.bottomPush >= cfg.dockPush else true
                if (c.y >= cfg.dockEnterY && pushed && !pointer.isTouching) {
                    val since = dockSince ?: t.also { dockSince = it }
                    if (t - since >= cfg.dockDwellMs) dockVisible = true
                } else {
                    dockSince = null
                }
            } else if (c.y < cfg.dockExitY && !pointer.isTouching) {
                dockVisible = false
                dockSince = null
            }
        }

        var action: SystemAction? = null
        if (dockVisible && touch != null && touch.y >= cfg.dockTop) {
            if (touch is TouchEvent.Tap) {
                action = DOCK_ACTIONS[(touch.x * DOCK_ACTIONS.size).toInt().coerceIn(0, DOCK_ACTIONS.size - 1)]
                dockVisible = false
                dockSince = null
            }
            touch = null // по панели не «тапаем» и не тянем приложение
        }
        return EngineOutput(touch = touch, action = action, dock = dockVisible)
    }

    private fun leaveCursor(): TouchEvent? {
        cursorMode = false
        return pointer.reset()
    }

    private fun resetV() {
        vSince = null
        vStart = null
        vFired = false
        vMismatch = 0
    }
}
