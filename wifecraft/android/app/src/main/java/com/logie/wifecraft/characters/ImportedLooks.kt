package com.logie.wifecraft.characters

import com.logie.wifecraft.bodyparts.ModelParts
import com.logie.wifecraft.creator.Appearance
import com.logie.wifecraft.creator.Body
import com.logie.wifecraft.creator.BodyOptions
import com.logie.wifecraft.creator.Catalog
import com.logie.wifecraft.creator.CreatureLooks
import com.logie.wifecraft.creator.DressCall
import com.logie.wifecraft.creator.Dressing
import com.logie.wifecraft.creator.LookResolver
import com.logie.wifecraft.creator.Overrides
import com.logie.wifecraft.creator.ResolvedLook
import com.logie.wifecraft.creator.Sex
import com.logie.wifecraft.creator.Tables

/**
 * What one look draws, the user's imports applied (Ralia's mod or Alternate Female Options, the body options, the undead
 * skins: [Overrides], creator/ImportStore). [look] is drawn; [drawn] is the playable body it is drawn on (its Body options go
 * to Dressing.body, its texture layout to the call), null for a display drawn as the game has it; [character]: a soft body
 * (a playable race's model); [set]: a partial look's model-parts set; [display]: the game's display it stands for (0: none).
 */
class ImportedLook(
    val look: ResolvedLook,
    val drawn: Appearance?,
    val layout: Int,
    val character: Boolean,
    val set: ModelParts.PartSet? = null,
    val display: Int = 0,
    /** A creature NPC (CreatureNpcs: Sindragosa's dragon): game animations only, never scaled to a human's height. */
    val creature: Boolean = false,
) {
    /** The renderer's call: a body's as Dresser makes it (CreatureLooks.call when clothing is worn, else Dressing.call), a display's as CreatureLooks makes it. */
    fun call(t: Tables): DressCall {
        val a = drawn ?: return CreatureLooks.call(t, look)
        return if (a.gear && a.clothing.isNotEmpty()) CreatureLooks.call(t, look, layout) else Dressing.call(t, layout, look)
    }

    /** The body's base skin file (the body parts' and model sets' skin match); 0: none. */
    val skin: Int get() = look.layers.firstOrNull { it.textureType == 1 && it.region == -1 }?.fileId ?: look.textures[1] ?: 0

    /** The same look drawn with [look] in its place (its textures made HD). */
    fun withLook(look: ResolvedLook) = ImportedLook(look, drawn, layout, character, set, display, creature)

    fun with(set: ModelParts.PartSet?) = ImportedLook(look, drawn, layout, character, set, display, creature)
}

/**
 * The one step every dressing path takes to put the user's imports on a look: the live scene and the creator (engine/Dresser),
 * the cast's portraits, the library's pictures and the placing ghost (engine/Thumbs). Pure JVM.
 *
 * - A created NPC, a starter (Mira, Rowan) or an NPC of an older project: its appearance resolved with the imports
 *   (LookResolver with [Overrides]), then [apply]; with Gear off (Appearance.gear) without the skin colour's underwear
 *   ([bare]): nude, as a named character's bare variant.
 * - A named character's body variant (outfit, underwear, bare, nude) and a partial look's stand-in: the variant's appearance,
 *   with the NPC's own Body options and Undead switch where it has them; a bare, nude or partial one without the skin
 *   colour's underwear, the imported underwear included ([bare]).
 * - A display (a named character's display variant, a placed creature, a humanoid NPC): a humanoid display of a playable
 *   race whose body the imports change, or with Body options on, is drawn as that body wearing the display's items
 *   ([composed]: the game's baked skin has the old skin painted in and no import names it); any other display as the game has
 *   it, its textures through [apply]. A display of a model of its own (Sylvanas', Jaina's, Alleria's... every named display
 *   variant today) is not a player body: the imports have nothing of it and it is drawn as the game has it.
 *
 * Where the imports have nothing for a race and sex (no texture of it replaced, no shape or design for it), the game's own
 * files are drawn: [Overrides.texture] gives the retail file back.
 */
object ImportedLooks {
    /** [a] as the roster or the creator keeps it: a named character's choice ([named]) or the appearance itself. */
    fun of(c: Catalog, t: Tables, a: Appearance, named: NamedCast?, o: Overrides): ImportedLook {
        val choice = named?.choiceOf(a)
        if (choice != null && choice.display > 0) {
            val shown = ofDisplay(c, t, choice.display, o, a.body)
            // a creature (Sindragosa's dragon): at her own scale, the game's animations only
            val spec = CreatureNpcs.of(a) ?: return shown
            return ImportedLook(shown.look.copy(scale = spec.scale), null, 0, false, display = shown.display, creature = true)
        }
        val variant = choice?.let { CharacterLooks.appearance(it.variant) }
        val drawn = if (variant == null) a else ownOptions(variant, a)
        // Gear off on a created NPC (or a starter) is nude: the skin colour's underwear goes too, as a bare variant's
        val nude = choice == null && a.named == null && !a.gear
        return ofBody(c, t, drawn, choice?.bare == true || nude, o).with(choice?.set)
    }

    /**
     * A playable body: resolved with the imports, without the skin colour's underwear when [bare], then [apply]. A bare body
     * wearing a tabard over no shirt, chest or legs (a tabard-only look) has the tabard hung as cloth, its layers the flap's
     * own and nothing of it on the chest (LookResolver.hangingCloth).
     */
    fun ofBody(c: Catalog, t: Tables, a: Appearance, bare: Boolean, o: Overrides): ImportedLook {
        val resolved = LookResolver.resolve(c, t, a, o)
        val look = if (bare) LookResolver.hangingCloth(t, a, bare(c, t, a, resolved, o)) else resolved
        return ImportedLook(apply(look, a.sex == Sex.FEMALE, o), a, Dressing.layout(c, t, a), true)
    }

    /** A display ([displayId]); [body]: the NPC's Body options, for a humanoid display drawn as its body. Throws when it can't be drawn. */
    fun ofDisplay(c: Catalog, t: Tables, displayId: Int, o: Overrides, body: Body = Body()): ImportedLook {
        val r = CreatureLooks.check(t, displayId)
        val look = r.look ?: throw IllegalStateException("Display $displayId can't be drawn: ${r.why ?: "it has no look"}.")
        composed(c, t, displayId, o, body)?.let { a ->
            val b = ofBody(c, t, a, false, o)
            return ImportedLook(b.look, a, b.layout, true, display = displayId)
        }
        val female = CharacterLooks.parts(t, displayId)?.sex != Sex.MALE
        return ImportedLook(apply(look, female, o), null, 0, CreatureLooks.isCharacter(t, look), display = displayId)
    }

    /**
     * The body a humanoid display is drawn as with the imports on: its race, sex and kept choices wearing its items
     * (CharacterLooks.parts), with [body]; null when the display is drawn as the game has it: a model of its own, a race the
     * creator hasn't, or imports that change nothing of its body (no texture of it replaced, no Body options on).
     */
    fun composed(c: Catalog, t: Tables, displayId: Int, o: Overrides, body: Body = Body()): Appearance? {
        val p = CharacterLooks.parts(t, displayId) ?: return null
        if (c.model(p.raceId, p.sex) == null) return null
        val a = Appearance(p.raceId, p.sex, p.picks, body, gear = p.equipment.isNotEmpty(), clothing = p.equipment, starterClothing = false)
        if (o.hasBody() && BodyOptions.eligible(p.raceId, p.sex) && (body.shape != 0 || body.style != 0)) return a
        if (!o.hasRalia()) return null
        val with = runCatching { LookResolver.resolve(c, t, a, o) }.getOrNull() ?: return null
        val without = runCatching { LookResolver.resolve(c, t, a, Overrides.None) }.getOrNull() ?: return null
        return a.takeIf { with.layers.map { it.fileId } != without.layers.map { it.fileId } }
    }

    /**
     * Every texture of [look] through the imports (its layers, its whole textures, its extra models' own): the game's file
     * where they have none. Idempotent (an imported file is its own); a look the imports change nothing of comes back as it is.
     */
    fun apply(look: ResolvedLook, female: Boolean, o: Overrides): ResolvedLook {
        if (!o.hasRalia()) return look
        fun f(id: Int) = if (id <= 0) id else o.texture(id, female)
        val layers = look.layers.map { l -> f(l.fileId).let { if (it == l.fileId) l else l.copy(fileId = it) } }
        val textures = LinkedHashMap<Int, Int>().apply { for ((k, v) in look.textures) put(k, f(v)) }
        val extra = look.extraModels.map { e -> if (e.texture == 0) e else f(e.texture).let { if (it == e.texture) e else e.copy(texture = it) } }
        if (layers == look.layers && textures == look.textures && extra == look.extraModels) return look
        val models = (listOf(look.model) + look.extraModels.map { it.model }).toSet()
        val needs = look.needsFiles.mapTo(LinkedHashSet()) { if (it in models) it else f(it) }
        return look.copy(layers = layers, textures = textures, extraModels = extra, needsFiles = needs)
    }

    /** CharacterLooks.bare with the imports: the skin colour's underwear goes whether it is the game's file or the import's. */
    fun bare(c: Catalog, t: Tables, a: Appearance, look: ResolvedLook, o: Overrides): ResolvedLook =
        CharacterLooks.bare(c, t, a, look, o)

    /**
     * A named character's variant appearance with the NPC's own Body options and Undead switch where its appearance has them
     * ([own]: the roster's, which is the variant's unless they were changed), so they are honoured as a created NPC's are.
     */
    fun ownOptions(variant: Appearance, own: Appearance): Appearance {
        var v = variant
        if (own.body != Body() && own.body != variant.body) v = v.copy(body = own.body)
        if (own.undead) v = v.copy(undead = true, undeadSkin = own.undeadSkin)
        return v
    }
}
