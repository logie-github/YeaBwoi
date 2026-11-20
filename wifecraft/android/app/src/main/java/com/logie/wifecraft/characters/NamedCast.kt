package com.logie.wifecraft.characters

import com.logie.wifecraft.bodyparts.ModelParts
import com.logie.wifecraft.creator.Appearance
import com.logie.wifecraft.creator.NamedLook
import com.logie.wifecraft.creator.Sex
import com.logie.wifecraft.editor.Actor

/**
 * The named characters (assets/characters.json) as NPCs of the scene's roster, each with a variant picker. Pure JVM.
 *
 * A character's choices ([choices]): her listed variants in the file's order (outfits, the undressed kinds, the listed
 * "<outfit>_nude" and "<outfit>_tabard" ones), the nude and tabard ones [CharacterLooks.nude] and [CharacterLooks.tabard]
 * make of an outfit that has none listed, and for each outfit
 * that assets/model_parts.json cuts a set from, "<outfit>_partial": her stand-in's bare body wearing the pieces of her own
 * model ([ModelParts]). A choice that can't be drawn is simply not listed (Alexstrasza's Dragonflight model has no set:
 * no partial; Chromie has her outfit only).
 */
class NamedCast(val characters: List<Character>, val manifest: ModelParts.Manifest? = null) {
    /** What one choice draws. */
    enum class Kind { OUTFIT, UNDERWEAR, BARE, NUDE, TABARD, PARTIAL }

    /**
     * One choice of a character's: [variant] the variant drawn (for [Kind.PARTIAL] the stand-in's bare one), [set] the
     * model-parts set ([Kind.PARTIAL] only), [display] the game's NPC display it is drawn as (a display variant), else 0.
     */
    data class Choice(val key: String, val label: String, val kind: Kind, val variant: CharacterVariant, val set: ModelParts.PartSet? = null) {
        val display: Int get() = if (kind != Kind.PARTIAL && variant.isDisplay) variant.display else 0
        /** Drawn without the skin colour's underwear (CharacterLooks.bare after the look is resolved). */
        val bare: Boolean get() = kind == Kind.BARE || kind == Kind.NUDE || kind == Kind.TABARD || kind == Kind.PARTIAL && variant.kind == VariantKind.BARE
    }

    fun character(id: String): Character? = characters.firstOrNull { it.id == id }

    fun choices(c: Character): List<Choice> {
        val out = ArrayList<Choice>()
        val keys = HashSet<String>()
        fun add(choice: Choice) { if (keys.add(choice.key)) out += choice }
        for (v in c.variants) {
            add(Choice(v.key, v.label, kindOf(v), v))
            if (v.kind == VariantKind.OUTFIT) CharacterLooks.nude(v)?.let { n ->
                if (c.variants.none { it.key == n.key }) add(Choice(n.key, n.label, Kind.NUDE, n))
            }
            if (v.kind == VariantKind.OUTFIT) CharacterLooks.tabard(v)?.let { n ->
                if (c.variants.none { it.key == n.key }) add(Choice(n.key, n.label, Kind.TABARD, n))
            }
        }
        val standIn = c.variants.firstOrNull { it.kind == VariantKind.BARE && !it.isDisplay && it.raceId > 0 }
        if (standIn != null && manifest != null) for (v in c.variants) {
            if (v.kind != VariantKind.OUTFIT) continue
            val set = manifest.forLook(c.id, v.key) ?: continue
            add(Choice("${v.key}$PARTIAL_SUFFIX", "${v.label}, partial", Kind.PARTIAL, standIn, set))
        }
        return out
    }

    fun choice(c: Character, key: String?): Choice? {
        val all = choices(c)
        return all.firstOrNull { it.key == key } ?: all.firstOrNull()
    }

    /** The choice an appearance names (its [Appearance.named]); null for a created NPC or one this roster doesn't have. */
    fun choiceOf(a: Appearance): Choice? {
        val n = a.named ?: return null
        val c = character(n.character) ?: return null
        return choices(c).firstOrNull { it.key == n.variant }
    }

    /**
     * The appearance the roster keeps for [c] in [choice]: a body variant's own (CharacterLooks.appearance); for a display
     * variant or a partial one the stand-in's body (her underwear or bare variant's, else any body variant or preset of
     * hers) with no gear, so the scene knows her race and sex. [Appearance.named] says which choice it is.
     */
    fun appearance(c: Character, choice: Choice): Appearance {
        val named = NamedLook(c.id, choice.key)
        val own = if (choice.kind == Kind.PARTIAL || choice.variant.isDisplay) null else CharacterLooks.appearance(choice.variant)
        if (own != null) return own.copy(named = named)
        val body = (c.variants.filter { it.kind == VariantKind.BARE || it.kind == VariantKind.UNDERWEAR } + c.variants + c.presets)
            .firstOrNull { !it.isDisplay && it.raceId > 0 }
        return if (body != null) Appearance(body.raceId, body.sex, body.picks, body.body, gear = false, named = named)
        else Appearance(1, Sex.FEMALE, gear = false, named = named)
    }

    /** The characters as roster NPCs ("character:<id>"), each in the choice [chosen] keeps for it (by actor id), else her first. */
    fun actors(chosen: Map<String, String> = emptyMap()): List<Actor> = characters.mapNotNull { c ->
        val choice = choice(c, chosen[actorId(c.id)]) ?: return@mapNotNull null
        Actor(actorId(c.id), c.name, appearance(c, choice))
    }

    /** Every display a choice is drawn as or a set is cut from: what Options' download fetches for the named characters. */
    fun displays(): Set<Int> {
        val out = LinkedHashSet<Int>()
        for (c in characters) for (ch in choices(c)) {
            if (ch.display > 0) out += ch.display
            ch.set?.display?.takeIf { it > 0 }?.let { out += it }
        }
        return out
    }

    companion object {
        const val PREFIX = "character:"
        const val PARTIAL_SUFFIX = "_partial"

        fun actorId(character: String) = PREFIX + character
        fun isNamed(actorId: String) = actorId.startsWith(PREFIX)

        fun kindOf(v: CharacterVariant): Kind = when (v.kind) {
            VariantKind.OUTFIT -> Kind.OUTFIT
            VariantKind.UNDERWEAR -> Kind.UNDERWEAR
            VariantKind.BARE -> Kind.BARE
            VariantKind.NUDE -> Kind.NUDE
            VariantKind.TABARD -> Kind.TABARD
        }

        /** [charactersJson] and [manifestJson] read; an empty cast when the characters can't be read, no partials without the manifest. */
        fun parse(charactersJson: String?, manifestJson: String?): NamedCast {
            val characters = charactersJson?.let { runCatching { CharacterRoster.parse(it) }.getOrNull() }.orEmpty()
            val manifest = manifestJson?.let { runCatching { ModelParts.parse(it) }.getOrNull() }
            return NamedCast(characters, manifest)
        }
    }
}
