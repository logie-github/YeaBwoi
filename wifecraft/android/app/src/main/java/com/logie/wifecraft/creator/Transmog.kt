package com.logie.wifecraft.creator

enum class ClothingSlot(val id: Int, val label: String) {
    HEAD(1, "Head"), SHOULDERS(3, "Shoulders"), SHIRT(4, "Shirt"), TORSO(5, "Torso"),
    WAIST(6, "Waist"), LEGS(7, "Legs"), FEET(8, "Feet"), WRISTS(9, "Wrists"),
    GLOVES(10, "Gloves"), BACK(15, "Back"), TABARD(19, "Tabard");

    companion object {
        fun fromInventory(id: Int) = entries.firstOrNull { it.id == if (id == 20) 5 else id }
    }
}

data class ClothingPiece(val item: Int, val display: Int, val name: String, val slot: ClothingSlot, val icon: Int = 0)
data class ClothingSet(val id: Int, val name: String, val displays: Map<Int, Int>, val icon: Int = 0)

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
                ClothingPiece(id, visual.display, name, slot, visual.icon)
            }.associateBy { it.item }
            val outfits = tables.rows("ItemSet").mapNotNull { row ->
                val name = row["Name_lang"]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val parts = (0..63).mapNotNull { byItem[row.int("ItemID_$it")] }
                    .distinctBy { it.slot }
                val displays = parts.associate { it.slot.id to it.display }
                val icon = (parts.firstOrNull { it.slot == ClothingSlot.TORSO && it.icon > 0 }
                    ?: parts.firstOrNull { it.icon > 0 })?.icon ?: 0
                if (parts.size < 2) null else ClothingSet(row.int("ID"), name, displays, icon)
            }
            val pieces = byItem.values.sortedBy { if (it.item in setOf(38, 39, 40, 6098)) 0 else 1 }
                .distinctBy { it.slot to it.display }
                .sortedWith(compareBy({ it.name.lowercase() }, { it.item }))
            return TransmogCatalog(pieces, outfits.sortedBy { it.name.lowercase() }.distinctBy { it.displays })
        }
    }
}
