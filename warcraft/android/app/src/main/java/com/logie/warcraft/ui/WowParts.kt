// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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

/**
 * A button in the game's style: the [primary] one gold-lettered on frost, the others plain, both
 * in the window frame.
 */
@Composable
fun WowButton(text: String, modifier: Modifier = Modifier, primary: Boolean = true, enabled: Boolean = true, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 1f else 0f, spring(dampingRatio = 0.7f, stiffness = 900f), label = "press")
    Box(
        modifier
            .graphicsLayer { alpha = if (enabled) 1f else 0.38f }
            .heightIn(min = 44.dp)
            .drawBehind {
                if (primary) {
                    drawRect(Brush.verticalGradient(listOf(WowColors.Frost, WowColors.Deep)))
                    drawRect(WowColors.Ice.copy(alpha = 0.18f * press))
                    drawFrame(alpha = 0.85f + 0.15f * press, arm = 7.dp.toPx())
                } else {
                    drawRect(WowColors.Ice.copy(alpha = 0.10f * press))
                    drawFrame(corner = WowColors.Mist, alpha = 0.7f + 0.3f * press, arm = 7.dp.toPx())
                }
            }
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = WowDisplayStyle.copy(
                fontSize = 14.sp,
                color = if (primary) WowColors.Gold else WowColors.Text,
                shadow = if (primary) WowGoldShadow else null,
            ),
            maxLines = 1,
        )
    }
}

/** A setting that's on or off, with a line of explanation under its name. */
@Composable
fun WowSwitchRow(title: String, summary: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .clickable(enabled = enabled) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (summary != null) Text(summary, style = MaterialTheme.typography.bodySmall)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = WowColors.Glow,
                checkedTrackColor = WowColors.Frost,
                checkedBorderColor = WowColors.Ice,
                uncheckedThumbColor = WowColors.Mist,
                uncheckedTrackColor = Color(0x66000000),
                uncheckedBorderColor = WowColors.Steel,
            ),
        )
    }
}

/** One choice out of several: the chosen one in a gold frame. */
@Composable
fun WowChoiceRow(title: String, summary: String?, selected: Boolean, leading: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                if (selected) {
                    drawRect(WowColors.Ice.copy(alpha = 0.08f))
                    drawFrame(arm = 6.dp.toPx(), line = null)
                }
            }
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = if (selected) WowColors.Glow else WowColors.Text)
            if (summary != null) Text(summary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** A title in Cinzel with a hairline running off to its right. */
@Composable
fun WowTitle(text: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.headlineSmall) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = style, maxLines = 1)
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(Brush.horizontalGradient(listOf(WowColors.Steel, Color.Transparent)))
        )
    }
}

/** A dialog on a dark-ice panel: title, scrolling content, buttons at the bottom. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WowDialog(
    title: String,
    onDismiss: () -> Unit,
    buttons: @Composable () -> Unit = { WowButton("OK", onClick = onDismiss) },
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        WowWindow(
            Modifier
                .padding(20.dp)
                .widthIn(max = 600.dp)
                .fillMaxWidth(),
            padding = PaddingValues(20.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                WowTitle(title)
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = content,
                )
                FlowRow(
                    Modifier.align(Alignment.End),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    buttons()
                }
            }
        }
    }
}

/** How far along something is: a frost line filling up, its leading edge glowing. */
@Composable
fun WowProgressLine(progress: Float, modifier: Modifier = Modifier) {
    Canvas(
        modifier
            .fillMaxWidth()
            .height(10.dp)
    ) {
        val y = size.height / 2
        drawLine(WowColors.Steel, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
        val end = size.width * progress.coerceIn(0f, 1f)
        drawLine(WowColors.Ice, Offset(0f, y), Offset(end, y), 2.dp.toPx())
        drawCircle(
            Brush.radialGradient(listOf(WowColors.Glow, Color.Transparent), center = Offset(end, y), radius = 8.dp.toPx()),
            radius = 8.dp.toPx(),
            center = Offset(end, y),
        )
    }
}

/** A text box: an account name or a password. */
@Composable
fun WowTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (password) KeyboardType.Password else KeyboardType.Ascii,
            autoCorrectEnabled = false,
        ),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = WowColors.Ice,
            unfocusedBorderColor = WowColors.Steel,
            focusedLabelColor = WowColors.Ice,
            unfocusedLabelColor = WowColors.Mist,
            cursorColor = WowColors.Ice,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

/** The end of a log, in a fixed-width font, scrolling sideways for long lines. */
@Composable
fun WowLog(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.45f))
            .horizontalScroll(rememberScrollState())
            .padding(10.dp)
    ) {
        Text(text, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp, color = WowColors.Mist))
    }
}

/** A line of a status: a name and its value, the value lit when it's [good]. */
@Composable
fun WowStatusLine(name: String, value: String, good: Boolean? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = MaterialTheme.typography.bodyMedium, color = WowColors.Mist, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = when (good) {
                true -> WowColors.Good
                false -> WowColors.Blood
                null -> WowColors.Text
            },
        )
    }
}
