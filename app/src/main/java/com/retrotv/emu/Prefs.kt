package com.retrotv.emu

import android.content.Context

/** Persistent preferences. Stable keys preserve existing installations. */
object Prefs {
    private const val NAME = "retrotv_prefs"

    const val FILTER_SHARP = "sharp"
    const val FILTER_SMOOTH = "smooth"
    const val FILTER_CRT = "crt"
    const val FILTER_LCD = "lcd"

    private fun sp(c: Context) = c.getSharedPreferences(NAME, Context.MODE_PRIVATE)


    var Context.smoothLevel: String
        get() = sp(this).getString("smooth_lvl", "basic") ?: "basic"
        set(v) = sp(this).edit().putString("smooth_lvl", v).apply()


    var Context.audioLowLatency: Boolean
        get() = sp(this).getBoolean("audio_ll", false)
        set(v) = sp(this).edit().putBoolean("audio_ll", v).apply()

    var Context.downloadCovers: Boolean
        get() = sp(this).getBoolean("covers", true)
        set(v) = sp(this).edit().putBoolean("covers", v).apply()


    var Context.renderHeight: Int
        get() = sp(this).getInt("render_h", 720)
        set(v) = sp(this).edit().putInt("render_h", v).apply()


    var Context.rewindEnabled: Boolean
        get() = sp(this).getBoolean("rewind_on", true)
        set(v) = sp(this).edit().putBoolean("rewind_on", v).apply()

    var Context.defaultFilter: String
        get() = sp(this).getString("filter", FILTER_SHARP) ?: FILTER_SHARP
        set(v) = sp(this).edit().putString("filter", v).apply()

    var Context.wallpaper: String
        get() = sp(this).getString("wallpaper", "black") ?: "black"
        set(v) = sp(this).edit().putString("wallpaper", v).apply()

    fun filterTitle(c: Context, id: String): String = c.getString(when (id) {
        FILTER_SMOOTH -> R.string.f_smooth
        FILTER_CRT -> R.string.f_crt
        FILTER_LCD -> R.string.f_lcd
        else -> R.string.f_sharp
    })

    fun nextFilter(id: String): String = when (id) {
        FILTER_SHARP -> FILTER_SMOOTH
        FILTER_SMOOTH -> FILTER_CRT
        FILTER_CRT -> FILTER_LCD
        else -> FILTER_SHARP
    }
}
