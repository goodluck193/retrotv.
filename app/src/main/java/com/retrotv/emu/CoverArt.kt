package com.retrotv.emu

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.retrotv.emu.Prefs.downloadCovers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object CoverArt {
    private val requests = Semaphore(2)
    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) { override fun sizeOf(key: String, value: Bitmap) = value.byteCount }
    suspend fun load(context: Context, rom: Rom): Bitmap? = withContext(Dispatchers.IO) {
        cache.get(rom.id)?.let { return@withContext it }
        requests.withPermit {
            val folder = File(context.filesDir, "covers").apply { mkdirs() }
            val file = File(folder, rom.id + ".png"); val missing = File(folder, rom.id + ".missing")
            if (!file.exists() && context.downloadCovers && (!missing.exists() || System.currentTimeMillis() - missing.lastModified() > 86_400_000)) {
                val repo = when (rom.system) {
                    SystemType.NES -> "Nintendo_-_Nintendo_Entertainment_System"
                    SystemType.SNES -> "Nintendo_-_Super_Nintendo_Entertainment_System"
                    SystemType.MEGADRIVE -> "Sega_-_Mega_Drive_-_Genesis"
                }
                for (name in listOf(rom.file.nameWithoutExtension, rom.title + " (USA)", rom.title + " (World)").distinct()) {
                    val encoded = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
                    if (download("https://raw.githubusercontent.com/libretro-thumbnails/$repo/master/Named_Boxarts/$encoded.png", file)) break
                }
                runCatching { if (!file.exists()) missing.writeText("") else missing.delete() }
            }
            decode(file)?.also { cache.put(rom.id, it) }
        }
    }
    private fun decode(file: File): Bitmap? {
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options()
        while (bounds.outWidth / options.inSampleSize > 512 || bounds.outHeight / options.inSampleSize > 512) options.inSampleSize *= 2
        return BitmapFactory.decodeFile(file.path, options)
    }
    private fun download(url: String, file: File): Boolean {
        val connection = URL(url).openConnection() as HttpURLConnection
        val temp = File(file.path + ".part")
        return try {
            connection.connectTimeout = 5000; connection.readTimeout = 5000
            if (connection.responseCode != 200) return false
            val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
            connection.inputStream.use { input -> temp.outputStream().use { output ->
                val buffer = ByteArray(8192); var total = 0
                while (true) {
                    check(android.os.SystemClock.elapsedRealtime() < deadline)
                    val n = input.read(buffer); if (n < 0) break
                    total += n; check(total <= 4 * 1024 * 1024); output.write(buffer, 0, n)
                }
            } }
            val bitmap = decode(temp)
            if (bitmap == null) false else { bitmap.recycle(); temp.renameTo(file) }
        } catch (_: Exception) { false }
        finally { connection.disconnect(); temp.delete() }
    }
}
