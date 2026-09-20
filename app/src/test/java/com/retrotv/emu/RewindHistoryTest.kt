package com.retrotv.emu

import org.junit.Assert.*
import org.junit.Test

class RewindHistoryTest {
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
