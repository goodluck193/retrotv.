package com.retrotv.emu

import android.graphics.*
import android.graphics.drawable.Drawable

/** Static, resolution-independent scenery. No bitmaps, timers, shaders or network. */
class SidebarDrawable(private val theme: String) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat(); val h = bounds.height().toFloat()
        canvas.drawColor(when (theme) {
            "midnight" -> 0xFF0B1320.toInt(); "grid" -> 0xFF101021.toInt()
            "dunes" -> 0xFF211B20.toInt(); "forest" -> 0xFF0C1B1A.toInt()
            "arcade" -> 0xFF171326.toInt(); else -> Color.BLACK
        })
        if (theme == "black") return
        canvas.save(); canvas.scale(w / 1600f, h / 900f)
        fun color(c: Long) { paint.color = c.toInt() }
        when (theme) {
            "midnight" -> {
                color(0xFF18283F); canvas.drawCircle(90f, 240f, 170f, paint); canvas.drawCircle(1550f, 640f, 230f, paint)
                color(0xFF617388)
                repeat(36) { i -> val x = ((i * 233 + 41) % 1600).toFloat(); val y = ((i * 137 + 59) % 900).toFloat(); canvas.drawCircle(x, y, if (i % 4 == 0) 2.5f else 1.3f, paint) }
            }
            "grid" -> {
                color(0xFF252640); paint.strokeWidth = 1.5f
                for (x in 0..1600 step 70) canvas.drawLine(x.toFloat(), 0f, x.toFloat(), 900f, paint)
                for (y in 0..900 step 70) canvas.drawLine(0f, y.toFloat(), 1600f, y.toFloat(), paint)
            }
            "dunes" -> {
                color(0xFF47302E); canvas.drawOval(-400f, 430f, 650f, 1400f, paint); canvas.drawOval(900f, 350f, 1900f, 1400f, paint)
                color(0xFF30262B); canvas.drawOval(-500f, 670f, 1000f, 1450f, paint); canvas.drawOval(700f, 720f, 2200f, 1400f, paint)
                color(0xFF9B7560); canvas.drawCircle(1450f, 210f, 48f, paint)
            }
            "forest" -> {
                repeat(18) { i ->
                    color(if (i % 2 == 0) 0xFF17322C else 0xFF203E34)
                    val x = i * 102f - 30; val top = 310f + (i * 79 % 240)
                    canvas.drawPath(Path().apply { moveTo(x, top); lineTo(x - 105, 900f); lineTo(x + 105, 900f); close() }, paint)
                }
            }
            "arcade" -> {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 5f
                repeat(12) { i ->
                    color(if (i % 2 == 0) 0xFF34304F else 0xFF254452)
                    val x = if (i % 2 == 0) 50f + (i * 47 % 180) else 1360f + (i * 59 % 180)
                    val y = i * 79f + 30
                    if (i % 3 == 0) canvas.drawCircle(x, y, 23f, paint)
                    else canvas.drawRoundRect(x - 23, y - 23, x + 23, y + 23, 6f, 6f, paint)
                }
                paint.style = Paint.Style.FILL
            }
        }
        canvas.restore()
    }
    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.OPAQUE
}
