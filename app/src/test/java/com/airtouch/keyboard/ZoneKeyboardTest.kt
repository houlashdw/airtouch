package com.airtouch.keyboard

import com.airtouch.gesture.TouchEvent
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class ZoneKeyboardTest {

    private fun dict(lang: Lang): List<DictWord> {
        val name = Dictionary.assetName(lang)
        val f = listOf("src/main/assets/$name", "app/src/main/assets/$name", "/home/claude/airtouch/app/src/main/assets/$name")
            .map(::File).first { it.exists() }
        return f.bufferedReader().use(Dictionary::parse)
    }

    private val decoders = Lang.values().associateWith { ZoneDecoder(it, dict(it)) }

    private class FakeField : TextSink {
        val sb = StringBuilder()
        override fun textBeforeCursor() = sb.toString()
        override fun insert(text: String) { sb.append(text) }
        override fun deleteBefore(count: Int) { sb.setLength((sb.length - count).coerceAtLeast(0)) }
        override fun enter() {}
    }

    private fun ZoneKeyboardController.tapCell(kind: KbCell.Kind, index: Int = 0) {
        val c = view().cells.first { it.kind == kind && (kind != KbCell.Kind.ZONE && kind != KbCell.Kind.SUGGESTION || it.index == index) }
        onTouch(TouchEvent.Tap(c.cx, c.cy))
    }

    private fun ZoneKeyboardController.typeWord(word: String) {
        val z = ZoneLayout.zoneOf(lang)
        word.forEach { tapCell(KbCell.Kind.ZONE, z.getValue(it)) }
    }

    private fun accuracy(lang: Lang): Pair<Float, Float> {
        val z = ZoneLayout.zoneOf(lang)
        val sample = dict(lang).take(2000)
        var top1 = 0
        var top3 = 0
        for (w in sample) {
            val c = decoders.getValue(lang).candidates(w.word.map { z.getValue(it) })
            if (c.firstOrNull() == w.word) top1++
            if (w.word in c) top3++
        }
        val r = top1.toFloat() / sample.size to top3.toFloat() / sample.size
        println("$lang zones: top1=${"%.2f".format(r.first)} top3=${"%.2f".format(r.second)}")
        return r
    }

    @Test
    fun russian_commonWordsAreFound() {
        val (a1, a3) = accuracy(Lang.RU)
        assert(a1 >= 0.8f && a3 >= 0.95f) { "ru $a1 $a3" }
    }

    @Test
    fun english_commonWordsAreFound() {
        val (a1, a3) = accuracy(Lang.EN)
        assert(a1 >= 0.75f && a3 >= 0.93f) { "en $a1 $a3" }
    }

    @Test
    fun typing_spaceCommitsTopWord_capitalised() {
        val f = FakeField()
        val k = ZoneKeyboardController(f, { decoders[it] })
        k.typeWord("привет"); k.tapCell(KbCell.Kind.SPACE)
        k.typeWord("как"); k.tapCell(KbCell.Kind.SPACE)
        k.typeWord("дела"); k.tapCell(KbCell.Kind.PERIOD)
        assertEquals("Привет как дела. ", f.sb.toString())
    }

    @Test
    fun backspace_removesLetterThenWholeWord() {
        val f = FakeField()
        val k = ZoneKeyboardController(f, { decoders[it] })
        k.typeWord("привет"); k.tapCell(KbCell.Kind.SPACE)
        k.typeWord("ма")
        k.tapCell(KbCell.Kind.BACKSPACE); k.tapCell(KbCell.Kind.BACKSPACE) // стёрли набор
        assertEquals(0, k.view().typedLetters)
        k.tapCell(KbCell.Kind.BACKSPACE) // стираем последнее вставленное слово целиком
        assertEquals("", f.sb.toString())
    }

    @Test
    fun suggestionReplacesJustInsertedWord() {
        val f = FakeField()
        val k = ZoneKeyboardController(f, { decoders[it] })
        k.typeWord("как"); k.tapCell(KbCell.Kind.SPACE)
        val alt = k.view().suggestions.getOrNull(1) ?: return
        k.tapCell(KbCell.Kind.SUGGESTION, 1)
        assertEquals(alt.replaceFirstChar { it.uppercaseChar() } + " ", f.sb.toString())
    }

    @Test
    fun slightlyDriftingPinch_stillPressesZone() {
        val f = FakeField()
        val k = ZoneKeyboardController(f, { decoders[it] })
        val c = k.view().cells.first { it.kind == KbCell.Kind.ZONE && it.index == 0 }
        k.onTouch(TouchEvent.Down(c.cx, c.cy)); k.onTouch(TouchEvent.Up(c.cx + 0.05f, c.cy))
        assertEquals(1, k.view().typedLetters)
    }
}
