package com.retrotv.emu

import org.junit.Assert.*
import org.junit.Test

class RewindHistoryTest {
    @Test fun oldestPreviewIsReleasedBeforeTheNextAllocation() {
        val released = mutableListOf<Int>()
        val history = RewindHistory<Int>(100, 3) { released += it }
        history.add(ByteArray(40), 1, 1, 10)
        history.add(ByteArray(40), 2, 2, 10)
        assertTrue(history.makeRoom(60))
        assertEquals(0, history.bytes); assertEquals(listOf(1, 2), released)
        assertFalse(history.makeRoom(Long.MAX_VALUE)); assertFalse(history.makeRoom(-1))
    }
    @Test fun repeatedBranchingAndExitStayBoundedAndReleaseEveryPreviewExactlyOnce() {
        val released = mutableSetOf<Int>()
        val history = RewindHistory<Int>(8192, 20) { assertTrue("released twice: $it", released.add(it)) }
        val random = java.util.Random(42)
        repeat(100_000) { i ->
            val size = 100 + random.nextInt(2000)
            assertTrue(history.makeRoom(size + 256L))
            assertTrue(history.bytes + size + 256 <= 8192)
            history.add(ByteArray(size), i.toLong(), i, 256)
            if (i % 11 == 0 && history.size > 0) history.discardAfter(random.nextInt(history.size))
            if (i % 97 == 0) history.clear()
            assertTrue(history.bytes in 0..8192); assertTrue(history.size in 0..20)
            assertEquals(history.bytes, (0 until history.size).sumOf { history[it].cost })
        }
        history.clear(); history.clear()
        assertEquals(100_000, released.size); assertEquals(0, history.bytes)
    }
    @Test fun memoryBoundIncludesPreviewsAndReleasesOldEntries() {
        val released = mutableListOf<Int>()
        val history = RewindHistory<Int>(100, 10) { released += it }
        history.add(ByteArray(40), 500, 1, 20)
        history.add(ByteArray(40), 1000, 2, 20)
        assertEquals(1, history.size); assertEquals(60, history.bytes)
        assertEquals(listOf(1), released)
        history.add(ByteArray(120), 1500, 3)
        assertEquals(1, history.size); assertEquals(listOf(1, 3), released)
    }
    @Test fun restoringAnOlderFrameDiscardsFutureAndRetainsPast() {
        val history = RewindHistory<Unit>(1000, 3)
        for (t in 1..4) history.add(ByteArray(10), t * 500L, null)
        assertEquals(3, history.size); assertEquals(1000L, history[0].timeMs)
        history.discardAfter(1)
        assertEquals(2, history.size); assertEquals(20, history.bytes)
        history.add(ByteArray(20), 1750, null)
        assertEquals(1750L, history[2].timeMs)
        history.clear(); assertEquals(0, history.bytes)
    }
}
