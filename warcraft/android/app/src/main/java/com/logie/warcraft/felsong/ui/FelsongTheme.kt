// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.felsong.ui

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
import kotlin.math.sin

/**
 * Felsong's palette, from felsong.gg's own stylesheet (assets/css/felsong/landing.css): its dark
 * olive page (#1e2117), its fel green (#9ccb2d, the icons, borders and their glow) and lime
 * (#bae449, links and the lit words), its headings lettered from white to #c1e46f, its sage
 * reading text (#87a07f, #97a37c), and the loading veil's near-black (rgba(14, 10, 7)). From the
 * user's art: Illidan's fire behind the fel (ember).
 */
object FelsongColors {
    val Soot = Color(0xFF0E0A07)
    val Night = Color(0xFF14170F)
    val Olive = Color(0xFF1E2117)
    val Moss = Color(0xFF2A3020)

    val Fel = Color(0xFF9CCB2D)
    val Lime = Color(0xFFBAE449)
    val Pale = Color(0xFFC1E46F)

    val Sage = Color(0xFF87A07F)
    val Lichen = Color(0xFF97A37C)
    val Bone = Color(0xFFEDF2E1)
    val Ember = Color(0xFFE9892F)
    val Blood = Color(0xFFE0574A)

    /** The site's headings: white at the top, pale fel at the foot. */
    val WhiteFel = Brush.verticalGradient(listOf(Color.White, Pale))
}

/**
 * Marcellus for titles: an open (SIL OFL, warcraft/fonts) flared serif in the spirit of the
 * site's Friz Quadrata, which can't be shipped; Jost for reading, as in the rest of the app.
 */
val FelsongDisplayFont = FontFamily(Font(R.font.felsong_marcellus, FontWeight.Normal))

val FelsongDisplayStyle = TextStyle(
    fontFamily = FelsongDisplayFont,
    fontWeight = FontWeight.Normal,
    color = FelsongColors.Bone,
    letterSpacing = 0.05.em,
)

/** The site's lettering for titles and main buttons (white to pale fel). */
val FelsongGradientText = FelsongDisplayStyle.copy(brush = FelsongColors.WhiteFel)

/** The fel glow under lit words (the site's `text-shadow: 0 0 20px #bbe44956`). */
val FelsongGlow = Shadow(FelsongColors.Lime.copy(alpha = 0.34f), Offset.Zero, 20f)

val FelsongShadow = Shadow(Color.Black.copy(alpha = 0.9f), Offset(0f, 2f), 6f)

private val Body = TextStyle(fontFamily = WowBodyFont, color = FelsongColors.Bone)

private val FelsongTypography = Typography(
    headlineLarge = FelsongDisplayStyle.copy(fontSize = 28.sp, lineHeight = 34.sp),
    headlineMedium = FelsongDisplayStyle.copy(fontSize = 22.sp, lineHeight = 28.sp),
    headlineSmall = FelsongDisplayStyle.copy(fontSize = 19.sp, lineHeight = 25.sp, color = FelsongColors.Lime),
    titleLarge = FelsongDisplayStyle.copy(fontSize = 15.sp, lineHeight = 20.sp, color = FelsongColors.Lichen),
    titleMedium = Body.copy(fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium),
    titleSmall = Body.copy(fontSize = 15.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium),
    bodyLarge = Body.copy(fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = Body.copy(fontSize = 15.sp, lineHeight = 21.sp),
    bodySmall = Body.copy(fontSize = 13.sp, lineHeight = 18.sp, color = FelsongColors.Sage),
    labelLarge = Body.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
    labelMedium = Body.copy(fontSize = 13.sp),
    labelSmall = Body.copy(fontSize = 11.sp),
)

private val FelsongScheme = darkColorScheme(
    primary = FelsongColors.Lime,
    onPrimary = FelsongColors.Soot,
    primaryContainer = FelsongColors.Moss,
    onPrimaryContainer = FelsongColors.Bone,
    secondary = FelsongColors.Fel,
    onSecondary = FelsongColors.Soot,
    tertiary = FelsongColors.Ember,
    background = FelsongColors.Olive,
    onBackground = FelsongColors.Bone,
    surface = FelsongColors.Night,
    onSurface = FelsongColors.Bone,
    surfaceVariant = FelsongColors.Moss,
    onSurfaceVariant = FelsongColors.Lichen,
    surfaceContainerHigh = FelsongColors.Night,
    surfaceContainerHighest = FelsongColors.Moss,
    outline = FelsongColors.Fel.copy(alpha = 0.4f),
    error = FelsongColors.Blood,
)

@Composable
fun FelsongTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = FelsongScheme, typography = FelsongTypography, content = content)
}

/**
 * The site's frame: a hairline of faint fel (`border: 1px solid #9ccb2d25`) with its soft glow
 * (`box-shadow: 0 0 15px #9ccb2d1a`), brighter when [lit]; a short lime rule centred on the top
 * edge, fading out both ways, with the logo's diamond under its middle.
 */
fun DrawScope.drawFelsongFrame(alpha: Float = 1f, lit: Float = 0f) {
    val w = size.width
    val line = 1.dp.toPx()
    for (i in 1..3) {
        val spread = i * 2.dp.toPx()
        drawRect(
            FelsongColors.Fel.copy(alpha = (0.05f + 0.06f * lit) / i),
            topLeft = Offset(-spread, -spread),
            size = Size(size.width + spread * 2, size.height + spread * 2),
            style = Stroke(2.dp.toPx()),
            alpha = alpha,
        )
    }
    drawRect(FelsongColors.Fel.copy(alpha = 0.16f + 0.4f * lit), style = Stroke(line), alpha = alpha)
    drawLine(
        Brush.horizontalGradient(listOf(Color.Transparent, FelsongColors.Lime, Color.Transparent), startX = w * 0.15f, endX = w * 0.85f),
        Offset(w * 0.15f, line / 2), Offset(w * 0.85f, line / 2), 1.6.dp.toPx(), alpha = alpha,
    )
    drawPath(diamond(Offset(w / 2, line / 2), 3.dp.toPx()), FelsongColors.Pale, alpha = alpha)
}

private fun diamond(c: Offset, d: Float) = Path().apply {
    moveTo(c.x, c.y - d)
    lineTo(c.x + d * 0.62f, c.y)
    lineTo(c.x, c.y + d)
    lineTo(c.x - d * 0.62f, c.y)
    close()
}

/**
 * Waiting: the logo's diamond (the gem in its O) breathing in a faint ring, a fel arc running round
 * it fading from nothing to lime.
 */
@Composable
fun FelsongSpinner(size: Dp, modifier: Modifier = Modifier) {
    val motion = rememberInfiniteTransition(label = "spinner")
    val turn by motion.animateFloat(0f, 360f, infiniteRepeatable(tween(1_400, easing = LinearEasing)), label = "turn")
    val breath by motion.animateFloat(0f, 1f, infiniteRepeatable(tween(2_000, easing = LinearEasing)), label = "breath")
    Canvas(modifier.size(size)) {
        val r = this.size.minDimension / 2 * 0.86f
        drawCircle(FelsongColors.Fel.copy(alpha = 0.22f), r, style = Stroke(1.2.dp.toPx()))
        val pulse = (sin(breath * 2 * PI).toFloat() + 1f) / 2f
        drawCircle(FelsongColors.Lime.copy(alpha = 0.10f + 0.12f * pulse), r * 0.42f)
        drawPath(diamond(center, r * (0.30f + 0.04f * pulse)), FelsongColors.Pale.copy(alpha = 0.6f + 0.4f * pulse))
        rotate(turn) {
            drawArc(
                Brush.sweepGradient(listOf(Color.Transparent, FelsongColors.Fel, FelsongColors.Lime)),
                startAngle = 0f,
                sweepAngle = 120f,
                useCenter = false,
                topLeft = Offset(center.x - r, center.y - r),
                size = Size(r * 2, r * 2),
                style = Stroke(2.4.dp.toPx(), cap = StrokeCap.Round),
            )
        }
    }
}

/** Fel motes rising, each with a soft glow round it, like the embers in the art. */
@Composable
fun FelsongMotes(modifier: Modifier = Modifier, count: Int = 52, alpha: Float = 1f) {
    val motion = rememberInfiniteTransition(label = "motes")
    val time by motion.animateFloat(0f, 1f, infiniteRepeatable(tween(24_000, easing = LinearEasing)), label = "time")
    val seeds = remember(count) {
        val random = java.util.Random(7)
        List(count) { floatArrayOf(random.nextFloat(), random.nextFloat(), 0.3f + random.nextFloat() * 0.7f, random.nextFloat()) }
    }
    Canvas(modifier.fillMaxSize()) {
        for (seed in seeds) {
            val (x0, y0, depth, phase) = seed.toList()
            val rise = ((y0 - time * (0.7f + depth)) % 1f + 1f) % 1f
            val sway = sin((time * 4f + phase) * 2 * PI).toFloat() * 0.02f
            val glow = 0.35f + 0.65f * ((sin((time * 11f + phase * 7f) * 2 * PI).toFloat() + 1f) / 2f)
            val colour = if (phase > 0.55f) FelsongColors.Lime else FelsongColors.Fel
            val c = Offset((((x0 + sway) % 1f + 1f) % 1f) * size.width, rise * size.height)
            val r = (0.9f + 1.7f * depth).dp.toPx()
            drawCircle(
                Brush.radialGradient(listOf(colour.copy(alpha = alpha * glow * 0.22f * depth), Color.Transparent), center = c, radius = r * 5f),
                radius = r * 5f,
                center = c,
            )
            drawCircle(colour.copy(alpha = alpha * glow * (0.25f + 0.5f * depth)), radius = r, center = c)
        }
    }
}

/** The page under everything: the site's olive, fel light falling from above. */
@Composable
fun FelsongBackdrop() {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(FelsongColors.Olive)
        drawRect(
            Brush.radialGradient(
                listOf(FelsongColors.Fel.copy(alpha = 0.14f), Color.Transparent),
                center = Offset(size.width * 0.5f, 0f),
                radius = size.maxDimension * 0.9f,
            )
        )
    }
}
