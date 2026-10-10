// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.pe.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.logie.pe.R

/**
 * Parasite Eve's palette: Manhattan at night in deep navy and indigo, the cold green-white of a
 * bridge lamp, the teal of the logo, and Eve's red.
 */
object PeColors {
    val Abyss = Color(0xFF02060D)
    val Night = Color(0xFF050D1C)
    val Navy = Color(0xFF0A1730)
    val Indigo = Color(0xFF16234A)
    val Steel = Color(0xFF2C3E58)

    val Teal = Color(0xFF5CC8B4)
    val TealDeep = Color(0xFF1C6A61)
    /** The lamp light: what's selected, and what matters. */
    val Glow = Color(0xFFC9F7E9)
    val Window = Color(0xFFE9D27A)

    val Text = Color(0xFFE2ECF2)
    val Mist = Color(0xFF93A9BD)
    val Eve = Color(0xFFD0283F)
    val Good = Color(0xFF7FE3C4)
}

/** Jost, a geometric sans like the logo's lettering: light for titles, regular for reading. */
val PeFont = FontFamily(
    Font(R.font.pe_light, FontWeight.Light),
    Font(R.font.pe_regular, FontWeight.Normal),
    Font(R.font.pe_medium, FontWeight.Medium),
)

/**
 * Titles and menu lines: light, lowercase and spread out, like `[ p a r a s i t e   e v e ]`.
 * Callers lowercase the text.
 */
val PeDisplayStyle = TextStyle(
    fontFamily = PeFont,
    fontWeight = FontWeight.Light,
    color = PeColors.Text,
    letterSpacing = 0.28.em,
)

/** The lamp-light glow behind selected text. */
val PeGlow = Shadow(PeColors.Teal.copy(alpha = 0.85f), Offset.Zero, 18f)

private val Body = TextStyle(fontFamily = PeFont, color = PeColors.Text)

private val PeTypography = Typography(
    headlineLarge = PeDisplayStyle.copy(fontSize = 26.sp, lineHeight = 34.sp),
    headlineMedium = PeDisplayStyle.copy(fontSize = 21.sp, lineHeight = 28.sp),
    headlineSmall = PeDisplayStyle.copy(fontSize = 17.sp, lineHeight = 24.sp, color = PeColors.Teal),
    titleLarge = PeDisplayStyle.copy(fontSize = 14.sp, lineHeight = 20.sp, color = PeColors.Mist),
    titleMedium = Body.copy(fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium),
    titleSmall = Body.copy(fontSize = 15.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium),
    bodyLarge = Body.copy(fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = Body.copy(fontSize = 15.sp, lineHeight = 21.sp),
    bodySmall = Body.copy(fontSize = 13.sp, lineHeight = 18.sp, color = PeColors.Mist),
    labelLarge = Body.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
    labelMedium = Body.copy(fontSize = 13.sp),
    labelSmall = Body.copy(fontSize = 11.sp),
)

private val PeScheme = darkColorScheme(
    primary = PeColors.Teal,
    onPrimary = PeColors.Abyss,
    primaryContainer = PeColors.TealDeep,
    onPrimaryContainer = PeColors.Glow,
    secondary = PeColors.Glow,
    onSecondary = PeColors.Abyss,
    tertiary = PeColors.Eve,
    background = PeColors.Night,
    onBackground = PeColors.Text,
    surface = PeColors.Navy,
    onSurface = PeColors.Text,
    surfaceVariant = PeColors.Indigo,
    onSurfaceVariant = PeColors.Mist,
    surfaceContainerHigh = PeColors.Navy,
    surfaceContainerHighest = PeColors.Indigo,
    outline = PeColors.Steel,
    error = PeColors.Eve,
)

@Composable
fun PeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = PeScheme, typography = PeTypography, content = content)
}

/**
 * Square brackets at the left and right edges of the area, like the ones around the logo's
 * lettering: [color] at [alpha], [arm] long at top and bottom.
 */
fun DrawScope.drawBrackets(color: Color, alpha: Float = 1f, arm: Float = 9.dp.toPx(), stroke: Float = 1.2.dp.toPx(), inset: Float = 0f) {
    val left = inset
    val right = size.width - inset
    val top = inset
    val bottom = size.height - inset
    drawLine(color, Offset(left, top), Offset(left, bottom), stroke, alpha = alpha)
    drawLine(color, Offset(left, top), Offset(left + arm, top), stroke, alpha = alpha)
    drawLine(color, Offset(left, bottom), Offset(left + arm, bottom), stroke, alpha = alpha)
    drawLine(color, Offset(right, top), Offset(right, bottom), stroke, alpha = alpha)
    drawLine(color, Offset(right, top), Offset(right - arm, top), stroke, alpha = alpha)
    drawLine(color, Offset(right, bottom), Offset(right - arm, bottom), stroke, alpha = alpha)
}

/**
 * A panel of night glass: deep navy, see-through, edged with a hairline and the logo's brackets.
 * Pressable panels light up while pressed.
 */
@Composable
fun PeWindow(
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
                drawRect(
                    Brush.verticalGradient(
                        listOf(
                            PeColors.Navy.copy(alpha = 0.80f),
                            PeColors.Night.copy(alpha = 0.90f),
                        )
                    )
                )
                if (lit > 0f) {
                    drawRect(Brush.radialGradient(listOf(PeColors.Teal.copy(alpha = 0.22f * lit), Color.Transparent)))
                }
                // The hairline, then the brackets over it.
                drawRect(PeColors.Steel, style = Stroke(1.dp.toPx()), alpha = 0.7f)
                drawBrackets(PeColors.Teal, alpha = 0.55f + 0.45f * lit)
            }
            .then(clickable)
            .padding(padding),
        content = content,
    )
}

/**
 * Waiting: a thin ring turning on a tilt, like the oval through the logo's "e", with a glowing
 * point running round it.
 */
@Composable
fun PeSpinner(size: Dp, modifier: Modifier = Modifier) {
    val motion = rememberInfiniteTransition(label = "spinner")
    val turn by motion.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1_400, easing = LinearEasing)),
        label = "turn",
    )
    val sway by motion.animateFloat(
        initialValue = -12f,
        targetValue = 12f,
        animationSpec = infiniteRepeatable(tween(2_600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "sway",
    )
    Canvas(modifier.size(size)) {
        val width = this.size.width * 0.62f
        val height = this.size.height * 0.96f
        val topLeft = Offset((this.size.width - width) / 2, (this.size.height - height) / 2)
        val ringSize = androidx.compose.ui.geometry.Size(width, height)
        rotate(-28f + sway) {
            drawOval(PeColors.Steel, topLeft, ringSize, style = Stroke(1.5.dp.toPx()))
            drawArc(
                Brush.sweepGradient(listOf(Color.Transparent, PeColors.Teal, PeColors.Glow)),
                startAngle = turn,
                sweepAngle = 110f,
                useCenter = false,
                topLeft = topLeft,
                size = ringSize,
                style = Stroke(2.dp.toPx(), cap = StrokeCap.Round),
            )
        }
    }
}

/** Draws [content] with a soft teal glow of [alpha] behind it. */
fun Modifier.peGlowBehind(alpha: Float): Modifier = drawWithContent {
    if (alpha > 0f) {
        drawRect(
            Brush.radialGradient(
                listOf(PeColors.Teal.copy(alpha = 0.18f * alpha), Color.Transparent),
                center = center,
                radius = size.maxDimension * 0.6f,
            )
        )
    }
    drawContent()
}
