package com.retrotv.emu

/** Memory-bounded history. Removing the future after a restore prevents time jumps. */
class RewindHistory<T>(private val budget: Int, private val maxEntries: Int, private val release: (T) -> Unit = {}) {
    data class Entry<T>(val state: ByteArray, val timeMs: Long, val preview: T?, val cost: Int)
    private val entries = ArrayDeque<Entry<T>>()
    var bytes = 0; private set
    val size get() = entries.size
    operator fun get(index: Int) = entries[index]
    fun add(state: ByteArray, timeMs: Long, preview: T?, previewBytes: Int = 0) {
        val cost = state.size + previewBytes
        if (cost > budget) { preview?.let(release); return }
        while (entries.isNotEmpty() && (bytes + cost > budget || entries.size >= maxEntries)) removeFirst()
        entries.addLast(Entry(state, timeMs, preview, cost)); bytes += cost
    }
    fun discardAfter(index: Int) {
        while (entries.size > index + 1) {
            val entry = entries.removeLast(); bytes -= entry.cost; entry.preview?.let(release)
        }
    }
    fun clear() { while (entries.isNotEmpty()) removeFirst() }
    private fun removeFirst() {
        val entry = entries.removeFirst(); bytes -= entry.cost; entry.preview?.let(release)
    }
}
