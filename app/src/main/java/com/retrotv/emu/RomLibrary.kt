package com.retrotv.emu

import android.content.Context
import java.io.File

data class Rom(val file: File, val system: SystemType) {
    val title: String get() = file.name.substringBeforeLast('.')
}

object RomLibrary {

    fun scan(context: Context): List<Rom> {
        val result = mutableListOf<Rom>()
        for (system in SystemType.entries) {
            val dir = RomImporter.romsDir(context, system)
            dir.listFiles()?.filter { it.isFile }?.forEach { f ->
                if (SystemType.fromFileName(f.name) == system) result += Rom(f, system)
            }
        }
        return result.sortedBy { it.title.lowercase() }
    }

    fun statesDir(context: Context): File =
        File(context.filesDir, "states").apply { mkdirs() }

    fun stateFile(context: Context, rom: Rom): File =
        File(statesDir(context), "${rom.system.id}_${rom.file.name}.state")

    /** Удаляет ром вместе с его сохранением состояния. */
    fun delete(context: Context, rom: Rom) {
        rom.file.delete()
        stateFile(context, rom).delete()
    }
}

/**
 * Ядра-эмуляторы (libretro) упакованы внутрь APK как нативные библиотеки.
 * Так безопаснее и надёжнее: ничего не скачивается на ТВ и не исполняется
 * из записываемых папок (что запрещено на новых версиях Android).
 */
object CoreProvider {
    fun corePath(context: Context, system: SystemType): File? {
        val f = File(context.applicationInfo.nativeLibraryDir, system.coreLibName)
        return if (f.exists()) f else null
    }
}
