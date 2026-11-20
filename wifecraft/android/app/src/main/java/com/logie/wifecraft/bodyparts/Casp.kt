package com.logie.wifecraft.bodyparts

/** One level of detail of a CAS part: the level (0 is the finest) and the meshes it draws (GEOM keys). */
class CaspLod(val level: Int, val meshes: List<ResourceKey>)

/**
 * A Sims 4 CAS part (CASP, 0x034AEECB), the fields a body part needs: its name, body type, age and gender, swatch
 * colours, catalog tags, meshes per level of detail and textures. Keys index [references] (the CASP's own key table).
 */
class PartDescription(
    val version: Int,
    val name: String,
    val parmFlags: Int,
    val bodyType: Int,
    val bodySubType: Int,
    val ageGender: Int,
    val species: Int,
    val swatchColors: IntArray,
    /** Catalog tags as (category, value). */
    val tags: List<Pair<Int, Int>>,
    val sortLayer: Int,
    val lods: List<CaspLod>,
    val diffuse: ResourceKey?,
    val shadow: ResourceKey?,
    val regionMap: ResourceKey?,
    val normalMap: ResourceKey?,
    val specular: ResourceKey?,
    val emission: ResourceKey?,
    val compositionMethod: Int,
    val sharedUvSpace: Int,
    val references: List<ResourceKey>,
) {
    val isMale: Boolean get() = ageGender and MALE != 0
    val isFemale: Boolean get() = ageGender and FEMALE != 0
    /** Young adult, adult or elder. */
    val isAdult: Boolean get() = ageGender and (YOUNG_ADULT or ADULT or ELDER) != 0

    /** The meshes of the finest level of detail present (lowest level number), empty for a texture-only part. */
    fun finestMeshes(): List<ResourceKey> = lods.filter { it.meshes.isNotEmpty() }.minByOrNull { it.level }?.meshes ?: emptyList()
    fun finestLevel(): Int = lods.filter { it.meshes.isNotEmpty() }.minOfOrNull { it.level } ?: -1

    companion object {
        const val BABY = 0x1; const val TODDLER = 0x2; const val CHILD = 0x4; const val TEEN = 0x8
        const val YOUNG_ADULT = 0x10; const val ADULT = 0x20; const val ELDER = 0x40; const val INFANT = 0x80
        const val MALE = 0x1000; const val FEMALE = 0x2000

        /** Body types (the game's BodyType enum) this reader names; others print as their number. */
        val BODY_TYPES = mapOf(
            1 to "Hat", 2 to "Hair", 3 to "Head", 4 to "Teeth", 5 to "Full body", 6 to "Upper body", 7 to "Lower body", 8 to "Shoes",
        )

        fun bodyTypeName(t: Int): String = BODY_TYPES[t] ?: "body type $t"
    }
}

/**
 * Reads a CASP. Layout by version (the fields read but not kept are named in brackets), after CmarNYC's TS4SimRipper
 * CASP.cs and s4pi's CASPartResource.cs, checked on versions 46 and 49:
 *
 *  u32 version, u32 key-table offset (from byte 8), u32 preset count (0), name (7-bit length, UTF-16BE),
 *  f32 [sort priority], u16 [swatch order], u32 [outfit id], u32 [material hash], u8 parm flags, v39+ u8 [parm flags 2],
 *  v50+ u16 [layer id], v51+ (u32 n, n x u64) else (u64, v41+ u64) [exclude part flags], u64 (u32 before v37) [modifier
 *  region flags], u32 tag count and tags (u16 category, u32 value; u16 before v37), u32 [price], u32 [title], u32 [desc],
 *  v43+ u32 [create desc], u8 [texture space], u32 body type, u32 body sub-type, u32 age/gender, v32+ u32 species,
 *  v34+ (u16 pack, u8 flags, 9 bytes) else (u8, and a u8 if not 0), u8 swatch count and ARGB u32s, u8 [buff key],
 *  u8 [variant thumbnail key], v28+ u64 [voice effect], v30+ u8 material count and, if not 0, 3 x u32, v31+ u32 [occult
 *  flags], v46+ u64 [unknown], v38+ u64 [opposite gender part], v39+ u64 [fallback part], v44+ 2 + 3 + 3 + 3 f32
 *  [opacity, hue, saturation, brightness sliders], v46+ u8 n and n bytes [unknown], u8 [naked key], u8 [parent key],
 *  i32 sort layer, u8 LOD count, per LOD (u8 level, u32 [unused], u8 n and n x 12 bytes [assets], u8 n and n key
 *  indices), u8 n and n slot key indices, u8 diffuse, u8 shadow, u8 composition method, u8 region map, u8 n and n x 5
 *  bytes [overrides], u8 normal, u8 specular, v27+ u32 shared UV space, v29+ u8 emission, v42+ u8, v49+ u8, v52+ u8;
 *  then the key table: u8 count, each instance u64, group u32, type u32.
 */
object Casp {
    const val TYPE = 0x034AEECB

    fun parse(resource: ByteArray, key: String = "CASP"): PartDescription {
        val r = Bin(resource, "CAS part $key")
        val v = r.i32()
        if (v < 27 || v > 60) r.fail("is CASP version $v; versions 27 to 60 are read")
        val tableAt = r.i32() + 8
        if (tableAt < 12 || tableAt >= resource.size) r.fail("points its key table outside the resource (byte $tableAt)")
        val presets = r.i32()
        if (presets != 0) r.fail("carries $presets presets; this reader expects none")
        val name = r.string7BigEndianUtf16()
        r.f32(); r.u16(); r.i32(); r.i32()
        val parm = r.u8()
        if (v >= 39) r.u8()
        if (v >= 50) r.u16()
        if (v >= 51) r.skip(8 * r.count(8, "exclude flags")) else { r.u64(); if (v >= 41) r.u64() }
        if (v >= 37) r.u64() else r.i32()
        val tagCount = r.count(if (v >= 37) 6 else 4, "tags")
        val tags = List(tagCount) { r.u16() to if (v >= 37) r.i32() else r.u16() }
        r.i32(); r.i32(); r.i32()
        if (v >= 43) r.i32()
        r.u8()
        val bodyType = r.i32()
        val bodySub = r.i32()
        val ageGender = r.i32()
        val species = if (v >= 32) r.i32() else 1
        if (v >= 34) { r.u16(); r.u8(); r.skip(9) } else { if (r.u8() != 0) r.u8() }
        val swatches = IntArray(r.u8()) { r.i32() }
        r.u8(); r.u8()
        if (v >= 28) r.u64()
        if (v >= 30) { if (r.u8() != 0) r.skip(12) }
        if (v >= 31) r.i32()
        if (v >= 46) r.u64()
        if (v >= 38) r.u64()
        if (v >= 39) r.u64()
        if (v >= 44) r.skip(4 * (2 + 3 + 3 + 3))
        if (v >= 46) r.skip(r.u8())
        r.u8(); r.u8()
        val sortLayer = r.i32()

        val keys = readKeyTable(r.absolute(tableAt))
        fun ref(i: Int): ResourceKey? = keys.getOrNull(i)?.takeIf { it != ResourceKey.NONE }

        val lods = ArrayList<CaspLod>()
        repeat(r.u8()) {
            val level = r.u8()
            r.i32()
            r.skip(12 * r.u8())
            val idx = List(r.u8()) { r.u8() }
            lods += CaspLod(level, idx.mapNotNull { i -> ref(i)?.takeIf { it.type == Geom.TYPE } })
        }
        r.skip(r.u8())
        val diffuse = r.u8(); val shadow = r.u8(); val comp = r.u8(); val region = r.u8()
        r.skip(5 * r.u8())
        val normal = r.u8(); val spec = r.u8()
        val uvSpace = r.i32()
        val emission = if (v >= 29) r.u8() else 0
        if (v >= 42) r.u8()
        if (v >= 49) r.u8()
        if (v >= 52) r.u8()
        if (r.p != tableAt) r.fail("ends its fields at byte ${r.p}, but its key table starts at $tableAt (an unknown CASP version $v layout)")
        return PartDescription(v, name, parm, bodyType, bodySub, ageGender, species, swatches, tags, sortLayer, lods,
            ref(diffuse), ref(shadow), ref(region), ref(normal), ref(spec), ref(emission), comp, uvSpace, keys)
    }

    private fun readKeyTable(t: Bin): List<ResourceKey> {
        val n = t.u8()
        return List(n) { val inst = t.u64(); val g = t.i32(); val ty = t.i32(); ResourceKey(ty, g, inst) }
    }
}
