package com.logie.wifecraft.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.logie.wifecraft.catalog.Emote
import com.logie.wifecraft.editor.Placement

/**
 * The animation and emote of a placed person or creature (the Animation page): every emote and animation its race and sex
 * have (EmoteCatalog.forModel), in the catalogue's groups (Emotes, Dance, Sit, sleep and kneel, Work and life, Combat...)
 * each with its icon, by the names the player reads ([Emote.label]: "Talk (Excited)"), with a search box (the label or the
 * game's own name). Tapping a row or Play previews it on the stage with its voice; Use makes it the one it plays in the
 * scene. Held (loops) and Sound (its voice and its own sounds) are switches; None goes back to standing. A creature also
 * has its own sounds to hear.
 */
@Composable
fun EmotePicker(
    emotes: List<Emote>,
    p: Placement,
    onPreview: (Emote) -> Unit,
    onUse: (Emote?) -> Unit,
    onLoop: (Boolean) -> Unit,
    onSound: (Boolean) -> Unit,
    onCreatureSound: ((String) -> Unit)? = null,
) {
    var filter by remember { mutableStateOf("") }
    var open by remember(p.id) { mutableStateOf<String?>(null) }
    val groups = remember(emotes, filter) { EmoteGroups.of(emotes, filter) }
    val chosen = emotes.firstOrNull { it.id == p.emoteId }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BookHeader("Animation", p.name, "Now: " + (chosen?.label ?: p.anim?.let { "animation $it" } ?: "standing"))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            WarToggle("Held", p.loop, onLoop)
            WarToggle("Sound", p.sound, onSound)
        }
        PillButton("None (standing)", { onUse(null) }, Modifier.fillMaxWidth())
        if (onCreatureSound != null) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (k in listOf("fidget", "alert", "attack", "wound", "death", "shout", "loop")) Chip(k.replaceFirstChar { it.uppercase() }, null, false, { onCreatureSound(k) })
            }
        }
        WarField(filter, { filter = it.take(40) }, "Search ${emotes.size} emotes and animations")
        if (emotes.isEmpty()) {
            Text("This model's emotes aren't known (only the character creator's races and sexes are in emotes.json).", style = warText(13.sp, War.brownSoft))
        }
        val list = rememberLazyListState()
        LazyColumn(Modifier.weight(1f).fillMaxWidth().warScrollbar(list), list, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((category, rows) in groups) {
                val shown = open == category || filter.isNotBlank() || groups.size == 1
                item(key = "h:$category") {
                    WarRow({ open = if (open == category) null else category }, selected = shown, icon = WarIcons.emoteGroup(category), modifier = Modifier.padding(end = 10.dp)) {
                        Text(category, style = warTitle(17.sp), modifier = Modifier.weight(1f), maxLines = 1)
                        Badge("${rows.size}")
                    }
                }
                if (shown) items(rows, key = { "e:${it.id}" }) { e ->
                    WarRow({ onPreview(e) }, selected = e.id == p.emoteId, modifier = Modifier.padding(start = 14.dp, end = 10.dp)) {
                        Column(Modifier.weight(1f).padding(vertical = 2.dp)) {
                            Text(e.label, style = warText(14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(EmoteGroups.detail(e), style = warText(11.sp, War.brownSoft), maxLines = 1)
                        }
                        RoundIconButton(Face(glyph = WarIcons.PLAY), { onPreview(e) }, size = 48.dp, description = "Play ${e.label}")
                        RoundIconButton(Face(glyph = WarIcons.CHECK), { onUse(e) }, size = 48.dp, lit = e.id == p.emoteId, description = "Use ${e.label}")
                    }
                }
            }
        }
    }
}
