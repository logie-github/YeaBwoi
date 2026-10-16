// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.turtle.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.logie.warcraft.R
import com.logie.warcraft.ui.WowBodyFont
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Turtle WoW's palette, from turtlecraft.gg's own stylesheet: its warm near-black page (#100d0c),
 * the gold-to-coral gradient of its buttons and links (#f1c22d to #ff7757), its orange accent
 * (#f89d41), brown, stone grey and green; and from the user's art, the old map's parchment and the
 * logo's carved stone.
 */
object TurtleColors {
    val Night = Color(0xFF100D0C)
    val Bark = Color(0xFF1B1511)
    val Earth = Color(0xFF2A2019)
    val Brown = Color(0xFF764502)

    /** The site's gradient, top to bottom: what's lit, and the main buttons. */
    val Gold = Color(0xFFF1C22D)
    val Coral = Color(0xFFFF7757)
    val Orange = Color(0xFFF89D41)

    val Parchment = Color(0xFFEFE3C8)
    val Stone = Color(0xFF9E9783)
    val Dust = Color(0xFF6E665A)
    val Leaf = Color(0xFF8CBE4B)
    val Blood = Color(0xFFE0574A)

    val GoldCoral = Brush.verticalGradient(listOf(Gold, Coral))
}

/**
 * Alegreya Sans SC for titles, in capitals: the closest open font to the site's Fontin Sans
 * headings (SIL OFL, warcraft/fonts); Jost for reading, as in the rest of the app.
 */
val TurtleDisplayFont = FontFamily(
    Font(R.font.turtle_alegreya_sc_medium, FontWeight.Medium),
    Font(R.font.turtle_alegreya_sc_bold, FontWeight.Bold),
)

val TurtleDisplayStyle = TextStyle(
    fontFamily = TurtleDisplayFont,
    fontWeight = FontWeight.Bold,
    color = TurtleColors.Parchment,
    letterSpacing = 0.06.em,
)

/** The site's gradient lettering (gold to coral), for titles and main buttons. */
val TurtleGradientText = TurtleDisplayStyle.copy(brush = TurtleColors.GoldCoral)

val TurtleShadow = Shadow(Color.Black.copy(alpha = 0.9f), Offset(0f, 2f), 6f)

private val Body = TextStyle(fontFamily = WowBodyFont, color = TurtleColors.Parchment)

private val TurtleTypography = Typography(
    headlineLarge = TurtleDisplayStyle.copy(fontSize = 28.sp, lineHeight = 34.sp),
    headlineMedium = TurtleDisplayStyle.copy(fontSize = 22.sp, lineHeight = 28.sp),
    headlineSmall = TurtleDisplayStyle.copy(fontSize = 19.sp, lineHeight = 25.sp, color = TurtleColors.Gold),
    titleLarge = TurtleDisplayStyle.copy(fontSize = 15.sp, lineHeight = 20.sp, color = TurtleColors.Stone, fontWeight = FontWeight.Medium),
    titleMedium = Body.copy(fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium),
    titleSmall = Body.copy(fontSize = 15.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium),
    bodyLarge = Body.copy(fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = Body.copy(fontSize = 15.sp, lineHeight = 21.sp),
    bodySmall = Body.copy(fontSize = 13.sp, lineHeight = 18.sp, color = TurtleColors.Stone),
    labelLarge = Body.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
    labelMedium = Body.copy(fontSize = 13.sp),
    labelSmall = Body.copy(fontSize = 11.sp),
)

private val TurtleScheme = darkColorScheme(
    primary = TurtleColors.Gold,
    onPrimary = TurtleColors.Night,
    primaryContainer = TurtleColors.Earth,
    onPrimaryContainer = TurtleColors.Parchment,
    secondary = TurtleColors.Orange,
    onSecondary = TurtleColors.Night,
    tertiary = TurtleColors.Coral,
    background = TurtleColors.Night,
    onBackground = TurtleColors.Parchment,
    surface = TurtleColors.Bark,
    onSurface = TurtleColors.Parchment,
    surfaceVariant = TurtleColors.Earth,
    onSurfaceVariant = TurtleColors.Stone,
    surfaceContainerHigh = TurtleColors.Bark,
    surfaceContainerHighest = TurtleColors.Earth,
    outline = TurtleColors.Brown,
    error = TurtleColors.Blood,
)

@Composable
fun TurtleTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = TurtleScheme, typography = TurtleTypography, content = content)
}

/**
 * The site's frame: a hairline in its faint gold-to-coral gradient (`.gradient-border-wrapper`),
 * and a short bright rule centred on the top edge, fading out both ways (its `footerline`).
 */
fun DrawScope.drawTurtleFrame(alpha: Float = 1f, lit: Float = 0f) {
    val w = size.width
    val line = 1.dp.toPx()
    drawRect(
        Brush.verticalGradient(listOf(TurtleColors.Gold.copy(alpha = 0.32f + 0.4f * lit), TurtleColors.Coral.copy(alpha = 0.26f + 0.4f * lit))),
        style = Stroke(line),
        alpha = alpha,
    )
    drawLine(
        Brush.horizontalGradient(listOf(Color.Transparent, TurtleColors.Gold, Color.Transparent), startX = w * 0.15f, endX = w * 0.85f),
        Offset(w * 0.15f, line / 2), Offset(w * 0.85f, line / 2), 1.6.dp.toPx(), alpha = alpha,
    )
    // A small diamond under the rule's middle, like the site's dividers.
    val d = 3.dp.toPx()
    val c = Offset(w / 2, line / 2)
    val diamond = Path().apply {
        moveTo(c.x, c.y - d)
        lineTo(c.x + d, c.y)
        lineTo(c.x, c.y + d)
        lineTo(c.x - d, c.y)
        close()
    }
    drawPath(diamond, TurtleColors.Gold, alpha = alpha)
}

/** Waiting: a turtle shell's ring of plates turning, a bright gold-to-coral arc running round it. */
@Composable
fun TurtleSpinner(size: Dp, modifier: Modifier = Modifier) {
    val motion = rememberInfiniteTransition(label = "spinner")
    val turn by motion.animateFloat(0f, 360f, infiniteRepeatable(tween(1_500, easing = LinearEasing)), label = "turn")
    val slow by motion.animateFloat(0f, 360f, infiniteRepeatable(tween(12_000, easing = LinearEasing)), label = "slow")
    Canvas(modifier.size(size)) {
        val r = this.size.minDimension / 2 * 0.86f
        drawCircle(TurtleColors.Brown, r, style = Stroke(1.2.dp.toPx()))
        rotate(slow) {
            for (i in 0 until 6) {
                val a = i * PI / 3
                val c = Offset(center.x + (r * 0.55f * cos(a)).toFloat(), center.y + (r * 0.55f * sin(a)).toFloat())
                drawCircle(TurtleColors.Orange.copy(alpha = 0.55f), r * 0.12f, c)
            }
        }
        drawArc(
            Brush.sweepGradient(listOf(Color.Transparent, TurtleColors.Gold, TurtleColors.Coral)),
            startAngle = turn,
            sweepAngle = 110f,
            useCenter = false,
            topLeft = Offset(center.x - r, center.y - r),
            size = Size(r * 2, r * 2),
            style = Stroke(2.4.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}

/** Warm motes drifting up over the old map, like dust in lamplight. */
@Composable
fun TurtleMotes(modifier: Modifier = Modifier, count: Int = 46, alpha: Float = 1f) {
    val motion = rememberInfiniteTransition(label = "motes")
    val time by motion.animateFloat(0f, 1f, infiniteRepeatable(tween(26_000, easing = LinearEasing)), label = "time")
    val seeds = remember(count) {
        val random = java.util.Random(11)
        List(count) { floatArrayOf(random.nextFloat(), random.nextFloat(), 0.3f + random.nextFloat() * 0.7f, random.nextFloat()) }
    }
    Canvas(modifier.fillMaxSize()) {
        for (seed in seeds) {
            val (x0, y0, depth, phase) = seed.toList()
            val rise = ((y0 - time * (0.6f + depth)) % 1f + 1f) % 1f
            val sway = sin((time * 5f + phase) * 2 * PI).toFloat() * 0.015f
            val glow = 0.35f + 0.65f * ((sin((time * 9f + phase * 7f) * 2 * PI).toFloat() + 1f) / 2f)
            drawCircle(
                (if (phase > 0.5f) TurtleColors.Gold else TurtleColors.Orange).copy(alpha = alpha * glow * (0.2f + 0.45f * depth)),
                radius = (0.8f + 1.6f * depth).dp.toPx(),
                center = Offset(((x0 + sway) % 1f) * size.width, rise * size.height),
            )
        }
    }
}

/** The page under everything: the site's near-black, warmed from above. */
@Composable
fun TurtleBackdrop() {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(TurtleColors.Night)
        drawRect(
            Brush.radialGradient(
                listOf(TurtleColors.Brown.copy(alpha = 0.28f), Color.Transparent),
                center = Offset(size.width * 0.5f, 0f),
                radius = size.maxDimension * 0.9f,
            )
        )
    }
}
