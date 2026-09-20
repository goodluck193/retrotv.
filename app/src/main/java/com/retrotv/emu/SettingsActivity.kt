package com.retrotv.emu

import android.app.AlertDialog
import android.os.Bundle
import android.widget.CheckBox
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import java.io.File
import com.retrotv.emu.Prefs.downloadCovers
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.retrotv.emu.Prefs.audioLowLatency
import com.retrotv.emu.Prefs.defaultFilter
import com.retrotv.emu.Prefs.renderHeight
import com.retrotv.emu.Prefs.rewindEnabled
import com.retrotv.emu.Prefs.smoothLevel

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


        // --- Уровень сглаживания ---
        val smoothGroup = findViewById<RadioGroup>(R.id.smoothGroup)
        findViewById<RadioButton>(when (smoothLevel) {
            "light" -> R.id.slLight
            "medium" -> R.id.slMedium
            "strong" -> R.id.slStrong
            else -> R.id.slSmart
        }).isChecked = true
        smoothGroup.setOnCheckedChangeListener { _, id ->
            smoothLevel = when (id) {
                R.id.slLight -> "light"
                R.id.slMedium -> "medium"
                R.id.slStrong -> "strong"
                else -> "smart"
            }
        }

        // --- Звук ---
        val audioGroup = findViewById<RadioGroup>(R.id.audioGroup)
        findViewById<RadioButton>(if (audioLowLatency) R.id.aLow else R.id.aStd).isChecked = true
        audioGroup.setOnCheckedChangeListener { _, id -> audioLowLatency = (id == R.id.aLow) }

        // --- Разрешение рендера ---
        val resGroup = findViewById<RadioGroup>(R.id.resGroup)
        findViewById<RadioButton>(when (renderHeight) {
            1080 -> R.id.r1080
            0 -> R.id.rNative
            else -> R.id.r720
        }).isChecked = true
        resGroup.setOnCheckedChangeListener { _, id ->
            renderHeight = when (id) { R.id.r1080 -> 1080; R.id.rNative -> 0; else -> 720 }
        }

        // --- Перемотка назад ---
        val rewGroup = findViewById<RadioGroup>(R.id.rewGroup)
        findViewById<RadioButton>(if (rewindEnabled) R.id.rewOn else R.id.rewOff).isChecked = true
        rewGroup.setOnCheckedChangeListener { _, id -> rewindEnabled = (id == R.id.rewOn) }

        findViewById<CheckBox>(R.id.downloadCovers).apply {
            isChecked = downloadCovers
            setOnCheckedChangeListener { _, checked -> downloadCovers = checked }
        }
        // --- Занятое место ---
        updateStorageInfo()

        // --- Очистка сохранений ---
        findViewById<Button>(R.id.btnClearStates).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Удалить снимки состояния?")
                .setMessage("Удалятся слоты, автосохранения и их резервные копии. Игры и внутриигровая SRAM останутся.")
                .setPositiveButton("Удалить") { _, _ ->
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            SaveWriter.flush()
                            RomLibrary.statesDir(this@SettingsActivity).listFiles()?.forEach { it.delete() }
                            File(filesDir, "saves").walkTopDown().filter { it.isFile && (it.name.contains(".state") || it.extension == "jpg") }.forEach { it.delete() }
                        }
                        updateStorageInfo()
                    }
                }
                .setNegativeButton("Отмена", null)
                .show()
        }
    }

    private fun updateStorageInfo() {
        lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) {
                fun bytes(name: String) = File(filesDir, name).walkTopDown().filter { it.isFile }.sumOf { it.length() }
                "Игры: ${bytes("roms") / 1024} КБ • Сохранения: ${(bytes("saves") + bytes("states")) / 1024} КБ\n" +
                    "Обложки: ${bytes("covers") / 1024} КБ • Свободно: ${filesDir.usableSpace / 1024 / 1024} МБ\nВерсия: 1.5"
            }
            findViewById<TextView>(R.id.storageInfo).text = info
        }
    }
}
