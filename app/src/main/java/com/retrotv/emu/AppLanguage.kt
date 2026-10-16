package com.retrotv.emu

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

object AppLanguage {
    val tags = arrayOf("en", "ru", "es", "pt", "fr", "de", "it")
    val names = arrayOf("English", "Русский", "Español", "Português", "Français", "Deutsch", "Italiano")
    private fun stored(c: Context) = c.getSharedPreferences("retrotv_prefs", Context.MODE_PRIVATE).getString("language", "en") ?: "en"
    fun tag(c: Context): String {
        if (Build.VERSION.SDK_INT >= 33) {
            val locales = c.getSystemService(LocaleManager::class.java).applicationLocales
            if (!locales.isEmpty) return locales[0].toLanguageTag()
        }
        return stored(c)
    }
    fun initialize(c: Context) {
        if (Build.VERSION.SDK_INT >= 33) {
            val manager = c.getSystemService(LocaleManager::class.java)
            if (manager.applicationLocales.isEmpty) manager.applicationLocales = LocaleList.forLanguageTags(stored(c))
        } else AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(stored(c)))
    }
    fun select(c: Context, tag: String) {
        c.getSharedPreferences("retrotv_prefs", Context.MODE_PRIVATE).edit().putString("language", tag).apply()
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }
    fun context(c: Context): Context = c.createConfigurationContext(Configuration(c.resources.configuration).apply {
        setLocales(LocaleList.forLanguageTags(tag(c)))
    })
}
