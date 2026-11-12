package com.tabisland

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.animation.OvershootInterpolator
import kotlin.math.min

/**
 * Padlock that appears closed and then springs open, like the iPhone's unlock in the Dynamic Island:
 * the shackle lifts and swings free of the body with a little overshoot, and the lock pops slightly as it opens.
 * The animation starts the first time it's drawn, so it plays as the pill appears.
 */
class UnlockDrawable(color: Int) : Drawable() {
    /** Called once, the moment the shackle starts to open (the island uses it for a haptic tick). */
    var onOpen: (() -> Unit)? = null
    private var open = 0f // 0 closed → 1 open (overshoots a little)
    private var pop = 1f
    private var started = false
    private var opened = false
    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private val shackle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val rect = RectF()
    private val arc = RectF()
    private val path = Path()

    override fun draw(c: Canvas) {
        if (!started) { started = true; play() }
        val b = bounds
        // 0.66: the shackle at its overshoot (lifted 0.24, swung 31°) reaches 0.25 above the lock and 0.51 left of
        // centre; popped 1.18× that must stay inside the bounds and the 11dp corners of the slot's clipToOutline
        val s = min(b.width(), b.height()) * 0.66f
        val cx = b.exactCenterX()
        val top = b.exactCenterY() - s * 0.344f // centres the lock's full travel, from the raised shackle down to the body
        c.save()
        c.scale(pop, pop, cx, b.exactCenterY())

        val bodyTop = top + s * 0.48f
        rect.set(cx - s * 0.32f, bodyTop, cx + s * 0.32f, top + s * 0.94f)
        c.drawRoundRect(rect, s * 0.1f, s * 0.1f, body)

        val stroke = s * 0.11f
        val legL = cx - s * 0.20f
        val legR = cx + s * 0.20f
        val lift = s * 0.20f * open
        val archTop = top + s * 0.08f - lift
        shackle.strokeWidth = stroke
        path.reset()
        path.moveTo(legL, bodyTop + stroke * 0.4f - lift) // left leg stays seated in the body, just raised
        path.lineTo(legL, archTop + (legR - legL) / 2)
        arc.set(legL, archTop, legR, archTop + (legR - legL))
        path.arcTo(arc, 180f, 180f)
        path.lineTo(legR, bodyTop + stroke * 0.4f - lift - s * 0.12f * open) // right leg comes free
        c.save()
        c.rotate(-26f * open, legL, bodyTop) // swing open around the left leg
        c.drawPath(path, shackle)
        c.restore()
        c.restore()
    }

    private fun play() {
        ValueAnimator.ofFloat(0f, 1f).apply {
            startDelay = 450 // the pill has grown and the closed lock has clearly registered
            duration = 480
            interpolator = OvershootInterpolator(2.4f)
            addUpdateListener {
                open = it.animatedValue as Float
                if (!opened && it.animatedFraction > 0f) { opened = true; onOpen?.invoke() }
                invalidateSelf()
            }
            start()
        }
        ValueAnimator.ofFloat(1f, 1.18f, 1f).apply {
            startDelay = 450
            duration = 400
            addUpdateListener { pop = it.animatedValue as Float; invalidateSelf() }
            start()
        }
    }

    override fun setAlpha(alpha: Int) { body.alpha = alpha; shackle.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { body.colorFilter = cf; shackle.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
