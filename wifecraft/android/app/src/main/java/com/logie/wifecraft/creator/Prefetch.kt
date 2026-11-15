package com.logie.wifecraft.creator

import java.util.WeakHashMap

/**
 * Every file of the game the character creator can ever draw, so that the first launch fetches them all with the rest
 * (data/GameFiles' "looks"), not one look at a time (the user: "I want it to download all the options in import instead
 * of on demand"). Pure JVM.
 *
 * [plan] goes race by race (the Catalog's order), each sex: first the race's default look (what every one of its looks
 * shares: the model, its skin's base layers), then each shown option's every choice resolved on the default look
 * (LookResolver.resolve with that one pick), and with each choice that one of its rows hangs on (a colour on its pattern,
 * a hair colour on its style), so the rows that apply only together are resolved as the creator would show them. Files are
 * kept once, in the order first needed. Then a sweep of every ChrCustomizationElement row of the race's choices adds what
 * no single resolve shows (a row of a choice that another choice's row outranks while the defaults are on): [Plan.swept].
 *
 * The models' own needs (their first skin, textures and animation files) are in the model files, not the tables: once
 * a model is here, [Plan.order] puts what M2Files.deps reads from it right after it (the story's animations for the body
 * models, as engine/Dresser asks, none for the skinned extras).
 */
object Prefetch {
    /*
     * Measured on wago.tools' tables of 15 November 2026 (wago.tools sends no sizes ahead, so the first launch counts with
     * these until the files are here): 26 races, 52 models, 16,013 files in all: 65 models (991.5 MB, every one fetched),
     * the 853 files the models name (143.8 MB, every one fetched), and 15,095 textures and bone files (a random 1,500 of
     * them fetched: 318 KB on average, 4.80 GB ± 0.28 GB at 95%). About 5.9 GB in all.
     */
    /** Files in all, and models among them, before the plan is made. */
    const val FILES_ESTIMATE = 16_013
    const val MODELS_ESTIMATE = 65
    /** A model's average weight. */
    const val MODEL_BYTES = 15_250_000L
    /** Any other file's (the textures, bone files, skins and animations together). */
    const val AVERAGE_BYTES = 310_000L

    class Plan(
        /** Every file the tables name, race by race, the shared ones first. */
        val files: LinkedHashSet<Int>,
        /** The models drawn as a character's body (their deps take the story's animations). */
        val bodyModels: Set<Int>,
        /** The skinned models drawn with it (horns, blindfolds...). */
        val extraModels: Set<Int>,
        /** Looks resolved. */
        val looks: Int = 0,
        /** Race id -> how many files it was the first to need. */
        val byRace: Map<Int, Int> = emptyMap(),
        /** Files only the elements' sweep found. */
        val swept: Int = 0,
    ) {
        val models: Set<Int> get() = bodyModels + extraModels

        /**
         * The fetching order: [files], each model followed by its deps ([deps]: null while the model isn't here yet).
         * Each file once.
         */
        fun order(deps: (model: Int, body: Boolean) -> List<Int>?): LinkedHashSet<Int> {
            val out = LinkedHashSet<Int>()
            for (f in files) {
                out += f
                if (f in bodyModels || f in extraModels) deps(f, f in bodyModels)?.let { out += it }
            }
            return out
        }

        /** As text, for keeping beside the tables ([key]: what the tables were, so a newer set of tables makes a new plan). */
        fun write(key: String): String = buildString {
            append("wifecraft-prefetch 1 ").append(key).append('\n')
            append("body ").append(bodyModels.joinToString(",")).append('\n')
            append("extra ").append(extraModels.joinToString(",")).append('\n')
            append("files ").append(files.joinToString(",")).append('\n')
        }

        companion object {
            /** A plan [write] made for the same [key], else null. */
            fun read(text: String, key: String): Plan? {
                val lines = text.lines()
                if (lines.size < 4 || lines[0] != "wifecraft-prefetch 1 $key") return null
                fun ids(line: String, tag: String): List<Int>? {
                    if (!line.startsWith("$tag ")) return null
                    val s = line.substring(tag.length + 1)
                    return if (s.isEmpty()) emptyList() else s.split(',').map { it.toIntOrNull() ?: return null }
                }
                val body = ids(lines[1], "body") ?: return null
                val extra = ids(lines[2], "extra") ?: return null
                val files = ids(lines[3], "files") ?: return null
                return Plan(LinkedHashSet(files), body.toSet(), extra.toSet())
            }
        }
    }

    fun plan(c: Catalog, t: Tables): Plan {
        val files = LinkedHashSet<Int>()
        val body = LinkedHashSet<Int>()
        val extra = LinkedHashSet<Int>()
        val byRace = LinkedHashMap<Int, Int>()
        var looks = 0
        var swept = 0
        val ix = index(t)
        for (race in c.races) {
            val before = files.size
            for (sex in Sex.entries) {
                if (sex !in race.sexes) continue
                val entry = c.model(race.id, sex) ?: continue
                val seen = HashSet<Map<Int, Int>>()
                fun take(picks: Map<Int, Int>) {
                    if (!seen.add(picks)) return
                    val look = runCatching { LookResolver.resolve(c, t, Appearance(race.id, sex, picks)) }.getOrNull() ?: return
                    looks++
                    if (look.model != 0) body += look.model
                    look.extraModels.forEach { if (it.model != 0) extra += it.model }
                    // the model first, then what it wears
                    if (look.model != 0) files += look.model
                    files += look.needsFiles.filter { it > 0 }
                }
                take(emptyMap())
                for (o in entry.options) for (ch in o.choices) {
                    take(mapOf(o.id to ch.id))
                    for (e in c.elements[ch.id].orEmpty()) {
                        val other = e.related.takeIf { it != 0 }?.let { c.optionOf(it) } ?: continue
                        if (other != o.id) take(mapOf(o.id to ch.id, other to e.related))
                    }
                }
                // what no single look shows: every row of every choice of this model
                val baseModel = ix.modelOf(entry.chrModelId)
                val layout = ix.layoutOf[entry.chrModelId] ?: 0
                val targets = ix.targets[layout].orEmpty()
                val models = setOf(0, baseModel) + c.allOptions(entry.chrModelId).flatMap { o ->
                    o.choices.flatMap { ch -> c.elements[ch.id].orEmpty().mapNotNull { e -> e.condModel.takeIf { it != 0 }?.let(ix::condModel) } }
                }
                for (o in c.allOptions(entry.chrModelId)) for (ch in o.choices) for (e in c.elements[ch.id].orEmpty()) {
                    val add = ArrayList<Int>(2)
                    if (e.material != 0) ix.materials[e.material]?.let { (target, res) -> if (target in targets) add += ix.file(res) }
                    if (e.skinned != 0) ix.skinned[e.skinned]?.let { if (it != 0) { add += it; extra += it } }
                    if (e.boneSet != 0) ix.boneSets[e.boneSet]?.let { (m, bone) -> if (bone != 0 && m in models) add += bone }
                    if (e.condModel != 0) ix.condModel(e.condModel).let { if (it != 0) { add += it; body += it } }
                    for (f in add) if (f > 0 && files.add(f)) swept++
                }
            }
            byRace[race.id] = files.size - before
        }
        return Plan(files, body, extra, looks, byRace, swept)
    }

    // --- the tables the sweep reads, indexed once each ------------------------------------------------------------

    private val indexes = WeakHashMap<Tables, Index>()

    private fun index(t: Tables): Index = synchronized(indexes) { indexes.getOrPut(t) { Index(t) } }

    private class Index(t: Tables) {
        private val chrModels = t.rows("ChrModel").associateBy { it.int("ID") }
        val layoutOf: Map<Int, Int> = chrModels.mapValues { it.value.int("CharComponentTextureLayoutID") }
        private val displays by lazy { t.rows("CreatureDisplayInfo").associate { it.int("ID") to it.int("ModelID") } }
        private val modelData by lazy { t.rows("CreatureModelData").associate { it.int("ID") to it.int("FileDataID") } }
        /** ChrCustomizationMaterial ID -> (texture target, MaterialResourcesID). */
        val materials: Map<Int, Pair<Int, Int>> = t.rows("ChrCustomizationMaterial").associate {
            it.int("ID") to (it.int("ChrModelTextureTargetID") to it.int("MaterialResourcesID"))
        }
        /** Layout -> the texture targets its layers take. */
        val targets: Map<Int, Set<Int>> = t.rows("ChrModelTextureLayer").groupBy { it.int("CharComponentTextureLayoutsID") }
            .mapValues { (_, rows) -> rows.flatMapTo(HashSet()) { listOf(it.int("ChrModelTextureTargetID_0"), it.int("ChrModelTextureTargetID_1")) } - 0 }
        val skinned: Map<Int, Int> = t.rows("ChrCustomizationSkinnedModel").associate { it.int("ID") to it.int("CollectionsFileDataID") }
        /** ChrCustomizationBoneSet ID -> (its model, 0 any; its bone file). */
        val boneSets: Map<Int, Pair<Int, Int>> = t.rows("ChrCustomizationBoneSet").associate {
            it.int("ID") to (it.int("ModelFileDataID") to it.int("BoneFileDataID"))
        }
        private val condModels: Map<Int, Int> = t.rows("ChrCustomizationCondModel").associate { it.int("ID") to it.int("CreatureModelDataID") }
        /** MaterialResourcesID -> its file, as LookResolver picks it: the lowest UsageType's, then the lowest id. */
        private val textureFiles by lazy {
            val out = HashMap<Int, Long>()
            for (r in t.rows("TextureFileData")) {
                val key = (r.int("UsageType").toLong() shl 32) or (r.int("FileDataID").toLong() and 0xFFFFFFFFL)
                val res = r.int("MaterialResourcesID")
                val have = out[res]
                if (have == null || key < have) out[res] = key
            }
            out.mapValues { (it.value and 0xFFFFFFFFL).toInt() }
        }

        fun modelOf(chrModel: Int): Int = chrModels[chrModel]?.let { displays[it.int("DisplayID")] }?.let { modelData[it] } ?: 0
        fun condModel(id: Int): Int = condModels[id]?.let { modelData[it] } ?: 0
        fun file(res: Int): Int = textureFiles[res] ?: 0
    }
}
