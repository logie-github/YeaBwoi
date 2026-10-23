// SPDX-License-Identifier: GPL-3.0-or-later
package com.logie.warcraft.tww.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.logie.warcraft.ui.WowDisplayStyle
import com.logie.warcraft.ui.drawFrame

internal object TwwColors {
    val Night = Color(0xFF09080B)
    val Panel = Color(0xFF211B20)
    val Gold = Color(0xFFE6BA73)
    val Bronze = Color(0xFF755B3E)
    val Text = Color(0xFFF1E7D8)
    val Muted = Color(0xFFB7AA9C)
    val Good = Color(0xFFA6CEA7)
    val Error = Color(0xFFF08F83)
}

/** The same bracketed windows and Cinzel controls as the other realm launchers. */
@Composable
internal fun TwwWindow(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.drawBehind {
        drawRect(Brush.verticalGradient(listOf(TwwColors.Panel.copy(alpha = .96f), TwwColors.Night.copy(alpha = .97f))))
        drawFrame(corner = TwwColors.Gold, line = TwwColors.Bronze, alpha = .8f)
    }.then(if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@Composable
internal fun TwwButton(text: String, modifier: Modifier = Modifier, primary: Boolean = true, enabled: Boolean = true, onClick: () -> Unit) {
    Box(modifier.heightIn(min = 48.dp).graphicsLayer { alpha = if (enabled) 1f else .4f }
        .drawBehind {
            if (primary) drawRect(Brush.verticalGradient(listOf(Color(0xFF59412B), Color(0xFF2A201C))))
            drawFrame(corner = if (primary) TwwColors.Gold else TwwColors.Bronze, line = TwwColors.Bronze)
        }.clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 13.dp), contentAlignment = Alignment.Center) {
        Text(text, style = WowDisplayStyle.copy(fontSize = 14.sp, color = if (primary) TwwColors.Gold else TwwColors.Text))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TwwDialog(title: String, onDismiss: () -> Unit, buttons: @Composable () -> Unit = { TwwButton("Close", primary = false, onClick = onDismiss) }, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        TwwWindow(Modifier.padding(20.dp).widthIn(max = 600.dp).fillMaxWidth()) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = TwwColors.Gold)
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
            FlowRow(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { buttons() }
        }
    }
}

@Composable
internal fun TwwStatus(label: String, value: String, good: Boolean? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = TwwColors.Muted, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = when (good) { true -> TwwColors.Good; false -> TwwColors.Error; null -> TwwColors.Text })
    }
}
