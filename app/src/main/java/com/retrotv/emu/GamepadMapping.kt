package com.retrotv.emu

/** Android names follow Xbox positions; LibretroDroid forwards the letters literally.
 * Translate positions to the SNES-shaped RetroPad used by all three cores.
 */
object GamepadMapping {
    const val CROSS = 96 // Android BUTTON_A (bottom)
    const val CIRCLE = 97 // BUTTON_B (right)
    const val SQUARE = 99 // BUTTON_X (left)
    const val TRIANGLE = 100 // BUTTON_Y (top)

    fun faceButton(androidKey: Int): Int? = when (androidKey) {
        CROSS -> 97 // RetroPad B
        CIRCLE -> 96 // RetroPad A
        SQUARE -> 100 // RetroPad Y
        TRIANGLE -> 99 // RetroPad X
        else -> null
    }
}
