package com.logie.wifecraft.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.logie.wifecraft.creator.ClothingSlot
import com.logie.wifecraft.creator.TransmogCatalog

private val gold = Color(0xFFE7BD76)
private val ink = Color(0xFFF1E7D6)

@Immutable
private data class WardrobeTile(val id: Int, val name: String, val icon: Int, val outfit: Map<Int, Int>? = null)

/** The tiles shown for a slot and a search (made off the main thread: the catalog holds thousands of pieces). */
@Immutable
private class WardrobeResults(val slot: ClothingSlot?, val search: String, val tiles: List<WardrobeTile>)

private fun wardrobeTiles(catalog: TransmogCatalog, slot: ClothingSlot?, search: String): List<WardrobeTile> {
    val query = search.trim()
    return if (slot == null) {
        catalog.outfits.asSequence().filter { query.isEmpty() || it.name.contains(query, true) }
            .map { WardrobeTile(it.id, it.name, it.icon, it.displays) }.toList()
    } else {
        listOf(WardrobeTile(0, "None", 0)) + catalog.pieces.asSequence()
            .filter { it.slot == slot && (query.isEmpty() || it.name.contains(query, true)) }
            .map { WardrobeTile(it.display, it.name, it.icon) }.toList()
    }
}

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
    // The filter runs off the main thread, a new slot at once and typing 250 ms after the last key (cancelling the one
    // before); the tiles shown stay until the new ones are ready (no flash of an empty grid while typing). Which tile is
    // selected is read as each is drawn, so a tap never waits for the filter.
    var results by remember(catalog) { mutableStateOf<WardrobeResults?>(null) }
    LaunchedEffect(catalog, slot, search) {
        val c = catalog ?: return@LaunchedEffect
        val before = results
        if (before != null && before.slot == slot && search.isNotEmpty()) delay(250)
        val made = withContext(Dispatchers.Default) { wardrobeTiles(c, slot, search) }
        results = WardrobeResults(slot, search, made)
    }
    val tiles = results?.takeIf { it.slot == slot }?.tiles.orEmpty()
    val isSelected: (WardrobeTile) -> Boolean = { tile ->
        val current = slot
        when {
            current == null -> selected == tile.outfit
            tile.id == 0 -> current.id !in selected
            else -> selected[current.id] == tile.id
        }
    }
    WowWindow("Transmog", onClose, modifier) {
        if (catalog == null) {
            Text(status ?: "Loading clothing catalog…", color = ink)
            if (status != null) RedButton("Retry", onRetry)
        }
        if (catalog != null) Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LazyColumn(Modifier.width(86.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                item(key = "outfits") { SlotRow("Outfits", slot == null) { slot = null; search = "" } }
                items(ClothingSlot.entries, key = { it.name }) { part ->
                    SlotRow(part.label, slot == part) { slot = part; search = "" }
                }
            }
            Column(Modifier.weight(1f)) {
                WowSearchBox(search, { search = it.take(80) }, "Search ${slot?.label?.lowercase() ?: "outfits"}",
                    Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                key(slot) {
                  Box(Modifier.weight(1f)) {
                    // over the grid, not above it: the grid never moves down a line as the message comes and goes
                    if (results?.slot == slot && tiles.isEmpty()) Text("No outfits match.", color = ink)
                    LazyVerticalGrid(
                        GridCells.Adaptive(64.dp), modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(6.dp), horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        items(tiles, key = { it.id }, contentType = { 0 }) { tile ->
                            val image = gameIcon(icons, tile.icon)
                            val chosen = isSelected(tile)
                            Column(
                                Modifier.fillMaxWidth().semantics { this.selected = chosen }
                                    // the kit's press: debounced (a double tap is one change), the 0.96 press
                                    .warPress {
                                        val current = slot
                                        if (current == null) tile.outfit?.let(onChange)
                                        else onChange(if (tile.id == 0) selected - current.id else selected + (current.id to tile.id))
                                    }.padding(2.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Box(Modifier.size(46.dp).background(Color(0xFF2D241F))
                                    .border(1.dp, if (chosen) gold else Color(0xFF65503A)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    val frame = LocalWar.current?.piece(if (chosen) "house-chest-list-item-active" else "house-chest-list-item-default")
                                    if (frame != null) Image(frame, null, Modifier.fillMaxSize())
                                    if (image != null) Image(image, null, Modifier.fillMaxSize().padding(4.dp))
                                    else Text(if (tile.id == 0) "—" else tile.name.take(1), color = gold)
                                }
                                // always two lines high: a row of short names is as tall as one of long ones
                                Text(tile.name, color = if (chosen) gold else ink, maxLines = 2, minLines = 2,
                                    overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                            }
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
        .warPress(onClick = onPick).padding(horizontal = 5.dp, vertical = 9.dp),
        color = if (active) gold else ink, style = MaterialTheme.typography.labelMedium)
}
