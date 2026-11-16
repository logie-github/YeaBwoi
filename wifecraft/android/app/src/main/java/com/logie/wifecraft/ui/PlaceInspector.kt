package com.logie.wifecraft.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.logie.wifecraft.editor.PlaceKind
import com.logie.wifecraft.editor.Placement
import com.logie.wifecraft.story.CameraPose
import com.logie.wifecraft.story.Vec3

/**
 * The selected thing as the game's tooltip (bottom right, over the scene): its name in gold, what it is and what it plays,
 * Emote (a person or a creature) and Details. Move, Rotate and Scale are on the action bar. **Details** folds open its
 * position (yards, WoW's axes, Z up), turn (degrees) and size typed in, the nudge buttons (Left, Right, Away, Near as the
 * camera sees the floor, Up, Down: a tenth of a yard or the grid), and its switches (Sound; Held for a person or creature).
 * Never taller than [maxHeight]: the details scroll.
 */
@Composable
fun InspectorTooltip(
    p: Placement,
    snap: Snap,
    camera: CameraPose,
    details: Boolean,
    onDetails: () -> Unit,
    onEmote: () -> Unit,
    onChange: (Placement) -> Unit,
    onNudge: (Vec3) -> Unit,
    onSound: (Boolean) -> Unit,
    onLoop: (Boolean) -> Unit,
    emoteName: String?,
    modifier: Modifier = Modifier,
    maxHeight: Dp = 600.dp,
) {
    val locked = p.locked
    val living = p.kind != PlaceKind.PROP
    WowTooltipBox(modifier) {
        Column(Modifier.heightIn(max = maxHeight).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(p.name, style = frizStyle(18.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(
                    when (p.kind) { PlaceKind.PROP -> "Prop"; PlaceKind.CREATURE -> "Creature"; PlaceKind.NPC -> "One of your NPCs" },
                    if (living) (emoteName ?: p.anim?.let { "animation $it" } ?: "standing") + if (p.loop && (emoteName != null || p.anim != null)) ", held" else "" else null,
                    "locked".takeIf { locked }, "hidden".takeIf { p.hidden },
                ).joinToString("  ·  "),
                style = frizStyle(13.sp, Color.White), maxLines = 2,
            )
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (living) RedButton("Emote", onEmote, Modifier.weight(1f))
                RedButton(if (details) "Hide details" else "Details", onDetails, Modifier.weight(1f))
            }
            if (details) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DarkNumber("X", p.x, Modifier.weight(1f), enabled = !locked) { onChange(p.copy(x = it)) }
                    DarkNumber("Y", p.y, Modifier.weight(1f), enabled = !locked) { onChange(p.copy(y = it)) }
                    DarkNumber("Z", p.z, Modifier.weight(1f), enabled = !locked) { onChange(p.copy(z = it)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DarkNumber("Turn°", yawDegrees(p.yaw), Modifier.weight(1f), decimals = 1, enabled = !locked) { onChange(p.copy(yaw = snap.yaw(yawRadians(it)))) }
                    DarkNumber("Size", p.scale, Modifier.weight(1f), enabled = !locked) { onChange(p.copy(scale = Snap.clampScale(it))) }
                }
                Text("Nudge ${showNumber(snap.step)} yd", style = frizStyle(13.sp))
                val step = snap.step
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextAction("Left", { onNudge(FloorMath.nudge(camera, 0, -1, step)) }, enabled = !locked)
                    TextAction("Right", { onNudge(FloorMath.nudge(camera, 0, 1, step)) }, enabled = !locked)
                    TextAction("Away", { onNudge(FloorMath.nudge(camera, 1, 0, step)) }, enabled = !locked)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextAction("Near", { onNudge(FloorMath.nudge(camera, -1, 0, step)) }, enabled = !locked)
                    TextAction("Up", { onNudge(Vec3(0f, 0f, step)) }, enabled = !locked)
                    TextAction("Down", { onNudge(Vec3(0f, 0f, -step)) }, enabled = !locked)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    WowCheckRow(if (living) "Sound" else PlaceLabels.sound(p.kind), p.sound, onSound)
                    if (living) WowCheckRow("Held", p.loop, onLoop)
                }
            }
        }
    }
}

/** A number typed on the tooltip's dark box: its gold label over a black field; used on Done or when the field is left. */
@Composable
fun DarkNumber(label: String, value: Float, modifier: Modifier = Modifier, decimals: Int = 2, enabled: Boolean = true, onValue: (Float) -> Unit) {
    var text by remember(value) { mutableStateOf(showNumber(value, decimals)) }
    var focused by remember { mutableStateOf(false) }
    fun use() { readNumber(text)?.let { if (it != value) onValue(it) } ?: run { text = showNumber(value, decimals) } }
    val shape = RoundedCornerShape(4.dp)
    Column(modifier) {
        Text(label, style = frizStyle(12.sp), modifier = Modifier.padding(bottom = 2.dp))
        BasicTextField(
            text, { text = it.take(12) }, singleLine = true, enabled = enabled,
            textStyle = frizStyle(15.sp, if (enabled) Color.White else Color(0xFF9D9D9D), false), cursorBrush = SolidColor(War.gold),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { use() }),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).background(Color.Black, shape)
                .border(if (focused) 1.5.dp else 1.dp, if (focused) War.gold else Color(0xFF6E6E6E), shape).padding(horizontal = 10.dp, vertical = 13.dp)
                .onFocusChanged { f -> if (focused != f.isFocused) { focused = f.isFocused; if (!f.isFocused) use() } },
        )
    }
}
