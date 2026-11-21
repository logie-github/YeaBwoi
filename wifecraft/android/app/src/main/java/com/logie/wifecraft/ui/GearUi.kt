package com.logie.wifecraft.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.logie.wifecraft.creator.ClassOutfits
import com.logie.wifecraft.creator.CreatorModel

/*
 * The Gear switch's controls (creator/ClassOutfits, characters/GearSwitch): the creator's Outfit tab (Gear on or off and the
 * class whose default clothing Gear on wears), the NPC menu's Gear row and the live scene's quick switches (LocalGearControl).
 * Not run here (Compose can't be): the logic under it is tested on the JVM (GearTest).
 */

@Volatile private var loadedOutfits: ClassOutfits? = null

/** assets/class_outfits.json, read once (ClassOutfits.EMPTY when it can't be: everyone wears the recruit's clothes). */
fun classOutfits(context: Context): ClassOutfits = loadedOutfits ?: ClassOutfits.parse(
    runCatching { context.assets.open("class_outfits.json").bufferedReader().use { it.readText() } }.getOrNull(),
).also { loadedOutfits = it }

/** The creator's Outfit tab: Gear on (her class's default clothing) or off (nude), and the class. */
@Composable
fun CasGearSection(model: CreatorModel, rev: Int, onChanged: (look: Boolean) -> Unit) {
    val context = LocalContext.current
    val outfits = remember { classOutfits(context) }
    val a = rev.let { model.appearance }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(WifeTheme.gapXs)) {
        WifeSwitchRow("Gear", a.gear, { on -> model.setGear(on); onChanged(true) },
            detail = if (a.gear) "On: ${outfits.name(outfits.effectiveClass(a.raceId, a.classId))} clothing" else "Off: nude")
        WifeSectionHeader("Class")
        WifeNote("Gear on wears the class's default clothing, the game's starting outfit for this race.")
        val classes = outfits.classesFor(a.raceId)
        val chosen = outfits.effectiveClass(a.raceId, a.classId)
        for (row in classes.chunked(2)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapXs)) {
                for (c in row) {
                    WifeButton(c.name, { model.setClass(c.id); if (!model.appearance.gear) model.setGear(true); onChanged(true) },
                        Modifier.weight(1f), kind = if (c.id == chosen) WifeButtonKind.PRIMARY else WifeButtonKind.SECONDARY, sound = "select")
                }
                if (row.size == 1) androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** The NPC menu's Gear row: on (class clothing, or a named character's outfit) or off (nude). */
@Composable
fun NpcGearRow(on: Boolean, onChange: (Boolean) -> Unit, named: Boolean) {
    WifeSectionHeader("Gear")
    WifeSwitchRow("Gear", on, onChange, detail = when {
        named && on -> "On: her outfit"
        named -> "Off: nude"
        on -> "On: class clothing"
        else -> "Off: nude"
    })
}

/**
 * The live scene's Gear switches: per NPC (the selected one) and for everyone in the scene. The host keeps the state for the
 * scene, dresses them again at once and keeps the switches when the scene ends.
 */
interface GearControl {
    fun isOn(npcId: String): Boolean
    fun set(npcId: String, on: Boolean)
    fun allOn(): Boolean
    fun setAll(on: Boolean)
}

/** The live scene's [GearControl]; none outside a live scene. */
val LocalGearControl = compositionLocalOf<GearControl?> { null }
