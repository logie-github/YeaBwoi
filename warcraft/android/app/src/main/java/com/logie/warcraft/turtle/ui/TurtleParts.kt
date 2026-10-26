// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.turtle.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** A panel like the site's cards: dark, a little see-through, in its steel frame. */
@Composable
fun TurtleWindow(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    padding: PaddingValues = PaddingValues(16.dp),
    opaque: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val lit by animateFloatAsState(if (pressed) 1f else 0f, spring(dampingRatio = 0.8f, stiffness = 900f), label = "lit")
    var clickable: Modifier = Modifier
    if (onClick != null) clickable = Modifier.clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
    Box(
        modifier
            .graphicsLayer { alpha = if (enabled) 1f else 0.42f }
            .drawBehind {
                val see = if (opaque) 1f else 0.88f
                drawRect(Brush.verticalGradient(listOf(TurtleColors.Earth.copy(alpha = see), TurtleColors.Bark.copy(alpha = maxOf(see, 0.94f)))))
                // The site's cards: a faint violet haze in their upper corner.
                drawRect(Brush.radialGradient(listOf(TurtleColors.Dusk.copy(alpha = 0.35f), Color.Transparent), center = Offset(size.width * 0.15f, 0f), radius = size.maxDimension * 0.7f))
                if (lit > 0f) drawRect(Brush.radialGradient(listOf(TurtleColors.Orange.copy(alpha = 0.2f * lit), Color.Transparent)))
                drawTurtleFrame(lit = lit)
            }
            .then(clickable)
            .padding(padding),
        content = content,
    )
}

/**
 * A button as on octowow.st: capitals on a smoky dark plate in a steel bevel. The [primary] one is
 * the site's Download button, an ember glow rising from its lower edge, orange capitals, a steel
 * diamond at each end; [green] makes it its Sign up button instead; the others are plain plates.
 */
@Composable
fun TurtleButton(text: String, modifier: Modifier = Modifier, primary: Boolean = true, enabled: Boolean = true, green: Boolean = false, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 1f else 0f, spring(dampingRatio = 0.7f, stiffness = 900f), label = "press")
    val glow = if (green) TurtleColors.Moss else TurtleColors.Coral
    Box(
        modifier
            .graphicsLayer { alpha = if (enabled) 1f else 0.38f }
            .heightIn(min = 44.dp)
            .padding(horizontal = if (primary) 6.dp else 0.dp)
            .drawBehind {
                val px = 1.dp.toPx()
                drawRect(Brush.verticalGradient(listOf(Color(0xFF2E2925), Color(0xFF15110F))))
                if (primary) {
                    drawRect(Brush.verticalGradient(listOf(Color.Transparent, glow.copy(alpha = 0.32f + 0.25f * press)), startY = size.height * 0.35f))
                    drawLine(glow, Offset(px, size.height - 1.5f * px), Offset(size.width - px, size.height - 1.5f * px), 1.6f * px)
                } else {
                    drawRect(TurtleColors.Gold.copy(alpha = 0.08f * press))
                }
                drawRect(TurtleColors.SteelDark, style = Stroke(2.4f * px))
                drawRect(
                    Brush.verticalGradient(listOf(TurtleColors.SteelLight, TurtleColors.Steel.copy(alpha = 0.6f), TurtleColors.SteelDark)),
                    topLeft = Offset(1.2f * px, 1.2f * px),
                    size = Size(size.width - 2.4f * px, size.height - 2.4f * px),
                    style = Stroke(1f * px),
                    alpha = if (primary) 0.9f else 0.55f,
                )
                if (primary) {
                    // The steel diamonds at the ends, edged with the button's own colour.
                    val r = 6 * px
                    for (cx in listOf(0f, size.width)) {
                        val cy = size.height / 2
                        val outer = Path().apply { moveTo(cx, cy - r); lineTo(cx + r, cy); lineTo(cx, cy + r); lineTo(cx - r, cy); close() }
                        drawPath(outer, glow)
                        val k = 0.68f
                        val inner = Path().apply { moveTo(cx, cy - r * k); lineTo(cx + r * k, cy); lineTo(cx, cy + r * k); lineTo(cx - r * k, cy); close() }
                        drawPath(inner, Brush.verticalGradient(listOf(TurtleColors.SteelLight, TurtleColors.Steel), startY = cy - r, endY = cy + r))
                    }
                }
            }
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text.uppercase(),
            style = TurtleDisplayStyle.copy(
                fontSize = 14.sp,
                color = when {
                    !primary -> TurtleColors.Parchment
                    green -> TurtleColors.Moss
                    else -> TurtleColors.Orange
                },
                shadow = TurtleShadow,
            ),
            maxLines = 1,
        )
    }
}

/** A heading as the site's (Almendra, gold to ember), with an ember rule fading out to its right. */
@Composable
fun TurtleTitle(text: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.headlineSmall) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = style.copy(brush = TurtleColors.GoldCoral, shadow = TurtleShadow), maxLines = 1)
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f).height(1.dp).background(Brush.horizontalGradient(listOf(TurtleColors.Coral.copy(alpha = 0.7f), Color.Transparent))))
    }
}

@Composable
fun TurtleSwitchRow(title: String, summary: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().graphicsLayer { alpha = if (enabled) 1f else 0.45f }.clickable(enabled = enabled) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (summary != null) Text(summary, style = MaterialTheme.typography.bodySmall)
        }
        TurtleSwitch(checked, enabled, onChange)
    }
}

/** An on/off switch in the site's gold. */
@Composable
fun TurtleSwitch(checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = TurtleColors.Gold,
            checkedTrackColor = TurtleColors.Brown,
            checkedBorderColor = TurtleColors.Orange,
            uncheckedThumbColor = TurtleColors.Stone,
            uncheckedTrackColor = Color(0x66000000),
            uncheckedBorderColor = TurtleColors.Dust,
        ),
    )
}

@Composable
fun TurtleChoiceRow(title: String, summary: String?, selected: Boolean, leading: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .drawBehind { if (selected) { drawRect(TurtleColors.Gold.copy(alpha = 0.07f)); drawTurtleFrame() } }
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = if (selected) TurtleColors.Gold else TurtleColors.Parchment)
            if (summary != null) Text(summary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TurtleDialog(
    title: String,
    onDismiss: () -> Unit,
    buttons: @Composable () -> Unit = { TurtleButton("OK", onClick = onDismiss) },
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        TurtleWindow(Modifier.padding(20.dp).widthIn(max = 620.dp).fillMaxWidth(), padding = PaddingValues(20.dp), opaque = true) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                TurtleTitle(title)
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = content,
                )
                FlowRow(
                    Modifier.align(Alignment.End),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) { buttons() }
            }
        }
    }
}

/** How far along: an orange line filling up, its leading edge glowing gold. */
@Composable
fun TurtleProgressLine(progress: Float, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(10.dp)) {
        val y = size.height / 2
        drawLine(TurtleColors.SteelDark, Offset(0f, y), Offset(size.width, y), 2.dp.toPx())
        val end = size.width * progress.coerceIn(0f, 1f)
        drawLine(Brush.horizontalGradient(listOf(TurtleColors.Coral, TurtleColors.Gold), endX = end.coerceAtLeast(1f)), Offset(0f, y), Offset(end, y), 2.4.dp.toPx())
        drawCircle(Brush.radialGradient(listOf(TurtleColors.Gold, Color.Transparent), center = Offset(end, y), radius = 8.dp.toPx()), radius = 8.dp.toPx(), center = Offset(end, y))
    }
}

@Composable
fun TurtleTextField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, password: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else KeyboardType.Ascii, autoCorrectEnabled = false),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = TurtleColors.Gold,
            unfocusedBorderColor = TurtleColors.Dust,
            focusedLabelColor = TurtleColors.Gold,
            unfocusedLabelColor = TurtleColors.Stone,
            cursorColor = TurtleColors.Gold,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
fun TurtleLog(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.5f)).horizontalScroll(rememberScrollState()).padding(10.dp)) {
        Text(text, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp, color = TurtleColors.Stone))
    }
}

/** A name and its value, the value green when [good], red when not. */
@Composable
fun TurtleStatusLine(name: String, value: String, good: Boolean? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = MaterialTheme.typography.bodyMedium, color = TurtleColors.Stone, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = when (good) {
                true -> TurtleColors.Leaf
                false -> TurtleColors.Blood
                null -> TurtleColors.Parchment
            },
        )
    }
}

/** A small round light: green done, gold working or to do, red failed, grey waiting. */
@Composable
fun TurtleDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(10.dp).clip(CircleShape).background(color))
}
