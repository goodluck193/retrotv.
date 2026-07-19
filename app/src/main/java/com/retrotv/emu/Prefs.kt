package com.retrotv.emu

import android.content.Context

object Prefs {
    private const val NAME = "retrotv_prefs"

    const val FILTER_SHARP = "sharp"      // чёткие пиксели (по умолчанию)
    const val FILTER_SMOOTH = "smooth"    // сглаживание / апскейл
    const val FILTER_CRT = "crt"          // имитация кинескопа
    const val FILTER_LCD = "lcd"          // имитация LCD

    private fun sp(c: Context) = c.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var Context.defaultFilter: String
        get() = sp(this).getString("filter", FILTER_SHARP) ?: FILTER_SHARP
        set(v) = sp(this).edit().putString("filter", v).apply()

    var Context.fastForwardSpeed: Int
        get() = sp(this).getInt("ff_speed", 2)
        set(v) = sp(this).edit().putInt("ff_speed", v.coerceIn(2, 4)).apply()

    fun filterTitle(id: String): String = when (id) {
        FILTER_SMOOTH -> "Сглаживание (апскейл)"
        FILTER_CRT -> "CRT (кинескоп)"
        FILTER_LCD -> "LCD"
        else -> "Чёткие пиксели"
    }

    fun nextFilter(id: String): String = when (id) {
        FILTER_SHARP -> FILTER_SMOOTH
        FILTER_SMOOTH -> FILTER_CRT
        FILTER_CRT -> FILTER_LCD
        else -> FILTER_SHARP
    }
}
