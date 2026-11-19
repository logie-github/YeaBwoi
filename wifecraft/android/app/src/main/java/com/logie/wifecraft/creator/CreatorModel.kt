package com.logie.wifecraft.creator

import kotlin.random.Random

/**
 * The character creator's logic without its screen, as WrathppCreate.lua's W.Rows, W.Step, W.Choose, Randomize and
 * W.Normalize work it: the categories of the race and sex on, each category's rows (Shadowlands' options in the game's
 * order; on a woman's Body tab Wrath++'s Shape, Style and Density; Gear last in every category), a choice stepped round
 * the ends or picked, and the choices kept right with each other (a face for the skin on, a colour for the pattern
 * picked: [normalize]).
 */
class CreatorModel(val catalog: Catalog, start: Appearance) {
    /** The look on now. */
    var appearance: Appearance = start
        private set

    /** The race and sex on now. */
    var model: ModelEntry = entryFor(start.raceId, start.sex)
        private set

    /**
     * Where the body options' package is asked about (what races it has shapes and designs for): the imports the app made
     * ([ImportStore.current], the one store the app opens), unless a caller names its own. Release 1.0.17 had only a flag
     * here that nothing set, so a woman's Body rows stayed greyed and did nothing even with the package imported.
     */
    var bodySource: Overrides? = null

    private var bodyFlag: Boolean? = null
    private val bodyOverrides: Overrides? get() = bodySource ?: ImportStore.current

    /** Whether the body options' package is imported ([bodySource]'s hasBody(); a caller may set it outright); without it
     *  the Body rows are shown greyed, saying so ([Row.why]), and don't change. */
    var bodyAvailable: Boolean
        get() = bodyFlag ?: (bodyOverrides?.hasBody() == true)
        set(v) { bodyFlag = v }

    /** The package has shapes (Petite, Curvy, Voluptuous) for this race's women: Wrath++'s are made for the Human only so far
     *  ("the other races' shapes are As drawn's model until they are made"); the Shape row says so for the others. */
    fun shapesAvailable(raceId: Int = model.race.id): Boolean {
        if (!bodyAvailable) return false
        val o = bodyOverrides?.takeIf { it.hasBody() } ?: return true      // set outright, nothing to ask: as the flag says
        return (1..3).any { o.bodyModel(raceId, it) != null }
    }

    /** The package has the body layer's designs (Style x Density) for this race (pack2.py: all ten races). */
    fun layersAvailable(raceId: Int = model.race.id): Boolean {
        if (!bodyAvailable) return false
        val o = bodyOverrides?.takeIf { it.hasBody() } ?: return true
        return (1..16).any { s -> (1..4).any { d -> o.bodyLayer(raceId, s, d) != null } }
    }

    /** Why a body row can't change now (null: it can). */
    private fun bodyWhy(optionId: Int): String? = when {
        !bodyAvailable -> BodyOptions.NOT_IMPORTED
        optionId == BodyOptions.SHAPE && !shapesAvailable() -> NOT_FOR_RACE
        optionId != BodyOptions.SHAPE && !layersAvailable() -> NOT_FOR_RACE
        else -> null
    }

    // --- Wrath++'s Undead switch (the imported undead skins) -----------------------------------------------------

    /** The imported undead skins of the race and sex on (empty: none, or nothing imported). */
    fun undeadSkins(): List<UndeadSkin> = bodyOverrides?.undeadSkins(model.race.id, model.sex).orEmpty()

    /** Undead is on and drawn: the switch on and the package has skins for the race and sex on. */
    val isUndead: Boolean get() = appearance.undead && undeadSkins().isNotEmpty()

    /**
     * Where the Undead switch is (WrathppCreate.lua: "Accessories", else the last category): the race's Accessories,
     * else the category today's tables give the options Shadowlands had under Accessories (Mirror: the night elf man's
     * horns and blindfold, the human woman's ears), else the last of the race's own.
     */
    fun undeadCategory(): Int? {
        val own = categories().filter { it.id != BodyOptions.BODY_TAB }
        return (own.firstOrNull { it.name == "Accessories" } ?: own.firstOrNull { it.name == "Mirror" } ?: own.lastOrNull())?.id
    }

    /** The Undead row: on where the package has the race and sex; greyed saying to import it where nothing is imported
     *  (the living races of 3.3.5, as Wrath++ makes them); not shown otherwise (as Wrath++). */
    private fun undeadRow(categoryId: Int): Row? {
        if (categoryId != undeadCategory()) return null
        val o = Option(UNDEAD, "Undead", categoryId, true, listOf(Choice(0, "", null, null), Choice(1, "", null, null)))
        val imported = bodyOverrides?.hasUndead() == true
        return when {
            undeadSkins().isNotEmpty() -> { val i = if (isUndead) 1 else 0; Row(o, o.choices[i], i) }
            !imported && model.race.id in UndeadSkins.RACES -> Row(o, o.choices[0], 0, false, UndeadSkins.NOT_IMPORTED)
            else -> null
        }
    }

    /** The Skin Color row while Undead is on: the undead skins, by the package's names and swatches. */
    private fun undeadSkinRow(skinColour: Option): Row {
        val skins = undeadSkins()
        val o = Option(UNDEAD_SKIN, skinColour.name, skinColour.categoryId, false, skins.mapIndexed { i, k -> Choice(i, k.name, k.swatch, null) })
        val i = appearance.undeadSkin.coerceIn(0, o.choices.size - 1)
        return Row(o, o.choices[i], i)
    }

    /** Undead on or off (W.SetUndead): the living skin colour picked stays in the picks, so off goes back to it. */
    fun setUndead(on: Boolean) {
        val skins = undeadSkins()
        if (on && skins.isEmpty()) return
        appearance = appearance.copy(undead = on, undeadSkin = if (on) appearance.undeadSkin.coerceIn(0, skins.size - 1) else appearance.undeadSkin)
    }

    /** The dice's chance (a test sets its own). */
    var random: Random = Random.Default

    /** One option line: the option, the choice on and its place in the option's choices; greyed with [why] when it can't change. */
    data class Row(val option: Option, val chosen: Choice, val index: Int, val enabled: Boolean = true, val why: String? = null)

    init {
        appearance = appearance.copy(raceId = model.race.id, sex = model.sex)
        normalize(null)
    }

    // --- race and sex -------------------------------------------------------------------------------------------------

    /** Another race: the sex kept where the race has it, each option kept that the race has too (by its name: the same
     *  choice by name, else the same place), the body options while she can have them, Gear as it was. */
    fun setRace(id: Int) {
        val race = catalog.race(id) ?: return
        val sex = if (appearance.sex in race.sexes) appearance.sex else race.sexes.first()
        carryOver(id, sex)
    }

    fun setSex(s: Sex) {
        if (s == appearance.sex || catalog.model(appearance.raceId, s) == null) return
        carryOver(appearance.raceId, s)
    }

    private fun carryOver(raceId: Int, sex: Sex) {
        val old = model
        val oldPicks = current()
        val next = entryFor(raceId, sex)
        val picks = HashMap<Int, Int>()
        for (o in next.options) {
            val before = old.options.firstOrNull { it.name == o.name } ?: continue
            val was = before.choices.indexOfFirst { it.id == oldPicks[before.id] }.takeIf { it >= 0 } ?: continue
            val name = before.choices[was].name
            val same = o.choices.firstOrNull { name.isNotEmpty() && it.name == name } ?: o.choices[minOf(was, o.choices.size - 1)]
            picks[o.id] = same.id
        }
        model = next
        // the body options while she can have them; a shape only where the package has this race's (else As drawn)
        val body = if (!BodyOptions.eligible(raceId, sex)) Body()
            else appearance.body.let { if (it.shape != 0 && bodyAvailable && !shapesAvailable(raceId)) it.copy(shape = 0) else it }
        // Undead kept where the package has the new race and sex too (the same skin's place, or its last)
        val skins = bodyOverrides?.undeadSkins(next.race.id, next.sex).orEmpty()
        val undead = appearance.undead && skins.isNotEmpty()
        appearance = Appearance(next.race.id, next.sex, picks, body, appearance.gear, undead,
            if (undead) appearance.undeadSkin.coerceIn(0, skins.size - 1) else 0, appearance.clothing)
        normalize(null)
    }

    private fun entryFor(raceId: Int, sex: Sex): ModelEntry =
        catalog.model(raceId, sex)
            ?: catalog.race(raceId)?.sexes?.firstNotNullOfOrNull { catalog.model(raceId, it) }
            ?: catalog.races.firstNotNullOf { r -> r.sexes.firstNotNullOfOrNull { catalog.model(r.id, it) } }

    // --- what the screen shows -----------------------------------------------------------------------------------

    private val bodyEligible get() = BodyOptions.eligible(model.race.id, model.sex)

    /** The category buttons: the race's own in Shadowlands' order, a Body category last (Wrath++'s: "the tab needs to be
     *  the last tab"), and for a woman with the body options whose looks have none, a Body tab of its own. */
    fun categories(): List<Category> {
        val own = model.categories.filter { it.name != "Body" } + model.categories.filter { it.name == "Body" }
        if (bodyEligible && own.none { it.name == "Body" }) return own + Category(BodyOptions.BODY_TAB, "Body", (own.maxOfOrNull { it.order } ?: 0) + 1, 100f)
        return own
    }

    private fun isBodyTab(categoryId: Int) = categoryId == BodyOptions.BODY_TAB || model.categories.any { it.id == categoryId && it.name == "Body" }

    /** The rows of a category: its options in the game's order (a colour only while its pattern is picked), on a woman's
     *  Body tab Shape, Style and Density, and Gear last. */
    fun rows(categoryId: Int): List<Row> {
        val cur = current()
        val chosenAll = currentAll().values.toSet()
        val out = ArrayList<Row>()
        for (o in model.options) {
            if (o.categoryId != categoryId) continue
            val m = catalog.master(o.id)
            if (m != null && cur[m]?.let { catalog.isNone(it) } != false) continue          // hidden until a pattern is picked
            if (!catalog.relevant(o.id, chosenAll)) continue                                // hidden while nothing on has its styles
            val id = cur[o.id] ?: continue
            val i = o.choices.indexOfFirst { it.id == id }.coerceAtLeast(0)
            val applies = catalog.applies(o.id, chosenAll)
            if (isUndead && o.name == FACE) continue                                       // an undead skin has its own face
            if (isUndead && o.name == SKIN_COLOR) { out += undeadSkinRow(o); continue }      // the undead skins, the race's own kept
            out += Row(o, o.choices[i], i, applies, if (applies) null else NONE_HERE)
        }
        undeadRow(categoryId)?.let { out += it }
        if (bodyEligible && isBodyTab(categoryId)) {
            for (id in listOf(BodyOptions.SHAPE, BodyOptions.STYLE, BodyOptions.DENSITY)) {
                val o = BodyOptions.option(id, categoryId)
                val i = BodyOptions.value(appearance.body, id).coerceIn(0, o.choices.size - 1)
                val why = bodyWhy(id)
                out += Row(o, o.choices[i], i, why == null, why)
            }
        }
        val gear = gearOption(categoryId)
        val g = if (appearance.gear) 1 else 0
        out += Row(gear, gear.choices[g], g)
        return out
    }

    private fun gearOption(categoryId: Int) = Option(GEAR, "Gear", categoryId, true, listOf(Choice(0, "", null, null), Choice(1, "", null, null)))

    // --- changing it ---------------------------------------------------------------------------------------------

    /** The next ([delta] 1) or previous (-1) choice of an option, round the ends (W.Step). */
    fun step(optionId: Int, delta: Int) {
        when {
            optionId == GEAR -> setGear(!appearance.gear)
            optionId == UNDEAD -> setUndead(!isUndead)
            optionId == UNDEAD_SKIN -> {
                val n = undeadSkins().size
                if (!isUndead || n == 0) return
                appearance = appearance.copy(undeadSkin = Math.floorMod(appearance.undeadSkin.coerceIn(0, n - 1) + delta, n))
            }
            optionId in BODY_IDS -> {
                if (!bodyEligible || bodyWhy(optionId) != null) return
                val n = BodyOptions.names(optionId).size
                val v = Math.floorMod(BodyOptions.value(appearance.body, optionId) + delta, n)
                appearance = appearance.copy(body = BodyOptions.with(appearance.body, optionId, v))
            }
            else -> {
                val o = model.options.firstOrNull { it.id == optionId } ?: return
                val at = o.choices.indexOfFirst { it.id == current()[optionId] }.coerceAtLeast(0)
                choose(optionId, o.choices[Math.floorMod(at + delta, o.choices.size)].id)
            }
        }
    }

    /** A choice picked (from the list, or the switch). */
    fun choose(optionId: Int, choiceId: Int) {
        when {
            optionId == GEAR -> setGear(choiceId != 0)
            optionId == UNDEAD -> setUndead(choiceId != 0)
            optionId == UNDEAD_SKIN -> if (isUndead && choiceId in undeadSkins().indices) appearance = appearance.copy(undeadSkin = choiceId)
            optionId in BODY_IDS -> {
                if (!bodyEligible || bodyWhy(optionId) != null || choiceId !in BodyOptions.names(optionId).indices) return
                appearance = appearance.copy(body = BodyOptions.with(appearance.body, optionId, choiceId))
            }
            else -> {
                val o = model.options.firstOrNull { it.id == optionId } ?: return
                if (o.choices.none { it.id == choiceId }) return
                appearance = appearance.copy(picks = current() + (optionId to choiceId))
                normalize(optionId)
            }
        }
    }

    /** The dice: a choice of every option at random (the body options and Gear kept, as Wrath++'s dice keeps them; with
     *  Undead on, one of the undead skins at random: W.AfterRandomize). */
    fun randomize() {
        val picks = HashMap<Int, Int>()
        for (o in model.options) picks[o.id] = o.choices[random.nextInt(o.choices.size)].id
        appearance = appearance.copy(picks = picks)
        if (isUndead) appearance = appearance.copy(undeadSkin = random.nextInt(undeadSkins().size))
        normalize(null)
    }

    fun setClothing(items: Map<Int, Int>) { appearance = appearance.copy(clothing = items, gear = true) }

    fun setGear(on: Boolean) { appearance = appearance.copy(gear = on) }

    fun resolved(t: Tables): ResolvedLook = LookResolver.resolve(catalog, t, appearance)

    fun resolved(t: Tables, overrides: Overrides): ResolvedLook = LookResolver.resolve(catalog, t, appearance, overrides)

    // --- keeping the choices right with each other ----------------------------------------------------------------

    /** Every shown option's choice on (its pick, else its first). */
    private fun current(): Map<Int, Int> = currentAll().filterKeys { id -> model.options.any { it.id == id } }

    private fun currentAll(): LinkedHashMap<Int, Int> {
        val out = LinkedHashMap<Int, Int>()
        for (o in catalog.allOptions(model.chrModelId)) {
            val pick = appearance.picks[o.id]?.takeIf { p -> o.choices.any { it.id == p } }
            out[o.id] = pick ?: catalog.defaultChoice(o.id) ?: o.choices.first().id
        }
        return out
    }

    /**
     * Each shown option's choice made one that draws with the others on (W.Normalize's work here): a choice that draws
     * nothing with them moves to the nearest of its option's that does (a face for the skin on); the option just picked
     * ([locked]) stays, and what it hangs on, or what hangs on it, moves instead (the skin its face is for, a colour for
     * the pattern picked). A None, and a colour while its pattern is at None, is fine as it is.
     */
    private fun normalize(locked: Int?) {
        val cur = currentAll()
        val shown = model.options.associateBy { it.id }
        repeat(8) {
            var changed = false
            for ((oid, cid) in cur.entries.toList()) {
                val o = shown[oid] ?: continue
                val chosen = cur.values.toSet()
                if (catalog.fine(oid, cid, cur, chosen)) continue
                if (oid != locked) {
                    val at = o.choices.indexOfFirst { it.id == cid }
                    val better = o.choices.withIndex().sortedBy { Math.abs(it.index - at) }
                        .firstOrNull { (_, c) -> c.id != cid && catalog.draws(c.id, chosen - cid + c.id) }
                    if (better != null) { cur[oid] = better.value.id; changed = true; continue }
                }
                if (movePartner(cid, cur, shown, locked ?: oid)) changed = true
            }
            if (!changed) return@repeat
        }
        appearance = appearance.copy(picks = cur.filterKeys { it in shown })
    }

    /** What a choice hangs on, or what hangs on it, set so that it draws; false when nothing could be. */
    private fun movePartner(cid: Int, cur: LinkedHashMap<Int, Int>, shown: Map<Int, Option>, locked: Int): Boolean {
        val candidates = catalog.elements[cid].orEmpty().filter { it.draws && it.related != 0 }.map { it.related } + catalog.relatedTo[cid].orEmpty()
        val byOption = candidates.distinct().groupBy { catalog.optionOf(it) }
        for ((oid, choices) in byOption) {
            if (oid == null || oid == locked || oid !in shown) continue
            val o = shown.getValue(oid)
            val at = o.choices.indexOfFirst { it.id == cur[oid] }
            val pick = choices.minByOrNull { ch -> Math.abs(o.choices.indexOfFirst { it.id == ch } - at) } ?: continue
            if (cur[oid] != pick) { cur[oid] = pick; return true }
        }
        return false
    }

    companion object {
        const val GEAR = -1
        /** Wrath++'s Undead switch, and the Skin Color row of the undead skins while it is on. */
        const val UNDEAD = -1201
        const val UNDEAD_SKIN = -1202
        /** The game's option names (ChrCustomizationOption.Name_lang) Undead stands in for: the face it hides, the skin it lists. */
        const val FACE = "Face"
        const val SKIN_COLOR = "Skin Color"
        /** A row with nothing for the character as it is (WrathppCreate.lua's words). */
        const val NONE_HERE = "None for this character"
        /** A body row the imported package has nothing for in this race (Wrath++'s shapes are the Human's only so far). */
        const val NOT_FOR_RACE = "Not available for this race yet"
        private val BODY_IDS = setOf(BodyOptions.SHAPE, BodyOptions.STYLE, BodyOptions.DENSITY)
    }
}
