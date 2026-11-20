package com.logie.wifecraft.creator

enum class ClothingSlot(val id: Int, val label: String) {
    HEAD(1, "Head"), SHOULDERS(3, "Shoulders"), SHIRT(4, "Shirt"), TORSO(5, "Torso"),
    WAIST(6, "Waist"), LEGS(7, "Legs"), FEET(8, "Feet"), WRISTS(9, "Wrists"),
    GLOVES(10, "Gloves"), BACK(15, "Back"), TABARD(19, "Tabard");

    companion object {
        fun fromInventory(id: Int) = entries.firstOrNull { it.id == if (id == 20) 5 else id }
    }
}

/**
 * A piece of the catalog: the item, its look (ItemDisplayInfo), name, slot and icon; its quality (ItemSparse's
 * OverallQualityID: the colour of its name in the Transmog window's tooltip) and expansion (ExpansionID: the window's filter).
 */
data class ClothingPiece(
    val item: Int, val display: Int, val name: String, val slot: ClothingSlot, val icon: Int = 0,
    val quality: Int = 1, val expansion: Int = 0,
)
/**
 * A named outfit of the Transmog panel: the game's item sets (ItemSet, positive ids) and the named characters' presets
 * (assets/characters.json, negative ids), whose [look] also sets the character's own choices for her race and sex.
 */
data class ClothingSet(
    val id: Int, val name: String, val displays: Map<Int, Int>, val icon: Int = 0, val look: LookPreset? = null,
    /** The expansion of its pieces (the first piece's; -1 for a preset): the Transmog window's filter. */
    val expansion: Int = -1,
)

/**
 * A named character's own choices for a playable race's body (ChrCustomizationOption id to ChrCustomizationChoice id), as
 * the creator offers them: a preset applies them only to a character of that race and sex ([CreatorModel.applyLook]).
 */
data class LookPreset(val raceId: Int, val sex: Sex, val picks: Map<Int, Int>)

class TransmogCatalog(val pieces: List<ClothingPiece>, val outfits: List<ClothingSet>) {
    /** IDs of Warcraft's recruit shirt, pants and boots; use another available item if an ID has no current display. */
    val starter: Map<Int, Int> by lazy {
        fun piece(slot: ClothingSlot, preferred: Int): ClothingPiece? {
            val candidates = pieces.filter { it.slot == slot }
            return candidates.firstOrNull { it.item == preferred }
                ?: candidates.firstOrNull { it.name.contains("Recruit", ignoreCase = true) }
                ?: candidates.firstOrNull()
        }
        listOfNotNull(
            piece(ClothingSlot.SHIRT, 38) ?: piece(ClothingSlot.TORSO, 6098),
            piece(ClothingSlot.LEGS, 39), piece(ClothingSlot.FEET, 40),
        ).associate { it.slot.id to it.display }
    }

    /**
     * This catalog with [named] outfits listed first (the named characters' presets), each given the icon of its torso
     * piece (else of any piece) where the catalog has the item.
     */
    fun withNamed(named: List<ClothingSet>): TransmogCatalog {
        if (named.isEmpty()) return this
        val iconOf = pieces.filter { it.icon > 0 }.groupBy { it.display }
        val sets = named.map { set ->
            if (set.icon > 0) set else {
                val torso = set.displays[ClothingSlot.TORSO.id]?.let { iconOf[it]?.firstOrNull()?.icon }
                set.copy(icon = torso ?: set.displays.values.firstNotNullOfOrNull { iconOf[it]?.firstOrNull()?.icon } ?: 0)
            }
        }
        return TransmogCatalog(pieces, sets + outfits.filter { o -> sets.none { it.id == o.id } })
    }

    fun equipStarter(appearance: Appearance): Appearance =
        if (!appearance.starterClothing || !appearance.gear) appearance else appearance.copy(clothing = starter, gear = true, starterClothing = false)

    companion object {
        val TABLES = listOf("ItemSparse", "ItemModifiedAppearance", "ItemAppearance", "ItemSet", "ItemDisplayInfoMaterialRes")

        fun load(tables: Tables): TransmogCatalog {
            data class Visual(val display: Int, val icon: Int)
            val appearances = tables.rows("ItemAppearance").associate {
                it.int("ID") to Visual(it.int("ItemDisplayInfoID"), it.int("DefaultIconFileDataID"))
            }
            val items = HashMap<Int, Pair<Int, Visual>>()
            for (row in tables.rows("ItemModifiedAppearance")) {
                val visual = appearances[row.int("ItemAppearanceID")] ?: continue
                if (visual.display <= 0) continue
                val id = row.int("ItemID")
                val modifier = row.int("ItemAppearanceModifierID")
                if (modifier < (items[id]?.first ?: Int.MAX_VALUE)) items[id] = modifier to visual
            }
            val byItem = tables.rows("ItemSparse").mapNotNull { row ->
                val slot = ClothingSlot.fromInventory(row.int("InventoryType")) ?: return@mapNotNull null
                val id = row.int("ID")
                val visual = items[id]?.second ?: return@mapNotNull null
                val name = row["Display_lang"]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                ClothingPiece(id, visual.display, name, slot, visual.icon, row.int("OverallQualityID"), row.int("ExpansionID"))
            }.associateBy { it.item }
            val outfits = tables.rows("ItemSet").mapNotNull { row ->
                val name = row["Name_lang"]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val parts = (0..63).mapNotNull { byItem[row.int("ItemID_$it")] }
                    .distinctBy { it.slot }
                val displays = parts.associate { it.slot.id to it.display }
                val icon = (parts.firstOrNull { it.slot == ClothingSlot.TORSO && it.icon > 0 }
                    ?: parts.firstOrNull { it.icon > 0 })?.icon ?: 0
                if (parts.size < 2) null else ClothingSet(row.int("ID"), name, displays, icon, expansion = parts.first().expansion)
            }
            val pieces = byItem.values.sortedBy { if (it.item in setOf(38, 39, 40, 6098)) 0 else 1 }
                .distinctBy { it.slot to it.display }
                .sortedWith(compareBy({ it.name.lowercase() }, { it.item }))
            return TransmogCatalog(pieces, outfits.sortedBy { it.name.lowercase() }.distinctBy { it.displays })
        }
    }
}
