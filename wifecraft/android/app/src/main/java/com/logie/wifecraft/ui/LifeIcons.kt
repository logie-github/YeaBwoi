package com.logie.wifecraft.ui

import com.logie.wifecraft.simulation.InteractionId

// Pure Kotlin, no android.* and no Compose (JVM-tested: unit-tests/.../ui/LifeHudTest): the life simulation's icon keys
// (Need.icon, Mood.icon, Trait.icon, LifeThought.iconKey, LifeEvent.icon, the radial menu's items) mapped to the game's own
// item and spell icons by file id (interface/icons/*.blp, the ids looked up in the community listfile, the same way
// ui_art.json's "icons" were: never made up), fetched and decoded by GameIcons. Every key also has a drawn glyph, shown until
// the icon is decoded or when it can't be had: an icon is never blank.

/** The drawn stand-ins (LifePanels.kt draws them). */
enum class LifeGlyph { MUG, BREAD, BED, BOOK, NOTE, HEART, SUN, MOON, DROP, PEOPLE, SMILE, FROWN, STAR, FLAME, GIFT, QUESTION, SEAT, FOOT, STORM }

/** A key's game icon ([fileId], with its [name] for reference) and its drawn [glyph]. */
data class LifeIcon(val fileId: Int, val name: String, val glyph: LifeGlyph)

object LifeIcons {
    // the game's icons used (file id: interface/icons/<name>.blp)
    private val MUG = LifeIcon(132792, "inv_drink_05", LifeGlyph.MUG)
    private val BREAD = LifeIcon(134051, "inv_misc_food_95_grainbread", LifeGlyph.BREAD)
    private val SLEEP = LifeIcon(136090, "spell_nature_sleep", LifeGlyph.BED)
    private val PILLOW = LifeIcon(4624626, "inv_10_tailoring2_pillow_red", LifeGlyph.SEAT)
    private val BOOK = LifeIcon(133741, "inv_misc_book_09", LifeGlyph.BOOK)
    private val DRUM = LifeIcon(133841, "inv_misc_drum_01", LifeGlyph.NOTE)
    private val FLUTE = LifeIcon(133942, "inv_misc_flute_01", LifeGlyph.NOTE)
    private val VALENTINE = LifeIcon(236709, "achievement_worldevent_valentine", LifeGlyph.HEART)
    private val CANDY = LifeIcon(135451, "inv_valentinescandy", LifeGlyph.HEART)
    private val BROKEN_HEART = LifeIcon(135767, "spell_brokenheart", LifeGlyph.FROWN)
    private val SUN = LifeIcon(135972, "spell_holy_searinglight", LifeGlyph.SUN)
    private val MOON = LifeIcon(136057, "spell_nature_moonglow", LifeGlyph.MOON)
    private val WATER = LifeIcon(135861, "spell_frost_summonwaterelemental", LifeGlyph.DROP)
    private val GROUP = LifeIcon(134149, "inv_misc_groupneedmore", LifeGlyph.PEOPLE)
    private val SMILEY = LifeIcon(236550, "achievement_halloween_smiley_01", LifeGlyph.SMILE)
    private val HAPPY = LifeIcon(237554, "spell_misc_emotionhappy", LifeGlyph.SMILE)
    private val SAD = LifeIcon(237555, "spell_misc_emotionsad", LifeGlyph.FROWN)
    private val ANGRY = LifeIcon(237553, "spell_misc_emotionangry", LifeGlyph.STORM)
    private val TOY = LifeIcon(134506, "inv_misc_toy_02", LifeGlyph.STAR)
    private val FLOWER = LifeIcon(133939, "inv_misc_flower_02", LifeGlyph.STAR)
    private val HEAL = LifeIcon(135915, "spell_holy_heal", LifeGlyph.HEART)
    private val GIFT = LifeIcon(134141, "inv_misc_gift_02", LifeGlyph.GIFT)
    private val TRACK = LifeIcon(132328, "ability_tracking", LifeGlyph.FOOT)
    private val SPRINT = LifeIcon(132307, "ability_rogue_sprint", LifeGlyph.FOOT)
    private val FIRE = LifeIcon(135805, "spell_fire_fire", LifeGlyph.FLAME)
    private val QUESTION = LifeIcon(134400, "inv_misc_questionmark", LifeGlyph.QUESTION)
    private val INSIGHT = LifeIcon(135895, "spell_holy_divineillumination", LifeGlyph.STAR)
    private val GUIDANCE = LifeIcon(135977, "spell_holy_spiritualguidence", LifeGlyph.SUN)
    private val ALE = LifeIcon(133696, "inv_misc_beer_01", LifeGlyph.MUG)
    private val MIND = LifeIcon(135934, "spell_holy_mindvision", LifeGlyph.STAR)
    private val SHOUT = LifeIcon(132333, "ability_warrior_battleshout", LifeGlyph.STORM)
    private val CHARM = LifeIcon(136129, "spell_shadow_charm", LifeGlyph.SMILE)
    private val COWER = LifeIcon(132118, "ability_druid_cower", LifeGlyph.FOOT)
    private val PRAYER = LifeIcon(135946, "spell_holy_prayerofspirit", LifeGlyph.PEOPLE)
    private val CANDLE = LifeIcon(133750, "inv_misc_candle_01", LifeGlyph.FLAME)

    val TABLE: Map<String, LifeIcon> = buildMap {
        // the needs (Need.icon)
        put("need_energy", SLEEP); put("need_hunger", BREAD); put("need_thirst", MUG); put("need_social", GROUP)
        put("need_fun", TOY); put("need_hygiene", WATER); put("need_comfort", PILLOW)
        // the moods (Mood.icon)
        put("mood_happy", HAPPY); put("mood_content", HAPPY); put("mood_inspired", INSIGHT); put("mood_playful", SMILEY)
        put("mood_flirty", VALENTINE); put("mood_fine", HAPPY); put("mood_tired", SLEEP); put("mood_hungry", BREAD)
        put("mood_thirsty", MUG); put("mood_lonely", GROUP); put("mood_bored", TOY); put("mood_uncomfortable", PILLOW)
        put("mood_grubby", WATER); put("mood_irritable", ANGRY); put("mood_sad", SAD)
        // the traits (Trait.icon)
        put("trait_early_bird", SUN); put("trait_night_owl", MOON); put("trait_social", GROUP); put("trait_loner", COWER)
        put("trait_glutton", ALE); put("trait_ascetic", MIND); put("trait_romantic", VALENTINE); put("trait_playful", SMILEY)
        put("trait_bookish", BOOK); put("trait_brave", SHOUT); put("trait_mischievous", CHARM); put("trait_calm", GUIDANCE)
        // the feed (LifeEvent.icon)
        put("event_wake", SUN); put("event_sleep", SLEEP); put("event_eat", BREAD); put("event_drink", MUG)
        put("event_read", BOOK); put("event_dance", DRUM); put("event_wash", WATER); put("event_refused", SAD)
        put("event_gift", GIFT); put("event_argue", ANGRY); put("event_friends", GROUP); put("event_falling_out", BROKEN_HEART)
        put("event_romance", VALENTINE)
        // thoughts about something missing, and the radial's own
        put("thought_missing", QUESTION); put("radial_animations", DRUM)
        // what someone is doing (thought_<interaction>) and the radial's items (interaction_<interaction>)
        for (id in InteractionId.entries) {
            val icon = interaction(id)
            put("thought_" + id.name.lowercase(), icon)
            put(interactionKey(id), icon)
        }
    }

    private fun interaction(id: InteractionId): LifeIcon = when (id) {
        InteractionId.SLEEP, InteractionId.NAP -> SLEEP
        InteractionId.REST, InteractionId.RELAX, InteractionId.INVITE_SIT -> PILLOW
        InteractionId.EAT -> BREAD
        InteractionId.DRINK -> MUG
        InteractionId.READ -> BOOK
        InteractionId.DANCE, InteractionId.DANCE_TOGETHER -> DRUM
        InteractionId.WASH, InteractionId.TIDY -> WATER
        InteractionId.STRETCH -> SPRINT
        InteractionId.WARM -> FIRE
        InteractionId.WANDER -> TRACK
        InteractionId.CHAT -> GROUP
        InteractionId.JOKE -> SMILEY
        InteractionId.COMPLIMENT -> FLOWER
        InteractionId.HUG -> HEAL
        InteractionId.FLIRT -> VALENTINE
        InteractionId.KISS -> CANDY
        InteractionId.ARGUE -> ANGRY
        InteractionId.GIVE_GIFT -> GIFT
        InteractionId.PLAY_TOGETHER -> FLUTE
    }

    /** The radial menu's key for [id]. */
    fun interactionKey(id: InteractionId): String = "interaction_" + id.name.lowercase()

    /** [key]'s icon; a key not in the table gets one by its kind (never nothing). */
    fun of(key: String?): LifeIcon {
        key ?: return QUESTION
        TABLE[key]?.let { return it }
        return when {
            key.startsWith("mood_") -> HAPPY
            key.startsWith("need_") -> PILLOW
            key.startsWith("event_") -> CANDLE
            key.startsWith("trait_") -> MIND
            else -> QUESTION
        }
    }
}
