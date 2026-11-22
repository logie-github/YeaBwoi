package com.logie.wifecraft.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.logie.wifecraft.graphics.Graphics
import com.logie.wifecraft.graphics.GraphicsPreset
import com.logie.wifecraft.graphics.GraphicsSettings
import com.logie.wifecraft.graphics.ToneCurve

/**
 * Options' Graphics page (graphics/GraphicsSettings.kt, applied at once and kept by graphics/Graphics.kt): the preset chips
 * (Standard, High, Ultra, Custom), the target frame rate, Adaptive quality and the performance overlay with what the governor
 * has lowered now, then every effect's switch or slider. Moving one makes the preset Custom (or the preset it now matches).
 */
@Composable
fun GraphicsPage() {
    val context = LocalContext.current
    Graphics.load(context)
    val s by Graphics.settings.collectAsState()
    val set: (GraphicsSettings) -> Unit = { Graphics.set(context, it) }
    val edit: (GraphicsSettings) -> Unit = { Graphics.set(context, it.withMatchingPreset()) }

    SparkleSwitch.load(context)
    WifeSectionHeader("Quality")
    val presets = GraphicsPreset.entries
    WifeTabs(presets.map { it.label }, presets.indexOf(s.preset).coerceAtLeast(0), { set(s.withPreset(presets[it])) })
    WifeNote("Standard draws the scene as before. High and Ultra add the effects below; Custom is whatever you set.")
    WifeSwitchRow("Show sparkles", SparkleSwitch.on, { SparkleSwitch.set(context, it) },
        detail = "WoW-style twinkles over every spot a body can use. Fewer when the governor lowers effects; off in cinematic mode and Build mode.")
    GfxDropdownRow("Target frame rate", "${s.targetFps} fps", GraphicsSettings.TARGETS.map { it to "$it fps" }) { fps: Int ->
        set(s.copy(targetFps = fps))
    }
    WifeSwitchRow("Adaptive quality", s.adaptive, { set(s.copy(adaptive = it)) },
        detail = "Lowers effects one at a time, in a fixed order, before frames are missed; raises them again slowly")
    WifeSwitchRow("Show performance overlay", s.overlay, { set(s.copy(overlay = it)) },
        detail = "Frames a second, frame time and each effect's cost over the scene")
    GfxLiveReadout()
    WifeButton("Test for 10 seconds", { Graphics.startTest() }, Modifier.fillMaxWidth())

    WifeSectionHeader("Resolution and textures")
    WifeSliderRow("Render scale", s.renderScale, { edit(s.copy(renderScale = it)) }, valueText = "${s.renderScale}%",
        range = GraphicsSettings.SCALE_MIN..GraphicsSettings.SCALE_MAX)
    GfxDropdownRow("Anti-aliasing", if (s.msaa == 0) "Off" else "${s.msaa}x MSAA", listOf(0 to "Off", 2 to "2x MSAA", 4 to "4x MSAA")) { n: Int ->
        edit(s.copy(msaa = n))
    }
    GfxDropdownRow("Texture filtering", if (s.anisotropy <= 1) "Trilinear" else "${s.anisotropy}x anisotropic",
        GraphicsSettings.ANISOTROPY.map { it to if (it <= 1) "Trilinear" else "${it}x anisotropic" }) { n: Int -> edit(s.copy(anisotropy = n)) }
    val detail = GraphicsSettings.TEXTURE_DETAIL
    GfxDropdownRow("Texture detail", detail.firstOrNull { it.first == s.textureDetail }?.second ?: "Standard", detail) { n: Int ->
        edit(s.copy(textureDetail = n))
    }
    WifeNote("High takes a mip twice the size where the game's file has one, Full four times (the file's own); each step " +
        "can take up to four times the texture memory. For what is loaded after the change.")
    WifeSliderRow("Sharpen", s.sharpen, { edit(s.copy(sharpen = it)) })

    WifeSectionHeader("Light and colour")
    val tones = ToneCurve.entries
    WifeTabs(tones.map { it.label }, tones.indexOf(s.tone).coerceAtLeast(0), { edit(s.copy(tone = tones[it])) })
    WifeNote("Filmic softens only the brightest light and keeps skin tones; ACES is the film look, with deeper shadows.")
    WifeSliderRow("Exposure", s.exposure, { edit(s.copy(exposure = it)) }, valueText = "%+.1f EV".format(s.exposure / 10f), range = -20..20)
    WifeSliderRow("Bloom", s.bloom, { edit(s.copy(bloom = it)) })
    WifeSliderRow("Ambient occlusion", s.ao, { edit(s.copy(ao = it)) })
    GfxDropdownRow("Occlusion quality", listOf("Low", "Medium", "High")[s.aoQuality.coerceIn(0, 2)],
        listOf(0 to "Low", 1 to "Medium", 2 to "High")) { q: Int -> edit(s.copy(aoQuality = q)) }
    WifeSliderRow("Contact shadows", s.contact, { edit(s.copy(contact = it)) })
    WifeSliderRow("Shadows", s.shadows, { edit(s.copy(shadows = it)) })
    WifeSwitchRow("Soft shadows", s.shadowQuality >= 1, { edit(s.copy(shadowQuality = if (it) 1 else 0)) },
        detail = "A sharper shadow map whose edges soften with distance (2048 px); off: 1024 px")

    WifeSectionHeader("Camera")
    WifeSliderRow("Depth of field", s.dof, { edit(s.copy(dof = it)) })
    WifeSliderRow("Aperture", s.aperture, { edit(s.copy(aperture = it)) }, valueText = "${s.aperture}%")
    WifeSwitchRow("Depth of field in the creator", s.dofInEditor, { edit(s.copy(dofInEditor = it)) },
        detail = "Off: the creator and the placement editor stay sharp")
    WifeSliderRow("Vignette", s.vignette, { edit(s.copy(vignette = it)) })
    WifeSliderRow("Film grain", s.grain, { edit(s.copy(grain = it)) })
    WifeSliderRow("Chromatic aberration", s.chroma, { edit(s.copy(chroma = it)) })
    WifeNote("Cinematic mode draws its own vignette and grain; these are left to it while it is on.")

    WifeSectionHeader("People")
    WifeSliderRow("Skin shading", s.skin, { edit(s.copy(skin = it)) })
    WifeSliderRow("Hair highlights", s.hair, { edit(s.copy(hair = it)) })
    WifeSliderRow("Eye highlights", s.eyes, { edit(s.copy(eyes = it)) })
    WifeSliderRow("Thin-skin tint", s.thinSkin, { edit(s.copy(thinSkin = it)) })
    WifeNote("Ears, nose, lips and fingers lit through, warm, most with the light behind them.")
    WifeSwitchRow("Skin sheen follows intensity", s.sheenFollows, { edit(s.copy(sheenFollows = it)) },
        detail = "The skin grows a light wet sheen as a scene's moment builds, and keeps it a while after")
    WifeSliderRow("Maximum sheen", s.sheenMax, { edit(s.copy(sheenMax = it)) }, valueText = "${s.sheenMax}%")

    WifeSectionHeader("Atmosphere")
    WifeSliderRow("Light shafts", s.shafts, { edit(s.copy(shafts = it)) })
    WifeSliderRow("Haze", s.haze, { edit(s.copy(haze = it)) })
    WifeNote("60 fps is the target the governor keeps to by lowering effects; what each effect costs on a phone hasn't been measured yet.")
}

/** A label and a dropdown on one row (the Options pages' own layout). */
@Composable
private fun <T> GfxDropdownRow(label: String, current: String, options: List<Pair<T, String>>, onPick: (T) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = WifeTheme.touch), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapM)) {
        Text(label, Modifier.weight(1f), style = WifeTheme.labelStyle(), maxLines = 2)
        WowDropdown(current, options, onPick, Modifier.weight(1.1f))
    }
}

/** What the governor has lowered now, the thermal status and the last test (read here alone: it changes twice a second). */
@Composable
private fun GfxLiveReadout() {
    val live by Graphics.live.collectAsState()
    val lowered = if (live.lowered.isEmpty()) "Nothing lowered now" else "Lowered now: " + live.lowered.joinToString(", ") { it.label }
    val thermal = when (live.thermal) { 0 -> ""; 1 -> " · the phone is warm: one step kept down"; else -> " · the phone is hot: two steps kept down" }
    Text(lowered + thermal, style = WifeTheme.captionStyle(WifeTheme.text), maxLines = 3)
    val test = if (live.testing) "Testing…" else live.test
    if (test != null) Text(test, style = WifeTheme.captionStyle(WifeTheme.gold), maxLines = 3)
}

/** The performance overlay over the scene (Options' Graphics: Show performance overlay): fps, ms, each pass's cost. */
@Composable
fun PerformanceOverlay(modifier: Modifier = Modifier) {
    val s by Graphics.settings.collectAsState()
    if (!s.overlay) return
    val live by Graphics.live.collectAsState()
    val mono = TextStyle(color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    fun ms(v: Float) = if (v.isNaN()) "–" else "%.1f".format(v)
    Column(modifier.padding(8.dp).background(Color(0xB0000000), RoundedCornerShape(4.dp)).padding(6.dp)) {
        Text("%.0f fps  %s ms  p95 %s  budget %s".format(live.fps, ms(live.frameMs), ms(live.p95Ms), ms(live.budgetMs)), style = mono)
        Text("cpu %s  gpu %s  %dx%d  %.0f Hz".format(ms(live.cpuMs), ms(live.gpuMs), live.sceneW, live.sceneH, live.refreshHz), style = mono)
        for ((name, v) in live.passes) Text("  %-15s %s".format(name, ms(v)), style = mono)
        if (live.lowered.isNotEmpty()) Text("lowered: " + live.lowered.joinToString(", ") { it.label }, style = mono, maxLines = 2)
    }
}
