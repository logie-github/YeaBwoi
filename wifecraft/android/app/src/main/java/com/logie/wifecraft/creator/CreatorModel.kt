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

    /** Whether the body options' package is imported (the screen sets it from Overrides.hasBody()); without it the
     *  Body rows are shown greyed, saying so ([Row.why]), and don't change. */
    var bodyAvailable: Boolean = false

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
        val body = if (BodyOptions.eligible(raceId, sex)) appearance.body else Body()
        appearance = Appearance(next.race.id, next.sex, picks, body, appearance.gear)
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
            out += Row(o, o.choices[i], i, applies, if (applies) null else NONE_HERE)
        }
        if (bodyEligible && isBodyTab(categoryId)) {
            for (id in listOf(BodyOptions.SHAPE, BodyOptions.STYLE, BodyOptions.DENSITY)) {
                val o = BodyOptions.option(id, categoryId)
                val i = BodyOptions.value(appearance.body, id).coerceIn(0, o.choices.size - 1)
                out += Row(o, o.choices[i], i, bodyAvailable, if (bodyAvailable) null else BodyOptions.NOT_IMPORTED)
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
            optionId in BODY_IDS -> {
                if (!bodyAvailable || !bodyEligible) return
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
            optionId in BODY_IDS -> {
                if (!bodyAvailable || !bodyEligible || choiceId !in BodyOptions.names(optionId).indices) return
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

    /** The dice: a choice of every option at random (the body options and Gear kept, as Wrath++'s dice keeps them). */
    fun randomize() {
        val picks = HashMap<Int, Int>()
        for (o in model.options) picks[o.id] = o.choices[random.nextInt(o.choices.size)].id
        appearance = appearance.copy(picks = picks)
        normalize(null)
    }

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
        /** A row with nothing for the character as it is (WrathppCreate.lua's words). */
        const val NONE_HERE = "None for this character"
        private val BODY_IDS = setOf(BodyOptions.SHAPE, BodyOptions.STYLE, BodyOptions.DENSITY)
    }
}
