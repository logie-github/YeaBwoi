// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.v3gold.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Persona 4 Golden's palette: TV yellow, ink black, the logo's gold outline and the TV color bars. */
object GoldColors {
    val Yellow = Color(0xFFFFE500)
    val TileBase = Color(0xFFFBF44C)
    val TileDeep = Color(0xFFFFE600)
    val TilePale = Color(0xFFFFF9A6)
    val TileWhite = Color(0xFFFFFFE8)
    val Ink = Color(0xFF1B1512)
    val InkSoft = Color(0xFF4A3F33)
    val Gold = Color(0xFFF2C318)
    val Paper = Color(0xFFFFFFFF)
    val Red = Color(0xFFE5262A)
    val Orange = Color(0xFFF68B1F)
    val Lime = Color(0xFFB8D530)
    val Cyan = Color(0xFF6FD8F0)
    val Green = Color(0xFF2B5F1D)
    val Cream = Color(0xFFF9E0A0)
}

private val GoldScheme = lightColorScheme(
    primary = GoldColors.Ink,
    onPrimary = GoldColors.Yellow,
    primaryContainer = GoldColors.Yellow,
    onPrimaryContainer = GoldColors.Ink,
    secondary = GoldColors.Gold,
    onSecondary = GoldColors.Ink,
    secondaryContainer = GoldColors.TilePale,
    onSecondaryContainer = GoldColors.Ink,
    tertiary = GoldColors.Orange,
    onTertiary = GoldColors.Ink,
    background = GoldColors.TileBase,
    onBackground = GoldColors.Ink,
    surface = GoldColors.Paper,
    onSurface = GoldColors.Ink,
    surfaceVariant = GoldColors.TilePale,
    onSurfaceVariant = GoldColors.InkSoft,
    surfaceContainerHigh = GoldColors.Paper,
    surfaceContainerHighest = GoldColors.Paper,
    outline = GoldColors.Ink,
    error = Color(0xFFB3261E),
    onError = GoldColors.Paper,
)

/** Bold and slanted, like the game's menus. */
private val GoldTypography = Typography().run {
    copy(
        headlineLarge = headlineLarge.copy(fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.ExtraBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.Bold),
    )
}

/** The big slanted labels: PLAY, section titles. */
val GoldDisplayStyle = TextStyle(
    fontWeight = FontWeight.Black,
    fontStyle = FontStyle.Italic,
    letterSpacing = 1.5.sp,
)

/** The home screen's yellow and black theme. Vita3K's own screens use the dark version (Theme.kt). */
@Composable
fun GoldTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = GoldScheme, typography = GoldTypography, content = content)
}
