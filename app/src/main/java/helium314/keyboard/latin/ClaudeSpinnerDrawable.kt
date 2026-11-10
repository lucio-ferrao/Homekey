// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.SystemClock

/**
 * The spinner of Claude Code while it works, drawn with its characters (· ✢ ✳ ✶ ✻ and back, as the terminal shows
 * them) in [color]: the icon of a Termarchy pane whose agent is working. Its cycle counts from [startedAt] (wall-clock
 * millis, when the agent started working), so spinners of tasks started apart do not move together. It runs only while
 * it is visible.
 */
class ClaudeSpinnerDrawable(color: Int, val startedAt: Long) : Drawable(), Animatable, Runnable {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private val glyphBounds = Rect()
    private var frame = 0
    private var running = false

    /** the frame for now, and the uptime at which the next one begins */
    private fun advance(): Long {
        var left = Math.floorMod(System.currentTimeMillis() - startedAt, CYCLE)
        frame = FRAMES.indexOfFirst { (_, ms) -> (left < ms).also { if (!it) left -= ms } }
        return SystemClock.uptimeMillis() + FRAMES[frame].second - left
    }

    override fun draw(canvas: Canvas) {
        val size = minOf(bounds.width(), bounds.height()).toFloat()
        val glyph = FRAMES[frame].first
        // the middle dot is too small next to the stars: a round dot of about a third of the stars instead
        if (glyph == DOT)
            return canvas.drawCircle(bounds.exactCenterX(), bounds.exactCenterY(), size * GLYPH * 0.17f, paint)
        // each star as big as the symbols of the other icons, whatever its size in the font
        paint.textSize = size
        paint.getTextBounds(glyph, 0, glyph.length, glyphBounds)
        paint.textSize = size * size * GLYPH / maxOf(glyphBounds.width(), glyphBounds.height(), 1)
        paint.getTextBounds(glyph, 0, glyph.length, glyphBounds)
        canvas.drawText(glyph, bounds.exactCenterX() - glyphBounds.exactCenterX(),
            bounds.exactCenterY() - glyphBounds.exactCenterY(), paint)
    }

    override fun start() {
        if (running) return
        running = true
        scheduleSelf(this, advance())
        invalidateSelf()
    }

    override fun stop() {
        running = false
        unscheduleSelf(this)
    }

    override fun isRunning() = running

    override fun run() {
        val next = advance()
        invalidateSelf()
        if (running) scheduleSelf(this, next)
    }

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        if (visible) start() else stop()
        return changed
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    private companion object {
        const val DOT = "·"
        /** the part of the icon a star fills: as the symbol of an adaptive icon, 66 of its 108 dp */
        const val GLYPH = 0.61f
        /** the characters with how long each stays (ms), 1.67 s in all; U+FE0E keeps ✳ as text, not an emoji */
        val FRAMES = listOf(DOT to 270L, "✢" to 130L, "✳︎" to 130L, "✶" to 140L, "✻" to 460L, "✶" to 140L,
            "✳︎" to 130L, "✢" to 130L, DOT to 140L)
        val CYCLE = FRAMES.sumOf { it.second }
    }
}
