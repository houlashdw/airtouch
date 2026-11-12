package com.airtouch.gesture

import kotlin.math.hypot

/**
 * Какую из рук в кадре слушать. Распознаватель отдаёт до двух рук; чужая рука на заднем плане
 * не должна перехватывать курсор.
 *
 * Правила: пока «своя» рука захвачена, берём ту, что ближе всего к её прошлому положению
 * (и не прыгнула дальше [maxJump]); захват держится [keepMs] после потери. Без захвата — самая
 * крупная ладонь (ближе к камере = владелец). Ладони меньше [minPalm] (далёкие люди) не берём.
 */
class HandPicker(
    private val minPalm: Float = 0.055f,
    private val maxJump: Float = 0.3f,
    private val keepMs: Long = 600,
) {
    private var lockX = 0f
    private var lockY = 0f
    private var lockPalm = 0f
    private var lockAt = Long.MIN_VALUE / 2

    val locked: Boolean get() = lockPalm > 0f

    fun reset() {
        lockPalm = 0f
        lockAt = Long.MIN_VALUE / 2
    }

    /** Индекс выбранной руки в [hands] или -1, если подходящей нет. */
    fun pick(hands: List<HandFrame>, timestampMs: Long): Int {
        if (hands.isEmpty()) {
            if (timestampMs - lockAt > keepMs) lockPalm = 0f
            return -1
        }
        var best = -1
        var bestScore = Float.MAX_VALUE
        val hasLock = lockPalm > 0f && timestampMs - lockAt <= keepMs
        for (i in hands.indices) {
            val h = hands[i]
            val palm = h.dist(Lm.WRIST, Lm.MIDDLE_MCP)
            if (palm < minPalm) continue
            val (ax, ay) = anchor(h)
            val score = if (hasLock) {
                val jump = hypot(ax - lockX, ay - lockY)
                // Далеко от прошлого места — почти наверняка другая рука.
                if (jump > maxJump) continue
                // Своя рука мало меняет размер между кадрами: сильно иной размер — минус.
                jump + kotlin.math.abs(palm - lockPalm) * 2f
            } else {
                -palm // без захвата: крупнее — лучше
            }
            if (score < bestScore) { bestScore = score; best = i }
        }
        if (best < 0 && hasLock) {
            // Своя рука пропала, в кадре только чужие: держим захват, но руку не отдаём.
            return -1
        }
        if (best < 0) return -1
        val h = hands[best]
        val (ax, ay) = anchor(h)
        lockX = ax; lockY = ay
        lockPalm = h.dist(Lm.WRIST, Lm.MIDDLE_MCP)
        lockAt = timestampMs
        return best
    }

    private fun anchor(h: HandFrame): Pair<Float, Float> {
        val lm = h.landmarks
        return ((lm[Lm.INDEX_MCP].x + lm[Lm.WRIST].x) / 2f * h.xScale) to
            ((lm[Lm.INDEX_MCP].y + lm[Lm.WRIST].y) / 2f * h.yScale)
    }
}
