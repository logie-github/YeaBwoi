package com.logie.wifecraft.audio

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SoundscapeTest {
    private fun book(): SoundBook {
        val dir = System.getProperty("wifecraft.assets") ?: "../android/app/src/main/assets"
        return SoundBook.parse(File(dir, "sounds.json").readText())
    }

    // ---- the mixer ----

    @Test fun mixerMultipliesMasterAndBusOnACurve() {
        val m = MixLevels(master = 100, music = 50, ambience = 100, effects = 0, dialog = 80)
        assertEquals(0.25f, Mixer.gain(m, Bus.MUSIC), 1e-6f)
        assertEquals(1f, Mixer.gain(m, Bus.AMBIENCE), 1e-6f)
        assertEquals(0f, Mixer.gain(m, Bus.EFFECTS), 1e-6f)
        assertEquals(0f, Mixer.gain(m, Bus.UI), 1e-6f)             // the interface's clicks follow Sound effects
        assertEquals(0.64f, Mixer.gain(m, Bus.DIALOG), 1e-6f)
        assertEquals(0.25f * 0.25f, Mixer.gain(m.copy(master = 50), Bus.MUSIC), 1e-6f)
    }

    @Test fun disabledSoundOrZeroMasterSilencesEverything() {
        for (b in Bus.entries) {
            assertEquals(0f, Mixer.gain(MixLevels(enabled = false), b))
            assertEquals(0f, Mixer.gain(MixLevels(master = 0), b))
        }
    }

    @Test fun duckTouchesOnlyTheBeds() {
        val m = MixLevels(master = 100, music = 100, ambience = 100, effects = 100, dialog = 100)
        assertEquals(0.5f, Mixer.gain(m, Bus.MUSIC, duck = 0.5f), 1e-6f)
        assertEquals(0.5f, Mixer.gain(m, Bus.AMBIENCE, duck = 0.5f), 1e-6f)
        assertEquals(1f, Mixer.gain(m, Bus.DIALOG, duck = 0.5f), 1e-6f)
        assertEquals(1f, Mixer.gain(m, Bus.EFFECTS, duck = 0.5f), 1e-6f)
    }

    @Test fun outOfRangeSlidersAreClamped() {
        assertEquals(1f, Mixer.curve(150)); assertEquals(0f, Mixer.curve(-5))
    }

    @Test fun sceneVolumesComeOutAtTheMixersGains() {
        val m = MixLevels(master = 80, music = 60, ambience = 40, effects = 90, dialog = 70)
        val v = Mixer.sceneVolumes(m)
        assertEquals(Mixer.gain(m, Bus.MUSIC), v.music, 1e-6f)
        assertEquals(Mixer.gain(m, Bus.EFFECTS), v.effects, 1e-6f)
        assertEquals(Mixer.gain(m, Bus.DIALOG), v.voices, 1e-6f)
        // SceneAudioCore plays the ambience at ambience x effects
        assertEquals(Mixer.gain(m, Bus.AMBIENCE), v.ambience * v.effects, 1e-6f)
        assertEquals(0f, Mixer.sceneVolumes(m.copy(effects = 0)).ambience)
    }

    // ---- ducking ----

    @Test fun duckerDipsWhileAVoicePlaysAndComesBackSlower() {
        val d = Ducker(depth = 0.4f, attackMs = 100, releaseMs = 400)
        assertFalse(d.step(50))
        d.voiceStarted()
        d.step(50); assertEquals(0.7f, d.level, 1e-5f)
        d.step(50); assertEquals(0.4f, d.level, 1e-5f)
        d.step(500); assertEquals(0.4f, d.level, 1e-5f)
        assertEquals(0.7f, d.ambienceLevel, 1e-5f)                 // the ambience dips half as far
        d.voiceStarted(); d.voiceEnded()
        assertEquals(0.4f, d.target)                                // one voice still speaking
        d.voiceEnded()
        d.step(100); assertEquals(0.55f, d.level, 1e-5f)            // back up over 400 ms
        d.step(1000); assertEquals(1f, d.level); assertFalse(d.moving)
        d.voiceEnded(); assertEquals(0, d.voices)                   // never below none
        d.setVoices(3); assertEquals(0.4f, d.target)
    }

    // ---- the crossfade ----

    @Test fun crossfadeFadesTheOldDeckOutAndTheNewIn() {
        val x = Crossfader<Int>()
        val a = x.play(1, fadeInMs = 100)
        x.step(50); assertEquals(0.5f, a.level, 1e-5f); assertTrue(x.busy)
        x.step(60); assertEquals(1f, a.level); assertFalse(x.busy)
        val b = x.play(2, fadeInMs = 200, fadeOutMs = 100)
        assertEquals(b, x.current)
        assertTrue(a.fadingOut)
        assertTrue(x.step(50).isEmpty())
        assertEquals(0.5f, a.level, 1e-5f); assertEquals(0.25f, b.level, 1e-5f)
        val gone = x.step(50)
        assertEquals(listOf(a.id), gone.map { it.id })              // released once silent
        assertEquals(listOf(b.id), x.decks.map { it.id })
        x.step(100); assertEquals(1f, b.level)
    }

    @Test fun crossfadeStopEndAndInstantFades() {
        val x = Crossfader<Int>()
        val a = x.play(1, fadeInMs = 0)
        assertEquals(1f, a.level); assertFalse(x.busy)
        x.stop(0)
        assertNull(x.current)
        assertEquals(listOf(a.id), x.step(1).map { it.id })
        val b = x.play(2, 100)
        assertEquals(b.id, x.end(b.id)?.id)                         // finished by itself
        assertTrue(x.decks.isEmpty()); assertNull(x.end(99))
        x.play(3, 100); x.play(4, 100)
        assertEquals(2, x.clear().size); assertTrue(x.decks.isEmpty())
    }

    @Test fun aQuickSecondChangeLeavesOnlyTheNewestPlaying() {
        val x = Crossfader<Int>()
        x.play(1, 100); x.step(100)
        x.play(2, 100); x.step(30)
        val c = x.play(3, 100)
        x.step(1000)
        assertEquals(listOf(c.id), x.decks.map { it.id })
    }

    // ---- which music ----

    @Test fun placeMusicGapIsCutFromTheTablesMinutes() {
        val r = MusicPicker.gap(180_000 to 300_000)
        assertEquals(MusicPicker.MAX_GAP_MS..MusicPicker.MAX_GAP_MS, r)
        assertEquals(MusicPicker.MIN_GAP_MS..MusicPicker.MIN_GAP_MS, MusicPicker.gap(0 to 0))
        assertEquals(6_000L..9_000L, MusicPicker.gap(6_000 to 9_000))
        assertEquals(9_000L..9_000L, MusicPicker.gap(9_000 to 2_000))     // never backwards
        repeat(20) { val g = MusicPicker.gapMs(6_000L..9_000L, it.toLong()); assertTrue(g in 6_000L..9_000L) }
    }

    @Test fun orderNeverRepeatsATrackBackToBack() {
        val tracks = listOf(1, 2, 3, 4, 5, 6)
        for (seed in 0L until 40L) {
            val played = (0 until 60).map { MusicPicker.at(tracks, seed, it)!! }
            for (i in 1 until played.size) assertNotEquals(played[i - 1], played[i], "seed $seed at $i")
            for (round in 0 until 10) assertEquals(tracks.toSet(), played.subList(round * 6, round * 6 + 6).toSet())
        }
        assertEquals(listOf(7), MusicPicker.order(listOf(7), 1, 3))
        assertNull(MusicPicker.at(emptyList(), 1, 0))
        assertEquals(MusicPicker.at(tracks, 9, 13), MusicPicker.at(tracks, 9, 13))   // the same seed, the same play
    }

    @Test fun theEmbassyPlaysItsRoomsZoneMusicAndAmbienceByDayAndNight() {
        val b = book()
        val p = assertNotNull(b.places["embassy"])
        assertEquals(9171, p.areaId); assertEquals(1929, p.zoneMusic)
        val day = assertNotNull(MusicPicker.place(p, night = false))
        val night = assertNotNull(MusicPicker.place(p, night = true))
        // ZoneMusic 1929 (Zone_735_AlliedRace_EmbassyAlliance) -> kit 97594's six files; SoundAmbience 569 -> 17895 / 17896
        assertEquals(listOf(53205, 53206, 53208, 441767, 1417244, 1417249), day.tracks.sorted())
        assertEquals(listOf(539127), day.ambience)
        assertEquals(listOf(539080), night.ambience)
        assertNotEquals(day.key, night.key)
        assertFalse(day.loop)
        assertEquals(MusicPicker.MAX_GAP_MS, day.gapMs.first)
    }

    @Test fun aPlaceWithoutSoundGivesNoBedAndOneSideFallsBackToTheOther() {
        val none = PlaceSound("x", 1, 0, emptyList(), emptyList(), emptyList(), 0 to 0)
        assertNull(MusicPicker.place(none, false))
        val dayOnly = none.copy(ambienceDay = listOf(5))
        assertEquals(listOf(5), MusicPicker.place(dayOnly, night = true)!!.ambience)
    }

    @Test fun titleMusicIsTheWarWithinsMainTitleLooped() {
        val t = MusicPicker.title(book())
        assertEquals("title", t.key)
        assertTrue(t.loop)
        assertEquals(listOf(6075186), t.tracks)    // MUS_110_MAIN_TITLE (kit 262929): mus_110_thewarwithin_maintitle.mp3
        assertTrue(t.ambience.isEmpty())
    }

    // ---- retries ----

    @Test fun retriesBackOffAndLevelOff() {
        assertEquals(0L, RetryPolicy.delayMs(0))
        val d = (1..8).map { RetryPolicy.delayMs(it) }
        for (i in 1 until d.size) assertTrue(d[i] >= d[i - 1])
        assertEquals(120_000L, d.last())
    }

    // ---- the interface's sounds ----

    @Test fun everyUiSoundNameHasItsKitsFilesInSoundsJson() {
        val b = book()
        // the files SoundKitEntry gives each kit (wago.tools' 12.1.7 tables, checked when the table was made: tools/sounds.py)
        val expected = mapOf(
            "open" to listOf(567490), "close" to listOf(567464), "click" to listOf(567481), "ok" to listOf(567407),
            "check_on" to listOf(567407), "check_off" to listOf(567407), "tab" to listOf(567422),
            "page" to listOf(567457, 567472, 567502), "select" to listOf(567508), "create" to listOf(567437),
            "begin" to listOf(567419), "gossip" to listOf(567504), "ending" to listOf(569143),
        )
        assertEquals(UiSounds.KITS.keys, b.ui.keys)
        for ((name, files) in expected) assertEquals(files, b.ui[name]?.sorted(), name)
        assertEquals(852, UiSounds.KITS.getValue("click").second)  // IG_MAINMENU_OPTION, not the checkbox's 857
        assertEquals(850, UiSounds.KITS.getValue("open").second)
        assertEquals(851, UiSounds.KITS.getValue("close").second)
    }

    @Test fun unknownUiNamesFallBackOrStaySilent() {
        val have = setOf("click", "open")
        assertEquals("open", UiSounds.resolve("open", have))
        assertEquals("click", UiSounds.resolve("tab", have))       // a kit not fetched yet: the plain click
        assertNull(UiSounds.resolve("hover", have))                // the game's buttons have none
        assertNull(UiSounds.resolve("nonsense", have))
        assertNull(UiSounds.resolve("tab", emptySet()))
    }

    @Test fun soundBookReadsEveryShortSound() {
        val b = book()
        assertTrue(b.shortFiles.containsAll(b.ui.values.flatten()))
        assertTrue(b.music.isNotEmpty())
        val parsed = SoundBook.parse("""{"ui":{"click":[1]}}""")
        assertTrue(parsed.places.isEmpty()); assertTrue(parsed.title.isEmpty()); assertEquals(setOf(1), parsed.shortFiles)
    }
}
