package com.retrotv.emu

/** Button-based D-pad state must retain the other held direction on key up/down. */
class DirectionalInput {
    private val keys = mutableSetOf<Int>()
    fun update(key: Int, down: Boolean): Pair<Float, Float> {
        if (down) keys += key else keys -= key
        return vector()
    }
    fun vector(): Pair<Float, Float> =
        ((if (RIGHT in keys) 1 else 0) - (if (LEFT in keys) 1 else 0)).toFloat() to
        ((if (DOWN in keys) 1 else 0) - (if (UP in keys) 1 else 0)).toFloat()
    fun clear() = keys.clear()
    companion object { const val UP = 19; const val DOWN = 20; const val LEFT = 21; const val RIGHT = 22 }
}
