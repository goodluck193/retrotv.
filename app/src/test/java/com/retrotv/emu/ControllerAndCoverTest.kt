package com.retrotv.emu

import org.junit.Assert.*
import org.junit.Test

class ControllerAndCoverTest {
    @Test fun duplicatedKeyAndHatRewindRequiresBothReleases() {
        val events = mutableListOf<Boolean>()
        val button = ButtonLatch { events += it }
        button.update(1, true); button.update(2, true)
        repeat(50) { button.update(1, true) }
        assertEquals(listOf(true), events)
        button.update(1, false); assertTrue(button.pressed)
        button.update(2, false); assertEquals(listOf(true, false), events)
        button.update(2, false); assertEquals(2, events.size)
    }
    @Test fun l1AndL2WithAnalogDoNotReleaseEachOther() {
        val events = mutableListOf<Boolean>()
        val shoulder = ButtonLatch { events += it }
        shoulder.update(1, true); shoulder.update(2, true); shoulder.update(4, true)
        shoulder.update(2, false); shoulder.update(4, false)
        assertEquals(listOf(true), events)
        shoulder.clear(); shoulder.clear()
        assertEquals(listOf(true, false), events)
        shoulder.update(4, true); shoulder.update(4, false)
        assertEquals(listOf(true, false, true, false), events)
    }
    @Test fun exactCoverTitleIgnoresRegionsAndPunctuationButKeepsSequels() {
        assertEquals(CoverNames.normalize("Desert Demolition Starring Road Runner and Wile E. Coyote (USA, Europe)"),
            CoverNames.normalize("Desert Demolition Starring Road Runner & Wile E Coyote [!]") )
        assertEquals(CoverNames.normalize("The Lion King (Europe)"), CoverNames.normalize("Lion King, The (USA)"))
        assertNotEquals(CoverNames.normalize("Sonic the Hedgehog"), CoverNames.normalize("Sonic the Hedgehog 2"))
    }
}
