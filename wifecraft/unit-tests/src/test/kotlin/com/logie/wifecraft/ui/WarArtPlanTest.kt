package com.logie.wifecraft.ui

import org.json.JSONObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The editor's build-mode art: every picture the kit's code names is in assets/ui_art.json (a source scan), every one has
 * a glyph to stand in for it, a fetched answer is kept only when it is a BLP2 texture, and the loader never keeps a failure
 * for good (loading, loaded, failed, tried again after a growing wait, Retry).
 */
class WarArtPlanTest {
    private val json = JSONObject(File(System.getProperty("wifecraft.assets"), "ui_art.json").readText())
    private val pieces: Set<String> = json.getJSONObject("pieces").keySet()
    private val icons: Set<String> = json.getJSONObject("icons").keySet()

    /** The ui files that draw with the kit (they read LocalWar, build a Face or name WarIcons), the character creator's left out. */
    private fun kitSources(): Map<String, String> {
        val dir = File(System.getProperty("wifecraft.ui"))
        return dir.listFiles()!!.filter { it.name.endsWith(".kt") && !it.name.startsWith("Creator") }
            .associate { it.name to it.readText() }
            .filter { (_, s) -> "LocalWar" in s || "Face(" in s || "WarIcons" in s || "WarSlices" in s || "WarGlyphs" in s }
    }

    private val iconKey = Regex("\"((?:tab|cat|tool|emote|menu)\\.[a-z][a-z \\-]*)\"")
    private val pieceName = Regex("\"([!_a-z][a-z0-9_!]*(?:-[a-z0-9_!]*)+)\"")
    private val notArt = setOf("war-art")

    @Test fun everyPictureTheKitNamesIsInTheJson() {
        val src = kitSources()
        assertTrue(src.keys.containsAll(listOf("WarWidgets.kt", "WarKit.kt", "EditorPlacement.kt", "PlaceLibrarySheet.kt", "EditorHome.kt")), "${src.keys}")
        val missing = ArrayList<String>()
        for ((f, s) in src) {
            for (m in iconKey.findAll(s)) if (m.groupValues[1] !in icons) missing += "$f: icon ${m.groupValues[1]}"
            for (m in pieceName.findAll(s)) {
                val n = m.groupValues[1]
                if (n !in pieces && n !in notArt) missing += "$f: piece $n"
            }
        }
        assertEquals(emptyList(), missing, "named in the code but not in ui_art.json (they would draw as glyphs for ever)")
    }

    @Test fun theDynamicKeysAreInTheJsonToo() {
        for (t in LibraryTab.values()) assertTrue(WarIcons.tab(t) in icons, WarIcons.tab(t))
        for (c in WarIcons.CATEGORIES) assertTrue(c in icons, c)
        for (g in listOf("Emotes", "Dance", "Sit, sleep and kneel", "Work and life", "Combat", "Spells", "Anything else")) {
            assertTrue(WarIcons.emoteGroup(g) in icons, g)
        }
        for (p in listOf(WarIcons.BACK, WarIcons.PLAY, WarIcons.UNDO, WarIcons.REDO, WarIcons.DELETE, WarIcons.COPY, WarIcons.CHECK,
            WarIcons.PLUS, WarIcons.MINUS, WarIcons.CLOSE, WarIcons.SPEAK, WarIcons.SOUND, WarIcons.ZOOM, WarIcons.CAMERA,
            WarIcons.LOCK, WarIcons.STAR, WarIcons.STAR_OFF, WarIcons.SEARCH)) assertTrue(p in pieces, p)
        for (n in WarSlices.INSETS.keys) assertTrue(n in pieces, n)
    }

    @Test fun whatTheJsonListsButTheKitDoesNotUseIsKnown() {
        // not a failure: the pieces fetched with a sheet the kit needs anyway, kept for what comes next
        val text = kitSources().filterKeys { it != "WarArtPlan.kt" }.values.joinToString("\n")   // the glyph table names them all
        val unusedIcons = icons.filter { "\"$it\"" !in text && !(it.startsWith("cat.") && it in WarIcons.CATEGORIES) }.sorted()
        assertEquals(listOf("tool.lock", "tool.tips"), unusedIcons)
    }

    @Test fun everyPictureHasAGlyph() {
        for (k in icons) assertTrue(k in WarGlyphs.TABLE, "icon $k has no glyph")
        val used = kitSources().values.flatMap { s -> pieceName.findAll(s).map { it.groupValues[1] }.toList() }.toSet()
        // every piece a button shows (glyphs and tool pictures); frames and grounds draw plainly instead
        for (p in pieces.filter { it in used && (it.startsWith("common-icon") || it.startsWith("decor-ability-") || it.startsWith("auctionhouse-icon") ||
                it == WarIcons.CAMERA || it == WarIcons.LOCK || it == WarIcons.SEARCH) }) assertTrue(p in WarGlyphs.TABLE, "piece $p has no glyph")
        assertEquals(WarGlyph.TRASH, WarGlyphs.of(WarIcons.DELETE))
        assertEquals(WarGlyph.HAND, WarGlyphs.of("decor-ability-move-default"))
        assertEquals(WarGlyph.EYE, WarGlyphs.of("tool.hide"))
        assertEquals(WarGlyph.CUBE, WarGlyphs.of(null))
        assertEquals(WarGlyph.PERSON, WarGlyphs.of("emote.something-new"))
        // the spec's glyphs are all there
        for (n in listOf("HAND", "ROTATE", "SCALE", "TRASH", "EYEDROPPER", "COPY", "LOCK", "EYE", "SPEECH", "CROSSHAIR", "STAR", "SEARCH",
            "UNDO", "REDO", "CAMERA", "PLAY", "BACK", "PLUS", "MINUS", "CUBE", "HOUSE", "PAW", "PERSON", "HEART", "CLOCK", "COG")) WarGlyph.valueOf(n)
    }

    @Test fun anAnswerIsKeptOnlyWhenItIsABlp() {
        val blp = "BLP2".toByteArray()
        assertNull(ArtCheck.problem(200, "application/octet-stream", 3916, blp))
        assertEquals("HTTP 429", ArtCheck.problem(429, null, 0, ByteArray(0)))
        assertEquals("HTTP 404", ArtCheck.problem(404, "text/html", 10, blp))
        assertNotNull(ArtCheck.problem(200, "application/json; charset=utf-8", 30, "{\"e".toByteArray()))
        assertEquals("came empty", ArtCheck.problem(200, "application/octet-stream", 0, ByteArray(0)))
        assertEquals("not a BLP2 texture", ArtCheck.problem(200, "application/octet-stream", 30, "<htm".toByteArray()))
        assertEquals("not a BLP2 texture", ArtCheck.problem(200, null, 3, "BLP".toByteArray()))
    }

    @Test fun theBackoffGrowsAndStops() {
        assertEquals(5_000L, ArtBackoff.delayMs(1))
        assertEquals(15_000L, ArtBackoff.delayMs(2))
        assertEquals(45_000L, ArtBackoff.delayMs(3))
        assertEquals(ArtBackoff.MAX_MS, ArtBackoff.delayMs(20))
        assertEquals(5_000L, ArtBackoff.delayMs(0))
    }

    @Test fun theLoaderLoadsFailsTriesAgainAndRetries() {
        val b = ArtLoadBook(listOf(1, 2, 3), maxAuto = 3)
        assertEquals(listOf(1, 2, 3), b.due(0))
        assertTrue(b.busy); assertFalse(b.settled)
        for (id in b.due(0)) b.started(id)
        assertEquals(ArtFile.Loading, b.state(1))
        assertEquals(emptyList(), b.due(0), "nothing starts twice")
        b.loaded(1); b.loaded(2)
        b.failed(3, "HTTP 429", now = 1_000)
        assertEquals(2, b.loadedCount)
        assertFalse(b.busy)
        assertFalse(b.settled, "a failure is not final")
        assertEquals(listOf(3 to "HTTP 429"), b.failures())
        assertEquals(6_000L, b.nextRetryAt())
        assertEquals(emptyList(), b.due(5_999), "it waits")
        assertEquals(listOf(3), b.due(6_000))
        // fails again: a longer wait, the count kept
        b.started(3); b.failed(3, "came empty", now = 6_000)
        assertEquals(2, (b.state(3) as ArtFile.Failed).attempts)
        assertEquals(21_000L, b.nextRetryAt())
        b.started(3); b.failed(3, "came empty", now = 21_000)
        // out of tries by itself: settled, until Retry
        assertEquals(3, (b.state(3) as ArtFile.Failed).attempts)
        assertNull(b.nextRetryAt())
        assertTrue(b.settled)
        assertEquals(emptyList(), b.due(Long.MAX_VALUE))
        assertEquals(1, b.retryFailed())
        assertEquals(listOf(3), b.due(0))
        b.started(3); b.failed(3, "HTTP 503", now = 0)
        assertEquals(1, (b.state(3) as ArtFile.Failed).attempts, "Retry counts from zero")
        b.started(3); b.loaded(3)
        assertEquals(3, b.loadedCount)
        assertTrue(b.settled)
        assertEquals(emptyList(), b.failures())
    }
}
