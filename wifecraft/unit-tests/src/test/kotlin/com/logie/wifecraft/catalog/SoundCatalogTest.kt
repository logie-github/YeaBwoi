package com.logie.wifecraft.catalog

import com.logie.wifecraft.creator.CsvTables
import com.logie.wifecraft.creator.Tables
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SoundCatalogTest {
    private fun m(vararg kv: Pair<String, Any>) = kv.associate { it.first to it.second.toString() }

    private val t = object : Tables {
        val d = mapOf(
            "AreaTable" to listOf(
                m("ID" to 1, "AreaName_lang" to "Town", "ParentAreaID" to 0, "AmbienceID" to 5, "ZoneMusic" to 6, "IntroSound" to 7),
                m("ID" to 2, "AreaName_lang" to "Inn", "ParentAreaID" to 1, "AmbienceID" to 0, "ZoneMusic" to 9, "IntroSound" to 0)),
            "SoundAmbience" to listOf(m("ID" to 5, "AmbienceID_0" to 100, "AmbienceID_1" to 101)),
            "SoundAmbienceFlavor" to listOf(m("ID" to 1, "SoundAmbienceID" to 5, "SoundEntriesIDDay" to 102, "SoundEntriesIDNight" to 102)),
            "ZoneMusic" to listOf(
                m("ID" to 6, "Sounds_0" to 103, "Sounds_1" to 104, "SilenceIntervalMin_0" to 1000, "SilenceIntervalMax_0" to 2000),
                m("ID" to 9, "Sounds_0" to 105, "Sounds_1" to 105, "SilenceIntervalMin_0" to 0, "SilenceIntervalMax_0" to 0)),
            "ZoneIntroMusicTable" to listOf(m("ID" to 7, "SoundID" to 106)),
            "CreatureDisplayInfo" to listOf(m("ID" to 50, "ModelID" to 60, "SoundID" to 0, "NPCSoundID" to 70)),
            "CreatureModelData" to listOf(m("ID" to 60, "SoundID" to 80)),
            "CreatureSoundData" to listOf(m("ID" to 80, "SoundAggroID" to 107, "SoundDeathID" to 0, "LoopSoundID" to 108,
                "FidgetDelaySecondsMin" to 10, "NPCSoundID" to 0, "SoundFidget_0" to 109)),
            "NPCSounds" to listOf(m("ID" to 70, "SoundID_0" to 110, "SoundID_1" to 111, "SoundID_2" to 0, "SoundID_3" to 0)),
            "GameObjectDisplayInfo" to listOf(m("ID" to 90, "ObjectEffectPackageID" to 3)),
            "ObjectEffectPackageElem" to listOf(m("ID" to 1, "ObjectEffectPackageID" to 3, "ObjectEffectGroupID" to 4, "StateType" to 34)),
            "ObjectEffect" to listOf(m("ID" to 1, "ObjectEffectGroupID" to 4, "EffectRecType" to 1, "EffectRecID" to 112)),
            "SoundKitEntry" to (100..112).map { m("ID" to it, "SoundKitID" to it, "FileDataID" to it * 10) } +
                listOf(m("ID" to 999, "SoundKitID" to 103, "FileDataID" to 1031)),
        )
        override fun rows(name: String) = d[name].orEmpty()
    }

    private val s = SoundCatalog(t, SoundCatalog.kitIndex(t))

    @Test fun places() {
        val town = s.location(1)!!
        assertEquals(listOf(1000), town.ambienceDay); assertEquals(listOf(1010), town.ambienceNight)
        assertEquals(listOf(1030, 1031), town.musicDay); assertEquals(listOf(1040), town.musicNight)
        assertEquals(listOf(1060), town.intro); assertEquals(listOf(1020), town.flavorDay)
        assertEquals(1000 to 2000, town.silenceMs)
        // the inn has its own music and its town's ambience and intro
        val inn = s.location(2)!!
        assertEquals(listOf(1050), inn.musicDay); assertEquals(listOf(1000), inn.ambienceDay); assertEquals(listOf(1060), inn.intro)
        assertEquals(null, s.location(3))
    }

    @Test fun creaturesNpcsAndObjects() {
        val c = s.creature(50)
        assertEquals(mapOf("Aggro" to listOf(1070), "Loop" to listOf(1080), "Fidget_0" to listOf(1090)), c)
        assertEquals(mapOf("hello" to listOf(1100), "goodbye" to listOf(1110)), s.npcVoice(50))
        assertEquals(mapOf(34 to listOf(1120)), s.gameObject(90))
        assertEquals(emptyMap(), s.creature(51))
    }

    @Test fun streamsTheKitTable() {
        val csv = "﻿ID,SoundKitID,FileDataID,Frequency\n1,5,500,1\n2,5,501,1\n3,6,600,1\n"
        val all = SoundCatalog.streamKitIndex(csv.byteInputStream())
        assertEquals(listOf(500, 501), all[5]!!.toList())
        val some = SoundCatalog.streamKitIndex(csv.byteInputStream(), setOf(6))
        assertEquals(setOf(6), some.keys)
    }

    /** With the game's tables (WIFECRAFT_CATALOG_TABLES): Dalaran's sound is what assets/sounds.json plays. */
    @Test fun realDalaran() {
        val dir = System.getenv("WIFECRAFT_CATALOG_TABLES")?.let(::File)?.takeIf { File(it, "AreaTable.csv").isFile }
        assumeTrue(dir != null, "WIFECRAFT_CATALOG_TABLES isn't set")
        val tables = CsvTables(dir!!)
        val index = File(dir, "SoundKitEntry.csv").inputStream().use { SoundCatalog.streamKitIndex(it) }
        val real = SoundCatalog(tables) { index[it]?.toList().orEmpty() }
        val dalaran = real.location(7502)!!                       // Dalaran over the Broken Isles
        assertTrue(594573 in dalaran.ambienceDay && 594369 in dalaran.ambienceNight)
        assertTrue(1417233 in dalaran.files)                     // mus_70_dalarandawn_a
        assertTrue(real.creature(4).isNotEmpty() || real.creature(49).isNotEmpty())
    }
}
