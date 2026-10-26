// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.turtle.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
 * OctoWoW's palette, from octowow.st: its near-black page, the gold-to-ember lettering of its
 * headings (Mysteries of Azeroth), the ember glow and orange capitals of its Download button and the
 * green of its Sign up one, its steel frames, and the violet of its arcane art and its launcher.
 */
object TurtleColors {
    val Night = Color(0xFF110D0B)
    val Bark = Color(0xFF17120F)
    val Earth = Color(0xFF231B16)
    val Brown = Color(0xFF5E3A1C)

    /** The headings' gradient, top to bottom: gold to ember. */
    val Gold = Color(0xFFF7C948)
    val Coral = Color(0xFFEE8436)
    /** The Download button's capitals. */
    val Orange = Color(0xFFF39A45)

    val Parchment = Color(0xFFE6E0D4)
    val Stone = Color(0xFFA8A196)
    val Dust = Color(0xFF5F5850)
    val Leaf = Color(0xFF8DD958)
    /** The Sign up button's capitals and glow. */
    val Moss = Color(0xFFA6CF62)
    val Blood = Color(0xFFE0574A)

    /** The frames' steel: its lit edge, its body, its shadow. */
    val SteelLight = Color(0xFFD3D1CB)
    val Steel = Color(0xFF8B8984)
    val SteelDark = Color(0xFF2B2927)

    /** The arcane violet of OctoWoW's art and launcher. */
    val Violet = Color(0xFFA98BE0)
    val Dusk = Color(0xFF271F30)

    val GoldCoral = Brush.verticalGradient(listOf(Gold, Coral))
}

/**
 * Almendra for headings, in title case: the closest open font to the site's Folkard (SIL OFL,
 * warcraft/fonts); Alegreya Sans SC for buttons and labels, in capitals, for its Fontin Sans SC;
 * Jost for reading, as in the rest of the app.
 */
val OctoHeadingFont = FontFamily(Font(R.font.octowow_almendra_bold, FontWeight.Bold))

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

/** The site's headings: Almendra, gold to ember. */
val OctoHeadingStyle = TextStyle(
    fontFamily = OctoHeadingFont,
    fontWeight = FontWeight.Bold,
    brush = TurtleColors.GoldCoral,
    letterSpacing = 0.01.em,
)

/** The site's gradient lettering (gold to ember), for titles and main buttons. */
val TurtleGradientText = TurtleDisplayStyle.copy(brush = TurtleColors.GoldCoral)

val TurtleShadow = Shadow(Color.Black.copy(alpha = 0.9f), Offset(0f, 2f), 6f)

private val Body = TextStyle(fontFamily = WowBodyFont, color = TurtleColors.Parchment)

private val TurtleTypography = Typography(
    headlineLarge = OctoHeadingStyle.copy(fontSize = 30.sp, lineHeight = 36.sp),
    headlineMedium = OctoHeadingStyle.copy(fontSize = 24.sp, lineHeight = 30.sp),
    headlineSmall = OctoHeadingStyle.copy(fontSize = 21.sp, lineHeight = 27.sp),
    titleLarge = OctoHeadingStyle.copy(fontSize = 18.sp, lineHeight = 23.sp),
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
    outline = TurtleColors.Steel,
    error = TurtleColors.Blood,
)

@Composable
fun TurtleTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = TurtleScheme, typography = TurtleTypography, content = content)
}

/**
 * The site's frame: a steel bevel (lit along the top, shadowed below) between two dark lines, and at
 * each corner a heavier steel bracket with a square rivet; [lit] warms it with the ember glow.
 */
fun DrawScope.drawTurtleFrame(alpha: Float = 1f, lit: Float = 0f) {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return
    val px = 1.dp.toPx()
    drawRect(TurtleColors.SteelDark, style = Stroke(3 * px), alpha = alpha)
    val inset = 1.5f * px
    drawRect(
        Brush.verticalGradient(listOf(TurtleColors.SteelLight, TurtleColors.Steel, TurtleColors.SteelDark)),
        topLeft = Offset(inset, inset),
        size = Size(w - 2 * inset, h - 2 * inset),
        style = Stroke(1.2f * px),
        alpha = alpha * 0.85f,
    )
    if (lit > 0f) drawRect(TurtleColors.Coral.copy(alpha = 0.55f * lit), topLeft = Offset(inset, inset), size = Size(w - 2 * inset, h - 2 * inset), style = Stroke(1.6f * px))
    // The corner brackets, each with its rivet.
    val arm = minOf(14.dp.toPx(), w / 4, h / 4)
    val thick = 3.2f * px
    val steel = Brush.linearGradient(listOf(TurtleColors.SteelLight, TurtleColors.Steel, TurtleColors.SteelDark))
    for ((x, y) in listOf(0f to 0f, w to 0f, 0f to h, w to h)) {
        val dx = if (x == 0f) 1f else -1f
        val dy = if (y == 0f) 1f else -1f
        drawLine(steel, Offset(x, y + dy * thick / 2), Offset(x + dx * arm, y + dy * thick / 2), thick, alpha = alpha)
        drawLine(steel, Offset(x + dx * thick / 2, y), Offset(x + dx * thick / 2, y + dy * arm), thick, alpha = alpha)
        val r = 2.2f * px
        val c = Offset(x + dx * 6 * px, y + dy * 6 * px)
        drawRect(TurtleColors.SteelDark, topLeft = Offset(c.x - r - px / 2, c.y - r - px / 2), size = Size(2 * r + px, 2 * r + px), alpha = alpha)
        drawRect(Brush.linearGradient(listOf(TurtleColors.SteelLight, TurtleColors.Steel), start = Offset(c.x - r, c.y - r), end = Offset(c.x + r, c.y + r)), topLeft = Offset(c.x - r, c.y - r), size = Size(2 * r, 2 * r), alpha = alpha)
    }
}

/**
 * The band under the site's menu: a steel bar with a gold line along it, diamonds set in it, the
 * middle one large, and small ones hanging under it.
 */
@Composable
fun OctoBand(modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(22.dp)) {
        val w = size.width
        val px = 1.dp.toPx()
        val top = 4 * px
        val bar = 8 * px
        drawRect(TurtleColors.SteelDark, topLeft = Offset(0f, top - px), size = Size(w, bar + 2 * px))
        drawRect(Brush.verticalGradient(listOf(TurtleColors.SteelLight, TurtleColors.Steel, TurtleColors.SteelDark), startY = top, endY = top + bar), topLeft = Offset(0f, top), size = Size(w, bar))
        drawLine(TurtleColors.Coral, Offset(0f, top + bar + 1.5f * px), Offset(w, top + bar + 1.5f * px), 1.2f * px)
        fun diamond(cx: Float, cy: Float, r: Float, fill: Brush) {
            val path = Path().apply {
                moveTo(cx, cy - r)
                lineTo(cx + r, cy)
                lineTo(cx, cy + r)
                lineTo(cx - r, cy)
                close()
            }
            drawPath(path, TurtleColors.SteelDark)
            val inner = Path().apply {
                moveTo(cx, cy - r * 0.72f)
                lineTo(cx + r * 0.72f, cy)
                lineTo(cx, cy + r * 0.72f)
                lineTo(cx - r * 0.72f, cy)
                close()
            }
            drawPath(inner, fill)
        }
        val steel = Brush.verticalGradient(listOf(TurtleColors.SteelLight, TurtleColors.Steel))
        val step = 64 * px
        var x = (w / 2) % step
        while (x < w) {
            if (kotlin.math.abs(x - w / 2) > step / 2) diamond(x, top + bar + 4 * px, 4 * px, steel)
            x += step
        }
        diamond(w / 2, top + bar / 2, 9 * px, Brush.verticalGradient(listOf(TurtleColors.SteelLight, TurtleColors.Gold, TurtleColors.Coral)))
        diamond(w / 2 - 34 * px, top + bar / 2, 5 * px, steel)
        diamond(w / 2 + 34 * px, top + bar / 2, 5 * px, steel)
    }
}

/** Waiting: eight plates in a ring (an octopus's eight arms) turning, a gold-to-ember arc running round them. */
@Composable
fun TurtleSpinner(size: Dp, modifier: Modifier = Modifier) {
    val motion = rememberInfiniteTransition(label = "spinner")
    val turn by motion.animateFloat(0f, 360f, infiniteRepeatable(tween(1_500, easing = LinearEasing)), label = "turn")
    val slow by motion.animateFloat(0f, 360f, infiniteRepeatable(tween(12_000, easing = LinearEasing)), label = "slow")
    Canvas(modifier.size(size)) {
        val r = this.size.minDimension / 2 * 0.86f
        drawCircle(TurtleColors.Steel.copy(alpha = 0.6f), r, style = Stroke(1.2.dp.toPx()))
        rotate(slow) {
            for (i in 0 until 8) {
                val a = i * PI / 4
                val c = Offset(center.x + (r * 0.58f * cos(a)).toFloat(), center.y + (r * 0.58f * sin(a)).toFloat())
                drawCircle((if (i % 2 == 0) TurtleColors.Violet else TurtleColors.Orange).copy(alpha = 0.6f), r * 0.1f, c)
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

/** Motes drifting up, gold and arcane violet, like the glow of OctoWoW's art. */
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
                (if (phase > 0.55f) TurtleColors.Gold else if (phase > 0.25f) TurtleColors.Violet else TurtleColors.Orange).copy(alpha = alpha * glow * (0.2f + 0.45f * depth)),
                radius = (0.8f + 1.6f * depth).dp.toPx(),
                center = Offset(((x0 + sway) % 1f) * size.width, rise * size.height),
            )
        }
    }
}

/** The page under everything: the site's near-black, a violet haze from above, embers from below. */
@Composable
fun TurtleBackdrop() {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(TurtleColors.Night)
        drawRect(
            Brush.radialGradient(
                listOf(TurtleColors.Dusk.copy(alpha = 0.55f), Color.Transparent),
                center = Offset(size.width * 0.5f, 0f),
                radius = size.maxDimension * 0.8f,
            )
        )
        drawRect(
            Brush.radialGradient(
                listOf(TurtleColors.Brown.copy(alpha = 0.18f), Color.Transparent),
                center = Offset(size.width * 0.5f, size.height),
                radius = size.maxDimension * 0.7f,
            )
        )
    }
}
