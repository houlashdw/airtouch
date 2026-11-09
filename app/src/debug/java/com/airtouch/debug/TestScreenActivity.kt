package com.airtouch.debug

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.atomic.AtomicInteger

/**
 * Тестовый экран (только debug) — «подопытное приложение» для сквозных тестов:
 *  0–30% высоты — большая кнопка (считаем нажатия),
 * 30–40% — поле ввода,
 * 40–100% — длинный список (считаем прокрутку и долгие нажатия).
 */
class TestScreenActivity : Activity() {

    lateinit var button: Button
    lateinit var edit: EditText
    lateinit var scroll: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val h = resources.displayMetrics.heightPixels

        button = Button(this).apply {
            text = "TAP"
            textSize = 32f
            setOnClickListener { clicks.incrementAndGet() }
        }
        edit = EditText(this).apply { hint = "type here"; textSize = 18f }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        repeat(80) { i ->
            list.addView(
                TextView(this).apply {
                    text = "Row $i"
                    textSize = 22f
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(32, 32, 32, 32)
                    setBackgroundColor(if (i % 2 == 0) Color.LTGRAY else Color.WHITE)
                    setOnLongClickListener { longPresses.incrementAndGet(); true }
                    setOnClickListener { rowClicks.incrementAndGet() }
                },
            )
        }
        scroll = ScrollView(this).apply { addView(list) }

        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(button, LinearLayout.LayoutParams(MATCH_PARENT, (h * 0.30f).toInt()))
                addView(edit, LinearLayout.LayoutParams(MATCH_PARENT, (h * 0.10f).toInt()))
                addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            },
        )
    }

    companion object {
        val clicks = AtomicInteger()
        val rowClicks = AtomicInteger()
        val longPresses = AtomicInteger()
        fun resetCounters() {
            clicks.set(0); rowClicks.set(0); longPresses.set(0)
        }
    }
}
