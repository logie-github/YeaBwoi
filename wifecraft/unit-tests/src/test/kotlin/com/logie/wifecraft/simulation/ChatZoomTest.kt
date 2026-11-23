package com.logie.wifecraft.simulation

import com.logie.wifecraft.story.Vec3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Chat mode's zoom, orbit, panel framing and the world's chat bubble layout (pure maths). */
class ChatZoomTest {
    private fun body(h: Float = 1.7f, facing: Float = 0f) = JoiBody().also {
        it.set(Vec3(0f, 0f, 1.62f * h / 1.7f), Vec3(0f, 0f, 1.25f * h / 1.7f), Vec3(0f, 0f, 0.95f * h / 1.7f), facing, h, 0f, true)
    }

    private fun run(cam: ChatCamera, b: JoiBody, seconds: Float, aspect: Float = 16f / 9f) {
        var t = 0f; while (t < seconds) { cam.update(1 / 30f, b, aspect); t += 1 / 30f }
    }

    private fun dist(cam: ChatCamera) = hypot(cam.eyeX, cam.eyeY)

    @Test fun distanceGrowsWithTheZoomAndTheDefaultIsTheOldFraming() {
        assertEquals(1.0f, ChatZoom.distance(ChatZoom.DEFAULT), 1e-3f)
        assertEquals(ChatZoom.NEAR, ChatZoom.distance(0f), 1e-4f)
        assertEquals(ChatZoom.FAR, ChatZoom.distance(1f), 1e-3f)
        var last = 0f
        for (i in 0..20) { val d = ChatZoom.distance(i / 20f); assertTrue(d > last); last = d }
        assertTrue(ChatZoom.FAR * 1.7f in 6f..8f, "up to about 6 to 8 yards")
        assertEquals(0.4f, ChatZoom.zoomOf(ChatZoom.distance(0.4f)), 1e-3f)
        assertEquals(listOf("Close", "Medium", "Wide", "Full body"), ChatZoom.PRESETS.map { it.label })
        assertTrue(ChatZoom.PRESETS.zipWithNext().all { (a, b) -> a.zoom < b.zoom })
    }

    @Test fun clampsAndPinchAndDrag() {
        assertEquals(0f, ChatZoom.clamp(-3f)); assertEquals(1f, ChatZoom.clamp(7f)); assertEquals(ChatZoom.DEFAULT, ChatZoom.clamp(Float.NaN))
        assertTrue(ChatZoom.pinch(0.5f, 2f) < 0.5f, "fingers spread: closer")
        assertTrue(ChatZoom.pinch(0.5f, 0.5f) > 0.5f, "fingers together: wider")
        assertEquals(1f, ChatZoom.pinch(0.9f, 0.01f)); assertEquals(0f, ChatZoom.pinch(0.1f, 100f)); assertEquals(0.5f, ChatZoom.pinch(0.5f, 1f), 1e-5f)
        assertEquals(ChatZoom.MAX_ORBIT, ChatZoom.drag(0f, -100000f, 1000f)); assertEquals(-ChatZoom.MAX_ORBIT, ChatZoom.drag(0f, 100000f, 1000f))
        assertEquals(1.0472f, ChatZoom.MAX_ORBIT, 1e-3f)
        assertEquals(0f, ChatZoom.clampOrbit(Float.NaN)); assertEquals(0f, ChatZoom.clampInset(-1f)); assertEquals(ChatZoom.MAX_INSET, ChatZoom.clampInset(2f))
    }

    @Test fun panelInsetLiftsHerOnlyWhenZoomedOutAndGrowsWithTheInset() {
        assertEquals(0f, ChatZoom.panelAim(ChatZoom.DEFAULT, 0.4f, 1f, 34f), 1e-6f)
        assertEquals(0f, ChatZoom.panelAim(0.1f, 0.4f, 1f, 34f), 1e-6f)
        val a = ChatZoom.panelAim(ChatZoom.FULL_BODY, 0.2f, 3f, 34f); val b = ChatZoom.panelAim(ChatZoom.FULL_BODY, 0.4f, 3f, 34f)
        assertTrue(a > 0f && b > a)
        assertEquals(0f, ChatZoom.panelAim(1f, 0f, 3f, 34f))
        assertTrue(ChatZoom.bodyMix(ChatZoom.FULL_BODY) > 0.99f && ChatZoom.bodyMix(ChatZoom.DEFAULT) < 0.01f)
    }

    @Test fun theCameraZoomsOutAndKeepsHerInFrameAtEveryStepAndAspect() {
        for (aspect in listOf(16f / 9f, 9f / 19.5f, 4f / 3f)) for (p in ChatZoom.PRESETS) {
            val b = body(); val cam = ChatCamera(4L); cam.zoom = p.zoom; cam.panelInset = if (aspect < 1f) 0.3f else 0.45f
            run(cam, b, 6f, aspect)
            val eye = Vec3(cam.eyeX, cam.eyeY, cam.eyeZ); val at = Vec3(cam.atX, cam.atY, cam.atZ)
            assertTrue(CinematicFraming.inFrame(eye, at, cam.fov, aspect, ChatZoom.mustShow(b, p.zoom), 0f), "${p.label} at $aspect")
        }
        val close = ChatCamera(4L).also { it.zoom = 0f }; val wide = ChatCamera(4L).also { it.zoom = 1f }
        run(close, body(), 4f); run(wide, body(), 4f)
        assertTrue(dist(wide) > dist(close) * 5f)
        assertTrue(dist(wide) in 6f..9f, "the widest is ${dist(wide)} yards")
    }

    @Test fun zoomingIsSmoothAndRememberedAcrossARestartAndScalesWithTheCharacter() {
        val b = body(); val cam = ChatCamera(2L); run(cam, b, 3f)
        val d0 = dist(cam)
        cam.zoom = 0.7f
        cam.update(1 / 30f, b, 16f / 9f)
        assertTrue(abs(dist(cam) - d0) < 0.6f, "one frame does not jump")
        run(cam, b, 4f); assertTrue(dist(cam) > d0 * 2f)
        cam.restart(); assertEquals(0.7f, cam.zoom, "kept for the session"); assertEquals(0f, cam.orbit, "a fresh turn")
        // a child-sized and a giant body: the distance follows her height
        val small = ChatCamera(2L).also { it.zoom = 0.7f }; val big = ChatCamera(2L).also { it.zoom = 0.7f }
        run(small, body(1.0f), 4f); run(big, body(3.0f), 4f)
        assertEquals(3f, dist(big) / dist(small), 0.3f)
    }

    @Test fun orbitTurnsTheViewRoundHerWithinSixtyDegrees() {
        val b = body(); val cam = ChatCamera(3L); cam.orbit = 5f
        run(cam, b, 4f)
        val a = atan2(cam.eyeY - b.head.y, cam.eyeX - b.head.x)
        assertTrue(abs(a) > 0.9f && abs(a) < ChatZoom.MAX_ORBIT + 0.25f, "turned to the limit: $a")
        cam.orbit = -5f; run(cam, b, 6f)
        assertTrue(atan2(cam.eyeY - b.head.y, cam.eyeX - b.head.x) < -0.9f)
    }

    @Test fun aWallDrawsTheZoomedOutCameraInToo() {
        val b = body(); val cam = ChatCamera(6L); cam.zoom = 0.9f
        cam.visible = { eye, _ -> hypot(eye.x, eye.y) < 3f }
        run(cam, b, 6f)
        assertTrue(dist(cam) < 3.6f, "drawn in: ${dist(cam)}")
    }

    // ---- the chat bubble's layout ----

    @Test fun bubbleWrapsAndSizesWithinLimits() {
        assertEquals(listOf("one two", "three"), SpeechBubbleModel.wrap("one two three", 8))
        assertTrue(SpeechBubbleModel.wrap("a".repeat(30), 10).all { it.length <= 10 })
        val short = SpeechBubbleModel.shape("Well met.")
        assertEquals(1, short.lines.size); assertTrue(short.widthDp < 120f && short.widthDp >= SpeechBubbleModel.MIN_WIDTH_DP)
        val mid = SpeechBubbleModel.shape("The road north is long and the nights are cold, traveller.")
        assertTrue(mid.lines.size in 2..3 && !mid.long && mid.widthDp <= SpeechBubbleModel.MAX_WIDTH_DP)
        val long = SpeechBubbleModel.shape("word ".repeat(60))
        assertTrue(long.long && long.maxLines > mid.maxLines && long.widthDp <= SpeechBubbleModel.MAX_WIDTH_LONG_DP)
    }

    @Test fun bubbleScaleAnchorAndDuration() {
        assertEquals(SpeechBubbleModel.MAX_SCALE, SpeechBubbleModel.scale(0.5f)); assertEquals(SpeechBubbleModel.MIN_SCALE, SpeechBubbleModel.scale(60f))
        assertEquals(1f, SpeechBubbleModel.scale(SpeechBubbleModel.REF_DEPTH), 1e-4f)
        assertTrue(SpeechBubbleModel.scale(4f) > SpeechBubbleModel.scale(8f))
        assertEquals(SpeechBubbleModel.MIN_SCALE, SpeechBubbleModel.scale(Float.NaN))
        val mid = SpeechBubbleModel.place(500f, 100f, 1000f); assertEquals(450f, mid.left, 1e-3f); assertEquals(50f, mid.tailX, 1e-3f)
        val edge = SpeechBubbleModel.place(10f, 100f, 1000f); assertEquals(6f, edge.left, 1e-3f); assertTrue(edge.tailX < 50f && edge.tailX >= 14f, "the tail still aims at the head")
        val right = SpeechBubbleModel.place(995f, 100f, 1000f); assertEquals(894f, right.left, 1e-3f); assertTrue(right.tailX > 50f)
        assertTrue(!SpeechBubbleModel.visible(0f, true) && !SpeechBubbleModel.visible(3f, false) && SpeechBubbleModel.visible(3f, true))
        assertTrue(SpeechBubbleModel.holdSeconds("Hi.") == 3f && SpeechBubbleModel.holdSeconds("x".repeat(100)) > SpeechBubbleModel.holdSeconds("x".repeat(50)))
        assertEquals(16f, SpeechBubbleModel.holdSeconds("x".repeat(2000)))
    }

    @Test fun bubbleKindsAreAColourHook() {
        assertEquals(SpeechBubbleModel.Kind.SAY, SpeechBubbleModel.kind("Well met."))
        assertEquals(SpeechBubbleModel.Kind.EMOTE, SpeechBubbleModel.kind("*laughs*")); assertEquals(SpeechBubbleModel.Kind.EMOTE, SpeechBubbleModel.kind("<Jaina> smiles"))
        assertEquals(SpeechBubbleModel.Kind.YELL, SpeechBubbleModel.kind("GET OUT OF HERE")); assertEquals(SpeechBubbleModel.Kind.YELL, SpeechBubbleModel.kind("No, stop!!"))
        assertEquals(0xFFFFFFFF, SpeechBubbleModel.colour(SpeechBubbleModel.Kind.SAY))
        assertEquals(0xFFFF4040, SpeechBubbleModel.colour(SpeechBubbleModel.Kind.YELL)); assertEquals(0xFFFF8040, SpeechBubbleModel.colour(SpeechBubbleModel.Kind.EMOTE))
        assertEquals(0xFFFF80FF, SpeechBubbleModel.colour(SpeechBubbleModel.Kind.WHISPER)); assertEquals(SpeechBubbleModel.Kind.WHISPER, SpeechBubbleModel.kind("/w psst"))
    }

    @Test fun bubbleLinksAreColouredAndSafe() {
        val p = SpeechBubbleModel.parse("Try [Wrath of Cenarius], then [[q:The Long Road]] and [[g:Boots]].")
        assertEquals("Try [Wrath of Cenarius], then [The Long Road] and [Boots].", p.text)
        assertEquals(listOf(0xFFA335EEL, 0xFFFFD100L, 0xFF1EFF00L), p.spans.map { it.colour })
        assertEquals("[Wrath of Cenarius]", p.text.substring(p.spans[0].start, p.spans[0].end))
        // unknown codes, unbalanced, nested, empty, over-long and multi-line brackets stay plain text
        for (raw in listOf("[[x:Name]]", "an [open", "[[q:a [b] c]]", "[] none", "[" + "x".repeat(61) + "]", "[a\nb]")) {
            assertTrue(SpeechBubbleModel.parse(raw).spans.isEmpty() || raw.startsWith("[[q:a"), raw)
            assertTrue(SpeechBubbleModel.parse(raw).text.length <= raw.length + 2)
        }
        assertEquals("[[x:Name]]", SpeechBubbleModel.parse("[[x:Name]]").text)
        // measuring and timing use the shown text, the markup stripped
        assertEquals(SpeechBubbleModel.shape("Hello [[q:Name]]").widthDp, SpeechBubbleModel.shape("Hello [Name]").widthDp)
        assertTrue(SpeechBubbleModel.shape("[[q:" + "x".repeat(10) + "]]").widthDp < SpeechBubbleModel.shape("x".repeat(20)).widthDp)
        assertEquals(SpeechBubbleModel.holdSeconds("Hello [Name] there you"), SpeechBubbleModel.holdSeconds("Hello [[b:Name]] there you"))
        // the dialogue layer's builder never lets a name break out
        assertEquals("[Boots]", SpeechBubbleModel.link("Bo]ots"))
        assertEquals("[[q:Hello]]", SpeechBubbleModel.link("[Hello", 'q')); assertEquals("[A]", SpeechBubbleModel.link("A", 'z')); assertEquals("", SpeechBubbleModel.link("[]"))
    }

    @Test fun bubbleTailSitsLeftOfCentreAndStillAimsAtTheHead() {
        val pl = SpeechBubbleModel.place(500f, 200f, 1000f, tailFrac = SpeechBubbleModel.TAIL_FRAC)
        assertEquals(200f * SpeechBubbleModel.TAIL_FRAC, pl.tailX, 1e-3f); assertTrue(pl.tailX < 100f)
        assertEquals(6f, SpeechBubbleModel.place(10f, 200f, 1000f, tailFrac = SpeechBubbleModel.TAIL_FRAC).left, 1e-3f)
    }
}
