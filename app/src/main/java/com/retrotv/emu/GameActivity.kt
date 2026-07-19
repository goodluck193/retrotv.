package com.retrotv.emu

import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
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
import com.retrotv.emu.Prefs.audioLowLatency
import com.retrotv.emu.Prefs.defaultFilter
import com.retrotv.emu.Prefs.renderHeight
import com.retrotv.emu.Prefs.rewindEnabled
import com.retrotv.emu.Prefs.smoothLevel
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroViewData
import com.swordfish.libretrodroid.ShaderConfig
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs
import kotlin.math.sign

class GameActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ROM_PATH = "rom_path"
        const val EXTRA_SYSTEM_ID = "system_id"

        // Отмотка назад: один лёгкий снимок раз в 10 секунд, максимум два в памяти.
        // Так нагрузка и расход памяти минимальны — никаких утечек и зависаний.
        private const val REWIND_SNAPSHOT_MS = 10_000L
        private const val REWIND_MAX_SNAPSHOTS = 2

        // Состояние для восстановления после смены фильтра
        private var pendingResumeState: ByteArray? = null
    }

    private var retroView: GLRetroView? = null
    private lateinit var rom: Rom
    private var currentFilter: String = Prefs.FILTER_SHARP

    private var menuShowing = false
    private var thumbLDown = false
    private var thumbRDown = false
    private var l2Held = false
    private var rewindOn = true

    // --- Отмотка назад (R2 = прыжок на ~10-20 секунд) ---
    private val rewindSlots = ArrayDeque<ByteArray>()
    private var snapshotBusy = false
    private var snapshotJob: Job? = null
    private var r2Held = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestSixtyHz()
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
            // Режим звука выбирается в настройках: на разных ТВ лучше разный
            preferLowLatencyAudio = audioLowLatency
            rumbleEventsEnabled = false
        }

        val view = GLRetroView(this, data)
        retroView = view
        lifecycle.addObserver(view)

        // Центрируем окно игры: чёрные полосы будут симметрично по краям
        val root = FrameLayout(this)
        root.setBackgroundColor(android.graphics.Color.BLACK)
        root.addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        )
        setContentView(root)

        // Ограничение разрешения рендера: на 4K-панелях полный рендер
        // перегружает GPU телевизора, кадры не успевают и звук трещит.
        // 720p для пиксельной графики неотличим, а нагрузка падает в разы.
        val targetH = renderHeight
        if (targetH > 0) {
            view.addOnLayoutChangeListener { v, l, tTop, r, b, _, _, _, _ ->
                val w = r - l
                val h = b - tTop
                if (h > targetH && w > 0) {
                    val scaledW = (w.toLong() * targetH / h).toInt()
                    try { view.holder.setFixedSize(scaledW, targetH) } catch (_: Exception) { }
                }
            }
        }

        rewindOn = rewindEnabled
        if (rewindOn) startSnapshots()

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

    override fun onDestroy() {
        snapshotJob?.cancel()
        rewindSlots.clear()
        super.onDestroy()
    }

    private fun shaderFor(id: String): ShaderConfig = when (id) {
        Prefs.FILTER_SMOOTH -> when (smoothLevel) {
            "light" -> ShaderConfig.CUT(useDynamicBlend = false, staticSharpness = 0.75f)
            "medium" -> ShaderConfig.CUT(useDynamicBlend = false, staticSharpness = 0.4f)
            "strong" -> ShaderConfig.Default // билинейное, самое размытое
            else -> ShaderConfig.CUT2()      // умный апскейл: сглаживает без «мыла»
        }
        Prefs.FILTER_CRT -> ShaderConfig.CRT
        Prefs.FILTER_LCD -> ShaderConfig.LCD
        else -> ShaderConfig.Sharp
    }

    /**
     * Игры NES/SNES/Sega работают в 60 Гц. Если панель ТВ находится в режиме
     * 50 Гц (частая настройка по умолчанию в PAL-регионах), звук постоянно
     * рассинхронизируется и трещит. Просим систему переключить экран на 60 Гц
     * на время игры.
     */
    private fun requestSixtyHz() {
        try {
            @Suppress("DEPRECATION")
            val display = windowManager.defaultDisplay ?: return
            val current = display.mode
            val target = display.supportedModes
                .filter {
                    it.physicalWidth == current.physicalWidth &&
                    it.physicalHeight == current.physicalHeight
                }
                .minByOrNull { abs(it.refreshRate - 60f) }
            val lp = window.attributes
            if (target != null && abs(target.refreshRate - 60f) < 1f) {
                lp.preferredDisplayModeId = target.modeId
            } else {
                @Suppress("DEPRECATION")
                lp.preferredRefreshRate = 60f
            }
            window.attributes = lp
        } catch (_: Exception) { }
    }

    private fun hideSystemUi() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    // ------------------------------------------------------------------
    // ПЕРЕМОТКА НАЗАД (удержание R2)
    // ------------------------------------------------------------------

    /** Раз в 10 секунд тихо запоминаем состояние игры (в памяти максимум два снимка). */
    private fun startSnapshots() {
        snapshotJob?.cancel()
        snapshotJob = lifecycleScope.launch {
            while (isActive) {
                delay(REWIND_SNAPSHOT_MS)
                if (menuShowing || snapshotBusy) continue
                val v = retroView ?: continue
                snapshotBusy = true
                try {
                    val s = v.serializeState()
                    if (s.isNotEmpty()) {
                        rewindSlots.addLast(s)
                        while (rewindSlots.size > REWIND_MAX_SNAPSHOTS) {
                            rewindSlots.removeFirst()
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    snapshotBusy = false
                }
            }
        }
    }

    /** Нажатие R2: мгновенный прыжок к снимку ~10-20 секунд назад. */
    private fun rewindBack() {
        if (!rewindOn) return
        val v = retroView ?: return
        val s = rewindSlots.removeFirstOrNull() // самый старый = дальше в прошлое
        if (s == null) {
            Toast.makeText(this, "Отматывать пока некуда", Toast.LENGTH_SHORT).show()
            return
        }
        rewindSlots.clear() // остальные снимки теперь «из будущего» — убираем
        try {
            v.unserializeState(s)
            Toast.makeText(this, "⏪ отмотано назад", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) { }
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
            // R2 = прыжок назад на ~10-20 секунд
            KeyEvent.KEYCODE_BUTTON_R2 -> {
                if (down && !r2Held) rewindBack()
                r2Held = down
                return true
            }

            // L2 = меню паузы
            KeyEvent.KEYCODE_BUTTON_L2 -> {
                if (down) showPauseMenu()
                return true
            }

            // L3 + R3 одновременно = меню паузы (запасной вариант)
            KeyEvent.KEYCODE_BUTTON_THUMBL -> {
                thumbLDown = down
                if (thumbLDown && thumbRDown) { showPauseMenu(); return true }
            }
            KeyEvent.KEYCODE_BUTTON_THUMBR -> {
                thumbRDown = down
                if (thumbLDown && thumbRDown) { showPauseMenu(); return true }
            }

            // BACK (пульт ТВ) = меню паузы
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

            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                view.sendKeyEvent(event.action, KeyEvent.KEYCODE_BUTTON_A)
                return true
            }
        }

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

        // Движение = крестовина ИЛИ левый стик (что отклонено — то и работает)
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        val stickX = event.getAxisValue(MotionEvent.AXIS_X)
        val stickY = event.getAxisValue(MotionEvent.AXIS_Y)

        fun combine(hat: Float, stick: Float): Float = when {
            abs(hat) > 0.3f -> hat
            abs(stick) > 0.5f -> sign(stick)  // стик с мёртвой зоной
            else -> 0f
        }
        view.sendMotionEvent(
            GLRetroView.MOTION_SOURCE_DPAD,
            combine(hatX, stickX),
            combine(hatY, stickY)
        )
        // Аналоговые оси тоже отправляем (для ядер, которые их понимают)
        view.sendMotionEvent(GLRetroView.MOTION_SOURCE_ANALOG_LEFT, stickX, stickY)
        view.sendMotionEvent(
            GLRetroView.MOTION_SOURCE_ANALOG_RIGHT,
            event.getAxisValue(MotionEvent.AXIS_Z),
            event.getAxisValue(MotionEvent.AXIS_RZ)
        )

        // R2 как ось (курок) = прыжок назад, срабатывает по нажатию
        val rt = maxOf(
            event.getAxisValue(MotionEvent.AXIS_RTRIGGER),
            event.getAxisValue(MotionEvent.AXIS_GAS)
        )
        if (rt > 0.6f && !r2Held) {
            r2Held = true
            rewindBack()
        } else if (rt < 0.4f) {
            r2Held = false
        }

        // L2 как ось = меню паузы (со «защёлкой», чтобы не открывалось повторно)
        val lt = maxOf(
            event.getAxisValue(MotionEvent.AXIS_LTRIGGER),
            event.getAxisValue(MotionEvent.AXIS_BRAKE)
        )
        if (lt > 0.6f && !l2Held) {
            l2Held = true
            showPauseMenu()
        } else if (lt < 0.4f) {
            l2Held = false
        }
        return true
    }

    // ------------------------------------------------------------------
    // МЕНЮ ПАУЗЫ (L2, или L3+R3, или «назад» на пульте)
    // ------------------------------------------------------------------

    private fun showPauseMenu() {
        if (menuShowing) return
        menuShowing = true
        val view = retroView
        try { view?.onPause() } catch (_: Exception) { }

        val content = layoutInflater.inflate(R.layout.dialog_pause, null)
        val dialog = AlertDialog.Builder(this, R.style.PauseDialog)
            .setView(content)
            .setCancelable(true)
            .create()

        val btnFilter = content.findViewById<Button>(R.id.btnFilter)
        btnFilter.text = getString(R.string.filter_fmt, Prefs.filterTitle(currentFilter))

        content.findViewById<Button>(R.id.btnResume).setOnClickListener { dialog.dismiss() }

        content.findViewById<Button>(R.id.btnSave).setOnClickListener {
            saveState(); dialog.dismiss()
        }
        content.findViewById<Button>(R.id.btnLoad).setOnClickListener {
            loadState(); dialog.dismiss()
        }
        btnFilter.setOnClickListener {
            currentFilter = Prefs.nextFilter(currentFilter)
            btnFilter.text = getString(R.string.filter_fmt, Prefs.filterTitle(currentFilter))
        }
        content.findViewById<Button>(R.id.btnExit).setOnClickListener {
            dialog.setOnDismissListener(null)
            dialog.dismiss()
            menuShowing = false
            finish()
        }

        dialog.setOnDismissListener {
            menuShowing = false
            l2Held = false
            r2Held = false
            val filterChanged = shaderFor(currentFilter) != shaderFor(defaultFilter)
            if (filterChanged) {
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
        this.defaultFilter = currentFilter
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
