package com.logie.wifecraft.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.logie.wifecraft.simulation.CinematicSettings
import com.logie.wifecraft.simulation.CinematicStyle

/** How long the HUD stays after a tap in cinematic mode before it hides again (ms). */
const val CINEMATIC_PEEK_MS = 5_000L
/** The HUD's fade out and in as cinematic mode hides and shows it (ms). */
const val CINEMATIC_HUD_MS = 400

/**
 * Cinematic mode's layer over the live scene: the Cinematic button (beside the camera button, in its look; lit while the mode
 * runs, dimmed while the HUD is hidden: pressing it ends the mode) and, while the HUD is hidden, a sheet over the view that
 * takes every touch: a tap brings the HUD back for a while ([onPeek]); no drag turns the camera under the director. Reports
 * the view's width over height ([onAspect]) for the director's framing.
 */
@Composable
fun CinematicLayer(on: Boolean, hudHidden: Boolean, shown: Boolean, onToggle: () -> Unit, onPeek: () -> Unit,
                   onAspect: (Float) -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().onSizeChanged { if (it.height > 0) onAspect(it.width.toFloat() / it.height) }) {
        if (hudHidden) {
            Box(Modifier.fillMaxSize().semantics { contentDescription = "Cinematic view: tap to show the controls" }
                .pointerInput(Unit) { detectTapGestures(onTap = { onPeek() }) })
        }
        if (shown) {
            val buttonAlpha by animateFloatAsState(if (hudHidden) 0.45f else 1f, tween(MenuMotion.duration(MenuMotion.WINDOW_IN_MS, reduceMotion())),
                label = "cinematicButton")
            BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                val hud = SceneHud.layout(maxWidth.value, maxHeight.value)
                CinematicButton(on, onToggle, Modifier.align(Alignment.TopEnd).graphicsLayer { alpha = buttonAlpha }
                    .padding(end = (SceneHud.MARGIN + SceneHud.CAMERA_W + 2 * SceneHud.CAMERA_GAP).dp, top = SceneHud.cameraTop(hud).dp))
            }
        }
    }
}

/** The Cinematic button: a slate pill with the gold trim like the camera button, lit gold while the mode runs. */
@Composable
fun CinematicButton(on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val pill = RoundedCornerShape(50)
    Box(
        modifier.width(CINEMATIC_BUTTON_W.dp).height(WifeTheme.touch)
            .semantics { contentDescription = if (on) "Cinematic mode on: end it" else "Cinematic mode" }
            .warPress(sound = "tab", onClick = onClick)
            .padding(vertical = 4.dp)
            .clip(pill).background(Brush.verticalGradient(listOf(WifeTheme.secondaryTop, WifeTheme.secondaryBottom)))
            .border(if (on) 2.dp else WifeTheme.trimWidth, if (on) WifeTheme.gold else WifeTheme.trim, pill),
        contentAlignment = Alignment.Center,
    ) {
        Text("Cinematic", style = frizStyle(13.sp, if (on) Color.White else WifeTheme.gold), maxLines = 1,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

private const val CINEMATIC_BUTTON_W = 96f

/**
 * Options' Cinematic page: the lighting's strength, the camera's style and speed, each shot's length, the letterbox bars,
 * film grain, and names and bubbles hidden while it runs. Every change applies at once (a running scene included).
 */
@Composable
fun CinematicPage(settings: CinematicSettings, onChange: (CinematicSettings) -> Unit) {
    WifeSectionHeader("Lighting")
    WifeSliderRow("Mood lighting", settings.lighting, { onChange(settings.copy(lighting = it)) })
    WifeNote("A warm, low light over the time of day's: less ambient light, a warm key from the side, a soft rim from behind, a slow candle-like flicker, a gentle grade and darker corners.")
    WifeSectionHeader("Camera")
    val styles = CinematicStyle.entries
    WifeTabs(styles.map { it.label }, styles.indexOf(settings.style).coerceAtLeast(0), { onChange(settings.copy(style = styles[it])) })
    WifeSliderRow("Camera speed", settings.motion, { onChange(settings.copy(motion = it)) }, valueText = "${settings.motion}%",
        range = CinematicSettings.MOTION_MIN..CinematicSettings.MOTION_MAX)
    Column(verticalArrangement = Arrangement.spacedBy(WifeTheme.gapXs)) {
        WifeSliderRow("Shortest shot", settings.shortest, { v -> onChange(settings.copy(shortest = v, longest = maxOf(v, settings.longest))) },
            valueText = "${settings.shortest} s", range = CinematicSettings.SHOT_MIN..CinematicSettings.SHOT_MAX)
        WifeSliderRow("Longest shot", settings.longest, { v -> onChange(settings.copy(longest = v, shortest = minOf(v, settings.shortest))) },
            valueText = "${settings.longest} s", range = CinematicSettings.SHOT_MIN..CinematicSettings.SHOT_MAX)
    }
    WifeNote("The camera moves round the bed or the people from shot to shot: orbits, low and high angles, close-ups, over a shoulder, wide shots, the bed's own views, slow dollies. Cuts joins them without the move.")
    WifeSectionHeader("Picture")
    WifeSwitchRow("Letterbox", settings.letterbox, { onChange(settings.copy(letterbox = it)) }, detail = "Black bars for a wide film frame")
    WifeSwitchRow("Film grain", settings.grain, { onChange(settings.copy(grain = it)) })
    WifeSwitchRow("Hide names and bubbles", settings.hideWords, { onChange(settings.copy(hideWords = it)) },
        detail = "Speech and thought bubbles hidden while the mode runs")
}
