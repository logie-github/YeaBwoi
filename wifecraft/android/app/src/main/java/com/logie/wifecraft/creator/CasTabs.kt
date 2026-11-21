package com.logie.wifecraft.creator

import kotlin.random.Random

/**
 * The Create-a-Character shell's logic without its screen (ui/CasScreen.kt draws it): the tabs down its side and which of the
 * creator's rows each one shows, the parts of the body a tap can land on and the tab and camera each opens, the history
 * behind Undo and Redo, and Randomize that keeps the locked tabs. The rows are [CreatorModel]'s, every race's and sex's
 * Shadowlands options as before, sorted onto the tabs by their names. Pure JVM.
 */
enum class CasTab(val label: String, val lockable: Boolean) {
    RACE("Race & Sex", false),
    BODY("Body", true),
    FACE("Face", true),
    HAIR("Hair", true),
    MAKEUP("Makeup & Details", true),
    OUTFIT("Outfit", true),
    ACCESSORIES("Accessories", true),
    PERSONALITY("Personality", true),
}

/** The views the camera buttons go to (zoom and the height on the screen that stays put, as the creator's [ViewState]). */
enum class CasCamera(val label: String, val zoom: Float, val focus: Float) {
    FULL("Full body", 1f, 0.6f),
    UPPER("Upper body", 1.9f, 0.62f),
    FACE("Face", 3.0f, 0.76f),
    LEGS("Legs", 1.8f, 0.3f),
}

/** A part of the body a tap lands on (the engine's body pick: Native.pickBodyRegion's codes, bodypick.h's BodyRegion). */
enum class BodyRegion(val code: Int, val label: String) {
    NONE(0, ""), HEAD(1, "Face"), HAIR(2, "Hair"), NECK(3, "Neck"), CHEST(4, "Chest"), WAIST(5, "Waist"), HIPS(6, "Hips"),
    LEGS(7, "Legs"), ARMS(8, "Arms"), HANDS(9, "Hands"), FEET(10, "Feet");

    companion object {
        fun of(code: Int): BodyRegion = entries.firstOrNull { it.code == code } ?: NONE
    }
}

object CasTabs {
    /** The tabs in the rail's order. */
    val ORDER: List<CasTab> = CasTab.entries.toList()

    private val HAIR_WORDS = listOf("hair", "beard", "mustache", "moustache", "sideburn", "goatee", "whisker", "braid", "mane", "ponytail", "bun")
    private val MAKEUP_WORDS = listOf("skin", "tattoo", "scar", "paint", "makeup", "make-up", "lipstick", "eyeshadow", "eye shadow", "eyeliner",
        "freckle", "blemish", "blush", "rune", "marking", "overlay", "fur", "pattern", "glow", "undead")
    private val ACCESSORY_WORDS = listOf("ear", "horn", "jewel", "earring", "necklace", "piercing", "ring", "bracelet", "circlet", "headdress",
        "blindfold", "accessor", "vine", "bandage", "arm band", "armband", "charm", "chain", "feather", "hat", "hood", "gem", "pendant", "crown")
    private val FACE_WORDS = listOf("face", "eye", "brow", "nose", "mouth", "lip", "jaw", "chin", "tusk", "teeth", "tooth", "cheek", "mole",
        "feature", "fang", "mask", "snout", "beak")
    private val BODY_WORDS = listOf("body", "upright", "shape", "tail", "physique", "stature", "build", "style", "density")

    private fun anyWord(name: String, words: List<String>): Boolean {
        val n = name.lowercase()
        // whole words or word starts ("Earrings" is an accessory, "Ear Style" too; "Hearty" is no ear)
        return words.any { w -> Regex("(^|[^a-z])" + Regex.escape(w)).containsMatchIn(n) }
    }

    /**
     * The tab an option of the creator belongs on, by its name (the game's ChrCustomizationOption.Name_lang), else by its
     * category's: Hair the styles, colours and facial hair; Makeup & Details the skin's colour and what lies on it; Accessories
     * ears, horns, jewellery; Face the face, eyes and features; Body the body's own; Gear on Outfit.
     */
    fun tabOf(option: Option, categoryName: String? = null): CasTab {
        if (option.id == CreatorModel.GEAR) return CasTab.OUTFIT
        if (option.id == CreatorModel.UNDEAD || option.id == CreatorModel.UNDEAD_SKIN) return CasTab.MAKEUP
        if (option.id == BodyOptions.SHAPE || option.id == BodyOptions.STYLE || option.id == BodyOptions.DENSITY) return CasTab.BODY
        val n = option.name
        return when {
            anyWord(n, listOf("eyebrow", "brow")) && !anyWord(n, ACCESSORY_WORDS) -> CasTab.FACE
            anyWord(n, listOf("ear")) && !anyWord(n, listOf("earring")) -> CasTab.ACCESSORIES
            anyWord(n, HAIR_WORDS) -> CasTab.HAIR
            anyWord(n, MAKEUP_WORDS) -> CasTab.MAKEUP
            anyWord(n, ACCESSORY_WORDS) -> CasTab.ACCESSORIES
            anyWord(n, FACE_WORDS) -> CasTab.FACE
            anyWord(n, BODY_WORDS) -> CasTab.BODY
            else -> when (categoryName?.lowercase()?.substringBefore(' ')) {
                "hair" -> CasTab.HAIR
                "accessories" -> CasTab.ACCESSORIES
                "markings" -> CasTab.MAKEUP
                "body" -> CasTab.BODY
                else -> CasTab.FACE
            }
        }
    }

    /** Options shown on a second tab as well: the skin's colour on Body (its first section), as on the Makeup tab. */
    fun alsoOn(option: Option): CasTab? = if (option.name.equals(CreatorModel.SKIN_COLOR, true) || option.id == CreatorModel.UNDEAD_SKIN) CasTab.BODY else null

    /**
     * The creator's rows a tab shows: every category's rows ([CreatorModel.rows]) in the game's order, those that belong on
     * [tab] (or are shown there too: [alsoOn]), each option once (Gear, which every category ends with, on Outfit alone).
     */
    fun rows(model: CreatorModel, tab: CasTab): List<CreatorModel.Row> {
        if (tab == CasTab.RACE || tab == CasTab.PERSONALITY) return emptyList()
        val seen = HashSet<Int>()
        val out = ArrayList<CreatorModel.Row>()
        for (c in model.categories()) for (r in model.rows(c.id)) {
            val on = tabOf(r.option, c.name) == tab || alsoOn(r.option) == tab
            if (on && seen.add(r.option.id)) out += r
        }
        return out
    }

    /** Which tab owns each option of the race and sex on (Randomize's locks). */
    fun owners(model: CreatorModel): Map<Int, CasTab> {
        val out = HashMap<Int, CasTab>()
        for (c in model.categories()) for (r in model.rows(c.id)) out.putIfAbsent(r.option.id, tabOf(r.option, c.name))
        for (o in model.model.options) if (o.id !in out) out[o.id] = tabOf(o, model.model.categories.firstOrNull { it.id == o.categoryId }?.name)
        return out
    }

    /** The tab a tapped part of the body opens. */
    fun tabFor(region: BodyRegion): CasTab? = when (region) {
        BodyRegion.NONE -> null
        BodyRegion.HEAD -> CasTab.FACE
        BodyRegion.HAIR -> CasTab.HAIR
        BodyRegion.NECK, BodyRegion.CHEST, BodyRegion.WAIST, BodyRegion.HIPS, BodyRegion.LEGS, BodyRegion.ARMS -> CasTab.BODY
        BodyRegion.HANDS, BodyRegion.FEET -> CasTab.OUTFIT
    }

    /** The view a tapped part of the body brings the camera to. */
    fun cameraFor(region: BodyRegion): CasCamera = when (region) {
        BodyRegion.HEAD, BodyRegion.HAIR, BodyRegion.NECK -> CasCamera.FACE
        BodyRegion.CHEST, BodyRegion.WAIST, BodyRegion.ARMS, BodyRegion.HANDS -> CasCamera.UPPER
        BodyRegion.HIPS, BodyRegion.LEGS, BodyRegion.FEET -> CasCamera.LEGS
        BodyRegion.NONE -> CasCamera.FULL
    }

    /** The sculpt sliders a tapped part of the body is about (shown first, glowing, on the Body tab). */
    fun slidersFor(region: BodyRegion): List<SculptSlider> = when (region) {
        BodyRegion.NECK -> listOf(SculptSlider.SHOULDERS)
        BodyRegion.CHEST -> listOf(SculptSlider.BUST_SIZE, SculptSlider.BUST_LIFT, SculptSlider.SHOULDERS)
        BodyRegion.WAIST -> listOf(SculptSlider.WAIST, SculptSlider.BELLY)
        BodyRegion.HIPS -> listOf(SculptSlider.HIPS, SculptSlider.BUTT)
        BodyRegion.LEGS -> listOf(SculptSlider.THIGHS, SculptSlider.LEG_LENGTH)
        BodyRegion.ARMS -> listOf(SculptSlider.ARMS)
        else -> emptyList()
    }

    /** The camera each tab starts on as it is chosen. */
    fun cameraOf(tab: CasTab): CasCamera = when (tab) {
        CasTab.FACE, CasTab.HAIR -> CasCamera.FACE
        CasTab.MAKEUP, CasTab.ACCESSORIES -> CasCamera.UPPER
        else -> CasCamera.FULL
    }
}

/**
 * Undo and Redo: a list of states and where we are in it. [push] after each change (a state equal to the current one is not
 * a change: nothing is kept); [undo] and [redo] move along it and give the state to put back; a push after undoing drops the
 * states that were ahead. At most [limit] states (the oldest go first). Pure.
 */
class CasHistory<T>(initial: T, val limit: Int = 100) {
    private val states = ArrayList<T>().apply { add(initial) }
    private var at = 0

    val current: T get() = states[at]
    val canUndo: Boolean get() = at > 0
    val canRedo: Boolean get() = at < states.size - 1
    val size: Int get() = states.size

    fun push(state: T): Boolean {
        if (state == states[at]) return false
        while (states.size > at + 1) states.removeAt(states.size - 1)
        states += state
        at = states.size - 1
        while (states.size > limit.coerceAtLeast(2)) { states.removeAt(0); at-- }
        return true
    }

    fun undo(): T? = if (canUndo) states[--at] else null
    fun redo(): T? = if (canRedo) states[++at] else null

    /** Starts again from [state] (another character opened). */
    fun reset(state: T) { states.clear(); states += state; at = 0 }
}

/**
 * Randomize with locks: every option of an unlocked tab a choice at random (kept right with each other as the dice's are:
 * [CreatorModel.randomize]), a locked tab's left as it is; on an unlocked Body the body options where she can have them and
 * the sculpt (only where the Eve body takes it: [sculptable]) a moderate value; on unlocked Personality three traits that go
 * together. A locked Outfit keeps the Gear switch and the class.
 */
object CasRandomizer {
    /** The sculpt's random reach: plausible bodies, never the sliders' far ends. */
    const val SCULPT_REACH = 0.55f

    fun randomize(model: CreatorModel, locks: Set<CasTab>, random: Random = model.random, sculptable: Boolean = false, traits: List<String> = emptyList(),
                  opposed: (String, String) -> Boolean = { _, _ -> false }) {
        val owners = CasTabs.owners(model)
        val before = model.appearance
        model.random = random
        model.randomize(keep = { o -> owners[o.id]?.let { it in locks } == true })
        var a = model.appearance
        if (CasTab.BODY !in locks) {
            if (BodyOptions.eligible(a.raceId, a.sex)) {
                for (id in listOf(BodyOptions.SHAPE, BodyOptions.STYLE, BodyOptions.DENSITY)) {
                    val n = BodyOptions.names(id).size
                    model.choose(id, random.nextInt(n))
                }
                a = model.appearance
            }
            val height = (random.nextFloat() * 2f - 1f) * 0.3f
            val s = if (sculptable) BodySculpt.random(random, SCULPT_REACH).with(SculptSlider.HEIGHT, height) else BodySculpt().with(SculptSlider.HEIGHT, height)
            model.setSculpt(s)
        }
        if (CasTab.PERSONALITY !in locks && traits.isNotEmpty()) {
            val now = model.appearance.personality ?: CasPersonality()
            model.setPersonality(now.copy(traits = CasPersonality.randomTraits(traits, random, opposed)))
        }
        if (CasTab.OUTFIT in locks) model.restoreOutfit(before)
    }
}
