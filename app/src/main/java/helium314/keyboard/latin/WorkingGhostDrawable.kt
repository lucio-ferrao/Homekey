// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import androidx.core.graphics.PathParser

/**
 * The ghost of Termarchy (its icon, the crown of Keyarchy at its base) with the line of its eyes blinking like a cursor,
 * in [color]: the icon of a Termarchy pane whose agent is working, as Termarchy shows it. The blinking counts from
 * [startedAt] (wall-clock millis, when the agent started working), so ghosts of tasks started apart do not blink
 * together. It runs only while it is visible.
 */
class WorkingGhostDrawable(color: Int, val startedAt: Long) : Drawable(), Animatable, Runnable {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val body = Path()
    private val eyes = Path()
    private var eyesShown = true
    private var running = false

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        // the drawing (its 108 dp viewport) scaled so the ghost fills [SIZE] of the icon, centred
        val size = minOf(bounds.width(), bounds.height()).toFloat()
        val scale = size * SIZE / GHOST_HEIGHT
        val matrix = Matrix().apply {
            setTranslate(-GHOST_CENTER_X, -GHOST_CENTER_Y)
            postScale(scale, scale)
            postTranslate(bounds.exactCenterX(), bounds.exactCenterY())
        }
        body.set(BODY); body.transform(matrix)
        eyes.set(EYES); eyes.transform(matrix)
        paint.strokeWidth = STROKE * scale
    }

    override fun draw(canvas: Canvas) {
        canvas.drawPath(body, paint)
        if (eyesShown) canvas.drawPath(eyes, paint)
    }

    /** whether the eyes show now, and the uptime at which they change */
    private fun advance(): Long {
        val elapsed = Math.floorMod(System.currentTimeMillis() - startedAt, 2 * BLINK)
        eyesShown = elapsed < BLINK
        return SystemClock.uptimeMillis() + BLINK - elapsed % BLINK
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
        /** the ghost and its eyes, as in Termarchy's ic_launcher_monochrome */
        val BODY: Path = PathParser.createPathFromPathData("M36,54 C36,34 72,34 72,54 L72,78 L72.00,78.00 L66.71,67.94 L61.41,78.00 L59.29,78.00 L54.00,63.18 L48.71,78.00 L46.59,78.00 L41.29,67.94 L36.00,78.00 L36,78 Z")
        val EYES: Path = PathParser.createPathFromPathData("M45,52 H63")
        const val STROKE = 3.7f
        /** the ghost's middle and height in the drawing, its line included */
        const val GHOST_CENTER_X = 54f
        const val GHOST_CENTER_Y = 56f
        const val GHOST_HEIGHT = 47.7f
        /** the part of the icon the ghost fills, as the symbol of an adaptive icon (66 of its 108 dp) */
        const val SIZE = 0.61f
        /** how long the eyes stay shown, then hidden (ms), as in Termarchy */
        const val BLINK = 420L
    }
}
