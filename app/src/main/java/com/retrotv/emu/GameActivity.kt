package com.retrotv.emu

import android.app.AlertDialog
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.retrotv.emu.Prefs.defaultFilter
import com.retrotv.emu.Prefs.fastForwardSpeed
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroViewData
import com.swordfish.libretrodroid.ShaderConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class GameActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ROM_PATH = "rom_path"
        const val EXTRA_SYSTEM_ID = "system_id"

        // Состояние, которое нужно восстановить после смены фильтра
        // (recreate() пересоздаёт activity, а игра продолжается с того же места)
        private var pendingResumeState: ByteArray? = null
    }

    private var retroView: GLRetroView? = null
    private lateinit var rom: Rom
    private var currentFilter: String = Prefs.FILTER_SHARP

    private var menuShowing = false
    private var thumbLDown = false
    private var thumbRDown = false
    private var fastForward = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUi()

        val romPath = intent.getStringExtra(EXTRA_ROM_PATH)
        val system = SystemType.fromId(intent.getStringExtra(EXTRA_SYSTEM_ID))
        val romFile = romPath?.let { File(it) }
        if (romFile == null || !romFile.exists() || system == null) {
            Toast.makeText(this, "Ром не найден", Toast.LENGTH_LONG).show()
            finish(); return
        }
        rom = Rom(romFile, system)

        val core = CoreProvider.corePath(this, system)
        if (core == null) { finish(); return }

        currentFilter = defaultFilter

        val data = GLRetroViewData(this).apply {
            coreFilePath = core.absolutePath
            gameFilePath = romFile.absolutePath
            systemDirectory = File(filesDir, "system").apply { mkdirs() }.absolutePath
            savesDirectory = File(filesDir, "sram").apply { mkdirs() }.absolutePath
            shader = shaderFor(currentFilter)
            preferLowLatencyAudio = true
            rumbleEventsEnabled = false
        }

        val view = GLRetroView(this, data)
        retroView = view
        lifecycle.addObserver(view)

        val root = FrameLayout(this)
        root.addView(view)
        setContentView(root)

        // Восстановление после смены фильтра
        pendingResumeState?.let { state ->
            pendingResumeState = null
            lifecycleScope.launch {
                repeat(12) {
                    delay(250)
                    try {
                        if (view.unserializeState(state)) return@launch
                    } catch (_: Exception) { }
                }
            }
        }
    }

    private fun shaderFor(id: String): ShaderConfig = when (id) {
        Prefs.FILTER_SMOOTH -> ShaderConfig.Default   // билинейное сглаживание
        Prefs.FILTER_CRT -> ShaderConfig.CRT
        Prefs.FILTER_LCD -> ShaderConfig.LCD
        else -> ShaderConfig.Sharp                    // чёткие пиксели
    }

    private fun hideSystemUi() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    // ------------------------------------------------------------------
    // ВВОД: DualSense (PS5) и пульт ТВ
    // ------------------------------------------------------------------

    private fun isGamepad(event: KeyEvent): Boolean {
        val src = event.source
        return (src and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD) ||
               (src and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK) ||
               (src and InputDevice.SOURCE_DPAD == InputDevice.SOURCE_DPAD)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (menuShowing) return super.dispatchKeyEvent(event)
        val view = retroView ?: return super.dispatchKeyEvent(event)
        val down = event.action == KeyEvent.ACTION_DOWN

        when (event.keyCode) {
            // R2 как кнопка (некоторые прошивки шлют её кнопкой, а не осью)
            KeyEvent.KEYCODE_BUTTON_R2 -> { setFastForward(down); return true }

            // L3 + R3 одновременно = меню паузы
            KeyEvent.KEYCODE_BUTTON_THUMBL -> {
                thumbLDown = down
                if (thumbLDown && thumbRDown) { showPauseMenu(); return true }
            }
            KeyEvent.KEYCODE_BUTTON_THUMBR -> {
                thumbRDown = down
                if (thumbLDown && thumbRDown) { showPauseMenu(); return true }
            }

            // BACK (кнопка «назад» на пульте ТВ) = меню паузы
            KeyEvent.KEYCODE_BACK -> {
                if (down) showPauseMenu()
                return true
            }

            // Крестовина пульта ТВ -> D-pad эмулятора
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val x = when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> -1f
                    KeyEvent.KEYCODE_DPAD_RIGHT -> 1f
                    else -> 0f
                }
                val y = when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> -1f
                    KeyEvent.KEYCODE_DPAD_DOWN -> 1f
                    else -> 0f
                }
                view.sendMotionEvent(
                    GLRetroView.MOTION_SOURCE_DPAD,
                    if (down) x else 0f,
                    if (down) y else 0f
                )
                return true
            }

            // Центральная кнопка пульта = кнопка A
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                view.sendKeyEvent(event.action, KeyEvent.KEYCODE_BUTTON_A)
                return true
            }
        }

        // Все остальные кнопки геймпада (крест/круг/квадрат/треугольник,
        // L1/R1, L2, Start/Select) — напрямую в эмулятор
        if (isGamepad(event)) {
            view.sendKeyEvent(event.action, event.keyCode)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (menuShowing) return super.dispatchGenericMotionEvent(event)
        val view = retroView ?: return super.dispatchGenericMotionEvent(event)
        val isJoystick = event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
        if (!isJoystick) return super.dispatchGenericMotionEvent(event)

        // Крестовина DualSense приходит как оси HAT
        view.sendMotionEvent(
            GLRetroView.MOTION_SOURCE_DPAD,
            event.getAxisValue(MotionEvent.AXIS_HAT_X),
            event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        )
        // Левый стик — дублирует крестовину (удобно в платформерах)
        view.sendMotionEvent(
            GLRetroView.MOTION_SOURCE_ANALOG_LEFT,
            event.getAxisValue(MotionEvent.AXIS_X),
            event.getAxisValue(MotionEvent.AXIS_Y)
        )
        view.sendMotionEvent(
            GLRetroView.MOTION_SOURCE_ANALOG_RIGHT,
            event.getAxisValue(MotionEvent.AXIS_Z),
            event.getAxisValue(MotionEvent.AXIS_RZ)
        )

        // R2 как ось (курок) = перемотка, пока удерживается
        val rt = maxOf(
            event.getAxisValue(MotionEvent.AXIS_RTRIGGER),
            event.getAxisValue(MotionEvent.AXIS_GAS)
        )
        setFastForward(rt > 0.5f)
        return true
    }

    private fun setFastForward(enabled: Boolean) {
        if (fastForward == enabled) return
        fastForward = enabled
        retroView?.frameSpeed = if (enabled) fastForwardSpeed else 1
    }

    // ------------------------------------------------------------------
    // МЕНЮ ПАУЗЫ
    // ------------------------------------------------------------------

    private fun showPauseMenu() {
        if (menuShowing) return
        menuShowing = true
        setFastForward(false)
        val view = retroView
        try { view?.onPause() } catch (_: Exception) { } // остановить эмуляцию и звук

        val content = layoutInflater.inflate(R.layout.dialog_pause, null)
        val dialog = AlertDialog.Builder(this, R.style.PauseDialog)
            .setView(content)
            .setCancelable(true)
            .create()

        val btnFilter = content.findViewById<Button>(R.id.btnFilter)
        btnFilter.text = getString(R.string.filter_fmt, Prefs.filterTitle(currentFilter))

        content.findViewById<Button>(R.id.btnResume).setOnClickListener { dialog.dismiss() }

        content.findViewById<Button>(R.id.btnSave).setOnClickListener {
            saveState()
            dialog.dismiss()
        }

        content.findViewById<Button>(R.id.btnLoad).setOnClickListener {
            loadState()
            dialog.dismiss()
        }

        btnFilter.setOnClickListener {
            currentFilter = Prefs.nextFilter(currentFilter)
            btnFilter.text = getString(R.string.filter_fmt, Prefs.filterTitle(currentFilter))
        }

        content.findViewById<Button>(R.id.btnExit).setOnClickListener {
            dialog.setOnDismissListener(null)
            dialog.dismiss()
            menuShowing = false
            finish() // возврат на главный экран
        }

        dialog.setOnDismissListener {
            menuShowing = false
            val filterChanged = currentFilter != defaultFilter &&
                shaderFor(currentFilter) != shaderFor(defaultFilter)
            if (filterChanged) {
                // Смена шейдера требует пересоздания вида: сохраняем состояние
                // игры в память и пересоздаём экран — игра продолжится с места паузы.
                applyFilterAndRestart()
            } else {
                try { view?.onResume() } catch (_: Exception) { }
            }
        }
        dialog.show()
        content.findViewById<Button>(R.id.btnResume).requestFocus()
    }

    private fun applyFilterAndRestart() {
        val view = retroView ?: return
        try {
            pendingResumeState = view.serializeState()
        } catch (_: Exception) {
            pendingResumeState = null
        }
        val chosen = currentFilter
        this.defaultFilter = chosen // запомнить как текущий фильтр
        recreate()
    }

    private fun saveState() {
        val view = retroView ?: return
        try {
            val bytes = view.serializeState()
            if (bytes.isEmpty()) throw IllegalStateException("empty state")
            val f = RomLibrary.stateFile(this, rom)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Не удалось сохранить: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun loadState() {
        val view = retroView ?: return
        val f = RomLibrary.stateFile(this, rom)
        if (!f.exists()) {
            Toast.makeText(this, R.string.no_save, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            if (!view.unserializeState(f.readBytes())) throw IllegalStateException("core rejected state")
            Toast.makeText(this, R.string.loaded, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Не удалось загрузить: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
