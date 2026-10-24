// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.felsong.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** A panel like felsong.gg's: near-black, see-through, in its faint fel frame. */
@Composable
fun FelsongWindow(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(16.dp),
    opaque: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val lit by animateFloatAsState(if (pressed) 1f else 0f, spring(dampingRatio = 0.8f, stiffness = 900f), label = "lit")
    var clickable: Modifier = Modifier
    if (onClick != null) clickable = Modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick)
    Box(
        modifier
            .drawBehind {
                val see = if (opaque) 1f else 0.82f
                drawRect(Brush.verticalGradient(listOf(FelsongColors.Night.copy(alpha = see), FelsongColors.Soot.copy(alpha = maxOf(see, 0.9f)))))
                if (lit > 0f) drawRect(Brush.radialGradient(listOf(FelsongColors.Fel.copy(alpha = 0.16f * lit), Color.Transparent)))
                drawFelsongFrame(lit = lit)
            }
            .then(clickable)
            .padding(padding),
        content = content,
    )
}

/**
 * A button as on felsong.gg: the [primary] one's capitals lettered white to pale fel on a dark plate
 * in a fel frame; the others plain sage lettering in a faint one.
 */
@Composable
fun FelsongButton(text: String, modifier: Modifier = Modifier, primary: Boolean = true, enabled: Boolean = true, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 1f else 0f, spring(dampingRatio = 0.7f, stiffness = 900f), label = "press")
    Box(
        modifier
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .heightIn(min = 44.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .drawBehind {
                if (primary) {
                    drawRect(Brush.verticalGradient(listOf(FelsongColors.Moss, FelsongColors.Night)))
                    drawRect(FelsongColors.Lime.copy(alpha = 0.12f * press))
                    drawRect(FelsongColors.Fel.copy(alpha = 0.7f), style = Stroke(1.3.dp.toPx()))
                } else {
                    drawRect(FelsongColors.Fel.copy(alpha = 0.08f * press))
                    drawRect(FelsongColors.Sage.copy(alpha = 0.5f), style = Stroke(1.dp.toPx()))
                }
            }
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text.uppercase(),
            style = if (primary) FelsongGradientText.copy(fontSize = 14.sp, shadow = FelsongShadow) else FelsongDisplayStyle.copy(fontSize = 14.sp, color = FelsongColors.Lichen),
            maxLines = 1,
        )
    }
}

/** A title in the site's capitals, white to pale fel, with a fel rule fading out to its right. */
@Composable
fun FelsongTitle(text: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.headlineSmall) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = style.copy(brush = FelsongColors.WhiteFel, shadow = FelsongGlow), maxLines = 1)
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f).height(1.dp).background(Brush.horizontalGradient(listOf(FelsongColors.Fel.copy(alpha = 0.6f), Color.Transparent))))
    }
}

/** A choice: its name, and a line under it. */
@Composable
fun FelsongChoiceRow(title: String, summary: String?, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = FelsongColors.Bone)
        if (summary != null) Text(summary, style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FelsongDialog(
    title: String,
    onDismiss: () -> Unit,
    buttons: @Composable () -> Unit = { FelsongButton("OK", onClick = onDismiss) },
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        FelsongWindow(Modifier.padding(20.dp).widthIn(max = 620.dp).fillMaxWidth(), padding = PaddingValues(20.dp), opaque = true) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                FelsongTitle(title)
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
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
