package com.airtouch.keyboard

import com.airtouch.gesture.TouchEvent
import kotlin.math.hypot

/** Куда печатаем. Реализация — служба спец. возможностей (фокусное поле ввода). */
interface TextSink {
    /** Текст до курсора (null — поле недоступно). */
    fun textBeforeCursor(): String?
    fun insert(text: String)
    fun deleteBefore(count: Int)
    fun enter()
}

/** Что рисовать на клавиатуре. */
data class KeyboardView(
    val lang: Lang,
    val hover: Key?,
    val stroke: List<Pair<Float, Float>>,
    val suggestions: List<String>,
)

/**
 * Логика воздушной клавиатуры. Координаты — доли прямоугольника клавиатуры (0..1).
 *
 * - быстрый щипок по клавише → буква / служебная клавиша;
 * - щипок + росчерк через буквы → слово целиком (+ пробел), до 3 вариантов в подсказках;
 * - ⌫ сразу после росчерка стирает всё слово;
 * - подсказка заменяет последнее слово;
 * - заглавная буква в начале поля и после . ! ?
 *
 * Вызывать из одного потока.
 */
class KeyboardController(
    private val sink: TextSink,
    private val decoderFor: (Lang) -> GlideDecoder?,
    initialLang: Lang = Lang.RU,
) {
    var lang: Lang = initialLang
        private set
    val layout: KeyboardLayout get() = KeyboardLayout.of(lang)

    private var hover: Key? = null
    private val stroke = ArrayList<Pair<Float, Float>>()
    private var stroking = false
    private var suggestions: List<String> = emptyList()
    /** Последнее вставленное росчерком слово вместе с пробелами — для ⌫ и замены подсказкой. */
    private var lastCommitted: String? = null
    private var lastCommittedPrefix = ""

    fun view() = KeyboardView(lang, hover, if (stroking) stroke.toList() else emptyList(), suggestions)

    fun onCursor(x: Float, y: Float) {
        hover = layout.keyAt(x, y)
    }

    /** Рука пропала посреди росчерка — недописанное слово не вставляем. */
    fun cancelStroke() {
        stroke.clear()
        stroking = false
    }

    fun reset() {
        stroke.clear()
        stroking = false
        hover = null
        lastCommitted = null
        suggestions = emptyList()
    }

    fun onTouch(e: TouchEvent) {
        when (e) {
            is TouchEvent.Tap -> layout.keyAt(e.x, e.y)?.let(::press)
            is TouchEvent.Down -> {
                stroke.clear()
                stroke += e.x to e.y
                stroking = true
            }
            is TouchEvent.Move -> if (stroking) stroke += e.x to e.y
            is TouchEvent.Up -> if (stroking) {
                stroke += e.x to e.y
                stroking = false
                finishStroke()
            }
        }
    }

    private fun finishStroke() {
        val pts = stroke.toList()
        stroke.clear()
        val start = layout.keyAt(pts.first().first, pts.first().second) ?: return
        val units = pts.map { layout.toUnits(it.first, it.second) }
        var len = 0f
        for (i in 1 until units.size) len += hypot(units[i].first - units[i - 1].first, units[i].second - units[i - 1].second)
        // Почти не сдвинулись или начали не с буквы — это нажатие на клавишу, а не росчерк.
        if (len < MIN_GLIDE_KEYS || start.code != KeyCode.CHAR) {
            press(start)
            return
        }
        val words = decoderFor(lang)?.decode(pts) ?: emptyList()
        if (words.isEmpty()) return
        suggestions = words
        commitWord(words.first())
    }

    private fun commitWord(word: String) {
        val before = sink.textBeforeCursor() ?: ""
        val prefix = if (before.isNotEmpty() && !before.last().isWhitespace()) " " else ""
        val text = if (needsCapital(before)) word.replaceFirstChar { it.uppercaseChar() } else word
        val inserted = "$prefix$text "
        sink.insert(inserted)
        lastCommitted = inserted
        lastCommittedPrefix = prefix
    }

    private fun press(key: Key) {
        when (key.code) {
            KeyCode.CHAR -> {
                val before = sink.textBeforeCursor() ?: ""
                val c = key.label
                sink.insert(if (needsCapital(before)) c.uppercase() else c)
                forget()
            }
            KeyCode.SPACE -> { sink.insert(" "); forget() }
            KeyCode.BACKSPACE -> {
                val last = lastCommitted
                if (last != null) sink.deleteBefore(last.length) else sink.deleteBefore(1)
                forget()
            }
            KeyCode.ENTER -> { sink.enter(); forget() }
            KeyCode.LANG -> { lang = if (lang == Lang.RU) Lang.EN else Lang.RU; forget() }
            KeyCode.COMMA, KeyCode.PERIOD -> {
                // «слово ,» → «слово, »: убираем пробел, который поставил росчерк.
                val before = sink.textBeforeCursor() ?: ""
                if (before.endsWith(" ")) sink.deleteBefore(1)
                sink.insert(if (key.code == KeyCode.COMMA) ", " else ". ")
                forget()
            }
            KeyCode.SUGGESTION -> {
                val word = suggestions.getOrNull(key.index) ?: return
                val last = lastCommitted ?: return
                sink.deleteBefore(last.length)
                val before = sink.textBeforeCursor() ?: ""
                val text = if (needsCapital(before)) word.replaceFirstChar { it.uppercaseChar() } else word
                val inserted = "$lastCommittedPrefix$text "
                sink.insert(inserted)
                lastCommitted = inserted
            }
        }
    }

    private fun forget() {
        lastCommitted = null
        suggestions = emptyList()
    }

    private fun needsCapital(before: String): Boolean {
        val t = before.trimEnd()
        return t.isEmpty() || t.last() in ".!?\n"
    }

    private companion object {
        /** Росчерк короче этого (в клавишах) — считаем нажатием. */
        const val MIN_GLIDE_KEYS = 0.8f
    }
}
