package com.airtouch.keyboard

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/** Слово словаря: [zipf] — частотность по шкале Zipf (≈1 редкое … 7 очень частое). */
data class DictWord(val word: String, val zipf: Float)

/**
 * Скользящий ввод: по росчерку через клавиши угадываем слово.
 *
 * Классическая схема SHARK²: у каждого слова есть «эталонный» росчерк — ломаная через центры
 * его букв. Росчерк пользователя сравниваем с эталонами по
 *  - положению (насколько близко прошли к нужным клавишам),
 *  - форме (похож ли рисунок, даже если весь росчерк сдвинут),
 *  - покрытию (прошёл ли росчерк рядом с КАЖДОЙ буквой слова),
 * и добавляем частотность слова. Поэтому точно попадать в клавиши не нужно.
 *
 * Кандидаты сначала отсекаются по первой и последней букве (рядом с началом/концом росчерка),
 * так что на 30 000 слов уходят миллисекунды.
 */
/** Веса оценки; подобраны на синтетических «неряшливых» росчерках (см. GlideDecoderTest). */
data class DecoderParams(
    val sigmaLoc: Float = 0.55f,
    val sigmaShape: Float = 0.12f,
    val coverageWeight: Float = 1.5f,
    val coverageTolerance: Float = 0.65f,
    val lengthWeight: Float = 1.2f,
    val endsWeight: Float = 0.6f,
    val freqWeight: Float = 1.2f,
)

class GlideDecoder(
    private val layout: KeyboardLayout,
    dictionary: List<DictWord>,
    private val p: DecoderParams = DecoderParams(),
) {

    private class Entry(val word: String, val zipf: Float, val keysPath: String) {
        var template: FloatArray? = null
        var templateLength = 0f
    }

    private val byEnds: Map<Long, List<Entry>>

    init {
        val map = HashMap<Long, MutableList<Entry>>()
        for (dw in dictionary) {
            val norm = normalize(dw.word)
            if (norm.length < 2 || norm.any { it !in layout.letterUnits }) continue
            // Двойные буквы («сс», «ll») на клавиатуре — одна точка.
            val path = buildString { norm.forEach { if (isEmpty() || last() != it) append(it) } }
            map.getOrPut(endsKey(path.first(), path.last())) { mutableListOf() } += Entry(dw.word, dw.zipf, path)
        }
        byEnds = map
    }

    val size: Int get() = byEnds.values.sumOf { it.size }

    /** [stroke] — точки в нормализованных координатах клавиатуры (0..1). Возвращает до [n] слов, лучшее первым. */
    fun decode(stroke: List<Pair<Float, Float>>, n: Int = 3): List<String> {
        if (stroke.size < 2) return emptyList()
        val pts = stroke.map { layout.toUnits(it.first, it.second) }
        val user = resample(pts, N)
        val userLen = pathLength(pts)
        val userNorm = normalizeShape(user)

        val starts = nearLetters(pts.first())
        val ends = nearLetters(pts.last())

        val scored = ArrayList<Pair<String, Float>>()
        for ((f, df) in starts) for ((l, dl) in ends) {
            val list = byEnds[endsKey(f, l)] ?: continue
            for (e in list) {
                val tpl = template(e)
                val loc = meanDist(user, tpl)
                val shape = meanDist(userNorm, normalizeShape(tpl))
                val coverage = coveragePenalty(pts, e.keysPath)
                val lenRatio = ln(max(userLen, 0.1f) / max(e.templateLength, 0.1f))
                val score = -(loc * loc) / (2 * p.sigmaLoc * p.sigmaLoc) -
                    (shape * shape) / (2 * p.sigmaShape * p.sigmaShape) -
                    coverage * p.coverageWeight -
                    abs(lenRatio) * p.lengthWeight -
                    (df + dl) * p.endsWeight +
                    e.zipf * p.freqWeight
                scored += e.word to score
            }
        }
        return scored.sortedByDescending { it.second }.map { it.first }.distinct().take(n)
    }

    // ---------------- геометрия ----------------

    private fun nearLetters(p: Pair<Float, Float>): List<Pair<Char, Float>> =
        layout.letterUnits.map { (c, k) -> c to hypot(k.first - p.first, k.second - p.second) }
            .filter { it.second <= END_RADIUS }
            .sortedBy { it.second }
            .take(MAX_END_KEYS)

    private fun template(e: Entry): FloatArray {
        e.template?.let { return it }
        val pts = e.keysPath.map { layout.letterUnits.getValue(it) }
        e.templateLength = pathLength(pts)
        return resample(pts, N).also { e.template = it }
    }

    /** Насколько росчерк «промахнулся» мимо букв слова: сумма превышений допуска. */
    private fun coveragePenalty(stroke: List<Pair<Float, Float>>, path: String): Float {
        var sum = 0f
        for (c in path) {
            val k = layout.letterUnits.getValue(c)
            var best = Float.MAX_VALUE
            for (p in stroke) best = minOf(best, hypot(p.first - k.first, p.second - k.second))
            sum += max(0f, best - p.coverageTolerance)
        }
        return sum
    }

    companion object {
        private const val N = 40
        private const val END_RADIUS = 1.6f
        private const val MAX_END_KEYS = 5

        fun normalize(word: String): String = word.lowercase().replace('ё', 'е')

        private fun endsKey(a: Char, b: Char): Long = (a.code.toLong() shl 32) or b.code.toLong()

        fun pathLength(p: List<Pair<Float, Float>>): Float {
            var len = 0f
            for (i in 1 until p.size) len += hypot(p[i].first - p[i - 1].first, p[i].second - p[i - 1].second)
            return len
        }

        /** Равномерно переразбить ломаную на n точек (x0,y0,x1,y1,…). Точка (одна буква) — n копий. */
        fun resample(p: List<Pair<Float, Float>>, n: Int): FloatArray {
            val out = FloatArray(n * 2)
            val total = pathLength(p)
            if (p.size == 1 || total < 1e-6f) {
                for (i in 0 until n) { out[2 * i] = p[0].first; out[2 * i + 1] = p[0].second }
                return out
            }
            val step = total / (n - 1)
            var seg = 1
            var segStart = 0f
            var segLen = hypot(p[1].first - p[0].first, p[1].second - p[0].second)
            for (i in 0 until n) {
                val d = minOf(i * step, total)
                while (seg < p.size - 1 && segStart + segLen < d) {
                    segStart += segLen
                    seg++
                    segLen = hypot(p[seg].first - p[seg - 1].first, p[seg].second - p[seg - 1].second)
                }
                val t = if (segLen < 1e-6f) 0f else ((d - segStart) / segLen).coerceIn(0f, 1f)
                out[2 * i] = p[seg - 1].first + t * (p[seg].first - p[seg - 1].first)
                out[2 * i + 1] = p[seg - 1].second + t * (p[seg].second - p[seg - 1].second)
            }
            return out
        }

        /** Сдвинуть в центр масс и привести к единичному размеру — остаётся только форма. */
        fun normalizeShape(a: FloatArray): FloatArray {
            val n = a.size / 2
            var cx = 0f
            var cy = 0f
            for (i in 0 until n) { cx += a[2 * i]; cy += a[2 * i + 1] }
            cx /= n; cy /= n
            var r = 0f
            for (i in 0 until n) r = max(r, max(abs(a[2 * i] - cx), abs(a[2 * i + 1] - cy)))
            val s = if (r < 1e-6f) 1f else 1f / r
            return FloatArray(a.size) { i -> if (i % 2 == 0) (a[i] - cx) * s else (a[i] - cy) * s }
        }

        fun meanDist(a: FloatArray, b: FloatArray): Float {
            var sum = 0f
            val n = a.size / 2
            for (i in 0 until n) {
                val dx = a[2 * i] - b[2 * i]
                val dy = a[2 * i + 1] - b[2 * i + 1]
                sum += sqrt(dx * dx + dy * dy)
            }
            return sum / n
        }
    }
}
