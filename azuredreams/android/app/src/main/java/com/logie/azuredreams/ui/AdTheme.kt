// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.azuredreams.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.logie.azuredreams.R
import androidx.compose.ui.graphics.Shadow

/**
 * Azure Dreams' palette: the sky over the Monster Tower, the desert sand, the parchment of the logo
 * banner, Koh's red hair and Kewne's yellow, with the deep blue menu windows of PlayStation RPGs.
 */
object AdColors {
    val SkyTop = Color(0xFF0B57C4)
    val Sky = Color(0xFF1E7FE0)
    val SkyLight = Color(0xFF7CC4F7)
    val Night = Color(0xFF061536)
    val NightDeep = Color(0xFF020814)
    val Azure = Color(0xFF3DA9F0)

    val WindowTop = Color(0xFF2F57B8)
    val WindowBottom = Color(0xFF0B1B5E)
    val WindowEdge = Color(0xFFE8EEFB)
    val WindowShade = Color(0xFF7A8CC6)

    val Parchment = Color(0xFFF1D9A0)
    val ParchmentDark = Color(0xFFC99A55)
    val Burnt = Color(0xFF7A4A1E)
    val Sand = Color(0xFFD9A75E)
    val Ember = Color(0xFFF0541E)
    val Kewne = Color(0xFFF6C531)

    val Text = Color(0xFFF4F6FF)
    val TextDim = Color(0xFFB4C4EC)
    val Gold = Color(0xFFFFD35A)
    val Good = Color(0xFF7CE38B)
    val Bad = Color(0xFFFF7A6B)
}

/** Press Start 2P: the blocky arcade letters, for titles and buttons. */
val AdDisplayFont = FontFamily(Font(R.font.ad_display))

/** Pixelify Sans: pixel letters that stay readable in sentences. */
val AdBodyFont = FontFamily(Font(R.font.ad_body))

/** Titles and buttons, with the hard drop shadow of PlayStation-era menus. */
val AdDisplayStyle = TextStyle(
    fontFamily = AdDisplayFont,
    color = AdColors.Text,
    letterSpacing = 1.sp,
    shadow = Shadow(Color(0xCC000000), Offset(3f, 3f), 0f),
)

// Pixelify Sans' "fi" ligature looks like an A ("finger" reads "Anger"), so no ligatures.
private val Body = TextStyle(fontFamily = AdBodyFont, color = AdColors.Text, fontFeatureSettings = "liga 0, clig 0")

private val AdTypography = Typography(
    headlineLarge = AdDisplayStyle.copy(fontSize = 22.sp, lineHeight = 30.sp),
    headlineMedium = AdDisplayStyle.copy(fontSize = 18.sp, lineHeight = 26.sp),
    headlineSmall = AdDisplayStyle.copy(fontSize = 15.sp, lineHeight = 22.sp),
    titleLarge = AdDisplayStyle.copy(fontSize = 14.sp, lineHeight = 20.sp),
    titleMedium = Body.copy(fontSize = 19.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold),
    titleSmall = Body.copy(fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold),
    bodyLarge = Body.copy(fontSize = 18.sp, lineHeight = 24.sp),
    bodyMedium = Body.copy(fontSize = 16.sp, lineHeight = 21.sp),
    bodySmall = Body.copy(fontSize = 14.sp, lineHeight = 18.sp, color = AdColors.TextDim),
    labelLarge = Body.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold),
    labelMedium = Body.copy(fontSize = 14.sp),
    labelSmall = Body.copy(fontSize = 12.sp),
)

private val AdScheme = darkColorScheme(
    primary = AdColors.Gold,
    onPrimary = AdColors.WindowBottom,
    primaryContainer = AdColors.WindowTop,
    onPrimaryContainer = AdColors.Text,
    secondary = AdColors.Azure,
    onSecondary = AdColors.NightDeep,
    tertiary = AdColors.Ember,
    background = AdColors.Night,
    onBackground = AdColors.Text,
    surface = AdColors.WindowBottom,
    onSurface = AdColors.Text,
    surfaceVariant = AdColors.WindowTop,
    onSurfaceVariant = AdColors.TextDim,
    surfaceContainerHigh = AdColors.WindowBottom,
    surfaceContainerHighest = AdColors.WindowTop,
    outline = AdColors.WindowEdge,
    error = AdColors.Bad,
)

@Composable
fun AdTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AdScheme, typography = AdTypography, content = content)
}

private val WindowShape = RoundedCornerShape(7.dp)

/**
 * A PlayStation RPG menu window: a deep blue gradient inside a light bevelled frame. Pressable
 * windows sink into their shadow while pressed.
 */
@Composable
fun AdWindow(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    padding: PaddingValues = PaddingValues(14.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val sink by animateFloatAsState(if (pressed) 1f else 0f, spring(dampingRatio = 0.6f), label = "sink")
    var clickable: Modifier = Modifier
    if (onClick != null) {
        clickable = Modifier.clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
    }
    Box(
        modifier
            .padding(end = 4.dp, bottom = 4.dp)
            .graphicsLayer {
                translationX = 3.dp.toPx() * sink
                translationY = 3.dp.toPx() * sink
                alpha = if (enabled) 1f else 0.45f
            }
            .drawBehind {
                val shadow = 4.dp.toPx() * (1f - sink)
                drawRoundRect(Color(0x99000000), topLeft = Offset(shadow, shadow), size = size, cornerRadius = CornerRadius(7.dp.toPx()))
            }
            .background(Brush.verticalGradient(listOf(AdColors.WindowTop, AdColors.WindowBottom)), WindowShape)
            .border(1.dp, Color(0xFF000814), WindowShape)
            .padding(1.dp)
            .border(2.dp, Brush.verticalGradient(listOf(AdColors.WindowEdge, AdColors.WindowShade)), RoundedCornerShape(6.dp))
            .then(clickable)
            .padding(padding),
        content = content,
    )
}

/** The menu pointer: a gold arrow, nudging toward what it points at. */
@Composable
fun AdPointer(modifier: Modifier = Modifier, size: Dp = 18.dp) {
    val motion = rememberInfiniteTransition(label = "pointer")
    val nudge by motion.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(420, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "nudge",
    )
    Canvas(
        modifier
            .size(size)
            .graphicsLayer { translationX = nudge * 5.dp.toPx() }
    ) {
        val path = Path().apply {
            moveTo(0f, 0f)
            lineTo(this@Canvas.size.width, this@Canvas.size.height / 2)
            lineTo(0f, this@Canvas.size.height)
            lineTo(this@Canvas.size.width * 0.28f, this@Canvas.size.height / 2)
            close()
        }
        drawPath(path, Color(0x99000000))
        translate(-2f, -2f) {
            drawPath(path, AdColors.Gold)
            drawPath(path, Color(0xFF6B4A00), style = Stroke(1.5f))
        }
    }
}

/** Centered text in the blocky display font. */
val AdDisplayCentered = AdDisplayStyle.copy(textAlign = TextAlign.Center)
