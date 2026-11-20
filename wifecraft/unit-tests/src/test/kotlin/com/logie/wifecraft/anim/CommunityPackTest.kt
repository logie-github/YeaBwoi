package com.logie.wifecraft.anim

import com.logie.wifecraft.sims.AnimTuning
import com.logie.wifecraft.sims.Channel
import com.logie.wifecraft.sims.ClipDecoder
import com.logie.wifecraft.sims.SimsClip
import com.logie.wifecraft.sims.SimsFixtures
import com.logie.wifecraft.sims.Stbl
import com.logie.wifecraft.story.Vec3
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import java.nio.file.Files
import java.time.Instant
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.math.PI
import kotlin.test.*

/**
 * The community-pack install list and the older generation of WickedWhims pack tuning (synthetic data only: markup and
 * tuning in the shapes the real pages and packs use, no pack content).
 */
class CommunityPackTest {
    // ---- the download links ----

    /** A pack page's download cards, in the markup wicked.cc's pack pages use (names and ids made up). */
    private fun card(name: String, size: String, href: String) = """
        <div class="file-card">
            <div class="file-card-info">
                <div class="file-card-name">
                    $name
                </div>
                <div class="file-card-meta">
                    <span>$size</span>
                </div>
            </div>
            <div class="file-card-actions">
                <a href="$href" class="download-btn">
                    <svg viewBox="0 0 24 24"><path d="M12 15V3"/></svg>                                        Download
                </a>
            </div>
        </div>"""

    private val page = URL("https://wicked.cc/animations/someone/some-pack")

    @Test fun aPagesFileCardsAreReadInOrderAndOnlyItsOwnLinksKept() {
        val html = """<html><body><a href="https://wicked.cc/mod-installation" class="mod-install-banner">guide</a>
            <div class="downloads-section"><div class="downloads-list">""" +
            card("Pack_Gold_Normal.zip", "60.51 MB", "https://wicked.cc/animations/someone/some-pack/download/DxYZMAFvQ1") +
            card("Pack_Gold_Rough.zip", "60.54 MB", "/animations/someone/some-pack/download/FN37aRkJt2") +
            card("Elsewhere.zip", "1 MB", "https://example.com/animations/someone/some-pack/download/Abcdef") +
            card("Other_Pack.zip", "1 MB", "https://wicked.cc/animations/someone/other-pack/download/Abcdef") +
            card("Script.zip", "1 MB", "https://wicked.cc/animations/someone/some-pack/download/../../x") +
            card("Pack &amp; More.package", "1.00 MB", "https://wicked.cc/animations/someone/some-pack/download/LrEarAENf3") +
            "</div></div></body></html>"
        val files = PackLinks.wickedFiles(html, page)
        assertEquals(listOf("Pack_Gold_Normal.zip", "Pack_Gold_Rough.zip", "Pack & More.package"), files.map { it.name })
        assertEquals(listOf("60.51 MB", "60.54 MB", "1.00 MB"), files.map { it.size })
        assertEquals("https://wicked.cc/animations/someone/some-pack/download/FN37aRkJt2", files[1].url)
        // several files: the person chooses; one: it is installed straight away
        assertNull(PackLinks.single(files))
        val one = PackLinks.wickedFiles(card("Only.package", "1.00 MB", "${page}/download/LrEarAENfw"), page)
        assertEquals("Only.package", PackLinks.single(one)?.name)
        assertTrue(PackLinks.wickedFiles("<html>no downloads</html>", page).isEmpty())
    }

    @Test fun onlyTheRulesHostsOverHttpsAreAllowed() {
        val wicked = PackRule(PackLinks.WICKED, listOf("wicked.cc"), listOf("files.wicked.cc"))
        assertTrue(wicked.pageAllowed(URL("https://wicked.cc/animations/a/b")))
        assertTrue(wicked.fileAllowed(URL("https://files.wicked.cc/file/wickedcc/Pack.zip")))
        assertFalse(wicked.pageAllowed(URL("https://files.wicked.cc/file/wickedcc/Pack.zip")))
        for (bad in listOf("http://wicked.cc/a", "https://wicked.cc:8443/a", "https://user@wicked.cc/a", "https://evilwicked.cc/a",
            "https://wicked.cc.example.com/a", "https://files.wicked.cc.example.com/a")) {
            assertFalse(wicked.fileAllowed(URL(bad)), bad)
        }
        val itch = PackRule(PackLinks.ITCH, listOf("turbodriver.itch.io"), listOf("*.itch.zone", "itchio-mirror.*.r2.cloudflarestorage.com"))
        assertTrue(itch.fileAllowed(URL("https://w3g3a5v6.ssl.hwcdn.itch.zone/x.zip")))
        assertTrue(itch.fileAllowed(URL("https://itchio-mirror.abc123.r2.cloudflarestorage.com/x.zip")))
        assertFalse(itch.fileAllowed(URL("https://itch.zone.example.com/x.zip")))
        assertFalse(itch.fileAllowed(URL("https://itchio-mirror.r2.cloudflarestorage.com.example.com/x")))
    }

    @Test fun theInstallListNamesOnlyPagesOnItsRulesHosts() {
        val json = File(System.getProperty("wifecraft.assets"), PackCatalog.ASSET).readText()
        val packs = PackCatalog.parse(json)
        assertEquals("wickedwhims", packs.first().id)
        assertTrue(packs.first().isWickedWhims)
        assertTrue(packs.size >= 4)
        assertEquals(packs.size, packs.map { it.id }.distinct().size)
        for (p in packs) {
            assertTrue(p.page.startsWith("https://"), p.page)
            assertTrue(p.rule.pageAllowed(URL(p.page)), p.page)
            assertTrue(p.name.isNotBlank() && p.author.isNotBlank())
        }
        assertEquals(3, packs.count { it.rule.kind == PackLinks.WICKED })
        // a pack whose page is on another site is refused when the list is read
        assertFailsWith<IllegalArgumentException> {
            PackCatalog.parse("""{"rules":{"w":{"kind":"wicked","pageHosts":["wicked.cc"]}},
                "packs":[{"id":"x","name":"X","page":"https://example.com/x","rule":"w"}]}""")
        }
    }

    @Test fun itchUploadsCookiesAndArchiveKinds() {
        assertEquals("123", PackLinks.itchUpload("""<a data-upload_id="123" class="button download_btn">"""))
        assertNull(PackLinks.itchUpload("""<a data-upload_id="1" class="download_btn"><a data-upload_id="2" class="download_btn">"""))
        assertEquals("a=1; b=2", PackLinks.cookies(listOf("a=1; path=/; secure", "b=2; HttpOnly", "junk")))
        assertTrue(PackDownload.isArchive(byteArrayOf(0x50, 0x4B, 3, 4, 0, 0)))
        assertTrue(PackDownload.isArchive("DBPF\u0002\u0000".toByteArray()))
        assertTrue(PackDownload.isArchive(byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C)))
        assertFalse(PackDownload.isArchive("<!DOCTYPE html>".toByteArray()))
        assertFalse(PackDownload.isArchive(byteArrayOf(0x50, 0x4B), 2))
    }

    @Test fun readsRetryAfterSecondsAndHttpDates() {
        val now = Instant.parse("2015-10-21T07:27:00Z").toEpochMilli()
        assertEquals(12_000L, PackRetry.retryAfter(" 12 ", now))
        assertEquals(60_000L, PackRetry.retryAfter("Wed, 21 Oct 2015 07:28:00 GMT", now))
        assertEquals(0L, PackRetry.retryAfter("Wed, 21 Oct 2015 07:26:00 GMT", now))
        assertEquals(null, PackRetry.retryAfter("later", now))
    }

    @Test fun waitsBeforeRetryingAndKeepsTheCooldownAcrossInstallAttempts() {
        var time = 1000L
        var requests = 0
        val waits = mutableListOf<Long>()
        val retry = PackRetry { time }
        val wait: suspend (Long) -> Unit = { waits += it; time += it }
        assertFailsWith<PackRateLimited> {
            synchronously {
                retry.run(wait) {
                    requests++
                    throw PackRateLimited(if (requests == 2) 40_000L else null)
                }
            }
        }
        assertEquals(3, requests)
        assertEquals(listOf(15_000L, 40_000L), waits)
        assertEquals("archive", synchronously { retry.run(wait) { "archive" } })
        assertEquals(listOf(15_000L, 40_000L, 60_000L), waits)
    }

    private fun <T> synchronously(block: suspend () -> T): T {
        var completion: Result<T>? = null
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) { completion = result }
        })
        return checkNotNull(completion).getOrThrow()
    }

    // ---- the older tuning generation, string tables, and what is left out ----

    /** A string table (STBL v5) of [strings]. */
    private fun stbl(strings: Map<Int, String>): ByteArray {
        val b = SimsFixtures.Buf().bytes("STBL".toByteArray()).u16(5).u8(0).u32(strings.size).u32(0).u16(0)
        b.u32(strings.values.sumOf { it.toByteArray().size + 1 })
        for ((k, v) in strings) { val s = v.toByteArray(); b.u32(k).u8(0).u16(s.size).bytes(s) }
        return b.get()
    }

    /** Older-generation tuning: lists as <T>, the clip as animation_name, the turn as animation_facing_offset (degrees). */
    private fun olderTuning() = """<?xml version="1.0" encoding="UTF-8"?>
<I c="WickedWhimsAnimationPackage" i="snippet" m="wickedwhims.sex.animations.animations_tuning" n="Test:Pack" s="1">
  <T n="wickedwhims_animations">1</T>
  <L n="animations_list">
    <T>
      <T n="animation_display_name">0x0000BEEF</T>
      <T n="animation_stage_name">dance1</T>
      <T n="animation_next_stages">dance2</T>
      <T n="animation_locations">SOFA, LOVESEAT</T>
      <T n="animation_loops">15</T>
      <T n="object_animation_clip_name">test:sofa_part</T>
      <T n="animation_actors_list">
        <T><T n="actor_id">1</T><T n="animation_name">test:pair_y</T><T n="animation_y_offset">0.5</T><T n="animation_facing_offset">90</T></T>
        <T><T n="actor_id">0</T><T n="animation_name">test:pair_x</T></T>
      </T>
    </T>
    <T>
      <T n="animation_raw_display_name">Group</T>
      <T n="animation_actors_list">
        <T><T n="actor_id">0</T><T n="animation_clip_name">test:group_a</T></T>
        <T><T n="actor_id">1</T><T n="animation_clip_name">test:group_b</T></T>
        <T><T n="actor_id">2</T><T n="animation_clip_name">test:pair_x</T></T>
      </T>
    </T>
  </L>
</I>"""

    @Test fun theOlderFieldNamesTListsAndStringTableNamesAreRead() {
        val sources = mapOf("test:pair_x" to "a", "test:pair_y" to "b", "test:group_a" to "g")
        val read = ImportedPerformances.read(olderTuning(), "r", sources, mapOf(0xBEEF to "Slow Dance"))
        // the three-person entry is left out; the two-person one is read in actor order, named from the string table
        val p = read.single()
        assertEquals("Slow Dance", p.name)
        assertEquals(listOf("a", "b"), p.roles.map { it.source })
        assertEquals(0.5f, p.roles[1].offset.z)
        assertEquals((PI / 2).toFloat(), p.roles[1].facing, 1e-6f)
        assertEquals("sofa, loveseat", p.setting)
        assertEquals(15, p.repetitions)
        assertEquals(listOf("dance2"), p.nextStages)
        // no table: the first clip's name
        assertEquals("pair x", ImportedPerformances.read(olderTuning(), "r", sources).single().name)
        // pairing's own reading of the tuning takes the same fields
        val def = AnimTuning.parse(olderTuning()).first()
        assertEquals(listOf("test:pair_x", "test:pair_y"), def.actors.map { it.clipName })
        assertEquals(90f, def.actors[1].angle)
        // what is left out: the sofa's own clip and the clips only the group plays (pair_x is also in a pair: kept)
        val use = ImportedPerformances.clipUse(olderTuning())
        assertEquals(mapOf("test:sofa_part" to ImportedPerformances.OBJECT_CLIP, "test:group_a" to ImportedPerformances.GROUP_CLIP,
            "test:group_b" to ImportedPerformances.GROUP_CLIP), use.leftOut())
    }

    @Test fun aPosePacksPosesAreNamedFromItsStringTable() {
        val xml = """<?xml version="1.0" encoding="utf-8"?><I s="1" n="Test:PosePack_1" c="PosePackInstance" i="snippet" m="poseplayer">
            <T n="display_name">0x00000001</T><L n="pose_list">
            <U><T n="pose_name">Test:PosePack_1_set_1</T><T n="pose_display_name">0x00000010</T></U>
            <U><T n="pose_name">Test:PosePack_1_set_2</T><T n="pose_display_name">0x00000011</T></U>
            <U><T n="pose_name">Test:PosePack_1_set_3</T><T n="pose_display_name">0x00000099</T></U>
            </L></I>"""
        val poses = ImportedPerformances.read(xml, "r", mapOf("test:posepack_1_set_1" to "a", "test:posepack_1_set_2" to "b",
            "test:posepack_1_set_3" to "c"), mapOf(0x10 to "Pose 1 Male", 0x11 to "Pose 1 Female"))
        assertEquals(listOf("Pose 1 Male" to "a", "Pose 1 Female" to "b"), poses.map { it.name to it.roles.single().source })
    }

    @Test fun stringTablesParseAndLookUpHashes() {
        val table = Stbl.parse(stbl(mapOf(0x0CBDA31C to "Sofa Handjob", 0x10 to "Pose")))
        assertEquals("Sofa Handjob", Stbl.lookup("0x0CBDA31C", table))
        assertEquals("Pose", Stbl.lookup(" 0x10 ", table))
        assertNull(Stbl.lookup("0x00000000", table))
        assertNull(Stbl.lookup("Plain name", table))
        assertTrue(Stbl.parse("STBL".toByteArray()).isEmpty())
        assertTrue(Stbl.parse(ByteArray(40)).isEmpty())
    }

    @Test fun aPackageLeavesOutObjectAndGroupClipsAndNamesItsPairFromTheStringTable() {
        fun clip(name: String) = SimsFixtures.actorClip(name, "x", floatArrayOf(0f, 0.9f, 0f), floatArrayOf(0f, 0.9f, 0f), SimsFixtures.IDENTITY)
        val bytes = SimsFixtures.dbpf(
            SimsFixtures.Res(ClipDecoder.TYPE, 1, clip("test:pair_x")),
            SimsFixtures.Res(ClipDecoder.TYPE, 2, clip("test:pair_y")),
            SimsFixtures.Res(ClipDecoder.TYPE, 3, clip("test:sofa_part")),
            SimsFixtures.Res(ClipDecoder.TYPE, 4, clip("test:group_a")),
            SimsFixtures.Res(ClipDecoder.TYPE, 5, clip("test:group_b")),
            SimsFixtures.Res(AnimTuning.TYPE, 6, olderTuning().toByteArray()),
            SimsFixtures.Res(Stbl.TYPE, 7, stbl(mapOf(0xBEEF to "Slow Dance"))),
        )
        val fake = object : Retarget {
            override fun convert(clip: SimsClip): Anim =
                Anim("", clip.name, "", clip.duration, false, listOf(BoneTrack("Root", Channel.POSITION, floatArrayOf(0f), floatArrayOf(0f, 0f, 0f))), "", 0)
        }
        val tmp = Files.createTempDirectory("packs").toFile()
        val events = ArrayList<ImportEvent>()
        val result = ImportPipeline(fake, tmp).runAll(listOf(ImportInput("pack.package") { bytes.inputStream() })) { events += it }
        val skipped = events.filterIsInstance<ImportEvent.Skipped>().associate { it.name.substringAfter(":: ") to it.why }
        assertEquals(mapOf("test:sofa_part" to ImportedPerformances.OBJECT_CLIP, "test:group_a" to ImportedPerformances.GROUP_CLIP,
            "test:group_b" to ImportedPerformances.GROUP_CLIP), skipped)
        assertEquals(setOf("test:pair_x", "test:pair_y"), result.anims.map { it.name }.toSet())
        assertTrue(events.none { it is ImportEvent.Failed })
        val choice = animationChoices(result.anims.map { CheckedAnimation(it, ClipInspection(ClipType.LOOP)) }).single()
        assertEquals("Slow Dance", choice.name.substringAfterLast(" · "))
        assertEquals(2, choice.participantCount)
        assertEquals(0, tmp.listFiles()!!.size)
    }

    // ---- stage chains named freely ----

    private fun staged(stage: String, next: String?, source: String) =
        ImportedPerformance("res:$stage", stage.substringAfterLast(' '), false,
            listOf(PerformanceRole(source + "a", Vec3(0f, 0f, 0f), 0f), PerformanceRole(source + "b", Vec3(0f, 0f, 0f), 0f)),
            stage = stage, nextStages = listOfNotNull(next))

    private fun choicesOf(performances: List<ImportedPerformance>): List<AnimationChoice> =
        performances.flatMap { p -> p.roles.map { it.source to p } }.map { (source, p) ->
            CheckedAnimation(Anim(source, source, "", 1f, false, emptyList(), source, 0L,
                performances = if (source.endsWith("a")) listOf(p) else emptyList()), ClipInspection(ClipType.LOOP))
        }.let(::animationChoices)

    @Test fun freelyNamedStagesAreGroupedByTheirLinksInPlayOrder() {
        val morning = listOf(staged("morning3", null, "m3"), staged("morning1", "morning2", "m1"), staged("morning2", "morning3", "m2"))
        val azmodan = listOf(
            staged("Author:Bed-Doggy V1 Stage 1", "Author:Bed-Doggy V1 Stage 2", "z1"),
            staged("Author:Bed-Doggy V1 Stage 2", "Author:Bed-Doggy V1 Climax", "z2"),
            staged("Author:Bed-Doggy V1 Climax", null, "z3"),
            staged("Author:Lone Stage", null, "z4"),
        )
        val choices = choicesOf(morning + azmodan)
        val m = choices.filter { it.group == "Morning sequence" }
        assertEquals(listOf("res:morning1", "res:morning2", "res:morning3"), m.map { it.id })
        assertEquals(listOf("Stage 1", "Stage 2", "Stage 3"), m.map { it.name.substringBefore(" · ") })
        val z = choices.filter { it.group == "Bed-Doggy V1 sequence" }
        assertEquals(listOf("res:Author:Bed-Doggy V1 Stage 1", "res:Author:Bed-Doggy V1 Stage 2", "res:Author:Bed-Doggy V1 Climax"), z.map { it.id })
        assertEquals(z, animationSequence(z.first(), choices))
        // an entry in no chain keeps its own stage name, the author's prefix left out
        assertEquals("Lone Stage · Stage", choices.single { it.id == "res:Author:Lone Stage" }.name)
    }
}
