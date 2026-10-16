package com.retrotv.emu

import kotlin.math.abs
import kotlin.math.sign

/** Android codes -> Mupen64Plus-Next independent C-button profile. */
class N64Input(private val emit: (Int, Boolean) -> Unit) {
    private val held = mutableMapOf<Int, ButtonLatch>()
    private fun set(code: Int, source: Int, down: Boolean) {
        held.getOrPut(code) { ButtonLatch { emit(code, it) } }.update(source, down)
    }
    fun key(code: Int, down: Boolean): Boolean {
        val index = keys.indexOf(code)
        if (index < 0) return false
        set(button(code)!!, 1 shl index, down)
        return true
    }
    fun triggers(left: Boolean, right: Boolean) {
        set(104, 1 shl 16, left) // Z
        set(105, 1 shl 17, right) // R, shared with R1/R2 keys
    }
    fun cStick(x: Float, y: Float) {
        set(102, 1 shl 18, x < -.5f) // C-left
        set(103, 1 shl 19, x > .5f) // C-right
        set(99, 1 shl 20, y < -.5f) // C-up, shared with Triangle
        set(96, 1 shl 21, y > .5f) // C-down, shared with Circle
    }
    fun clear() { held.values.forEach { it.clear() } }
    companion object {
        private val keys = listOf(96, 97, 99, 100, 102, 103, 104, 105, 108)
        fun button(code: Int): Int? = when (code) {
            96 -> 97 // Cross -> N64 A (RetroPad B)
            99 -> 100 // Square -> N64 B (RetroPad Y)
            97 -> 96 // Circle -> C-down
            100 -> 99 // Triangle -> C-up
            102 -> 109 // L1 -> N64 L (RetroPad Select)
            103, 105 -> 105 // R1/R2 -> N64 R
            104 -> 104 // L2 -> N64 Z
            108 -> 108 // Options -> Start
            else -> null
        }
        fun analog(value: Float): Float {
            if (!value.isFinite() || abs(value) <= .12f) return 0f
            return sign(value) * ((abs(value).coerceAtMost(1f) - .12f) / .88f)
        }
    }
}
