// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.warcrafts.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.logie.warcrafts.R
import com.logie.warcrafts.library.Title

/** What drifts over a game's pictures. */
enum class Weather { EMBERS, RAIN, SNOW }

/**
 * A game's look in the launcher, from the user's art for it: its colours, its pictures and logo,
 * where the faces are in them (kept in frame when the screen is narrower than the picture), and
 * the weather over them.
 */
data class RtsLook(
    val abyss: Color,
    val night: Color,
    val deep: Color,
    /** Panels' lit inside, and the main buttons'. */
    val frost: Color,
    /** Hairlines. */
    val steel: Color,
    /** What's selected and what matters. */
    val accent: Color,
    val glow: Color,
    /** Frame corners and the main buttons' lettering: the logos' gold. */
    val gold: Color,
    val text: Color,
    val mist: Color,
    val header: Int,
    val headerAlignment: BiasAlignment,
    val loading: Int,
    val loadingAlignment: BiasAlignment,
    val logo: Int,
    /** The logo's height over its width. */
    val logoRatio: Float,
    val weather: Weather,
    val name: Int,
) {
    val blood = Color(0xFFE0574A)
    val good = Color(0xFF86E3B4)

    companion object {
        /**
         * Warcraft I: the orc and the knight face to face on crimson, the logo's gold over blue
         * stone. Embers.
         */
        val WC1 = RtsLook(
            abyss = Color(0xFF0D0405), night = Color(0xFF1B0709), deep = Color(0xFF33100F), frost = Color(0xFF5A1C17),
            steel = Color(0xFF7D4A3C), accent = Color(0xFFF5C451), glow = Color(0xFFFFE9AE), gold = Color(0xFFF2B33D),
            text = Color(0xFFF6EAE0), mist = Color(0xFFC0A699),
            header = R.drawable.wc1_header, headerAlignment = BiasAlignment(0f, -0.2f),
            loading = R.drawable.wc1_loading, loadingAlignment = BiasAlignment(0f, -0.2f),
            logo = R.drawable.wc1_logo, logoRatio = 0.467f, weather = Weather.EMBERS, name = R.string.rts_title_wc1,
        )

        /** Warcraft II: the orc pirate and the admiral in a storm at sea, lightning. Rain. */
        val WC2 = RtsLook(
            abyss = Color(0xFF020811), night = Color(0xFF061527), deep = Color(0xFF0C243E), frost = Color(0xFF15395C),
            steel = Color(0xFF3B5E7F), accent = Color(0xFF94D6FF), glow = Color(0xFFE4F6FF), gold = Color(0xFFE8B34A),
            text = Color(0xFFE8F1F8), mist = Color(0xFF93AEC6),
            header = R.drawable.wc2_header, headerAlignment = BiasAlignment(0f, -0.3f),
            loading = R.drawable.wc2_loading, loadingAlignment = BiasAlignment(-0.35f, 0f),
            logo = R.drawable.wc2_logo, logoRatio = 0.461f, weather = Weather.RAIN, name = R.string.rts_title_wc2,
        )

        /** Reign of Chaos: Thrall out of the dark, the logo in fire. Embers. */
        val ROC = RtsLook(
            abyss = Color(0xFF050403), night = Color(0xFF100B07), deep = Color(0xFF20150C), frost = Color(0xFF3B2411),
            steel = Color(0xFF61482F), accent = Color(0xFFFF9636), glow = Color(0xFFFFDBA3), gold = Color(0xFFF0B93A),
            text = Color(0xFFF4ECE2), mist = Color(0xFFB6A290),
            header = R.drawable.roc_header, headerAlignment = BiasAlignment(0.5f, -0.3f),
            loading = R.drawable.wc3_loading, loadingAlignment = BiasAlignment(0.4f, 0f),
            logo = R.drawable.roc_logo, logoRatio = 0.533f, weather = Weather.EMBERS, name = R.string.rts_title_roc,
        )

        /** The Frozen Throne: Arthas out of the dark, the logo in ice. Snow. */
        val TFT = RtsLook(
            abyss = Color(0xFF02060B), night = Color(0xFF06111C), deep = Color(0xFF0B1E30), frost = Color(0xFF12324D),
            steel = Color(0xFF2E5670), accent = Color(0xFF7FD8F8), glow = Color(0xFFDDF7FF), gold = Color(0xFFE8B34A),
            text = Color(0xFFE6F1F7), mist = Color(0xFF8FA9BC),
            header = R.drawable.tft_header, headerAlignment = BiasAlignment(0.42f, -0.3f),
            loading = R.drawable.wc3_loading, loadingAlignment = BiasAlignment(0.4f, 0f),
            logo = R.drawable.tft_logo, logoRatio = 0.566f, weather = Weather.SNOW, name = R.string.rts_title_tft,
        )

        fun of(title: Title): RtsLook = when (title) {
            Title.WC1 -> WC1
            Title.WC2 -> WC2
            Title.ROC -> ROC
            Title.TFT -> TFT
        }
    }
}

val LocalLook = staticCompositionLocalOf { RtsLook.TFT }

/** Cinzel, carved Roman capitals like the games' own titles. */
@OptIn(ExperimentalTextApi::class)
val RtsDisplayFont = FontFamily(
    Font(R.font.rts_cinzel, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.rts_cinzel, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(650))),
    Font(R.font.rts_cinzel, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(800))),
)

/** Jost for reading. */
val RtsBodyFont = FontFamily(
    Font(R.font.rts_jost_light, FontWeight.Light),
    Font(R.font.rts_jost_regular, FontWeight.Normal),
    Font(R.font.rts_jost_medium, FontWeight.Medium),
)

val RtsDisplayStyle = TextStyle(fontFamily = RtsDisplayFont, fontWeight = FontWeight.SemiBold, letterSpacing = 0.08.em)

/** Gold text's dark edge, so it reads over the pictures. */
val RtsGoldShadow = Shadow(Color.Black.copy(alpha = 0.85f), Offset(0f, 2f), 6f)

/** The game's colours and type over everything in [content]. */
@Composable
fun RtsTheme(look: RtsLook, content: @Composable () -> Unit) {
    val display = RtsDisplayStyle.copy(color = look.text)
    val body = TextStyle(fontFamily = RtsBodyFont, color = look.text)
    val typography = Typography(
        headlineLarge = display.copy(fontSize = 26.sp, lineHeight = 34.sp),
        headlineMedium = display.copy(fontSize = 21.sp, lineHeight = 28.sp),
        headlineSmall = display.copy(fontSize = 17.sp, lineHeight = 24.sp, color = look.gold),
        titleLarge = display.copy(fontSize = 14.sp, lineHeight = 20.sp, color = look.mist),
        titleMedium = body.copy(fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium),
        titleSmall = body.copy(fontSize = 15.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium),
        bodyLarge = body.copy(fontSize = 16.sp, lineHeight = 23.sp),
        bodyMedium = body.copy(fontSize = 15.sp, lineHeight = 21.sp),
        bodySmall = body.copy(fontSize = 13.sp, lineHeight = 18.sp, color = look.mist),
        labelLarge = body.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
        labelMedium = body.copy(fontSize = 13.sp),
        labelSmall = body.copy(fontSize = 11.sp),
    )
    val scheme = darkColorScheme(
        primary = look.accent,
        onPrimary = look.abyss,
        primaryContainer = look.frost,
        onPrimaryContainer = look.glow,
        secondary = look.gold,
        onSecondary = look.abyss,
        background = look.night,
        onBackground = look.text,
        surface = look.deep,
        onSurface = look.text,
        surfaceVariant = look.frost,
        onSurfaceVariant = look.mist,
        surfaceContainerHigh = look.deep,
        surfaceContainerHighest = look.frost,
        outline = look.steel,
        error = look.blood,
    )
    CompositionLocalProvider(LocalLook provides look) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}
