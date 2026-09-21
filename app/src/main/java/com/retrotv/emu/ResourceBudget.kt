package com.retrotv.emu

/** Optional caches take only a small part of the app heap, even on low-RAM TVs. */
object ResourceBudget {
    const val MIB = 1024 * 1024
    const val STORAGE_RESERVE = 64L * MIB
    const val COVER_RESERVE = 200L * MIB
    fun rewind(heap: Long, lowRam: Boolean) = minOf(heap / 16, (if (lowRam) 6L else 24L) * MIB).coerceAtLeast(0).toInt()
    fun covers(heap: Long, lowRam: Boolean) = minOf(heap / 32, (if (lowRam) 4L else 12L) * MIB).coerceAtLeast(1).toInt()
    fun canWrite(available: Long, needed: Long, reserve: Long = STORAGE_RESERVE) =
        needed >= 0 && available >= reserve && needed <= available - reserve
}

/** Count both queued and running work. Reservations happen before queueing closures. */
class WorkBudget(private val maxJobs: Int, private val maxBytes: Long) {
    private var jobs = 0
    private var bytes = 0L
    @Synchronized fun acquire(cost: Long): Boolean {
        if (cost < 0 || jobs >= maxJobs || cost > maxBytes - bytes) return false
        jobs++; bytes += cost
        return true
    }
    @Synchronized fun release(cost: Long) { check(jobs > 0 && cost <= bytes); jobs--; bytes -= cost }
    @Synchronized fun idle() = jobs == 0
}
