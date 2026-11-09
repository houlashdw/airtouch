package com.airtouch.keyboard

import com.airtouch.gesture.TouchEvent
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class KeyboardControllerTest {

    private class FakeField : TextSink {
        val sb = StringBuilder()
        var entered = 0
        override fun textBeforeCursor() = sb.toString()
        override fun insert(text: String) { sb.append(text) }
        override fun deleteBefore(count: Int) { sb.setLength((sb.length - count).coerceAtLeast(0)) }
        override fun enter() { entered++ }
    }

    private val decoders = Lang.values().associateWith { lang ->
        val name = Dictionary.assetName(lang)
        val f = listOf("src/main/assets/$name", "app/src/main/assets/$name", "/home/claude/airtouch/app/src/main/assets/$name")
            .map(::File).first { it.exists() }
        GlideDecoder(KeyboardLayout.of(lang), f.bufferedReader().use(Dictionary::parse))
    }

    private fun kb(field: FakeField, lang: Lang = Lang.RU) = KeyboardController(field, { decoders[it] }, lang)

    private fun KeyboardController.glide(word: String) {
        val pts = word.map { c -> layout.keys.first { it.char == c } }.map { it.cx to it.cy }
        onTouch(TouchEvent.Down(pts[0].first, pts[0].second))
        pts.drop(1).forEach { onTouch(TouchEvent.Move(it.first, it.second)) }
        onTouch(TouchEvent.Up(pts.last().first, pts.last().second))
    }

    private fun KeyboardController.tap(code: KeyCode, label: String? = null, index: Int = 0) {
        val k = layout.keys.first { it.code == code && (label == null || it.label == label) && (code != KeyCode.SUGGESTION || it.index == index) }
        onTouch(TouchEvent.Tap(k.cx, k.cy))
    }

    @Test
    fun glideWords_capitalisedAndSpaced() {
        val f = FakeField()
        val k = kb(f)
        k.glide("привет")
        k.glide("как")
        k.glide("дела")
        assertEquals("Привет как дела ", f.sb.toString())
    }

    @Test
    fun backspaceAfterGlide_deletesWholeWord() {
        val f = FakeField()
        val k = kb(f)
        k.glide("привет")
        k.glide("мир")
        k.tap(KeyCode.BACKSPACE)
        assertEquals("Привет ", f.sb.toString())
        k.tap(KeyCode.BACKSPACE) // второй ⌫ — уже по одному символу
        assertEquals("Привет", f.sb.toString())
    }

    @Test
    fun period_attachesToWord_andCapitalisesNext() {
        val f = FakeField()
        val k = kb(f)
        k.glide("привет")
        k.tap(KeyCode.PERIOD)
        k.glide("как")
        assertEquals("Привет. Как ", f.sb.toString())
    }

    @Test
    fun suggestion_replacesLastWord() {
        val f = FakeField()
        val k = kb(f)
        k.glide("привет")
        val alt = k.view().suggestions.getOrNull(1) ?: return
        k.tap(KeyCode.SUGGESTION, index = 1)
        assertEquals(alt.replaceFirstChar { it.uppercaseChar() } + " ", f.sb.toString())
    }

    @Test
    fun tapLetters_andLanguageSwitch() {
        val f = FakeField()
        val k = kb(f)
        k.tap(KeyCode.CHAR, "д")
        k.tap(KeyCode.CHAR, "а")
        k.tap(KeyCode.SPACE)
        k.tap(KeyCode.LANG)
        k.glide("hello")
        assertEquals("Да hello ", f.sb.toString())
    }

    @Test
    fun enter_isSent() {
        val f = FakeField()
        val k = kb(f)
        k.tap(KeyCode.ENTER)
        assertEquals(1, f.entered)
    }
}
