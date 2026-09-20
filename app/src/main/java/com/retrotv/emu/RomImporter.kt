package com.retrotv.emu

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Импорт рома из общего хранилища в приватную папку приложения.
 * Поддерживаются как «голые» ромы, так и ромы внутри .zip-архива.
 *
 * Защиты от «поломки телевизора»:
 *  1. Белый список расширений — импортируются только файлы известных консолей.
 *  2. Предел размера на консоль + общий жёсткий предел 32 МБ.
 *  3. Проверка свободного места ДО копирования (нужен запас минимум 200 МБ).
 *  4. Копирование с подсчётом байт: если данные льются бесконечно
 *     (в т.ч. «zip-бомба» — архив, раздувающийся при распаковке) —
 *     операция обрывается ровно на лимите.
 *  5. Лимит 60 секунд проверяется между чтениями; блокирующий SAF-провайдер может задержать отмену.
 *  6. Запись сначала во временный .part-файл, затем атомарное переименование.
 *     При любой ошибке временный файл удаляется — мусора не остаётся.
 *  7. Проверка магических байт (NES / SEGA), чтобы не запускать не-ромы.
 */
object RomImporter {

    private const val COPY_TIMEOUT_MS = 60_000L
    private const val MIN_FREE_SPACE_BYTES = 200L * 1024 * 1024
    private const val BUFFER_SIZE = 64 * 1024
    private const val MAX_ZIP_ENTRIES = 200 // защита от патологических архивов

    sealed class Result {
        data class Success(val rom: Rom) : Result()
        data class Error(val message: String) : Result()
    }

    fun romsDir(context: Context, system: SystemType): File =
        File(File(context.filesDir, "roms"), system.id).apply { mkdirs() }

    suspend fun import(context: Context, uri: Uri): Result = withContext(Dispatchers.IO) {
        var tempFile: File? = null
        try {
            withTimeout(COPY_TIMEOUT_MS) {
                val resolver = context.contentResolver

                // --- Имя и заявленный размер выбранного файла ---
                var displayName: String? = null
                var declaredSize: Long = -1
                resolver.query(uri, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIdx >= 0) displayName = c.getString(nameIdx)
                        if (sizeIdx >= 0 && !c.isNull(sizeIdx)) declaredSize = c.getLong(sizeIdx)
                    }
                }
                val rawName = displayName
                    ?: return@withTimeout Result.Error("Не удалось прочитать имя файла")
                val pickedName = sanitize(rawName)
                val pickedExt = pickedName.substringAfterLast('.', "").lowercase()

                // Общий потолок на любой выбранный файл (в т.ч. на сам архив)
                if (declaredSize > SystemType.HARD_LIMIT_BYTES) {
                    return@withTimeout Result.Error(
                        "Файл слишком большой (${declaredSize / 1024 / 1024} МБ, лимит 32 МБ) — " +
                            "это не похоже на ром. Импорт не начат."
                    )
                }

                // --- Проверка свободного места ---
                val usable = context.filesDir.usableSpace
                if (usable < MIN_FREE_SPACE_BYTES + SystemType.HARD_LIMIT_BYTES) {
                    return@withTimeout Result.Error(
                        "Мало свободного места на ТВ (${usable / 1024 / 1024} МБ). " +
                            "Импорт остановлен, чтобы не забить память. Удалите что-нибудь и повторите."
                    )
                }

                // --- Определяем: обычный ром или zip ---
                if (pickedExt == "zip") {
                    importFromZip(context, resolver.openInputStream(uri)
                        ?: return@withTimeout Result.Error("Не удалось открыть архив"),
                        onTemp = { tempFile = it })
                } else {
                    val system = SystemType.fromFileName(pickedName)
                        ?: return@withTimeout Result.Error(
                            "Неизвестный формат «.$pickedExt». Поддерживаются: " +
                                ".nes, .sfc, .smc, .md, .gen, .bin, .smd и .zip с ромом внутри"
                        )
                    if (declaredSize > system.maxRomBytes) {
                        return@withTimeout Result.Error(
                            "Файл слишком большой для ${system.title} " +
                                "(лимит ${system.maxRomBytes / 1024 / 1024} МБ)."
                        )
                    }
                    val input = resolver.openInputStream(uri)
                        ?: return@withTimeout Result.Error("Не удалось открыть файл для чтения")
                    input.use {
                        copyAndFinish(context, it, pickedName, system, declaredSize,
                            onTemp = { t -> tempFile = t })
                    }
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Result.Error("Операция заняла больше 60 секунд и была остановлена. Память ТВ не пострадала.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.Error("Ошибка импорта: ${e.message ?: e.javaClass.simpleName}. Ничего не записано.")
        } finally {
            tempFile?.delete() // гарантированная уборка при любой ошибке
        }
    }

    /**
     * Достаёт из .zip первый файл с расширением поддерживаемого рома.
     * Распаковка идёт потоково с жёстким подсчётом байт: «zip-бомба»
     * (маленький архив, раздувающийся в гигабайты) будет остановлена на лимите.
     */
    private suspend fun importFromZip(
        context: Context,
        raw: InputStream,
        onTemp: (File) -> Unit
    ): Result = withContext(Dispatchers.IO) {
        ZipInputStream(raw.buffered()).use { zip ->
            var entries = 0
            var skippedBytes = 0L
            val skipBuffer = ByteArray(BUFFER_SIZE)
            while (true) {
                ensureActive()
                val entry = zip.nextEntry ?: break
                if (++entries > MAX_ZIP_ENTRIES) {
                    return@withContext Result.Error(
                        "В архиве слишком много файлов (> $MAX_ZIP_ENTRIES). Импорт остановлен."
                    )
                }

                // Берём только имя файла, отбрасывая пути внутри архива
                val entryName = sanitize(entry.name.substringAfterLast('/').substringAfterLast('\\'))
                val system = SystemType.fromFileName(entryName)
                if (entry.isDirectory || system == null) {
                    while (true) {
                        ensureActive()
                        val n = zip.read(skipBuffer)
                        if (n < 0) break
                        skippedBytes += n
                        if (skippedBytes > SystemType.HARD_LIMIT_BYTES) return@withContext Result.Error("Архив содержит больше 32 МБ посторонних данных.")
                    }
                    zip.closeEntry(); continue
                }

                // Если архив честно сообщает размер после распаковки — проверяем заранее
                val unpacked = entry.size
                if (unpacked > system.maxRomBytes) {
                    return@withContext Result.Error(
                        "Ром «$entryName» внутри архива слишком большой " +
                            "(${unpacked / 1024 / 1024} МБ, лимит ${system.maxRomBytes / 1024 / 1024} МБ)."
                    )
                }

                // Нашли ром — распаковываем его (лимит контролируется по байтам)
                return@withContext copyAndFinish(
                    context, zip, entryName, system,
                    declaredSize = if (unpacked > 0) unpacked else -1,
                    onTemp = onTemp
                )
            }
        }
        Result.Error(
            "В архиве не нашлось рома. Внутри должен быть файл " +
                ".nes, .sfc, .smc, .md, .gen, .bin или .smd."
        )
    }

    /**
     * Общий финал: потоковое копирование с лимитом, проверка сигнатуры,
     * атомарное перемещение в библиотеку.
     */
    private suspend fun copyAndFinish(
        context: Context,
        input: InputStream,
        fileName: String,
        system: SystemType,
        declaredSize: Long,
        onTemp: (File) -> Unit
    ): Result = withContext(Dispatchers.IO) {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val limit = minOf(system.maxRomBytes, SystemType.HARD_LIMIT_BYTES)
        val temp = File.createTempFile("import_", ".part", romsDir(context, system))
        onTemp(temp)

        var total = 0L
        temp.outputStream().use { output ->
            val buf = ByteArray(BUFFER_SIZE)
            while (true) {
                ensureActive()
                val read = input.read(buf)
                if (read < 0) break
                total += read
                if (total > limit) {
                    return@withContext Result.Error(
                        "Копирование остановлено: данные превысили лимит " +
                            "${limit / 1024 / 1024} МБ (возможно, битый файл или zip-бомба). " +
                            "Память ТВ не пострадала."
                    )
                }
                output.write(buf, 0, read)
            }
            output.flush()
        }

        if (total == 0L) return@withContext Result.Error("Файл пустой")
        if (declaredSize > 0 && total != declaredSize) {
            return@withContext Result.Error(
                "Файл скопировался не полностью (получено $total из $declaredSize байт). Импорт отменён."
            )
        }

        // --- Проверка сигнатуры ---
        val headerLen = minOf(total, 0x110L).toInt()
        val header = ByteArray(headerLen)
        temp.inputStream().use { it.read(header) }
        if (!system.looksLikeValidRom(header, ext)) {
            return@withContext Result.Error(
                "Файл «$fileName» не похож на ром ${system.title} (не совпала сигнатура). Импорт отменён."
            )
        }

        // --- Атомарное перемещение в библиотеку ---
        val destDir = romsDir(context, system)
        var dest = File(destDir, fileName)
        var i = 1
        while (dest.exists()) {
            val base = fileName.substringBeforeLast('.')
            dest = File(destDir, "${base}_${i++}.$ext")
        }
        java.nio.file.Files.move(temp.toPath(), dest.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        Result.Success(Rom(dest, system))
    }

    /** Защита от path traversal: в имени не должно быть разделителей путей. */
    private fun sanitize(name: String): String =
        name.replace('/', '_').replace('\\', '_').trim()
}
