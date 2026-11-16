package com.logie.wifecraft.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.logie.wifecraft.editor.PlaceKind
import com.logie.wifecraft.editor.Placement

/**
 * Objects in this scene (the outliner, opened from More): a WoW list window with every placed thing in the order it was
 * added (the name in gold, greyed when hidden; what it is in white). A tap selects it and the camera looks at it (a tap on the
 * selected one deselects); the selected row's own line holds Hide (Show), Lock (Unlock), Rename and Look at it, as text
 * buttons. Show hidden things is the check box on top. Its list scrolls inside the window.
 */
@Composable
fun ObjectsWindow(
    placements: List<Placement>,
    ui: PlaceUi,
    onSelect: (String?) -> Unit,
    onHide: (String) -> Unit,
    onLock: (String) -> Unit,
    onRename: (String) -> Unit,
    onLookAt: (Placement) -> Unit,
    onShowHidden: () -> Unit,
    nameOf: (actorId: String) -> String?,
    onClose: () -> Unit,
    width: Dp,
    height: Dp,
) {
    Overlay(onClose) {
        WowWindow("Objects in this scene", onClose, Modifier.width(width).height(height)) {
            WowCheckRow("Show hidden things", ui.showHidden, { onShowHidden() }, detail = plural(placements.size, "placed thing"))
            if (placements.isEmpty()) {
                WowNote("Nothing is placed yet. Add (on the action bar) opens the Library: props, buildings, people.", Modifier.padding(6.dp))
                return@WowWindow
            }
            val list = rememberLazyListState()
            val at = placements.indexOfFirst { it.id == ui.selected }
            LaunchedEffect(at) { if (at >= 0) list.animateScrollToItem(at) }
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).warScrollbar(list), list, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(placements, key = { it.id }) { p ->
                    val sel = p.id == ui.selected
                    WowListRow(
                        p.name,
                        listOfNotNull(
                            when (p.kind) {
                                PlaceKind.PROP -> "Prop (file ${p.asset})"
                                PlaceKind.CREATURE -> "Creature (display ${p.asset})"
                                PlaceKind.NPC -> "NPC: " + (p.actorId?.let(nameOf) ?: p.actorId ?: "?")
                            },
                            p.anim?.let { "animation $it" + if (p.loop) ", held" else "" },
                            "hidden".takeIf { p.hidden }, "locked".takeIf { p.locked },
                            "muted".takeIf { !p.sound && p.kind != PlaceKind.PROP },
                        ).joinToString(" · "),
                        selected = sel,
                        onClick = { onSelect(if (sel) null else p.id) },
                        modifier = Modifier.padding(end = 10.dp),
                        icon = when (p.kind) { PlaceKind.PROP -> "tab.props"; PlaceKind.CREATURE -> "tab.creatures"; PlaceKind.NPC -> "tab.npcs" },
                        dim = p.hidden,
                        context = {
                            TextAction(if (p.hidden) "Show" else "Hide", { onHide(p.id) })
                            TextAction(if (p.locked) "Unlock" else "Lock", { onLock(p.id) })
                            TextAction("Rename", { onRename(p.id) })
                            TextAction("Look at it", { onLookAt(p) })
                        },
                    )
                }
            }
        }
    }
}

