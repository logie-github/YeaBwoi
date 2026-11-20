package com.logie.wifecraft.creator

/**
 * Choices of Wifecraft's own, added to an option of the game's for one race and sex ([Catalog.build] appends them to the
 * option's list, after the game's). Each draws as its [base] choice of the game does (the same ChrCustomizationElement
 * rows, and the rows of other choices that hang on the base), with every texture those rows put on laid through the
 * renderer's tint ([tint1] for the texture's light end, [tint2] for its dark end: TextureLayer's tints, the skin
 * compositor's gradient). Ids are negative, never a ChrCustomizationChoice id of the game's (always positive), and are kept
 * for good: a look saved with one keeps it (Appearance's picks), and a look saved before has none.
 */
data class CustomChoice(
    val id: Int, val raceId: Int, val sex: Sex, val optionName: String, val name: String, val swatch: Int,
    val base: Int, val tint1: Int, val tint2: Int,
)

object CustomChoices {
    /**
     * Chromie's hair (a gnome woman's Hair Color): the golden blonde of her own model (display 10008, creature/chromie,
     * file 4218359), sampled from its texture 4218364 (the hair's strands and buns: boxes 640,20-790,290, 530,420-860,500
     * and 10,320-170,420 of the 1024x512 texture) and fitted to the gnome woman's Hair Color textures: the gold one (choice
     * 8752, texture 3448769) laid through #ffffe7 / #392a1e matches her hair's colours best (per channel, 19 quantiles: RMS
     * 8.7 of 255, against 25.9 untinted and 9.3 for the ash 1279 through #ffe78a / #2d0000). Swatch: her hair's median,
     * #aa8035. The hair styles' scalp textures that hang on the gold (material 23226) come with it, tinted alike.
     */
    const val CHROMIE_HAIR = -70001

    val ALL = listOf(
        CustomChoice(CHROMIE_HAIR, 7, Sex.FEMALE, "Hair Color", "Chromie", 0xAA8035, base = 8752, tint1 = 0xFFFFE7, tint2 = 0x392A1E),
    )
}
