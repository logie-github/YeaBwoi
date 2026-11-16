package com.logie.wifecraft.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import com.logie.wifecraft.editor.Project

/** The scenes in order: add, open, rename, reorder, delete; the starred one is where the story starts. */
@Composable
fun EditorScenes(
    project: Project,
    state: EditorState,
    places: List<PlaceInfo>,
    onOpen: (sceneId: String) -> Unit,
    onCast: () -> Unit,
    onBack: () -> Unit,
) {
    var renaming by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    EditorFrame(
        "${project.title}: Scenes", onBack,
        actions = { RoundIconButton(Face(icon = "menu.cast"), onCast, description = "Cast") },
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (project.scenes.isEmpty()) {
                Text("No scenes yet.", style = frizStyle(16.sp, WowColors.grey), modifier = Modifier.padding(8.dp))
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(project.scenes, key = { _, s -> s.id }) { i, s ->
                    EditorCard(onClick = { onOpen(s.id) }, selected = s.id == project.start) {
                        Column(Modifier.weight(1f)) {
                            Text((if (s.id == project.start) "* " else "") + s.name, style = frizStyle(18.sp), maxLines = 1)
                            Text(
                                (places.firstOrNull { it.id == com.logie.wifecraft.editor.Compile.stageOf(s) }?.name ?: s.stage.name.ifBlank { com.logie.wifecraft.editor.Compile.stageOf(s) }) +
                                    " · " + s.stage.light + " · " + plural(s.beats.size, "beat") + (if (s.placements.isEmpty()) "" else " · " + plural(s.placements.size, "placed thing")),
                                style = frizStyle(12.sp, WowColors.grey), maxLines = 1,
                            )
                        }
                        SmallButton("Up", { state.moveScene(s.id, -1) }, enabled = i > 0, width = 64.dp)
                        SmallButton("Down", { state.moveScene(s.id, 1) }, enabled = i < project.scenes.size - 1, width = 74.dp)
                        SmallButton("Start", { state.setStart(s.id) }, enabled = s.id != project.start, width = 80.dp)
                        SmallButton("Rename", { renaming = s.id })
                        SmallButton("Delete", { deleting = s.id }, width = 90.dp)
                    }
                }
            }
            PillButton("Add scene", { adding = true }, Modifier.align(Alignment.CenterHorizontally).widthIn(min = 240.dp), glyph = WarIcons.PLUS)
        }
    }
    if (adding) NameDialog("New scene", "", "Add", { adding = false }) { name ->
        adding = false
        onOpen(state.addScene(name))
    }
    renaming?.let { id ->
        NameDialog("Rename scene", state.sceneOf(id)?.name ?: "", "Rename", { renaming = null }) { name ->
            renaming = null
            state.renameScene(id, name)
        }
    }
    deleting?.let { id ->
        ConfirmDialog("Delete \"${state.sceneOf(id)?.name}\"? Jumps and choices that lead there go too.", "Delete", { deleting = null }) {
            deleting = null
            state.deleteScene(id)
        }
    }
}
