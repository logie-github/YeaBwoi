// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.azuredreams.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** A gold (or, if not [primary], outlined) button in the blocky display font. */
@Composable
fun AdButton(text: String, modifier: Modifier = Modifier, primary: Boolean = true, enabled: Boolean = true, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 0.94f else 1f, spring(dampingRatio = 0.5f), label = "press")
    val shape = RoundedCornerShape(5.dp)
    Box(
        modifier
            .graphicsLayer {
                scaleX = press
                scaleY = press
                alpha = if (enabled) 1f else 0.4f
            }
            .heightIn(min = 44.dp)
            .then(
                if (primary) {
                    Modifier.background(Brush.verticalGradient(listOf(Color(0xFFFFE38A), AdColors.Gold, Color(0xFFD99A1E))), shape)
                } else {
                    Modifier.background(Color(0x33000000), shape)
                }
            )
            .border(2.dp, if (primary) Color(0xFF6B4A00) else AdColors.WindowEdge, shape)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text.uppercase(),
            style = AdDisplayStyle.copy(
                fontSize = 12.sp,
                color = if (primary) AdColors.WindowBottom else AdColors.Text,
                shadow = if (primary) null else AdDisplayStyle.shadow,
            ),
        )
    }
}

/** A setting that's on or off, with a line of explanation under its name. */
@Composable
fun AdSwitchRow(title: String, summary: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
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
                checkedThumbColor = AdColors.WindowBottom,
                checkedTrackColor = AdColors.Gold,
                uncheckedThumbColor = AdColors.TextDim,
                uncheckedTrackColor = Color(0x55000000),
                uncheckedBorderColor = AdColors.TextDim,
            ),
        )
    }
}

/** One choice out of several, with the menu pointer beside the chosen one. */
@Composable
fun AdChoiceRow(title: String, summary: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(30.dp)) {
            if (selected) AdPointer()
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = if (selected) AdColors.Gold else AdColors.Text)
            if (summary != null) Text(summary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** A dialog drawn as a menu window, with a title, scrolling content and buttons at the bottom. */
@Composable
fun AdDialog(
    title: String,
    onDismiss: () -> Unit,
    buttons: @Composable () -> Unit = { AdButton("OK", onClick = onDismiss) },
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        AdWindow(
            Modifier
                .padding(20.dp)
                .widthIn(max = 560.dp)
                .fillMaxWidth(),
            padding = androidx.compose.foundation.layout.PaddingValues(18.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(title.uppercase(), style = MaterialTheme.typography.headlineSmall, color = AdColors.Gold)
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = content,
                )
                Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    buttons()
                }
            }
        }
    }
}

/** Space between a dialog's sections. */
@Composable
fun AdGap() = Spacer(Modifier.padding(2.dp))
