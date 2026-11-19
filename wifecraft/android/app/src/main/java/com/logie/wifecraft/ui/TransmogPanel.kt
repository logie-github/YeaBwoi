package com.logie.wifecraft.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.logie.wifecraft.creator.ClothingSlot
import com.logie.wifecraft.creator.TransmogCatalog

private val gold = Color(0xFFE7BD76)
private val ink = Color(0xFFF1E7D6)

private data class WardrobeTile(val id: Int, val name: String, val icon: Int, val selected: Boolean, val outfit: Map<Int, Int>? = null)

@Composable
fun TransmogPanel(
    catalog: TransmogCatalog?,
    selected: Map<Int, Int>,
    status: String?,
    onRetry: () -> Unit,
    onChange: (Map<Int, Int>) -> Unit,
    onClose: () -> Unit,
    icons: GameIcons?,
    modifier: Modifier = Modifier,
) {
    var slot by remember { mutableStateOf<ClothingSlot?>(null) }
    var search by remember { mutableStateOf("") }
    val tiles = remember(catalog, selected, slot, search) {
        val current = slot
        if (current == null) {
            catalog?.outfits.orEmpty().filter { it.name.contains(search, true) }
                .map { WardrobeTile(it.id, it.name, it.icon, selected == it.displays, it.displays) }
        } else {
            val none = WardrobeTile(0, "None", 0, current.id !in selected)
            listOf(none) + catalog?.pieces.orEmpty().asSequence().filter { it.slot == current && it.name.contains(search, true) }
                .map { WardrobeTile(it.display, it.name, it.icon, selected[current.id] == it.display) }.toList()
        }
    }
    WowWindow("Transmog", onClose, modifier) {
        if (catalog == null) {
            Text(status ?: "Loading clothing catalog…", color = ink)
            if (status != null) RedButton("Retry", onRetry)
        }
        if (catalog != null) Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LazyColumn(Modifier.width(86.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                item { SlotRow("Outfits", slot == null) { slot = null; search = "" } }
                items(ClothingSlot.entries.size) { index ->
                    val part = ClothingSlot.entries[index]
                    SlotRow(part.label, slot == part) { slot = part; search = "" }
                }
            }
            Column(Modifier.weight(1f)) {
                WowSearchBox(search, { search = it.take(80) }, "Search ${slot?.label?.lowercase() ?: "outfits"}",
                    Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                if (tiles.isEmpty()) Text("No outfits match.", color = ink)
                key(slot) {
                    LazyVerticalGrid(
                        GridCells.Adaptive(64.dp), modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp), horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        items(tiles, key = { it.id }) { tile ->
                            val image = gameIcon(icons, tile.icon)
                            Column(
                                Modifier.fillMaxWidth().semantics { this.selected = tile.selected }
                                    .clickable(role = Role.Button) {
                                        val current = slot
                                        if (current == null) tile.outfit?.let(onChange)
                                        else onChange(if (tile.id == 0) selected - current.id else selected + (current.id to tile.id))
                                    }.padding(2.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Box(Modifier.size(46.dp).background(Color(0xFF2D241F))
                                    .border(1.dp, if (tile.selected) gold else Color(0xFF65503A)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    val frame = LocalWar.current?.piece(if (tile.selected) "house-chest-list-item-active" else "house-chest-list-item-default")
                                    if (frame != null) Image(frame, null, Modifier.fillMaxSize())
                                    if (image != null) Image(image, null, Modifier.fillMaxSize().padding(4.dp))
                                    else Text(if (tile.id == 0) "—" else tile.name.take(1), color = gold)
                                }
                                Text(tile.name, color = if (tile.selected) gold else ink, maxLines = 2,
                                    overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SlotRow(label: String, active: Boolean, onPick: () -> Unit) {
    Text(label, Modifier.fillMaxWidth().semantics { selected = active }
        .background(if (active) Color(0xFF443423) else Color.Transparent)
        .clickable(role = Role.Tab, onClick = onPick).padding(horizontal = 5.dp, vertical = 9.dp),
        color = if (active) gold else ink, style = MaterialTheme.typography.labelMedium)
}
