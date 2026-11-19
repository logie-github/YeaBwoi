package com.logie.wifecraft.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.logie.wifecraft.creator.Appearance
import com.logie.wifecraft.creator.Catalog

// The story editor's screens, in WoW's own interface: metal frames, red buttons, gold words.
fun defaultAppearance(c: Catalog): Appearance? {
    val race = c.races.firstOrNull() ?: return null
    val sex = race.sexes.firstOrNull { it.name == "FEMALE" } ?: race.sexes.firstOrNull() ?: return null
    return Appearance(race.id, sex)
}

fun plural(n: Int, word: String): String = "$n $word" + if (n == 1) "" else "s"

// ------------------------------------------------------------------------ the editor's pieces
// In the build-mode look (WarWidgets): the warm dark ground, rounded glass panels, wood pills with gold words, cream fields.

/** A full-screen window: Back and the title on its ribbon in the top bar, [actions] beside them, the content on warm glass. */
@Composable
fun EditorFrame(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFF2A1C12), Color(0xFF140D08))))) {
        Column(Modifier.fillMaxSize()) {
            TopBar(title, onBack, actions = actions)
            GlassPanel(Modifier.weight(1f).fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp), corbel = false) {
                Box(Modifier.fillMaxSize().padding(8.dp)) { content() }
            }
        }
    }
}

/** A row on a list over the dark glass: warm, rounded, gold-edged, lit when [selected]. */
@Composable
fun EditorCard(onClick: () -> Unit, selected: Boolean = false, content: @Composable RowScope.() -> Unit) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(WarSize.R_CARD.dp)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .background(if (selected) Color(0x66E3A33B) else Color(0x803A2414), shape)
            .border(if (selected) 2.dp else 1.dp, if (selected) War.gold else Color(0x88B08850), shape)
            .warPress(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
fun SmallButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, width: androidx.compose.ui.unit.Dp = 104.dp) =
    PillButton(text, onClick, modifier.widthIn(min = width), enabled)

/** A line of text the author types, in a cream field. */
@Composable
fun EditorField(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier, singleLine: Boolean = true) =
    WarField(value, onChange, hint, modifier.fillMaxWidth(), singleLine = singleLine)

/** A name to type, in a small WoW window over the screen. */
@Composable
fun NameDialog(title: String, initial: String, ok: String, onCancel: () -> Unit, onOk: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    Overlay(onCancel) {
        WowWindow(title, onCancel, Modifier.widthIn(max = 480.dp).padding(16.dp)) {
            WowTextField(text, { text = it.take(60) }, "Name", Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RedButton("Cancel", onCancel, Modifier.weight(1f))
                RedButton(ok, { onOk(text) }, Modifier.weight(1f))
            }
        }
    }
}

/** A question in a small WoW window: Cancel and [ok]. */
@Composable
fun ConfirmDialog(question: String, ok: String, onCancel: () -> Unit, onOk: () -> Unit) {
    Overlay(onCancel) {
        WowWindow(if (ok == "Delete") "Delete" else ok, onCancel, Modifier.widthIn(max = 480.dp).padding(16.dp)) {
            Text(question, style = frizStyle(15.sp, Color.White), modifier = Modifier.padding(vertical = 6.dp))
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RedButton("Cancel", onCancel, Modifier.weight(1f))
                RedButton(ok, onOk, Modifier.weight(1f))
            }
        }
    }
}

/** A list of (id, label) to pick one from, in a WoW list window over the screen. */
@Composable
fun PickDialog(title: String, items: List<Pair<String, String>>, current: String?, onCancel: () -> Unit, onPick: (String) -> Unit) {
    Overlay(onCancel) {
        WowWindow(title, onCancel, Modifier.widthIn(max = 520.dp).heightIn(max = 520.dp).padding(16.dp)) {
            LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(items, key = { it.first }) { (id, label) ->
                    WowListRow(label, null, selected = id == current, onClick = { onPick(id) })
                }
            }
        }
    }
}

/** A labelled setting that shows its value and opens a [PickDialog]. */
@Composable
fun PickRow(label: String, items: List<Pair<String, String>>, current: String?, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = frizStyle(14.sp), modifier = Modifier.width(86.dp))
        val shape = androidx.compose.foundation.shape.RoundedCornerShape(WarSize.R_SMALL.dp)
        Box(
            Modifier.weight(1f).heightIn(min = 48.dp).background(War.cream, shape).border(1.dp, War.edge, shape).warPress { open = true }.padding(12.dp),
        ) {
            Text((items.firstOrNull { it.first == current }?.second ?: current ?: "").ifEmpty { "Choose" }, style = warText(15.sp), maxLines = 1)
        }
    }
    if (open) PickDialog(label, items, current, { open = false }) { open = false; onPick(it) }
}
