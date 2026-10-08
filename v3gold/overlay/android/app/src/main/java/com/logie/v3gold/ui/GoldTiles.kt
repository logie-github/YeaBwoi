// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.v3gold.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.Canvas as GraphicsCanvas
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.sin

/** One pass of the tile animation. Every tile repeats a whole number of times in it. */
private const val LOOP_MS = 12_000

/** A tile only shows during the top part of its wave, so most of the wall is calm at any moment. */
private const val SHOW_ABOVE = 0.35f

/**
 * The yellow wall of softly flickering squares from Persona 4 Golden's menus: a grid of tiles in
 * deeper yellow, pale yellow and near white fading in and out at their own pace, over a fine
 * halftone dot texture.
 */
@Composable
fun GoldTileBackground(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "tiles")
    val time by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(LOOP_MS, easing = LinearEasing)),
        label = "tileTime",
    )
    val dots = remember { halftoneBrush() }

    Canvas(modifier.fillMaxSize()) {
        drawRect(GoldColors.TileBase)
        drawTiles(time)
        drawRect(dots, alpha = 0.16f)
    }
}

private fun DrawScope.drawTiles(time: Float) {
    val cell = 40.dp.toPx()
    val gap = cell * 0.1f
    val columns = ceil(size.width / cell).toInt()
    val rows = ceil(size.height / cell).toInt()
    for (row in 0 until rows) {
        for (column in 0 until columns) {
            val seed = hash(column, row)
            val cycles = 1 + (seed % 3)
            val phase = ((seed ushr 4) % 1000) / 1000f
            val wave = sin(2 * PI * (time * cycles + phase)).toFloat()
            if (wave <= SHOW_ABOVE) continue
            val strength = (wave - SHOW_ABOVE) / (1 - SHOW_ABOVE)
            val color = when ((seed ushr 12) % 10) {
                in 0..5 -> GoldColors.TileDeep
                in 6..8 -> GoldColors.TilePale
                else -> GoldColors.TileWhite
            }
            drawRect(
                color = color,
                topLeft = Offset(column * cell + gap / 2, row * cell + gap / 2),
                size = Size(cell - gap, cell - gap),
                alpha = 0.85f * strength,
            )
        }
    }
}

/** A stable pseudo-random number for a tile. */
private fun hash(x: Int, y: Int): Int {
    var h = x * 374761393 + y * 668265263
    h = (h xor (h ushr 13)) * 1274126177
    return (h xor (h ushr 16)) and 0x7fffffff
}

/** A repeating pattern of tiny darker dots, like printed halftone. */
private fun halftoneBrush(): ShaderBrush {
    val size = 6
    val bitmap = ImageBitmap(size, size)
    val canvas = GraphicsCanvas(bitmap)
    val paint = Paint().apply { color = Color(0xFFC9B400) }
    canvas.drawCircle(Offset(size / 2f, size / 2f), 1.1f, paint)
    return ShaderBrush(ImageShader(bitmap, TileMode.Repeated, TileMode.Repeated))
}

/** The TV color bars the game cuts to between scenes, from left to right, with their widths. */
private val TvBars = listOf(
    GoldColors.Red to 0.5f,
    GoldColors.Paper to 0.6f,
    GoldColors.Lime to 1f,
    GoldColors.Green to 0.3f,
    GoldColors.Paper to 0.4f,
    GoldColors.TileDeep to 2f,
    GoldColors.Orange to 1.6f,
    GoldColors.Lime to 0.5f,
    GoldColors.Green to 0.4f,
    GoldColors.Cream to 0.6f,
    GoldColors.Cyan to 0.8f,
    GoldColors.Lime to 0.5f,
    GoldColors.Green to 0.3f,
    GoldColors.Paper to 0.5f,
)

/**
 * The TV color bars, opening from a line in the middle of the screen as [progress] goes from 0 to
 * 1. Used as the cut into the game.
 */
@Composable
fun GoldTvBars(progress: Float, modifier: Modifier = Modifier) {
    if (progress <= 0f) return
    Canvas(modifier.fillMaxSize()) {
        val total = TvBars.sumOf { it.second.toDouble() }.toFloat()
        val visibleWidth = size.width * progress
        val left = (size.width - visibleWidth) / 2
        var x = left
        for ((color, weight) in TvBars) {
            val width = visibleWidth * weight / total
            drawRect(color, topLeft = Offset(x, 0f), size = Size(width + 1f, size.height))
            x += width
        }
        // A bright scan line across the middle while the bars open, like a TV switching on.
        if (progress < 1f) {
            val lineHeight = 6.dp.toPx() * (1f - progress)
            drawRect(
                Color.White.copy(alpha = 1f - progress),
                topLeft = Offset(0f, size.height / 2 - lineHeight / 2),
                size = Size(size.width, lineHeight),
            )
        }
    }
}
