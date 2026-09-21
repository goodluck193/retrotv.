package com.retrotv.emu

import android.app.AlertDialog
import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.graphics.Bitmap
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
import com.retrotv.emu.Prefs.sidebarTheme
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
    private val memoryManager by lazy { getSystemService(ACTIVITY_SERVICE) as ActivityManager }
    private val rewindBudget by lazy { ResourceBudget.rewind(Runtime.getRuntime().maxMemory(), memoryManager.isLowRamDevice) }
    private val history by lazy { RewindHistory<Bitmap>(rewindBudget, 120) { it.recycle() } }
    private var memoryLimited = false
    private var memoryJob: Job? = null
    private var exiting = false
    private var rewindOverlay: LinearLayout? = null
    private var rewindImage: ImageView? = null
    private var rewindLabel: TextView? = null
    private var rewindIndex = 0
    private var rewindJob: Job? = null
    private var rewindOrigin: ByteArray? = null
    private var restoreJob: Job? = null
    private var rewinding = false
    private var rewindStep = 500L
    private var playTime = 0L
    private var lastClock = 0L
    private var lastAuto = 0L
    private var lastSnapshot = 0L
    private val sentKeys = mutableSetOf<Int>()
    private val leftShoulder = ButtonLatch { sendButton(KeyEvent.KEYCODE_BUTTON_L1, it) }
    private val rightShoulder = ButtonLatch { sendButton(KeyEvent.KEYCODE_BUTTON_R1, it) }
    private val rewindButton = ButtonLatch { if (it) beginRewind() else if (rewinding) finishRewind(true) }
    private var l2Axis = false
    private var r2Axis = false
    private var gameAspect = 4f / 3f
    private var controllerId = -1
    private var audioInfo = ""
    private val inputManager by lazy { getSystemService(INPUT_SERVICE) as InputManager }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        root = FrameLayout(this).apply { background = SidebarDrawable(sidebarTheme) }
        setContentView(root)
        root.addView(TextView(this).apply { text = getString(R.string.loading_game); gravity = Gravity.CENTER }, FrameLayout.LayoutParams(-1, -1))
        metadata = LibraryMetadata(this)
        val file = intent.getStringExtra(EXTRA_ROM_PATH)?.let(::File)
        val system = SystemType.fromId(intent.getStringExtra(EXTRA_SYSTEM_ID))
        if (file == null || !file.isFile || system == null) { toast(getString(R.string.game_missing)); finish(); return }
        inputManager.registerInputDeviceListener(this, Handler(Looper.getMainLooper()))
        lifecycleScope.launch {
            try {
                val ramResult = withContext(Dispatchers.IO) {
                    require(file.length() in 1..system.maxRomBytes) { getString(R.string.invalid_rom_size) }
                    rom = Rom(file, system).also { it.id }
                    saves = SaveStore(applicationContext, rom)
                    SaveWriter.flush(); saves.migrateLegacy()
                    runCatching { saves.readRam() }
                }
                if (ramResult.isFailure && !confirmWithoutRam()) { finish(); return@launch }
                currentFilter = metadata.filter(rom, defaultFilter)
                val core = CoreProvider.corePath(this@GameActivity, system) ?: error(getString(R.string.core_missing))
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
                            runCatching { pausePlayer() }
                            ready = false; protectingSave = true
                            AlertDialog.Builder(this@GameActivity).setTitle(getString(R.string.game_start_failed))
                                .setMessage(getString(R.string.game_start_code, code)).setCancelable(false)
                                .setPositiveButton(getString(R.string.to_library)) { _, _ -> finish() }.show()
                        }
                    }
                }
                lifecycleScope.launch {
                    view.getGLRetroEvents().collect { event ->
                        if (event == GLRetroView.GLRetroEvents.SurfaceCreated && !ready) {
                            ready = true; stopped = false; lastClock = SystemClock.elapsedRealtime()
                            gameAspect = LibretroDroid.getAspectRatio().takeIf { it.isFinite() && it in .5f..3f } ?: (4f / 3f)
                            fitGameSurface()
                            setScreenAwake(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                            metadata.played(rom)
                            if (shouldResume) restore(0, true) else { startCheckpoints(); showHint() }
                        }
                    }
                }
                lifecycle.addObserver(view)
                root.removeAllViews(); root.addView(view, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
                root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitGameSurface() }
                val height = renderHeight
                if (height > 0) view.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
                    if (b - t > height && r > l) view.holder.setFixedSize((r - l) * height / (b - t), height)
                }
            } catch (e: Throwable) {
                if (e !is Exception && e !is OutOfMemoryError) throw e
                if (e is OutOfMemoryError) limitMemory()
                if (e is CancellationException) throw e
                AlertDialog.Builder(this@GameActivity).setTitle(getString(R.string.game_open_failed))
                    .setMessage(if (e is OutOfMemoryError) getString(R.string.low_memory) else e.userMessage(this@GameActivity))
                    .setCancelable(false).setPositiveButton(getString(R.string.to_library)) { _, _ -> finish() }.show()
            }
        }
    }
    private suspend fun confirmWithoutRam(): Boolean = suspendCancellableCoroutine { continuation ->
        val dialog = AlertDialog.Builder(this).setTitle(getString(R.string.ram_damaged))
            .setMessage(getString(R.string.ram_damaged_body))
            .setCancelable(false).setPositiveButton(getString(R.string.open)) { _, _ -> continuation.resume(true) }
            .setNegativeButton(getString(R.string.back)) { _, _ -> continuation.resume(false) }.show()
        continuation.invokeOnCancellation { dialog.dismiss() }
    }
    override fun onPostResume() {
        super.onPostResume()
        memoryJob?.cancel()
        memoryJob = lifecycleScope.launch {
            val info = ActivityManager.MemoryInfo()
            while (isActive) {
                memoryManager.getMemoryInfo(info)
                val runtime = Runtime.getRuntime()
                val spare = runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()
                if (info.lowMemory || info.availMem < maxOf(info.threshold, 64L * ResourceBudget.MIB) ||
                    spare < maxOf(16L * ResourceBudget.MIB, runtime.maxMemory() / 8)) limitMemory()
                delay(2000)
            }
        }
        if (ready) {
            stopped = false; lastClock = SystemClock.elapsedRealtime()
            if (menuShowing || protectingSave || rewinding || exiting) pausePlayer() else { setScreenAwake(true); startCheckpoints() }
        }
    }
    override fun onPause() {
        checkpointJob?.cancel(); memoryJob?.cancel(); setScreenAwake(false)
        if (rewinding) finishRewind(false, resume = false)
        rewindButton.clear()
        if (ready) pausePlayer()
        super.onPause()
    }
    override fun onStop() {
        clearHistory(); CoverArt.clearMemory()
        if (ready && !protectingSave && !exiting) saveAutomatically()
        super.onStop()
    }
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) { if (rewinding) finishRewind(false, false); clearHistory() }
        else if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) limitMemory()
    }
    override fun onLowMemory() { super.onLowMemory(); limitMemory() }
    private fun limitMemory() {
        if (rewinding) finishRewind(false)
        clearHistory(); CoverArt.clearMemory()
        if (!memoryLimited) { memoryLimited = true; toast(getString(R.string.rewind_memory)) }
    }
    private fun setScreenAwake(awake: Boolean) {
        if (awake) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        retroView?.keepScreenOn = awake
    }
    override fun onDestroy() {
        ready = false; generation++
        checkpointJob?.cancel(); rewindJob?.cancel(); memoryJob?.cancel(); restoreJob?.cancel()
        rewindImage?.setImageDrawable(null); history.clear()
        rewindOrigin = null; rewindOverlay = null; rewindImage = null; rewindLabel = null
        inputManager.unregisterInputDeviceListener(this)
        super.onDestroy()
        retroView = null
    }
    private fun shaderFor(id: String): ShaderConfig = when (id) {
        Prefs.FILTER_SMOOTH -> when (smoothLevel) {
            "light" -> ShaderConfig.CUT(useDynamicBlend = false, staticSharpness = .75f)
            "medium" -> ShaderConfig.CUT(useDynamicBlend = false, staticSharpness = .4f)
            "strong", "basic" -> ShaderConfig.Default
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
        setScreenAwake(false)
        if (!ready || stopped) return
        tickClock(); audioInfo = LibretroDroid.audioDiagnostics()
        leftShoulder.clear(); rightShoulder.clear(); l2Axis = false; r2Axis = false
        sentKeys.toList().forEach { sendButton(it, false) }
        retroView?.sendMotionEvent(GLRetroView.MOTION_SOURCE_DPAD, 0f, 0f)
        retroView?.onPause(); LibretroDroid.pause(); stopped = true
    }
    private fun resumePlayer() {
        if (!ready || !stopped || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        LibretroDroid.resume(); retroView?.onResume()
        stopped = false; lastClock = SystemClock.elapsedRealtime(); startCheckpoints()
        setScreenAwake(true)
    }
    private fun startCheckpoints() {
        checkpointJob?.cancel()
        checkpointJob = lifecycleScope.launch {
            while (isActive) {
                delay(100)
                if (!ready || stopped || menuShowing || protectingSave || rewinding || captureBusy) continue
                tickClock()
                val auto = playTime - lastAuto >= 30_000 && SaveWriter.isIdle()
                val snapshot = rewindEnabled && !memoryLimited && playTime - lastSnapshot >= rewindStep
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
                            if (ready && !stopped && !memoryLimited && version == generation && !rewinding) history.add(state, time, bitmap, bitmap?.byteCount ?: 0)
                            else bitmap?.recycle()
                        }
                    }
                } catch (_: OutOfMemoryError) { limitMemory() }
                catch (e: Exception) { android.util.Log.w("RetroTV", "Checkpoint", e) }
            }
        }
    }
    private fun writeAutomatic(state: ByteArray): Deferred<Boolean> {
        val ram = retroView!!.serializeSRAM(false); val target = saves
        return SaveWriter.submit(applicationContext, state.size.toLong() + ram.size) { target.write(0, state); target.writeRam(ram) }
    }
    private fun saveAutomatically(): Deferred<Boolean>? {
        return try { writeAutomatic(retroView!!.serializeState(false)) }
        catch (_: OutOfMemoryError) { limitMemory(); toast(getString(R.string.save_memory)); null }
        catch (e: Exception) { toast(getString(R.string.autosave_failed, e.userMessage(this))); null }
    }
    private fun captureBitmap(width: Int, done: (Bitmap?) -> Unit) {
        val view = retroView
        if (memoryLimited || view == null || !view.holder.surface.isValid || view.width <= 0 || view.height <= 0) { done(null); return }
        val bitmap = try { Bitmap.createBitmap(width, (width.toLong() * view.height / view.width).toInt().coerceIn(1, width), Bitmap.Config.RGB_565) }
        catch (_: OutOfMemoryError) { limitMemory(); done(null); return }
        try {
            PixelCopy.request(view, bitmap, { result ->
                if (result == PixelCopy.SUCCESS) done(bitmap) else { bitmap.recycle(); done(null) }
            }, Handler(Looper.getMainLooper()))
        } catch (_: Exception) { bitmap.recycle(); done(null) }
    }
    private fun capturePreview(slot: Int) {
        val target = saves
        captureBitmap(480) { bitmap -> if (bitmap != null) SaveWriter.submit(applicationContext, bitmap.byteCount.toLong(), { bitmap.recycle() }) {
            try { target.writePreview(slot, bitmap) } finally { bitmap.recycle() }
        } }
    }
    private fun clearHistory() { generation++; history.clear(); lastSnapshot = playTime }
    private fun chooseSlot(saving: Boolean) {
        if (protectingSave) return
        val slots = if (saving) (1..3).toList() else (0..3).toList()
        val labels = slots.map { slot ->
            val date = if (saves.has(slot)) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, resources.configuration.locales[0]).format(Date(saves.timestamp(slot))) else getString(R.string.empty_slot)
            (if (slot == 0) getString(R.string.autosave) else getString(R.string.slot, slot)) + "  •  $date"
        }
        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, labels) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(16, 12, 16, 12) }
                val image = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
                row.addView(image, LinearLayout.LayoutParams(128, 80))
                row.addView(TextView(context).apply { text = labels[position]; textSize = 17f; setPadding(16, 0, 0, 0) })
                lifecycleScope.launch { image.setImageBitmap(withContext(Dispatchers.IO) { CoverArt.decode(saves.preview(slots[position])) }) }
                return row
            }
        }
        AlertDialog.Builder(this).setTitle(if (saving) getString(R.string.save_state) else getString(R.string.load_state)).setAdapter(adapter) { _, index ->
            val slot = slots[index]
            if (!saving) { if (saves.has(slot)) restore(slot) else toast(getString(R.string.slot_empty)) }
            else if (saves.has(slot)) AlertDialog.Builder(this).setMessage(getString(R.string.replace_slot, slot))
                .setPositiveButton(getString(R.string.replace)) { _, _ -> saveManual(slot) }.setNegativeButton(getString(R.string.cancel), null).show()
            else saveManual(slot)
        }.setNegativeButton(getString(R.string.cancel), null).show()
    }
    private fun saveManual(slot: Int) {
        if (protectingSave) return
        try {
            val state = retroView!!.serializeState(false); val ram = retroView!!.serializeSRAM(false); val target = saves
            val job = SaveWriter.submit(applicationContext, state.size.toLong() + ram.size) { target.write(slot, state); target.writeRam(ram) }
            capturePreview(slot)
            lifecycleScope.launch { if (job.await()) toast(getString(R.string.saved_slot, slot)) }
        } catch (_: OutOfMemoryError) { limitMemory(); toast(getString(R.string.save_memory)) }
        catch (e: Exception) { toast(getString(R.string.save_failed, e.userMessage(this))) }
    }
    private fun restore(slot: Int, initial: Boolean = false) {
        if (restoreJob?.isActive == true) return
        pausePlayer(); protectingSave = true
        restoreJob = lifecycleScope.launch {
            var rollback: ByteArray? = null
            try {
                rollback = retroView!!.serializeState(false)
                val state = withContext(Dispatchers.IO) { SaveWriter.flush(); saves.read(slot) }
                check(retroView!!.unserializeState(state.bytes, false)) { getString(R.string.state_rejected) }
                clearHistory(); lastAuto = playTime; protectingSave = false
                if (state.fromBackup) toast(getString(R.string.backup_restored))
                if (!menuShowing) { resumePlayer(); if (initial) showHint() }
            } catch (e: Throwable) {
                if (e !is Exception && e !is OutOfMemoryError) throw e
                if (e is OutOfMemoryError) limitMemory()
                if (e is CancellationException) throw e
                val restored = rollback?.let { runCatching { retroView!!.unserializeState(it, false) }.getOrDefault(false) } ?: false
                AlertDialog.Builder(this@GameActivity).setTitle(getString(R.string.restore_failed))
                    .setMessage(getString(R.string.restore_body, e.userMessage(this@GameActivity))).setCancelable(false)
                    .setPositiveButton(if (initial || !restored) getString(R.string.restart) else getString(R.string.resume)) { _, _ ->
                        if (!restored) LibretroDroid.reset()
                        protectingSave = false; if (!menuShowing) resumePlayer()
                    }.setNegativeButton(getString(R.string.to_library)) { _, _ -> finish() }.show()
            }
        }
    }

    private fun beginRewind() {
        if (!rewindEnabled || memoryLimited || protectingSave || menuShowing || rewinding || exiting) return
        if (history.size == 0) { toast(getString(R.string.history_empty)); return }
        pausePlayer()
        try { rewindOrigin = retroView!!.serializeState(false) }
        catch (_: OutOfMemoryError) { limitMemory(); resumePlayer(); return }
        catch (_: Exception) { toast(getString(R.string.rewind_failed)); resumePlayer(); return }
        rewinding = true; generation++
        rewindIndex = history.size - 1
        rewindOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setBackgroundColor(0xEE101418.toInt()); setPadding(32, 24, 32, 24)
            rewindLabel = TextView(context).apply { textSize = 22f; gravity = Gravity.CENTER; setTextColor(Color.WHITE) }
            addView(rewindLabel, LinearLayout.LayoutParams(-1, -2))
            rewindImage = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
            addView(rewindImage, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(TextView(context).apply {
                text = getString(R.string.rewind_help)
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
        rewindLabel?.text = getString(R.string.rewind_position, (playTime - entry.timeMs) / 1000.0, rewindIndex + 1, history.size)
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
            } catch (_: Exception) {
                val restored = rewindOrigin?.let { runCatching { retroView!!.unserializeState(it, false) }.getOrDefault(false) } ?: false
                if (!restored) { protectingSave = true; finish() }
                toast(getString(R.string.rewind_restore_failed))
            }
        }
        rewindOrigin = null
        if (resume && !protectingSave) resumePlayer()
    }
    private fun showHint() {
        val hint = TextView(this).apply {
            text = getString(R.string.game_hint)
            textSize = 16f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); setBackgroundColor(0xBB101418.toInt()); setPadding(16, 12, 16, 12)
        }
        root.addView(hint, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        lifecycleScope.launch { delay(5000); root.removeView(hint) }
    }
    private fun showPauseMenu() {
        if (menuShowing || !ready || protectingSave || exiting) return
        if (rewinding) finishRewind(false, false)
        menuShowing = true; pausePlayer(); capturePreview(0)
        val content = layoutInflater.inflate(R.layout.dialog_pause, null)
        val dialog = AlertDialog.Builder(this, R.style.PauseDialog).setView(content).create()
        content.findViewById<Button>(R.id.btnResume).setOnClickListener { dialog.dismiss() }
        content.findViewById<Button>(R.id.btnSave).setOnClickListener { chooseSlot(true) }
        content.findViewById<Button>(R.id.btnLoad).setOnClickListener { chooseSlot(false) }
        val filter = content.findViewById<Button>(R.id.btnFilter)
        fun label() { filter.text = getString(R.string.filter_fmt, Prefs.filterTitle(this, currentFilter)) }
        label()
        filter.setOnClickListener { currentFilter = Prefs.nextFilter(currentFilter); metadata.setFilter(rom, currentFilter); retroView?.shader = shaderFor(currentFilter); label() }
        content.findViewById<Button>(R.id.btnAudioInfo).setOnClickListener {
            AlertDialog.Builder(this).setTitle(getString(R.string.audio_info)).setMessage(getString(R.string.diagnostics_body, audioInfo, rewindStep, history.size, history.bytes / 1024, rewindBudget / 1024))
                .setPositiveButton(getString(R.string.close), null).show()
        }
        fun exitGame(closeApp: Boolean = false) { dialog.setOnDismissListener(null); dialog.dismiss(); requestExit(closeApp) }
        content.findViewById<Button>(R.id.btnExit).setOnClickListener { exitGame() }
        content.findViewById<Button>(R.id.btnExitApp).setOnClickListener { exitGame(true) }
        dialog.setOnKeyListener { _, code, event ->
            if (event.scanCode == 318 || code == KeyEvent.KEYCODE_BUTTON_THUMBR || code == KeyEvent.KEYCODE_BUTTON_THUMBL) true
            else when (code) {
                KeyEvent.KEYCODE_BUTTON_B -> { if (event.action == KeyEvent.ACTION_UP) dialog.dismiss(); true }
                KeyEvent.KEYCODE_BACK -> { if (event.action == KeyEvent.ACTION_UP) exitGame(); true }
                else -> false
            }
        }
        dialog.setOnDismissListener { menuShowing = false; rewindButton.clear(); if (!protectingSave) resumePlayer() }
        dialog.show(); content.findViewById<Button>(R.id.btnResume).requestFocus()
    }
    private fun requestExit(closeApp: Boolean) {
        if (exiting) return
        exiting = true; checkpointJob?.cancel(); pausePlayer(); clearHistory(); CoverArt.clearMemory()
        val progress = AlertDialog.Builder(this).setMessage(getString(R.string.saving_exit)).setCancelable(false).show()
        lifecycleScope.launch {
            try {
                val result = withTimeoutOrNull(3000) {
                    SaveWriter.flush()
                    if (!protectingSave) saveAutomatically()?.await() else true
                }
                if (result == null) toast(getString(R.string.save_timeout))
            } finally {
                progress.dismiss()
                if (closeApp) AppExit.finish(this@GameActivity) else finish()
            }
        }
    }
    private fun fitGameSurface() {
        val view = retroView ?: return
        if (root.width <= 0 || root.height <= 0) return
        val width = minOf(root.width, (root.height * gameAspect).toInt())
        val height = minOf(root.height, (width / gameAspect).toInt())
        val params = view.layoutParams as? FrameLayout.LayoutParams ?: return
        if (params.width != width || params.height != height) {
            params.width = width; params.height = height; params.gravity = Gravity.CENTER
            view.layoutParams = params
        }
    }
    private fun sendButton(code: Int, down: Boolean) {
        val changed = if (down) sentKeys.add(code) else sentKeys.remove(code)
        if (changed) retroView?.sendKeyEvent(if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, code)
    }
    private fun sonyTouchpad(device: InputDevice?): Boolean = device != null &&
        (device.vendorId == 0x054c || device.name.contains("DualSense", true) || device.name.contains("Wireless Controller", true))

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (exiting) return true
        // Never send either stick click to the core, including L3+R3 combinations.
        if (event.scanCode == 318 || event.keyCode == KeyEvent.KEYCODE_BUTTON_THUMBR || event.keyCode == KeyEvent.KEYCODE_BUTTON_THUMBL) return true
        if (!ready || menuShowing || protectingSave) return super.dispatchKeyEvent(event)
        val down = event.action == KeyEvent.ACTION_DOWN
        if (event.isFromSource(InputDevice.SOURCE_GAMEPAD)) controllerId = event.deviceId
        // Sony's touchpad is a separate input device on many Android kernels (BTN_LEFT).
        if (sonyTouchpad(event.device) && (event.scanCode == 272 || event.keyCode == KeyEvent.KEYCODE_BUTTON_1)) {
            if (down && event.repeatCount == 0) showPauseMenu()
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) { rewindButton.update(1, down); return true }
        if (rewinding) {
            if (down && (event.keyCode == KeyEvent.KEYCODE_BUTTON_B || event.keyCode == KeyEvent.KEYCODE_BACK)) finishRewind(false)
            return true
        }
        when (event.keyCode) {
            KeyEvent.KEYCODE_BACK -> { if (down && event.repeatCount == 0) showPauseMenu(); return true }
            KeyEvent.KEYCODE_BUTTON_L1 -> { leftShoulder.update(1, down); return true }
            KeyEvent.KEYCODE_BUTTON_L2 -> { leftShoulder.update(2, down); return true }
            KeyEvent.KEYCODE_BUTTON_R1 -> { rightShoulder.update(1, down); return true }
            KeyEvent.KEYCODE_BUTTON_R2 -> { rightShoulder.update(2, down); return true }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT -> return true
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { sendButton(KeyEvent.KEYCODE_BUTTON_A, down); return true }
        }
        if (event.isFromSource(InputDevice.SOURCE_GAMEPAD) || event.isFromSource(InputDevice.SOURCE_JOYSTICK)) {
            // Whitelist gameplay buttons: PS, vendor buttons and right-stick events do nothing.
            if (event.keyCode in intArrayOf(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B,
                    KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT)) sendButton(event.keyCode, down)
            return true
        }
        return super.dispatchKeyEvent(event)
    }
    private fun touchpadClick(event: MotionEvent): Boolean {
        if (!ready || menuShowing || protectingSave || !sonyTouchpad(event.device) ||
            !(event.isFromSource(InputDevice.SOURCE_MOUSE) || event.isFromSource(InputDevice.SOURCE_TOUCHPAD))) return false
        if ((event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS && event.actionButton == MotionEvent.BUTTON_PRIMARY) ||
            (event.actionMasked == MotionEvent.ACTION_DOWN && event.buttonState and MotionEvent.BUTTON_PRIMARY != 0)) showPauseMenu()
        // Movement / swiping the panel never changes game input.
        return true
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean =
        if (touchpadClick(event)) true else super.dispatchTouchEvent(event)

    private var lastScrub = 0L
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (exiting) return true
        if (touchpadClick(event)) return true
        if (!ready || menuShowing || protectingSave || !event.isFromSource(InputDevice.SOURCE_JOYSTICK)) return super.dispatchGenericMotionEvent(event)
        controllerId = event.deviceId
        rewindButton.update(2, event.getAxisValue(MotionEvent.AXIS_HAT_X) < -.5f)
        if (rewinding) {
            val x = event.getAxisValue(MotionEvent.AXIS_X)
            if (abs(x) > .5f && SystemClock.elapsedRealtime() - lastScrub > 150) {
                rewindJob?.cancel(); moveRewind(sign(x).toInt()); lastScrub = SystemClock.elapsedRealtime()
            }
            return true
        }
        // Only real 0..1 trigger ranges; never mistake a -1..1 right stick for a trigger.
        fun trigger(vararg axes: Int): Float = axes.maxOf { axis ->
            val range = event.device?.getMotionRange(axis, event.source)
            if (range != null && range.min >= 0f && range.max > 0f) event.getAxisValue(axis) / range.max else 0f
        }
        val lt = trigger(MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE)
        val rt = trigger(MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS)
        l2Axis = lt > if (l2Axis) .35f else .6f
        r2Axis = rt > if (r2Axis) .35f else .6f
        leftShoulder.update(4, l2Axis); rightShoulder.update(4, r2Axis)
        fun stick(axis: Int): Float = event.getAxisValue(axis).let { if (abs(it) > .45f) sign(it) else 0f }
        retroView?.sendMotionEvent(GLRetroView.MOTION_SOURCE_DPAD, stick(MotionEvent.AXIS_X), stick(MotionEvent.AXIS_Y))
        return true
    }
    override fun onInputDeviceRemoved(deviceId: Int) {
        if (deviceId == controllerId) {
            if (rewinding) finishRewind(false, false)
            rewindButton.clear(); showPauseMenu(); toast(getString(R.string.controller_disconnected))
        }
    }
    override fun onInputDeviceAdded(deviceId: Int) = Unit
    override fun onInputDeviceChanged(deviceId: Int) = Unit
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
