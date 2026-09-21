package com.retrotv.emu

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {
    private lateinit var recycler: RecyclerView
    private lateinit var metadata: LibraryMetadata
    private lateinit var adapter: RomAdapter
    private var roms = emptyList<Rom>()
    private var launchingGame = false
    private var category = 0
    private var search = ""
    private var focusedPath: String? = null
    private var refreshJob: Job? = null
    private var coverPath: String? = null
    private val categories get() = arrayOf(getString(R.string.all_games), getString(R.string.recent), getString(R.string.favorites), "NES / Dendy", "Super Nintendo", "Sega Mega Drive")
    private val pickRom = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? -> if (uri != null) importRom(uri) }
    private val pickCover = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val path = coverPath; coverPath = null
        if (uri != null && path != null) {
            val file = java.io.File(path)
            val system = SystemType.fromFileName(file.name)
            if (file.isFile && system != null) lifecycleScope.launch {
                try {
                    CoverArt.importLocal(applicationContext, Rom(file, system), uri)
                    toast(getString(R.string.cover_saved)); refresh()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    toast(e.userMessage(this@MainActivity))
                } catch (e: OutOfMemoryError) { CoverArt.clearMemory(); toast(getString(R.string.low_memory)) }
            }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); setContentView(R.layout.activity_main)
        metadata = LibraryMetadata(this); category = savedInstanceState?.getInt("category") ?: 0; focusedPath = savedInstanceState?.getString("focus")
        coverPath = savedInstanceState?.getString("cover_path")
        recycler = findViewById(R.id.gameList)
        adapter = RomAdapter(metadata, lifecycleScope, { launchGame(it) }, { showActions(it) }, { focusedPath = it.file.path })
        recycler.layoutManager = GridLayoutManager(this, 4); recycler.adapter = adapter
        findViewById<Button>(R.id.btnSettings).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        findViewById<Button>(R.id.btnAddRom).setOnClickListener { pickRom.launch(arrayOf("*/*")) }
        findViewById<Button>(R.id.btnExitApp).setOnClickListener {
            lifecycleScope.launch { withTimeoutOrNull(3000) { SaveWriter.flush() }; AppExit.finish(this@MainActivity) }
        }
        findViewById<Button>(R.id.btnCategory).apply {
            text = categories[category]
            setOnClickListener { AlertDialog.Builder(this@MainActivity).setTitle(getString(R.string.library)).setSingleChoiceItems(categories, category) { dialog, which ->
                category = which; text = categories[category]; dialog.dismiss(); filter()
            }.show() }
        }
        findViewById<EditText>(R.id.searchGames).doAfterTextChanged { search = it.toString(); filter() }
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putInt("category", category); outState.putString("focus", focusedPath); outState.putString("cover_path", coverPath); super.onSaveInstanceState(outState) }
    override fun onResume() { super.onResume(); launchingGame = false; recycler.adapter = adapter; refresh() }
    override fun onStop() {
        refreshJob?.cancel()
        adapter.releaseCovers(); recycler.adapter = null; recycler.recycledViewPool.clear()
        CoverArt.clearMemory()
        super.onStop()
    }
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            adapter.releaseCovers()
        }
    }
    private fun refresh() {
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            try {
                roms = withContext(Dispatchers.IO) { SaveWriter.flush(); RomLibrary.scan(this@MainActivity) }
                filter()
                focusedPath?.let { path ->
                    val index = adapter.positionOf(path)
                    if (index >= 0) { recycler.scrollToPosition(index); recycler.post { recycler.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus() } }
                }
            } catch (e: Exception) { if (e is CancellationException) throw e; toast(getString(R.string.library_failed, e.userMessage(this@MainActivity))) }
        }
    }
    private fun filter() {
        var list = roms.filter { search.isBlank() || it.title.contains(search, true) }
        list = when (category) {
            1 -> list.filter { metadata.lastPlayed(it) > 0 }.sortedByDescending { metadata.lastPlayed(it) }
            2 -> list.filter { metadata.favorite(it) }
            3 -> list.filter { it.system == SystemType.NES }
            4 -> list.filter { it.system == SystemType.SNES }
            5 -> list.filter { it.system == SystemType.MEGADRIVE }
            else -> list
        }
        adapter.submit(list)
        findViewById<TextView>(R.id.emptyView).apply { visibility = if (list.isEmpty()) View.VISIBLE else View.GONE; text = if (roms.isEmpty()) getString(R.string.empty_hint) else getString(R.string.no_matches) }
        findViewById<TextView>(R.id.libraryCount).text = getString(R.string.game_count, list.size)
    }
    private fun importRom(uri: Uri) {
        val progress = AlertDialog.Builder(this).setMessage(R.string.importing).setCancelable(false).show()
        lifecycleScope.launch {
            try {
                when (val result = RomImporter.import(this@MainActivity, uri)) {
                    is RomImporter.Result.Success -> { focusedPath = result.rom.file.path; toast(getString(R.string.game_added, result.rom.title)); refresh() }
                    is RomImporter.Result.Error -> AlertDialog.Builder(this@MainActivity).setTitle(R.string.import_failed).setMessage(result.message).setPositiveButton(android.R.string.ok, null).show()
                }
            } finally { progress.dismiss() }
        }
    }
    private fun launchGame(rom: Rom, resume: Boolean = true) {
        if (launchingGame) return
        launchingGame = true
        focusedPath = rom.file.path
        startActivity(Intent(this, GameActivity::class.java).apply {
            putExtra(GameActivity.EXTRA_ROM_PATH, rom.file.path); putExtra(GameActivity.EXTRA_SYSTEM_ID, rom.system.id); putExtra(GameActivity.EXTRA_RESUME, resume)
        })
    }
    private fun showActions(rom: Rom) {
        val favorite = if (metadata.favorite(rom)) getString(R.string.unfavorite) else getString(R.string.favorite)
        AlertDialog.Builder(this).setTitle(rom.title).setItems(arrayOf(getString(R.string.resume), getString(R.string.restart), favorite, getString(R.string.choose_cover), getString(R.string.delete_game))) { _, index ->
            when (index) {
                0 -> launchGame(rom)
                1 -> AlertDialog.Builder(this).setMessage(getString(R.string.restart_body)).setPositiveButton(getString(R.string.start)) { _, _ -> launchGame(rom, false) }.setNegativeButton(getString(R.string.cancel), null).show()
                2 -> { metadata.toggleFavorite(rom); filter() }
                3 -> { coverPath = rom.file.path; pickCover.launch(arrayOf("image/*")) }
                4 -> confirmDelete(rom)
            }
        }.show()
    }
    private fun confirmDelete(rom: Rom) {
        var keep = true
        AlertDialog.Builder(this).setTitle(getString(R.string.delete_game_title, rom.title))
            .setMultiChoiceItems(arrayOf(getString(R.string.keep_saves)), booleanArrayOf(true)) { _, _, checked -> keep = checked }
            .setPositiveButton(getString(R.string.delete)) { _, _ -> lifecycleScope.launch {
                try { withContext(Dispatchers.IO) { RomLibrary.delete(this@MainActivity, rom, keep) }; focusedPath = null; refresh() }
                catch (e: Exception) { if (e is CancellationException) throw e; toast(getString(R.string.delete_failed, e.userMessage(this@MainActivity))) }
            } }.setNegativeButton(getString(R.string.cancel), null).show()
    }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}

private class RomAdapter(private val metadata: LibraryMetadata, private val scope: CoroutineScope,
    private val onClick: (Rom) -> Unit, private val onLongClick: (Rom) -> Unit, private val onFocus: (Rom) -> Unit
) : RecyclerView.Adapter<RomAdapter.VH>() {
    private val holders = mutableSetOf<VH>()
    fun releaseCovers() { holders.forEach { it.job?.cancel(); it.cover.setImageDrawable(null); it.placeholder.visibility = View.VISIBLE } }
    private var items = emptyList<Rom>()
    fun submit(list: List<Rom>) { items = list; notifyDataSetChanged() }
    fun positionOf(path: String) = items.indexOfFirst { it.file.path == path }
    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.romTitle); val system: TextView = v.findViewById(R.id.romSystem)
        val cover: ImageView = v.findViewById(R.id.romCover); val placeholder: TextView = v.findViewById(R.id.coverPlaceholder)
        var job: Job? = null
    }
    override fun onCreateViewHolder(parent: ViewGroup, type: Int) = VH(LayoutInflater.from(parent.context).inflate(R.layout.item_game, parent, false))
    override fun getItemCount() = items.size
    override fun onBindViewHolder(holder: VH, position: Int) {
        holders.add(holder)
        val rom = items[position]; holder.title.text = (if (metadata.favorite(rom)) "★ " else "") + rom.title; holder.system.text = rom.system.title
        holder.cover.setImageDrawable(null); holder.placeholder.text = when (rom.system) { SystemType.NES -> "NES"; SystemType.SNES -> "SNES"; SystemType.MEGADRIVE -> "SEGA" }; holder.placeholder.visibility = View.VISIBLE
        holder.job?.cancel(); holder.job = scope.launch {
            val bitmap = CoverArt.load(holder.itemView.context.applicationContext, rom); holder.cover.setImageBitmap(bitmap); holder.placeholder.visibility = if (bitmap == null) View.VISIBLE else View.GONE
        }
        holder.itemView.setOnClickListener { onClick(rom) }; holder.itemView.setOnLongClickListener { onLongClick(rom); true }
        holder.itemView.setOnFocusChangeListener { view, focused ->
            if (focused) onFocus(rom); view.animate().scaleX(if (focused) 1.04f else 1f).scaleY(if (focused) 1.04f else 1f).setDuration(120).start()
        }
        holder.itemView.scaleX = if (holder.itemView.hasFocus()) 1.04f else 1f; holder.itemView.scaleY = holder.itemView.scaleX
    }
    override fun onViewRecycled(holder: VH) { holder.job?.cancel(); holder.cover.setImageDrawable(null); holders.remove(holder) }
}
