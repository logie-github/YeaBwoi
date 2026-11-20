package com.logie.wifecraft.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.logie.wifecraft.simulation.ImmersiveSettings
import com.logie.wifecraft.simulation.SoftControl
import com.logie.wifecraft.simulation.UndressOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The soft controls: a few round words on a shallow arc (Slower, Faster, Closer, Change position, Continue, End), in the
 * menus' slate and gold but quieter, no numbers. [controls] in order along the arc.
 */
@Composable
fun SoftRadial(controls: List<SoftControl>, onPick: (SoftControl) -> Unit, modifier: Modifier = Modifier) {
    if (controls.isEmpty()) return
    val w = SOFT_ARC_W
    val h = SOFT_ARC_H
    Box(modifier.size(w.dp, h.dp)) {
        val n = controls.size
        for ((i, c) in controls.withIndex()) {
            // from the left end of the arc to the right, raised in the middle
            val t = if (n == 1) 0.5f else i.toFloat() / (n - 1)
            val a = PI * (1.0 - t)
            val x = (w / 2f + cos(a).toFloat() * (w / 2f - SOFT_PILL_W / 2f)) - SOFT_PILL_W / 2f
            val y = (h - SOFT_PILL_H) - sin(a).toFloat() * (h - SOFT_PILL_H - 4f)
            SoftPill(c.label, c == SoftControl.CONTINUE, { onPick(c) }, Modifier.offset(x.dp, y.dp))
        }
    }
}

@Composable
private fun SoftPill(text: String, lead: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val pill = RoundedCornerShape(50)
    Box(
        modifier.width(SOFT_PILL_W.dp).height(SOFT_PILL_H.dp).semantics { contentDescription = text }
            .warPress(sound = "click", onClick = onClick)
            .clip(pill).background(Brush.verticalGradient(listOf(WifeTheme.secondaryTop.copy(alpha = 0.78f), WifeTheme.secondaryBottom.copy(alpha = 0.78f))))
            .border(if (lead) 1.5.dp else WifeTheme.trimWidth, if (lead) WifeTheme.gold.copy(alpha = 0.85f) else WifeTheme.trimSoft, pill),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = frizStyle(12.sp, if (lead) Color.White else WifeTheme.text, shadow = false), maxLines = 1,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

private const val SOFT_ARC_W = 340f
private const val SOFT_ARC_H = 120f
private const val SOFT_PILL_W = 104f
private const val SOFT_PILL_H = 40f

/**
 * Options' Immersion page: the life HUD (off: the Sims layer hidden, the simulation still running underneath), the HUD's
 * time, the flow (Auto flow, undressing and what is kept on), the body language (breathing, small movements, eye contact),
 * the atmosphere (motes, the camera's sway, atmosphere sounds, how much the voices follow the moment). Every change applies at
 * once.
 */
@Composable
fun ImmersionPage(settings: ImmersiveSettings, onChange: (ImmersiveSettings) -> Unit, keepOn: Set<Int>, onKeepOn: (Set<Int>) -> Unit) {
    WifeSwitchRow("Show life HUD", settings.lifeHud, { onChange(settings.copy(lifeHud = it)) },
        detail = "Needs, moods, thoughts, the clock, the feed and the Actions menu over the scene")
    WifeSliderRow("Controls stay", settings.hudSeconds, { onChange(settings.copy(hudSeconds = it)) }, valueText = "${settings.hudSeconds} s",
        range = ImmersiveSettings.HUD_MIN_S..ImmersiveSettings.HUD_MAX_S)
    WifeNote("The scene fills the screen. Tap it to bring the controls back for a moment.")
    WifeSectionHeader("Flow")
    WifeSwitchRow("Auto flow", settings.autoFlow, { onChange(settings.copy(autoFlow = it)) },
        detail = "Each part moves on by itself; Continue still moves on at once")
    WifeSwitchRow("Undress slowly", settings.undress, { onChange(settings.copy(undress = it)) },
        detail = "Clothes come off one at a time")
    WifeNote("Keep on")
    val slots = UndressOrder.ORDER
    for (row in slots.chunked(3)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapS)) {
            for (slot in row) {
                val kept = slot in keepOn
                val name = UndressOrder.NAMES[slot] ?: ""
                WifeChip(if (kept) "$name ✓" else name, { onKeepOn(if (kept) keepOn - slot else keepOn + slot) },
                    Modifier.weight(1f), trailing = null)
            }
            repeat(3 - row.size) { Box(Modifier.weight(1f)) }
        }
    }
    WifeSectionHeader("Body")
    WifeSliderRow("Breathing", settings.breathing, { onChange(settings.copy(breathing = it)) })
    WifeSliderRow("Small movements", settings.idleMotion, { onChange(settings.copy(idleMotion = it)) })
    WifeSwitchRow("Eye contact", settings.eyeContact, { onChange(settings.copy(eyeContact = it)) },
        detail = "They turn their heads to each other")
    WifeSectionHeader("Atmosphere")
    WifeSwitchRow("Glowing motes", settings.particles, { onChange(settings.copy(particles = it)) },
        detail = "Embers and dust drifting in the light")
    WifeSwitchRow("Motes in cinematic mode", settings.particlesInCinematic, { onChange(settings.copy(particlesInCinematic = it)) })
    WifeSliderRow("Camera sway", settings.handheld, { onChange(settings.copy(handheld = it)) })
    WifeSliderRow("Atmosphere sounds", settings.atmosphere, { onChange(settings.copy(atmosphere = it)) })
    WifeSwitchRow("Rain outside", settings.rain, { onChange(settings.copy(rain = it)) })
    WifeSliderRow("Voice intensity", settings.voice, { onChange(settings.copy(voice = it)) })
    WifeNote("The music swells and the room quietens as the moment builds; the voices follow it as far as you choose.")
}
