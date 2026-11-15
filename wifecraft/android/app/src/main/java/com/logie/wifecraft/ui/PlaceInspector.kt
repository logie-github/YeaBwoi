package com.logie.wifecraft.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.logie.wifecraft.editor.PlaceKind
import com.logie.wifecraft.editor.Placement
import com.logie.wifecraft.story.CameraPose
import com.logie.wifecraft.story.Vec3
import kotlin.math.log10
import kotlin.math.pow

/**
 * The selected thing's numbers: position (yards, WoW's axes, Z up), turn (degrees) and size, typed or stepped; the nudge
 * buttons (as the camera sees the floor, a grid step while snapping), Up and Down; Copy and Delete; for a person or creature,
 * its animation. Scrolls inside its panel.
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
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(p.name, style = frizStyle(16.sp), maxLines = 1)
        Text(
            when (p.kind) {
                PlaceKind.PROP -> "Prop, file ${p.asset}"
                PlaceKind.CREATURE -> "Creature, display ${p.asset}"
                PlaceKind.NPC -> "One of your NPCs"
            } + if (locked) " · locked" else "",
            style = frizStyle(12.sp, WowColors.grey, false),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            NumberField("X", p.x, Modifier.weight(1f)) { onChange(p.copy(x = it)) }
            NumberField("Y", p.y, Modifier.weight(1f)) { onChange(p.copy(y = it)) }
            NumberField("Z", p.z, Modifier.weight(1f)) { onChange(p.copy(z = it)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            NumberField("Turn°", yawDegrees(p.yaw), Modifier.weight(1f), decimals = 1) { onChange(p.copy(yaw = yawRadians(it))) }
            NumberField("Size", p.scale, Modifier.weight(1f)) { onChange(p.copy(scale = Snap.clampScale(it))) }
        }
        // size on a log slider: 0.05 to 20, 1 in the middle
        val gold = SliderDefaults.colors(thumbColor = WowColors.gold, activeTrackColor = WowColors.gold, inactiveTrackColor = Color(0xFF3A3020))
        Slider(
            log10(p.scale.coerceIn(Snap.MIN_SCALE, Snap.MAX_SCALE)), { v -> onSlide(p.copy(scale = snap.scale(10f.pow(v)))) },
            valueRange = log10(Snap.MIN_SCALE)..log10(Snap.MAX_SCALE), colors = gold, enabled = !locked, onValueChangeFinished = onSlideEnd,
        )
        Slider(
            yawDegrees(p.yaw), { v -> onSlide(p.copy(yaw = snap.yaw(yawRadians(v)))) },
            valueRange = -180f..180f, colors = gold, enabled = !locked, onValueChangeFinished = onSlideEnd,
        )
        Text("Nudge ${showNumber(snap.step)} yd", style = frizStyle(13.sp))
        val step = snap.step
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            PButton("Left", { onNudge(FloorMath.nudge(camera, 0, -1, step)) }, Modifier.weight(1f), enabled = !locked, width = 72.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PButton("Away", { onNudge(FloorMath.nudge(camera, 1, 0, step)) }, enabled = !locked, width = 80.dp)
                PButton("Near", { onNudge(FloorMath.nudge(camera, -1, 0, step)) }, enabled = !locked, width = 80.dp)
            }
            PButton("Right", { onNudge(FloorMath.nudge(camera, 0, 1, step)) }, Modifier.weight(1f), enabled = !locked, width = 72.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PButton("Up", { onNudge(Vec3(0f, 0f, step)) }, enabled = !locked, width = 72.dp)
                PButton("Down", { onNudge(Vec3(0f, 0f, -step)) }, enabled = !locked, width = 72.dp)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            PButton("Copy", onDuplicate, Modifier.weight(1f), width = 90.dp)
            PButton("Delete", onDelete, Modifier.weight(1f), width = 90.dp)
            if (p.kind != PlaceKind.PROP) PButton("Animation", onAnimation, Modifier.weight(1.4f), width = 120.dp)
        }
        Spacer(Modifier.heightIn(min = 4.dp))
    }
}

/** A labelled number box: what is typed is used when it reads as a number and Done is pressed or the box is left. */
@Composable
private fun NumberField(label: String, value: Float, modifier: Modifier = Modifier, decimals: Int = 2, onValue: (Float) -> Unit) {
    var text by remember(value) { mutableStateOf(showNumber(value, decimals)) }
    var focused by remember { mutableStateOf(false) }
    fun use() { readNumber(text)?.let { if (it != value) onValue(it) } ?: run { text = showNumber(value, decimals) } }
    Column(modifier) {
        Text(label, style = frizStyle(12.sp))
        Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).background(Color.Black).border(1.dp, if (focused) WowColors.gold else Color(0xFF6B6B6B)).padding(horizontal = 8.dp, vertical = 12.dp)) {
            BasicTextField(
                text, { text = it.take(12) }, singleLine = true,
                textStyle = frizStyle(15.sp, Color.White, false),
                cursorBrush = SolidColor(WowColors.gold),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { use() }),
                modifier = Modifier.fillMaxWidth().onFocusChanged { f -> if (focused && !f.isFocused) use(); focused = f.isFocused },
            )
        }
    }
}
