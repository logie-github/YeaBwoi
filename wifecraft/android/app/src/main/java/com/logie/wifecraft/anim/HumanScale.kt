package com.logie.wifecraft.anim

/**
 * Human scale: in the live scene (and the placement editor's ghost of an NPC) every NPC but the dwarves and gnomes stands as
 * tall as a human of the same sex, whatever the race, so imported (Sims) animations and partner animations, made for
 * human-sized bodies, line up. The character creator and the animation library's preview keep each race's own size.
 *
 * **Standing height** (cpp engine/humanscale.h, measured on the model's skeleton): the Head joint's height (the base of the
 * skull, so hair, horns and ears don't count) above the soles, the mean of two stances: the model's own (its bind pose, as
 * Warcraft's animations stand it) and the clips' (every bone the clips aim turned as the reference skeleton's are, so a
 * hunched back stands up and bent legs straighten). They differ by under 4% for every playable race but the tauren (the man
 * 2.55 yards hunched, 3.23 straight), whose size then sits between: about 12% short in his own stance and 12% tall in a clip's.
 * Scale = [MALE_HEIGHT] or [FEMALE_HEIGHT] over the model's standing height. Engine-test (humanscale_test) measures both
 * numbers and checks them against these: the human man (ChrModel 1, file 1011653) and woman (ChrModel 2, file 1000764),
 * each at CreatureDisplayInfo's CreatureModelScale and CreatureModelData's ModelScale of 1 (every playable race's is 1).
 * An orc man is then 0.87 times his size, a tauren man 0.64 (a gnome would be 2.13 and a dwarf 1.30: they keep theirs, below).
 *
 * **Root motion and pairs**: Retarget scales a Sims body's root motion and a pair's offsets to a body whose pelvis is
 * [Retarget]'s reference height (1.4407, the night elf woman's Root), a Sims human 1.4407 tall at the hip. At human scale they
 * are taken by [PAIR], the human man's and woman's mean pelvis (1.1299, 1.2158) over that: across the floor the same for
 * everyone (the engine divides by each actor's scale, so a clip's steps match the pair's slots), up and down by each body's
 * own pelvis (so a sit reaches its floor), and a body whose straightened legs would reach under the floor is lifted.
 * Warcraft's own animations (the emotes, the walk) are in model space and only grow or shrink with the body.
 *
 * Not normalised: a model with no humanoid skeleton (no Root, Head or feet: a creature, a quadruped form) keeps its own
 * scale, and so do the dwarves and gnomes ([keepsOwnSize], by ChrRaces id: Dwarf 3, Dark Iron Dwarf 34, the Earthen 84 and 85,
 * Gnome 7, Mechagnome 37; the user: "keep dwarves and gnomes their original size"): they stand as the creator shows
 * them, [bodyScale] of a human of their sex (engine-test measured the dwarf and the gnome, man and woman; the Mechagnome's
 * skeleton is the gnome's and measures the same; the Dark Iron's and the Earthen's are taken as the dwarf's, not measured).
 *
 * **A gnome's positioning** ([pairScale]): a pair's slots and the clip's root motion across the floor are taken by the
 * gnome's size about the pair's contact point (simulation/PairFit), so a gnome's reach, a small copy of a human's, meets its
 * partner's: engine-test's partner pose, hands apart across the floor, gnome man with a human woman 0.49 yards on the human
 * slot, 0.21 fitted; gnome woman with a human man 0.40, 0.18. Up and down they can't meet standing (the gnome's hands land
 * about 0.8 yards under a human's; lifting the gnome that far would leave it in the air), so no lift is added. A dwarf keeps
 * the human slots (0.18 and 0.27 apart across, 0.4 to 0.5 under: the user asked for gnomes only). Sitting and lying need
 * nothing: the engine puts each body's own pelvis on the seat or bed whatever its size (Scene::alignWithFurniture).
 */
object HumanScale {
    /** The human man's standing height (yards; engine-test measures it). */
    const val MALE_HEIGHT = 1.8518f
    /** The human woman's standing height (yards). */
    const val FEMALE_HEIGHT = 1.7088f
    /** A pair's offsets and a clip's root motion across the floor, in yards per reference unit (the humans' pelvis over 1.4407). */
    const val PAIR = 0.8139f
    /** Below and above this a measured height is taken as a failed measurement (no scaling). */
    const val MIN_HEIGHT = 0.15f
    const val MAX_HEIGHT = 12f
    /** The scale stays within these (the vulpera man, the smallest body at human scale, is 1.68). */
    const val MIN_SCALE = 0.2f
    const val MAX_SCALE = 4f

    /** Where a person's speech bubble hangs above the floor at human scale (the crown of the head, standing). */
    const val HEAD_MALE = 2.05f
    const val HEAD_FEMALE = 1.9f

    /** ChrRaces ids that keep their own size in the live scene: the dwarves (Dwarf, Dark Iron Dwarf, both Earthen) and gnomes. */
    val DWARVES = setOf(3, 34, 84, 85)
    val GNOMES = setOf(7, 37)

    /** A dwarf's and a gnome's standing height over a human's of the same sex (engine-test: 1.4250 / 1.8518, 1.3199 / 1.7088,
     *  0.8688 / 1.8518, 0.8100 / 1.7088). */
    const val DWARF_MALE = 0.7695f
    const val DWARF_FEMALE = 0.7724f
    const val GNOME_MALE = 0.4692f
    const val GNOME_FEMALE = 0.4740f
    /** Where a dwarf's and a gnome's speech bubble hangs, standing (the top of the bald skull, engine-test: 1.559, 1.506,
     *  1.156, 1.107, plus about what the humans' anchors add to theirs). */
    const val HEAD_DWARF_MALE = 1.58f
    const val HEAD_DWARF_FEMALE = 1.52f
    const val HEAD_GNOME_MALE = 1.18f
    const val HEAD_GNOME_FEMALE = 1.13f

    /** Whether race [raceId] (ChrRaces id) keeps its own size in the live scene and the ghost: the dwarves and gnomes. */
    fun keepsOwnSize(raceId: Int): Boolean = raceId in DWARVES || raceId in GNOMES

    /**
     * A body's size in the live scene over a human's of the same sex: 1 for every race drawn at human scale, a dwarf's or a
     * gnome's own for theirs. For whatever sizes things to the body (the furniture's seat and bed heights, a camera's framing).
     */
    fun bodyScale(raceId: Int, female: Boolean): Float = when (raceId) {
        in DWARVES -> if (female) DWARF_FEMALE else DWARF_MALE
        in GNOMES -> if (female) GNOME_FEMALE else GNOME_MALE
        else -> 1f
    }

    /**
     * How a body's pair slots and its clips' root motion across the floor are taken about the pair's contact point
     * (simulation/PairFit): a gnome's size, 1 for everyone else (dwarves included: the user asked for gnomes only).
     */
    fun pairScale(raceId: Int, female: Boolean): Float = if (raceId in GNOMES) bodyScale(raceId, female) else 1f

    /** The height a body of this sex is scaled to. */
    fun target(female: Boolean): Float = if (female) FEMALE_HEIGHT else MALE_HEIGHT

    /**
     * The factor that makes a model whose standing height is [standing] (model units, the engine's measure; 0 when it has no
     * humanoid skeleton) stand [target] tall; 1 (its own size) when it can't be measured.
     */
    fun scale(standing: Float, female: Boolean): Float {
        if (!standing.isFinite() || standing < MIN_HEIGHT || standing > MAX_HEIGHT) return 1f
        return (target(female) / standing).coerceIn(MIN_SCALE, MAX_SCALE)
    }

    /** The bubble's anchor above the feet of a standing person of this sex at human scale. */
    fun head(female: Boolean): Float = if (female) HEAD_FEMALE else HEAD_MALE

    /** The bubble's anchor above the feet of a standing person of race [raceId] in the live scene (a dwarf's, a gnome's own). */
    fun head(raceId: Int, female: Boolean): Float = when (raceId) {
        in DWARVES -> if (female) HEAD_DWARF_FEMALE else HEAD_DWARF_MALE
        in GNOMES -> if (female) HEAD_GNOME_FEMALE else HEAD_GNOME_MALE
        else -> head(female)
    }

    /**
     * Whether the actor under [key] is drawn at human scale: the live scene's NPCs (MainActivity's "<session>:npc:<id>") and
     * the placement editor's ghost ("ghost:"). Not the creator's ("npc-preview") nor the animation library's preview
     * ("animation-preview:..."): they show each race at its own size.
     */
    fun applies(key: String): Boolean = key == GHOST_KEY || key.contains(LIVE_MARK)

    /** engine/SceneView.GHOST_KEY (kept here so this stays pure JVM). */
    const val GHOST_KEY = "ghost:"
    private const val LIVE_MARK = ":npc:"
}
