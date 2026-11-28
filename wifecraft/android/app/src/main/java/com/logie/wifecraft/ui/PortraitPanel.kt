package com.logie.wifecraft.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.logie.wifecraft.simulation.GazeMode
import com.logie.wifecraft.simulation.LifeLevel
import com.logie.wifecraft.simulation.PortraitLighting
import com.logie.wifecraft.simulation.PortraitSettings

/**
 * The portrait rows shared by Interact mode's sheet (its Look tab) and the hidden mode's settings: Lighting, Life, Gaze and the Look angle
 * (simulation/PortraitSettings.kt keeps them; the engine's rig and life layer are cpp/engine/portrait.h and life.h).
 */
@Composable
internal fun PortraitRows(s: PortraitSettings, onChange: (PortraitSettings) -> Unit) {
    WifeNote("Lighting")
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapXs)) {
        for (l in PortraitLighting.entries) WifeChip(l.label, { onChange(s.copy(lighting = l)) }, trailing = if (s.lighting == l) "✓" else null)
    }
    WifeNote("Life")
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapXs)) {
        for (l in LifeLevel.entries) WifeChip(l.label, { onChange(s.copy(life = l)) }, trailing = if (s.life == l) "✓" else null)
    }
    WifeNote("Gaze")
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapXs)) {
        for (g in GazeMode.entries) WifeChip(g.label, { onChange(s.copy(gaze = g)) }, trailing = if (s.gaze == g) "✓" else null)
    }
    WifeSliderRow("Look angle", s.lookAngle, { onChange(s.withAngle(it)) }, valueText = "${s.lookAngle}°", range = PortraitSettings.MIN_ANGLE..PortraitSettings.MAX_ANGLE,
        enabled = s.gaze != GazeMode.OFF)
}
