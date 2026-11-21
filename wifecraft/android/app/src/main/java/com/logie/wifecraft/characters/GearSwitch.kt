package com.logie.wifecraft.characters

import com.logie.wifecraft.creator.Appearance
import com.logie.wifecraft.editor.Actor

/**
 * The Gear switch on any NPC, pure JVM. A created NPC or a starter keeps it in its appearance (Appearance.gear: on, its
 * class's default clothing; off, nude). A named character has no class outfit: Gear on is her signature outfit (her first
 * outfit, or the one chosen if it is worn), Gear off her nude look (her bare variant, else her outfit's nude one, else her
 * underwear one); her variant picker stays, and choosing a variant there sets the switch with it.
 */
object GearSwitch {
    private val WORN = setOf(NamedCast.Kind.OUTFIT, NamedCast.Kind.TABARD, NamedCast.Kind.PARTIAL)

    /** Whether a named character's choice counts as Gear on (something of her outfit worn). */
    fun isOn(choice: NamedCast.Choice): Boolean = choice.kind in WORN

    /** Whether [a] is drawn with Gear on. */
    fun isOn(a: Appearance, cast: NamedCast?): Boolean {
        val choice = cast?.choiceOf(a) ?: return a.gear
        return isOn(choice)
    }

    /**
     * The choice of [c]'s that the switch turned to [on] shows, starting from [current] (her key now): unchanged when it is
     * already on that side; null when she has no choice of that side (Chromie has her outfit only).
     */
    fun choiceFor(cast: NamedCast, c: Character, on: Boolean, current: String?): NamedCast.Choice? {
        val all = cast.choices(c)
        val now = all.firstOrNull { it.key == current }
        if (now != null && isOn(now) == on) return now
        if (on) return all.firstOrNull { it.kind == NamedCast.Kind.OUTFIT } ?: all.firstOrNull { isOn(it) }
        val outfitKey = now?.takeIf { it.kind == NamedCast.Kind.OUTFIT }?.key ?: all.firstOrNull { it.kind == NamedCast.Kind.OUTFIT }?.key
        return all.firstOrNull { it.kind == NamedCast.Kind.BARE }
            ?: all.firstOrNull { it.kind == NamedCast.Kind.NUDE && it.key == "${outfitKey}_nude" }
            ?: all.firstOrNull { it.kind == NamedCast.Kind.NUDE }
            ?: all.firstOrNull { it.kind == NamedCast.Kind.UNDERWEAR }
    }

    /** [actor] with the switch [on]: a named character in the choice [choiceFor] gives (as she is when she has none). */
    fun apply(actor: Actor, on: Boolean, cast: NamedCast?): Actor {
        val n = actor.appearance.named
        val c = n?.let { cast?.character(it.character) }
        if (cast == null || c == null) return actor.copy(appearance = actor.appearance.copy(gear = on))
        val choice = choiceFor(cast, c, on, n.variant) ?: return actor
        val a = cast.appearance(c, choice)
        return actor.copy(appearance = a.copy(parts = actor.appearance.parts, body = actor.appearance.body,
            undead = actor.appearance.undead, undeadSkin = actor.appearance.undeadSkin))
    }

    /** What npc-looks.json keeps for [actor] with the switch [on]: a named character's variant, anyone else's switch. */
    fun remember(looks: NpcLooks, actor: Actor, on: Boolean, cast: NamedCast?): NpcLooks {
        val n = actor.appearance.named
        val c = n?.let { cast?.character(it.character) }
        if (cast != null && c != null) {
            val choice = choiceFor(cast, c, on, n.variant) ?: return looks
            return looks.withVariant(actor.id, choice.key)
        }
        return looks.withGear(actor.id, on)
    }

    /** Gear on or off for everyone in [actors]. */
    fun everyone(looks: NpcLooks, actors: List<Actor>, on: Boolean, cast: NamedCast?): NpcLooks =
        actors.fold(looks) { l, a -> remember(l, a, on, cast) }

    /** Whether everyone in [actors] has Gear on (false for none). */
    fun allOn(actors: List<Actor>, cast: NamedCast?): Boolean = actors.isNotEmpty() && actors.all { isOn(it.appearance, cast) }
}
