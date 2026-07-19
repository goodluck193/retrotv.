package com.retrotv.emu

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var emptyView: TextView
    private val adapter = RomAdapter(
        onClick = { launchGame(it) },
        onLongClick = { confirmDelete(it) }
    )

    private val pickRom = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) importRom(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recycler = findViewById(R.id.gameList)
        emptyView = findViewById(R.id.emptyView)
        recycler.layoutManager = GridLayoutManager(this, 4)
        recycler.adapter = adapter

        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<ImageButton>(R.id.btnAddRom).setOnClickListener {
            // Системный файловый диалог: можно выбрать ром из любой общей папки
            // (например, из «Загрузки»/Download). Приложение само скопирует его
            // в свою защищённую папку.
            pickRom.launch(arrayOf("*/*"))
        }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val roms = RomLibrary.scan(this)
        adapter.submit(roms)
        emptyView.visibility = if (roms.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun importRom(uri: Uri) {
        val progress = AlertDialog.Builder(this)
            .setMessage(getString(R.string.importing))
            .setCancelable(false)
            .create()
        progress.show()

        lifecycleScope.launch {
            val result = RomImporter.import(this@MainActivity, uri)
            progress.dismiss()
            when (result) {
                is RomImporter.Result.Success -> {
                    Toast.makeText(
                        this@MainActivity,
                        "Добавлено: ${result.rom.title} (${result.rom.system.title})",
                        Toast.LENGTH_LONG
                    ).show()
                    refresh()
                }
                is RomImporter.Result.Error -> AlertDialog.Builder(this@MainActivity)
                    .setTitle(R.string.import_failed)
                    .setMessage(result.message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    private fun launchGame(rom: Rom) {
        val core = CoreProvider.corePath(this, rom.system)
        if (core == null) {
            AlertDialog.Builder(this)
                .setTitle("Ядро не найдено")
                .setMessage(
                    "В сборку не добавлено ядро ${rom.system.coreLibName} для ${rom.system.title}.\n" +
                        "См. README проекта: файл кладётся в app/src/main/jniLibs перед сборкой."
                )
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        startActivity(Intent(this, GameActivity::class.java).apply {
            putExtra(GameActivity.EXTRA_ROM_PATH, rom.file.absolutePath)
            putExtra(GameActivity.EXTRA_SYSTEM_ID, rom.system.id)
        })
    }

    private fun confirmDelete(rom: Rom) {
        AlertDialog.Builder(this)
            .setTitle(rom.title)
            .setMessage("Удалить игру и её сохранение с телевизора? Это освободит память.")
            .setPositiveButton("Удалить") { _, _ ->
                RomLibrary.delete(this, rom)
                refresh()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }
}

private class RomAdapter(
    val onClick: (Rom) -> Unit,
    val onLongClick: (Rom) -> Unit
) : RecyclerView.Adapter<RomAdapter.VH>() {

    private var items: List<Rom> = emptyList()

    fun submit(list: List<Rom>) {
        items = list
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.romTitle)
        val system: TextView = v.findViewById(R.id.romSystem)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_game, parent, false)
        // Анимация фокуса для навигации пультом/геймпадом
        v.setOnFocusChangeListener { view, focused ->
            view.animate().scaleX(if (focused) 1.08f else 1f)
                .scaleY(if (focused) 1.08f else 1f)
                .setDuration(120).start()
        }
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val rom = items[position]
        holder.title.text = rom.title
        holder.system.text = rom.system.title
        holder.itemView.setOnClickListener { onClick(rom) }
        holder.itemView.setOnLongClickListener { onLongClick(rom); true }
    }
}
