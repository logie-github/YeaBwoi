// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.azuredreams.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin
import androidx.compose.ui.graphics.Canvas as GraphicsCanvas

/** One pass of the backdrop's animation. */
private const val LOOP_MS = 16_000

/**
 * The screen behind the menus, PlayStation style: a twilight sky over the desert with twinkling
 * pixel stars, a glowing grid floor rolling toward the viewer like a late-90s 3D intro, and the
 * scanlines of a CRT TV over everything.
 */
@Composable
fun AdBackdrop(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "backdrop")
    val time by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(LOOP_MS, easing = LinearEasing)),
        label = "time",
    )
    val scanlines = remember { scanlineBrush() }

    Canvas(modifier.fillMaxSize()) {
        drawRect(
            Brush.verticalGradient(
                0f to AdColors.Night,
                0.55f to Color(0xFF0D2F7A),
                0.62f to Color(0xFF1C4E9E),
                1f to AdColors.NightDeep,
            )
        )
        drawStars(time)
        drawGridFloor(time)
        drawRect(scanlines, alpha = 0.22f)
    }
}

private fun DrawScope.drawStars(time: Float) {
    val cell = 46.dp.toPx()
    val horizon = size.height * 0.6f
    val columns = (size.width / cell).toInt() + 1
    val rows = (horizon / cell).toInt() + 1
    val dot = 2.dp.toPx()
    for (row in 0 until rows) {
        for (column in 0 until columns) {
            val seed = hash(column, row)
            if (seed % 3 != 0) continue
            val x = column * cell + (seed ushr 3) % cell.toInt().coerceAtLeast(1)
            val y = row * cell + (seed ushr 9) % cell.toInt().coerceAtLeast(1)
            if (y > horizon) continue
            val cycles = 1 + (seed ushr 15) % 3
            val phase = ((seed ushr 5) % 1000) / 1000f
            val twinkle = (sin(2 * PI * (time * cycles + phase)).toFloat() + 1f) / 2f
            val size = if ((seed ushr 20) % 5 == 0) dot * 2 else dot
            val color = if ((seed ushr 22) % 4 == 0) AdColors.Kewne else AdColors.SkyLight
            drawRect(color, Offset(x, y), Size(size, size), alpha = 0.25f + 0.7f * twinkle * (1f - y / horizon * 0.6f))
        }
    }
}

/** A perspective grid from the horizon (60% down) to the bottom, its lines rolling forward. */
private fun DrawScope.drawGridFloor(time: Float) {
    val horizon = size.height * 0.6f
    val depth = size.height - horizon
    if (depth <= 0f) return
    val line = Color(0xFF3D8DFF)
    val stroke = 1.5.dp.toPx()

    // The glow along the horizon.
    drawRect(
        Brush.verticalGradient(listOf(Color.Transparent, AdColors.Azure.copy(alpha = 0.35f), Color.Transparent), horizon - 40.dp.toPx(), horizon + 24.dp.toPx()),
        topLeft = Offset(0f, horizon - 40.dp.toPx()),
        size = Size(size.width, 64.dp.toPx()),
    )

    // Lines across, closer together toward the horizon, rolling toward the viewer.
    val rows = 9
    val roll = (time * 6f) % 1f
    for (i in 0 until rows) {
        val z = (i + 1 - roll) / rows
        val y = horizon + depth * z * z
        drawLine(line, Offset(0f, y), Offset(size.width, y), stroke, alpha = 0.15f + 0.45f * z)
    }
    // Lines going away, meeting at the middle of the horizon.
    val centerX = size.width / 2
    val spread = size.width * 1.6f
    val columns = 14
    for (i in -columns..columns) {
        val bottomX = centerX + spread * i / columns
        drawLine(line, Offset(centerX + (bottomX - centerX) * 0.06f, horizon), Offset(bottomX, size.height), stroke, alpha = 0.32f)
    }
}

/** A stable pseudo-random number for a grid cell. */
private fun hash(x: Int, y: Int): Int {
    var h = x * 374761393 + y * 668265263
    h = (h xor (h ushr 13)) * 1274126177
    return (h xor (h ushr 16)) and 0x7fffffff
}

/** Every third line of pixels darkened, like a CRT TV. */
private fun scanlineBrush(): ShaderBrush {
    val bitmap = ImageBitmap(1, 3)
    val canvas = GraphicsCanvas(bitmap)
    val paint = Paint().apply { color = Color.Black }
    canvas.drawRect(0f, 2f, 1f, 3f, paint)
    return ShaderBrush(ImageShader(bitmap, TileMode.Repeated, TileMode.Repeated))
}

/** CRT scanlines alone, to lay over pictures. */
@Composable
fun AdScanlines(modifier: Modifier = Modifier, alpha: Float = 0.18f) {
    val brush = remember { scanlineBrush() }
    Canvas(modifier.fillMaxSize()) { drawRect(brush, alpha = alpha) }
}

/** A spinning CD-ROM with a rainbow sheen: the PlayStation's loading. */
@Composable
fun AdSpinningDisc(size: Dp, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "disc")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "angle",
    )
    Canvas(modifier.size(size)) {
        val radius = this.size.minDimension / 2
        rotate(angle) {
            drawCircle(Color(0xFF1A1D24), radius)
            drawCircle(
                Brush.sweepGradient(
                    listOf(
                        Color(0xFFDDE6F0), Color(0xFF8FD3FF), Color(0xFFE9B3FF), Color(0xFFFFF2A8),
                        Color(0xFFDDE6F0), Color(0xFF9FFFD2), Color(0xFFFFB8C8), Color(0xFFDDE6F0),
                    )
                ),
                radius * 0.94f,
            )
            drawCircle(Color.Black.copy(alpha = 0.25f), radius * 0.94f, style = Stroke(radius * 0.04f))
        }
        drawCircle(Color(0xFFB9C2CC), radius * 0.34f)
        drawCircle(Color(0xFF07122A), radius * 0.16f)
    }
}
