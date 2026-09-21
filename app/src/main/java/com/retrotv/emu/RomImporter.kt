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


/** Streams a single ROM into private storage. Enforces byte, entry, time and free-space limits; always removes partial files. */
object RomImporter {

    private const val COPY_TIMEOUT_MS = 60_000L
    private const val MIN_FREE_SPACE_BYTES = 200L * 1024 * 1024
    private const val BUFFER_SIZE = 64 * 1024
    private const val MAX_ZIP_ENTRIES = 200

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
                    ?: return@withTimeout Result.Error(context.getString(R.string.import_name))
                val pickedName = sanitize(rawName)
                val pickedExt = pickedName.substringAfterLast('.', "").lowercase()


                if (declaredSize > SystemType.HARD_LIMIT_BYTES) {
                    return@withTimeout Result.Error(context.getString(R.string.import_size, 32))
                }


                val usable = context.filesDir.usableSpace
                if (usable < MIN_FREE_SPACE_BYTES + SystemType.HARD_LIMIT_BYTES) {
                    return@withTimeout Result.Error(context.getString(R.string.import_space))
                }


                if (pickedExt == "zip") {
                    importFromZip(context, resolver.openInputStream(uri)
                        ?: return@withTimeout Result.Error(context.getString(R.string.import_open)),
                        onTemp = { tempFile = it })
                } else {
                    val system = SystemType.fromFileName(pickedName)
                        ?: return@withTimeout Result.Error(context.getString(R.string.import_format))
                    if (declaredSize > system.maxRomBytes) {
                        return@withTimeout Result.Error(context.getString(R.string.import_size, system.maxRomBytes / 1024 / 1024))
                    }
                    val input = resolver.openInputStream(uri)
                        ?: return@withTimeout Result.Error(context.getString(R.string.import_open))
                    input.use {
                        copyAndFinish(context, it, pickedName, system, declaredSize,
                            onTemp = { t -> tempFile = t })
                    }
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Result.Error(context.getString(R.string.import_timeout))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.Error(context.getString(R.string.import_error))
        } finally {
            tempFile?.delete()
        }
    }


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
                    return@withContext Result.Error(context.getString(R.string.import_zip_entries))
                }


                val entryName = sanitize(entry.name.substringAfterLast('/').substringAfterLast('\\'))
                val system = SystemType.fromFileName(entryName)
                if (entry.isDirectory || system == null) {
                    while (true) {
                        ensureActive()
                        val n = zip.read(skipBuffer)
                        if (n < 0) break
                        skippedBytes += n
                        if (skippedBytes > SystemType.HARD_LIMIT_BYTES) return@withContext Result.Error(context.getString(R.string.import_zip_data))
                    }
                    zip.closeEntry(); continue
                }


                val unpacked = entry.size
                if (unpacked > system.maxRomBytes) {
                    return@withContext Result.Error(context.getString(R.string.import_size, system.maxRomBytes / 1024 / 1024))
                }


                return@withContext copyAndFinish(
                    context, zip, entryName, system,
                    declaredSize = if (unpacked > 0) unpacked else -1,
                    onTemp = onTemp
                )
            }
        }
        Result.Error(context.getString(R.string.import_zip_empty))
    }


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
                    return@withContext Result.Error(context.getString(R.string.import_size, limit / 1024 / 1024))
                }
                output.write(buf, 0, read)
            }
            output.flush()
        }

        if (total == 0L) return@withContext Result.Error(context.getString(R.string.import_empty))
        if (declaredSize > 0 && total != declaredSize) {
            return@withContext Result.Error(context.getString(R.string.import_incomplete))
        }


        val headerLen = minOf(total, 0x110L).toInt()
        val header = ByteArray(headerLen)
        temp.inputStream().use { it.read(header) }
        if (!system.looksLikeValidRom(header, ext)) {
            return@withContext Result.Error(context.getString(R.string.import_signature))
        }


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


    private fun sanitize(name: String): String =
        name.replace('/', '_').replace('\\', '_').trim()
}
