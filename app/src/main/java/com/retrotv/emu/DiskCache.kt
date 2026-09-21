package com.retrotv.emu

import java.io.File

/** Deletes only replaceable cover files, oldest first; includes zero-byte miss markers. */
object DiskCache {
    @Synchronized fun trim(folder: File, limit: Long, maxFiles: Int) {
        val files = folder.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") }
            ?.sortedBy { it.lastModified() } ?: return
        var bytes = files.sumOf { it.length() }; var count = files.size
        for (file in files) {
            if (bytes <= limit && count <= maxFiles) break
            val size = file.length()
            if (file.delete()) { bytes -= size; count-- }
        }
    }
}
