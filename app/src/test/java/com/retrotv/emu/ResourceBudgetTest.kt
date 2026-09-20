package com.retrotv.emu

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ResourceBudgetTest {
    @Test fun cacheBudgetsShrinkOnSmallHeapsAndKeepStorageReserve() {
        val mb = ResourceBudget.MIB.toLong()
        assertEquals(6 * mb, ResourceBudget.rewind(256 * mb, true).toLong())
        assertEquals(4 * mb, ResourceBudget.rewind(64 * mb, false).toLong())
        assertEquals(24 * mb, ResourceBudget.rewind(1024 * mb, false).toLong())
        assertEquals(2 * mb, ResourceBudget.covers(64 * mb, false).toLong())
        assertFalse(ResourceBudget.canWrite(65 * mb, 2 * mb))
        assertTrue(ResourceBudget.canWrite(66 * mb, 2 * mb))
        assertFalse(ResourceBudget.canWrite(Long.MAX_VALUE, Long.MAX_VALUE))
    }
    @Test fun slowWriterCannotAccumulateUnboundedWork() {
        val budget = WorkBudget(3, 100)
        assertTrue(budget.acquire(60)); assertTrue(budget.acquire(40))
        repeat(10_000) { assertFalse(budget.acquire(1)) }
        budget.release(60); assertTrue(budget.acquire(50))
        budget.release(40); budget.release(50); assertTrue(budget.idle())
    }
    @Test fun concurrentReservationsReleaseAfterFailure() {
        val budget = WorkBudget(3, 100)
        val active = AtomicInteger(); val peak = AtomicInteger(); val accepted = AtomicInteger()
        val start = CountDownLatch(1); val pool = Executors.newFixedThreadPool(8)
        val tasks = (1..8).map { pool.submit {
            start.await()
            repeat(1000) {
                if (budget.acquire(40)) {
                    accepted.incrementAndGet()
                    val count = active.incrementAndGet(); peak.updateAndGet { maxOf(it, count) }
                    try { Thread.yield(); throw IllegalStateException("disk write failed") }
                    catch (_: IllegalStateException) { }
                    finally { active.decrementAndGet(); budget.release(40) }
                }
            }
        } }
        start.countDown()
        try { tasks.forEach { it.get(10, TimeUnit.SECONDS) } } finally { pool.shutdownNow() }
        assertTrue(accepted.get() > 0); assertTrue(peak.get() <= 2); assertTrue(budget.idle())
    }
    @Test fun coverCleanupBoundsBytesAndEmptyMarkersWithoutTouchingInflightDownload() {
        val dir = Files.createTempDirectory("covers").toFile()
        try {
            for (i in 1..10) File(dir, "$i.png").apply { writeBytes(ByteArray(20)); setLastModified(i * 1000L) }
            val active = File(dir, "active.part").apply { writeBytes(ByteArray(40)) }
            DiskCache.trim(dir, 60, 100)
            assertEquals(setOf("8.png", "9.png", "10.png", "active.part"), dir.list()!!.toSet())
            repeat(1000) { File(dir, "$it.missing").writeText("") }
            DiskCache.trim(dir, 60, 20)
            assertEquals(21, dir.list()!!.size); assertTrue(active.exists())
        } finally { dir.deleteRecursively() }
    }
    @Test fun longRewindSessionReleasesEveryPreviewOnExit() {
        val released = mutableSetOf<Int>()
        val history = RewindHistory<Int>(5000, 30) { assertTrue(released.add(it)) }
        repeat(10_000) { i ->
            history.add(ByteArray(100 + i % 300), i * 500L, i, 100)
            assertTrue(history.bytes <= 5000); assertTrue(history.size <= 30)
        }
        history.clear(); history.clear()
        assertEquals(0, history.bytes); assertEquals(0, history.size); assertEquals(10_000, released.size)
    }
}
