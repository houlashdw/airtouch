package com.airtouch.keyboard

import kotlin.math.max

enum class Lang { RU, EN }

enum class KeyCode { CHAR, SPACE, BACKSPACE, ENTER, LANG, COMMA, PERIOD, SUGGESTION }

/**
 * Клавиша в нормализованных координатах клавиатуры (0..1 по ширине и высоте).
 * [index] — номер подсказки для [KeyCode.SUGGESTION].
 */
data class Key(
    val code: KeyCode,
    val label: String,
    val cx: Float,
    val cy: Float,
    val w: Float,
    val h: Float,
    val index: Int = 0,
) {
    val char: Char? get() = if (code == KeyCode.CHAR) label[0] else null
    fun contains(x: Float, y: Float) = x >= cx - w / 2 && x < cx + w / 2 && y >= cy - h / 2 && y < cy + h / 2
}

/**
 * Раскладка: строка подсказок сверху, три ряда букв, нижний ряд со служебными клавишами.
 * Для декодера важны «клавишные единицы»: 1 единица ≈ ширина/высота одной клавиши.
 */
class KeyboardLayout private constructor(val lang: Lang, val keys: List<Key>, val cols: Int, val rows: Int) {

    /** Центры букв в клавишных единицах — по ним строятся «эталонные» росчерки слов. */
    val letterUnits: Map<Char, Pair<Float, Float>> = keys.filter { it.code == KeyCode.CHAR }
        .associate { it.label[0] to (it.cx * cols to it.cy * rows) }

    fun keyAt(x: Float, y: Float): Key? =
        keys.firstOrNull { it.contains(x, y) } ?: keys.minByOrNull { (it.cx - x).sq() + (it.cy - y).sq() }

    fun toUnits(x: Float, y: Float): Pair<Float, Float> = x * cols to y * rows

    companion object {
        private val RU = listOf("йцукенгшщзхъ", "фывапролджэ", "ячсмитьбю")
        private val EN = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")

        private val cache = mutableMapOf<Lang, KeyboardLayout>()

        fun of(lang: Lang): KeyboardLayout = synchronized(cache) { cache.getOrPut(lang) { build(lang) } }

        private fun build(lang: Lang): KeyboardLayout {
            val letters = if (lang == Lang.RU) RU else EN
            val cols = letters.maxOf { it.length }
            val rows = letters.size + 2 // подсказки + буквы + нижний ряд
            val kh = 1f / rows
            val keys = mutableListOf<Key>()

            // Ряд 0 — три подсказки.
            for (i in 0 until 3) {
                keys += Key(KeyCode.SUGGESTION, "", (i + 0.5f) / 3f, 0.5f * kh, 1f / 3f, kh, index = i)
            }
            // Буквы, ряды выровнены по центру.
            letters.forEachIndexed { r, row ->
                val offset = (cols - row.length) / 2f
                row.forEachIndexed { i, c ->
                    keys += Key(KeyCode.CHAR, c.toString(), (offset + i + 0.5f) / cols, (r + 1.5f) * kh, 1f / cols, kh)
                }
            }
            // Нижний ряд: ширины в долях, растягиваем на всю ширину.
            val bottom = listOf(
                Triple(KeyCode.LANG, if (lang == Lang.RU) "RU" else "EN", 1.4f),
                Triple(KeyCode.COMMA, ",", 1f),
                Triple(KeyCode.SPACE, "пробел", 4.6f),
                Triple(KeyCode.PERIOD, ".", 1f),
                Triple(KeyCode.BACKSPACE, "⌫", 1.6f),
                Triple(KeyCode.ENTER, "⏎", 1.4f),
            )
            val total = bottom.sumOf { it.third.toDouble() }.toFloat()
            var x = 0f
            for ((code, label, wUnits) in bottom) {
                val w = wUnits / total
                keys += Key(code, label, x + w / 2, (rows - 0.5f) * kh, w, kh)
                x += w
            }
            return KeyboardLayout(lang, keys, max(cols, 1), rows)
        }

        private fun Float.sq() = this * this
    }
}
