package com.logie.wifecraft.creator

import java.util.WeakHashMap

/**
 * A creature's or an NPC's look from its CreatureDisplayInfo id, made the same [ResolvedLook] the creator makes (and drawn
 * the same way: [call] for the renderer), on the phone from the game's tables (tools/looks.py of the old demo did this
 * offline for its Sentinels). Two kinds of display:
 *
 * - **A creature** (no ExtendedDisplayInfoID: a boar, a wolf, a murloc): CreatureModelData's model (its FileDataID), its
 *   scale (the display's CreatureModelScale times the model data's ModelScale), the display's texture variations on the
 *   model's replaceable textures (M2 texture types 11, 12 and 13, "creature skin 1 to 3", take TextureVariationFileDataID
 *   0 to 2; type 5 takes the fourth, as the models that have four use it) and its geosets (CreatureDisplayInfoGeosetData:
 *   geoset group GeosetIndex + 1 shows GeosetValue; the others their defaults).
 * - **A humanoid NPC** (CreatureDisplayInfoExtra: race, sex, a baked skin): the race's model as the display names it, the
 *   customization choices the display keeps (CreatureDisplayInfoOption) resolved by [LookResolver] (geosets, hair and the
 *   other textures, skinned models) for the race the catalogue has with that ChrModel, then the baked skin
 *   (HDBakeMaterialResourcesID, else BakeMaterialResourcesID: skin, face, underwear and the worn clothes in one texture) in
 *   place of the composed skin (texture types 1 and 8), and the worn gear (NPCModelItemSlotDisplayInfo -> ItemDisplayInfo):
 *   each slot's geoset groups, the helm and shoulders hung on their attachment points (the model for the race and sex by
 *   ComponentModelFileData; a pair of shoulders is one resource, its left and right by PositionIndex 0 and 1), armour
 *   collection pieces skinned to the body (ModelType 1), the cape's texture (type 2), and what a helm hides
 *   (HelmetGeosetData). Weapons aren't the display's (the server equips them): none are drawn.
 *
 * A look's scale is the game's own size for the display, on the model's units (yards), and the engine adds nothing to
 * it: measured beside a night elf woman in engine-test, a boar (display 503, boar2 at 0.6) stands 1.2 yards, a guard 2.1,
 * a raptor hatchling (its model data's 0.15) 0.7. What the tables can't say is a server's own scale for a creature: a
 * display can be huge as it is (52696, Draenor's giant war boar, 8 yards tall at 1), and the editor's Size sets it.
 *
 * Pure JVM; the small tables are indexed once per [Tables], the big ones looked up by key ([Tables.rowsBy]).
 */
object CreatureLooks {
    /** A display's look, or why it has none (null when it has one). */
    data class Result(val look: ResolvedLook?, val why: String?)

    /** NPC item slots (NPCModelItemSlotDisplayInfo.ItemSlot) to equipment slots (tools/looks.py's NPC_SLOT). */
    internal val NPC_SLOT = mapOf(0 to 1, 1 to 3, 2 to 4, 3 to 5, 4 to 6, 5 to 7, 6 to 8, 7 to 9, 8 to 10, 9 to 19, 10 to 15)

    /**
     * Each equipment slot's geoset groups: (index into ItemDisplayInfo.GeosetGroup, the character's geoset group). The
     * tabard (19, group 12) is left out, as tools/looks.py did: the baked skins paint no tabard, so its mesh would show
     * scraps of the rest of the texture.
     */
    internal val SLOT_GEOSETS = mapOf(
        1 to listOf(0 to 27, 1 to 21),
        3 to listOf(0 to 26),
        4 to listOf(0 to 8, 1 to 10),
        5 to listOf(0 to 8, 1 to 10, 2 to 13, 3 to 22, 4 to 28),
        6 to listOf(0 to 18),
        7 to listOf(0 to 11, 1 to 9, 2 to 13),
        8 to listOf(0 to 5, 1 to 20),
        10 to listOf(0 to 4, 1 to 23),
        15 to listOf(0 to 15),
        19 to listOf(0 to 12),
    )

    /** Where several slots set one group, the first listed here that does wins (else the lowest slot). */
    internal val PRIORITY = mapOf(8 to listOf(10, 5, 4), 10 to listOf(5, 4), 13 to listOf(5, 7))

    /**
     * Attachment points of the slots whose items hang models of their own: the helm's, the left (6) and right (5) shoulders',
     * a belt's buckle (53, BeltBuckle: ModelResourcesID_0 of ModelType 0, most of the game's belts).
     */
    internal val ATTACH = mapOf(1 to listOf(11), 3 to listOf(6, 5), 6 to listOf(53))

    /** The creature skins' M2 texture types, in TextureVariationFileDataID's order. */
    internal val VARIATION_TYPES = intArrayOf(11, 12, 13, 5)

    /** ItemDisplayInfo.ModelType of an armour collection piece (skinned to the body, not hung). */
    private const val COLLECTION = 1

    fun resolve(tables: Tables, displayId: Int): ResolvedLook? = check(tables, displayId).look

    /**
     * Whether [look] is a character's: its model is one of the creator's playable races' (an NPC of those races, a Stormwind
     * guard, a Sentinel), not a creature's (a boar, a wolf, a murloc, and the races the creator doesn't offer). Only a
     * character has a soft body (Native.character).
     */
    fun isCharacter(tables: Tables, look: ResolvedLook): Boolean {
        if (look.model == 0) return false
        val chr = index(tables).chrModelOfFile[look.model] ?: return false
        val catalog = catalog(tables)
        return catalog.races.any { r -> Sex.values().any { s -> catalog.model(r.id, s)?.chrModelId == chr } }
    }

    fun check(tables: Tables, displayId: Int): Result {
        val ix = index(tables)
        val disp = ix.display(displayId) ?: return Result(null, "display not in CreatureDisplayInfo")
        val data = ix.modelData(disp.int("ModelID")) ?: return Result(null, "model ${disp.int("ModelID")} not in CreatureModelData")
        val model = data.int("FileDataID")
        if (model == 0) return Result(null, "model data ${disp.int("ModelID")} has no model file")
        val scale = disp.float("CreatureModelScale", 1f).takeIf { it > 0f } ?: 1f
        val modelScale = data.float("ModelScale", 1f).takeIf { it > 0f } ?: 1f
        val extended = disp.int("ExtendedDisplayInfoID")
        return if (extended == 0) Result(creature(ix, displayId, disp, model, scale * modelScale), null)
        else humanoid(tables, ix, disp, extended, model, scale * modelScale)
    }

    private fun creature(ix: Index, displayId: Int, disp: Map<String, String>, model: Int, scale: Float): ResolvedLook {
        val textures = sortedMapOf<Int, Int>()
        for ((i, type) in VARIATION_TYPES.withIndex()) {
            val f = disp.int("TextureVariationFileDataID_$i")
            if (f != 0) textures[type] = f
        }
        val groups = sortedMapOf<Int, Int>()
        for ((index, value) in ix.geosetData(displayId)) groups[index + 1] = value
        val needs = LinkedHashSet<Int>().apply { add(model); addAll(textures.values) }
        return ResolvedLook(model, scale, groups, textures, emptyList(), emptyList(), needs)
    }

    private fun humanoid(t: Tables, ix: Index, disp: Map<String, String>, extendedId: Int, model: Int, scale: Float): Result {
        val ex = ix.extra(extendedId) ?: return Result(null, "extended display $extendedId not in CreatureDisplayInfoExtra")
        val race = ex.int("DisplayRaceID")
        val sex = if (ex.int("DisplaySexID") == 1) Sex.FEMALE else Sex.MALE
        val diagnostics = ArrayList<String>()
        val chrModel = ix.chrModelOfModel[disp.int("ModelID")] ?: ix.chrModelOfRace[race to sex.ordinal]

        // the choices the display keeps, through the creator's own resolver, for the catalogue's race with this model
        val catalog = catalog(t)
        val entry = chrModel?.let { m -> catalog.races.firstNotNullOfOrNull { r -> catalog.model(r.id, sex)?.takeIf { it.chrModelId == m } } }
        val base: ResolvedLook? = if (entry != null) {
            val picks = ix.options(extendedId).toMap()
            runCatching { LookResolver.resolve(catalog, t, Appearance(entry.race.id, sex, picks, gear = false)) }
                .onFailure { diagnostics += "choices: ${it.message}" }.getOrNull()
        } else if (chrModel != null) {
            // a race or form the creator doesn't offer (the Vrykul, the Naga, the Dracthyr's dragon form): its choices'
            // rows read directly, as tools/looks.py did
            raw(ix, chrModel, model, scale, ix.options(extendedId).map { it.second })
        } else {
            diagnostics += "race $race ${sex.name.lowercase()}: no ChrModel, its choices aren't drawn"
            null
        }
        val groups = java.util.TreeMap(base?.geosetGroups.orEmpty())
        var layers = base?.layers.orEmpty()
        val textures = java.util.TreeMap<Int, Int>()
        base?.diagnostics?.let { diagnostics += it }

        // the baked skin: the body's texture whole, in place of the composed one
        val bake = ex.int("HDBakeMaterialResourcesID").takeIf { it != 0 } ?: ex.int("BakeMaterialResourcesID")
        val bakeFile = if (bake != 0) ix.texture(bake, race, sex) else 0
        if (bakeFile != 0) {
            layers = layers.filter { it.textureType != 1 && it.textureType != 8 }
            textures[1] = bakeFile
        } else diagnostics += if (bake == 0) "no baked skin: the worn clothes' textures aren't drawn" else "baked skin $bake not in TextureFileData"
        for (l in layers) textures.putIfAbsent(l.textureType, l.fileId)

        val extra = ArrayList(base?.extraModels.orEmpty())
        applyEquipment(ix, ix.slots(extendedId).mapNotNull { (npcSlot, item) -> NPC_SLOT[npcSlot]?.let { it to item } }.toMap(),
            race, sex, groups, textures, extra, mutableListOf(), diagnostics, paintMaterials = false)

        val needs = LinkedHashSet<Int>()
        needs += model
        needs += textures.values
        layers.forEach { needs += it.fileId }
        extra.forEach { needs += it.model; if (it.texture != 0) needs += it.texture }
        needs.remove(0)
        return Result(ResolvedLook(model, scale, groups, textures, layers, extra.distinct(), needs, emptyList(), diagnostics), null)
    }

    private fun clothingLayer(slot: Int): Int = 120 + when (slot) {
        4 -> 0
        7 -> 1
        5 -> 2
        19 -> 3
        9 -> 4
        8 -> 5
        10 -> 6
        6 -> 7
        else -> 8
    }

    fun transmog(tables: Tables, appearance: Appearance, base: ResolvedLook): ResolvedLook {
        if (!appearance.gear || appearance.clothing.isEmpty()) return base
        val groups = base.geosetGroups.toMutableMap()
        val textures = base.textures.toMutableMap()
        val extra = base.extraModels.toMutableList()
        val layers = base.layers.toMutableList()
        val diagnostics = base.diagnostics.toMutableList()
        val ix = index(tables)
        applyEquipment(ix, appearance.clothing, appearance.raceId, appearance.sex, groups, textures, extra, layers, diagnostics, paintMaterials = true)
        val needs = base.needsFiles + textures.values + layers.map { it.fileId } +
            extra.flatMap { listOf(it.model, it.texture).filter { file -> file != 0 } }
        return base.copy(geosetGroups = groups, textures = textures, layers = layers.sortedBy { it.layer },
            extraModels = extra.distinct(), needsFiles = needs, diagnostics = diagnostics)
    }

    fun clothingFiles(tables: Tables, displays: Set<Int>): Pair<Set<Int>, Set<Int>> {
        val ix = index(tables)
        val models = LinkedHashSet<Int>()
        val textures = LinkedHashSet<Int>()
        for (id in displays) {
            val row = ix.item(id) ?: continue
            for (k in 0..1) {
                val model = row.int("ModelResourcesID_$k")
                if (model > 0) models += ix.modelFiles(model)
                val texture = row.int("ModelMaterialResourcesID_$k")
                if (texture > 0) textures += ix.textureFiles(texture).map { it.second }
            }
            for (material in ix.materials(id)) {
                val resource = material.int("MaterialResourcesID")
                if (resource > 0) textures += ix.textureFiles(resource).map { it.second }
            }
        }
        return models to textures
    }

    private fun applyEquipment(
        ix: Index, items: Map<Int, Int>, race: Int, sex: Sex,
        groups: MutableMap<Int, Int>, textures: MutableMap<Int, Int>, extra: MutableList<ExtraModel>,
        clothingLayers: MutableList<TextureLayer>, diagnostics: MutableList<String>, paintMaterials: Boolean,
    ) {
        val perGroup = LinkedHashMap<Int, MutableList<Pair<Int, Int>>>()   // geoset group -> (slot, value)
        val hidden = LinkedHashSet<Int>()
        for ((slot, item) in items.toSortedMap()) {
            val row = ix.item(item) ?: continue
            for ((gi, group) in SLOT_GEOSETS[slot].orEmpty()) {
                if (slot == 19 && !paintMaterials) continue
                val v = row.int("GeosetGroup_$gi")
                perGroup.getOrPut(group) { ArrayList() } += slot to if (group == 20) (if (v == 0) 2 else v) else 1 + v
            }
            for ((k, att) in ATTACH[slot].orEmpty().withIndex()) {
                if (row.int("ModelType_$k") == COLLECTION) continue   // skinned to the body, below (a belt's or hood's piece)
                val res = row.int("ModelResourcesID_$k").takeIf { it != 0 } ?: row.int("ModelResourcesID_0").takeIf { k > 0 && it != 0 } ?: continue
                val mres = row.int("ModelMaterialResourcesID_$k").takeIf { it != 0 } ?: row.int("ModelMaterialResourcesID_0")
                val position = if (slot == 3) (if (att == 6) 0 else 1) else -1
                val m = ix.itemModel(res, race, sex, position)
                if (m == 0) { diagnostics += "item $item: model resource $res not in ModelFileData"; continue }
                extra += ExtraModel(att, m, ix.texture(mres, race, sex))
            }
            // armour collection pieces, every slot's (a hood's cloth over a helm's: ModelResourcesID_1 of a head piece)
            for (k in 0..1) {
                if (row.int("ModelType_$k") != COLLECTION) continue
                val res = row.int("ModelResourcesID_$k").takeIf { it != 0 } ?: continue
                val m = ix.itemModel(res, race, sex, -1)
                if (m == 0) { diagnostics += "item $item: collection resource $res not in ModelFileData"; continue }
                val tex = ix.texture(row.int("ModelMaterialResourcesID_$k"), race, sex)
                // the piece's own geosets: the slot's groups at the item's attachment values
                val own = SLOT_GEOSETS[slot].orEmpty().map { (gi, group) -> group * 100 + 1 + row.int("AttachmentGeosetGroup_$gi") }
                if (own.isEmpty()) extra += ExtraModel(-1, m, tex)
                else own.forEach { extra += ExtraModel(-1, m, tex, it) }
            }
            if (paintMaterials) {
                val layer = clothingLayer(slot)
                for (material in ix.materials(item)) {
                    val section = material["ComponentSection"]?.toIntOrNull() ?: -1
                    if (section !in 0..8) continue
                    val file = ix.texture(material.int("MaterialResourcesID"), race, sex)
                    if (file != 0) clothingLayers += TextureLayer(file, 1 shl section, 15, layer, null, null)
                }
            }
            if (slot == 15) ix.texture(row.int("ModelMaterialResourcesID_0"), race, sex).takeIf { it != 0 }?.let { textures[2] = it }
            if (slot == 1) {
                val vis = row.int(if (sex == Sex.FEMALE) "HelmetGeosetVis_1" else "HelmetGeosetVis_0")
                for ((r, g) in ix.helmHides[vis].orEmpty()) if (r == race || r == 0) hidden += g
            }
        }
        for ((group, values) in perGroup) {
            val pick = PRIORITY[group]?.firstNotNullOfOrNull { s -> values.firstOrNull { it.first == s } } ?: values.first()
            groups[group] = pick.second
        }
        // a robe covers the boots' tops, as in the game (high boots would poke through its hem)
        if (groups[13] == 2 && (groups[5] ?: 1) > 1) groups[5] = 1
        for (g in hidden) groups[g] = 0

    }

    /**
     * The look of [chosen] choices on [chrModel] read straight from the customization tables (tools/looks.py's way), for
     * a model the creator's catalogue doesn't have: each choice's rows that apply (none hanging on a choice not chosen; a
     * row hanging on a chosen one wins) for geosets, skinned models and textures (onto the model's texture layout), and
     * each option the display leaves unset at its first choice open to everyone.
     */
    private fun raw(ix: Index, chrModel: Int, model: Int, scale: Float, chosen: List<Int>): ResolvedLook {
        val cz = ix.custom
        val picked = chosen.toSet()
        val pickOf = HashMap<Int, Int>()
        for (c in chosen) cz.optionOfChoice[c]?.let { pickOf[it] = c }
        for (o in cz.optionsOf[chrModel].orEmpty()) {
            if (o in pickOf) continue
            val choices = cz.choicesOf[o].orEmpty()
            val open = choices.filter { it.int("ChrCustomizationReqID") == 0 }.ifEmpty { choices }
            open.minWithOrNull(compareBy({ it.int("OrderIndex") }, { it.int("ID") }))?.let { pickOf[o] = it.int("ID") }
        }
        val all = pickOf.values.toSet() + picked
        val layout = ix.layoutOfChrModel[chrModel] ?: 0
        val groups = java.util.TreeMap<Int, Int>()
        val layers = ArrayList<TextureLayer>()
        val extra = LinkedHashMap<Int, ExtraModel>()
        for (choice in pickOf.values) {
            val best = HashMap<Int, Pair<Int, Int>>()        // geoset group -> (rank, geoset)
            for (e in cz.elements[choice].orEmpty()) {
                val rel = e.int("RelatedChrCustomizationChoiceID")
                if (rel != 0 && rel !in all) continue
                val rank = if (rel != 0) 2 else 1
                cz.geosets[e.int("ChrCustomizationGeosetID")]?.let { g ->
                    val type = g.int("GeosetType")
                    if ((best[type]?.first ?: 0) < rank) best[type] = rank to g.int("GeosetID")
                }
                cz.skinned[e.int("ChrCustomizationSkinnedModelID")]?.takeIf { it.int("CollectionsFileDataID") != 0 }?.let { sk ->
                    extra[e.int("ChrCustomizationSkinnedModelID")] = ExtraModel(-1, sk.int("CollectionsFileDataID"), 0, sk.int("GeosetType") * 100 + sk.int("GeosetID"))
                }
                cz.materials[e.int("ChrCustomizationMaterialID")]?.let { m ->
                    val target = m.int("ChrModelTextureTargetID")
                    val file = ix.texture(m.int("MaterialResourcesID"), 0, Sex.MALE)
                    if (file != 0) for (l in cz.layers[layout].orEmpty()) {
                        if (l.int("ChrModelTextureTargetID_0") != target && l.int("ChrModelTextureTargetID_1") != target) continue
                        layers += TextureLayer(file, l.int("TextureSectionTypeBitMask"), l.int("BlendMode"), l.int("Layer"), null, null, l.int("TextureType"))
                    }
                }
            }
            for ((type, v) in best) groups[type] = v.second
        }
        for (e in extra.values) if (e.geoset >= 100) groups.putIfAbsent(e.geoset / 100, e.geoset % 100)
        layers.sortWith(compareBy({ it.textureType }, { it.layer }))
        return ResolvedLook(model, scale, groups, emptyMap(), layers, extra.values.toList(), emptySet())
    }

    /**
     * The renderer's call for a look from [resolve]: the composed layers as Dressing makes them (the character model's
     * texture layout, found by the look's model), and each texture of [ResolvedLook.textures] no layer composes as a
     * whole-texture layer, which the renderer binds to that texture type as it is (a creature's skins, an NPC's baked
     * skin, a cape). A look with no geoset group (most creatures) gets group 0 at 0, which changes nothing but makes the
     * renderer show each group's default geoset rather than every geoset at once.
     */
    fun call(tables: Tables, look: ResolvedLook, layout: Int = index(tables).layoutOfModel[look.model] ?: 0): DressCall {
        val base = Dressing.call(tables, layout, look)
        val composed = HashSet<Int>()
        for (i in 0 until base.layerFiles.size) composed += base.layerMeta[i * Dressing.META]
        val files = base.layerFiles.toMutableList()
        val meta = base.layerMeta.toMutableList()
        val tints = base.tintColours.toMutableList()
        for ((type, file) in look.textures) {
            if (type in composed || file == 0) continue
            files += file
            meta += listOf(type, 0, 0, 0, 0, 0, 0, 0, 0)
            tints += 0; tints += 0
        }
        var keys = base.geosetKeys
        var values = base.geosetValues
        if (keys.isEmpty()) { keys = intArrayOf(0); values = intArrayOf(0) }
        return DressCall(look.model, look.scale, keys, values, files.toIntArray(), meta.toIntArray(), tints.toIntArray(), base.extraModels, base.skipped)
    }

    // --- the tables, indexed once each -----------------------------------------------------------------------------

    private val indexes = WeakHashMap<Tables, Index>()
    private val catalogs = WeakHashMap<Tables, Catalog>()

    private fun index(t: Tables): Index = synchronized(indexes) { indexes.getOrPut(t) { Index(t) } }

    private fun catalog(t: Tables): Catalog = synchronized(catalogs) { catalogs.getOrPut(t) { Catalog.build(t) } }

    /**
     * The big tables (CreatureDisplayInfo, CreatureDisplayInfoOption, NPCModelItemSlotDisplayInfo, ItemDisplayInfo,
     * ModelFileData and the rest: some 55 MB of text) are looked up by key ([Tables.rowsBy]: an index on [CsvTables]),
     * never held whole as maps: a phone's heap hasn't the room.
     */
    private class Index(private val t: Tables) {
        fun display(id: Int) = t.rowBy("CreatureDisplayInfo", "ID", id)
        fun modelData(id: Int) = t.rowBy("CreatureModelData", "ID", id)
        fun extra(id: Int) = t.rowBy("CreatureDisplayInfoExtra", "ID", id)
        fun geosetData(display: Int) =
            t.rowsBy("CreatureDisplayInfoGeosetData", "CreatureDisplayInfoID", display).map { it.int("GeosetIndex") to it.int("GeosetValue") }
        fun options(extra: Int) =
            t.rowsBy("CreatureDisplayInfoOption", "CreatureDisplayInfoExtraID", extra).map { it.int("ChrCustomizationOptionID") to it.int("ChrCustomizationChoiceID") }
        /** NPC item slot -> ItemDisplayInfo id (the last row of a slot wins). */
        fun slots(extra: Int): Map<Int, Int> {
            val out = HashMap<Int, Int>()
            for (r in t.rowsBy("NPCModelItemSlotDisplayInfo", "NpcModelID", extra)) out[r.int("ItemSlot")] = r.int("ItemDisplayInfoID")
            return out
        }
        fun materials(id: Int) = t.rowsBy("ItemDisplayInfoMaterialRes", "ItemDisplayInfoID", id)
        fun item(id: Int) = t.rowBy("ItemDisplayInfo", "ID", id)
        /** ModelResourcesID -> its files (a resource has one per race and sex, or one per shoulder). */
        fun modelFiles(res: Int) = t.rowsBy("ModelFileData", "ModelResourcesID", res).map { it.int("FileDataID") }
        /** A model file -> (gender 0 male 1 female 2 any, race 0 any, position -1 none / 0 left / 1 right). */
        private fun componentModel(file: Int) =
            t.rowBy("ComponentModelFileData", "ID", file)?.let { Triple(it.int("GenderIndex"), it.int("RaceID"), it.int("PositionIndex")) }
        /** MaterialResourcesID -> its files (UsageType, file). */
        fun textureFiles(res: Int) =
            t.rowsBy("TextureFileData", "MaterialResourcesID", res).map { it.int("UsageType") to it.int("FileDataID") }
        /** A texture file -> (gender, race). */
        private fun componentTexture(file: Int) = t.rowBy("ComponentTextureFileData", "ID", file)?.let { it.int("GenderIndex") to it.int("RaceID") }
        val helmHides by lazy { t.rows("HelmetGeosetData").groupBy({ it.int("HelmetGeosetVisDataID") }) { it.int("RaceID") to it.int("HideGeosetGroup") } }
        /** The customization tables, for the models the creator's catalogue doesn't have (raw). */
        val custom by lazy { Custom(t) }
        /** CreatureModelData id -> ChrModel id, and (race, sex) -> ChrModel id; a model file -> its texture layout. */
        val chrModelOfModel: Map<Int, Int>
        val layoutOfChrModel: Map<Int, Int>
        val chrModelOfRace: Map<Pair<Int, Int>, Int>
        val layoutOfModel: Map<Int, Int>
        /** A model file -> the ChrModel whose display shows it. */
        val chrModelOfFile: Map<Int, Int>

        init {
            val chr = t.rows("ChrModel")
            layoutOfChrModel = chr.associate { it.int("ID") to it.int("CharComponentTextureLayoutID") }
            val byModel = HashMap<Int, Int>()
            val layouts = HashMap<Int, Int>()
            val files = HashMap<Int, Int>()
            for (m in chr) {
                val d = display(m.int("DisplayID")) ?: continue
                byModel.putIfAbsent(d.int("ModelID"), m.int("ID"))
                modelData(d.int("ModelID"))?.int("FileDataID")?.takeIf { it != 0 }?.let {
                    layouts.putIfAbsent(it, m.int("CharComponentTextureLayoutID"))
                    files.putIfAbsent(it, m.int("ID"))
                }
            }
            chrModelOfModel = byModel
            layoutOfModel = layouts
            chrModelOfFile = files
            val sexOf = chr.associate { it.int("ID") to it.int("Sex") }
            val byRace = HashMap<Pair<Int, Int>, Int>()
            for (x in t.rows("ChrRaceXChrModel")) {
                val m = x.int("ChrModelID")
                val s = sexOf[m] ?: continue
                byRace.putIfAbsent(x.int("ChrRacesID") to s, m)
            }
            chrModelOfRace = byRace
        }

        class Custom(t: Tables) {
            val optionsOf = t.rows("ChrCustomizationOption").groupBy({ it.int("ChrModelID") }) { it.int("ID") }
            val choicesOf = t.rows("ChrCustomizationChoice").groupBy { it.int("ChrCustomizationOptionID") }
            val optionOfChoice = t.rows("ChrCustomizationChoice").associate { it.int("ID") to it.int("ChrCustomizationOptionID") }
            val elements = t.rows("ChrCustomizationElement").groupBy { it.int("ChrCustomizationChoiceID") }
            val geosets = t.rows("ChrCustomizationGeoset").associateBy { it.int("ID") }
            val skinned = t.rows("ChrCustomizationSkinnedModel").associateBy { it.int("ID") }
            val materials = t.rows("ChrCustomizationMaterial").associateBy { it.int("ID") }
            val layers = t.rows("ChrModelTextureLayer").groupBy { it.int("CharComponentTextureLayoutsID") }
        }

        /** The file of resource [res] for [race] and [sex] at [position] (-1: any): the closest match, then the lowest id. */
        fun itemModel(res: Int, race: Int, sex: Sex, position: Int): Int {
            val files = modelFiles(res)
            if (files.isEmpty()) return 0
            return files.minWith(compareBy({ f ->
                val c = componentModel(f) ?: return@compareBy 50
                var score = 0
                if (position >= 0 && c.third != position) score += 100
                score += when (c.first) { sex.ordinal -> 0; 2 -> 1; else -> 20 }
                score += when (c.second) { race -> 0; 0 -> 2; else -> 10 }
                score
            }, { it }))
        }

        /** The texture of material [res] for [race] and [sex]: the closest ComponentTextureFileData, the lowest UsageType, the lowest id. */
        fun texture(res: Int, race: Int, sex: Sex): Int {
            if (res == 0) return 0
            val files = textureFiles(res)
            if (files.isEmpty()) return 0
            return files.minWith(compareBy({ it.first }, { (_, f) ->
                val c = componentTexture(f) ?: return@compareBy 5
                (when (c.first) { sex.ordinal -> 0; 2 -> 1; else -> 20 }) + (when (c.second) { race -> 0; 0 -> 2; else -> 10 })
            }, { it.second })).second
        }
    }
}
