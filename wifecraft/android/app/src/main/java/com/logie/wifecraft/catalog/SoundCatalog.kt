package com.logie.wifecraft.catalog

import com.logie.wifecraft.creator.Tables
import java.io.InputStream

/**
 * A place's sound, by AreaTable id (the locations' areas: W25's work names them): the ambience loop by day and by night
 * (AreaTable.AmbienceID -> SoundAmbience.AmbienceID_0/_1), the music by day and night (ZoneMusic.Sounds_0/_1: one of
 * the files at random, then a silence of [silenceMs] before the next), the zone's intro music (ZoneIntroMusicTable) and
 * the ambience's flavour sounds (SoundAmbienceFlavor: one-shots, birds, bells, played now and then). File ids; empty
 * where the table has nothing. A sub-area with none of its own has its parent's (AreaTable.ParentAreaID).
 */
data class LocationAudio(
    val areaId: Int,
    val name: String,
    val ambienceDay: List<Int>,
    val ambienceNight: List<Int>,
    val musicDay: List<Int>,
    val musicNight: List<Int>,
    val intro: List<Int>,
    val flavorDay: List<Int>,
    val flavorNight: List<Int>,
    /** ZoneMusic's silence between tracks, day: min, max (ms); 0, 0 without music. */
    val silenceMs: Pair<Int, Int> = 0 to 0,
) {
    /** Every file the place's sound needs. */
    val files: Set<Int> get() = (ambienceDay + ambienceNight + musicDay + musicNight + intro + flavorDay + flavorNight).toSet()
}

/**
 * The game's sounds for the scene editor, from its tables: a place's ([location]), a creature's ([creature]: its
 * CreatureSoundData, by display), an NPC's voice ([npcVoice]: NPCSounds) and a game object's ([gameObject]: its display's
 * ObjectEffectPackage). [kitFiles] gives a sound kit's files (SoundKitEntry): [kitIndex] builds it from the table for tests
 * and the desktop; on the phone SoundKitEntry is 37 MB of CSV (1.3 million rows), too big to hold as text, so
 * [streamKitIndex] reads it once, a line at a time, keeping only the kits asked for. Pure JVM.
 */
class SoundCatalog(private val t: Tables, private val kitFiles: (Int) -> List<Int>) {
    private fun Map<String, String>.int(k: String) = this[k]?.toDoubleOrNull()?.toInt() ?: 0

    private val areas by lazy { t.rows("AreaTable").associateBy { it.int("ID") } }
    private val ambiences by lazy { t.rows("SoundAmbience").associateBy { it.int("ID") } }
    private val flavors by lazy { t.rows("SoundAmbienceFlavor").groupBy { it.int("SoundAmbienceID") } }
    private val music by lazy { t.rows("ZoneMusic").associateBy { it.int("ID") } }
    private val intros by lazy { t.rows("ZoneIntroMusicTable").associateBy { it.int("ID") } }
    private val displays by lazy { t.rows("CreatureDisplayInfo").associateBy { it.int("ID") } }
    private val models by lazy { t.rows("CreatureModelData").associateBy { it.int("ID") } }
    private val creatureSounds by lazy { t.rows("CreatureSoundData").associateBy { it.int("ID") } }
    private val npcSounds by lazy { t.rows("NPCSounds").associateBy { it.int("ID") } }
    private val objectDisplays by lazy { t.rows("GameObjectDisplayInfo").associateBy { it.int("ID") } }
    private val packageElems by lazy { t.rows("ObjectEffectPackageElem").groupBy { it.int("ObjectEffectPackageID") } }
    private val groupEffects by lazy { t.rows("ObjectEffect").groupBy { it.int("ObjectEffectGroupID") } }

    private fun files(kit: Int): List<Int> = if (kit <= 0) emptyList() else kitFiles(kit)

    /**
     * A place's sound (null: no such area). [zoneMusic] and [ambience] (a ZoneMusic id, a SoundAmbience id) take the place
     * of the area's own where given: a building's rooms (a WMO's WMOAreaTable) have their own music over the area's, as the
     * Stormwind Embassy (area 9171: the AreaTable row has none and would give Stormwind City's) has ZoneMusic 1929.
     */
    fun location(areaId: Int, zoneMusic: Int = 0, ambience: Int = 0): LocationAudio? {
        val a = areas[areaId] ?: return null
        // the area, then its parents, for a field the area leaves at 0
        val chain = generateSequence(a) { r -> r.int("ParentAreaID").takeIf { it != 0 && it != r.int("ID") }?.let { areas[it] } }.take(8).toList()
        fun first(col: String) = chain.firstNotNullOfOrNull { r -> r.int(col).takeIf { it != 0 } } ?: 0
        val amb = ambiences[ambience.takeIf { it != 0 } ?: first("AmbienceID")]
        val zm = music[zoneMusic.takeIf { it != 0 } ?: first("ZoneMusic")]
        val intro = intros[first("IntroSound")]
        val fl = amb?.let { flavors[it.int("ID")] }.orEmpty()
        return LocationAudio(
            areaId, a["AreaName_lang"].orEmpty(),
            files(amb?.int("AmbienceID_0") ?: 0), files(amb?.int("AmbienceID_1") ?: 0),
            files(zm?.int("Sounds_0") ?: 0), files(zm?.int("Sounds_1") ?: 0),
            files(intro?.int("SoundID") ?: 0),
            fl.flatMap { files(it.int("SoundEntriesIDDay")) }.distinct(), fl.flatMap { files(it.int("SoundEntriesIDNight")) }.distinct(),
            if (zm == null) 0 to 0 else zm.int("SilenceIntervalMin_0") to zm.int("SilenceIntervalMax_0"),
        )
    }

    /**
     * A creature's sounds by display (CreatureDisplayInfo.SoundID, else its model's CreatureModelData.SoundID): each
     * CreatureSoundData column that names a kit with files, by its name less "Sound"/"ID" ("Aggro", "Death", "Injury",
     * "Exertion", "Alert", "Stand", "Footstep", "Loop", "Fidget_0", "BattleShout", "CustomAttack_0"...).
     */
    fun creature(displayId: Int): Map<String, List<Int>> {
        val d = displays[displayId] ?: return emptyMap()
        val id = d.int("SoundID").takeIf { it != 0 } ?: models[d.int("ModelID")]?.int("SoundID") ?: 0
        val row = creatureSounds[id] ?: return emptyMap()
        val out = LinkedHashMap<String, List<Int>>()
        for ((col, _) in row) {
            if (col in NOT_KITS || col.startsWith("Field_")) continue
            val f = files(row.int(col))
            if (f.isNotEmpty()) out[label(col)] = f
        }
        return out
    }

    /**
     * An NPC's voice by display (CreatureDisplayInfo.NPCSoundID -> NPCSounds): "hello", "goodbye", "pissed", "ack" (its
     * SoundID_0..3, in wowdev's names: greeting, farewell, clicked too often, acknowledged).
     */
    fun npcVoice(displayId: Int): Map<String, List<Int>> {
        val n = npcSounds[displays[displayId]?.int("NPCSoundID") ?: 0] ?: return emptyMap()
        val out = LinkedHashMap<String, List<Int>>()
        NPC_VOICE.forEachIndexed { i, name -> files(n.int("SoundID_$i")).takeIf { it.isNotEmpty() }?.let { out[name] = it } }
        return out
    }

    /**
     * A game object's sounds by its GameObjectDisplayInfo id: the sound kits (ObjectEffect.EffectRecType 1) of its
     * ObjectEffectPackage's groups, by the element's state (ObjectEffectPackageElem.StateType). Few objects have one (201
     * displays of 27,128 in 12.1.7: transports, doors, carts); a doodad's own sounds are in its model (M2 events) instead.
     */
    fun gameObject(displayId: Int): Map<Int, List<Int>> {
        val pkg = objectDisplays[displayId]?.int("ObjectEffectPackageID") ?: return emptyMap()
        if (pkg == 0) return emptyMap()
        val elems = packageElems[pkg].orEmpty()
        val out = LinkedHashMap<Int, MutableList<Int>>()
        for (e in elems) for (fx in groupEffects[e.int("ObjectEffectGroupID")].orEmpty()) {
            if (fx.int("EffectRecType") != 1) continue
            val f = files(fx.int("EffectRecID"))
            if (f.isNotEmpty()) out.getOrPut(e.int("StateType")) { ArrayList() }.addAll(f)
        }
        return out.mapValues { it.value.distinct() }
    }

    companion object {
        /** The tables [SoundCatalog] reads (SoundKitEntry through [kitFiles]), at creator/GameTables.BUILD. */
        val TABLES = listOf("AreaTable", "SoundAmbience", "SoundAmbienceFlavor", "ZoneMusic", "ZoneIntroMusicTable",
            "CreatureDisplayInfo", "CreatureModelData", "CreatureSoundData", "NPCSounds", "GameObjectDisplayInfo",
            "ObjectEffectPackageElem", "ObjectEffect", "SoundKitEntry")

        val NPC_VOICE = listOf("hello", "goodbye", "pissed", "ack")

        private val NOT_KITS = setOf("ID", "FidgetDelaySecondsMin", "FidgetDelaySecondsMax", "CreatureImpactType",
            "CreatureSoundDataIDPet", "NPCSoundID")

        internal fun label(col: String): String = col.removePrefix("Sound").removeSuffix("SoundID").removeSuffix("ID")

        /**
         * Every sound kit the editor can ask for, read from the tables alone (no SoundKitEntry): the kits of every
         * ZoneMusic, SoundAmbience, flavour and intro row, every CreatureSoundData kit column,
         * NPCSounds' four and the ObjectEffects' sound kits. [audio.SoundIndex] keeps only these out of SoundKitEntry's
         * 1.3 million rows.
         */
        fun usedKits(t: Tables): Set<Int> {
            fun Map<String, String>.i(k: String) = this[k]?.toDoubleOrNull()?.toInt() ?: 0
            val kits = HashSet<Int>()
            fun add(rows: String, vararg cols: String) { for (r in t.rows(rows)) for (c in cols) r.i(c).takeIf { it > 0 }?.let { kits += it } }
            // every row of the places' tables, not only the areas': a building's rooms name a ZoneMusic or SoundAmbience of their own (WMOAreaTable)
            add("ZoneMusic", "Sounds_0", "Sounds_1"); add("SoundAmbience", "AmbienceID_0", "AmbienceID_1")
            add("SoundAmbienceFlavor", "SoundEntriesIDDay", "SoundEntriesIDNight"); add("ZoneIntroMusicTable", "SoundID")
            for (r in t.rows("CreatureSoundData")) for (col in r.keys) {
                if (col in NOT_KITS || col.startsWith("Field_")) continue
                r.i(col).takeIf { it > 0 }?.let { kits += it }
            }
            for (r in t.rows("NPCSounds")) for (i in 0..3) r.i("SoundID_$i").takeIf { it > 0 }?.let { kits += it }
            for (r in t.rows("ObjectEffect")) if (r.i("EffectRecType") == 1) r.i("EffectRecID").takeIf { it > 0 }?.let { kits += it }
            return kits
        }

        /** A kit's files from the SoundKitEntry table as rows (tests, the desktop). */
        fun kitIndex(t: Tables): (Int) -> List<Int> {
            val m = HashMap<Int, MutableList<Int>>()
            for (r in t.rows("SoundKitEntry")) {
                val kit = r["SoundKitID"]?.toIntOrNull() ?: continue
                val f = r["FileDataID"]?.toIntOrNull() ?: continue
                m.getOrPut(kit) { ArrayList(2) } += f
            }
            return { m[it].orEmpty() }
        }

        /**
         * SoundKitEntry's CSV read a line at a time (its fields are numbers: no quoting), keeping the kits in [wanted]
         * (all when null): kit -> its files, in the table's order.
         */
        fun streamKitIndex(input: InputStream, wanted: Set<Int>? = null): Map<Int, IntArray> {
            val m = HashMap<Int, IntArrayList>()
            input.bufferedReader().useLines { lines ->
                var kitCol = -1
                var fileCol = -1
                for (line in lines) {
                    val f = line.trimStart('﻿').split(',')
                    if (kitCol < 0) { kitCol = f.indexOf("SoundKitID"); fileCol = f.indexOf("FileDataID"); if (kitCol < 0 || fileCol < 0) return emptyMap(); continue }
                    val kit = f.getOrNull(kitCol)?.toIntOrNull() ?: continue
                    if (wanted != null && kit !in wanted) continue
                    val file = f.getOrNull(fileCol)?.toIntOrNull() ?: continue
                    m.getOrPut(kit) { IntArrayList() }.add(file)
                }
            }
            return m.mapValues { it.value.toArray() }
        }

        private class IntArrayList {
            private var a = IntArray(2)
            private var n = 0
            fun add(v: Int) { if (n == a.size) a = a.copyOf(n * 2); a[n++] = v }
            fun toArray(): IntArray = a.copyOf(n)
        }
    }
}
