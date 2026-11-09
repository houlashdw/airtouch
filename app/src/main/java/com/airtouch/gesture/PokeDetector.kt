package com.airtouch.gesture

import kotlin.math.hypot

data class PokeConfig(
    /** Тык = рука резко приблизилась к камере: длина И ширина ладони выросли хотя бы во столько раз… */
    val riseRatio: Float = 1.28f,
    val widthRiseRatio: Float = 1.2f,
    /** …не дольше чем за это время (медленно поднести руку ближе — не тык). */
    val riseWindowMs: Long = 300,
    /** Сдвиг вбок во время тыка, в длинах ладони. Больше — это мах, а не тык. */
    val maxLateral: Float = 0.35f,
    /** Рука вернулась (размер упал ниже base × этого) — тык завершён → «домой». */
    val releaseRatio: Float = 1.08f,
    /** Ткнул и держит руку у камеры столько — «недавние приложения». */
    val holdMs: Long = 600,
    /** Рука должна побыть в кадре столько, прежде чем ловить тык (стабильная «база»). */
    val armMs: Long = 300,
    val cooldownMs: Long = 700,
)

data class PokeResult(
    val action: SystemAction? = null,
    val hold: Hold? = null,
    /** Тык в процессе — махи и курсор в это время не ловим. */
    val active: Boolean = false,
)

/**
 * «Тык» — движение руки к экрану, любой формой руки.
 * Отличаем от поворота кисти: при повороте меняется только длина ИЛИ только ширина ладони,
 * при приближении к камере — обе.
 */
class PokeDetector(config: PokeConfig = PokeConfig()) {

    @Volatile
    var config: PokeConfig = config

    private data class S(val t: Long, val len: Float, val wid: Float, val x: Float, val y: Float)
    private enum class State { IDLE, PUSHED, DONE }

    private val history = ArrayDeque<S>()
    private var visibleSince: Long? = null
    private var state = State.IDLE
    private var pushedAt = 0L
    private var baseLen = 0f
    private var cooldownUntil = 0L

    fun reset() {
        history.clear()
        visibleSince = null
        state = State.IDLE
    }

    fun process(frame: HandFrame): PokeResult {
        val cfg = config
        val t = frame.timestampMs
        val len = frame.dist(Lm.WRIST, Lm.MIDDLE_MCP).coerceAtLeast(1e-4f)
        val wid = frame.dist(Lm.INDEX_MCP, Lm.PINKY_MCP).coerceAtLeast(1e-4f)
        val c = frame.palmCenter
        val cur = S(t, len, wid, c.x * frame.xScale, c.y * frame.yScale)
        if (visibleSince == null) visibleSince = t
        history.addLast(cur)
        while (history.isNotEmpty() && t - history.first().t > cfg.riseWindowMs) history.removeFirst()

        when (state) {
            State.IDLE -> {
                if (t < cooldownUntil || t - visibleSince!! < cfg.armMs) return PokeResult()
                val base = history.minByOrNull { it.len } ?: return PokeResult()
                val lateral = hypot(cur.x - base.x, cur.y - base.y) / base.len
                if (len / base.len >= cfg.riseRatio && wid / base.wid >= cfg.widthRiseRatio && lateral <= cfg.maxLateral) {
                    state = State.PUSHED
                    pushedAt = t
                    baseLen = base.len
                    return PokeResult(active = true)
                }
                return PokeResult()
            }
            State.PUSHED -> {
                if (len / baseLen < cfg.releaseRatio) {
                    finish(t)
                    return PokeResult(action = SystemAction.HOME)
                }
                val held = t - pushedAt
                if (held >= cfg.holdMs) {
                    state = State.DONE
                    return PokeResult(action = SystemAction.RECENTS, active = true)
                }
                // Кольцо «недавние» показываем не сразу, чтобы быстрый тык не мигал им.
                val hold = if (held > 150) Hold(SystemAction.RECENTS, held.toFloat() / cfg.holdMs) else null
                return PokeResult(hold = hold, active = true)
            }
            State.DONE -> {
                if (len / baseLen < cfg.releaseRatio) finish(t)
                return PokeResult(active = state == State.DONE)
            }
        }
    }

    private fun finish(t: Long) {
        state = State.IDLE
        cooldownUntil = t + config.cooldownMs
        history.clear()
    }
}
