package com.retrotv.emu

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.hardware.input.InputManager
import android.os.*
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.retrotv.emu.Prefs.audioLowLatency
import com.retrotv.emu.Prefs.defaultFilter
import com.retrotv.emu.Prefs.renderHeight
import com.retrotv.emu.Prefs.rewindEnabled
import com.retrotv.emu.Prefs.smoothLevel
import com.swordfish.libretrodroid.*
import kotlinx.coroutines.*
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.sign

class GameActivity : AppCompatActivity(), InputManager.InputDeviceListener {
    companion object {
        const val EXTRA_ROM_PATH = "rom_path"
        const val EXTRA_SYSTEM_ID = "system_id"
        const val EXTRA_RESUME = "resume_game"
    }
    private var retroView: GLRetroView? = null
    private lateinit var root: FrameLayout
    private lateinit var rom: Rom
    private lateinit var saves: SaveStore
    private lateinit var metadata: LibraryMetadata
    private var ready = false
    private var stopped = false
    private var menuShowing = false
    private var protectingSave = true
    private var currentFilter = Prefs.FILTER_SHARP
    private var checkpointJob: Job? = null
    private var captureBusy = false
    private var generation = 0
    private val history = RewindHistory<Bitmap>(24 * 1024 * 1024, 120) { it.recycle() }
    private var rewindOverlay: LinearLayout? = null
    private var rewindImage: ImageView? = null
    private var rewindLabel: TextView? = null
    private var rewindIndex = 0
    private var rewindJob: Job? = null
    private var rewinding = false
    private var rewindStep = 500L
    private var playTime = 0L
    private var lastClock = 0L
    private var lastAuto = 0L
    private var lastSnapshot = 0L
    private val dpad = DirectionalInput()
    private var l2Axis = false
    private var r2Key = false
    private var r2Axis = false
    private var thumbL = false
    private var thumbR = false
    private var controllerId = -1
    private var audioInfo = ""
    private val inputManager by lazy { getSystemService(INPUT_SERVICE) as InputManager }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)
        root.addView(TextView(this).apply { text = "Загрузка игры…"; gravity = Gravity.CENTER }, FrameLayout.LayoutParams(-1, -1))
        metadata = LibraryMetadata(this)
        val file = intent.getStringExtra(EXTRA_ROM_PATH)?.let(::File)
        val system = SystemType.fromId(intent.getStringExtra(EXTRA_SYSTEM_ID))
        if (file == null || !file.isFile || system == null) { toast("Игра не найдена"); finish(); return }
        inputManager.registerInputDeviceListener(this, Handler(Looper.getMainLooper()))
        lifecycleScope.launch {
            try {
                val ramResult = withContext(Dispatchers.IO) {
                    rom = Rom(file, system).also { it.id }
                    saves = SaveStore(applicationContext, rom)
                    SaveWriter.flush(); saves.migrateLegacy()
                    runCatching { saves.readRam() }
                }
                if (ramResult.isFailure && !confirmWithoutRam()) { finish(); return@launch }
                currentFilter = metadata.filter(rom, defaultFilter)
                val core = CoreProvider.corePath(this@GameActivity, system) ?: error("Ядро игры отсутствует в сборке")
                val shouldResume = intent.getBooleanExtra(EXTRA_RESUME, true) && saves.has(0)
                protectingSave = shouldResume
                val data = GLRetroViewData(this@GameActivity).apply {
                    coreFilePath = core.absolutePath; gameFilePath = file.absolutePath
                    systemDirectory = File(filesDir, "system").apply { mkdirs() }.absolutePath
                    savesDirectory = saves.directory.absolutePath
                    saveRAMState = ramResult.getOrNull()
                    shader = shaderFor(currentFilter)
                    preferLowLatencyAudio = audioLowLatency; rumbleEventsEnabled = false
                }
                val view = GLRetroView(this@GameActivity, data)
                retroView = view
                lifecycleScope.launch {
                    view.getGLRetroErrors().collect { code ->
                        if (!isFinishing) {
                            ready = false; protectingSave = true
                            AlertDialog.Builder(this@GameActivity).setTitle("Ошибка запуска игры")
                                .setMessage("Код: $code. Попробуйте другой файл этой игры.").setCancelable(false)
                                .setPositiveButton("В библиотеку") { _, _ -> finish() }.show()
                        }
                    }
                }
                lifecycleScope.launch {
                    view.getGLRetroEvents().collect { event ->
                        if (event == GLRetroView.GLRetroEvents.SurfaceCreated && !ready) {
                            ready = true; stopped = false; lastClock = SystemClock.elapsedRealtime()
                            metadata.played(rom)
                            if (shouldResume) restore(0, true) else { startCheckpoints(); showHint() }
                        }
                    }
                }
                lifecycle.addObserver(view)
                root.removeAllViews(); root.addView(view, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
                val height = renderHeight
                if (height > 0) view.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
                    if (b - t > height && r > l) view.holder.setFixedSize((r - l) * height / (b - t), height)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                AlertDialog.Builder(this@GameActivity).setTitle("Не удалось открыть игру").setMessage(e.message)
                    .setCancelable(false).setPositiveButton("В библиотеку") { _, _ -> finish() }.show()
            }
        }
    }
    private suspend fun confirmWithoutRam(): Boolean = suspendCancellableCoroutine { continuation ->
        val dialog = AlertDialog.Builder(this).setTitle("Сохранение картриджа повреждено")
            .setMessage("Основная и резервная копии не читаются. Открыть игру без них? Запись нового прогресса заменит повреждённый файл.")
            .setCancelable(false).setPositiveButton("Открыть") { _, _ -> continuation.resume(true) }
            .setNegativeButton("Назад") { _, _ -> continuation.resume(false) }.show()
        continuation.invokeOnCancellation { dialog.dismiss() }
    }
    override fun onPostResume() {
        super.onPostResume()
        if (ready) {
            stopped = false; lastClock = SystemClock.elapsedRealtime()
            if (menuShowing || protectingSave || rewinding) pausePlayer() else startCheckpoints()
        }
    }
    override fun onPause() {
        checkpointJob?.cancel()
        if (rewinding) finishRewind(false, resume = false)
        if (ready) { pausePlayer(); if (!protectingSave) saveAutomatically() }
        super.onPause()
    }
    override fun onDestroy() {
        ready = false; generation++
        checkpointJob?.cancel(); rewindJob?.cancel()
        rewindImage?.setImageDrawable(null); history.clear()
        inputManager.unregisterInputDeviceListener(this)
        super.onDestroy()
    }
    private fun shaderFor(id: String): ShaderConfig = when (id) {
        Prefs.FILTER_SMOOTH -> when (smoothLevel) {
            "light" -> ShaderConfig.CUT(useDynamicBlend = false, staticSharpness = .75f)
            "medium" -> ShaderConfig.CUT(useDynamicBlend = false, staticSharpness = .4f)
            "strong" -> ShaderConfig.Default
            else -> ShaderConfig.CUT2()
        }
        Prefs.FILTER_CRT -> ShaderConfig.CRT
        Prefs.FILTER_LCD -> ShaderConfig.LCD
        else -> ShaderConfig.Sharp
    }
    private fun tickClock() {
        val now = SystemClock.elapsedRealtime()
        if (!stopped && lastClock > 0) playTime += now - lastClock
        lastClock = now
    }
    private fun pausePlayer() {
        if (!ready || stopped) return
        tickClock(); audioInfo = LibretroDroid.audioDiagnostics()
        retroView?.onPause(); LibretroDroid.pause(); stopped = true
        dpad.clear(); thumbL = false; thumbR = false
    }
    private fun resumePlayer() {
        if (!ready || !stopped || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        LibretroDroid.resume(); retroView?.onResume()
        stopped = false; lastClock = SystemClock.elapsedRealtime(); startCheckpoints()
    }
    private fun startCheckpoints() {
        checkpointJob?.cancel()
        checkpointJob = lifecycleScope.launch {
            while (isActive) {
                delay(100)
                if (!ready || stopped || menuShowing || protectingSave || rewinding || captureBusy) continue
                tickClock()
                val auto = playTime - lastAuto >= 30_000
                val snapshot = rewindEnabled && playTime - lastSnapshot >= rewindStep
                if (!auto && !snapshot) continue
                try {
                    val start = SystemClock.elapsedRealtime()
                    val state = retroView!!.serializeState(false)
                    // Weak TVs trade rewind precision for fewer serialization stalls.
                    if (SystemClock.elapsedRealtime() - start > 12) rewindStep = 1000
                    if (auto) { writeAutomatic(state); lastAuto = playTime }
                    if (snapshot) {
                        lastSnapshot = playTime
                        val time = playTime; val version = generation
                        captureBusy = true
                        captureBitmap(320) { bitmap ->
                            captureBusy = false
                            if (ready && version == generation && !rewinding) history.add(state, time, bitmap, bitmap?.byteCount ?: 0)
                            else bitmap?.recycle()
                        }
                    }
                } catch (e: Exception) { android.util.Log.w("RetroTV", "Checkpoint", e) }
            }
        }
    }
    private fun writeAutomatic(state: ByteArray) {
        val ram = retroView!!.serializeSRAM(false); val target = saves
        SaveWriter.submit(applicationContext) { target.write(0, state); target.writeRam(ram) }
    }
    private fun saveAutomatically() {
        try { writeAutomatic(retroView!!.serializeState(false)) }
        catch (e: Exception) { toast("Автосохранение не удалось: ${e.message}") }
    }
    private fun captureBitmap(width: Int, done: (Bitmap?) -> Unit) {
        val view = retroView
        if (view == null || !view.holder.surface.isValid || view.width <= 0 || view.height <= 0) { done(null); return }
        val bitmap = Bitmap.createBitmap(width, (width.toLong() * view.height / view.width).toInt().coerceIn(1, width), Bitmap.Config.RGB_565)
        try {
            PixelCopy.request(view, bitmap, { result ->
                if (result == PixelCopy.SUCCESS) done(bitmap) else { bitmap.recycle(); done(null) }
            }, Handler(Looper.getMainLooper()))
        } catch (_: Exception) { bitmap.recycle(); done(null) }
    }
    private fun capturePreview(slot: Int) {
        val target = saves
        captureBitmap(480) { bitmap -> if (bitmap != null) SaveWriter.submit(applicationContext) {
            try { target.writePreview(slot, bitmap) } finally { bitmap.recycle() }
        } }
    }
    private fun clearHistory() { generation++; history.clear(); lastSnapshot = playTime; lastAuto = playTime }
    private fun chooseSlot(saving: Boolean) {
        val slots = if (saving) (1..3).toList() else (0..3).toList()
        val labels = slots.map { slot ->
            val date = if (saves.has(slot)) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(saves.timestamp(slot))) else "пусто"
            (if (slot == 0) "Автосохранение" else "Слот $slot") + "  •  $date"
        }
        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, labels) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(16, 12, 16, 12) }
                val image = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
                row.addView(image, LinearLayout.LayoutParams(128, 80))
                row.addView(TextView(context).apply { text = labels[position]; textSize = 17f; setPadding(16, 0, 0, 0) })
                lifecycleScope.launch { image.setImageBitmap(withContext(Dispatchers.IO) { BitmapFactory.decodeFile(saves.preview(slots[position]).path) }) }
                return row
            }
        }
        AlertDialog.Builder(this).setTitle(if (saving) "Сохранить игру" else "Загрузить игру").setAdapter(adapter) { _, index ->
            val slot = slots[index]
            if (!saving) { if (saves.has(slot)) restore(slot) else toast("Слот пуст") }
            else if (saves.has(slot)) AlertDialog.Builder(this).setMessage("Заменить сохранение в слоте $slot?")
                .setPositiveButton("Заменить") { _, _ -> saveManual(slot) }.setNegativeButton("Отмена", null).show()
            else saveManual(slot)
        }.setNegativeButton("Отмена", null).show()
    }
    private fun saveManual(slot: Int) {
        try {
            val state = retroView!!.serializeState(false); val ram = retroView!!.serializeSRAM(false); val target = saves
            val job = SaveWriter.submit(applicationContext) { target.write(slot, state); target.writeRam(ram) }
            capturePreview(slot)
            lifecycleScope.launch { if (job.await()) toast("Сохранено в слот $slot") }
        } catch (e: Exception) { toast("Не удалось сохранить: ${e.message}") }
    }
    private fun restore(slot: Int, initial: Boolean = false) {
        pausePlayer(); protectingSave = true
        lifecycleScope.launch {
            try {
                val state = withContext(Dispatchers.IO) { SaveWriter.flush(); saves.read(slot) }
                check(retroView!!.unserializeState(state.bytes, false)) { "Ядро отклонило сохранение" }
                clearHistory(); protectingSave = false
                if (state.fromBackup) toast("Восстановлена резервная копия")
                if (!menuShowing) { resumePlayer(); if (initial) showHint() }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                AlertDialog.Builder(this@GameActivity).setTitle("Не удалось восстановить игру")
                    .setMessage("${e.message}\nСохранение оставлено без изменений.").setCancelable(false)
                    .setPositiveButton(if (initial) "Начать заново" else "Продолжить") { _, _ ->
                        protectingSave = false; if (!menuShowing) resumePlayer()
                    }.setNegativeButton("В библиотеку") { _, _ -> finish() }.show()
            }
        }
    }

    private fun beginRewind() {
        if (!rewindEnabled || protectingSave || menuShowing || rewinding) return
        if (history.size == 0) { toast("История ещё накапливается"); return }
        pausePlayer(); rewinding = true; generation++
        rewindIndex = history.size - 1
        rewindOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setBackgroundColor(0xEE101418.toInt()); setPadding(32, 24, 32, 24)
            rewindLabel = TextView(context).apply { textSize = 22f; gravity = Gravity.CENTER; setTextColor(Color.WHITE) }
            addView(rewindLabel, LinearLayout.LayoutParams(-1, -2))
            rewindImage = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
            addView(rewindImage, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(TextView(context).apply {
                text = "R2 удерживать — назад  •  ← → точнее\nОтпустить R2 — продолжить  •  ○ отмена"
                textSize = 17f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            }, LinearLayout.LayoutParams(-1, -2))
        }
        root.addView(rewindOverlay, FrameLayout.LayoutParams(-1, -1))
        showRewindFrame()
        rewindJob = lifecycleScope.launch {
            delay(350)
            while (isActive && rewinding) { moveRewind(-1); delay(180) }
        }
    }
    private fun showRewindFrame() {
        val entry = history[rewindIndex]
        rewindImage?.setImageBitmap(entry.preview)
        rewindLabel?.text = "Перемотка   −%.1f сек   %d / %d".format((playTime - entry.timeMs) / 1000.0, rewindIndex + 1, history.size)
    }
    private fun moveRewind(delta: Int) { rewindIndex = (rewindIndex + delta).coerceIn(0, history.size - 1); showRewindFrame() }
    private fun finishRewind(commit: Boolean, resume: Boolean = true) {
        if (!rewinding) return
        rewindJob?.cancel(); rewindImage?.setImageDrawable(null)
        root.removeView(rewindOverlay); rewindOverlay = null; rewindImage = null; rewindLabel = null
        rewinding = false
        if (commit) {
            try {
                val chosen = history[rewindIndex]
                check(retroView!!.unserializeState(chosen.state, false))
                playTime = chosen.timeMs; lastAuto = playTime; lastSnapshot = playTime
                history.discardAfter(rewindIndex)
            } catch (_: Exception) { toast("Не удалось восстановить этот момент") }
        }
        if (resume) resumePlayer()
    }
    private fun triggerR2(key: Boolean? = null, axis: Boolean? = null) {
        val before = r2Key || r2Axis
        key?.let { r2Key = it }; axis?.let { r2Axis = it }
        val after = r2Key || r2Axis
        if (!before && after) beginRewind()
        if (before && !after && rewinding) finishRewind(true)
    }
    private fun showHint() {
        val hint = TextView(this).apply {
            text = "L2 — меню   •   R2 удерживать — перемотка   •   Options — Start"
            textSize = 16f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); setBackgroundColor(0xBB101418.toInt()); setPadding(16, 12, 16, 12)
        }
        root.addView(hint, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        lifecycleScope.launch { delay(5000); root.removeView(hint) }
    }
    private fun showPauseMenu() {
        if (menuShowing || !ready || protectingSave) return
        if (rewinding) finishRewind(false, false)
        menuShowing = true; pausePlayer(); capturePreview(0)
        val content = layoutInflater.inflate(R.layout.dialog_pause, null)
        val dialog = AlertDialog.Builder(this, R.style.PauseDialog).setView(content).create()
        content.findViewById<Button>(R.id.btnResume).setOnClickListener { dialog.dismiss() }
        content.findViewById<Button>(R.id.btnSave).setOnClickListener { chooseSlot(true) }
        content.findViewById<Button>(R.id.btnLoad).setOnClickListener { chooseSlot(false) }
        val filter = content.findViewById<Button>(R.id.btnFilter)
        fun label() { filter.text = getString(R.string.filter_fmt, Prefs.filterTitle(currentFilter)) }
        label()
        filter.setOnClickListener { currentFilter = Prefs.nextFilter(currentFilter); metadata.setFilter(rom, currentFilter); retroView?.shader = shaderFor(currentFilter); label() }
        content.findViewById<Button>(R.id.btnAudioInfo).setOnClickListener {
            AlertDialog.Builder(this).setTitle("Диагностика звука").setMessage("$audioInfo\n\nRefills — нехватка данных. Xruns — сбои вывода.\nШаг перемотки: $rewindStep мс, история: ${history.size} снимков.")
                .setPositiveButton("Закрыть", null).show()
        }
        fun exitGame() { dialog.setOnDismissListener(null); dialog.dismiss(); finish() }
        content.findViewById<Button>(R.id.btnExit).setOnClickListener { exitGame() }
        dialog.setOnKeyListener { _, code, event ->
            when (code) {
                KeyEvent.KEYCODE_BUTTON_B -> { if (event.action == KeyEvent.ACTION_UP) dialog.dismiss(); true }
                KeyEvent.KEYCODE_BACK -> { if (event.action == KeyEvent.ACTION_UP) exitGame(); true }
                else -> false
            }
        }
        dialog.setOnDismissListener { menuShowing = false; r2Key = false; r2Axis = false; if (!protectingSave) resumePlayer() }
        dialog.show(); content.findViewById<Button>(R.id.btnResume).requestFocus()
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!ready || menuShowing || protectingSave) return super.dispatchKeyEvent(event)
        val view = retroView ?: return super.dispatchKeyEvent(event)
        val down = event.action == KeyEvent.ACTION_DOWN
        if (event.isFromSource(InputDevice.SOURCE_GAMEPAD)) controllerId = event.deviceId
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_R2) { triggerR2(key = down); return true }
        if (rewinding) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> if (down) finishRewind(false)
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> if (down) { rewindJob?.cancel(); moveRewind(if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1) }
            }
            return true
        }
        when (event.keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_L2 -> { if (down && event.repeatCount == 0) showPauseMenu(); return true }
            KeyEvent.KEYCODE_BUTTON_THUMBL -> { thumbL = down; if (thumbL && thumbR) showPauseMenu(); return true }
            KeyEvent.KEYCODE_BUTTON_THUMBR -> { thumbR = down; if (thumbL && thumbR) showPauseMenu(); return true }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val (x, y) = dpad.update(event.keyCode, down); view.sendMotionEvent(GLRetroView.MOTION_SOURCE_DPAD, x, y); return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { view.sendKeyEvent(event.action, KeyEvent.KEYCODE_BUTTON_A); return true }
        }
        if (event.isFromSource(InputDevice.SOURCE_GAMEPAD) || event.isFromSource(InputDevice.SOURCE_JOYSTICK)) { view.sendKeyEvent(event.action, event.keyCode); return true }
        return super.dispatchKeyEvent(event)
    }
    private var lastScrub = 0L
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (!ready || menuShowing || protectingSave || !event.isFromSource(InputDevice.SOURCE_JOYSTICK)) return super.dispatchGenericMotionEvent(event)
        controllerId = event.deviceId
        val rt = maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS))
        triggerR2(axis = if (r2Axis) rt > .35f else rt > .6f)
        if (rewinding) {
            val x = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            if (abs(x) > .5f && SystemClock.elapsedRealtime() - lastScrub > 150) {
                rewindJob?.cancel(); moveRewind(sign(x).toInt()); lastScrub = SystemClock.elapsedRealtime()
            }
            return true
        }
        val lt = maxOf(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE))
        if (lt > .6f && !l2Axis) { l2Axis = true; showPauseMenu(); return true }
        if (lt < .35f) l2Axis = false
        fun axis(hat: Int, stick: Int, held: Float): Float = when {
            abs(event.getAxisValue(hat)) > .3f -> event.getAxisValue(hat)
            held != 0f -> held
            abs(event.getAxisValue(stick)) > .5f -> sign(event.getAxisValue(stick))
            else -> 0f
        }
        val (x, y) = dpad.vector()
        retroView?.sendMotionEvent(GLRetroView.MOTION_SOURCE_DPAD, axis(MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_X, x), axis(MotionEvent.AXIS_HAT_Y, MotionEvent.AXIS_Y, y))
        return true
    }
    override fun onInputDeviceRemoved(deviceId: Int) { if (deviceId == controllerId) { r2Key = false; r2Axis = false; showPauseMenu(); toast("Геймпад отключён. Игра на паузе.") } }
    override fun onInputDeviceAdded(deviceId: Int) = Unit
    override fun onInputDeviceChanged(deviceId: Int) = Unit
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
