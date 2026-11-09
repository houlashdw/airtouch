package com.airtouch.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * Точность скользящего ввода на «неаккуратных» синтетических росчерках:
 * рука промахивается мимо центров клавиш, срезает углы и дрожит.
 */
class GlideDecoderTest {

    private fun dict(lang: Lang): List<DictWord> {
        val name = Dictionary.assetName(lang)
        val f = listOf("src/main/assets/$name", "app/src/main/assets/$name", "/home/claude/airtouch/app/src/main/assets/$name")
            .map(::File).first { it.exists() }
        return f.bufferedReader().use(Dictionary::parse)
    }

    /** Росчерк через буквы слова с промахами σ=[aim] клавиши и дрожанием. */
    private fun sloppyStroke(layout: KeyboardLayout, word: String, rnd: Random, aim: Float): List<Pair<Float, Float>> {
        val keys = GlideDecoder.normalize(word).map { c -> layout.keys.first { it.char == c } }
        val pts = ArrayList<Pair<Float, Float>>()
        val verts = keys.map { k ->
            (k.cx + gauss(rnd) * aim / layout.cols) to (k.cy + gauss(rnd) * aim / layout.rows)
        }
        for (i in 1 until verts.size) {
            val (ax, ay) = verts[i - 1]
            val (bx, by) = verts[i]
            for (s in 0 until 6) {
                val t = s / 6f
                pts += (ax + (bx - ax) * t + gauss(rnd) * 0.04f / layout.cols) to (ay + (by - ay) * t + gauss(rnd) * 0.04f / layout.rows)
            }
        }
        pts += verts.last()
        return pts
    }

    private fun gauss(r: Random): Float {
        val u = r.nextDouble(1e-9, 1.0)
        val v = r.nextDouble()
        return (kotlin.math.sqrt(-2 * kotlin.math.ln(u)) * kotlin.math.cos(2 * Math.PI * v)).toFloat()
    }

    private fun accuracy(lang: Lang, aim: Float, words: Int = 400): Pair<Float, Float> {
        val d = dict(lang)
        val layout = KeyboardLayout.of(lang)
        val decoder = GlideDecoder(layout, d)
        val rnd = Random(7)
        val sample = d.filter { GlideDecoder.normalize(it.word).toSet().size >= 2 && it.word.length >= 3 }.take(words)
        var top1 = 0
        var top3 = 0
        val misses = mutableListOf<String>()
        for (w in sample) {
            val res = decoder.decode(sloppyStroke(layout, w.word, rnd, aim))
            if (res.firstOrNull() == w.word) top1++ else if (misses.size < 12) misses += "${w.word}→${res.firstOrNull()}"
            if (w.word in res) top3++
        }
        val a1 = top1.toFloat() / sample.size
        val a3 = top3.toFloat() / sample.size
        println("$lang aim=$aim top1=${"%.2f".format(a1)} top3=${"%.2f".format(a3)} dict=${decoder.size} misses=$misses")
        return a1 to a3
    }

    @Test
    fun russian_sloppyStrokes() {
        val (a1, a3) = accuracy(Lang.RU, aim = 0.3f)
        assert(a1 >= 0.75f) { "ru top1=$a1" }
        assert(a3 >= 0.9f) { "ru top3=$a3" }
    }

    @Test
    fun english_sloppyStrokes() {
        val (a1, a3) = accuracy(Lang.EN, aim = 0.3f)
        assert(a1 >= 0.75f) { "en top1=$a1" }
        assert(a3 >= 0.9f) { "en top3=$a3" }
    }

    @Test
    fun exactStroke_isTop1() {
        val layout = KeyboardLayout.of(Lang.RU)
        val dec = GlideDecoder(layout, dict(Lang.RU))
        for (w in listOf("привет", "спасибо", "хорошо", "сегодня", "работа")) {
            val keys = w.map { c -> layout.keys.first { it.char == c } }.map { it.cx to it.cy }
            assertEquals(w, dec.decode(keys).first())
        }
    }

    @Test
    fun decode_isFast() {
        val layout = KeyboardLayout.of(Lang.RU)
        val dec = GlideDecoder(layout, dict(Lang.RU))
        val rnd = Random(1)
        val strokes = listOf("привет", "человек", "который", "сказать", "можно").map { sloppyStroke(layout, it, rnd, 0.3f) }
        strokes.forEach { dec.decode(it) } // прогрев шаблонов
        val t0 = System.nanoTime()
        repeat(20) { strokes.forEach { dec.decode(it) } }
        val ms = (System.nanoTime() - t0) / 1e6 / 100
        println("decode avg ${"%.2f".format(ms)} ms")
        assert(ms < 30) { "decode too slow: $ms ms" }
    }
}
