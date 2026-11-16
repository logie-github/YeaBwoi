package com.logie.wifecraft.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.logie.wifecraft.creator.Catalog
import com.logie.wifecraft.editor.Project

/** The cast: every NPC as a card (name, look), with New NPC, Edit, Duplicate and Delete. */
@Composable
fun EditorCast(
    project: Project,
    catalog: Catalog,
    state: EditorState,
    onNew: () -> Unit,
    onEdit: (actorId: String) -> Unit,
    onScenes: () -> Unit,
    onBack: () -> Unit,
) {
    var deleting by remember { mutableStateOf<String?>(null) }
    // a tap picks an NPC (its line: Edit, Duplicate, Delete); a tap on the picked one opens it in the creator
    var picked by remember { mutableStateOf<String?>(null) }
    EditorFrame(
        "${project.title}: Cast", onBack,
        actions = { RoundIconButton(Face(icon = "menu.scene"), onScenes, description = "Scenes") },
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (project.cast.isEmpty()) {
                Text("Nobody yet. Make your first NPC.", style = frizStyle(16.sp, WowColors.grey), modifier = Modifier.padding(8.dp))
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(project.cast, key = { it.id }) { a ->
                    val sel = a.id == picked
                    WowListRow(
                        a.name, lookSummary(catalog.races.firstOrNull { it.id == a.appearance.raceId }, a.appearance),
                        selected = sel, onClick = { if (sel) onEdit(a.id) else picked = a.id }, icon = "tab.npcs",
                        context = {
                            TextAction("Edit", { onEdit(a.id) })
                            TextAction("Duplicate", { state.duplicateActor(a.id) })
                            TextAction("Delete", { deleting = a.id }, danger = true)
                        },
                    )
                }
            }
            RedButton("New NPC", onNew, Modifier.align(Alignment.CenterHorizontally).widthIn(min = 240.dp))
        }
    }
    deleting?.let { id ->
        val uses = state.actorUses(id)
        ConfirmDialog(
            "Delete ${state.nameOf(id)}?" + if (uses > 0) " ${plural(uses, "beat")} use them: their lines become narration." else "",
            "Delete", { deleting = null },
        ) {
            deleting = null
            state.deleteActor(id)
        }
    }
}
