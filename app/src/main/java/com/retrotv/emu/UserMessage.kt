package com.retrotv.emu

import android.content.Context

/** Translate stable domain error codes; keep OS/provider messages out of the user interface. */
fun Throwable.userMessage(context: Context): String {
    var error: Throwable? = this
    repeat(8) {
        val id = when (error?.message) {
            "STATE_SIZE" -> R.string.state_size
            "STATE_SPACE" -> R.string.state_space
            "STATE_CORE" -> R.string.state_core
            "STATE_DAMAGED" -> R.string.state_damaged
            "STATE_MISSING" -> R.string.no_save
            "SAVE_BUSY" -> R.string.save_busy
            "CORE_MISSING" -> R.string.core_missing
            "DELETE_FAILED" -> R.string.delete_game_failed
            else -> null
        }
        if (id != null) return context.getString(id)
        error = error?.cause
    }
    return context.getString(if (this is OutOfMemoryError) R.string.low_memory else R.string.unknown_error)
}
