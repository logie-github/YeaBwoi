// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.azuredreams

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Specks of warm light drifting slowly up through the game's picture, twinkling: the dust in the
 * air of the 2D-HD picture. Over the picture only (the PlayStation's 4:3, centered), never the
 * black bars beside it. Doesn't take touches.
 */
class MoteView(context: Context) : View(context) {
    private class Mote(
        var x: Float,
        var y: Float,
        val size: Float,
        /** Picture heights per second. */
        val rise: Float,
        val sway: Float,
        val swayHz: Float,
        val twinkleHz: Float,
        val phase: Float,
        val brightness: Float,
    )

    private val random = Random(1997)
    private val motes = ArrayList<Mote>()
    private val picture = RectF()
    private val dest = RectF()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dot: Bitmap = softDot()
    private var running = false
    private var lastFrame = 0L
    private var time = 0f

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Drifting while the game runs; frozen (and not redrawing) while it's paused. */
    fun setRunning(running: Boolean) {
        if (this.running == running) return
        this.running = running
        lastFrame = SystemClock.uptimeMillis()
        if (running) postInvalidateOnAnimation()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val pictureWidth = min(w.toFloat(), h * 4f / 3f)
        val pictureHeight = pictureWidth * 3f / 4f
        picture.set((w - pictureWidth) / 2, (h - pictureHeight) / 2, (w + pictureWidth) / 2, (h + pictureHeight) / 2)
        motes.clear()
        repeat(MOTES) { motes.add(newMote(picture.top + random.nextFloat() * picture.height())) }
    }

    private fun newMote(y: Float): Mote {
        val density = resources.displayMetrics.density
        val near = random.nextFloat()
        return Mote(
            x = picture.left + random.nextFloat() * picture.width(),
            y = y,
            size = (3f + 7f * near * near) * density,
            rise = 0.015f + 0.035f * random.nextFloat(),
            sway = (6f + 14f * random.nextFloat()) * density,
            swayHz = 0.05f + 0.12f * random.nextFloat(),
            twinkleHz = 0.15f + 0.45f * random.nextFloat(),
            phase = random.nextFloat() * 2f * PI.toFloat(),
            brightness = 0.25f + 0.45f * random.nextFloat(),
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (picture.isEmpty) return
        val now = SystemClock.uptimeMillis()
        val dt = if (running) ((now - lastFrame).coerceIn(0, 50)) / 1000f else 0f
        lastFrame = now
        time += dt

        canvas.save()
        canvas.clipRect(picture)
        for (i in motes.indices) {
            var mote = motes[i]
            mote.y -= mote.rise * picture.height() * dt
            if (mote.y < picture.top - mote.size) {
                mote = newMote(picture.bottom + mote.size)
                motes[i] = mote
            }
            val x = mote.x + sin(2f * PI.toFloat() * mote.swayHz * time + mote.phase) * mote.sway
            val twinkle = 0.55f + 0.45f * sin(2f * PI.toFloat() * mote.twinkleHz * time + mote.phase * 1.7f)
            // Fade in from the bottom and out toward the top.
            val height = ((picture.bottom - mote.y) / picture.height()).coerceIn(0f, 1f)
            val edges = (height / 0.15f).coerceAtMost(1f) * ((1f - height) / 0.2f).coerceAtMost(1f)
            paint.alpha = (255 * mote.brightness * twinkle * edges).toInt().coerceIn(0, 255)
            dest.set(x - mote.size, mote.y - mote.size, x + mote.size, mote.y + mote.size)
            canvas.drawBitmap(dot, null, dest, paint)
        }
        canvas.restore()

        if (running) postInvalidateOnAnimation()
    }

    private companion object {
        const val MOTES = 28

        /** A soft, warm glow fading out from its middle. */
        fun softDot(): Bitmap {
            val size = 64
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    size / 2f, size / 2f, size / 2f,
                    intArrayOf(Color.argb(255, 255, 248, 220), Color.argb(150, 255, 214, 140), Color.argb(0, 255, 190, 110)),
                    floatArrayOf(0f, 0.25f, 1f),
                    Shader.TileMode.CLAMP,
                )
            }
            Canvas(bitmap).drawCircle(size / 2f, size / 2f, size / 2f, glow)
            return bitmap
        }
    }
}
