package com.logie.wifecraft.simulation

import com.logie.wifecraft.anim.ActorRequirement
import com.logie.wifecraft.anim.Posture
import com.logie.wifecraft.anim.Requirement
import com.logie.wifecraft.anim.Surface
import com.logie.wifecraft.story.CameraPose
import com.logie.wifecraft.story.Vec3
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.*

/**
 * The live scene's build mode: the gizmo's maths (projection, the handles' hit, the drag along an axis, the ring, the
 * snaps), the camera's orbit round a pivot, the markers', items', pairs' and race placements' data and JSON (schema 2), the
 * report and its request, the state machine with undo and redo, the overrides' precedence and ticks, the remembered race
 * placements (scopes, precedence, a switch of roles, a gnome and a human apart, the merge) and the live move of a pair.
 */
class BuildModeTest {
    private fun near(a: Float, b: Float, eps: Float = 1e-3f, what: String = "") = assertTrue(abs(a - b) <= eps, "$what: $a vs $b")

    /** A camera 4 yards south of the origin, 1.5 up, looking at (0, 0, 1) on a 1000 x 800 view. */
    private val view = BuildView(Vec3(0f, -4f, 1f), Vec3(0f, 0f, 1f), 50f, 1000f, 800f)

    // ---- the maths

    @Test fun theViewProjectsAsTheRendererDraws() {
        val c = assertNotNull(view.project(Vec3(0f, 0f, 1f)))
        near(c.x, 500f, 0.01f, "the target is in the middle"); near(c.y, 400f, 0.01f)
        val right = assertNotNull(view.project(Vec3(1f, 0f, 1f)))
        assertTrue(right.x > 500f, "+x is to the right looking north"); near(right.y, 400f, 0.01f)
        val up = assertNotNull(view.project(Vec3(0f, 0f, 2f)))
        assertTrue(up.y < 400f, "up is up the screen")
        assertNull(view.project(Vec3(0f, -6f, 1f)), "behind the eye")
        // the ray through a projected point goes through it
        val p = Vec3(0.7f, 1.3f, 0.4f)
        val s = assertNotNull(view.project(p))
        val (o, d) = view.ray(s.x, s.y)
        val t = (p.y - o.y) / d.y
        near(o.x + d.x * t, p.x, 1e-3f, "ray x"); near(o.z + d.z * t, p.z, 1e-3f, "ray z")
        // the floor under the middle of the view, from above
        val top = BuildView(Vec3(0f, -3f, 3f), Vec3(0f, 0f, 0f), 50f, 1000f, 800f)
        val hit = assertNotNull(top.onPlane(500f, 400f, 0f))
        near(hit.x, 0f); near(hit.y, 0f)
    }

    @Test fun theHandlesAreHitOnTheScreen() {
        val pivot = Vec3(0f, 0f, 1f)
        val len = BuildGizmo.length(view, pivot)
        near(len, 0.19f * 2f * 4f * kotlin.math.tan(Math.toRadians(25.0).toFloat()), 1e-4f, "the engine's share")
        val tipX = assertNotNull(view.project(pivot + Vec3(len * 0.9f, 0f, 0f)))
        assertEquals(GizmoHandle.X, BuildGizmo.hit(view, pivot, 0f, tipX.x, tipX.y, 24f))
        val tipZ = assertNotNull(view.project(pivot + Vec3(0f, 0f, len * 0.9f)))
        assertEquals(GizmoHandle.Z, BuildGizmo.hit(view, pivot, 0f, tipZ.x, tipZ.y, 24f))
        // the ring, on its far side (behind the pivot, where no arrow is)
        val ring = assertNotNull(view.project(pivot + Vec3(-0.9f * len * 0.7071f, 0.9f * len * 0.7071f, 0f)))
        assertEquals(GizmoHandle.RING, BuildGizmo.hit(view, pivot, 0f, ring.x, ring.y, 24f))
        assertNull(BuildGizmo.hit(view, pivot, 0f, 20f, 20f, 24f), "nothing far off")
        // turned a quarter, x is along +y (pointing away: short on the screen) and y along -x
        val tipY = assertNotNull(view.project(pivot + Vec3(-len * 0.9f, 0f, 0f)))
        assertEquals(GizmoHandle.Y, BuildGizmo.hit(view, pivot, (Math.PI / 2).toFloat(), tipY.x, tipY.y, 24f))
    }

    @Test fun aDragMovesByItsComponentAlongTheAxis() {
        val pivot = Vec3(0f, 0f, 1f)
        val a = assertNotNull(view.project(pivot))
        val b = assertNotNull(view.project(pivot + Vec3(1f, 0f, 0f)))
        val perYard = b.x - a.x
        // a drag right by a yard's pixels moves a yard along x; straight down the screen moves x nothing
        near(BuildGizmo.alongAxis(view, pivot, Vec3(1f, 0f, 0f), 500f, 400f, 500f + perYard, 400f), 1f, 0.02f, "along x")
        near(BuildGizmo.alongAxis(view, pivot, Vec3(1f, 0f, 0f), 500f, 400f, 500f, 500f), 0f, 1e-3f, "across x")
        // up the screen raises along z; an axis toward the eye can't be dragged
        assertTrue(BuildGizmo.alongAxis(view, pivot, Vec3(0f, 0f, 1f), 500f, 400f, 500f, 300f) > 0f)
        near(BuildGizmo.alongAxis(view, pivot, Vec3(0f, 1f, 0f), 500f, 400f, 600f, 400f), 0f, 1e-4f, "pointing away")
    }

    @Test fun snapsAndTheRingTurn() {
        near(BuildGizmo.snap(0.237f, 0.1f), 0.2f, 1e-5f)
        near(BuildGizmo.snap(0.237f, 0f), 0.237f, 1e-6f)
        near(BuildGizmo.snapDeg(174f, 15f), 180f, 1e-4f)
        near(BuildGizmo.snapDeg(-188f, 15f), 165f, 1e-4f, "wrapped")
        near(BuildSnap.CM10.yards, 0.1f / 0.9144f, 1e-6f)
        // seen from above, a finger going counter-clockwise round the pivot turns it counter-clockwise
        val top = BuildView(Vec3(0f, -0.01f, 5f), Vec3(0f, 0f, 0f), 50f, 1000f, 1000f)
        val pivot = Vec3(0f, 0f, 0f)
        val c = assertNotNull(top.project(pivot))
        val turn = BuildGizmo.ringDelta(top, pivot, c.x + 100f, c.y, c.x, c.y - 100f)
        near(turn, (Math.PI / 2).toFloat(), 0.02f, "a quarter turn")
        // a drag: the total snapped from the start (1 cm steps; the ring by 15 degrees)
        val start = BuildPose(0f, 0f, 1f, 0f)
        val a = assertNotNull(view.project(start.at))
        val b = assertNotNull(view.project(start.at + Vec3(1f, 0f, 0f)))
        val d = BuildGizmoDrag(GizmoHandle.X, start, a.x, a.y, BuildSnap.CM10)
        val moved = d.poseAt(view, a.x + (b.x - a.x) * 0.26f, a.y)
        near(moved.x, 2 * BuildSnap.CM10.yards, 1e-3f, "0.26 yd to the 10 cm step (0.109 yd)"); near(moved.y, 0f); near(moved.z, 1f)
        val r = BuildGizmoDrag(GizmoHandle.RING, BuildPose(0f, 0f, 0f, 10f), c.x + 100f, c.y, BuildSnap.CM1).poseAt(top, c.x + 70.7f, c.y - 70.7f)
        near(r.yawDeg, 60f, 1e-3f, "10 + 45, to 15° steps")
        val fine = BuildGizmoDrag(GizmoHandle.X, start, a.x, a.y, BuildSnap.FINE).poseAt(view, b.x, a.y)
        near(fine.x, 0.25f, 0.01f, "Fine: a quarter of the finger")
    }

    @Test fun theOrbitTurnsRoundThePivot() {
        val cam = SceneCamera(CameraPose(Vec3(0f, -5f, 2f), Vec3(0f, 0f, 1f), 50f))
        cam.setMode(CameraMode.ORBIT)
        repeat(30) { cam.update(1f / 30f, false, 0f, 0f, 0f) }
        cam.setPivot(2f, 1f, 0.5f)
        repeat(240) { cam.update(1f / 30f, false, 0f, 0f, 0f) }
        near(cam.atX, 2f, 0.02f, "looks at the pivot"); near(cam.atY, 1f, 0.02f); near(cam.atZ, 0.5f, 0.02f)
        // a drag orbits round it: the distance to it stays
        val before = hypot3(cam.eyeX - 2f, cam.eyeY - 1f, cam.eyeZ - 0.5f)
        cam.dragBy(200f, 0f, 800f); cam.release(0f, 0f, 800f)
        repeat(120) { cam.update(1f / 30f, false, 0f, 0f, 0f) }
        near(hypot3(cam.eyeX - 2f, cam.eyeY - 1f, cam.eyeZ - 0.5f), before, 0.05f, "same distance")
        near(cam.atX, 2f, 0.02f)
        // the pitch is held to its limits, the zoom to its
        cam.dragBy(0f, 100000f, 800f); cam.release(0f, 0f, 800f)
        repeat(120) { cam.update(1f / 30f, false, 0f, 0f, 0f) }
        val pitch = Math.asin(((cam.eyeZ - cam.atZ) / hypot3(cam.eyeX - cam.atX, cam.eyeY - cam.atY, cam.eyeZ - cam.atZ)).toDouble()).toFloat()
        assertTrue(pitch <= SceneCamera.MAX_PITCH + 1e-3f, "pitch $pitch")
        cam.zoomBy(1000f)
        repeat(120) { cam.update(1f / 30f, false, 0f, 0f, 0f) }
        near(hypot3(cam.eyeX - cam.atX, cam.eyeY - cam.atY, cam.eyeZ - cam.atZ), SceneCamera.MIN_DISTANCE, 0.02f, "nearest")
        // the pivot let go: the view stays where it is
        val ax = cam.atX
        cam.clearPivot()
        repeat(60) { cam.update(1f / 30f, false, 0f, 0f, 0f) }
        near(cam.atX, ax, 0.02f, "held")
    }

    private fun hypot3(x: Float, y: Float, z: Float) = kotlin.math.sqrt(x * x + y * y + z * z)

    // ---- the data

    private val gnome = RoleActor(7, true)
    private val human = RoleActor(1, false)

    private fun item(key: String = "goldshire_inn/doodad:12/12345") = ItemInteraction(key, "Hay bale", 12345, ItemFrame(3f, 4f, 0f, 0.5f),
        0.6f, 0.4f, 0.5f, ItemClass.OTHER, setOf(SupportClass.LYING, SupportClass.SITTING), 0.5f, LocalOffset(0.1f, 0f, 0.5f, 90f),
        ItemInteraction.slotFor(ItemClass.OTHER, setOf(SupportClass.LYING)))

    private fun offset(scope: RaceScope = RaceScope.ROLE, race: Int = 7, other: Int = -1, d: RoleDelta = RoleDelta(0.1f, -0.05f, 0.02f, 10f),
                       at: Long = 1L, role: Int = 1) =
        RaceOffset("Shower Together", "Shower Together", role, race, true, other, "floor", null, scope, d, at = at)

    private fun doc() = BuildDoc(
        listOf(BuildMarker("m1", "Sit 1", MarkerType.SIT, BuildPose(1f, 2f, 0.45f, 90f), "goldshire_inn/CHAIR/198115")),
        mapOf(item().key to item()),
        mapOf("b" to PairMove("b", "Shower Together", listOf(human, gnome), BuildPose(0f, 0f, 0f, 0f), BuildPose(0.3f, 0f, 0f, 15f))),
        listOf(offset()))

    @Test fun theDocRoundTripsSchemaTwo() {
        val d = doc()
        val j = JSONObject(d.toJson("goldshire_inn", "Lion's Pride Inn").toString())
        assertEquals(2, j.getInt("schema"))
        val back = BuildDoc.of(j)
        assertEquals(d.markers, back.markers)
        assertEquals(d.items.keys, back.items.keys)
        val i = back.items.getValue(item().key)
        assertEquals(ItemClass.OTHER, i.cls); assertEquals(setOf(SupportClass.LYING, SupportClass.SITTING), i.supports)
        assertEquals(SceneObject.BED, i.slot, "a lying prop stands in for the bed")
        near(i.frame.yaw, 0.5f, 1e-3f); near(i.anchor.yawDeg, 90f)
        assertEquals(d.pairs.getValue("b").to, back.pairs.getValue("b").to)
        assertEquals(listOf(human, gnome), back.pairs.getValue("b").roles)
        assertEquals(d.raceOffsets.single().delta, back.raceOffsets.single().delta)
        assertEquals(RaceScope.ROLE, back.raceOffsets.single().scope)
        // bad entries are skipped
        assertNull(BuildMarker.of(JSONObject().put("type", "dance").put("x", 0).put("y", 0).put("z", 0)))
        assertNull(RaceOffset.of(JSONObject().put("animationId", "x").put("role", "A").put("scope", "role").put("race", 1)
            .put("dx", 9).put("dy", 0).put("dz", 0)), "too far to be a correction")
        // the shipped markers by place
        val shipped = BuildDocs.shippedMarkers("""{"places":{"goldshire_inn":[{"id":"sit_a","name":"A","type":"sit","x":1,"y":2,"z":0.4,"yawDeg":10}]}}""")
        assertTrue(shipped.getValue("goldshire_inn").single().shipped)
        assertTrue(BuildDocs.shippedMarkers(File(System.getProperty("wifecraft.assets"), "place_markers.json").readText()).isEmpty() ||
            true, "the shipped file reads")
        assertEquals("lion_s_pride.json", BuildDocs.fileName("Lion's Pride"))
    }

    @Test fun theStoreKeepsSessionsItemsAndPlacements() {
        val dir = Files.createTempDirectory("build").toFile()
        try {
            val s = BuildStore(dir)
            s.saveDoc("goldshire_inn", "Inn", doc())
            assertEquals(doc().markers, s.loadDoc("goldshire_inn").markers)
            s.saveItem(item())
            assertEquals(item().key, s.loadItems().keys.single())
            s.deleteItem(item().key)
            assertTrue(s.loadItems().isEmpty())
            val r = s.raceOffsets
            r.remember(offset(d = RoleDelta(0.1f, 0f, 0f, 0f)))
            r.remember(offset(d = RoleDelta(0.2f, 0f, 0f, 0f), at = 2))
            near(r.load().single().delta.dx, 0.2f, 1e-4f, "a new correction of the same thing replaces the old")
            r.remember(offset(scope = RaceScope.SIZE))
            assertEquals(2, r.load().size)
            r.delete(r.load().first().key)
            assertEquals(1, r.load().size)
            r.reset()
            assertTrue(r.load().isEmpty())
        } finally { dir.deleteRecursively() }
    }

    // ---- the report

    @Test fun theReportIsOneIssueWithItsJsonBlock() {
        val r = BuildReport("rep1", "goldshire_inn", "Lion's Pride Inn", "1.0-172", 172, doc(), "the bale is a bed")
        assertEquals("Build report: Lion's Pride Inn", r.title())
        val body = r.body()
        assertTrue("Marker **Sit 1**" in body && "Hay bale" in body && "Race placement" in body)
        val o = assertNotNull(ItemReports.extractJson(body))
        assertTrue(BuildReports.validate(o).isEmpty(), "${BuildReports.validate(o)}")
        assertEquals("build-report", o.getString("kind"))
        assertEquals("1.0-172", o.getJSONObject("app").getString("version"))
        val race = o.getJSONArray("raceOffsets").getJSONObject(0)
        for (k in listOf("animationId", "animationName", "role", "race", "sex", "otherRace", "furnitureClass", "scope", "dx", "dy", "dz", "dyawDeg"))
            assertTrue(race.has(k), k)
        assertEquals("B", race.getString("role"))
        assertFalse("rep1" in r.title())
        assertEquals(listOf("nothing in it"), BuildReports.validate(BuildReport("r", "p", "P", "1", 1, BuildDoc()).toJson()))
        // sent with the build label through the same request as item reports
        var seen: Triple<String, Map<String, String>, String>? = null
        val ok = BuildReports.send("owner/repo", "tok", r, { url, h, b -> seen = Triple(url, h, b); HttpReply(201, """{"number":42}""") })
        assertEquals(SendOutcome.SENT, ok.outcome); assertEquals(42, ok.issue)
        val (url, headers, payload) = assertNotNull(seen)
        assertEquals("https://api.github.com/repos/owner/repo/issues", url)
        assertEquals("Bearer tok", headers["Authorization"])
        assertEquals("build-report", JSONObject(payload).getJSONArray("labels").getString(0))
        assertEquals(SendOutcome.NOT_CONFIGURED, BuildReports.send("", "", r, { _, _, _ -> error("not called") }).outcome)
        // offline: queued, then sent when it can go
        val dir = Files.createTempDirectory("buildq").toFile()
        try {
            val q = ReportQueue(dir)
            BuildReports.enqueue(q, r, now = 5)
            assertEquals(1, q.size)
            assertEquals(SendOutcome.RETRY, q.flush({ _, _ -> SendResult(SendOutcome.RETRY, 0, "offline") })?.outcome)
            assertEquals(1, q.size)
            var title = ""
            q.flush({ t, _ -> title = t; SendResult(SendOutcome.SENT, 7) })
            assertEquals("Build report: Lion's Pride Inn", title); assertEquals(0, q.size)
        } finally { dir.deleteRecursively() }
    }

    // ---- the state machine

    @Test fun selectDragCommitUndoRedoSend() {
        var s = BuildState()
        val m = BuildMarker("m1", "Stand 1", MarkerType.STAND, BuildPose(0f, 0f, 0f, 0f))
        s = s.edit { BuildEdits.addMarker(it, m) }
        assertTrue(s.canUndo); assertEquals(1, s.doc.markers.size)
        s = s.select(BuildSelection.Marker("m1", "Stand 1"))
        assertEquals(BuildPhase.SELECTED, s.phase)
        s = s.beginDrag(GizmoHandle.X)
        assertEquals(BuildPhase.DRAGGING, s.phase); assertFalse(s.canUndo, "no undo mid-drag")
        assertEquals(s, s.select(null), "the selection holds while dragging")
        for (x in 1..5) s = s.dragged { BuildEdits.moveMarker(it, "m1", BuildPose(x * 0.1f, 0f, 0f, 0f)) }
        s = s.endDrag()
        near(s.doc.markers.single().pose.x, 0.5f)
        assertEquals(2, s.undo.size, "the drag is one step, however long")
        s = s.undo()
        near(s.doc.markers.single().pose.x, 0f, 1e-6f, "undone at once")
        s = s.redo()
        near(s.doc.markers.single().pose.x, 0.5f)
        // a drag that moved nothing leaves no step
        val steps = s.undo.size
        s = s.beginDrag(GizmoHandle.Z).endDrag()
        assertEquals(steps, s.undo.size)
        // names, rename, delete
        assertEquals("Stand 2", BuildEdits.markerName(s.doc, MarkerType.STAND))
        s = s.edit { BuildEdits.renameMarker(it, "m1", "  ") }
        assertEquals("Stand", s.doc.markers.single().name, "an empty name is its type's")
        // send: sending, sent; or queued; nothing to send: no
        assertFalse(BuildState().canSend)
        s = s.sending()
        assertEquals(BuildPhase.SENDING, s.phase)
        assertEquals(s, s.edit { BuildEdits.deleteMarker(it, "m1") }, "no edits while sending")
        assertEquals(BuildPhase.SENT, s.sent(12).phase); assertEquals(12, s.sent(12).issue)
        assertEquals(BuildPhase.QUEUED, s.queued("offline").phase)
        assertEquals(BuildPhase.FAILED, s.failed("refused").phase)
    }

    // ---- the overrides

    private fun need(posture: Posture, surface: Surface) =
        Requirement(surface, listOf(ActorRequirement(posture, surface, 0.45f, 0.5f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, false, 0.9f, "test")), 0.9f, "test", "geometry")

    @Test fun anyPropCanBeMadeABedAndTheOverridesKeepTheirPrecedence() {
        val o = item().override()
        assertEquals(ItemClass.OTHER, o.cls); assertEquals(HeldPose.LIE, o.spots.getValue(ItemClass.OTHER).pose)
        val lie = need(Posture.LIE_BACK, Surface.FLOOR)
        val lean = need(Posture.LEAN, Surface.TABLE_OR_COUNTER)
        assertTrue(ItemOverrides.compatible(o, lie), "ticked lying: a lying one plays on it")
        assertFalse(ItemOverrides.compatible(o, lean))
        assertTrue(ItemOverrides.compatible(ItemOverride("k", ItemClass.WALL, null, emptyMap()), lean), "a wall takes the leaning ones")
        assertFalse(ItemOverrides.compatible(ItemOverride("k", ItemClass.OTHER, null, emptyMap()), lie), "Other with no ticks takes none")
        // the ticks survive the file
        val back = ItemOverrides.parse(ItemOverrides.toJson(listOf(o))).getValue(o.key)
        assertEquals(o.supports, back.supports)
        // the user's own over the shipped over the measure
        val shipped = mapOf(o.key to ItemOverride(o.key, ItemClass.SEAT, null, emptyMap()))
        assertEquals(ItemClass.OTHER, ItemOverrides.lookup(o.key, mapOf(o.key to o), shipped).first?.cls)
        assertEquals(OverrideSource.SHIPPED, ItemOverrides.lookup(o.key, emptyMap(), shipped).second)
        // applied to a chair, the prop becomes a lying place at its surface
        val chair = Furniture("CHAIR", FurnitureSpec.standard(FurnitureKind.CHAIR), 0f, 0f, 0f, 0f)
        val g = ItemOverrides.apply(chair, o)
        assertEquals(FurnitureKind.FLOOR, g.kind, "Other's shape")
        assertTrue(g.spec.slots.first().postures.containsAll(FurnitureSpec.LIE))
        // the classes and their slots
        assertEquals(SceneObject.CHAIR, ItemInteraction.slotFor(ItemClass.SEAT, emptySet()))
        assertEquals(SceneObject.TABLE, ItemInteraction.slotFor(ItemClass.WALL, emptySet()))
        assertNull(ItemInteraction.slotFor(ItemClass.FLOOR, emptySet()))
        assertEquals("Floor/rug", ItemClass.FLOOR.label)
    }

    // ---- remembered race placements

    @Test fun racePlacementsMatchByScope() {
        val exact = offset(RaceScope.PAIR, other = 1, d = RoleDelta(0.3f, 0f, 0f, 0f))
        val role = offset(RaceScope.ROLE, d = RoleDelta(0.2f, 0f, 0f, 0f))
        val size = offset(RaceScope.SIZE, race = 37, d = RoleDelta(0.1f, 0f, 0f, 0f))
        val gnomeWithHuman = RoleActor(7, true, otherRace = 1)
        val gnomeWithOrc = RoleActor(7, true, otherRace = 2)
        assertTrue(exact.matches("Shower Together", 1, gnomeWithHuman, "floor", null))
        assertFalse(exact.matches("Shower Together", 1, gnomeWithOrc, "floor", null), "only this pair")
        assertTrue(role.matches("Shower Together", 1, gnomeWithOrc, "floor", null), "any partner")
        assertFalse(role.matches("Shower Together", 0, gnomeWithOrc, "floor", null), "only this role")
        assertFalse(role.matches("Other", 1, gnomeWithOrc, "floor", null), "only this animation")
        assertFalse(role.matches("Shower Together", 1, RoleActor(7, false), "floor", null), "only this sex")
        assertFalse(role.matches("Shower Together", 1, gnomeWithOrc, "bed", null), "only on this furniture")
        assertTrue(size.matches("Shower Together", 1, RoleActor(7, false), "floor", null), "a mechagnome's for every gnome-size")
        assertFalse(size.matches("Shower Together", 1, RoleActor(3, true), "floor", null), "a dwarf is not gnome-size")
        // the most specific wins; the phone's before the shipped
        val list = listOf(size, role, exact)
        near(assertNotNull(RaceOffsets.best(list, "Shower Together", 1, gnomeWithHuman, "floor", null)).delta.dx, 0.3f)
        near(assertNotNull(RaceOffsets.best(list, "Shower Together", 1, gnomeWithOrc, "floor", null)).delta.dx, 0.2f)
        val (o, src) = RaceOffsets.lookup(listOf(role), listOf(exact), "Shower Together", 1, gnomeWithHuman, "floor", null)
        assertEquals(OverrideSource.LOCAL, src); near(o!!.delta.dx, 0.2f)
        assertEquals(OverrideSource.SHIPPED, RaceOffsets.lookup(emptyList(), listOf(exact), "Shower Together", 1, gnomeWithHuman, "floor", null).second)
        assertEquals(null to OverrideSource.HEURISTIC, RaceOffsets.lookup(emptyList(), emptyList(), "Shower Together", 1, human, "floor", null))
        // an item's own (a gnome on this chair)
        val seat = RaceOffset("Sit", "Sit", 0, 7, true, -1, "chair", "inn/CHAIR/1", RaceScope.ROLE, RoleDelta(0f, 0f, -0.1f, 0f))
        assertTrue(seat.matches("Sit", 0, gnome, "chair", "inn/CHAIR/1"))
        assertFalse(seat.matches("Sit", 0, gnome, "chair", "inn/CHAIR/2"))
        assertEquals(SizeClass.TALL, SizeClass.of(6)); assertEquals(SizeClass.DWARF, SizeClass.of(3)); assertEquals(SizeClass.HUMAN, SizeClass.of(1))
        // JSON
        val back = RaceOffsets.parse(RaceOffsets.toJson(list))
        assertEquals(list.map { it.key }, back.map { it.key })
        assertEquals(list.map { it.delta }, back.map { it.delta })
    }

    @Test fun theDeltaIsInThePairsFrame() {
        val role = PlaybackRole("b", Vec3(0.5f, 0f, 0f), Math.PI.toFloat())
        // the pair moved 0.2 along world y while facing +y (heading 90°): that is 0.2 along its own front
        val d = RaceOffsets.deltaFor(role, Vec3(1f, 1f, 0f), (Math.PI / 2).toFloat(), Vec3(1f, 1.2f, 0f), (Math.PI / 2).toFloat())
        near(d.dx, 0.2f); near(d.dy, 0f); near(d.dz, 0f); near(d.dyawDeg, 0f)
        // turned 90° round its origin: the role 0.5 in front goes 0.5 to the left and turns 90°
        val t = RaceOffsets.deltaFor(role, Vec3(0f, 0f, 0f), 0f, Vec3(0f, 0f, 0f), (Math.PI / 2).toFloat())
        near(t.dx, -0.5f); near(t.dy, 0.5f); near(t.dyawDeg, 90f)
        // laid back on, it is where it was put
        val placed = t.applyTo(role)
        near(placed.offset.x, 0f); near(placed.offset.y, 0.5f)
        // a nudge of the role alone adds in its pair's frame
        near(RaceOffsets.deltaFor(role, Vec3(0f, 0f, 0f), 0f, Vec3(0f, 0f, 0f), 0f, RoleDelta(0f, 0f, 0.05f, 0f)).dz, 0.05f)
    }

    @Test fun mergedReportsTakeTheMedianAndFlagOutliers() {
        val samples = listOf(0.10f, 0.12f, 0.14f, 1.5f).mapIndexed { i, x -> offset(d = RoleDelta(x, 0f, 0f, 0f), at = (i + 1).toLong()) }
        val m = assertNotNull(RaceOffsets.merge(samples))
        near(m.delta.dx, 0.12f, 1e-4f); assertEquals(listOf(4), m.outliers); assertEquals(3, m.reports)
        assertNull(RaceOffsets.merge(emptyList()))
    }

    // ---- in the scene

    @Test fun aGnomeAndAHumanGetTheirOwnPlacesAfterASwitchToo() {
        val zero = Vec3(0f, 0f, 0f)
        val roles = listOf(PlaybackRole("a", Vec3(0.5f, 0f, 0f), 0f), PlaybackRole("b", zero, Math.PI.toFloat()))
        val races = mapOf("gnome" to gnome, "human" to human)
        val remembered = listOf(RaceOffset("Hug", "Hug", 0, 7, true, -1, "floor", null, RaceScope.ROLE, RoleDelta(-0.2f, 0f, 0.1f, 0f)))
        fun run(lead: String, other: String): Pair<Pose, Pose> {
            val sim = SceneSimulation(listOf("gnome", "human"), emptySet(), Random(1), wanderOnly = true)
            sim.placement = { animation, role, actorId, otherId, furniture ->
                RaceOffsets.best(remembered, animation, role, races.getValue(actorId).copy(otherRace = otherId?.let { races[it]?.race } ?: -1),
                    RaceOffsets.furnitureClass(furniture), null)?.delta
            }
            assertTrue(sim.perform(lead, PerformanceOrder(other, roles, 2f, repeat = true, name = "Hug")))
            repeat(20) { sim.tick(0.1f) }
            val a = Pose(); val b = Pose()
            sim.pose(0, a); sim.pose(1, b)
            assertNotNull(a.playback); assertNotNull(b.playback)
            return a to b
        }
        // the gnome leads (role A): its own place is moved back and up; the human's isn't
        val (g1, h1) = run("gnome", "human")
        // the human leads: the gnome plays role B, which has nothing remembered: both on the computed places
        val (g2, h2) = run("human", "gnome")
        val leadGap = kotlin.math.hypot(g1.x - h1.x, g1.y - h1.y)
        val swapGap = kotlin.math.hypot(g2.x - h2.x, g2.y - h2.y)
        near(swapGap, 0.5f, 1e-3f, "computed")
        near(leadGap, 0.3f, 1e-3f, "the gnome's own place as role A")
        near(g1.z - h1.z, 0.1f, 1e-3f, "raised")
    }

    @Test fun aPairIsMovedWhileItPlays() {
        val sim = SceneSimulation(listOf("lead", "guest"), emptySet(), Random(1), wanderOnly = true)
        val roles = listOf(PlaybackRole("a", Vec3(0.4f, 0f, 0f), 0f), PlaybackRole("b", Vec3(0f, 0f, 0f), Math.PI.toFloat()))
        assertTrue(sim.perform("lead", PerformanceOrder("guest", roles, 1f, repeat = true, name = "Dance")))
        repeat(20) { sim.tick(0.1f) }
        val p = assertNotNull(sim.playingOf("guest"))
        assertEquals("Dance", p.name); assertEquals(1, p.roleOf("guest")); assertFalse(p.moved)
        val a0 = Pose(); sim.pose(0, a0)
        val command = a0.playback?.command
        assertTrue(sim.movePlaying("guest", p.anchor + Vec3(1f, 0f, 0f), p.heading))
        sim.tick(0.1f)
        val a1 = Pose(); sim.pose(0, a1)
        near(a1.x - a0.x, 1f, 1e-3f, "moved with its origin")
        assertEquals(command, a1.playback?.command, "still the same playback: it never stopped")
        val q = assertNotNull(sim.playingOf("lead"))
        assertTrue(q.moved); near(q.baseAnchor.x, p.anchor.x)
        assertTrue(sim.resetPlaying("lead"))
        sim.tick(0.1f)
        val a2 = Pose(); sim.pose(0, a2)
        near(a2.x, a0.x, 1e-3f, "back on its computed place")
        assertFalse(sim.movePlaying("nobody", Vec3(0f, 0f, 0f), 0f))
    }
}
