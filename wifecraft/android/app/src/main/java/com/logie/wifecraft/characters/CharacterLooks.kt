package com.logie.wifecraft.characters

import com.logie.wifecraft.creator.Appearance
import com.logie.wifecraft.creator.Catalog
import com.logie.wifecraft.creator.CreatorModel
import com.logie.wifecraft.creator.CreatureLooks
import com.logie.wifecraft.creator.DressCall
import com.logie.wifecraft.creator.Dressing
import com.logie.wifecraft.creator.LookResolver
import com.logie.wifecraft.creator.ResolvedLook
import com.logie.wifecraft.creator.Sex
import com.logie.wifecraft.creator.Tables

/**
 * A named character's variant (CharacterRoster) made what the existing drawing path takes, pure JVM:
 *
 * - a display variant: the game's NPC display as it is ([CreatureLooks.resolve] and [CreatureLooks.call]);
 * - a body variant: an [Appearance] of the playable race with the character's own choices (face, hair, skin, markings: her
 *   identity) and Wrath++'s body options, drawn as the creator draws one ([LookResolver.resolve], then the call Dresser
 *   makes: [CreatureLooks.call] when clothing is worn, else [Dressing.call]). [VariantKind.OUTFIT] wears the display's
 *   equipment through the creator's transmog (CreatureLooks.transmog: the items' geosets, models and texture layers);
 *   [VariantKind.UNDERWEAR] is the creator's No Gear (gear off, no clothing: the equipment groups at their bare
 *   geosets, the skin colour's underwear layers kept); [VariantKind.BARE] also leaves out the skin colour's own
 *   underwear layers (every layer the Skin Color option's materials put on the body's texture but its whole-texture
 *   base), so the race's base skin shows. Nothing is drawn that the game's model and textures don't have.
 */
object CharacterLooks {
    /** NPCModelItemSlotDisplayInfo.ItemSlot to the equipment slot (ClothingSlot ids; CreatureLooks' NPC_SLOT). */
    val NPC_SLOT = mapOf(0 to 1, 1 to 3, 2 to 4, 3 to 5, 4 to 6, 5 to 7, 6 to 8, 7 to 9, 8 to 10, 9 to 19, 10 to 15)

    /**
     * The equipment slots a [VariantKind.NUDE] variant leaves out of its outfit (ClothingSlot ids): shirt 4, chest or robe 5,
     * belt 6, legs 7, feet 8, tabard 19. The head piece 1, shoulders 3, wrists 9, gloves 10 and cloak 15 stay.
     */
    val NUDE_REMOVED = setOf(4, 5, 6, 7, 8, 19)

    /**
     * An outfit variant's nude one ("<key>_nude"): its items but [NUDE_REMOVED]'s over the bare body of its own race and
     * choices; null for a display variant (a model of its own: its armour is the model's, nothing comes off), for a gnome
     * woman, and for an outfit with no torso, leg or feet piece to take off.
     */
    fun nude(v: CharacterVariant): CharacterVariant? {
        if (v.kind != VariantKind.OUTFIT || v.isDisplay || v.raceId == 0) return null
        if (v.raceId == 7 && v.sex == Sex.FEMALE) return null
        if (v.equipment.keys.none { it in NUDE_REMOVED }) return null
        return v.copy(key = "${v.key}_nude", label = "${v.label}, nude", kind = VariantKind.NUDE,
            equipment = v.equipment.filterKeys { it !in NUDE_REMOVED })
    }

    /** What a humanoid NPC display is made of: its race, sex, kept choices and worn items (by equipment slot). */
    data class DisplayParts(val displayId: Int, val raceId: Int, val sex: Sex, val picks: Map<Int, Int>, val equipment: Map<Int, Int>)

    /**
     * A humanoid display's parts (CreatureDisplayInfo -> CreatureDisplayInfoExtra: race and sex,
     * CreatureDisplayInfoOption: the choices, NPCModelItemSlotDisplayInfo: the items); null for a display of a model of its
     * own (no ExtendedDisplayInfoID).
     */
    fun parts(t: Tables, displayId: Int): DisplayParts? {
        val disp = t.rowsBy("CreatureDisplayInfo", "ID", displayId.toString()).firstOrNull() ?: return null
        val extraId = disp.int("ExtendedDisplayInfoID").takeIf { it != 0 } ?: return null
        val ex = t.rowsBy("CreatureDisplayInfoExtra", "ID", extraId.toString()).firstOrNull() ?: return null
        val picks = LinkedHashMap<Int, Int>()
        for (r in t.rowsBy("CreatureDisplayInfoOption", "CreatureDisplayInfoExtraID", extraId.toString()))
            picks[r.int("ChrCustomizationOptionID")] = r.int("ChrCustomizationChoiceID")
        val equipment = sortedMapOf<Int, Int>()
        for (r in t.rowsBy("NPCModelItemSlotDisplayInfo", "NpcModelID", extraId.toString())) {
            val slot = NPC_SLOT[r.int("ItemSlot")] ?: continue
            val item = r.int("ItemDisplayInfoID")
            if (item != 0) equipment[slot] = item
        }
        val sex = if (ex.int("DisplaySexID") == 1) Sex.FEMALE else Sex.MALE
        return DisplayParts(displayId, ex.int("DisplayRaceID"), sex, picks, equipment)
    }

    /** The variant's appearance for the creator's resolver; null for a display variant. */
    fun appearance(v: CharacterVariant): Appearance? {
        if (v.isDisplay || v.raceId == 0) return null
        return if ((v.kind == VariantKind.OUTFIT || v.kind == VariantKind.NUDE) && v.equipment.isNotEmpty())
            Appearance(v.raceId, v.sex, v.picks, v.body, gear = true, clothing = v.equipment, starterClothing = false)
        else Appearance(v.raceId, v.sex, v.picks, v.body, gear = false)
    }

    /** The variant's look; null when the tables can't draw it (the reason in [why]). */
    fun resolve(c: Catalog, t: Tables, v: CharacterVariant): ResolvedLook? = check(c, t, v).first

    fun why(c: Catalog, t: Tables, v: CharacterVariant): String? = check(c, t, v).second

    private fun check(c: Catalog, t: Tables, v: CharacterVariant): Pair<ResolvedLook?, String?> {
        if (v.isDisplay) {
            val r = CreatureLooks.check(t, v.display)
            return r.look to r.why
        }
        val a = appearance(v) ?: return null to "no race and no display"
        if (c.model(a.raceId, a.sex) == null) return null to "race ${a.raceId} ${a.sex.name.lowercase()} isn't in the creator's catalogue"
        val look = runCatching { LookResolver.resolve(c, t, a) }.getOrElse { return null to (it.message ?: it.toString()) }
        return (if (v.kind == VariantKind.BARE || v.kind == VariantKind.NUDE) bare(c, t, a, look) else look) to null
    }

    /** The renderer's call for [look] as Dresser makes it for this variant. */
    fun call(c: Catalog, t: Tables, v: CharacterVariant, look: ResolvedLook): DressCall {
        if (v.isDisplay) return CreatureLooks.call(t, look)
        val a = appearance(v)!!
        val layout = Dressing.layout(c, t, a)
        return if (a.gear && a.clothing.isNotEmpty()) CreatureLooks.call(t, look, layout) else Dressing.call(t, layout, look)
    }

    /**
     * [look] without the skin colour's underwear: each layer whose file is one of the Skin Color option's materials (any
     * of its choices') but isn't the body's whole-texture base (texture type 1, every section) goes, as LookResolver does
     * for an undead skin that brings no underwear of its own.
     */
    fun bare(c: Catalog, t: Tables, a: Appearance, look: ResolvedLook): ResolvedLook {
        val files = skinColorFiles(c, t, a)
        if (files.isEmpty()) return look.copy(diagnostics = look.diagnostics + "Bare: the race has no Skin Color option; its underwear stays")
        val keep = look.layers.filter { l -> l.fileId !in files || (l.textureType == 1 && l.region == -1) }
        val gone = look.layers.size - keep.size
        val diagnostics = if (gone == 0) look.diagnostics + "Bare: no underwear layer to take off (the body's texture carries it)" else look.diagnostics
        return look.copy(layers = keep, diagnostics = diagnostics)
    }

    /** Every texture file the Skin Color option's choices put on (ChrCustomizationElement -> Material -> TextureFileData). */
    fun skinColorFiles(c: Catalog, t: Tables, a: Appearance): Set<Int> {
        val entry = c.model(a.raceId, a.sex) ?: return emptySet()
        val option = entry.options.firstOrNull { it.name == CreatorModel.SKIN_COLOR } ?: return emptySet()
        val out = HashSet<Int>()
        for (choice in option.choices) {
            for (e in t.rowsBy("ChrCustomizationElement", "ChrCustomizationChoiceID", choice.id.toString())) {
                val m = e.int("ChrCustomizationMaterialID").takeIf { it != 0 } ?: continue
                val mat = t.rowsBy("ChrCustomizationMaterial", "ID", m.toString()).firstOrNull() ?: continue
                for (f in t.rowsBy("TextureFileData", "MaterialResourcesID", mat.int("MaterialResourcesID").toString()))
                    f.int("FileDataID").takeIf { it != 0 }?.let { out += it }
            }
        }
        return out
    }

    private fun Map<String, String>.int(k: String): Int = this[k]?.trim()?.toDoubleOrNull()?.toInt() ?: 0
}
