package com.retrotv.emu

import android.content.Context
import java.io.File

data class Rom(val file: File, val system: SystemType) {
    val title: String get() = file.name.substringBeforeLast('.').replace(Regex("\\s*[\\(\\[].*?[\\)\\]]"), "").trim()
    val id: String by lazy {
        val cached = File(file.path + ".id")
        val fingerprint = "${file.length()}:${file.lastModified()}:"
        val stored = runCatching { cached.readText() }.getOrDefault("")
        val hash = if (stored.startsWith(fingerprint) && stored.removePrefix(fingerprint).matches(Regex("[0-9a-f]{64}"))) {
            stored.removePrefix(fingerprint)
        } else SaveStore.sha256(file).also { runCatching { cached.writeText(fingerprint + it) } }
        "${system.id}_$hash"
    }
}

object RomLibrary {

    fun scan(context: Context): List<Rom> {
        val result = mutableListOf<Rom>()
        for (system in SystemType.entries) {
            val dir = RomImporter.romsDir(context, system)
            dir.listFiles()?.filter { it.isFile }?.forEach { f ->
                if (SystemType.fromFileName(f.name) == system) result += Rom(f, system).also { it.id }
            }
        }
        return result.sortedBy { it.title.lowercase() }
    }

    fun statesDir(context: Context): File =
        File(context.filesDir, "states").apply { mkdirs() }

    fun stateFile(context: Context, rom: Rom): File =
        File(statesDir(context), "${rom.system.id}_${rom.file.name}.state")


    fun delete(context: Context, rom: Rom, keepSaves: Boolean = true) {
        val id = rom.id
        check(rom.file.delete()) { "DELETE_FAILED" }
        File(rom.file.path + ".id").delete()
        if (!keepSaves) {
            stateFile(context, rom).delete()
            File(context.filesDir, "saves/$id").deleteRecursively()
        }
    }
}


object CoreProvider {
    fun corePath(context: Context, system: SystemType): File? {
        val f = File(context.applicationInfo.nativeLibraryDir, system.coreLibName)
        return if (f.exists()) f else null
    }
}
