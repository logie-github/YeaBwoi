package com.logie.wifecraft.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.logie.wifecraft.creator.ClothingSlot
import com.logie.wifecraft.creator.TransmogCatalog

@Composable
fun TransmogPanel(
    catalog: TransmogCatalog?,
    selected: Map<Int, Int>,
    status: String?,
    onRetry: () -> Unit,
    onChange: (Map<Int, Int>) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var slot by remember { mutableStateOf<ClothingSlot?>(null) }
    var search by remember { mutableStateOf("") }
    val outfits = remember(catalog, search) { catalog?.outfits.orEmpty().filter { it.name.contains(search, true) } }
    val pieces = remember(catalog, slot, search) {
        catalog?.pieces.orEmpty().filter { it.slot == slot && it.name.contains(search, true) }
    }
    Column(modifier.background(Color(0xFF201A19)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Transmog", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, color = Color(0xFFF4DFBD))
            TextButton(onClose) { Text("Done") }
        }
        if (catalog == null) {
            Text(status ?: "Loading clothing catalog…", color = Color.White)
            if (status != null) OutlinedButton(onRetry) { Text("Retry") }
            return
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(slot == null, { slot = null; search = "" }, { Text("Outfits") })
            for (part in ClothingSlot.entries) {
                FilterChip(slot == part, { slot = part; search = "" }, { Text(part.label) })
            }
        }
        OutlinedTextField(search, { search = it.take(80) }, Modifier.fillMaxWidth(), singleLine = true,
            placeholder = { Text("Search ${slot?.label?.lowercase() ?: "outfits"}") },
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White))
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            item {
                TextButton({
                    val current = slot
                    onChange(if (current == null) emptyMap() else selected - current.id)
                }) { Text(if (slot == null) "Clear outfit" else "None") }
            }
            if (slot == null) {
                items(outfits, key = { it.id }) { set ->
                    TextButton({ onChange(set.displays) }) {
                        Text(if (selected == set.displays) "✓ ${set.name}" else set.name)
                    }
                }
            } else {
                items(pieces, key = { it.display }) { piece ->
                    TextButton({ onChange(selected + (piece.slot.id to piece.display)) }) {
                        Text(if (selected[piece.slot.id] == piece.display) "✓ ${piece.name}" else piece.name)
                    }
                }
            }
        }
    }
}
