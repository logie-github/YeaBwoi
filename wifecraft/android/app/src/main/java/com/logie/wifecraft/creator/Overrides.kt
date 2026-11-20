package com.logie.wifecraft.creator

/**
 * What the user's own imports replace in a look (Ralia's mod, Alternate Female Options, the body options' package,
 * the undead skins, as Wrath++ has them): file ids of files the app holds under files/game/<id> beside the game's own, made by
 * ImportStore. LookResolver.resolve asks it for each texture and for the body options' models and layers.
 * Nothing of any zip is in the repository or the app.
 */
interface Overrides {
    /** The id of the imported texture that replaces the game's file [retailFileId] (Ralia's, Alternate Female Options'), or it. */
    fun texture(retailFileId: Int, female: Boolean): Int
    /** The model the body option Shape [shape] (1..3: Petite, Curvy, Voluptuous; 0 is the race's own) makes of this race's women, or null. */
    fun bodyModel(raceId: Int, shape: Int): Int?
    /** The body layer's design texture for Style [style] (1..16) and Density [density] (1..4), or null (none). */
    fun bodyLayer(raceId: Int, style: Int, density: Int): Int?
    fun hasRalia(): Boolean
    fun hasBody(): Boolean

    /** Wrath++'s undead skins (the user's package) for this race and sex, in the package's order; empty: none. */
    fun undeadSkins(raceId: Int, sex: Sex): List<UndeadSkin> = emptyList()
    /** The body texture of undead skin [colour] (0 the first) of this race and sex, or null. */
    fun undeadSkin(raceId: Int, sex: Sex, colour: Int): Int? = undeadSkins(raceId, sex).getOrNull(colour)?.skin
    /** The face of undead skin [index] (0 the first) of this race and sex, turned as the head's box wants it, or null. */
    fun undeadFace(raceId: Int, sex: Sex, index: Int): Int? = undeadSkins(raceId, sex).getOrNull(index)?.face
    /** The package's word for this race and sex's underwear under an undead skin: "none" or "race" (its own). */
    fun undeadUnderwear(raceId: Int, sex: Sex): String = "race"
    /** The undead skins' package is imported. */
    fun hasUndead(): Boolean = false

    /**
     * What is imported and on now, as a short text that changes whenever a look drawn with these imports could (a set switched
     * on, imported again or removed): the pictures' caches (engine/Thumbs) are named with it. Empty: nothing imported.
     */
    fun signature(): String = ""

    object None : Overrides {
        override fun texture(retailFileId: Int, female: Boolean) = retailFileId
        override fun bodyModel(raceId: Int, shape: Int): Int? = null
        override fun bodyLayer(raceId: Int, style: Int, density: Int): Int? = null
        override fun hasRalia() = false
        override fun hasBody() = false
    }
}
