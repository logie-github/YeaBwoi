package com.logie.wifecraft.creator

enum class ClothingSlot(val id: Int, val label: String) {
    HEAD(1, "Head"), SHOULDERS(3, "Shoulders"), SHIRT(4, "Shirt"), TORSO(5, "Torso"),
    WAIST(6, "Waist"), LEGS(7, "Legs"), FEET(8, "Feet"), WRISTS(9, "Wrists"),
    GLOVES(10, "Gloves"), BACK(15, "Back"), TABARD(19, "Tabard");

    companion object {
        fun fromInventory(id: Int) = entries.firstOrNull { it.id == if (id == 20) 5 else id }
    }
}

data class ClothingPiece(val item: Int, val display: Int, val name: String, val slot: ClothingSlot)
data class ClothingSet(val id: Int, val name: String, val displays: Map<Int, Int>)

class TransmogCatalog(val pieces: List<ClothingPiece>, val outfits: List<ClothingSet>) {
    companion object {
        val TABLES = listOf("ItemSparse", "ItemModifiedAppearance", "ItemAppearance", "ItemSet", "ItemDisplayInfoMaterialRes")

        fun load(tables: Tables): TransmogCatalog {
            val appearances = tables.rows("ItemAppearance").associate { it.int("ID") to it.int("ItemDisplayInfoID") }
            val items = HashMap<Int, Pair<Int, Int>>()
            for (row in tables.rows("ItemModifiedAppearance")) {
                val display = appearances[row.int("ItemAppearanceID")] ?: continue
                if (display <= 0) continue
                val id = row.int("ItemID")
                val modifier = row.int("ItemAppearanceModifierID")
                if (modifier < (items[id]?.first ?: Int.MAX_VALUE)) items[id] = modifier to display
            }
            val byItem = tables.rows("ItemSparse").mapNotNull { row ->
                val slot = ClothingSlot.fromInventory(row.int("InventoryType")) ?: return@mapNotNull null
                val id = row.int("ID")
                val display = items[id]?.second ?: return@mapNotNull null
                val name = row["Display_lang"]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                ClothingPiece(id, display, name, slot)
            }.associateBy { it.item }
            val outfits = tables.rows("ItemSet").mapNotNull { row ->
                val name = row["Name_lang"]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val parts = (0..63).mapNotNull { byItem[row.int("ItemID_$it")] }
                    .distinctBy { it.slot }.associate { it.slot.id to it.display }
                if (parts.size < 2) null else ClothingSet(row.int("ID"), name, parts)
            }
            val pieces = byItem.values.sortedWith(compareBy({ it.name.lowercase() }, { it.item }))
                .distinctBy { it.slot to it.display }
            return TransmogCatalog(pieces, outfits.sortedBy { it.name.lowercase() }.distinctBy { it.displays })
        }
    }
}
