package com.logie.wifecraft.play

/** What the story plays: the game's music and sounds, by their names in assets/sounds.json. */
interface Sounds {
    /** Fades to this music ("" for none) over [fadeMs]. */
    fun music(name: String, fadeMs: Int = 1500)
    /** The city's own sound, by light ("day", "night"; "" for none). */
    fun ambience(name: String)
    /** A sound effect (one of its recordings, at random). */
    fun effect(name: String)
    /** A person's voice for an emote (laugh, cheer...), when their voice has one. */
    fun voice(look: String, emote: String)
    /** An interface sound: click, open, close, gossip, ending. */
    fun ui(name: String)

    /** Silence, for tests and before the sounds are fetched. */
    object None : Sounds {
        override fun music(name: String, fadeMs: Int) = Unit
        override fun ambience(name: String) = Unit
        override fun effect(name: String) = Unit
        override fun voice(look: String, emote: String) = Unit
        override fun ui(name: String) = Unit
    }
}
