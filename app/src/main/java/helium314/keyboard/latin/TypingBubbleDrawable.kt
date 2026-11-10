// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import androidx.core.graphics.PathParser
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * The icon of MatriX (a chat bubble with the crown of Keyarchy) while the person types there: the three points of the
 * crown go up and down in turn, as the dots of "typing" in WhatsApp; the bubble stays still. In [color], as the other
 * icons, and only while it is visible.
 */
class TypingBubbleDrawable(color: Int) : Drawable(), Animatable, Runnable {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val matrix = Matrix()
    private val bubble = Path()
    private val point = Path()
    private var scale = 1f
    private var running = false

    override fun onBoundsChange(bounds: Rect) {
        // the 108 dp layers of an adaptive icon, of which 72 show, as Keyarchy draws the icons of apps
        val size = minOf(bounds.width(), bounds.height()).toFloat()
        scale = size / 72f
        matrix.setTranslate(-54f, -54f)
        matrix.postScale(scale, scale)
        matrix.postTranslate(bounds.exactCenterX(), bounds.exactCenterY())
        bubble.set(BUBBLE)
        bubble.transform(matrix)
    }

    override fun draw(canvas: Canvas) {
        paint.strokeWidth = BUBBLE_STROKE * scale
        canvas.drawPath(bubble, paint)
        paint.strokeWidth = CROWN_STROKE * scale
        val phase = (SystemClock.uptimeMillis() % CYCLE_MS) / CYCLE_MS.toFloat()
        POINTS.forEachIndexed { i, peak ->
            val rise = max(0f, sin(2 * PI * (phase - i / 3f)).toFloat()) * RISE
            point.reset()
            point.moveTo(peak - HALF, BASE)
            point.lineTo(peak, PEAKS[i] - rise)
            point.lineTo(peak + HALF, BASE)
            point.transform(matrix)
            canvas.drawPath(point, paint)
        }
    }

    override fun start() {
        if (running) return
        running = true
        scheduleSelf(this, SystemClock.uptimeMillis() + FRAME_MS)
    }

    override fun stop() {
        running = false
        unscheduleSelf(this)
    }

    override fun isRunning() = running

    override fun run() {
        invalidateSelf()
        if (running) scheduleSelf(this, SystemClock.uptimeMillis() + FRAME_MS)
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
        /** the bubble of the icon of MatriX (as WhatsApp's: centre 54.09,53.92, radius 20.05, the tail at the bottom left) */
        val BUBBLE: Path = PathParser.createPathFromPathData("M36.73,63.95 A20.05,20.05 0 1 1 44.49,71.52 L33.7,74.5 Z")
        const val BUBBLE_STROKE = 3.7f
        /** the crown of Keyarchy in it (scaled by 0.17): the middles of its three points, their tops, half a width, the base */
        val POINTS = floatArrayOf(45.93f, 54.09f, 62.25f)
        val PEAKS = floatArrayOf(52.22f, 49.16f, 52.22f)
        const val HALF = 3.4f
        const val BASE = 58.68f
        const val CROWN_STROKE = 3.4f
        /** how high a point goes, and how long the three take in turn (ms) */
        const val RISE = 2.8f
        const val CYCLE_MS = 1200L
        const val FRAME_MS = 40L
    }
}
