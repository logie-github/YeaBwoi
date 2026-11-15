package com.logie.wifecraft.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.logie.wifecraft.editor.PlaceKind
import com.logie.wifecraft.editor.Placement

/**
 * The outliner: every placed thing of the scene in the order it was added, what it is, and its switches: Hide (off the
 * editor's view; Play still shows it), Lock (selected but not moved), Rename, Look (the camera turns to it). The list scrolls
 * inside its panel.
 */
@Composable
fun PlaceOutliner(
    placements: List<Placement>,
    ui: PlaceUi,
    onSelect: (String?) -> Unit,
    onHide: (String) -> Unit,
    onLock: (String) -> Unit,
    onRename: (String) -> Unit,
    onLookAt: (Placement) -> Unit,
    nameOf: (actorId: String) -> String?,
) {
    if (placements.isEmpty()) {
        Text("Nothing is placed yet. Add opens the library: the starter pack, props, buildings, creatures and your NPCs.",
            style = frizStyle(14.sp, WowColors.grey, false), modifier = Modifier.padding(6.dp))
        return
    }
    val list = rememberLazyListState()
    val at = placements.indexOfFirst { it.id == ui.selected }
    LaunchedEffect(at) { if (at >= 0) list.animateScrollToItem(at) }
    LazyColumn(Modifier.fillMaxSize(), list, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(placements, key = { it.id }) { p ->
            val sel = p.id == ui.selected
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                EditorCard(onClick = { onSelect(if (sel) null else p.id) }, selected = sel) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = frizStyle(15.sp, if (p.id in ui.hidden) WowColors.grey else Color.White), maxLines = 1)
                        Text(
                            listOfNotNull(
                                when (p.kind) {
                                    PlaceKind.PROP -> "Prop (file ${p.asset})"
                                    PlaceKind.CREATURE -> "Creature (display ${p.asset})"
                                    PlaceKind.NPC -> "NPC: " + (p.actorId?.let(nameOf) ?: p.actorId ?: "?")
                                },
                                p.anim?.let { "animation $it" + if (p.loop) ", held" else "" },
                                "hidden".takeIf { p.id in ui.hidden },
                                "locked".takeIf { p.id in ui.locked },
                                "muted".takeIf { !p.sound && p.kind != PlaceKind.PROP },
                            ).joinToString(" · "),
                            style = frizStyle(12.sp, WowColors.grey, false), maxLines = 1,
                        )
                    }
                }
                if (sel) Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    PToggle("Hide", p.id in ui.hidden, { onHide(p.id) }, Modifier.weight(1f), width = 80.dp)
                    PToggle("Lock", p.id in ui.locked, { onLock(p.id) }, Modifier.weight(1f), width = 80.dp)
                    PButton("Rename", { onRename(p.id) }, Modifier.weight(1.3f), width = 96.dp)
                    PButton("Look", { onLookAt(p) }, Modifier.weight(1f), width = 80.dp)
                }
            }
        }
    }
}
