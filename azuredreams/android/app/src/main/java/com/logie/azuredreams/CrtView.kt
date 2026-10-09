// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.azuredreams

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.animation.doOnEnd

/**
 * Covers the game in black until it's ready, then switches it on like an old CRT TV: a bright line
 * across the middle that opens up into the picture.
 */
class CrtView(context: Context) : View(context) {
    private val black = Paint().apply { color = Color.BLACK }
    private val glow = Paint().apply { color = Color.WHITE }
    /** 0: all black, 1: the picture is fully open. */
    private var progress = 0f

    init {
        // Covers the game but lets touches through to the gesture layer under it.
        isClickable = false
        isFocusable = false
    }

    fun switchOn() {
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 520
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            doOnEnd { visibility = GONE }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val middle = h / 2
        // First the line grows from the middle to the full width, then the screen opens up.
        val line = (progress / 0.3f).coerceIn(0f, 1f)
        val open = ((progress - 0.3f) / 0.7f).coerceIn(0f, 1f)
        val halfOpen = middle * open
        val lineHeight = 3 * resources.displayMetrics.density

        canvas.drawRect(0f, 0f, w, middle - halfOpen, black)
        canvas.drawRect(0f, middle + halfOpen, w, h, black)
        if (open == 0f) {
            canvas.drawRect(0f, middle - lineHeight, w, middle + lineHeight, black)
            val half = w / 2 * line
            glow.alpha = 255
            canvas.drawRect(w / 2 - half, middle - lineHeight / 2, w / 2 + half, middle + lineHeight / 2, glow)
        } else {
            // A white flash over the opening picture, fading as it opens.
            glow.alpha = (200 * (1f - open)).toInt()
            canvas.drawRect(0f, middle - halfOpen, w, middle + halfOpen, glow)
        }
    }
}
