// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.logie.warcraft.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Northrend's palette, from the header and the logo: the night over Icecrown in deep blue-black,
 * the cold cyan of the Lich King's light, frost white, the steel of the logo's frame and its gold.
 */
object WowColors {
    val Abyss = Color(0xFF02070D)
    val Night = Color(0xFF061320)
    val Deep = Color(0xFF0B2233)
    val Frost = Color(0xFF123A52)
    val Steel = Color(0xFF2E5670)

    /** The Lich King's light: what's selected, and what matters. */
    val Ice = Color(0xFF7FDDF8)
    val Glow = Color(0xFFDDF7FF)

    /** The logo's gold: frame corners and the main buttons. */
    val Gold = Color(0xFFE8B34A)
    val GoldDeep = Color(0xFF7A4E12)

    val Text = Color(0xFFE6F1F7)
    val Mist = Color(0xFF8FA9BC)
    val Blood = Color(0xFFE0574A)
    val Good = Color(0xFF86E3B4)
}

/** Cinzel, carved Roman capitals like the game's own titles, at a firm weight. */
@OptIn(ExperimentalTextApi::class)
val WowDisplayFont = FontFamily(
    Font(R.font.wow_cinzel, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.wow_cinzel, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(650))),
    Font(R.font.wow_cinzel, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(800))),
)

/** Jost for reading: clean, and narrow enough for the folded phone's screen. */
val WowBodyFont = FontFamily(
    Font(R.font.wow_jost_light, FontWeight.Light),
    Font(R.font.wow_jost_regular, FontWeight.Normal),
    Font(R.font.wow_jost_medium, FontWeight.Medium),
)

/** Titles and menu lines, in Cinzel's capitals. */
val WowDisplayStyle = TextStyle(
    fontFamily = WowDisplayFont,
    fontWeight = FontWeight.SemiBold,
    color = WowColors.Text,
    letterSpacing = 0.08.em,
)

/** The frost glow behind lit text. */
val WowGlow = Shadow(WowColors.Ice.copy(alpha = 0.8f), Offset.Zero, 16f)

/** Gold text's dark edge, so it reads over the picture. */
val WowGoldShadow = Shadow(Color.Black.copy(alpha = 0.85f), Offset(0f, 2f), 6f)

private val Body = TextStyle(fontFamily = WowBodyFont, color = WowColors.Text)

private val WowTypography = Typography(
    headlineLarge = WowDisplayStyle.copy(fontSize = 26.sp, lineHeight = 34.sp),
    headlineMedium = WowDisplayStyle.copy(fontSize = 21.sp, lineHeight = 28.sp),
    headlineSmall = WowDisplayStyle.copy(fontSize = 17.sp, lineHeight = 24.sp, color = WowColors.Gold),
    titleLarge = WowDisplayStyle.copy(fontSize = 14.sp, lineHeight = 20.sp, color = WowColors.Mist),
    titleMedium = Body.copy(fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium),
    titleSmall = Body.copy(fontSize = 15.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium),
    bodyLarge = Body.copy(fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = Body.copy(fontSize = 15.sp, lineHeight = 21.sp),
    bodySmall = Body.copy(fontSize = 13.sp, lineHeight = 18.sp, color = WowColors.Mist),
    labelLarge = Body.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
    labelMedium = Body.copy(fontSize = 13.sp),
    labelSmall = Body.copy(fontSize = 11.sp),
)

private val WowScheme = darkColorScheme(
    primary = WowColors.Ice,
    onPrimary = WowColors.Abyss,
    primaryContainer = WowColors.Frost,
    onPrimaryContainer = WowColors.Glow,
    secondary = WowColors.Gold,
    onSecondary = WowColors.Abyss,
    tertiary = WowColors.Glow,
    background = WowColors.Night,
    onBackground = WowColors.Text,
    surface = WowColors.Deep,
    onSurface = WowColors.Text,
    surfaceVariant = WowColors.Frost,
    onSurfaceVariant = WowColors.Mist,
    surfaceContainerHigh = WowColors.Deep,
    surfaceContainerHighest = WowColors.Frost,
    outline = WowColors.Steel,
    error = WowColors.Blood,
)

@Composable
fun WowTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = WowScheme, typography = WowTypography, content = content)
}

/**
 * A frame like the game's own windows: a steel hairline, and at each corner a short gold bracket
 * with a small diamond, like the rivets on the logo's frame.
 */
fun DrawScope.drawFrame(
    corner: Color = WowColors.Gold,
    alpha: Float = 1f,
    arm: Float = 10.dp.toPx(),
    stroke: Float = 1.3.dp.toPx(),
    line: Color? = WowColors.Steel,
) {
    val w = size.width
    val h = size.height
    if (line != null) drawRect(line, style = Stroke(1.dp.toPx()), alpha = 0.75f * alpha)
    val corners = listOf(Offset(0f, 0f) to Offset(1f, 1f), Offset(w, 0f) to Offset(-1f, 1f), Offset(0f, h) to Offset(1f, -1f), Offset(w, h) to Offset(-1f, -1f))
    val d = 3.dp.toPx()
    for ((point, dir) in corners) {
        drawLine(corner, point, Offset(point.x + dir.x * arm, point.y), stroke, alpha = alpha)
        drawLine(corner, point, Offset(point.x, point.y + dir.y * arm), stroke, alpha = alpha)
        val c = Offset(point.x + dir.x * d * 1.6f, point.y + dir.y * d * 1.6f)
        val diamond = Path().apply {
            moveTo(c.x, c.y - d)
            lineTo(c.x + d, c.y)
            lineTo(c.x, c.y + d)
            lineTo(c.x - d, c.y)
            close()
        }
        drawPath(diamond, corner, alpha = alpha)
    }
}

/**
 * A panel of dark ice: deep blue, see-through, with the game's window frame. Pressable panels
 * light up with frost while pressed.
 */
@Composable
fun WowWindow(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    padding: PaddingValues = PaddingValues(16.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val lit by animateFloatAsState(if (pressed) 1f else 0f, spring(dampingRatio = 0.8f, stiffness = 900f), label = "lit")
    var clickable: Modifier = Modifier
    if (onClick != null) {
        clickable = Modifier.clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
    }
    Box(
        modifier
            .graphicsLayer { alpha = if (enabled) 1f else 0.42f }
            .drawBehind {
                drawRect(Brush.verticalGradient(listOf(WowColors.Deep.copy(alpha = 0.84f), WowColors.Night.copy(alpha = 0.92f))))
                if (lit > 0f) {
                    drawRect(Brush.radialGradient(listOf(WowColors.Ice.copy(alpha = 0.22f * lit), Color.Transparent)))
                }
                drawFrame(alpha = 0.7f + 0.3f * lit)
            }
            .then(clickable)
            .padding(padding),
        content = content,
    )
}

/** Waiting: a ring of frost runes turning, a bright arc running round it. */
@Composable
fun WowSpinner(size: Dp, modifier: Modifier = Modifier) {
    val motion = rememberInfiniteTransition(label = "spinner")
    val turn by motion.animateFloat(0f, 360f, infiniteRepeatable(tween(1_600, easing = LinearEasing)), label = "turn")
    val slow by motion.animateFloat(360f, 0f, infiniteRepeatable(tween(9_000, easing = LinearEasing)), label = "slow")
    Canvas(modifier.size(size)) {
        val r = this.size.minDimension / 2 * 0.86f
        drawCircle(WowColors.Steel, r, style = Stroke(1.2.dp.toPx()))
        rotate(slow) {
            for (i in 0 until 12) {
                val a = i * PI / 6
                val inner = r * 0.72f
                val outer = r * 0.84f
                drawLine(
                    WowColors.Gold.copy(alpha = 0.7f),
                    Offset(center.x + (inner * cos(a)).toFloat(), center.y + (inner * sin(a)).toFloat()),
                    Offset(center.x + (outer * cos(a)).toFloat(), center.y + (outer * sin(a)).toFloat()),
                    1.2.dp.toPx(),
                )
            }
        }
        drawArc(
            Brush.sweepGradient(listOf(Color.Transparent, WowColors.Ice, WowColors.Glow)),
            startAngle = turn,
            sweepAngle = 100f,
            useCenter = false,
            topLeft = Offset(center.x - r, center.y - r),
            size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
            style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}

/** Snow drifting down over the picture, as in the header's storm. */
@Composable
fun WowSnow(modifier: Modifier = Modifier, flakes: Int = 70, alpha: Float = 1f) {
    val motion = rememberInfiniteTransition(label = "snow")
    val time by motion.animateFloat(0f, 1f, infiniteRepeatable(tween(20_000, easing = LinearEasing)), label = "time")
    val seeds = remember(flakes) {
        val random = java.util.Random(7)
        List(flakes) { floatArrayOf(random.nextFloat(), random.nextFloat(), 0.4f + random.nextFloat() * 0.6f, random.nextFloat()) }
    }
    Canvas(modifier.fillMaxSize()) {
        for (seed in seeds) {
            val (x0, y0, depth, phase) = seed.toList()
            // Nearer flakes are bigger and fall faster; all of them sway a little.
            val fall = (y0 + time * (1f + 2f * depth)) % 1f
            val sway = sin((time * 6f + phase) * 2 * PI).toFloat() * 0.012f
            val x = ((x0 + sway - time * 0.15f * depth) % 1f + 1f) % 1f
            drawCircle(
                WowColors.Glow.copy(alpha = alpha * (0.25f + 0.5f * depth)),
                radius = (0.8f + 1.8f * depth).dp.toPx(),
                center = Offset(x * size.width, fall * size.height),
            )
        }
    }
}

/** A full-screen dark backdrop under everything, with a faint frost light from above. */
@Composable
fun WowBackdrop() {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(WowColors.Abyss)
        drawRect(
            Brush.radialGradient(
                listOf(WowColors.Frost.copy(alpha = 0.35f), Color.Transparent),
                center = Offset(size.width * 0.5f, 0f),
                radius = size.maxDimension * 0.9f,
            )
        )
    }
}
