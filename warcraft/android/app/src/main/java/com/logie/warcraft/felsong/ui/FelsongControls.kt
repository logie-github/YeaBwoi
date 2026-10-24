// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.felsong.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

// The launcher's controls in the Legion game's look (its panels, buttons and dialog are in FelsongParts).

@Composable
fun FelsongSwitchRow(title: String, summary: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().graphicsLayer { alpha = if (enabled) 1f else 0.45f }.clickable(enabled = enabled) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (summary != null) Text(summary, style = MaterialTheme.typography.bodySmall)
        }
        FelsongSwitch(checked, enabled, onChange)
    }
}

/** An on/off switch in fel. */
@Composable
fun FelsongSwitch(checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = FelsongColors.Soot,
            checkedTrackColor = FelsongColors.Lime,
            uncheckedThumbColor = FelsongColors.Sage,
            uncheckedTrackColor = FelsongColors.Night,
            uncheckedBorderColor = FelsongColors.Sage,
        ),
    )
}

/** A choice with a mark when it's the one chosen, and something before it if asked. */
@Composable
fun FelsongChoiceRow(title: String, summary: String?, selected: Boolean, leading: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = if (selected) FelsongColors.Lime else FelsongColors.Bone)
            if (summary != null) Text(summary, style = MaterialTheme.typography.bodySmall)
        }
        if (selected) FelsongDot(FelsongColors.Lime, Modifier.padding(start = 10.dp))
    }
}

/** How far along: a fel line filling up, its leading edge glowing lime. */
@Composable
fun FelsongProgressLine(progress: Float, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(10.dp)) {
        val y = size.height / 2
        drawLine(FelsongColors.Moss, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
        val end = size.width * progress.coerceIn(0f, 1f)
        drawLine(Brush.horizontalGradient(listOf(FelsongColors.Fel, FelsongColors.Lime), endX = end.coerceAtLeast(1f)), Offset(0f, y), Offset(end, y), 2.4.dp.toPx())
        drawCircle(Brush.radialGradient(listOf(FelsongColors.Lime, Color.Transparent), center = Offset(end, y), radius = 8.dp.toPx()), radius = 8.dp.toPx(), center = Offset(end, y))
    }
}

@Composable
fun FelsongTextField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, password: Boolean = false, email: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = when {
                password -> KeyboardType.Password
                email -> KeyboardType.Email
                else -> KeyboardType.Ascii
            },
            autoCorrectEnabled = false,
        ),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = FelsongColors.Lime,
            unfocusedBorderColor = FelsongColors.Sage,
            focusedLabelColor = FelsongColors.Lime,
            unfocusedLabelColor = FelsongColors.Sage,
            cursorColor = FelsongColors.Lime,
            focusedTextColor = FelsongColors.Bone,
            unfocusedTextColor = FelsongColors.Bone,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
fun FelsongLog(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.5f)).horizontalScroll(rememberScrollState()).padding(10.dp)) {
        Text(text, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp, color = FelsongColors.Sage))
    }
}

/** A name and its value, the value lime when [good], red when not. */
@Composable
fun FelsongStatusLine(name: String, value: String, good: Boolean? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = MaterialTheme.typography.bodyMedium, color = FelsongColors.Sage, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = when (good) {
                true -> FelsongColors.Lime
                false -> FelsongColors.Blood
                null -> FelsongColors.Bone
            },
        )
    }
}

/** A small round light: lime done, ember working or to do, red failed, grey waiting. */
@Composable
fun FelsongDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(10.dp).clip(CircleShape).background(color))
}
