package com.retrotv.emu

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import kotlin.math.roundToInt

/** One small vector tile repeated at a fixed dp size. No animation or full-screen image. */
class SidebarDrawable(context: Context, theme: String) : Drawable() {
    private val paint = Paint().apply {
        color = when (theme) {
            "midnight" -> 0xFF0B1320.toInt(); "grid" -> 0xFF101021.toInt()
            "dunes" -> 0xFF211B20.toInt(); "forest" -> 0xFF0C1B1A.toInt()
            "arcade" -> 0xFF171326.toInt(); else -> Color.BLACK
        }
    }
    private val tileSize = (120f * context.resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
    private val tile = when (theme) {
        "midnight" -> R.drawable.sidebar_midnight
        "grid" -> R.drawable.sidebar_grid
        "dunes" -> R.drawable.sidebar_dunes
        "forest" -> R.drawable.sidebar_forest
        "arcade" -> R.drawable.sidebar_arcade
        else -> null
    }?.let { context.getDrawable(it)?.mutate() }?.apply { setBounds(0, 0, tileSize, tileSize) }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        val saved = canvas.save()
        canvas.clipRect(bounds)
        canvas.drawRect(bounds, paint)
        tile?.let { pattern ->
            // Equal width/height for every tile, regardless of the view's aspect ratio.
            var y = bounds.top
            while (y < bounds.bottom) {
                var x = bounds.left
                while (x < bounds.right) {
                    val cell = canvas.save()
                    canvas.translate(x.toFloat(), y.toFloat())
                    pattern.draw(canvas)
                    canvas.restoreToCount(cell)
                    x += tileSize
                }
                y += tileSize
            }
        }
        canvas.restoreToCount(saved)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; tile?.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; tile?.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Android") override fun getOpacity() = if (paint.alpha == 255) PixelFormat.OPAQUE else PixelFormat.TRANSLUCENT
}
