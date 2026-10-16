package com.retrotv.emu

/** Multiple physical sources can hold one virtual button without releasing each other. */
class ButtonLatch(private val changed: (Boolean) -> Unit) {
    private var sources = 0
    val pressed: Boolean get() = sources != 0
    fun update(source: Int, down: Boolean) {
        val before = pressed
        sources = if (down) sources or source else sources and source.inv()
        if (before != pressed) changed(pressed)
    }
    fun clear() { val before = pressed; sources = 0; if (before) changed(false) }
}
