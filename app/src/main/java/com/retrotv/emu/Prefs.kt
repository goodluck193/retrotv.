package com.retrotv.emu

import android.content.Context

object Prefs {
    private const val NAME = "retrotv_prefs"

    const val FILTER_SHARP = "sharp"      // чёткие пиксели (по умолчанию)
    const val FILTER_SMOOTH = "smooth"    // сглаживание / апскейл
    const val FILTER_CRT = "crt"          // имитация кинескопа
    const val FILTER_LCD = "lcd"          // имитация LCD

    private fun sp(c: Context) = c.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** Уровень размытия для фильтра «Сглаживание»: smart / light / medium / strong. */
    var Context.smoothLevel: String
        get() = sp(this).getString("smooth_lvl", "smart") ?: "smart"
        set(v) = sp(this).edit().putString("smooth_lvl", v).apply()

    /** Стандартный звук (false) надёжнее на ТВ; низкая задержка (true) — если стандартный трещит. */
    var Context.audioLowLatency: Boolean
        get() = sp(this).getBoolean("audio_ll", false)
        set(v) = sp(this).edit().putBoolean("audio_ll", v).apply()

    /** Высота рендера: 720 (по умолчанию, легче всего для ТВ), 1080 или 0 = родное разрешение. */
    var Context.renderHeight: Int
        get() = sp(this).getInt("render_h", 720)
        set(v) = sp(this).edit().putInt("render_h", v).apply()

    /** Перемотка назад на R2. Выключение убирает фоновые снимки состояния. */
    var Context.rewindEnabled: Boolean
        get() = sp(this).getBoolean("rewind_on", true)
        set(v) = sp(this).edit().putBoolean("rewind_on", v).apply()

    var Context.defaultFilter: String
        get() = sp(this).getString("filter", FILTER_SHARP) ?: FILTER_SHARP
        set(v) = sp(this).edit().putString("filter", v).apply()

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
