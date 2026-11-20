package com.logie.wifecraft.creator

import java.util.WeakHashMap

/**
 * A texture composed into one of the character's textures ([textureType]: ChrModelTextureLayer.TextureType, 1 the
 * body's skin, 6 the hair...): its file, the sections it covers ([region]: ChrModelTextureLayer.TextureSectionTypeBitMask,
 * -1 the whole texture), how it blends ([blend]: ChrModelTextureLayer.BlendMode), its place among the layers ([layer]:
 * ChrModelTextureLayer.Layer, lowest first) and its tints (0xRRGGBB, none in the game's tables today). [rect]: a box of
 * the material of its own (u0, v0, u1, v1 as fractions, top row first) in place of [region]'s; the undead skins' (UndeadSkins).
 */
data class TextureLayer(val fileId: Int, val region: Int, val blend: Int, val layer: Int, val tint1: Int?, val tint2: Int?, val textureType: Int = 1,
                        val rect: List<Float>? = null)

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
 * Equipment groups start at their bare variants. Transmog then applies the selected display IDs and clothing layers.
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
        val display = ix.display(chrModel.int("DisplayID"))
        var model = display?.let { ix.modelData(it.int("ModelID"))?.int("FileDataID") } ?: 0
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
        // a choice of Wifecraft's own (CustomChoices) draws as its base, tinted: the base counts as on for the rows hanging on it
        val chosen = c.withBases(current.values.toSet())
        val tintOfBase = current.values.mapNotNull { id -> c.customs[id]?.let { it.base to (it.tint1 to it.tint2) } }.toMap()

        val groups = LinkedHashMap<Int, Int>()
        val materials = LinkedHashMap<Int, Int>()            // texture target -> MaterialResourcesID
        val owners = HashMap<Int, String>()                  // texture target -> the name of the option that set it
        val tints = HashMap<Int, Pair<Int, Int>>()           // texture target -> a custom choice's tints (light, dark)
        val skinned = LinkedHashMap<Int, ExtraModel>()
        val bones = LinkedHashSet<Int>()
        for (o in options) {
            val choiceId = current.getValue(o.id)
            val choice = o.choices.first { it.id == choiceId }
            val what = "${o.name.ifEmpty { "Option ${o.id}" }}: ${choice.name.ifEmpty { "choice ${choice.id}" }}"
            val myGroups = HashMap<Int, Pair<Int, Int>>()      // group -> (rank, geoset id)
            val myMaterials = HashMap<Int, Pair<Int, Int>>()   // target -> (rank, material resources)
            val myTints = HashMap<Int, Pair<Int, Int>?>()
            val ownTint = c.tintOf(choiceId)
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
                                if ((myMaterials[target]?.first ?: 0) < rank) {
                                    myMaterials[target] = rank to m.int("MaterialResourcesID")
                                    myTints[target] = ownTint ?: tintOfBase[e.related]
                                }
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
                    val cm = ix.condModels[e.condModel]?.let { ix.modelData(it.int("CreatureModelDataID")) }?.int("FileDataID") ?: 0
                    if (cm == 0) unsupported += "model ${e.condModel} isn't in ChrCustomizationCondModel/CreatureModelData"
                    else { model = cm; drew = true }
                }
                if (e.other != null) unsupported += "${e.other} isn't drawn by Wifecraft's renderer"
            }
            for ((type, v) in myGroups) groups[type] = v.second
            for ((target, v) in myMaterials) {
                materials[target] = v.second; owners[target] = o.name
                val tint = myTints[target]
                if (tint != null) tints[target] = tint else tints.remove(target)
            }
            if (o.id in shown && !drew) {
                val hangs = c.relatedTo[choiceId].orEmpty().any { it in chosen }
                val none = c.isNone(choiceId) || !c.relevant(o.id, chosen) || !c.applies(o.id, chosen) || c.master(o.id)?.let { m -> current[m]?.let { c.isNone(it) } } == true
                if (!hangs && (!none || unsupported.isNotEmpty())) {
                    diagnostics += "$what draws nothing" + if (unsupported.isEmpty()) " with the other choices on" else " (${unsupported.joinToString()})"
                }
            }
        }
        // a skinned model's choice sets its geoset group for the whole character before the bare-equipment defaults do
        // (the Earthen's hand crystals pick 2300, none: the default 2301 drew the collection's glow sheets around them)
        for (e in skinned.values) if (e.geoset >= 100) groups.putIfAbsent(e.geoset / 100, e.geoset % 100)
        for (g in EQUIPMENT_GROUPS) groups.putIfAbsent(g, 1)

        // Wrath++'s Undead switch, from the user's undead skins: the body and the face of the skin on, laid over the race's own
        // skin colour (UndeadSkins); the Face option's textures go (an undead skin has its own face), and the skin colour's
        // others (the underwear) unless the package keeps the race's own ("race")
        val undead = if (a.undead) overrides.undeadSkins(a.raceId, a.sex).let { it.getOrNull(a.undeadSkin.coerceIn(0, maxOf(0, it.size - 1))) } else null
        if (a.undead && undead == null)
            diagnostics += "Undead: " + if (overrides.hasUndead()) "the undead skins have none for this race" else UndeadSkins.NOT_IMPORTED
        val keepUnderwear = undead == null || overrides.undeadUnderwear(a.raceId, a.sex) == "race"

        val layers = ArrayList<TextureLayer>()
        for ((target, res) in materials) {
            val owner = owners[target]
            if (undead != null && owner == CreatorModel.FACE) continue
            val file = overrides.texture(ix.file(res), female)
            for (l in ix.layersOf(layout)) {
                if (l.int("ChrModelTextureTargetID_0") != target && l.int("ChrModelTextureTargetID_1") != target) continue
                val base = l.int("TextureType") == 1 && l.int("TextureSectionTypeBitMask") == -1
                if (undead != null && owner == CreatorModel.SKIN_COLOR && !base && !keepUnderwear) continue
                val tint = tints[target]
                layers += TextureLayer(file, l.int("TextureSectionTypeBitMask"), l.int("BlendMode"), l.int("Layer"), tint?.first, tint?.second, l.int("TextureType"))
            }
        }
        if (undead != null) {
            // on the skin's lowest layer, after the race's own colour: everything the race lays over its skin stays over them
            val at = layers.filter { it.textureType == 1 }.minOfOrNull { it.layer } ?: 0
            layers += TextureLayer(undead.skin, -1, 1, at, null, null, 1, UndeadSkins.BODY)
            val head = UndeadSkins.head(a.raceId, a.sex)
            if (head != null) layers += TextureLayer(undead.face, -1, 1, at, null, null, 1, head.rect)
            else diagnostics += "Undead: no head layout for this race's face"
        }

        // Wrath++'s body options, from the user's package
        val b = a.body
        if (BodyOptions.eligible(a.raceId, a.sex) && (b.shape != 0 || b.style != 0)) {
            if (overrides.hasBody()) {
                // the race's own model stays: a shape is a patch the renderer lays on it (creator/BodyShapes, Native.body)
                if (b.shape != 0 && overrides.bodyModel(a.raceId, b.shape) == null)
                    diagnostics += "Shape ${BodyOptions.SHAPES.getOrElse(b.shape) { b.shape.toString() }}: the body options have no model for this race"
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
        val look = ResolvedLook(model, scale, groups.toSortedMap(), textures.toSortedMap(), layers, extra, needs, bones.toList(), diagnostics)
        return CreatureLooks.transmog(t, a, look)
    }

    // --- the tables, indexed once each ----------------------------------------------------------------------------

    private val indexes = WeakHashMap<Tables, Index>()

    private fun index(t: Tables): Index = synchronized(indexes) { indexes.getOrPut(t) { Index(t) } }

    private class Index(private val t: Tables) {
        val chrModels = t.rows("ChrModel").associateBy { it.int("ID") }
        // the big tables by key (Tables.rowsBy), never held whole
        fun display(id: Int) = t.rowBy("CreatureDisplayInfo", "ID", id)
        fun modelData(id: Int) = t.rowBy("CreatureModelData", "ID", id)
        val geosets = t.rows("ChrCustomizationGeoset").associateBy { it.int("ID") }
        val materials = t.rows("ChrCustomizationMaterial").associateBy { it.int("ID") }
        val skinned = t.rows("ChrCustomizationSkinnedModel").associateBy { it.int("ID") }
        val boneSets = t.rows("ChrCustomizationBoneSet").associateBy { it.int("ID") }
        val condModels = t.rows("ChrCustomizationCondModel").associateBy { it.int("ID") }
        private val layers = t.rows("ChrModelTextureLayer").groupBy { it.int("CharComponentTextureLayoutsID") }
        /** MaterialResourcesID -> its file: the lowest UsageType's (0 the colour), then the lowest id (tools/looks.py). */
        private fun files(res: Int): Int {
            var have: Pair<Int, Int>? = null
            for (r in t.rowsBy("TextureFileData", "MaterialResourcesID", res)) {
                val key = r.int("UsageType") to r.int("FileDataID")
                val h = have
                if (h == null || key.first < h.first || (key.first == h.first && key.second < h.second)) have = key
            }
            return have?.second ?: 0
        }

        fun layersOf(layout: Int): List<Map<String, String>> = layers[layout].orEmpty()
        fun file(materialResources: Int): Int = files(materialResources)
    }
}
