package com.logie.wifecraft.creator

/** The equipment slots a look can wear (the game's equipment slot ids: Appearance.clothing's keys). */
enum class ClothingSlot(val id: Int, val label: String) {
    HEAD(1, "Head"), SHOULDERS(3, "Shoulders"), SHIRT(4, "Shirt"), TORSO(5, "Torso"),
    WAIST(6, "Waist"), LEGS(7, "Legs"), FEET(8, "Feet"), WRISTS(9, "Wrists"),
    GLOVES(10, "Gloves"), BACK(15, "Back"), TABARD(19, "Tabard");

    companion object {
        /**
         * An item's slot from its InventoryType: a robe (20) is worn on the chest, a cloak (16, INVTYPE_CLOAK) on the back;
         * 15 is INVTYPE_RANGED (bows), not the back, though the back's equipment slot is 15.
         */
        fun fromInventory(id: Int): ClothingSlot? = when (id) {
            20 -> TORSO
            16 -> BACK
            15 -> null
            else -> entries.firstOrNull { it.id == id }
        }
    }
}

/**
 * A named character's preset (assets/characters.json's presets: negative ids): her worn items by slot ([displays]) and her
 * own choices for her race and sex ([look]). The creator's Presets window offers them (CasPresets.builtIns).
 */
data class ClothingSet(
    val id: Int, val name: String, val displays: Map<Int, Int>, val icon: Int = 0, val look: LookPreset? = null,
    val expansion: Int = -1,
)

/**
 * A named character's own choices for a playable race's body (ChrCustomizationOption id to ChrCustomizationChoice id), as
 * the creator offers them: a preset applies them only to a character of that race and sex ([CreatorModel.applyLook]).
 */
data class LookPreset(val raceId: Int, val sex: Sex, val picks: Map<Int, Int>)
