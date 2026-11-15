package com.logie.wifecraft.audio

import com.logie.wifecraft.catalog.Emote
import com.logie.wifecraft.catalog.LocationAudio
import com.logie.wifecraft.catalog.SoundCatalog
import com.logie.wifecraft.creator.Tables
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SceneAudioTest {
    private class Voice(val file: Int, val loop: Boolean, var vol: Float, val fadeIn: Int, val onDone: () -> Unit) : SoundVoice {
        var stopped = false; var stopFade = -1; var paused = false
        override fun setVolume(v: Float) { vol = v }
        override fun stop(fadeMs: Int) { stopped = true; stopFade = fadeMs }
        override fun pause() { paused = true }
        override fun resume() { paused = false }
    }

    /** Everything runs at once; [later] tasks wait for [advance]. */
    private class Rig(val here: MutableSet<Int> = mutableSetOf(), val failing: Set<Int> = emptySet()) {
        val voices = ArrayList<Voice>()
        val asked = ArrayList<Triple<Int, Int, Int>>()
        val errors = ArrayList<String>()
        val fetched = ArrayList<Int>()
        val waits = ArrayList<Pair<Long, () -> Unit>>()
        val area = LocationAudio(9171, "Embassy", listOf(5690, 5691), listOf(5692), listOf(1, 2, 3), listOf(4), emptyList(), emptyList(), emptyList(), 2000 to 2000)
        val lookup = object : SoundLookup {
            override fun location(areaId: Int, zoneMusic: Int, ambience: Int) = if (areaId == 9171) area.also { asked += Triple(areaId, zoneMusic, ambience) } else null
            override fun creature(displayId: Int) = if (displayId == 5) mapOf("Aggro" to listOf(70, 71), "Loop" to listOf(72), "Injury" to listOf(73)) else emptyMap()
            override fun gameObject(displayId: Int) = if (displayId == 8) mapOf(34 to listOf(80), 12 to listOf(81)) else emptyMap()
        }
        val core = SceneAudioCore(lookup,
            object : SoundFiles {
                override fun has(id: Int) = id in here
                override fun fetch(id: Int) { fetched += id; if (id in failing) throw java.io.IOException("wago.tools answered 404"); here += id }
            },
            object : SoundOut {
                override fun play(file: Int, loop: Boolean, volume: Float, fadeInMs: Int, onDone: () -> Unit) = Voice(file, loop, volume, fadeInMs, onDone).also { voices += it }
            },
            object : SoundScheduler {
                override fun background(task: () -> Unit) = task()
                override fun main(task: () -> Unit) = task()
                override fun later(ms: Long, task: () -> Unit) { waits += ms to task }
            }, { errors += it })
        fun playing() = voices.filter { !it.stopped }.map { it.file }
    }

    private fun emote(vararg files: Int) = Emote(7, "Laugh", 70, 0, files.toList())

    @Test fun locationPlaysAmbienceLoopAndMusicFetchingWhatIsMissing() {
        val r = Rig(mutableSetOf(5690, 5691))
        r.core.enterLocation(9171)
        val amb = r.voices.first { it.loop }
        assertTrue(amb.file in listOf(5690, 5691))
        val music = r.voices.first { !it.loop }
        assertTrue(music.file in listOf(1, 2, 3))
        assertEquals(listOf(music.file), r.fetched)      // the ambience was here; only the first track was fetched
        assertTrue(r.errors.isEmpty())
        // the same place again does nothing
        r.core.enterLocation(9171); assertEquals(2, r.voices.size)
    }

    @Test fun theRoomsOwnMusicAndAmbienceAreAskedFor() {
        val r = Rig(mutableSetOf(5690, 5691, 1, 2, 3))
        r.core.enterLocation(9171, zoneMusic = 1929, ambience = 569)
        assertEquals(listOf(Triple(9171, 1929, 569)), r.asked)
        r.core.enterLocation(9171, zoneMusic = 1929, ambience = 569); assertEquals(1, r.asked.size)
        r.core.enterLocation(9171); assertEquals(2, r.asked.size)
    }

    @Test fun musicGoesTrackByTrackWithTheSilenceBetween() {
        val r = Rig(mutableSetOf(5690, 5691, 1, 2, 3))
        r.core.enterLocation(9171)
        val first = r.voices.first { !it.loop }
        first.onDone()
        assertEquals(1, r.waits.size); assertEquals(2000L, r.waits[0].first)
        r.waits[0].second()
        val second = r.voices.last { !it.loop }
        assertTrue(second !== first && second.file != first.file)
    }

    @Test fun leavingFadesTheOldPlaceAndIgnoresItsPendingWork() {
        val r = Rig(mutableSetOf(5690, 5691, 1, 2, 3))
        r.core.enterLocation(9171)
        r.voices.first { !it.loop }.onDone()
        val pending = r.waits.single().second
        r.core.enterLocation(9999)                              // unknown area: told, and the old place is gone
        assertTrue(r.voices.all { it.stopped && it.stopFade == SceneAudioCore.FADE_MS })
        assertEquals(1, r.errors.size)
        val n = r.voices.size; pending(); assertEquals(n, r.voices.size)
        r.core.stop(); assertTrue(r.voices.all { it.stopped })
    }

    @Test fun aFileThatWontFetchIsToldAndTheRestPlays() {
        val r = Rig(mutableSetOf(), failing = setOf(1, 2, 3))
        r.core.enterLocation(9171)
        assertEquals(1, r.errors.size); assertTrue(r.errors[0].contains("couldn't be fetched"))
        assertTrue(r.voices.any { it.loop })                    // the ambience arrived
        assertTrue(r.voices.none { !it.loop })
    }

    @Test fun nightUsesTheNightKitAndFallsBackToDay() {
        val r = Rig(mutableSetOf(5690, 5691, 5692, 1, 2, 3, 4))
        r.core.enterLocation(9171, night = true)
        assertEquals(5692, r.voices.first { it.loop }.file)
        assertEquals(4, r.voices.first { !it.loop }.file)
    }

    @Test fun emoteVoiceIsStablePerRaceSexAndEmote() {
        fun firstPicks(race: Int, sex: Int): List<Int> {
            val r = Rig(mutableSetOf(*(1..40).toList().toTypedArray()))
            val e = emote(*(1..40).toList().toIntArray())
            repeat(5) { r.core.emote(race, sex, e) }
            return r.voices.map { it.file }
        }
        assertEquals(firstPicks(1, 0), firstPicks(1, 0))
        assertTrue(firstPicks(1, 0) != firstPicks(2, 1))
        assertTrue(firstPicks(1, 0).toSet().size > 1)           // successive plays vary
    }

    @Test fun anEmoteWithoutAVoiceDoesNothing() {
        val r = Rig()
        r.core.emote(1, 0, emote())
        assertTrue(r.voices.isEmpty() && r.fetched.isEmpty())
    }

    @Test fun aPlacementSoundsOffMutesAndSilences() {
        val r = Rig(mutableSetOf(70, 71, 72, 73, 80, 81, 9))
        r.core.creature(5, "loop", "p1")
        assertEquals(listOf(72), r.playing()); assertTrue(r.voices[0].loop)
        r.core.creature(5, "loop", "p1")                        // already looping: not doubled
        assertEquals(1, r.voices.size)
        r.core.setEnabled("p1", false)
        assertTrue(r.playing().isEmpty())
        r.core.creature(5, "attack", "p1"); r.core.emote(1, 0, emote(9), "p1"); r.core.gameObject(8, "p1")
        assertEquals(1, r.voices.size)
        r.core.emote(1, 0, emote(9), "p2"); assertEquals(listOf(9), r.playing())
        r.core.setEnabled("p1", true); r.core.gameObject(8, "p1"); assertEquals(listOf(9, 81), r.playing())
    }

    @Test fun creatureKindsAndObjectsResolve() {
        val have = listOf("Aggro", "Injury", "Fidget_0", "Loop", "Alert")
        assertEquals("Aggro", SceneAudioCore.resolveKind("attack", have))
        assertEquals("Injury", SceneAudioCore.resolveKind("wound", have))
        assertEquals("Fidget_0", SceneAudioCore.resolveKind("stand", have))   // no Stand: its first fidget
        assertEquals("Alert", SceneAudioCore.resolveKind("ALERT", have))
        assertNull(SceneAudioCore.resolveKind("death", have))
        val r = Rig(mutableSetOf(70, 71, 73, 81, 80))
        r.core.creature(5, "wound"); assertEquals(listOf(73), r.playing())
        r.core.creature(5, "death"); assertEquals(1, r.voices.size)           // none: silent
        r.core.creature(404, "attack"); assertEquals(1, r.voices.size)
        r.core.gameObject(8, state = 34); assertEquals(80, r.voices.last().file)
        r.core.`object`(8); assertEquals(81, r.voices.last().file)             // the lowest state
        r.core.`object`(1); assertEquals(3, r.voices.size)                      // an object with none: nothing
    }

    @Test fun pauseHoldsAndStopsNewSoundsAndMusicResumes() {
        val r = Rig(mutableSetOf(5690, 5691, 1, 2, 3, 9))
        r.core.enterLocation(9171)
        r.core.pause(); assertTrue(r.voices.all { it.paused })
        r.core.emote(1, 0, emote(9)); assertEquals(2, r.voices.size)
        r.voices.first { !it.loop }.onDone(); r.waits.single().second()         // the next track is due during the pause
        assertEquals(2, r.voices.size)
        r.core.resume(); assertFalse(r.voices.first { it.loop }.paused)
        assertEquals(3, r.voices.size)
    }

    @Test fun volumesReachEachChannel() {
        val r = Rig(mutableSetOf(5690, 5691, 1, 2, 3, 9))
        r.core.volumes = SoundVolumes(music = 0.4f, ambience = 0.5f, effects = 0.8f, voices = 0.3f)
        r.core.enterLocation(9171); r.core.emote(1, 0, emote(9))
        assertEquals(0.4f, r.voices.first { !it.loop && it.file != 9 }.vol)
        assertEquals(0.4f, r.voices.first { it.loop }.vol, 1e-6f)          // ambience 0.5 x effects 0.8
        assertEquals(0.3f, r.voices.first { it.file == 9 }.vol)
        r.core.volumes = SoundVolumes(music = 1f, voices = 1f)
        assertEquals(1f, r.voices.first { it.file == 9 }.vol)
    }

    // --- the compact index ---------------------------------------------------------------------------------------------

    private val csv = "ID,SoundKitID,FileDataID,Frequency,Volume,PlayerConditionID\n2,3,568392,1,1,0\n3,7,568542,1,1,0\n4,7,568543,1,1,0\n5,9,1,1,1,0\n"

    private val tables = object : Tables {
        override fun rows(name: String): List<Map<String, String>> = when (name) {
            "AreaTable" -> listOf(mapOf("ID" to "1", "AreaName_lang" to "A", "ParentAreaID" to "0", "AmbienceID" to "4", "ZoneMusic" to "0", "IntroSound" to "0"))
            "SoundAmbience" -> listOf(mapOf("ID" to "4", "AmbienceID_0" to "3", "AmbienceID_1" to "0"))
            "CreatureSoundData" -> listOf(mapOf("ID" to "1", "SoundAggroID" to "7", "FidgetDelaySecondsMin" to "5", "NPCSoundID" to "0"))
            else -> emptyList()
        }
    }

    @Test fun usedKitsAreTheTablesOwn() {
        assertEquals(setOf(3, 7), SoundCatalog.usedKits(tables))
    }

    @Test fun indexKeepsOnlyTheUsedKitsAndRoundTrips() {
        val idx = SoundIndex.build(tables, ByteArrayInputStream(csv.toByteArray()))
        assertEquals(setOf(3, 7), idx.kits.keys)
        assertEquals(listOf(568542, 568543), idx.files(7)); assertEquals(emptyList(), idx.files(9))
        val b = ByteArrayOutputStream(); idx.write(b, "k1")
        val back = assertNotNull(SoundIndex.read(b.toByteArray(), "k1"))
        assertContentEquals(idx.kits.getValue(7), back.kits.getValue(7)); assertEquals(idx.kits.keys, back.kits.keys)
        assertNull(SoundIndex.read(b.toByteArray(), "k2"))                            // another build or tables
        assertNull(SoundIndex.read(b.toByteArray().copyOf(b.size() - 1), "k1"))        // cut off
        assertNull(SoundIndex.read(ByteArray(3), "k1"))
    }

    @Test fun indexIsBuiltOnceAndKept() {
        val dir = kotlin.io.path.createTempDirectory().toFile()
        val f = File(dir, "cache/sound-index.bin")
        var opened = 0
        val (a, built) = SoundIndex.load(f, "k", tables) { opened++; ByteArrayInputStream(csv.toByteArray()) }
        assertTrue(built); assertTrue(f.isFile); assertFalse(File(f.path + ".part").exists())
        val (b, builtAgain) = SoundIndex.load(f, "k", tables) { opened++; ByteArrayInputStream(csv.toByteArray()) }
        assertFalse(builtAgain); assertEquals(1, opened); assertEquals(a.kits.keys, b.kits.keys)
        SoundIndex.load(f, "other", tables) { opened++; ByteArrayInputStream(csv.toByteArray()) }
        assertEquals(2, opened)
        dir.deleteRecursively()
    }

    /** The real tables and SoundKitEntry (WIFECRAFT_SOUND_TABLES = a folder of the CSVs, SoundKitEntry.csv too): sizes and the Embassy. */
    @Test fun realTables() {
        val dir = System.getenv("WIFECRAFT_SOUND_TABLES")?.let(::File)
        assumeTrue(dir != null && File(dir, "SoundKitEntry.csv").isFile && File(dir, "AreaTable.csv").isFile)
        val t = com.logie.wifecraft.creator.CsvTables(dir!!)
        val t0 = System.nanoTime()
        val idx = File(dir, "SoundKitEntry.csv").inputStream().use { SoundIndex.build(t, it) }
        val ms = (System.nanoTime() - t0) / 1_000_000
        val b = ByteArrayOutputStream(); idx.write(b, "real")
        println("sound index: ${idx.kits.size} kits, ${idx.kits.values.sumOf { it.size }} files, ${b.size()} bytes, built in $ms ms (tables included)")
        val cat = SoundCatalog(t) { idx.files(it) }
        val emb = cat.location(9171, zoneMusic = 1929, ambience = 569)!!
        println("Embassy: ambience ${emb.ambienceDay} / ${emb.ambienceNight} music ${emb.musicDay} silence ${emb.silenceMs}")
        assertEquals(listOf(53205, 53206, 53208, 441767, 1417244, 1417249), emb.musicDay.sorted().let { m -> listOf(53205, 53206, 53208, 441767, 1417244, 1417249).filter { it in m } })
        assertEquals(6, emb.musicDay.size)
        assertEquals(listOf(539080, 539127), (emb.ambienceDay + emb.ambienceNight).sorted())
        // the area alone gives its parent's music (Stormwind City's)
        assertEquals(10, cat.location(9171)!!.musicDay.size)
    }
}
