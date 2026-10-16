package com.retrotv.emu

/** A rejected state may partially change a core: restore the origin or stop without saving. */
object RewindRestore {
    enum class Result { APPLIED, ROLLED_BACK, FAILED }

    fun apply(state: ByteArray, origin: ByteArray?, restore: (ByteArray) -> Boolean,
              clearHistory: () -> Unit, onLowMemory: () -> Unit): Result {
        fun attempt(bytes: ByteArray): Boolean = try { restore(bytes) }
        catch (_: OutOfMemoryError) { onLowMemory(); false }
        catch (_: Exception) { false }

        if (attempt(state)) return Result.APPLIED
        // Drop optional buffers before attempting recovery, including on ordinary core errors.
        clearHistory()
        return if (origin != null && attempt(origin)) Result.ROLLED_BACK else Result.FAILED
    }
}
