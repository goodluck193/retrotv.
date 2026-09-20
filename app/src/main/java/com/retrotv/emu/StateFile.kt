package com.retrotv.emu

import java.io.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption.*
import java.nio.file.AtomicMoveNotSupportedException
import java.util.zip.CRC32

/** One checked envelope, atomic publication, and one last known good generation. */
object StateFile {
    private const val MAGIC = 0x52545632
    private const val LIMIT = 32 * 1024 * 1024
    data class Loaded(val bytes: ByteArray, val tag: String, val fromBackup: Boolean)

    @Synchronized fun write(file: File, bytes: ByteArray, tag: String) {
        require(bytes.isNotEmpty() && bytes.size <= LIMIT) { "Недопустимый размер сохранения" }
        file.parentFile?.mkdirs()
        require(ResourceBudget.canWrite(file.parentFile.usableSpace, bytes.size.toLong() + file.length() + 8192)) {
            "На ТВ мало свободного места. Сохранение не заменено; освободите место."
        }
        val temp = File(file.path + ".tmp")
        try {
            FileOutputStream(temp).use { raw ->
                val output = DataOutputStream(raw)
                output.writeInt(MAGIC)
                output.writeUTF(tag)
                output.writeInt(bytes.size)
                output.writeLong(CRC32().apply { update(bytes) }.value)
                output.write(bytes)
                output.flush()
                raw.fd.sync()
            }
            // Never replace a valid backup with an already corrupted primary.
            if (file.exists() && runCatching { decode(file, false, false) }.isSuccess) {
                val backupTemp = File(file.path + ".bak.tmp")
                try {
                    Files.copy(file.toPath(), backupTemp.toPath(), REPLACE_EXISTING)
                    move(backupTemp, File(file.path + ".bak"))
                } finally { backupTemp.delete() }
            }
            move(temp, file)
        } finally { temp.delete() }
    }

    @Synchronized fun read(file: File, expectedTag: String? = null): Loaded {
        var last: Throwable? = null
        for ((candidate, backup) in listOf(file to false, File(file.path + ".bak") to true)) {
            if (!candidate.exists()) continue
            try {
                val loaded = decode(candidate, backup)
                require(expectedTag == null || loaded.tag == expectedTag) {
                    "Сохранение создано другой версией ядра. Внутриигровой прогресс сохранён отдельно."
                }
                return loaded
            } catch (e: Exception) { last = e }
        }
        throw IOException(last?.message ?: "Сохранения пока нет", last)
    }

    fun exists(file: File) = file.exists() || File(file.path + ".bak").exists()
    private fun decode(file: File, backup: Boolean, loadBytes: Boolean = true): Loaded = DataInputStream(file.inputStream().buffered()).use {
        require(file.length() <= LIMIT + 4096 && it.readInt() == MAGIC) { "Повреждённый файл сохранения" }
        val tag = it.readUTF()
        val size = it.readInt()
        require(size in 1..LIMIT && size <= file.length()) { "Неверный размер сохранения" }
        val crc = it.readLong()
        // Checking the previous generation must not allocate a second full state.
        val bytes = ByteArray(if (loadBytes) size else minOf(size, 8192))
        val checksum = CRC32(); var read = 0
        while (read < size) {
            val offset = if (loadBytes) read else 0
            val n = minOf(size - read, bytes.size)
            it.readFully(bytes, offset, n); checksum.update(bytes, offset, n); read += n
        }
        require(it.read() == -1 && checksum.value == crc) { "Сохранение повреждено" }
        Loaded(bytes, tag, backup)
    }
    private fun move(from: File, to: File) {
        try { Files.move(from.toPath(), to.toPath(), ATOMIC_MOVE, REPLACE_EXISTING) }
        catch (_: AtomicMoveNotSupportedException) { Files.move(from.toPath(), to.toPath(), REPLACE_EXISTING) }
    }
}
