package com.logie.wifecraft.audio

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PartnerSoundsTest {
    private fun dir(): File = Files.createTempDirectory("partner").toFile().also { it.deleteOnExit() }.let { File(it, "partner-sounds") }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArrayInputStream {
        val o = ByteArrayOutputStream()
        ZipOutputStream(o).use { z -> for ((n, b) in entries) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        return ByteArrayInputStream(o.toByteArray())
    }

    private fun PartnerSounds.put(name: String, size: Int = 8) { addFile(name, ByteArrayInputStream(ByteArray(size) { 1 })) }
    private fun PartnerSounds.names(event: String) = resolve(event).map { it.name }.sorted()

    @Test fun fnvVectors() {
        assertEquals(0x811c9dc5u, SoundNames.fnv32(""))
        assertEquals(0xe40c292cu, SoundNames.fnv32("a"))
        assertEquals(0xcbf29ce484222325uL, SoundNames.fnv64(""))
        assertEquals(0xaf63dc4c8601ec8cuL, SoundNames.fnv64("a"))
        assertEquals("e40c292c", SoundNames.hex32(SoundNames.fnv32("a")))
        assertEquals("af63dc4c8601ec8c", SoundNames.hex64(SoundNames.fnv64("a")))
        assertEquals(SoundNames.fnv32("a") to SoundNames.fnv64("a"), SoundNames.hashOf(" A "))
    }

    @Test fun namesAndCandidates() {
        assertEquals("vo_cas_trait_artlover", SoundNames.normalise("C:\\x/Vo_CAS_Trait_ArtLover.OGG"))
        assertEquals("a_b_c", SoundNames.normalise("a b.c"))
        assertEquals(listOf("sfx_vo_a", "vo_a", "a"), SoundNames.candidates("SFX_vo_a"))
        assertEquals(listOf("vo_"), SoundNames.candidates("vo_"))
        assertEquals("name" to 2, SoundNames.variantOf("name_02"))
        assertEquals(null, SoundNames.variantOf("name"))
        assertEquals("ogg", SoundNames.format("X.OGG")); assertEquals(null, SoundNames.format("x.txt")); assertEquals(null, SoundNames.format("ogg"))
        assertEquals(0xe40c292cuL to 32, SoundNames.hashFileName("0xE40C292C"))
        assertEquals(null, SoundNames.hashFileName("e40c29"))
        assertEquals(null, SoundNames.hashFileName("zzzzzzzz"))
    }

    @Test fun chooseIsStableAndSpreads() {
        val files = listOf("c", "a", "b")
        assertEquals(SoundNames.choose(files, 5) { it }, SoundNames.choose(files, 5) { it })
        assertEquals(null, SoundNames.choose(emptyList<String>(), 1) { it })
        val picks = (1..60).map { SoundNames.choose(files, SoundNames.seed("x", "k", it)) { f -> f } }.toSet()
        assertEquals(setOf("a", "b", "c"), picks)
        assertNotEquals(SoundNames.seed("x", "k", 1), SoundNames.seed("x", "k", 2))
    }

    @Test fun resolutionOrder() {
        val p = PartnerSounds(dir())
        assertEquals(emptyList(), p.resolve("anything"))
        p.put("default.ogg")
        assertEquals(listOf("default.ogg"), p.names("nothing_matches"))
        p.put("0xE40C292C.wav")
        assertEquals(listOf("0xe40c292c.wav"), p.names("a"), "a hash-named file (0x kept) is found by the sound's 32-bit hash")
        p.put("artlover_01.ogg"); p.put("artlover_02.ogg"); p.put("artlover_03.mp3")
        assertEquals(listOf("artlover_01.ogg", "artlover_02.ogg", "artlover_03.mp3"), p.names("vo_cas_trait_artlover").let { p.names("VO_artlover") })
        p.put("artlover.ogg")
        assertEquals(listOf("artlover.ogg"), p.names("vo_artlover"), "the prefix-less exact name beats its variants")
        p.put("vo_artlover.mp3")
        assertEquals(listOf("vo_artlover.mp3"), p.names("Vo_ArtLover"), "the exact name first")
        assertEquals(listOf("default.ogg"), p.names("zzz"))
    }

    @Test fun hashNamedFile() {
        val p = PartnerSounds(dir())
        val (h32, h64) = SoundNames.hashOf("vo_cas_trait_artlover")
        p.put(SoundNames.hex32(h32) + ".ogg")
        assertEquals(listOf(SoundNames.hex32(h32) + ".ogg"), p.names("VO_CAS_TRAIT_ARTLOVER"))
        val q = PartnerSounds(dir()); q.put("0x" + SoundNames.hex64(h64) + ".wav")
        assertEquals(listOf("0x" + SoundNames.hex64(h64) + ".wav"), q.names("vo_cas_trait_artlover"))
        assertEquals(emptyList(), q.names("another"))
    }

    @Test fun statusAndClear() {
        val p = PartnerSounds(dir())
        assertEquals("0 files, 0 KB", p.status().summary())
        p.put("a.ogg", 1_048_576); p.put("b.wav", 1024)
        val s = p.status()
        assertEquals(2, s.count); assertEquals(listOf("ogg", "wav"), s.formats)
        assertEquals("2 files, 1.0 MB", s.summary())
        p.clear()
        assertEquals(0, p.status().count); assertEquals(emptyList(), p.resolve("a"))
    }

    @Test fun zipImport() {
        val p = PartnerSounds(dir())
        val r = p.addZip(zip("pack/vo_a.ogg" to byteArrayOf(1, 2), "pack/sub/B 1.WAV" to byteArrayOf(3), "readme.txt" to byteArrayOf(4), "__MACOSX/._x.ogg" to byteArrayOf(5)))
        assertEquals(3, r.added); assertEquals(1, r.skipped)
        assertEquals(listOf("b_1.wav", "vo_a.ogg"), p.status().let { (p.names("b_1") + p.names("vo_a")).sorted() })
        assertEquals(listOf("vo_a.ogg"), p.names("vo_a"))
        assertTrue(p.import("more.zip", zip("c.mp3" to byteArrayOf(9))).added == 1 && p.names("c") == listOf("c.mp3"))
        assertEquals(1, p.import("d.flac", ByteArrayInputStream(byteArrayOf(1))).added)
    }

    @Test fun zipRefusals() {
        val p = PartnerSounds(dir())
        p.put("keep.ogg")
        for (bad in listOf("../evil.ogg", "/abs.ogg", "a/../../b.ogg", "C:/x.ogg", "a\\..\\..\\b.ogg"))
            assertFailsWith<PartnerSoundException>(bad) { p.addZip(zip("ok.ogg" to byteArrayOf(1), bad to byteArrayOf(2))) }
        assertFailsWith<PartnerSoundException> { p.addZip(zip("a.txt" to byteArrayOf(1))) }
        assertEquals(listOf("keep.ogg"), (p.dirFiles()), "a refused zip leaves the folder as it was")
        val small = PartnerSounds(dir(), maxEntry = 10, maxTotal = 15)
        assertFailsWith<PartnerSoundException> { small.addZip(zip("a.ogg" to ByteArray(11))) }
        assertFailsWith<PartnerSoundException> { small.addZip(zip("a.ogg" to ByteArray(8), "b.ogg" to ByteArray(8))) }
        assertEquals(0, small.status().count)
        assertFailsWith<PartnerSoundException> { PartnerSounds(dir(), maxEntries = 1).addZip(zip("a.ogg" to byteArrayOf(1), "b.ogg" to byteArrayOf(1))) }
        assertFailsWith<PartnerSoundException> { PartnerSounds(dir(), maxFiles = 1).addZip(zip("a.ogg" to byteArrayOf(1), "b.ogg" to byteArrayOf(1))) }
        assertFailsWith<PartnerSoundException> { p.addFile("notes.txt", ByteArrayInputStream(byteArrayOf(1))) }
        assertFalse(File(p.dirOf(), ".tmp-a").exists())
    }

    @Test fun sinkForwards() {
        val calls = ArrayList<String>()
        val player = object : PartnerSoundPlayer {
            override fun partnerSound(name: String, actorKey: String?, loop: Boolean, volume: Float): Boolean { calls.add("$name/$actorKey/$loop/$volume"); return false }
            override fun stopAll() { calls.add("stop") }
        }
        val sink = SceneAudioPartnerSink(player)
        sink.play("s", "a"); sink.play("t", null, true); sink.stopAll()
        assertEquals(listOf("s/a/false/1.0", "t/null/true/1.0", "stop"), calls)
    }

    private fun PartnerSounds.dirOf(): File = PartnerSounds::class.java.getDeclaredField("dir").also { it.isAccessible = true }.get(this) as File
    private fun PartnerSounds.dirFiles(): List<String> = dirOf().listFiles()!!.map { it.name }.sorted()
}
