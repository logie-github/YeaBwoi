package com.logie.wifecraft.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.logie.wifecraft.editor.PlaceKind
import com.logie.wifecraft.editor.Placement
import com.logie.wifecraft.story.CameraPose
import com.logie.wifecraft.story.Vec3
import kotlin.math.log10
import kotlin.math.pow

/**
 * The selected thing's page (Selected): its name, position (yards, WoW's axes, Z up), turn (degrees) and size typed on
 * parchment or slid, the nudge buttons (round: as the camera sees the floor, a grid step while snapping, Up and Down), Copy
 * and Delete; for a person or creature, its animation. Scrolls inside its page.
 */
@Composable
fun PlaceInspector(
    p: Placement,
    snap: Snap,
    camera: CameraPose,
    locked: Boolean,
    onChange: (Placement) -> Unit,
    /** A slider's moves (one undo step until [onSlideEnd]). */
    onSlide: (Placement) -> Unit,
    onSlideEnd: () -> Unit,
    onNudge: (Vec3) -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onAnimation: () -> Unit,
    /** The Sound switch of a prop (nothing plays for props yet: its label says so). */
    onSound: (Boolean) -> Unit = {},
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BookHeader(
            "Selected", p.name,
            when (p.kind) {
                PlaceKind.PROP -> "Prop, file ${p.asset}"
                PlaceKind.CREATURE -> "Creature, display ${p.asset}"
                PlaceKind.NPC -> "One of your NPCs"
            } + if (locked) " · locked" else "",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WarNumberField("X", p.x, Modifier.weight(1f), enabled = !locked) { onChange(p.copy(x = it)) }
            WarNumberField("Y", p.y, Modifier.weight(1f), enabled = !locked) { onChange(p.copy(y = it)) }
            WarNumberField("Z", p.z, Modifier.weight(1f), enabled = !locked) { onChange(p.copy(z = it)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WarNumberField("Turn°", yawDegrees(p.yaw), Modifier.weight(1f), decimals = 1, enabled = !locked) { onChange(p.copy(yaw = yawRadians(it))) }
            WarNumberField("Size", p.scale, Modifier.weight(1f), enabled = !locked) { onChange(p.copy(scale = Snap.clampScale(it))) }
        }
        // size on a log slider: 0.05 to 20, 1 in the middle
        Text("Size  ${showNumber(p.scale)}×", style = warText(12.sp, War.brownSoft))
        WarSlider(
            log10(p.scale.coerceIn(Snap.MIN_SCALE, Snap.MAX_SCALE)), { v -> onSlide(p.copy(scale = snap.scale(10f.pow(v)))) },
            log10(Snap.MIN_SCALE)..log10(Snap.MAX_SCALE), enabled = !locked, onFinished = onSlideEnd,
        )
        Text("Turn  ${showNumber(yawDegrees(p.yaw), 0)}°", style = warText(12.sp, War.brownSoft))
        WarSlider(yawDegrees(p.yaw), { v -> onSlide(p.copy(yaw = snap.yaw(yawRadians(v)))) }, -180f..180f, enabled = !locked, onFinished = onSlideEnd)
        Text("Nudge ${showNumber(snap.step)} yd", style = warText(12.sp, War.brownSoft))
        val step = snap.step
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            RoundIconButton(Face(glyph = WarIcons.BACK), { onNudge(FloorMath.nudge(camera, 0, -1, step)) }, size = 48.dp, enabled = !locked, label = "Left", onLight = true)
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                RoundIconButton(Face(glyph = WarIcons.PLUS), { onNudge(FloorMath.nudge(camera, 1, 0, step)) }, size = 48.dp, enabled = !locked, label = "Away", onLight = true)
                RoundIconButton(Face(glyph = WarIcons.MINUS), { onNudge(FloorMath.nudge(camera, -1, 0, step)) }, size = 48.dp, enabled = !locked, label = "Near", onLight = true)
            }
            RoundIconButton(Face(glyph = WarIcons.PLAY), { onNudge(FloorMath.nudge(camera, 0, 1, step)) }, size = 48.dp, enabled = !locked, label = "Right", onLight = true)
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                RoundIconButton(Face(glyph = WarIcons.REDO), { onNudge(Vec3(0f, 0f, step)) }, size = 48.dp, enabled = !locked, label = "Up", onLight = true)
                RoundIconButton(Face(glyph = WarIcons.UNDO), { onNudge(Vec3(0f, 0f, -step)) }, size = 48.dp, enabled = !locked, label = "Down", onLight = true)
            }
        }
        if (p.kind == PlaceKind.PROP) WarToggle(PlaceLabels.sound(p.kind), p.sound, onSound)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("Copy", onDuplicate, Modifier.weight(1f), glyph = WarIcons.COPY)
            PillButton("Delete", onDelete, Modifier.weight(1f), danger = true, glyph = WarIcons.DELETE)
        }
        if (p.kind != PlaceKind.PROP) PillButton("Animation", onAnimation, Modifier.fillMaxWidth(), glyph = WarIcons.SPEAK)
        Spacer(Modifier.heightIn(min = 4.dp).padding(bottom = 4.dp))
    }
}
