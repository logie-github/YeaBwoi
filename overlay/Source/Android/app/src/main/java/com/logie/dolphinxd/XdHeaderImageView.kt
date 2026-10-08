// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.dolphinxd

import android.content.Context
import android.graphics.Matrix
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView
import org.dolphinemu.dolphinemu.R

/**
 * The main menu banner. Like centerCrop, but keeps a chosen horizontal point of the image
 * (R.integer.xd_header_focus_percent) in view, since the artwork's characters often sit at one
 * side of a wide banner and a centered crop on a phone would cut them off.
 */
class XdHeaderImageView(context: Context, attrs: AttributeSet?) : AppCompatImageView(context, attrs) {
    private val focusX = resources.getInteger(R.integer.xd_header_focus_percent) / 100f

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun setFrame(l: Int, t: Int, r: Int, b: Int): Boolean {
        val changed = super.setFrame(l, t, r, b)
        updateCrop()
        return changed
    }

    private fun updateCrop() {
        val image = drawable ?: return
        val viewWidth = (width - paddingLeft - paddingRight).toFloat()
        val viewHeight = (height - paddingTop - paddingBottom).toFloat()
        val imageWidth = image.intrinsicWidth.toFloat()
        val imageHeight = image.intrinsicHeight.toFloat()
        if (viewWidth <= 0 || viewHeight <= 0 || imageWidth <= 0 || imageHeight <= 0) return

        val scale = maxOf(viewWidth / imageWidth, viewHeight / imageHeight)
        val scaledWidth = imageWidth * scale
        val dx = (viewWidth / 2 - focusX * scaledWidth).coerceIn(viewWidth - scaledWidth, 0f)
        val dy = (viewHeight - imageHeight * scale) / 2
        imageMatrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(dx, dy)
        }
    }
}
