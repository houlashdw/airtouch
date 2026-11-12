package com.airtouch.gesture

data class GrabConfig(
    /** Кулак: ладонь уменьшилась до этой доли от размера в момент сжатия — «потянул назад». */
    val pullRatio: Float = 0.74f,
    /** Кулак: ладонь выросла до этого — «толкнул к экрану». */
    val pushRatio: Float = 1.30f,
    /** Потянул и держит кулак вдали столько — вторая ступень (шторка). */
    val holdMs: Long = 700,
    /** Кулак должен продержаться столько кадров, прежде чем начнём мерить глубину (база размера). */
    val armFrames: Int = 3,
    /** Сколько кадров «не кулака» терпим внутри жеста (классификатор моргает). */
    val toleranceFrames: Int = 3,
    val cooldownMs: Long = 600,
    val enabled: Boolean = true,
)

data class GrabResult(
    val action: SystemAction? = null,
    val hold: Hold? = null,
    /** Жест идёт: махи и тык в это время молчат. */
    val active: Boolean = false,
)

/**
 * «Схватил экран» — жесты глубины кулаком:
 *
 *  ✊ и потянуть к себе (от телефона)  → экран «уменьшается»: недавние приложения;
 *  ✊ потянуть и подержать             → шторка уведомлений;
 *  ✊ и толкнуть к телефону            → назад (экран «увеличивается»: обратно в приложение).
 *
 * Глубина = длина ладони в кадре относительно её длины в момент сжатия кулака.
 * Работает любой рукой и из любого места кадра — тянуться никуда не нужно.
 */
class GrabDepthDetector(config: GrabConfig = GrabConfig()) {

    @Volatile
    var config: GrabConfig = config

    private enum class State { IDLE, ARMED, PULLED, DONE }

    private var state = State.IDLE
    private var fistFrames = 0
    private var mismatch = 0
    private var baseLen = 0f
    private var pulledAt = 0L
    private var cooldownUntil = 0L

    val active: Boolean get() = state == State.PULLED || state == State.DONE

    fun reset() {
        state = State.IDLE
        fistFrames = 0
        mismatch = 0
        baseLen = 0f
    }

    fun process(frame: HandFrame, pose: HandPose): GrabResult {
        val cfg = config
        if (!cfg.enabled) { reset(); return GrabResult() }
        val t = frame.timestampMs
        val fist = pose == HandPose.FIST
        if (!fist) {
            if (state == State.IDLE) { fistFrames = 0; return GrabResult() }
            if (++mismatch > cfg.toleranceFrames) { reset(); return GrabResult() }
            return GrabResult(active = true, hold = holdOf(t))
        }
        mismatch = 0
        val len = frame.dist(Lm.WRIST, Lm.MIDDLE_MCP).coerceAtLeast(1e-4f)
        when (state) {
            State.IDLE -> {
                if (t < cooldownUntil) return GrabResult()
                if (++fistFrames >= cfg.armFrames) {
                    state = State.ARMED
                    baseLen = len
                }
                return GrabResult()
            }
            State.ARMED -> {
                val r = len / baseLen
                if (r <= cfg.pullRatio) {
                    state = State.PULLED
                    pulledAt = t
                    return GrabResult(action = SystemAction.RECENTS, active = true)
                }
                if (r >= cfg.pushRatio) {
                    state = State.DONE
                    cooldownUntil = t + cfg.cooldownMs
                    return GrabResult(action = SystemAction.BACK, active = true)
                }
                // Кулак просто держат или машут им — махи кулаком остаются махами.
                return GrabResult()
            }
            State.PULLED -> {
                val r = len / baseLen
                // Вернул руку ближе — ступень «шторка» отменяется, но жест ещё держим до разжатия.
                if (r > (cfg.pullRatio + 1f) / 2f) { state = State.DONE; return GrabResult(active = true) }
                if (t - pulledAt >= cfg.holdMs) {
                    state = State.DONE
                    cooldownUntil = t + cfg.cooldownMs
                    return GrabResult(action = SystemAction.NOTIFICATIONS, active = true)
                }
                return GrabResult(active = true, hold = holdOf(t))
            }
            State.DONE -> return GrabResult(active = true)
        }
    }

    private fun holdOf(t: Long): Hold? =
        if (state == State.PULLED) Hold(SystemAction.NOTIFICATIONS, ((t - pulledAt).toFloat() / config.holdMs).coerceIn(0f, 1f)) else null
}
