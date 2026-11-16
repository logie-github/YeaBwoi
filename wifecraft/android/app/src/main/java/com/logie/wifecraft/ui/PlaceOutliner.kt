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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.logie.wifecraft.editor.PlaceKind
import com.logie.wifecraft.editor.Placement

/**
 * The outliner (the Things page): every placed thing of the scene in the order it was added, as parchment rows with what
 * it is (a tap selects it and the editor's camera looks at it; a tap on the selected one deselects), and for the selected
 * one its switches: Hide (off the editor's view; Play still shows it), Lock (selected but not moved), Rename, Look (the
 * camera turns to it). The list scrolls inside its page.
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
        Text("Nothing is placed yet. Pick a card in the catalogue below: the starter pack, props, buildings, creatures and your NPCs.",
            style = warText(14.sp, War.brownSoft), modifier = Modifier.padding(6.dp))
        return
    }
    val list = rememberLazyListState()
    val at = placements.indexOfFirst { it.id == ui.selected }
    LaunchedEffect(at) { if (at >= 0) list.animateScrollToItem(at) }
    LazyColumn(Modifier.fillMaxSize().warScrollbar(list), list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(placements, key = { it.id }) { p ->
            val sel = p.id == ui.selected
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(end = 10.dp)) {
                WarRow(onClick = { onSelect(if (sel) null else p.id) }, selected = sel, icon = when (p.kind) {
                    PlaceKind.PROP -> "tab.props"; PlaceKind.CREATURE -> "tab.creatures"; PlaceKind.NPC -> "tab.npcs"
                }) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = warText(15.sp, if (p.hidden) War.muted else War.brown), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(
                                when (p.kind) {
                                    PlaceKind.PROP -> "Prop (file ${p.asset})"
                                    PlaceKind.CREATURE -> "Creature (display ${p.asset})"
                                    PlaceKind.NPC -> "NPC: " + (p.actorId?.let(nameOf) ?: p.actorId ?: "?")
                                },
                                p.anim?.let { "animation $it" + if (p.loop) ", held" else "" },
                                "hidden".takeIf { p.hidden },
                                "locked".takeIf { p.locked },
                                "muted".takeIf { !p.sound && p.kind != PlaceKind.PROP },
                            ).joinToString(" · "),
                            style = warText(12.sp, War.brownSoft), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (sel) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    RoundIconButton(Face(icon = "tool.hide"), { onHide(p.id) }, size = 48.dp, lit = p.hidden, label = if (p.hidden) "Show" else "Hide", onLight = true)
                    RoundIconButton(Face(glyph = WarIcons.LOCK), { onLock(p.id) }, size = 48.dp, lit = p.locked, label = if (p.locked) "Unlock" else "Lock", onLight = true)
                    RoundIconButton(Face(icon = "menu.project"), { onRename(p.id) }, size = 48.dp, label = "Rename", onLight = true)
                    RoundIconButton(Face(icon = "tool.focus"), { onLookAt(p) }, size = 48.dp, label = "Look", onLight = true)
                }
            }
        }
    }
}
