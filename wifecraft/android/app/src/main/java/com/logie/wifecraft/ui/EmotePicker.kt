package com.logie.wifecraft.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.logie.wifecraft.catalog.Emote
import com.logie.wifecraft.editor.Placement

/**
 * The emotes of a placed person or creature, as a WoW window: one search box and one category dropdown (All, Emotes, Dance,
 * Sit, sleep and kneel, Work and life, Combat, Spells... with their counts: EmoteGroups), then the list by the names the
 * player reads ([Emote.label]: "Talk (Excited)"). Tapping a row picks it and previews it on the stage with its voice;
 * **Use** makes it what the thing plays in the scene, **None** goes back to standing, **Preview** plays the picked one again.
 * Held (loops) and Sound (its voice and its own sounds) are check boxes; a creature also has its own sounds to hear.
 */
@Composable
fun EmoteWindow(
    emotes: List<Emote>,
    p: Placement,
    onPreview: (Emote) -> Unit,
    onUse: (Emote?) -> Unit,
    onLoop: (Boolean) -> Unit,
    onSound: (Boolean) -> Unit,
    onCreatureSound: ((String) -> Unit)?,
    onClose: () -> Unit,
    width: Dp,
    height: Dp,
) {
    var filter by remember { mutableStateOf("") }
    var group by remember(p.id) { mutableStateOf<String?>(null) }
    var picked by remember(p.id) { mutableStateOf(emotes.firstOrNull { it.id == p.emoteId }) }
    val groups = remember(emotes, filter) { EmoteGroups.of(emotes, filter) }
    val shown = remember(groups, group) { (if (group == null) groups else groups.filter { it.first == group }).flatMap { it.second } }
    val now = emotes.firstOrNull { it.id == p.emoteId }
    Overlay(onClose) {
        WowWindow("Emotes: ${p.name}", onClose, Modifier.width(width).height(height)) {
            Text("Now: " + (now?.label ?: p.anim?.let { "animation $it" } ?: "standing"), style = frizStyle(13.sp, Color.White), maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                WowSearchBox(filter, { filter = it.take(40) }, "Search ${emotes.size} emotes", Modifier.weight(1f))
                val options = listOf<Pair<String?, String>>(null to "All  (${groups.sumOf { it.second.size }})") + groups.map { (g, rows) -> g to "$g  (${rows.size})" }
                WowDropdown(options.firstOrNull { it.first == group }?.second ?: options.first().second, options, { group = it }, Modifier.weight(0.8f))
            }
            if (emotes.isEmpty()) WowNote("This model's emotes aren't known (only the character creator's races and sexes are in emotes.json).", Modifier.padding(6.dp))
            val list = rememberLazyListState()
            LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(top = 6.dp).warScrollbar(list), list, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(shown, key = { "e:${it.id}" }) { e ->
                    WowListRow(
                        e.label, EmoteGroups.detail(e), selected = e.id == picked?.id,
                        onClick = { picked = e; onPreview(e) },
                        modifier = Modifier.padding(end = 10.dp),
                        icon = WarIcons.emoteGroup(e.category),
                        trailing = { if (e.id == p.emoteId) Text("in use", style = frizStyle(12.sp, WowColors.gold)) },
                    )
                }
            }
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                WowCheckRow("Held", p.loop, onLoop)
                WowCheckRow("Sound", p.sound, onSound)
                if (onCreatureSound != null) WowDropdown(
                    "Creature sound", listOf("fidget", "alert", "attack", "wound", "death", "shout", "loop").map { it to it.replaceFirstChar(Char::uppercase) },
                    { onCreatureSound(it) }, Modifier.width(190.dp),
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RedButton("None", { onUse(null) }, Modifier.weight(1f))
                RedButton("Preview", { picked?.let(onPreview) }, Modifier.weight(1f), enabled = picked != null)
                RedButton("Use", { picked?.let(onUse) }, Modifier.weight(1f), enabled = picked != null)
            }
        }
    }
}

