package com.logie.wifecraft.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.logie.wifecraft.catalog.Emote
import com.logie.wifecraft.editor.Placement

/**
 * The animation and emote of a placed person or creature: every emote and animation its race and sex have
 * (EmoteCatalog.forModel), in the catalogue's groups (Emotes, Dance, Sit, sleep and kneel, Work and life, Combat...), with
 * a search box. Play previews one on the stage with its voice; Use makes it the one it plays in the scene. Held (loops) and
 * Sound (its voice and its own sounds) are switches; None goes back to standing. A creature also has its own sounds to hear.
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
        Text(
            "Now: " + (chosen?.name ?: p.anim?.let { "animation $it" } ?: "standing"),
            style = frizStyle(14.sp, Color.White), maxLines = 1,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            PToggle("Held", p.loop, onLoop, Modifier.weight(1f), width = 80.dp)
            PToggle("Sound", p.sound, onSound, Modifier.weight(1f), width = 80.dp)
            PButton("None", { onUse(null) }, Modifier.weight(1f), width = 80.dp)
        }
        if (onCreatureSound != null) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (k in listOf("fidget", "alert", "attack", "wound", "death", "shout", "loop")) PButton(k.replaceFirstChar { it.uppercase() }, { onCreatureSound(k) }, width = 84.dp)
            }
        }
        EditorField(filter, { filter = it.take(40) }, "Search ${emotes.size} emotes and animations")
        if (emotes.isEmpty()) {
            Text("This model's emotes aren't known (only the character creator's races and sexes are in emotes.json).",
                style = frizStyle(13.sp, WowColors.grey, false))
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((category, list) in groups) {
                val shown = open == category || filter.isNotBlank() || groups.size == 1
                item(key = "h:$category") {
                    EditorCard(onClick = { open = if (open == category) null else category }, selected = shown) {
                        Text(category, style = frizStyle(15.sp), modifier = Modifier.weight(1f))
                        Text("${list.size}", style = frizStyle(13.sp, WowColors.grey))
                    }
                }
                if (shown) items(list, key = { "e:${it.id}" }) { e ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .background(if (e.id == p.emoteId) Color(0x55FFD100) else Color(0x44000000))
                            .border(1.dp, Color(0x33FFD100)).clickable { onPreview(e) }.padding(start = 10.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                            Text(e.name, style = frizStyle(14.sp, Color.White, false), maxLines = 1)
                            Text(EmoteGroups.detail(e), style = frizStyle(11.sp, WowColors.grey, false), maxLines = 1)
                        }
                        PButton("Play", { onPreview(e) }, width = 72.dp)
                        PButton("Use", { onUse(e) }, width = 68.dp)
                    }
                }
            }
        }
    }
}
