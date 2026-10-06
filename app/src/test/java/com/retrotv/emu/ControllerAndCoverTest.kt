package com.retrotv.emu

import org.junit.Assert.*
import org.junit.Test

class ControllerAndCoverTest {
    @Test fun facePositionsReachTheExpectedRetroPadButtons() {
        // Android key codes -> LibretroDroid key codes, whose letters are literal.
        assertEquals(97, GamepadMapping.faceButton(96)) // Cross -> B (bottom)
        assertEquals(96, GamepadMapping.faceButton(97)) // Circle -> A (right)
        assertEquals(100, GamepadMapping.faceButton(99)) // Square -> Y (left)
        assertEquals(99, GamepadMapping.faceButton(100)) // Triangle -> X (top)
        assertNull(GamepadMapping.faceButton(107)) // R3 must never become a face button.
    }
    @Test fun menuPressOpensOnceUntilKeyAndHatAreReleased() {
        var opens = 0
        val menu = ButtonLatch { if (it) opens++ }
        menu.update(1, true); menu.update(2, true)
        repeat(20) { menu.update(1, true); menu.update(2, true) }
        assertEquals(1, opens)
        menu.update(1, false); menu.update(2, false)
        menu.update(2, true)
        assertEquals(2, opens)
        menu.clear(); assertEquals(2, opens)
    }
    @Test fun coverAliasMatchesTheCatalogWithoutConfusingOtherEccoGames() {
        assertEquals(CoverNames.searchKey("Ecco - The Tides of Time (USA)"), CoverNames.searchKey("Ecco - Tides of Time]"))
        assertNotEquals(CoverNames.searchKey("Ecco the Dolphin"), CoverNames.searchKey("Ecco - Tides of Time"))
        assertNotEquals(CoverNames.searchKey("Ecco Jr."), CoverNames.searchKey("Ecco - Tides of Time"))
    }
    @Test fun displayTitleCleansRegionTagsAndDanglingBrackets() {
        assertEquals("Bomberman", CoverNames.displayTitle("Bomberman (USA) [!]]"))
        assertEquals("Cannon Fodder", CoverNames.displayTitle("Cannon Fodder]"))
        assertEquals("Sonic 2", CoverNames.displayTitle("Sonic 2 (Europe) [T+En]"))
    }
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
