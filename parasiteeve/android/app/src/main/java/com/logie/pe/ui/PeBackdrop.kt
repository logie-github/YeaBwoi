// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.pe.ui

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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** One pass of the backdrop's animation; everything in it loops seamlessly. */
private const val LOOP_MS = 30_000

/** Where the city meets the river, as a share of the height. */
private const val HORIZON = 0.56f

/**
 * The screen behind the menus: Manhattan at night across the river, its windows lit and their
 * light trembling on the water, fog drifting along the shore, the twisted cables of a bridge in
 * the foreground, and Eve's glowing mitochondria rising through it all, under film grain.
 */
@Composable
fun PeBackdrop(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "backdrop")
    val time by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(LOOP_MS, easing = LinearEasing)),
        label = "time",
    )
    val grain = remember { grainBrush() }

    Canvas(modifier.fillMaxSize()) {
        val horizon = size.height * HORIZON
        drawRect(
            Brush.verticalGradient(
                0f to PeColors.Abyss,
                HORIZON * 0.75f to PeColors.Night,
                HORIZON to PeColors.Indigo,
                HORIZON + 0.02f to PeColors.Navy,
                1f to PeColors.Abyss,
            )
        )
        drawSkyline(horizon, time)
        drawWater(horizon, time)
        drawFog(horizon, time)
        drawCables()
        drawMitochondria(time)
        // Grain that shifts every frame, like film.
        val jump = (time * LOOP_MS / 41f).toInt()
        translate(-(hash(jump, 1) % 128).toFloat(), -(hash(jump, 2) % 128).toFloat()) {
            drawRect(grain, size = Size(size.width + 128f, size.height + 128f), alpha = 0.07f)
        }
        drawRect(
            Brush.radialGradient(
                listOf(Color.Transparent, PeColors.Abyss.copy(alpha = 0.75f)),
                center = center,
                radius = hypot(size.width, size.height) * 0.62f,
            )
        )
    }
}

/** The towers across the river: dark blocks against the sky, a few windows lit. */
private fun DrawScope.drawSkyline(horizon: Float, time: Float) {
    val unit = 1.dp.toPx()
    var x = -8 * unit
    var index = 0
    val windowSize = 2.2f * unit
    val windowStep = 5.5f * unit
    while (x < size.width) {
        val seed = hash(index, 11)
        val width = (16 + seed % 34) * unit
        val tall = (seed ushr 6) % 7 == 0
        val height = size.height * (0.05f + ((seed ushr 9) % 100) / 100f * (if (tall) 0.26f else 0.13f))
        val top = horizon - height
        drawRect(Color(0xFF060D1B), Offset(x, top), Size(width, height))
        drawRect(PeColors.Steel.copy(alpha = 0.35f), Offset(x, top), Size(width, unit))

        var row = 0
        var y = top + 4 * unit
        while (y < horizon - 3 * unit) {
            var column = 0
            var wx = x + 3 * unit
            while (wx < x + width - 3 * unit) {
                val w = hash(index * 131 + column, row)
                if (w % 5 == 0) {
                    val warm = (w ushr 4) % 3 != 0
                    val phase = ((w ushr 8) % 1000) / 1000f
                    val flicker = 0.55f + 0.45f * sin(2 * PI * (time * (1 + (w ushr 12) % 3) + phase)).toFloat()
                    drawRect(
                        if (warm) PeColors.Window else PeColors.Glow,
                        Offset(wx, y),
                        Size(windowSize, windowSize),
                        alpha = 0.18f + 0.32f * flicker,
                    )
                }
                wx += windowStep
                column++
            }
            y += windowStep
            row++
        }
        // A red light blinking on top of the tallest towers.
        if (tall) {
            val blink = if ((time * 20 + (seed % 7) / 7f) % 1f < 0.3f) 1f else 0.15f
            drawCircle(PeColors.Eve, 1.8f * unit, Offset(x + width / 2, top - 2 * unit), alpha = blink)
        }
        x += width + ((seed ushr 16) % 6) * unit
        index++
    }
}

/** The river: the city's light broken into trembling streaks. */
private fun DrawScope.drawWater(horizon: Float, time: Float) {
    val unit = 1.dp.toPx()
    val depth = size.height - horizon
    for (i in 0 until 170) {
        val seed = hash(i, 37)
        val z = ((seed % 1000) / 1000f).let { it * it }
        val y = horizon + 2 * unit + depth * z
        val x0 = ((seed ushr 10) % 1000) / 1000f * size.width
        val phase = ((seed ushr 3) % 1000) / 1000f
        val sway = sin(2 * PI * (time * 3 + phase)).toFloat() * (2 + 6 * z) * unit
        val length = (3 + (seed ushr 20) % 12) * unit * (0.6f + z)
        val shimmer = 0.5f + 0.5f * sin(2 * PI * (time * (4 + (seed ushr 7) % 4) + phase * 3)).toFloat()
        val color = if ((seed ushr 14) % 3 == 0) PeColors.Glow else PeColors.Window
        drawLine(
            color,
            Offset(x0 + sway, y),
            Offset(x0 + sway + length, y),
            strokeWidth = (0.8f + z) * unit,
            alpha = (0.07f + 0.32f * shimmer) * (1f - z * 0.75f),
        )
    }
}

/** Two banks of fog along the shore, drifting past each other. */
private fun DrawScope.drawFog(horizon: Float, time: Float) {
    val angle = 2 * PI * time
    for ((index, speed) in listOf(1, -1).withIndex()) {
        val cx = size.width * (0.5f + 0.45f * sin(angle * speed + index * 2.1).toFloat())
        val cy = horizon + (if (index == 0) -0.03f else 0.04f) * size.height
        drawOval(
            Brush.radialGradient(
                listOf(PeColors.Mist.copy(alpha = 0.10f), Color.Transparent),
                center = Offset(cx, cy),
                radius = size.width * 0.55f,
            ),
            topLeft = Offset(cx - size.width * 0.8f, cy - size.height * 0.10f),
            size = Size(size.width * 1.6f, size.height * 0.20f),
        )
    }
}

/**
 * A bridge's twisted steel cables crossing the foreground, dark against the night: a thick line
 * with the twist of its strands catching the light.
 */
private fun DrawScope.drawCables() {
    val unit = 1.dp.toPx()
    cable(Offset(size.width * 0.07f, size.height * 1.02f), Offset(size.width * 0.13f, -size.height * 0.02f), 5.5f * unit)
    cable(Offset(size.width * 0.58f, size.height * 1.02f), Offset(size.width * 1.04f, size.height * 0.48f), 6.5f * unit)
}

private fun DrawScope.cable(from: Offset, to: Offset, thickness: Float) {
    drawLine(Color(0xFF03070E), from, to, thickness, cap = StrokeCap.Round, alpha = 0.92f)
    val length = (to - from).getDistance()
    val direction = (to - from) / length
    val across = Offset(-direction.y, direction.x)
    val step = thickness * 1.15f
    var travelled = 0f
    while (travelled < length) {
        val center = from + direction * travelled
        // One strand's twist: a short stroke slanted across the cable.
        val half = across * (thickness * 0.42f)
        val lean = direction * (thickness * 0.35f)
        drawLine(PeColors.Steel, center - half - lean, center + half + lean, thickness * 0.22f, alpha = 0.55f)
        travelled += step
    }
}

/** Eve's mitochondria: glowing specks rising slowly, swaying as they go. */
private fun DrawScope.drawMitochondria(time: Float) {
    val unit = 1.dp.toPx()
    for (i in 0 until 28) {
        val seed = hash(i, 71)
        val laps = 1 + seed % 2
        val phase = ((seed ushr 4) % 1000) / 1000f
        val rise = (time * laps + phase) % 1f
        val y = size.height * (1.05f - 1.1f * rise)
        val baseX = ((seed ushr 9) % 1000) / 1000f * size.width
        val x = baseX + sin(2 * PI * (rise * 2 + phase)).toFloat() * 14 * unit
        val radius = (1.6f + (seed ushr 20) % 3) * unit
        // Fades in from below and out near the top.
        val fade = (sin(PI * rise).toFloat()).coerceAtLeast(0f)
        val pulse = 0.7f + 0.3f * cos(2 * PI * (time * 6 + phase)).toFloat()
        drawCircle(
            Brush.radialGradient(
                listOf(PeColors.Teal.copy(alpha = 0.55f * fade * pulse), Color.Transparent),
                center = Offset(x, y),
                radius = radius * 4,
            ),
            radius = radius * 4,
            center = Offset(x, y),
        )
        drawCircle(PeColors.Glow, radius * 0.45f, Offset(x, y), alpha = 0.8f * fade)
    }
}

/** A stable pseudo-random number. */
private fun hash(x: Int, y: Int): Int {
    var h = x * 374761393 + y * 668265263
    h = (h xor (h ushr 13)) * 1274126177
    return (h xor (h ushr 16)) and 0x7fffffff
}

/** Film grain: a tile of random light and dark specks. */
private fun grainBrush(): ShaderBrush {
    val side = 128
    val pixels = IntArray(side * side) { index ->
        val value = hash(index, 5) % 256
        val alpha = 40 + hash(index, 9) % 120
        (alpha shl 24) or (value shl 16) or (value shl 8) or value
    }
    val bitmap = android.graphics.Bitmap.createBitmap(pixels, side, side, android.graphics.Bitmap.Config.ARGB_8888)
    return ShaderBrush(ImageShader(bitmap.asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
}

/** Film grain alone, to lay over pictures. */
@Composable
fun PeGrain(modifier: Modifier = Modifier, alpha: Float = 0.06f) {
    val brush = remember { grainBrush() }
    Canvas(modifier.fillMaxSize()) { drawRect(brush, alpha = alpha) }
}
