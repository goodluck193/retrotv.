package com.retrotv.emu

import android.content.Context

class LibraryMetadata(context: Context) {
    private val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)
    fun favorite(rom: Rom) = prefs.getBoolean("favorite.${rom.id}", false)
    fun toggleFavorite(rom: Rom) = prefs.edit().putBoolean("favorite.${rom.id}", !favorite(rom)).apply()
    fun lastPlayed(rom: Rom) = prefs.getLong("played.${rom.id}", 0)
    fun played(rom: Rom) = prefs.edit().putLong("played.${rom.id}", System.currentTimeMillis()).apply()
    fun filter(rom: Rom, default: String) = prefs.getString("filter.${rom.id}", default) ?: default
    fun setFilter(rom: Rom, value: String) = prefs.edit().putString("filter.${rom.id}", value).apply()
}
