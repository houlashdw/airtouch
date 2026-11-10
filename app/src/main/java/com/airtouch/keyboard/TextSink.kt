package com.airtouch.keyboard

/** Куда печатаем. Реализация — служба спец. возможностей (фокусное поле ввода). */
interface TextSink {
    /** Текст до курсора (null — поле недоступно). */
    fun textBeforeCursor(): String?
    fun insert(text: String)
    fun deleteBefore(count: Int)
    fun enter()
}
