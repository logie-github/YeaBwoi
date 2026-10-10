// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.pe.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * A button: lowercase, spread-out letters in a thin frame. The [primary] one is lit teal, the
 * others are bare outlines.
 */
@Composable
fun PeButton(text: String, modifier: Modifier = Modifier, primary: Boolean = true, enabled: Boolean = true, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 1f else 0f, spring(dampingRatio = 0.7f, stiffness = 900f), label = "press")
    Box(
        modifier
            .graphicsLayer { alpha = if (enabled) 1f else 0.38f }
            .heightIn(min = 44.dp)
            .drawBehind {
                val fill = if (primary) 0.20f + 0.18f * press else 0.10f * press
                drawRect(PeColors.Teal.copy(alpha = fill))
                if (primary) {
                    drawBrackets(PeColors.Glow, arm = 7.dp.toPx())
                } else {
                    drawBrackets(PeColors.Mist, alpha = 0.7f + 0.3f * press, arm = 7.dp.toPx())
                }
            }
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text.lowercase(),
            style = PeDisplayStyle.copy(
                fontSize = 14.sp,
                color = if (primary) PeColors.Glow else PeColors.Text,
                letterSpacing = PeDisplayStyle.letterSpacing * 0.7f,
                shadow = if (primary) PeGlow else null,
            ),
            maxLines = 1,
        )
    }
}

/** A choice among a few, side by side: the chosen one in its brackets. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> PeSegments(options: List<Pair<T, String>>, selected: T, enabled: Boolean = true, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((value, label) in options) {
            PeButton(label, primary = value == selected, enabled = enabled) { onSelect(value) }
        }
    }
}

/** A setting that's on or off, with a line of explanation under its name. */
@Composable
fun PeSwitchRow(title: String, summary: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (summary != null) Text(summary, style = MaterialTheme.typography.bodySmall)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = PeColors.Glow,
                checkedTrackColor = PeColors.TealDeep,
                checkedBorderColor = PeColors.Teal,
                uncheckedThumbColor = PeColors.Mist,
                uncheckedTrackColor = Color(0x66000000),
                uncheckedBorderColor = PeColors.Steel,
            ),
        )
    }
}

/** One choice out of several: the chosen one glows in its brackets. */
@Composable
fun PeChoiceRow(title: String, summary: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .drawBehind { if (selected) drawBrackets(PeColors.Teal, arm = 6.dp.toPx()) }
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = if (selected) PeColors.Glow else PeColors.Text)
            if (summary != null) Text(summary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * A line of a menu, in the display letters: the [highlighted] one, and whichever is being touched,
 * glows between brackets, `[ resume ]`.
 */
@Composable
fun PeMenuLine(text: String, highlighted: Boolean = false, detail: String? = null, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    var touched by remember { mutableStateOf(false) }
    LaunchedEffect(interaction) {
        interaction.interactions.collect { touched = it is PressInteraction.Press }
    }
    val lit = highlighted || touched
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            Text(
                text.lowercase(),
                style = PeDisplayStyle.copy(
                    fontSize = 17.sp,
                    color = if (lit) PeColors.Glow else PeColors.Text,
                    shadow = if (lit) PeGlow else null,
                ),
                maxLines = 1,
                modifier = Modifier
                    .drawBehind { if (lit) drawBrackets(PeColors.Teal, arm = 6.dp.toPx()) }
                    .padding(horizontal = 14.dp, vertical = 5.dp),
            )
        }
        if (detail != null) {
            Text(
                detail.lowercase(),
                style = PeDisplayStyle.copy(fontSize = 13.sp, color = PeColors.Mist),
                modifier = Modifier.padding(end = 6.dp),
            )
        }
    }
}

/**
 * A row of small choices after a label, like `speed   1×  [2×]  3×`: the chosen one between
 * brackets.
 */
@Composable
fun <T> PeInlineChoice(label: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(end = 4.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label.lowercase(),
            style = PeDisplayStyle.copy(fontSize = 17.sp),
            maxLines = 1,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 14.dp),
        )
        for ((value, text) in options) {
            val chosen = value == selected
            Text(
                text,
                style = PeDisplayStyle.copy(
                    fontSize = 15.sp,
                    letterSpacing = PeDisplayStyle.letterSpacing * 0.4f,
                    color = if (chosen) PeColors.Glow else PeColors.Mist,
                    shadow = if (chosen) PeGlow else null,
                ),
                modifier = Modifier
                    .drawBehind { if (chosen) drawBrackets(PeColors.Teal, arm = 4.dp.toPx()) }
                    .clickable { onSelect(value) }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
            )
        }
    }
}

/** A title in the display letters, lowercase, with a hairline running off to its right. */
@Composable
fun PeTitle(text: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.headlineSmall) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(text.lowercase(), style = style, maxLines = 1)
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(PeColors.Steel)
        )
    }
}

/** A dialog on a night-glass panel, with a title, scrolling content and buttons at the bottom. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PeDialog(
    title: String,
    onDismiss: () -> Unit,
    buttons: @Composable () -> Unit = { PeButton("OK", onClick = onDismiss) },
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        PeWindow(
            Modifier
                .padding(20.dp)
                .widthIn(max = 560.dp)
                .fillMaxWidth(),
            padding = PaddingValues(20.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                PeTitle(title)
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
