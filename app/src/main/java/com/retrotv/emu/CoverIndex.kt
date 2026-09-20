package com.retrotv.emu

import android.content.Context
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.zip.GZIPInputStream

object CoverIndex {
    /** Stream metadata on IO; never retain a full catalog or scan the network from the TV. */
    suspend fun candidates(context: Context, rom: Rom): List<String> {
        val key = CoverNames.normalize(rom.title)
        if (key.isBlank()) return emptyList()
        val result = mutableListOf<String>()
        val name = if (rom.system == SystemType.MEGADRIVE) "megadrive" else rom.system.id
        val coroutine = currentCoroutineContext()
        GZIPInputStream(context.assets.open("cover-index/$name.tsv.gz")).bufferedReader().use { reader ->
            var lines = 0
            while (true) {
                val line = reader.readLine() ?: break
                if (++lines % 128 == 0) coroutine.ensureActive()
                if (line.startsWith("$key\t")) {
                    result += line.substringAfter('\t')
                    if (result.size == 4) break
                }
            }
        }
        return result
    }
}
