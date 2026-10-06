package com.retrotv.emu

import java.nio.ByteBuffer
import java.util.Random
import org.junit.Assert.*
import org.junit.Test

class N64Test {
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Invalid data accepted") } catch (_: Exception) { }
    }
    @Test fun fullSizeStateRoundTripsWithinBoundedHistory() {
        val raw = ByteArray(16793412) { (it / 16384).toByte() }
        val packed = N64StateCodec.encode(raw)
        assertTrue(packed.size < 1024 * 1024)
        assertEquals(raw.size, N64StateCodec.rawSize(packed))
        assertArrayEquals(raw, N64StateCodec.decode(packed))
    }
    @Test fun truncatedCorruptAndOversizedStatesAreRejected() {
        val packed = N64StateCodec.encode(ByteArray(65536) { it.toByte() })
        rejects { N64StateCodec.decode(packed.copyOf(packed.size - 1)) }
        rejects { N64StateCodec.decode(packed + byteArrayOf(1)) }
        val badCrc = packed.clone(); badCrc[8] = (badCrc[8].toInt() xor 1).toByte()
        rejects { N64StateCodec.decode(badCrc) }
        val bomb = packed.clone(); ByteBuffer.wrap(bomb).putInt(4, Int.MAX_VALUE)
        rejects { N64StateCodec.decode(bomb) }
        rejects { N64StateCodec.encode(ByteArray(0)) }
        val random = ByteArray(N64StateCodec.MAX_PACKED + 1); Random(17).nextBytes(random)
        rejects { N64StateCodec.encode(random) }
    }
    @Test fun rightStickAndFaceButtonReleasesDoNotCancelEachOther() {
        val events = mutableListOf<Pair<Int, Boolean>>()
        val input = N64Input { code, down -> events += code to down }
        input.key(97, true); input.cStick(0f, 1f)
        input.key(97, false)
        assertEquals(listOf(96 to true), events)
        input.cStick(0f, 0f)
        assertEquals(listOf(96 to true, 96 to false), events)
    }
    @Test fun shoulderAndTriggerHaveIndependentSourcesAndPauseReleasesAll() {
        val events = mutableListOf<Pair<Int, Boolean>>()
        val input = N64Input { code, down -> events += code to down }
        input.key(103, true); input.key(105, true); input.triggers(false, true)
        input.key(103, false); input.key(105, false)
        assertEquals(listOf(105 to true), events)
        input.clear(); input.clear()
        assertEquals(listOf(105 to true, 105 to false), events)
        assertFalse(input.key(106, true)) // L3 belongs to rewind, never a core button.
        assertFalse(input.key(109, true)) // Share belongs to the menu.
        assertEquals(109, N64Input.button(102)) // N64 L.
        assertEquals(104, N64Input.button(104)) // N64 Z.
    }
    @Test fun analogTravelHasDeadzoneAndKeepsIntermediateValues() {
        assertEquals(0f, N64Input.analog(.1f), 0f)
        assertEquals(.5f, N64Input.analog(.56f), .0001f)
        assertEquals(-1f, N64Input.analog(-1f), 0f)
        assertEquals(1f, N64Input.analog(2f), 0f)
        assertEquals(0f, N64Input.analog(Float.NaN), 0f)
    }
    @Test fun n64ImportValidatesAllThreeByteOrdersWithoutRaisingLegacyLimits() {
        for ((ext, bytes) in listOf("z64" to intArrayOf(0x80,0x37,0x12,0x40),
            "v64" to intArrayOf(0x37,0x80,0x40,0x12), "n64" to intArrayOf(0x40,0x12,0x37,0x80))) {
            assertEquals(SystemType.N64, SystemType.fromFileName("game.$ext"))
            assertTrue(SystemType.N64.looksLikeValidRom(bytes.map { it.toByte() }.toByteArray(), ext))
            assertFalse(SystemType.N64.looksLikeValidRom(byteArrayOf(0,0,0,0), ext))
        }
        assertEquals(32L * 1024 * 1024, SystemType.sourceLimit("sfc"))
        assertEquals(64L * 1024 * 1024, SystemType.N64.maxRomBytes)
        assertEquals(16L * 1024 * 1024, SystemType.SNES.maxRomBytes)
    }
}
