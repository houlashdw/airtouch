package com.airtouch.keyboard

import com.airtouch.gesture.TouchEvent
import kotlin.math.hypot

/** Ячейка воздушной клавиатуры для отрисовки (координаты — доли прямоугольника клавиатуры). */
data class KbCell(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val kind: Kind,
    val label: String,
    /** Номер зоны / подсказки. */
    val index: Int = 0,
) {
    enum class Kind { ZONE, SUGGESTION, LANG, COMMA, SPACE, PERIOD, BACKSPACE, ENTER }

    fun contains(x: Float, y: Float) = x >= left && x < right && y >= top && y < bottom
    val cx get() = (left + right) / 2
    val cy get() = (top + bottom) / 2
}

/** Что рисовать. [hover] — индекс ячейки под рукой, [composing] — набранные зоны (кол-во букв). */
data class KeyboardView(
    val lang: Lang,
    val cells: List<KbCell>,
    val hover: Int,
    val suggestions: List<String>,
    val typedLetters: Int,
)

/**
 * Раскладка «крупные зоны»: каждый ряд обычной клавиатуры разрезан пополам → 6 огромных зон
 * по 4–6 букв. Попасть в зону рукой в воздухе легко; какое слово имелось в виду, решает словарь (как T9).
 */
object ZoneLayout {
    private val RU = listOf(listOf("йцукен", "гшщзхъ"), listOf("фывап", "ролджэ"), listOf("ячсми", "тьбю"))
    private val EN = listOf(listOf("qwert", "yuiop"), listOf("asdfg", "hjkl"), listOf("zxcv", "bnm"))

    fun zones(lang: Lang): List<String> = (if (lang == Lang.RU) RU else EN).flatten()

    fun zoneOf(lang: Lang): Map<Char, Int> {
        val m = HashMap<Char, Int>()
        zones(lang).forEachIndexed { i, z -> z.forEach { m[it] = i } }
        if (lang == Lang.RU) m['ё'] = m.getValue('е')
        return m
    }

    private const val SUGG_H = 0.18f
    private const val ZONE_H = 0.21f

    fun cells(lang: Lang, suggestions: List<String>): List<KbCell> {
        val out = ArrayList<KbCell>()
        for (i in 0 until 3) {
            out += KbCell(i / 3f, 0f, (i + 1) / 3f, SUGG_H, KbCell.Kind.SUGGESTION, suggestions.getOrNull(i) ?: "", i)
        }
        zones(lang).forEachIndexed { i, z ->
            val r = i / 2
            val c = i % 2
            out += KbCell(c / 2f, SUGG_H + r * ZONE_H, (c + 1) / 2f, SUGG_H + (r + 1) * ZONE_H, KbCell.Kind.ZONE, z, i)
        }
        val top = SUGG_H + 3 * ZONE_H
        val bottom = listOf(
            KbCell.Kind.LANG to (if (lang == Lang.RU) "RU" else "EN") to 1.3f,
            KbCell.Kind.COMMA to "," to 0.9f,
            KbCell.Kind.SPACE to "пробел" to 3.4f,
            KbCell.Kind.PERIOD to "." to 0.9f,
            KbCell.Kind.BACKSPACE to "⌫" to 1.6f,
            KbCell.Kind.ENTER to "⏎" to 1.3f,
        )
        val total = bottom.sumOf { it.second.toDouble() }.toFloat()
        var x = 0f
        for ((kl, w) in bottom) {
            val ww = w / total
            out += KbCell(x, top, x + ww, 1f, kl.first, kl.second)
            x += ww
        }
        return out
    }
}

/** Слова по последовательности зон: точные совпадения по длине + продолжения. */
class ZoneDecoder(private val lang: Lang, dictionary: List<DictWord>) {
    private val zoneOf = ZoneLayout.zoneOf(lang)
    private val exact = HashMap<String, MutableList<DictWord>>()
    private val prefix = HashMap<String, MutableList<DictWord>>()

    init {
        for (dw in dictionary) {
            val w = dw.word.lowercase()
            if (w.any { it !in zoneOf }) continue
            val code = buildString { w.forEach { append('0' + zoneOf.getValue(it)) } }
            addTop(exact.getOrPut(code) { mutableListOf() }, dw, 6)
            for (k in 1 until code.length) addTop(prefix.getOrPut(code.substring(0, k)) { mutableListOf() }, dw, 3)
        }
    }

    private fun addTop(list: MutableList<DictWord>, dw: DictWord, n: Int) {
        if (list.size < n) list += dw else return // словарь отсортирован по частоте — первые и есть лучшие
    }

    /** До [n] вариантов: сначала слова ровно такой длины, затем продолжения. */
    fun candidates(zones: List<Int>, n: Int = 3): List<String> {
        if (zones.isEmpty()) return emptyList()
        val code = zones.joinToString("") { ('0' + it).toString() }
        val res = LinkedHashSet<String>()
        exact[code]?.forEach { if (res.size < n) res += it.word }
        prefix[code]?.forEach { if (res.size < n) res += it.word }
        return res.toList()
    }
}

/**
 * Клавиатура «крупные зоны». Щипок по зоне — следующая буква слова; словарь угадывает слово;
 * пробел вставляет лучший вариант; подсказка — выбранный вариант; ⌫ — убрать последнюю букву
 * (или всё только что вставленное слово).
 */
class ZoneKeyboardController(
    private val sink: TextSink,
    private val decoderFor: (Lang) -> ZoneDecoder?,
    initialLang: Lang = Lang.RU,
) {
    var lang = initialLang
        private set
    private val zones = ArrayList<Int>()
    private var suggestions: List<String> = emptyList()
    private var hover = -1
    private var lastCommitted: String? = null
    private var downAt: Pair<Float, Float>? = null

    private fun cells() = ZoneLayout.cells(lang, suggestions)

    fun view() = KeyboardView(lang, cells(), hover, suggestions, zones.size)

    fun onCursor(x: Float, y: Float) {
        hover = cells().indexOfFirst { it.contains(x, y) }
    }

    fun cancelStroke() {
        downAt = null
    }

    fun reset() {
        zones.clear()
        suggestions = emptyList()
        lastCommitted = null
        hover = -1
        downAt = null
    }

    fun onTouch(e: TouchEvent) {
        when (e) {
            is TouchEvent.Tap -> press(e.x, e.y)
            // Щипок чуть «поплыл» — всё равно считаем нажатием в точке, где он начался.
            is TouchEvent.Down -> downAt = e.x to e.y
            is TouchEvent.Move -> Unit
            is TouchEvent.Up -> {
                val d = downAt ?: return
                downAt = null
                if (hypot(e.x - d.first, e.y - d.second) < 0.12f) press(d.first, d.second)
            }
        }
    }

    private fun press(x: Float, y: Float) {
        val cell = cells().firstOrNull { it.contains(x, y) } ?: return
        when (cell.kind) {
            KbCell.Kind.ZONE -> {
                zones += cell.index
                refresh()
            }
            KbCell.Kind.SUGGESTION -> {
                val w = suggestions.getOrNull(cell.index) ?: return
                if (zones.isEmpty() && lastCommitted != null) sink.deleteBefore(lastCommitted!!.length)
                commit(w)
            }
            KbCell.Kind.SPACE -> {
                val top = suggestions.firstOrNull()
                if (zones.isNotEmpty() && top != null) commit(top) else sink.insert(" ")
            }
            KbCell.Kind.BACKSPACE -> when {
                zones.isNotEmpty() -> { zones.removeAt(zones.lastIndex); refresh() }
                lastCommitted != null -> { sink.deleteBefore(lastCommitted!!.length); lastCommitted = null; suggestions = emptyList() }
                else -> sink.deleteBefore(1)
            }
            KbCell.Kind.COMMA, KbCell.Kind.PERIOD -> {
                if (zones.isNotEmpty()) suggestions.firstOrNull()?.let { commit(it) }
                val before = sink.textBeforeCursor() ?: ""
                if (before.endsWith(" ")) sink.deleteBefore(1)
                sink.insert(if (cell.kind == KbCell.Kind.COMMA) ", " else ". ")
                lastCommitted = null
                suggestions = emptyList()
            }
            KbCell.Kind.ENTER -> {
                if (zones.isNotEmpty()) suggestions.firstOrNull()?.let { commit(it) }
                sink.enter()
                lastCommitted = null
            }
            KbCell.Kind.LANG -> {
                lang = if (lang == Lang.RU) Lang.EN else Lang.RU
                zones.clear()
                suggestions = emptyList()
            }
        }
    }

    private fun refresh() {
        suggestions = decoderFor(lang)?.candidates(zones) ?: emptyList()
    }

    private fun commit(word: String) {
        val before = sink.textBeforeCursor() ?: ""
        val prefix = if (before.isNotEmpty() && !before.last().isWhitespace()) " " else ""
        val t = before.trimEnd()
        val text = if (t.isEmpty() || t.last() in ".!?\n") word.replaceFirstChar { it.uppercaseChar() } else word
        val ins = "$prefix$text "
        sink.insert(ins)
        lastCommitted = ins
        // Подсказки оставляем: можно сразу заменить слово другим вариантом.
        zones.clear()
    }
}
