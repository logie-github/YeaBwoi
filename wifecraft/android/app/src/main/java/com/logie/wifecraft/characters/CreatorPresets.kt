package com.logie.wifecraft.characters

import com.logie.wifecraft.creator.ClothingSet
import com.logie.wifecraft.creator.LookPreset

/**
 * The named characters' creator presets (assets/characters.json's "presets": Chromie's gnome look), offered in the
 * creator's Presets window (CasPresets.builtIns): the preset's worn items, and its choices as a [LookPreset] that the
 * creator puts on a character of the preset's race and sex. Ids are negative (-1 the roster's first preset). Pure JVM.
 */
object CreatorPresets {
    fun outfits(roster: List<Character>): List<ClothingSet> {
        val out = ArrayList<ClothingSet>()
        for (c in roster) for (p in c.presets) {
            if (p.raceId <= 0 || p.equipment.isEmpty() && p.picks.isEmpty()) continue
            val look = if (p.picks.isEmpty()) null else LookPreset(p.raceId, p.sex, p.picks)
            out += ClothingSet(-(out.size + 1), p.label, p.equipment, look = look)
        }
        return out
    }

    /** [outfits] of the roster's JSON; none when it can't be read. */
    fun outfits(json: String?): List<ClothingSet> =
        if (json == null) emptyList() else runCatching { outfits(CharacterRoster.parse(json)) }.getOrDefault(emptyList())
}
