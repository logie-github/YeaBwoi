package com.logie.wifecraft.play

import com.logie.wifecraft.editor.PlaceKind
import com.logie.wifecraft.editor.Placement
import com.logie.wifecraft.story.Command

// Pure Kotlin (no android.*): the unit tests compile this file alone and drive it with a fake host.

/**
 * Where the things the placement editor put in a scene live (the engine side, wired in MainActivity): [spawn] puts one
 * in the stage, [clear] removes them all (each scene cut), [playAnim] plays a game animation on one. The last two have
 * defaults that do nothing, so a host overrides only what it can.
 */
interface PlaceHost {
    fun spawn(p: Placement)
    fun clear()
    fun playAnim(id: String, anim: Int, loop: Boolean)
    /** `emote ID EMOTE_ID`: the EmoteCatalog emote [emoteId] on the placed thing (its animation and its voice). */
    fun playEmote(id: String, emoteId: Int) = Unit
    /** A scene on a location (`scene loc:embassy:inside day`): load [locationId] in [view]; called before its placements spawn. */
    fun enterLocation(locationId: String, view: String, light: String) = Unit
}

/** The Director's placed things: turns `place`, `anim` and an `emote` on a placed id into [PlaceHost] calls. */
class PlaceDirector(private val host: PlaceHost?) {
    private val placed = mutableSetOf<String>()

    /** A scene cut: every placed thing goes. */
    fun clear() {
        placed.clear()
        host?.clear()
    }

    fun enterLocation(locationId: String, view: String, light: String) {
        host?.enterLocation(locationId, view, light)
    }

    /** True when [c] was about a placed thing (handled, or skipped for lack of a host/while [instant]). */
    fun handle(c: Command, instant: Boolean): Boolean {
        when (c) {
            is Command.Place -> {
                placed += c.id
                val kind = when (c.kind) { "creature" -> PlaceKind.CREATURE; "npc" -> PlaceKind.NPC; else -> PlaceKind.PROP }
                host?.spawn(Placement(c.id, kind, c.asset, c.actor, c.id, c.x, c.y, c.z, c.yaw, c.scale, c.anim, null, c.loop, c.sound))
            }
            is Command.PlaceAnim -> if (!instant && c.id in placed) host?.playAnim(c.id, c.anim, c.loop)
            is Command.Emote -> {
                if (c.who !in placed) return false
                if (!instant) c.emote.toIntOrNull()?.let { host?.playEmote(c.who, it) }
            }
            else -> return false
        }
        return true
    }
}
