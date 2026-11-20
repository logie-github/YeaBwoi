package com.logie.wifecraft.creator

/** ChrModel.Sex 0 and 1. */
enum class Sex { MALE, FEMALE }

data class Race(val id: Int, val key: String, val name: String, val alliance: Boolean, val sexes: Set<Sex>)
data class Category(val id: Int, val name: String, val order: Int, val zoom: Float)
/** A choice of an option; swatches 0xRRGGBB (the game's SwatchColor_0/1), null where it has none. */
data class Choice(val id: Int, val name: String, val swatch1: Int?, val swatch2: Int?)
data class Option(val id: Int, val name: String, val categoryId: Int, val isSwitch: Boolean, val choices: List<Choice>)
data class ModelEntry(val chrModelId: Int, val race: Race, val sex: Sex, val categories: List<Category>, val options: List<Option>)

/**
 * What the character creator offers, read from the game's tables (as Wrath++'s `tools/sl_creator.py` and
 * `sl_inventory.py` read them, without 3.3.5's five numbers): every playable race and allied race that has a ChrModel
 * (ChrRaces, ChrRaceXChrModel, ChrModel), both sexes, each with Shadowlands' categories and options in the game's order
 * (ChrCustomizationOption.OrderIndex) and each option's choices in the order the game's creator lists them
 * (ChrCustomizationChoice.UiOrderIndex), with their names and colour swatches.
 *
 * Left out: the druids' forms (categories with a SpellShapeshiftFormID), and from the rows an option with no name or
 * fewer than two choices (it is still resolved, at its first choice: [hiddenOptions]).
 * Races: a race whose model the game gives no sex (Dracthyr's dragon) is offered as the form whose sexes it has
 * (ChrRaces.UnalteredVisualRaceID: the visage); a race sharing its models with another (the Pandaren's three, each
 * faction's Earthen and Haranir) is offered once, by the Alliance's (else the lowest) id.
 * Order: the Alliance's then the Horde's (neutral counts as Horde), the original races before the allied
 * (ChrRaces.Flags 0x80000), each by ChrRaces.UiDisplayOrder and id.
 */
class Catalog internal constructor(
    val races: List<Race>,
    private val entries: Map<Pair<Int, Sex>, ModelEntry>,
    private val allByModel: Map<Int, List<Option>>,
    private val defaults: Map<Int, Int>,
    private val optionOfChoice: Map<Int, Int>,
    private val choiceOrder: Map<Int, Int>,
    internal val elements: Map<Int, List<El>>,
    internal val relatedTo: Map<Int, List<Int>>,
    private val masters: Map<Int, Int>,
    /** Wifecraft's own choices ([CustomChoices]) in these options, by id. */
    internal val customs: Map<Int, CustomChoice> = emptyMap(),
) {
    private val choicesOf: Map<Int, List<Int>> = optionOfChoice.entries.groupBy({ it.value }, { it.key })
    private val contextuals: Set<Int> = allByModel.values.flatten().filter { o ->
        o.choices.all { elements[it.id].isNullOrEmpty() } && o.choices.any { !relatedTo[it.id].isNullOrEmpty() } && o.id !in masters.values
    }.mapTo(HashSet()) { it.id }

    fun model(raceId: Int, sex: Sex): ModelEntry? = entries[raceId to sex]

    fun race(raceId: Int): Race? = races.firstOrNull { it.id == raceId }

    /** Every option of a model (shown or not), in the game's order. */
    internal fun allOptions(chrModelId: Int): List<Option> = allByModel[chrModelId].orEmpty()

    /** The options of a model the rows don't show (nameless, a single choice), resolved at their first choice. */
    internal fun hiddenOptions(entry: ModelEntry): List<Option> {
        val shown = entry.options.mapTo(HashSet()) { it.id }
        return allOptions(entry.chrModelId).filter { it.id !in shown }
    }

    /** The choice an option starts at: its first by ChrCustomizationChoice.OrderIndex. */
    internal fun defaultChoice(optionId: Int): Int? = defaults[optionId]

    internal fun optionOf(choiceId: Int): Int? = optionOfChoice[choiceId]

    /**
     * The pattern option that a colour option paints (War Paint for War Paint Color, Tattoo for Tattoo Color, Markings
     * for Markings Color): the pattern's choices draw nothing themselves, the colour's material rows hang on them
     * (ChrCustomizationElement.RelatedChrCustomizationChoiceID). The colour is shown only while a pattern is picked.
     */
    internal fun master(optionId: Int): Int? = masters[optionId]

    /**
     * An option that only styles what other options' choices draw (Eye Style for the eye colours that have styles): its
     * choices draw nothing themselves, other options' rows hang on them, and it paints no pattern's colour. It matters,
     * and is shown, only while a choice that hangs on it is on ([relevant]).
     */
    internal fun contextual(optionId: Int): Boolean = optionId in contextuals

    internal fun relevant(optionId: Int, chosen: Set<Int>): Boolean =
        !contextual(optionId) || withBases(chosen).let { on -> choicesOf[optionId].orEmpty().any { c -> relatedTo[c].orEmpty().any { it in on } } }

    /**
     * Whether any choice of an option draws with the other choices on: an option whose every choice hangs on choices
     * that aren't on (the Goblin's faces with a skin that has its face in it) has nothing for the character as it is
     * (WrathppCreate.lua's "None for this character"). A pattern always has something (its colour follows it).
     */
    internal fun applies(optionId: Int, chosen: Set<Int>): Boolean {
        if (optionId in masters.values) return true
        val mine = choicesOf[optionId].orEmpty()
        val others = chosen - mine.toSet()
        return mine.any { isNone(it) && mine.size == 1 || draws(it, others + it) }
    }

    /** [chosen] with the game's choice each of Wifecraft's own in it draws as ([CustomChoice.base]). */
    internal fun withBases(chosen: Set<Int>): Set<Int> =
        if (customs.isEmpty() || chosen.none { it in customs }) chosen else chosen + chosen.mapNotNull { customs[it]?.base }

    /** The custom choice's tints (0xRRGGBB, light end then dark end), null for a choice of the game's. */
    internal fun tintOf(choiceId: Int): Pair<Int, Int>? = customs[choiceId]?.let { it.tint1 to it.tint2 }

    /** A choice that draws nothing and that nothing hangs on: a "None". */
    internal fun isNone(choiceId: Int): Boolean = elements[choiceId].isNullOrEmpty() && relatedTo[choiceId].isNullOrEmpty()

    /** Whether a choice draws something with the other choices [chosen] (its own rows that apply, or rows hanging on it). */
    internal fun draws(choiceId: Int, chosen: Set<Int>): Boolean {
        val on = withBases(chosen)
        if (elements[choiceId].orEmpty().any { it.draws && (it.related == 0 || it.related in on) }) return true
        return relatedTo[choiceId].orEmpty().any { it in chosen }
    }

    /** Whether the choice on of [optionId] is right with the others: it draws, it is a None, or its pattern is at None. */
    internal fun fine(optionId: Int, choiceId: Int, current: Map<Int, Int>, chosen: Set<Int>): Boolean {
        if (isNone(choiceId) || draws(choiceId, chosen) || !relevant(optionId, chosen) || !applies(optionId, chosen)) return true
        val m = master(optionId) ?: return false
        return current[m]?.let { isNone(it) } ?: true
    }

    internal fun orderOf(choiceId: Int): Int = choiceOrder[choiceId] ?: 0

    /** A row of ChrCustomizationElement, by the ids it names (0: none). */
    internal class El(
        val related: Int, val geoset: Int, val skinned: Int, val material: Int, val boneSet: Int, val condModel: Int,
        val itemGeo: Int, val other: String?,
    ) {
        /** Something Wifecraft's renderer draws: geosets, models, textures, face bones, another model. */
        val draws get() = geoset != 0 || skinned != 0 || material != 0 || boneSet != 0 || condModel != 0
    }

    companion object {
        private const val ALLIED_FLAG = 0x80000
        private const val NPC_ONLY_FLAG = 0x1
        /** ChrCustomizationReq.ReqType of the barbershop's "Transmog" choices (keep what is on): not a look. */
        private const val TRANSMOG_REQ = 4
        private val OTHER_KINDS = listOf("ChrCustomizationDisplayInfoID", "ChrCustomizationVoiceID", "AnimKitID", "ParticleColorID", "ChrCustGeoComponentLinkID")

        fun build(t: Tables, custom: List<CustomChoice> = CustomChoices.ALL): Catalog {
            val chrModels = t.rows("ChrModel").associateBy { it.int("ID") }
            val raceRows = t.rows("ChrRaces").associateBy { it.int("ID") }
            val modelsOfRace = HashMap<Int, MutableMap<Sex, Int>>()
            for (x in t.rows("ChrRaceXChrModel")) {
                val m = chrModels[x.int("ChrModelID")] ?: continue
                val sex = when (m.int("Sex")) { 0 -> Sex.MALE; 1 -> Sex.FEMALE; else -> null } ?: continue
                modelsOfRace.getOrPut(x.int("ChrRacesID")) { sortedMapOf() }.putIfAbsent(sex, m.int("ID"))
            }
            // the playable races, each with the models of its sexes
            data class Pick(val row: Map<String, String>, val models: Map<Sex, Int>)
            val picks = ArrayList<Pick>()
            for ((id, r) in raceRows) {
                if (r.int("PlayableRaceBit") < 0 || r.int("Flags") and NPC_ONLY_FLAG != 0) continue
                val own = modelsOfRace[id]
                val models = if (!own.isNullOrEmpty()) own else modelsOfRace[r.int("UnalteredVisualRaceID")] ?: continue
                if (models.isEmpty()) continue
                picks += Pick(r, models)
            }
            // one race a set of models: the Alliance's, else the lowest id
            val bySet = picks.groupBy { it.models.values.toSortedSet() }.values.map { same ->
                same.minWith(compareBy<Pick>({ if (it.row.int("Alliance") == 0) 0 else 1 }, { it.row.int("ID") }))
            }
            val ordered = bySet.sortedWith(compareBy<Pick>(
                { if (it.row.int("Flags") and ALLIED_FLAG != 0) 1 else 0 },
                { if (it.row.int("Alliance") == 0) 0 else 1 },
                { it.row.int("UiDisplayOrder") }, { it.row.int("ID") }))

            val categories = t.rows("ChrCustomizationCategory").associateBy { it.int("ID") }
            val reqTypes = t.rows("ChrCustomizationReq").associate { it.int("ID") to it.int("ReqType") }
            val choicesOf = HashMap<Int, MutableList<Map<String, String>>>()
            for (c in t.rows("ChrCustomizationChoice")) choicesOf.getOrPut(c.int("ChrCustomizationOptionID")) { ArrayList() } += c
            val optionsOf = HashMap<Int, MutableList<Map<String, String>>>()
            val wanted = ordered.flatMap { it.models.values }.toSet()
            for (o in t.rows("ChrCustomizationOption")) {
                val m = o.int("ChrModelID")
                if (m in wanted) optionsOf.getOrPut(m) { ArrayList() } += o
            }

            val races = ArrayList<Race>()
            val entries = HashMap<Pair<Int, Sex>, ModelEntry>()
            val allByModel = HashMap<Int, List<Option>>()
            val defaults = HashMap<Int, Int>()
            val optionOfChoice = HashMap<Int, Int>()
            val choiceOrder = HashMap<Int, Int>()
            val modelOptions = HashMap<Int, Pair<List<Option>, List<Category>>>()
            // Wifecraft's own choices, by the model and option they are added to
            val customsOf = HashMap<Pair<Int, String>, MutableList<CustomChoice>>()
            for (cc in custom) modelsOfRace[cc.raceId]?.get(cc.sex)?.let { customsOf.getOrPut(it to cc.optionName) { ArrayList() } += cc }
            val customs = HashMap<Int, CustomChoice>()
            // every choice of these models (the Transmog ones too) to its model, for the rows that hang on a choice
            val modelOfChoice = HashMap<Int, Int>()
            for ((m, os) in optionsOf) for (o in os) for (c in choicesOf[o.int("ID")].orEmpty()) modelOfChoice[c.int("ID")] = m
            for (chrModel in wanted) {
                val all = ArrayList<Option>()
                val shown = ArrayList<Option>()
                val cats = LinkedHashMap<Int, Category>()
                for (o in optionsOf[chrModel].orEmpty().sortedWith(compareBy({ it.int("OrderIndex") }, { it.int("ID") }))) {
                    val cat = categories[o.int("ChrCustomizationCategoryID")] ?: continue
                    if (cat.int("SpellShapeshiftFormID") != 0) continue
                    val rows = choicesOf[o.int("ID")].orEmpty().filter { reqTypes[it.int("ChrCustomizationReqID")] != TRANSMOG_REQ }
                    if (rows.isEmpty()) continue
                    val game = rows.sortedWith(compareBy({ it.int("UiOrderIndex") }, { it.int("OrderIndex") }, { it.int("ID") })).map {
                        Choice(it.int("ID"), it["Name_lang"].orEmpty(), swatch(it.int("SwatchColor_0")), swatch(it.int("SwatchColor_1")))
                    }
                    // after the game's, each of Wifecraft's own whose base is one of this option's
                    val own = customsOf[chrModel to o["Name_lang"].orEmpty()].orEmpty().filter { cc -> game.any { it.id == cc.base } }
                    val choices = game + own.map { Choice(it.id, it.name, it.swatch and 0xFFFFFF, null) }
                    val option = Option(o.int("ID"), o["Name_lang"].orEmpty(), cat.int("ID"), o.int("OptionType") == 1, choices)
                    all += option
                    for (c in rows) { optionOfChoice[c.int("ID")] = option.id; choiceOrder[c.int("ID")] = c.int("OrderIndex") }
                    for (cc in own) { optionOfChoice[cc.id] = option.id; choiceOrder[cc.id] = Int.MAX_VALUE; customs[cc.id] = cc }
                    defaults[option.id] = rows.minWith(compareBy({ it.int("OrderIndex") }, { it.int("ID") })).int("ID")
                    if (option.name.isNotBlank() && choices.size >= 2) {
                        shown += option
                        cats.getOrPut(cat.int("ID")) {
                            Category(cat.int("ID"), cat["CategoryName_lang"].orEmpty().substringBefore(" ("), cat.int("OrderIndex"), cat.float("CameraZoomLevel"))
                        }
                    }
                }
                allByModel[chrModel] = all
                modelOptions[chrModel] = shown to cats.values.sortedWith(compareBy({ it.order }, { it.id }))
            }
            for (p in ordered) {
                val r = p.row
                val race = Race(r.int("ID"), r["ClientFileString"].orEmpty(), r["Name_lang"].orEmpty(), r.int("Alliance") == 0, p.models.keys.toSet())
                races += race
                for ((sex, chrModel) in p.models) {
                    val (shown, cats) = modelOptions[chrModel] ?: continue
                    entries[race.id to sex] = ModelEntry(chrModel, race, sex, cats, shown)
                }
            }

            // the elements of these choices, and what hangs on each choice
            val elements = HashMap<Int, MutableList<El>>()
            val relatedTo = HashMap<Int, MutableList<Int>>()
            for (e in t.rows("ChrCustomizationElement")) {
                val choice = e.int("ChrCustomizationChoiceID")
                if (choice !in optionOfChoice) continue
                val other = OTHER_KINDS.firstOrNull { e.int(it) != 0 }
                // a row hanging on another model's choice (the Dracthyr's Razor Tip on an Ears of another race's) could
                // never be shown: it is the choice's own
                val related = e.int("RelatedChrCustomizationChoiceID").let { r -> if (r != 0 && modelOfChoice[r] != modelOfChoice[choice]) 0 else r }
                val el = El(related, e.int("ChrCustomizationGeosetID"), e.int("ChrCustomizationSkinnedModelID"),
                    e.int("ChrCustomizationMaterialID"), e.int("ChrCustomizationBoneSetID"), e.int("ChrCustomizationCondModelID"),
                    e.int("ChrCustItemGeoModifyID"), other)
                if (!el.draws && el.itemGeo == 0 && other == null) continue          // an empty row
                elements.getOrPut(choice) { ArrayList() } += el
                if (el.related != 0 && el.draws) relatedTo.getOrPut(el.related) { ArrayList() }.let { if (choice !in it) it += choice }
            }
            // Wifecraft's own choices draw as their bases: the same rows, and what hangs on the base hangs on them
            for (cc in customs.values) {
                elements[cc.base]?.let { elements[cc.id] = ArrayList(it) }
                relatedTo[cc.base]?.let { relatedTo[cc.id] = ArrayList(it) }
            }
            // the colours that paint a pattern: every row of theirs that hangs on a choice hangs on one option's, whose
            // choices draw nothing themselves
            val masters = HashMap<Int, Int>()
            for (options in allByModel.values) {
                for (o in options) {
                    val rows = o.choices.flatMap { c -> elements[c.id].orEmpty().filter { it.draws } }
                    if (rows.isEmpty() || rows.any { it.related == 0 }) continue          // a colour draws only on its pattern
                    val rel = rows.map { it.related }
                    val owners = rel.mapNotNull { optionOfChoice[it] }.toSet()
                    if (owners.size != 1 || rel.size != rel.count { optionOfChoice[it] != null }) continue
                    val m = options.firstOrNull { it.id == owners.first() } ?: continue
                    if (m.id != o.id && m.choices.all { elements[it.id].isNullOrEmpty() }) masters[o.id] = m.id
                }
            }
            return Catalog(races, entries, allByModel, defaults, optionOfChoice, choiceOrder, elements, relatedTo, masters, customs)
        }

        private fun swatch(v: Int): Int? = if (v == 0) null else v and 0xFFFFFF
    }
}
