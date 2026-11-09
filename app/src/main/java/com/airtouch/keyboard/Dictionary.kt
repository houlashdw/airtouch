package com.airtouch.keyboard

import java.io.BufferedReader

/** Формат: «слово<TAB>zipf» по строке, строки с # — комментарии. */
object Dictionary {
    fun parse(reader: BufferedReader): List<DictWord> = reader.lineSequence()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) null else DictWord(line.substring(0, tab), line.substring(tab + 1).toFloatOrNull() ?: 3f)
        }
        .toList()

    fun assetName(lang: Lang) = if (lang == Lang.RU) "dict_ru.txt" else "dict_en.txt"
}
