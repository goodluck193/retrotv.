package com.retrotv.emu

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class StateFileTest {
    @Test fun corruptedPrimaryRecoversPreviousGeneration() {
        val dir = Files.createTempDirectory("saves").toFile()
        try {
            val file = java.io.File(dir, "game.state")
            StateFile.write(file, byteArrayOf(1, 2, 3), "core1")
            StateFile.write(file, byteArrayOf(4, 5), "core1")
            file.writeBytes(byteArrayOf(0))
            val recovered = StateFile.read(file, "core1")
            assertTrue(recovered.fromBackup)
            assertArrayEquals(byteArrayOf(1, 2, 3), recovered.bytes)
            StateFile.write(file, byteArrayOf(8), "core1")
            file.writeBytes(byteArrayOf(0))
            assertArrayEquals(byteArrayOf(1, 2, 3), StateFile.read(file, "core1").bytes)
        } finally { dir.deleteRecursively() }
    }
    @Test fun rejectsCoreMismatchAndTruncatedData() {
        val dir = Files.createTempDirectory("saves").toFile()
        try {
            val file = java.io.File(dir, "game.state")
            StateFile.write(file, byteArrayOf(1, 2), "old-core")
            assertThrows(java.io.IOException::class.java) { StateFile.read(file, "new-core") }
            file.writeBytes(file.readBytes().dropLast(1).toByteArray())
            assertThrows(java.io.IOException::class.java) { StateFile.read(file) }
        } finally { dir.deleteRecursively() }
    }
    @Test fun diagonalsAndReleasesRetainHeldDirection() {
        val input = DirectionalInput()
        input.update(DirectionalInput.UP, true)
        assertEquals(1f to -1f, input.update(DirectionalInput.RIGHT, true))
        assertEquals(0f to -1f, input.update(DirectionalInput.RIGHT, false))
        input.clear()
        assertEquals(0f to 0f, input.vector())
    }
}
