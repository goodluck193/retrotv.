package com.retrotv.emu

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.security.MessageDigest

class SaveStore(private val context: Context, val rom: Rom) {
    val directory = File(context.filesDir, "saves/${rom.id}").apply { mkdirs() }
    private val coreTag: String by lazy {
        val core = CoreProvider.corePath(context, rom.system) ?: error("Ядро не найдено")
        rom.system.id + ":" + sha256(core)
    }
    fun state(slot: Int) = File(directory, if (slot == 0) "auto.state" else "slot-$slot.state")
    fun preview(slot: Int) = File(directory, if (slot == 0) "auto.jpg" else "slot-$slot.jpg")
    fun has(slot: Int) = StateFile.exists(state(slot))
    fun timestamp(slot: Int) = maxOf(state(slot).lastModified(), File(state(slot).path + ".bak").lastModified())
    fun write(slot: Int, bytes: ByteArray) = StateFile.write(state(slot), bytes, coreTag)
    fun read(slot: Int) = StateFile.read(state(slot), coreTag)
    fun writeRam(bytes: ByteArray) {
        if (bytes.isNotEmpty()) StateFile.write(File(directory, "cartridge.sram"), bytes, rom.system.id)
    }
    fun readRam(): ByteArray? {
        val file = File(directory, "cartridge.sram")
        return if (StateFile.exists(file)) StateFile.read(file, rom.system.id).bytes else null
    }
    fun writePreview(slot: Int, bitmap: Bitmap) {
        if (!ResourceBudget.canWrite(directory.usableSpace, 1024L * 1024, ResourceBudget.COVER_RESERVE)) return
        val file = preview(slot)
        val temp = File(file.path + ".tmp")
        try {
            temp.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it)) }
            java.nio.file.Files.move(temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally { temp.delete() }
    }
    fun migrateLegacy() {
        val legacy = RomLibrary.stateFile(context, rom)
        if (!has(1) && legacy.exists() && legacy.length() in 1..(32L * 1024 * 1024)) {
            // Keep the original; the core still validates state compatibility on load.
            write(1, legacy.readBytes())
        }
    }
    companion object {
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { stream ->
                val buf = ByteArray(64 * 1024)
                while (true) { val n = stream.read(buf); if (n < 0) break; digest.update(buf, 0, n) }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
