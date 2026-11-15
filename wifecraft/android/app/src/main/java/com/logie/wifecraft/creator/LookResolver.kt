package com.logie.wifecraft.creator

import java.util.WeakHashMap

/**
 * A texture composed into one of the character's textures ([textureType]: ChrModelTextureLayer.TextureType, 1 the
 * body's skin, 6 the hair...): its file, the sections it covers ([region]: ChrModelTextureLayer.TextureSectionTypeBitMask,
 * -1 the whole texture), how it blends ([blend]: ChrModelTextureLayer.BlendMode), its place among the layers ([layer]:
 * ChrModelTextureLayer.Layer, lowest first) and its tints (0xRRGGBB, none in the game's tables today).
 */
data class TextureLayer(val fileId: Int, val region: Int, val blend: Int, val layer: Int, val tint1: Int?, val tint2: Int?, val textureType: Int = 1)

/**
 * A model drawn with the character. [attachment] -1: skinned to the character's own skeleton (a
 * ChrCustomizationSkinnedModel: horns, blindfolds), showing its geoset [geoset] (group × 100 + id); [texture] 0: it takes
 * the character's textures.
 */
data class ExtraModel(val attachment: Int, val model: Int, val texture: Int, val geoset: Int = 0)

/**
 * What the renderer draws for an [Appearance]: the model (M2 file id), its scale, geoset group to the geoset shown,
 * texture type to its base file, every layer of every composed texture, extra models, the face's bone files
 * ([boneFiles]: ChrCustomizationBoneSet, the face shapes) and every file the look needs. [diagnostics]: each choice on
 * that draws nothing here, and why (empty when the whole look is drawn).
 */
data class ResolvedLook(
    val model: Int,
    val scale: Float, val geosetGroups: Map<Int, Int>, val textures: Map<Int, Int>,
    val layers: List<TextureLayer>, val extraModels: List<ExtraModel>, val needsFiles: Set<Int>,
    val boneFiles: List<Int> = emptyList(),
    val diagnostics: List<String> = emptyList(),
)

/**
 * An appearance made what the renderer draws, from the game's tables on the phone (tools/looks.py's work for one
 * character, for any choices): ChrModel -> CreatureDisplayInfo -> CreatureModelData for the model; each choice's
 * ChrCustomizationElement rows that apply (those hanging on another choice only while that one is picked) for geosets
 * (ChrCustomizationGeoset), textures (ChrCustomizationMaterial onto the model's ChrModelTextureLayer rows, the file by
 * TextureFileData), skinned models, face bones and another model (ChrCustomizationCondModel: Upright). A row hanging on a
 * picked choice wins over a row of the same choice that hangs on none.
 *
 * The starting outfit ([Appearance.gear]) isn't drawn yet: either way the equipment's geoset groups (gloves 4, boots 5,
 * sleeves 8, shirt 10, robe 13, tabard 22, wristbands 23, chest 28) show their bare variant (1) unless a choice sets them.
 */
object LookResolver {
    private val EQUIPMENT_GROUPS = intArrayOf(4, 5, 8, 10, 13, 22, 23, 28)
    const val BODY_LAYER = 100

    fun resolve(c: Catalog, t: Tables, a: Appearance, overrides: Overrides = Overrides.None): ResolvedLook {
        val entry = c.model(a.raceId, a.sex) ?: throw IllegalArgumentException("No model for race ${a.raceId} ${a.sex}")
        val ix = index(t)
        val female = a.sex == Sex.FEMALE
        val diagnostics = ArrayList<String>()
        val chrModel = ix.chrModels[entry.chrModelId] ?: throw IllegalArgumentException("ChrModel ${entry.chrModelId} isn't in the tables")
        val display = ix.displays[chrModel.int("DisplayID")]
        var model = display?.let { ix.modelData[it.int("ModelID")]?.int("FileDataID") } ?: 0
        if (model == 0) diagnostics += "No model file for ChrModel ${entry.chrModelId} (CreatureDisplayInfo ${chrModel.int("DisplayID")})"
        val scale = display?.float("CreatureModelScale", 1f)?.takeIf { it > 0f } ?: 1f
        val layout = chrModel.int("CharComponentTextureLayoutID")

        val options = c.allOptions(entry.chrModelId)
        val shown = entry.options.mapTo(HashSet()) { it.id }
        val current = LinkedHashMap<Int, Int>()
        for (o in options) {
            val pick = a.picks[o.id]?.takeIf { p -> o.choices.any { it.id == p } }
            current[o.id] = pick ?: c.defaultChoice(o.id) ?: o.choices.first().id
        }
        val chosen = current.values.toSet()

        val groups = LinkedHashMap<Int, Int>()
        val materials = LinkedHashMap<Int, Int>()            // texture target -> MaterialResourcesID
        val skinned = LinkedHashMap<Int, ExtraModel>()
        val bones = LinkedHashSet<Int>()
        for (o in options) {
            val choiceId = current.getValue(o.id)
            val choice = o.choices.first { it.id == choiceId }
            val what = "${o.name.ifEmpty { "Option ${o.id}" }}: ${choice.name.ifEmpty { "choice ${choice.id}" }}"
            val myGroups = HashMap<Int, Pair<Int, Int>>()      // group -> (rank, geoset id)
            val myMaterials = HashMap<Int, Pair<Int, Int>>()   // target -> (rank, material resources)
            var drew = false
            val unsupported = LinkedHashSet<String>()
            for (e in c.elements[choiceId].orEmpty()) {
                if (e.related != 0 && e.related !in chosen) continue
                val rank = if (e.related != 0) 2 else 1
                if (e.geoset != 0) {
                    val g = ix.geosets[e.geoset]
                    if (g == null) unsupported += "geoset ${e.geoset} isn't in ChrCustomizationGeoset"
                    else {
                        val type = g.int("GeosetType")
                        if ((myGroups[type]?.first ?: 0) < rank) myGroups[type] = rank to g.int("GeosetID")
                        drew = true
                    }
                }
                if (e.material != 0) {
                    val m = ix.materials[e.material]
                    if (m == null) unsupported += "material ${e.material} isn't in ChrCustomizationMaterial"
                    else {
                        val target = m.int("ChrModelTextureTargetID")
                        if (ix.layersOf(layout).any { it.int("ChrModelTextureTargetID_0") == target || it.int("ChrModelTextureTargetID_1") == target }) {
                            if (ix.file(m.int("MaterialResourcesID")) == 0) unsupported += "material ${e.material}'s texture isn't in TextureFileData"
                            else {
                                if ((myMaterials[target]?.first ?: 0) < rank) myMaterials[target] = rank to m.int("MaterialResourcesID")
                                drew = true
                            }
                        }
                    }
                }
                if (e.skinned != 0) {
                    val s = ix.skinned[e.skinned]
                    if (s == null || s.int("CollectionsFileDataID") == 0) unsupported += "skinned model ${e.skinned} isn't in ChrCustomizationSkinnedModel"
                    else {
                        val geoset = s.int("GeosetType") * 100 + s.int("GeosetID")
                        skinned[e.skinned] = ExtraModel(-1, s.int("CollectionsFileDataID"), 0, geoset)
                        drew = true
                    }
                }
                if (e.boneSet != 0) {
                    val b = ix.boneSets[e.boneSet]
                    if (b != null && (b.int("ModelFileDataID") == 0 || b.int("ModelFileDataID") == model) && b.int("BoneFileDataID") != 0) {
                        bones += b.int("BoneFileDataID"); drew = true
                    }
                }
                if (e.condModel != 0) {
                    val cm = ix.condModels[e.condModel]?.let { ix.modelData[it.int("CreatureModelDataID")] }?.int("FileDataID") ?: 0
                    if (cm == 0) unsupported += "model ${e.condModel} isn't in ChrCustomizationCondModel/CreatureModelData"
                    else { model = cm; drew = true }
                }
                if (e.other != null) unsupported += "${e.other} isn't drawn by Wifecraft's renderer"
            }
            for ((type, v) in myGroups) groups[type] = v.second
            for ((target, v) in myMaterials) materials[target] = v.second
            if (o.id in shown && !drew) {
                val hangs = c.relatedTo[choiceId].orEmpty().any { it in chosen }
                val none = c.isNone(choiceId) || !c.relevant(o.id, chosen) || !c.applies(o.id, chosen) || c.master(o.id)?.let { m -> current[m]?.let { c.isNone(it) } } == true
                if (!hangs && (!none || unsupported.isNotEmpty())) {
                    diagnostics += "$what draws nothing" + if (unsupported.isEmpty()) " with the other choices on" else " (${unsupported.joinToString()})"
                }
            }
        }
        for (g in EQUIPMENT_GROUPS) groups.putIfAbsent(g, 1)

        val layers = ArrayList<TextureLayer>()
        for ((target, res) in materials) {
            val file = overrides.texture(ix.file(res), female)
            for (l in ix.layersOf(layout)) {
                if (l.int("ChrModelTextureTargetID_0") != target && l.int("ChrModelTextureTargetID_1") != target) continue
                layers += TextureLayer(file, l.int("TextureSectionTypeBitMask"), l.int("BlendMode"), l.int("Layer"), null, null, l.int("TextureType"))
            }
        }

        // Wrath++'s body options, from the user's package
        val b = a.body
        if (BodyOptions.eligible(a.raceId, a.sex) && (b.shape != 0 || b.style != 0)) {
            if (overrides.hasBody()) {
                if (b.shape != 0) {
                    val m = overrides.bodyModel(a.raceId, b.shape)
                    if (m != null) model = m else diagnostics += "Shape ${BodyOptions.SHAPES.getOrElse(b.shape) { b.shape.toString() }}: the body options have no model for this race"
                }
                if (b.style != 0 && b.density != 0) {
                    val f = overrides.bodyLayer(a.raceId, b.style, b.density)
                    if (f != null) layers += TextureLayer(overrides.texture(f, female), -1, 1, BODY_LAYER, null, null, 1)
                    else diagnostics += "Style ${b.style}: the body options have no design for this race"
                }
            } else diagnostics += "Shape, Style, Density: ${BodyOptions.NOT_IMPORTED}"
        }

        layers.sortWith(compareBy({ it.textureType }, { it.layer }))
        val textures = LinkedHashMap<Int, Int>()
        for (l in layers) textures.putIfAbsent(l.textureType, l.fileId)
        val extra = skinned.values.toList()
        val needs = LinkedHashSet<Int>()
        if (model != 0) needs += model
        layers.forEach { needs += it.fileId }
        extra.forEach { needs += it.model; if (it.texture != 0) needs += it.texture }
        needs += bones
        return ResolvedLook(model, scale, groups.toSortedMap(), textures.toSortedMap(), layers, extra, needs, bones.toList(), diagnostics)
    }

    // --- the tables, indexed once each ----------------------------------------------------------------------------

    private val indexes = WeakHashMap<Tables, Index>()

    private fun index(t: Tables): Index = synchronized(indexes) { indexes.getOrPut(t) { Index(t) } }

    private class Index(t: Tables) {
        val chrModels = t.rows("ChrModel").associateBy { it.int("ID") }
        val displays by lazy { t.rows("CreatureDisplayInfo").associateBy { it.int("ID") } }
        val modelData by lazy { t.rows("CreatureModelData").associateBy { it.int("ID") } }
        val geosets = t.rows("ChrCustomizationGeoset").associateBy { it.int("ID") }
        val materials = t.rows("ChrCustomizationMaterial").associateBy { it.int("ID") }
        val skinned = t.rows("ChrCustomizationSkinnedModel").associateBy { it.int("ID") }
        val boneSets = t.rows("ChrCustomizationBoneSet").associateBy { it.int("ID") }
        val condModels = t.rows("ChrCustomizationCondModel").associateBy { it.int("ID") }
        private val layers = t.rows("ChrModelTextureLayer").groupBy { it.int("CharComponentTextureLayoutsID") }
        /** MaterialResourcesID -> its file: the lowest UsageType's (0 the colour), then the lowest id (tools/looks.py). */
        private val files by lazy {
            val out = HashMap<Int, Pair<Int, Int>>()
            for (r in t.rows("TextureFileData")) {
                val res = r.int("MaterialResourcesID")
                val key = r.int("UsageType") to r.int("FileDataID")
                val have = out[res]
                if (have == null || key.first < have.first || (key.first == have.first && key.second < have.second)) out[res] = key
            }
            out.mapValues { it.value.second }
        }

        fun layersOf(layout: Int): List<Map<String, String>> = layers[layout].orEmpty()
        fun file(materialResources: Int): Int = files[materialResources] ?: 0
    }
}
