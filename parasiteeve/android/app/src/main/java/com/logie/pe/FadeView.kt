// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.pe

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.animation.doOnEnd

/** Covers the game in black until it's ready, then fades it in, as the title screen faded out. */
class FadeView(context: Context) : View(context) {
    /** How much of the black is left: 1 covers the game. */
    private var cover = 1f

    init {
        // Covers the game but lets touches through to the gesture layer under it.
        isClickable = false
        isFocusable = false
    }

    fun fadeIn() {
        ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 700
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                cover = it.animatedValue as Float
                invalidate()
            }
            doOnEnd { visibility = GONE }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.argb((255 * cover).toInt(), 0, 0, 0))
    }
}
