package com.retrotv.emu

import android.app.AlertDialog
import android.os.Bundle
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.retrotv.emu.Prefs.defaultFilter
import com.retrotv.emu.Prefs.fastForwardSpeed

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // --- Фильтр по умолчанию ---
        val filterGroup = findViewById<RadioGroup>(R.id.filterGroup)
        val filterIds = listOf(
            Prefs.FILTER_SHARP to R.id.fSharp,
            Prefs.FILTER_SMOOTH to R.id.fSmooth,
            Prefs.FILTER_CRT to R.id.fCrt,
            Prefs.FILTER_LCD to R.id.fLcd
        )
        filterIds.firstOrNull { it.first == defaultFilter }?.let {
            findViewById<RadioButton>(it.second).isChecked = true
        }
        filterGroup.setOnCheckedChangeListener { _, checkedId ->
            defaultFilter = filterIds.first { it.second == checkedId }.first
        }

        // --- Скорость перемотки ---
        val ffGroup = findViewById<RadioGroup>(R.id.ffGroup)
        when (fastForwardSpeed) {
            3 -> findViewById<RadioButton>(R.id.ff3).isChecked = true
            4 -> findViewById<RadioButton>(R.id.ff4).isChecked = true
            else -> findViewById<RadioButton>(R.id.ff2).isChecked = true
        }
        ffGroup.setOnCheckedChangeListener { _, checkedId ->
            fastForwardSpeed = when (checkedId) {
                R.id.ff3 -> 3
                R.id.ff4 -> 4
                else -> 2
            }
        }

        // --- Занятое место ---
        updateStorageInfo()

        // --- Очистка сохранений ---
        findViewById<Button>(R.id.btnClearStates).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Удалить все сохранения?")
                .setMessage("Сами игры останутся, удалятся только файлы состояний.")
                .setPositiveButton("Удалить") { _, _ ->
                    RomLibrary.statesDir(this).listFiles()?.forEach { it.delete() }
                    updateStorageInfo()
                }
                .setNegativeButton("Отмена", null)
                .show()
        }
    }

    private fun updateStorageInfo() {
        val romsBytes = SystemType.entries.sumOf { sys ->
            RomImporter.romsDir(this, sys).listFiles()?.sumOf { it.length() } ?: 0L
        }
        val statesBytes = RomLibrary.statesDir(this).listFiles()?.sumOf { it.length() } ?: 0L
        val freeMb = filesDir.usableSpace / 1024 / 1024
        findViewById<TextView>(R.id.storageInfo).text =
            "Игры: ${romsBytes / 1024} КБ  •  Сохранения: ${statesBytes / 1024} КБ\n" +
            "Свободно на ТВ: $freeMb МБ"
    }
}
