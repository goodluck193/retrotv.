package com.retrotv.emu

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.retrotv.emu.Prefs.audioLowLatency
import com.retrotv.emu.Prefs.defaultFilter
import com.retrotv.emu.Prefs.downloadCovers
import com.retrotv.emu.Prefs.renderHeight
import com.retrotv.emu.Prefs.rewindEnabled
import com.retrotv.emu.Prefs.smoothLevel
import com.retrotv.emu.Prefs.sidebarTheme
import kotlinx.coroutines.*
import java.io.File

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        findViewById<Button>(R.id.btnLanguage).apply {
            text = getString(R.string.language) + " · " + AppLanguage.names[AppLanguage.tags.indexOf(AppLanguage.tag(this@SettingsActivity)).coerceAtLeast(0)]
            setOnClickListener {
                AlertDialog.Builder(this@SettingsActivity).setTitle(R.string.language)
                    .setSingleChoiceItems(AppLanguage.names, AppLanguage.tags.indexOf(AppLanguage.tag(this@SettingsActivity))) { dialog, index ->
                        dialog.dismiss(); AppLanguage.select(this@SettingsActivity, AppLanguage.tags[index])
                    }.show()
            }
        }
        findViewById<Button>(R.id.btnControls).setOnClickListener {
            AlertDialog.Builder(this).setTitle(R.string.controls).setMessage(R.string.controls_help).setPositiveButton(R.string.close, null).show()
        }
        val wallpapers = listOf("black", "midnight", "grid", "dunes", "forest", "arcade")
        val wallpaperNames = intArrayOf(R.string.wallpaper_black, R.string.wallpaper_midnight, R.string.wallpaper_grid,
            R.string.wallpaper_dunes, R.string.wallpaper_forest, R.string.wallpaper_arcade).map { getString(it) }.toTypedArray()
        val preview = findViewById<View>(R.id.wallpaperPreview)
        val wallpaperButton = findViewById<Button>(R.id.btnWallpaper)
        fun showWallpaper() {
            wallpaperButton.text = getString(R.string.wallpaper) + " · " + wallpaperNames[wallpapers.indexOf(sidebarTheme).coerceAtLeast(0)]
            preview.background = SidebarDrawable(sidebarTheme)
        }
        showWallpaper()
        wallpaperButton.setOnClickListener {
            AlertDialog.Builder(this).setTitle(R.string.wallpaper).setSingleChoiceItems(wallpaperNames, wallpapers.indexOf(sidebarTheme)) { dialog, index ->
                sidebarTheme = wallpapers[index]; showWallpaper(); dialog.dismiss()
            }.show()
        }
        val filterIds = listOf(Prefs.FILTER_SHARP to R.id.fSharp, Prefs.FILTER_SMOOTH to R.id.fSmooth, Prefs.FILTER_CRT to R.id.fCrt, Prefs.FILTER_LCD to R.id.fLcd)
        bind(R.id.filterGroup, filterIds.firstOrNull { it.first == defaultFilter }?.second ?: R.id.fSharp) { id -> defaultFilter = filterIds.first { it.second == id }.first }
        val smoothIds = listOf("basic" to R.id.slBasic, "smart" to R.id.slSmart, "light" to R.id.slLight, "medium" to R.id.slMedium, "strong" to R.id.slStrong)
        bind(R.id.smoothGroup, smoothIds.firstOrNull { it.first == smoothLevel }?.second ?: R.id.slBasic) { id -> smoothLevel = smoothIds.first { it.second == id }.first }
        bind(R.id.audioGroup, if (audioLowLatency) R.id.aLow else R.id.aStd) { audioLowLatency = it == R.id.aLow }
        bind(R.id.resGroup, when (renderHeight) { 1080 -> R.id.r1080; 0 -> R.id.rNative; else -> R.id.r720 }) { renderHeight = when (it) { R.id.r1080 -> 1080; R.id.rNative -> 0; else -> 720 } }
        bind(R.id.rewGroup, if (rewindEnabled) R.id.rewOn else R.id.rewOff) { rewindEnabled = it == R.id.rewOn }
        findViewById<CheckBox>(R.id.downloadCovers).apply {
            isChecked = downloadCovers
            setOnCheckedChangeListener { _, checked -> downloadCovers = checked }
        }
        updateStorageInfo()
        findViewById<Button>(R.id.btnClearCovers).setOnClickListener { button ->
            button.isEnabled = false
            lifecycleScope.launch {
                try { CoverArt.clearDisk(applicationContext); updateStorageInfo() }
                catch (e: Exception) { if (e is CancellationException) throw e; showError(e) }
                finally { button.isEnabled = true }
            }
        }
        findViewById<Button>(R.id.btnClearStates).setOnClickListener {
            AlertDialog.Builder(this).setTitle(R.string.delete_states_title).setMessage(R.string.delete_states_body)
                .setPositiveButton(R.string.delete) { _, _ -> lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            SaveWriter.flush()
                            RomLibrary.statesDir(this@SettingsActivity).listFiles()?.forEach { it.delete() }
                            File(filesDir, "saves").walkTopDown().filter { it.isFile && (it.name.contains(".state") || it.extension == "jpg") }.forEach { it.delete() }
                        }
                        updateStorageInfo()
                    } catch (e: Exception) { if (e is CancellationException) throw e; showError(e) }
                } }.setNegativeButton(R.string.cancel, null).show()
        }
        findViewById<Button>(R.id.btnLicenses).setOnClickListener {
            AlertDialog.Builder(this).setTitle(R.string.licenses).setMessage(R.string.legal_summary)
                .setPositiveButton(R.string.license_files) { _, _ -> showLicenses() }.setNegativeButton(R.string.close, null).show()
        }
    }
    private fun bind(group: Int, selected: Int, change: (Int) -> Unit) {
        findViewById<RadioGroup>(group).apply { check(selected); setOnCheckedChangeListener { _, id -> if (id != -1) change(id) } }
    }
    private fun showError(e: Exception) {
        AlertDialog.Builder(this).setTitle(R.string.operation_failed).setMessage(e.userMessage(this)).setPositiveButton(R.string.close, null).show()
    }
    private fun showLicenses() = lifecycleScope.launch {
        val files = withContext(Dispatchers.IO) {
            fun walk(path: String): List<String> = assets.list(path).orEmpty().flatMap { child ->
                val next = "$path/$child"; if (assets.list(next).isNullOrEmpty()) listOf(next) else walk(next)
            }
            walk("licenses").sorted()
        }
        AlertDialog.Builder(this@SettingsActivity).setTitle(R.string.license_files).setItems(files.map { it.removePrefix("licenses/") }.toTypedArray()) { _, index ->
            lifecycleScope.launch {
                val text = withContext(Dispatchers.IO) { assets.open(files[index]).bufferedReader().use { it.readText() } }
                val body = TextView(this@SettingsActivity).apply { this.text = text; textSize = 14f; setPadding(24, 16, 24, 16); setTextIsSelectable(true) }
                val scroll = ScrollView(this@SettingsActivity).apply { addView(body); isFocusable = true }
                AlertDialog.Builder(this@SettingsActivity).setTitle(files[index].substringAfterLast('/')).setView(scroll).setPositiveButton(R.string.close, null).show()
            }
        }.setNegativeButton(R.string.close, null).show()
    }
    private fun updateStorageInfo() = lifecycleScope.launch {
        val sizes = withContext(Dispatchers.IO) {
            fun bytes(name: String) = File(filesDir, name).walkTopDown().filter { it.isFile }.sumOf { it.length() }
            longArrayOf(bytes("roms") / 1024, (bytes("saves") + bytes("states")) / 1024, bytes("covers") / 1024, filesDir.usableSpace / 1024 / 1024)
        }
        findViewById<TextView>(R.id.storageInfo).text = getString(R.string.storage_info, sizes[0], sizes[1], sizes[2], sizes[3], "1.6.0")
    }
}
