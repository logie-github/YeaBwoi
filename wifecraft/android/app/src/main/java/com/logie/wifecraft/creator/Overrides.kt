package com.logie.wifecraft.creator

/**
 * What the user's own imports replace in a look (Ralia's mod, Alternate Female Options, the body options' package,
 * as Wrath++ has them): file ids of files the app holds under files/game/<id> beside the game's own, made by
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

    object None : Overrides {
        override fun texture(retailFileId: Int, female: Boolean) = retailFileId
        override fun bodyModel(raceId: Int, shape: Int): Int? = null
        override fun bodyLayer(raceId: Int, style: Int, density: Int): Int? = null
        override fun hasRalia() = false
        override fun hasBody() = false
    }
}
