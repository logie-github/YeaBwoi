package com.logie.wifecraft.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.logie.wifecraft.dialogue.PersonaKit

/**
 * The Era control: a stop for each era of assets/dialogue/eras (Vanilla .. Midnight) and a last one, a custom year (a release year
 * from 2004 to 2026, mapped to the era it falls in). Used by Options' Dialogue page (the Default era), the scene creation's last step
 * (which sets the same default, remembered) and the scene menu (this scene only, applied from the next line). Draws nothing when the
 * profile and era data are missing.
 */
@Composable
fun EraPickerRow(label: String, eraId: String, customYear: Int, onPick: (String, Int) -> Unit) {
    val ctx = LocalContext.current
    val kit = remember(ctx) { DialogueAssets.persona(ctx) } ?: return
    val eras = kit.eras
    val custom = eraId == PersonaKit.CUSTOM
    val index = if (custom) eras.size else eras.indexOfFirst { it.id == eraId }.coerceAtLeast(0)
    val year = if (customYear > 0) customYear else 2008
    val text = if (custom) "Custom: $year (${kit.forYear(year).label})" else eras[index].label
    WifeSliderRow(label, index, { i -> if (i >= eras.size) onPick(PersonaKit.CUSTOM, year) else onPick(eras[i.coerceAtLeast(0)].id, 0) },
        valueText = text, range = 0..eras.size)
    if (custom) WifeSliderRow("Year", year, { onPick(PersonaKit.CUSTOM, it) }, valueText = "$year", range = 2004..2026)
}
