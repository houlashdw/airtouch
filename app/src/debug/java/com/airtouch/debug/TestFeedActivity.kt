package com.airtouch.debug

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.atomic.AtomicInteger

/**
 * Копия ленты в стиле YouTube Shorts / TikTok (только debug): вертикальные полноэкранные страницы,
 * листаются по одной (PagerSnapHelper); долгое нажатие на странице = «меню видео».
 */
class TestFeedActivity : Activity() {

    lateinit var list: RecyclerView
    lateinit var layoutManager: LinearLayoutManager

    val currentPage: Int get() = layoutManager.findFirstCompletelyVisibleItemPosition()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        layoutManager = LinearLayoutManager(this, RecyclerView.VERTICAL, false)
        list = RecyclerView(this).apply {
            layoutManager = this@TestFeedActivity.layoutManager
            adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun getItemCount() = 50
                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                    object : RecyclerView.ViewHolder(
                        TextView(parent.context).apply {
                            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                            gravity = Gravity.CENTER
                            textSize = 40f
                            setOnLongClickListener { menuOpens.incrementAndGet(); true }
                            setOnClickListener { pauses.incrementAndGet() }
                        },
                    ) {}
                override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                    (holder.itemView as TextView).apply {
                        text = "Video $position"
                        setBackgroundColor(Color.HSVToColor(floatArrayOf((position * 47f) % 360, 0.5f, 0.9f)))
                    }
                }
            }
        }
        PagerSnapHelper().attachToRecyclerView(list)
        setContentView(list)
    }

    companion object {
        val menuOpens = AtomicInteger()
        val pauses = AtomicInteger()
        fun resetCounters() {
            menuOpens.set(0); pauses.set(0)
        }
    }
}
