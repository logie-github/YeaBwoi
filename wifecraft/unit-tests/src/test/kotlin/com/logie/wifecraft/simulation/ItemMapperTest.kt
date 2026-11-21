package com.logie.wifecraft.simulation

import com.logie.wifecraft.anim.ActorRequirement
import com.logie.wifecraft.anim.Posture
import com.logie.wifecraft.anim.Requirement
import com.logie.wifecraft.anim.Surface
import com.logie.wifecraft.story.Vec3
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import kotlin.math.PI
import kotlin.test.*

/** Move mode's item mapper: the frame maths, the state machine, the report and its queue, the overrides and their merge. */
class ItemMapperTest {
    private fun near(a: Float, b: Float, eps: Float = 1e-3f, what: String = "") = assertTrue(kotlin.math.abs(a - b) <= eps, "$what: $a vs $b")

    private fun chair(x: Float = 2f, y: Float = 1f, yaw: Float = 0.5f) = Furniture("CHAIR", FurnitureSpec.standard(FurnitureKind.CHAIR), x, y, 0.3f, yaw)

    private fun target(f: Furniture = chair(), obj: String = "CHAIR", model: Int = 198115) =
        ItemTarget("goldshire_inn", "Lion's Pride Inn, Goldshire", obj, model, "Chair", ItemFrame.of(f), ItemFrame(f.x + 10f, f.y, f.z, f.yaw), f.spec)

    private fun report(t: ItemTarget = target(), cls: ItemClass = ItemClass.SEAT, flags: Set<ReportFlag> = setOf(ReportFlag.CLIPS)) =
        ItemReport("r1", "1.0-147", 147, t, cls, NpcInfo(10, true, 1f, null), LocalOffset(0.1f, -0.05f, 0.52f, 12f), HeldPose.SIT,
            "knees in the table", flags, screenshot = true)

    private fun need(posture: Posture, surface: Surface) =
        Requirement(surface, listOf(ActorRequirement(posture, surface, 0.45f, 0.5f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, false, 0.9f, "test")), 0.9f, "test", "geometry")

    // ---- the frame

    @Test fun localAndBackIsTheSamePlaceRotatedAndScaled() {
        for (scale in listOf(1f, 2.5f)) for (yaw in listOf(0f, 0.7f, -2.9f, PI.toFloat())) {
            val frame = ItemFrame(3f, -4f, 1.2f, yaw, scale)
            val world = Vec3(4.1f, -2.2f, 1.9f)
            val o = frame.toLocal(world, 1.1f)
            val (back, facing) = frame.toWorld(o)
            near(back.x, world.x, what = "x"); near(back.y, world.y, what = "y"); near(back.z, world.z, what = "z")
            near(ItemFrame.wrapRad(facing - 1.1f), 0f, what = "facing")
        }
    }

    @Test fun localFrameIsTheItemsFrontAndLeft() {
        val frame = ItemFrame(1f, 2f, 0f, (PI / 2).toFloat())
        // the item faces +y: a point one yard along +y is one yard in front of it, facing the same way as it: 0 degrees
        val o = frame.toLocal(Vec3(1f, 3f, 0.5f), (PI / 2).toFloat())
        near(o.x, 1f); near(o.y, 0f); near(o.z, 0.5f); near(o.yawDeg, 0f)
        // halved by a doubled model
        val big = ItemFrame(1f, 2f, 0f, (PI / 2).toFloat(), 2f).toLocal(Vec3(1f, 3f, 0.5f), 0f)
        near(big.x, 0.5f); near(big.z, 0.25f); near(big.yawDeg, -90f)
    }

    // ---- the state machine

    @Test fun pickPlaceNudgeReport() {
        var s = MoveState()
        assertFalse(s.holding)
        assertEquals(s, s.sending(), "nothing to report while waiting for a tap")
        s = s.tap(target())
        assertEquals(MovePhase.PICKING, s.phase)
        assertEquals(s, s.nudge(0.1f), "no nudge before the class")
        s = s.place(ItemClass.SEAT)
        assertEquals(MovePhase.PLACED, s.phase)
        assertEquals(HeldPose.SIT, s.pose)
        val seat = FurnitureSpec.standard(FurnitureKind.CHAIR).slots.first { it.name == "seat" }
        near(s.offset!!.z, seat.height, what = "the seat's height")
        s = s.nudge(dx = 0.1f, dYawDeg = 15f)
        near(s.offset!!.x, seat.x + 0.1f); near(s.offset!!.yawDeg, 15f)
        s = s.sending()
        assertEquals(MovePhase.SENDING, s.phase)
        assertEquals(s, s.nudge(0.1f), "nothing moves while it sends")
        s = s.sent(123)
        assertEquals(MovePhase.REPORTED, s.phase); assertEquals(123, s.issue)
        // nudged again: it can be reported again
        s = s.nudge(dz = 0.02f)
        assertEquals(MovePhase.PLACED, s.phase)
        assertEquals(MovePhase.QUEUED, s.sending().queued("offline").phase)
        assertEquals(MovePhase.FAILED, s.sending().failed("no").phase)
        assertEquals(HeldPose.STAND, s.nextPose().pose)
        assertEquals(MovePhase.IDLE, MoveState().tap(target()).cancelPick().phase)
    }

    @Test fun eachClassStartsAtItsUsualPlace() {
        val bed = Furniture("BED", FurnitureSpec.standard(FurnitureKind.BED_DOUBLE), 0f, 0f, 0f, 0f)
        val t = ItemTarget("p", "P", "BED", 1, "Bed", ItemFrame.of(bed), ItemFrame.of(bed), bed.spec)
        val (lie, pose) = ItemMapping.defaultSpot(ItemClass.BED, t)
        assertEquals(HeldPose.LIE, pose); assertTrue(lie.z > 0.3f)
        val (front, stand) = ItemMapping.defaultSpot(ItemClass.TABLETOP, t)
        assertEquals(HeldPose.STAND, stand); near(front.z, 0f)
        val floor = ItemTarget("p", "P", "FLOOR", 0, "Floor", ItemFrame(1f, 1f, 0f, 0f), ItemFrame(1f, 1f, 0f, 0f), null)
        assertEquals(LocalOffset(0f, 0f, 0f, 0f), ItemMapping.defaultSpot(ItemClass.FLOOR, floor).first)
        assertTrue(floor.openFloor); assertEquals("p/FLOOR/0", floor.key)
    }

    // ---- the report

    @Test fun reportRoundTripsThroughTheIssueBody() {
        val r = report()
        assertEquals("Item report: Chair/seat Chair 198115 @ Lion's Pride Inn, Goldshire", r.title())
        val body = r.body()
        val o = assertNotNull(ItemReports.extractJson(body))
        assertEquals(emptyList(), ItemReports.validate(o))
        assertEquals(1, o.getInt("schema"))
        assertEquals("seat", o.getJSONObject("item").getString("detected_class"))
        assertEquals("CHAIR", o.getJSONObject("item").getJSONObject("fit").getString("kind"))
        assertTrue(o.getJSONObject("item").getJSONArray("surfaces").length() >= 1)
        assertTrue(o.getJSONObject("npc").isNull("name"), "a player's own NPC's name is never sent")
        val (key, sample) = assertNotNull(ItemReports.sample(o, 7))
        assertEquals("goldshire_inn/CHAIR/198115", key)
        assertEquals(ItemClass.SEAT, sample.cls); assertEquals(HeldPose.SIT, sample.pose); assertEquals(7, sample.issue)
        near(sample.offset.x, 0.1f); near(sample.offset.y, -0.05f); near(sample.offset.z, 0.52f); near(sample.offset.yawDeg, 12f)
        assertTrue("clips" in sample.notes && "knees" in sample.notes)
        assertFalse("/data/" in body || "/storage/" in body, "no phone paths")
    }

    @Test fun badReportsAreNamed() {
        val o = report().toJson()
        o.put("class", "sofa"); o.getJSONObject("offset").put("x", "left")
        val bad = ItemReports.validate(o)
        assertTrue(bad.any { "class" in it }); assertTrue(bad.any { "offset" in it })
        assertNull(ItemReports.sample(o, 1))
        assertNull(ItemReports.extractJson("no block here"))
    }

    @Test fun theRequestIsAnIssueWithTheLabel() {
        var seen: IssueRequest? = null
        val http = HttpTransport { url, headers, body -> seen = IssueRequest(url, headers, body); HttpReply(201, "{\"number\":42}") }
        val r = report()
        val result = ItemReports.send("owner/repo", "tok", r.title(), r.body(), "1.0", http)
        assertEquals(SendResult(SendOutcome.SENT, 42), result)
        val q = assertNotNull(seen)
        assertEquals("https://api.github.com/repos/owner/repo/issues", q.url)
        assertEquals("Bearer tok", q.headers["Authorization"])
        val payload = JSONObject(q.body)
        assertEquals("item-report", payload.getJSONArray("labels").getString(0))
        assertEquals(r.title(), payload.getString("title"))
        // no token: nothing is tried
        var called = false
        assertEquals(SendOutcome.NOT_CONFIGURED, ItemReports.send("owner/repo", "", "t", "b", "1.0", { _, _, _ -> called = true; HttpReply(201) }).outcome)
        assertFalse(called)
    }

    @Test fun answersAreClassified() {
        assertEquals(SendOutcome.RETRY, ItemReports.classify(HttpReply(0, error = "UnknownHostException")).outcome)
        assertEquals(SendOutcome.RETRY, ItemReports.classify(HttpReply(502)).outcome)
        assertEquals(SendOutcome.RETRY, ItemReports.classify(HttpReply(403, retryAfter = "60")).outcome)
        assertEquals(SendOutcome.NOT_CONFIGURED, ItemReports.classify(HttpReply(401, "{\"message\":\"Bad credentials\"}")).outcome)
        assertEquals(SendOutcome.NOT_CONFIGURED, ItemReports.classify(HttpReply(404)).outcome)
        assertEquals(SendOutcome.REFUSED, ItemReports.classify(HttpReply(422)).outcome)
    }

    // ---- the queue

    @Test fun theQueueWaitsOfflineAndGoesWhenOnline() {
        val dir = Files.createTempDirectory("reports").toFile()
        try {
            val q = ReportQueue(File(dir, "queue"))
            q.enqueue(report().copy(id = "a"), now = 1)
            q.enqueue(report().copy(id = "b"), now = 2)
            assertEquals(2, q.size)
            // offline: the first can't go, both stay, the first's try counted
            var tries = 0
            val offline = q.flush({ _, _ -> tries++; SendResult(SendOutcome.RETRY, 0, "no connection") })
            assertEquals(SendOutcome.RETRY, offline?.outcome)
            assertEquals(1, tries, "the rest wait behind the first")
            assertEquals(listOf(1, 0), q.pending().map { it.tries })
            assertEquals("no connection", q.pending()[0].lastError)
            // online: both go, oldest first
            val sent = ArrayList<Pair<String, Int>>()
            var n = 10
            q.flush({ _, _ -> SendResult(SendOutcome.SENT, ++n) }, onSent = { e, issue -> sent += e.reportId to issue })
            assertEquals(listOf("a" to 11, "b" to 12), sent)
            assertEquals(0, q.size)
            // refused as it is: dropped, said so
            q.enqueue(report().copy(id = "c"), now = 3)
            val refused = ArrayList<String>()
            q.flush({ _, _ -> SendResult(SendOutcome.REFUSED, 0, "422") }, onRefused = { e, _ -> refused += e.reportId })
            assertEquals(listOf("c"), refused); assertEquals(0, q.size)
            q.enqueue(report().copy(id = "d"), now = 4)
            q.remove("d"); assertEquals(0, q.size)
        } finally { dir.deleteRecursively() }
    }

    @Test fun mappingsAreKeptListedDeletedAndUsedNewestFirst() {
        val dir = Files.createTempDirectory("mapped").toFile()
        try {
            val store = MappedItems(File(dir, "mapped.json"))
            val r = report()
            store.upsert(MappedItems.of(r, MappedItem.Status.QUEUED, now = 1))
            store.upsert(MappedItems.of(r.copy(id = "r2", cls = ItemClass.BED, pose = HeldPose.LIE), MappedItem.Status.LOCAL, now = 5))
            assertEquals(2, store.load().size)
            store.status("r1", MappedItem.Status.SENT, 99)
            assertEquals("Reported: #99", store.load().first { it.reportId == "r1" }.statusLine)
            val o = MappedItems.overrides(store.load()).getValue(r.target.key)
            assertEquals(ItemClass.BED, o.cls, "the newest mapping of an item wins")
            store.delete("r2")
            assertEquals(ItemClass.SEAT, MappedItems.overrides(store.load()).getValue(r.target.key).cls)
        } finally { dir.deleteRecursively() }
    }

    // ---- the overrides

    @Test fun mergeTakesMediansAndLeavesOutliersOut() {
        fun s(issue: Int, x: Float, yaw: Float, cls: ItemClass = ItemClass.SEAT) = ReportSample(issue, cls, LocalOffset(x, 0f, 0.5f, yaw), HeldPose.SIT, 0.48f, "n$issue")
        val o = assertNotNull(ItemOverrides.merge("k", listOf(s(1, 0.10f, 179f), s(2, 0.12f, -179f), s(3, 0.14f, 177f), s(4, 1.5f, 0f), s(5, 0f, 0f, ItemClass.BED))))
        assertEquals(ItemClass.SEAT, o.cls, "the class most reports gave")
        val seat = o.spots.getValue(ItemClass.SEAT)
        assertEquals(listOf(4), seat.outliers)
        assertEquals(3, seat.reports)
        near(seat.offset.x, 0.12f); near(kotlin.math.abs(seat.offset.yawDeg), 179f, 0.6f, "the facing's median across 180")
        assertTrue(ItemClass.BED in o.spots)
        assertEquals(listOf(1, 2, 3, 4, 5), o.issues)
        near(o.surface!!, 0.48f)
        // a tie: the newest report's class
        assertEquals(ItemClass.BED, ItemOverrides.merge("k", listOf(s(1, 0f, 0f), s(2, 0f, 0f, ItemClass.BED)))!!.cls)
        // through the file's shape and back
        val back = ItemOverrides.parse(ItemOverrides.toJson(listOf(o))).getValue("k")
        assertEquals(o.cls, back.cls); assertEquals(seat.outliers, back.spots.getValue(ItemClass.SEAT).outliers)
    }

    @Test fun lookupPrefersThePhonesOwnThenTheShippedFile() {
        val local = mapOf("k" to ItemOverride("k", ItemClass.BED, null, emptyMap()))
        val shipped = mapOf("k" to ItemOverride("k", ItemClass.SEAT, null, emptyMap()), "j" to ItemOverride("j", ItemClass.FLOOR, null, emptyMap()))
        assertEquals(OverrideSource.LOCAL, ItemOverrides.lookup("k", local, shipped).second)
        assertEquals(ItemClass.BED, ItemOverrides.lookup("k", local, shipped).first?.cls)
        assertEquals(OverrideSource.SHIPPED, ItemOverrides.lookup("j", local, shipped).second)
        assertEquals(null to OverrideSource.HEURISTIC, ItemOverrides.lookup("x", local, shipped))
        assertTrue(ItemOverrides.parse("not json").isEmpty())
    }

    @Test fun anOverrideMovesTheSeatAndChangesTheKind() {
        val f = chair()
        val o = ItemOverride("k", ItemClass.SEAT, null, mapOf(ItemClass.SEAT to HeldSpot(LocalOffset(0.05f, 0f, 0.62f, 0f), HeldPose.SIT)))
        val g = ItemOverrides.apply(f, o)
        assertEquals(FurnitureKind.CHAIR, g.kind, "a chair stays a chair, its measured shape kept")
        assertEquals("user_seat", g.spec.slots.first().name)
        near(g.spec.slots.first().height, 0.62f); near(g.spec.regions.first().height, 0.62f, what = "the seat moved to the reported height")
        assertTrue(g.spec.slots.filter { it.name == "seat" }.all { kotlin.math.abs(it.height - 0.62f) < 1e-3f })
        // a table said to be a seat is taken as one
        val table = Furniture("TABLE", FurnitureSpec.standard(FurnitureKind.TABLE), 0f, 0f, 0f, 0f)
        assertEquals(FurnitureKind.CHAIR, ItemOverrides.apply(table, o).kind)
        // a bed said to be the floor
        assertEquals(FurnitureKind.FLOOR, ItemOverrides.apply(Furniture("BED", FurnitureSpec.standard(FurnitureKind.BED_DOUBLE), 0f, 0f, 0f, 0f),
            ItemOverride("b", ItemClass.FLOOR, null, emptyMap())).kind)
    }

    @Test fun thePickerOffersOnlyWhatFitsTheClass() {
        val sit = need(Posture.SIT, Surface.CHAIR)
        val lean = need(Posture.LEAN, Surface.TABLE_OR_COUNTER)
        val lie = need(Posture.LIE_BACK, Surface.BED)
        val floor = need(Posture.KNEEL, Surface.FLOOR)
        assertEquals(listOf(true, false, false, false), listOf(sit, lean, lie, floor).map { ItemOverrides.compatible(ItemClass.SEAT, it) })
        assertEquals(listOf(false, true, false, false), listOf(sit, lean, lie, floor).map { ItemOverrides.compatible(ItemClass.TABLETOP, it) })
        assertEquals(listOf(false, false, true, false), listOf(sit, lean, lie, floor).map { ItemOverrides.compatible(ItemClass.BED, it) })
        assertEquals(listOf(false, false, false, true), listOf(sit, lean, lie, floor).map { ItemOverrides.compatible(ItemClass.FLOOR, it) })
    }

    @Test fun thePlacesModelsAndTheShippedFileAreRead() {
        val assets = File(System.getProperty("wifecraft.assets"))
        val (id, models) = assertNotNull(ItemMapping.placeModels(File(assets, "locations.json").readText(), "Lion's Pride Inn, Goldshire"))
        assertEquals("goldshire_inn", id)
        assertEquals(198115, models["CHAIR"]); assertTrue((models["BED"] ?: 0) > 0)
        assertNull(ItemMapping.placeModels("{}", "x"))
        // the shipped file parses (empty until reports are merged), and the tool's shape is the app's
        ItemOverrides.parse(File(assets, "item_overrides.json").readText())
        val tool = """{"schema":1,"items":{"goldshire_inn/CHAIR/198115":{"class":"seat","surface":0.479,"spots":{"seat":{"x":0.12,"y":0.0,
            "z":0.5,"yaw":179.0,"pose":"sit","reports":3,"outliers":[4]}},"notes":["n; clips"],"issues":[1,2,3,4],"detected":["seat"]}}}"""
        val o = ItemOverrides.parse(tool).getValue("goldshire_inn/CHAIR/198115")
        assertEquals(ItemClass.SEAT, o.cls); assertEquals(listOf(4), o.spots.getValue(ItemClass.SEAT).outliers); near(o.surface!!, 0.479f)
    }

    // ---- the hold in the scene

    @Test fun heldStaysUntilLetGo() {
        val sim = SceneSimulation(listOf("a", "b"), setOf(SceneObject.CHAIR), kotlin.random.Random(1))
        val at = Vec3(-2.7f, 1.7f, 0.5f)
        assertTrue(sim.holdAt("a", SceneObject.CHAIR, at, 1f, Activity.SIT))
        assertTrue(sim.held("a"))
        repeat(2000) { sim.tick(0.05f) }
        val a = sim.people().first { it.id == "a" }
        assertEquals(at, a.position); assertEquals(Activity.SIT, a.activity); assertEquals(SceneObject.CHAIR, a.furniture)
        assertFalse(sim.available(SceneObject.CHAIR, "b"), "the held one holds the chair")
        assertFalse(sim.holdAt("b", SceneObject.CHAIR, at, 0f, Activity.SIT), "nobody else is put on it")
        sim.endHold("a")
        assertFalse(sim.held("a"))
        assertTrue(sim.available(SceneObject.CHAIR, "b"))
        // held off the furniture when the class's activity isn't its own (a chair stood on)
        assertTrue(sim.holdAt("b", SceneObject.CHAIR, at, 0f, Activity.IDLE))
        assertNull(sim.people().first { it.id == "b" }.furniture)
    }
}
