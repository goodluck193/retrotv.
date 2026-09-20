package com.retrotv.emu

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.retrotv.emu.Prefs.downloadCovers
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object CoverArt {
    private val requests = Semaphore(2)
    private val maintenance = Mutex()
    private var diskPrepared = false
    // Fixed stripes avoid keeping a Mutex forever for every ROM ever seen.
    private val locks = Array(16) { Mutex() }
    private val guard = Any()
    private var generation = 0
    private val cache = object : LruCache<String, Bitmap>(1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    fun configure(context: Context) {
        val lowRam = (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice
        cache.resize(ResourceBudget.covers(Runtime.getRuntime().maxMemory(), lowRam))
    }
    fun clearMemory() = synchronized(guard) { generation++; cache.evictAll() }
    suspend fun clearDisk(context: Context) = withContext(Dispatchers.IO) {
        maintenance.withLock { requests.withPermit { requests.withPermit {
            clearMemory(); File(context.filesDir, "covers").deleteRecursively()
        } } }
    }
    suspend fun load(context: Context, rom: Rom): Bitmap? = withContext(Dispatchers.IO) {
        val epoch = synchronized(guard) { generation }
        cache.get(rom.id)?.let { return@withContext it }
        try {
            maintenance.withLock {
                if (!diskPrepared) {
                    val folder = File(context.filesDir, "covers")
                    folder.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }
                    DiskCache.trim(folder, 56L * ResourceBudget.MIB, 510)
                    diskPrepared = true
                }
            }
            locks[(rom.id.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
                cache.get(rom.id) ?: requests.withPermit {
                    ensureActive()
                    val runtime = Runtime.getRuntime()
                    if (runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory() < 8L * ResourceBudget.MIB) return@withPermit null
                    val folder = File(context.filesDir, "covers").apply { mkdirs() }
                    // Reserve 8 MiB for the two in-flight downloads, 4 MiB each.
                    DiskCache.trim(folder, 56L * ResourceBudget.MIB, 510)
                    val file = File(folder, rom.id + ".png")
                    val missing = File(folder, rom.id + ".missing")
                    if (!file.exists() && context.downloadCovers &&
                        ResourceBudget.canWrite(folder.usableSpace, 8L * ResourceBudget.MIB, ResourceBudget.COVER_RESERVE) &&
                        (!missing.exists() || System.currentTimeMillis() - missing.lastModified() > 86_400_000)) {
                        val repo = when (rom.system) {
                            SystemType.NES -> "Nintendo_-_Nintendo_Entertainment_System"
                            SystemType.SNES -> "Nintendo_-_Super_Nintendo_Entertainment_System"
                            SystemType.MEGADRIVE -> "Sega_-_Mega_Drive_-_Genesis"
                        }
                        for (name in listOf(rom.file.nameWithoutExtension, rom.title + " (USA)", rom.title + " (World)").distinct()) {
                            ensureActive()
                            val encoded = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
                            if (download("https://raw.githubusercontent.com/libretro-thumbnails/$repo/master/Named_Boxarts/$encoded.png", file)) break
                        }
                        runCatching { if (!file.exists()) missing.writeText("") else missing.delete() }
                    }
                    ensureActive()
                    decode(file)?.also { bitmap ->
                        file.setLastModified(System.currentTimeMillis())
                        synchronized(guard) { if (generation == epoch) cache.put(rom.id, bitmap) }
                    }
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: OutOfMemoryError) { clearMemory(); null }
        catch (_: Exception) { null }
    }
    fun decode(file: File): Bitmap? {
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply { inSampleSize = 1; inPreferredConfig = Bitmap.Config.RGB_565 }
        while (bounds.outWidth / options.inSampleSize > 512 || bounds.outHeight / options.inSampleSize > 512) options.inSampleSize *= 2
        return try { BitmapFactory.decodeFile(file.path, options) } catch (_: OutOfMemoryError) { clearMemory(); null }
    }
    private suspend fun download(url: String, file: File): Boolean {
        val connection = URL(url).openConnection() as HttpURLConnection
        val temp = File(file.path + ".part")
        return try {
            connection.connectTimeout = 5000; connection.readTimeout = 5000
            if (connection.responseCode != 200) return false
            val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
            connection.inputStream.use { input -> temp.outputStream().use { output ->
                val buffer = ByteArray(8192); var total = 0
                while (true) {
                    currentCoroutineContext().ensureActive()
                    check(android.os.SystemClock.elapsedRealtime() < deadline)
                    val n = input.read(buffer); if (n < 0) break
                    total += n; check(total <= 4 * ResourceBudget.MIB); output.write(buffer, 0, n)
                }
            } }
            val bitmap = decode(temp)
            if (bitmap == null) false else { bitmap.recycle(); temp.renameTo(file) }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { false }
        finally { connection.disconnect(); temp.delete() }
    }
}
