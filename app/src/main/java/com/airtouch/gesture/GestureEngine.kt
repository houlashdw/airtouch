package com.airtouch.gesture

/** Системные кнопки, которые вызываются жестами. */
enum class SystemAction { HOME, BACK, RECENTS }

/** Идёт удержание: кольцо на экране заполняется [progress] 0..1. */
data class Hold(val action: SystemAction, val progress: Float)

data class EngineConfig(
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
)

data class EngineOutput(
    val swipe: AirGesture? = null,
    val touch: TouchEvent? = null,
    val action: SystemAction? = null,
    val hold: Hold? = null,
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

    /** Центр ладони (кадр, 0..1) — для точки-трекера и кольца удержания. */
    var palm: Landmark? = null
        private set

    var lastPose: HandPose = HandPose.OTHER
        private set

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
        return EngineOutput(touch = up)
    }

    fun process(frame: HandFrame): EngineOutput {
        val cfg = config
        val t = frame.timestampMs
        swipes.config = cfg.swipe.copy(anyPose = true)
        pointer.config = cfg.cursor
        poke.config = cfg.poke

        val pose = HandPoseClassifier.classify(frame)
        lastPose = pose
        palm = frame.palmCenter

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
        var action: SystemAction? = null
        var hold: Hold? = null
        if (pose == HandPose.V_SIGN && swipe == null) {
            vMismatch = 0
            val p = frame.palmCenter.x * frame.xScale to frame.palmCenter.y * frame.yScale
            val start = vStart
            if (vSince == null || start == null ||
                kotlin.math.hypot(p.first - start.first, p.second - start.second) > cfg.backMaxDrift
            ) {
                vSince = t
                vStart = p
            }
            val progress = (t - vSince!!).toFloat() / cfg.backHoldMs
            if (!vFired && progress >= 1f) {
                vFired = true
                action = SystemAction.BACK
            } else if (!vFired && t - vSince!! > 120) {
                hold = Hold(SystemAction.BACK, progress)
            }
        } else if (++vMismatch > cfg.poseToleranceFrames || swipe != null) {
            resetV()
        }

        return EngineOutput(swipe = swipe, touch = up, action = action, hold = hold)
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
