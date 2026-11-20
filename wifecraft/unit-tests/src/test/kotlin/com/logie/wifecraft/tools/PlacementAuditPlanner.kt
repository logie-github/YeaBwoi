package com.logie.wifecraft.tools

import com.logie.wifecraft.anim.Anim
import com.logie.wifecraft.anim.BodyGeometry
import com.logie.wifecraft.anim.BoneTrack
import com.logie.wifecraft.anim.HumanScale
import com.logie.wifecraft.anim.PoseFixtures
import com.logie.wifecraft.anim.Posture
import com.logie.wifecraft.anim.Requirement
import com.logie.wifecraft.anim.RequirementClassifier
import com.logie.wifecraft.location.LocationInfo
import com.logie.wifecraft.sims.Channel
import com.logie.wifecraft.simulation.Furniture
import com.logie.wifecraft.simulation.FurnitureAnimation
import com.logie.wifecraft.simulation.FurnitureFit
import com.logie.wifecraft.simulation.InteractionSpot
import com.logie.wifecraft.simulation.ModelEmotes
import com.logie.wifecraft.simulation.PartnerEmotes
import com.logie.wifecraft.simulation.PartnerPair
import com.logie.wifecraft.simulation.PartnerSlot
import com.logie.wifecraft.simulation.PerformanceOrder
import com.logie.wifecraft.simulation.PlaybackKeys
import com.logie.wifecraft.simulation.PlaybackRole
import com.logie.wifecraft.simulation.Pose
import com.logie.wifecraft.simulation.SceneObject
import com.logie.wifecraft.simulation.SceneSimulation
import com.logie.wifecraft.story.Vec3
import java.io.File
import java.util.Locale
import kotlin.random.Random
import kotlin.test.Test

/**
 * The placement audit's planner (tools/audit/README.md): for every animation here (the Sims sitting poses converted by the
 * app's own pipeline, two-role pairs made of them and of anim/PoseFixtures' bodies, the Warcraft emotes of the partner
 * fallback and some solo emotes) on every place the app can play it (the floor; the locations' beds, chairs and tables;
 * the test couch), for a set of body pairs, the app's own simulation ([SceneSimulation]: partnerOn, partner, performOn,
 * with the location's furniture rows and spots as MainActivity and SceneSessionScreen give them) puts the people where it
 * would, and the planner writes engine-test/audit's plan: who, where, which clip, when to measure. Does nothing without
 * AUDIT_OUT. Desktop only; nothing here runs on a phone.
 *
 * AUDIT_OUT the output folder; AUDIT_POSES the converted poses (pose1.anim .. pose15.anim, the harness's anim text);
 * AUDIT_LOOKS the look files (human_m.look ...); AUDIT_FILES the game files; AUDIT_COUCH the couch's seat as the engine
 * measures it ("x z": engine-test/audit's `contact`), else the standard spec's; AUDIT_ROOMS which rooms (default all);
 * AUDIT_RAW=1 people placed at the simulation's height (Pose.z, with its lift), not stood on the floor by the engine.
 */
class PlacementAuditPlanner {
    private val locale = Locale.ROOT

    /** A body: its look, race (ChrRaces), sex. */
    data class Body(val look: String, val race: Int, val female: Boolean) {
        val target: String get() = if (HumanScale.keepsOwnSize(race)) "own" else "%.4f".format(Locale.ROOT, HumanScale.target(female))
        val xy: Float get() = HumanScale.PAIR * HumanScale.pairScale(race, female)
    }

    private val bodies = mapOf(
        "human_m" to Body("human_m", 1, false), "human_f" to Body("human_f", 1, true), "orc_m" to Body("orc_m", 2, false),
        "dwarf_m" to Body("dwarf_m", 3, false), "nightelf_f" to Body("nightelf_f", 4, true), "undead_f" to Body("undead_f", 5, true),
        "tauren_m" to Body("tauren_m", 6, false), "gnome_m" to Body("gnome_m", 7, false), "gnome_f" to Body("gnome_f", 7, true),
        "goblin_m" to Body("goblin_m", 9, false), "bloodelf_m" to Body("bloodelf_m", 10, false), "draenei_f" to Body("draenei_f", 11, true),
    )

    /** The body pairs; gnomes for the emotes only. */
    private val pairings = listOf("human_m" to "human_f", "orc_m" to "human_f", "nightelf_f" to "bloodelf_m", "dwarf_m" to "human_f",
        "tauren_m" to "nightelf_f", "undead_f" to "human_m", "goblin_m" to "draenei_f")
    private val gnomePairings = listOf("gnome_m" to "human_f", "gnome_f" to "human_m")
    private val singles = listOf("human_m", "human_f", "orc_m", "dwarf_m", "nightelf_f", "undead_f", "tauren_m", "goblin_m", "bloodelf_m", "draenei_f")

    /** A room: a location (null: the bare stage with the test couch) and its furniture's models for the solids. */
    private class Room(val name: String, val location: String?, val models: Map<SceneObject, Int>)

    private val rooms = listOf(
        Room("goldshire", "goldshire_inn", mapOf(SceneObject.BED to 190887, SceneObject.CHAIR to 198115, SceneObject.TABLE to 198604)),
        Room("brill", "brill_house", mapOf(SceneObject.BED to 190886)),
        Room("thunderbrew", "thunderbrew", mapOf(SceneObject.BED to 197266)),
        Room("stage", null, mapOf(SceneObject.COUCH to COUCH_MODEL)),
    )

    private fun f(v: Float) = "%.4f".format(locale, v)

    /** The harness's anim text as an [Anim]. */
    private fun readAnim(file: File, id: String): Anim {
        var duration = 0f; var loop = false
        val keys = LinkedHashMap<Pair<String, Boolean>, Pair<ArrayList<Float>, ArrayList<Float>>>()
        file.forEachLine { line ->
            val t = line.trim().split(Regex("\\s+"))
            when (t.firstOrNull()) {
                "duration" -> duration = t[1].toFloat()
                "loop" -> loop = true
                "rot", "pos" -> {
                    val rot = t[0] == "rot"
                    val k = keys.getOrPut(t[1] to rot) { ArrayList<Float>() to ArrayList() }
                    k.first += t[2].toFloat()
                    for (i in 0 until if (rot) 4 else 3) k.second += t[3 + i].toFloat()
                }
            }
        }
        val tracks = keys.map { (k, v) -> BoneTrack(k.first, if (k.second) Channel.ROTATION else Channel.POSITION, v.first.toFloatArray(), v.second.toFloatArray()) }
        return Anim(id, id, "", duration, loop, tracks, "audit:$id", 0L, sourceName = id)
    }

    private fun animText(a: Anim): String = buildString {
        append("# ${a.name}\nduration ${a.duration}\n")
        if (a.loop) append("loop\n")
        for (t in a.tracks) {
            val per = if (t.channel == Channel.ROTATION) 4 else 3
            for (k in t.times.indices) {
                append(if (per == 4) "rot " else "pos ").append(t.bone).append(' ').append(t.times[k])
                for (c in 0 until per) append(' ').append(t.values[k * per + c])
                append('\n')
            }
        }
    }

    /** SceneSessionScreen's lifted(): a pair role's slot height carried in its Root track. */
    private fun lifted(a: Anim, lift: Float): Anim = if (lift == 0f) a else a.copy(tracks = a.tracks.map { t ->
        if (t.bone == "Root" && t.channel == Channel.POSITION) BoneTrack(t.bone, t.channel, t.times, t.values.copyOf().also { v ->
            for (i in 2 until v.size step 3) v[i] += lift
        }) else t
    })

    /** What a role rests on, for the audit's floating check. */
    private fun rest(p: Posture): String = when {
        p.lying -> "lie"
        p.seated -> "sit"
        p == Posture.KNEEL || p == Posture.ALL_FOURS -> "kneel"
        else -> "stand"
    }

    private fun times(a: Anim?): List<Int> = if (a == null || a.duration < 0.5f) listOf(600) else listOf(500, 1100, 1700, 2500)

    @Test fun plan() {
        val outDir = System.getenv("AUDIT_OUT") ?: return
        val poses = File(System.getenv("AUDIT_POSES") ?: return)
        val looks = File(System.getenv("AUDIT_LOOKS") ?: return)
        val files = System.getenv("AUDIT_FILES") ?: return
        val wanted = System.getenv("AUDIT_ROOMS")?.split(',')?.map { it.trim() }?.toSet()
        // AUDIT_RAW=1: each person at the height the simulation means (Pose.z = floor + lift), not stood on the floor as the
        // engine's placeActor stands them today (the raised clips' lift is lost there): the app once the lift is carried
        val raw = System.getenv("AUDIT_RAW") == "1"
        val couch = System.getenv("AUDIT_COUCH")?.trim()?.split(Regex("\\s+"))?.map { it.toFloat() }
        val assets = File(System.getProperty("wifecraft.assets") ?: "../android/app/src/main/assets")
        val locationsPath = File(assets, "locations.json")
        val locations = LocationInfo.parseAll(locationsPath.readText())
        val out = File(outDir).apply { mkdirs() }
        val clipDir = File(out, "clips").apply { mkdirs() }

        // ---- the animations
        val pose = (1..15).associateWith { readAnim(File(poses, "pose$it.anim"), "pose$it") }
        val clipFile = HashMap<String, String>()
        fun clip(key: String, a: Anim): String = clipFile.getOrPut(key) {
            File(clipDir, "${key.replace(':', '_')}.anim").also { it.writeText(animText(a)) }.absolutePath
        }
        // two-role pairs: two of the poses side by side as an author would set them (0.6 body units apart, both facing
        // forward), and anim/PoseFixtures' bodies as FurnitureSceneTest makes them
        fun pair(id: String, a: Anim, b: Anim, dx: Double = 0.0, dy: Double = 0.0) = PartnerPair(id, id, a, b,
            PartnerSlot.human(0f, 0f, 0f, 0f), PartnerSlot.human((dx * BodyGeometry.REF_UNIT).toFloat(), (dy * BodyGeometry.REF_UNIT).toFloat(), 0f, 0f), true)
        val F = PoseFixtures
        val pairs = listOf(
            pair("seat_3_5", pose.getValue(3), pose.getValue(5), dy = 0.6), pair("seat_6_7", pose.getValue(6), pose.getValue(7), dy = 0.6),
            pair("seat_8_9", pose.getValue(8), pose.getValue(9), dy = 0.6), pair("seat_13_13", pose.getValue(13), pose.getValue(13), dy = 0.6),
            pair("floor_1_2", pose.getValue(1), pose.getValue(2), dy = 0.6), pair("floor_4_10", pose.getValue(4), pose.getValue(10), dy = 0.6),
            pair("floor_11_12", pose.getValue(11), pose.getValue(12), dy = 0.6), pair("floor_14_14", pose.getValue(14), pose.getValue(14), dy = 0.6),
            pair("lie_15_15", pose.getValue(15), pose.getValue(15), dy = 0.6),
            pair("fx_cuddle", F.lyingBack(0.55, name = "cuddle_a"), F.lyingBack(0.55, name = "cuddle_b"), dy = 0.6),
            pair("fx_sit2", F.sitting(0.45, name = "sit_a"), F.sitting(0.45, name = "sit_b"), dy = 0.55),
            pair("fx_hug", F.standing, F.standing, dx = 0.6),
            pair("fx_floorlie", F.lyingBack(0.0, name = "fl_a"), F.lyingBack(0.0, name = "fl_b"), dy = 0.6),
            pair("fx_spoon", F.lyingBack(0.55, name = "sp_a"), F.lyingBack(0.55, name = "sp_b"), dy = 0.0),
        )
        val solo = listOf(60 to "Talk", 84 to "Point", 77 to "Cry", 74 to "Roar", 82 to "Flex", 83 to "Shy", 73 to "Rude",
            79 to "Beg", 78 to "Chicken", 97 to "SitGround", 115 to "Kneel", 100 to "Sleep")
        val restOfSolo = mapOf(97 to "sit", 115 to "kneel", 100 to "lie")

        val index = StringBuilder("case\troom\tkind\tanim\tplace\tpairing\tposture0\tposture1\tsurface\tnote\n")
        var cases = 0
        for (room in rooms) {
            if (wanted != null && room.name !in wanted) continue
            val plan = StringBuilder()
            plan.append("files $files\n")
            // the stage: x along the scene's +x, the floor at 0; a location: its own coordinates
            val info = room.location?.let { id -> locations.first { it.id == id } }
            val origin: Vec3
            val floorZ: Float
            val pieces = HashMap<SceneObject, Furniture>()
            val spots = HashMap<SceneObject, InteractionSpot>()
            val solids = HashMap<SceneObject, String>()
            if (info != null) {
                plan.append("location ${locationsPath.absolutePath} ${info.id} inside\n")
                val first = info.interactions.first { SceneObject.entries.any { o -> o.name == it.item } }
                origin = first.entry
                floorZ = origin.z
                val toLocal: (Vec3) -> Vec3 = { it - origin }
                for (i in info.interactions) {
                    val item = SceneObject.entries.firstOrNull { it.name == i.item } ?: continue
                    if (item !in room.models) continue
                    spots[item] = InteractionSpot(toLocal(i.entry), toLocal(i.pose), i.facing)
                    info.furniture.firstOrNull { it.item == item.name }?.let { piece -> Furniture.of(piece, toLocal)?.let { pieces[item] = it } }
                    val m = i.models.first()
                    solids[item] = "solid ${item.name} doodad ${m.id} ${f(m.position.x)} ${f(m.position.y)} ${f(m.position.z)}"
                }
            } else {
                // the Embassy's floor (a floor to see in the pictures; nothing of the room is furniture) with the test couch
                // 6 yards along +x from where the people start, its front +x; its seat as the engine measures it (else the spec's)
                val embassy = locations.first { it.id == "embassy" }
                plan.append("location ${locationsPath.absolutePath} embassy inside\n")
                origin = Vec3(0f, 0f, STAGE_FLOOR)
                floorZ = STAGE_FLOOR
                check(embassy.id == "embassy")
                val sx = couch?.getOrNull(0) ?: -0.35f
                val sz = couch?.getOrNull(1)
                val spot = InteractionSpot(Vec3(COUCH_X + sx + 0.9f, 0f, 0f), Vec3(COUCH_X + sx, 0f, sz ?: 0.49f), 0f)
                spots[SceneObject.COUCH] = spot
                pieces[SceneObject.COUCH] = Furniture.of(SceneObject.COUCH, spot)
                solids[SceneObject.COUCH] = "prop COUCH $COUCH_MODEL ${f(COUCH_X)} 0 ${f(STAGE_FLOOR)} 0 1\nsolid COUCH prop COUCH"
            }
            plan.append("floor ${f(floorZ)}\n")
            val objects = spots.keys.toSet()
            fun toWorld(p: Vec3) = p + origin

            /** A simulation of this room with [ids] (bodies), set up as the live screen sets it up. */
            fun sim(ids: List<String>, raceOf: Map<String, Body>, withFurniture: Boolean, pairsHere: List<PartnerPair>, emote: Int = -1): SceneSimulation {
                val s = SceneSimulation(ids, if (withFurniture) objects else emptySet(), Random(7), pairs = pairsHere,
                    emotes = if (emote >= 0) ModelEmotes { _, anim -> anim == emote } else ModelEmotes.STANDARD)
                s.sizes = ids.associateWith { HumanScale.pairScale(raceOf.getValue(it).race, raceOf.getValue(it).female) }
                s.bodyScales = ids.associateWith { HumanScale.bodyScale(raceOf.getValue(it).race, raceOf.getValue(it).female) }
                if (withFurniture) {
                    for ((item, spot) in spots) s.setSpot(item, spot)
                    for ((item, p) in pieces) s.setFurniture(item, p)
                }
                return s
            }

            fun settle(s: SceneSimulation, n: Int): Boolean {
                val p = Pose()
                for (step in 0 until 60 * 60) {
                    s.tick(1f / 60f)
                    if ((0 until n).all { s.pose(it, p); p.playback != null }) {
                        repeat(3) { s.tick(1f / 60f) }
                        return true
                    }
                }
                return false
            }

            /** One case's block: the solids, the people where [s] put them, what they play, the moments to measure. */
            fun emit(id: String, kind: String, anim: String, place: String, item: SceneObject?, cast: List<String>, s: SceneSimulation,
                     clipOf: (String) -> Pair<String, Anim?>, restOf: (String) -> String, extra: String = "") {
                val p = Pose()
                val lines = StringBuilder()
                lines.append("case $id room=${room.name} kind=$kind anim=$anim place=$place pairing=${cast.joinToString("+")}")
                val at = ArrayList<Vec3>()
                val roles = ArrayList<Triple<String, Anim?, String>>()
                for (i in cast.indices) {
                    s.pose(i, p)
                    val key = p.playback?.clip ?: "still"
                    val (cmd, a) = clipOf(key)
                    roles += Triple(cmd, a, restOf(key))
                    at += toWorld(Vec3(p.x, p.y, p.z))
                    lines.append(" rest$i=${restOf(key)} x$i=${f(at[i].x)} y$i=${f(at[i].y)} z$i=${f(at[i].z)} f$i=${f(p.facing)} lift$i=${f(p.lift)}")
                }
                val c = Vec3(at.map { it.x }.average().toFloat(), at.map { it.y }.average().toFloat(), at.map { it.z }.average().toFloat())
                val fy = item?.let { pieces[it]?.yaw } ?: s.partnerOf(cast[0].let { "p0" })?.yaw ?: 0f
                lines.append(" cx=${f(c.x)} cy=${f(c.y)} cz=${f(c.z)} fyaw=${f(fy)}$extra\n")
                lines.append("clearsolids\nclearprops\n")
                if (item != null) solids[item]?.let { lines.append(it).append('\n') }
                for ((i, b) in cast.withIndex()) {
                    val body = bodies.getValue(b)
                    s.pose(i, p)
                    lines.append("actor $i ${File(looks, body.look + ".look").absolutePath} ${body.target} ${f(body.xy)}\n")
                    lines.append("place $i ${f(at[i].x)} ${f(at[i].y)} ${f(at[i].z)} ${f(p.facing)}${if (raw) " raw" else ""}\n")
                    lines.append(roles[i].first.replace("SLOT", "$i")).append('\n')
                }
                if (cast.size < 2) lines.append("hide 1\n")
                val ts = roles.mapNotNull { it.second }.maxByOrNull { it.duration }.let { a ->
                    if (roles.any { it.first.startsWith("emote") }) listOf(1000, 1400, 1800, 2200, 2600) else times(a)
                }
                for (t in ts) lines.append("measure $t\n")
                plan.append(lines)
                cases++
                index.append(listOf(id, room.name, kind, anim, place, cast.joinToString("+"), roles.getOrNull(0)?.third ?: "",
                    roles.getOrNull(1)?.third ?: "", "", extra.trim()).joinToString("\t")).append('\n')
            }

            // ---- singles: each pose, alone, on the floor and on each piece it fits (the player's Play animation)
            for ((n, a) in pose) {
                val r = RequirementClassifier.classify(a)
                val restSingle = rest(r.actors[0].posture)
                val places = ArrayList<SceneObject?>()
                if (r.surface in Requirement.FLOOR_LEVEL && info == null) places += null
                for (item in objects) pieces[item]?.let { if (FurnitureFit.fit(r, it, SceneSimulation.HUMAN_BODY_UNIT) != null) places += item }
                for (item in places) for (b in singles) {
                    val body = bodies.getValue(b)
                    val s = sim(listOf("p0"), mapOf("p0" to body), item != null, emptyList())
                    val order = PerformanceOrder(null, listOf(PlaybackRole("single:pose$n", Vec3(0f, 0f, 0f), 0f)), 30f)
                    val ok = if (item == null) s.perform("p0", order) else s.performOn("p0", item, r, order)
                    if (!ok || !settle(s, 1)) continue
                    emit("${room.name}-pose$n-${item?.name?.lowercase() ?: "floor"}-$b", "single", "pose$n", item?.name?.lowercase() ?: "floor",
                        item, listOf(b), s, { _ -> "clip SLOT ${clip("pose$n", a)}" to a }, { restSingle }, " surface=${r.surface}")
                }
            }

            // ---- pairs: on the floor (partner, no furniture) and on each piece they fit (partnerOn)
            for (p in pairs) {
                val r = FurnitureAnimation.of(p).requirement
                val places = ArrayList<SceneObject?>()
                if (r.surface in Requirement.FLOOR_LEVEL && info == null) places += null
                for (item in objects) pieces[item]?.let { if (FurnitureFit.fit(r, it, SceneSimulation.HUMAN_BODY_UNIT) != null) places += item }
                for (item in places) for ((ba, bb) in pairings) {
                    val raceOf = mapOf("p0" to bodies.getValue(ba), "p1" to bodies.getValue(bb))
                    val s = sim(listOf("p0", "p1"), raceOf, item != null, listOf(p))
                    s.requirements = mapOf(p.id to r)
                    val ok = if (item == null) s.partner("p0", "p1") else s.partnerOn(item, p.id, "p0", "p1")
                    if (!ok || s.partnerOf("p0")?.emote?.let { it >= 0 } == true || !settle(s, 2)) continue
                    val fit = item?.let { pieces[it] }?.let { FurnitureFit.fit(r, it, SceneSimulation.HUMAN_BODY_UNIT) }
                    val note = fit?.let { " slots=${it.slots.joinToString("/")} lift=${f(it.lift)} " +
                        it.positions.withIndex().joinToString(" ") { (k, v) -> val w = toWorld(v); "sup${k}x=${f(w.x)} sup${k}y=${f(w.y)} sup${k}z=${f(w.z)}" } } ?: ""
                    emit("${room.name}-${p.id}-${item?.name?.lowercase() ?: "floor"}-$ba+$bb", "pair", p.id, item?.name?.lowercase() ?: "floor",
                        item, listOf(ba, bb), s, { key ->
                            val role = key.substringAfterLast(':').toIntOrNull() ?: 0
                            val a = if (role == 0) p.a else p.b
                            val slot = if (role == 0) p.slotA else p.slotB
                            "clip SLOT ${clip("${p.id}_$role", lifted(a.copy(loop = false), slot.z))}" to a
                        }, { key -> rest(r.actors[key.substringAfterLast(':').toIntOrNull() ?: 0].posture) }, note + " surface=${r.surface}")
                }
            }

            // ---- the partner fallback's emotes (floor) and solo emotes: the stage only (the floor is the same everywhere)
            if (info == null) {
                for (e in PartnerEmotes.ALL) for ((ba, bb) in pairings + gnomePairings) {
                    val raceOf = mapOf("p0" to bodies.getValue(ba), "p1" to bodies.getValue(bb))
                    val s = sim(listOf("p0", "p1"), raceOf, false, emptyList(), e.anim)
                    if (!s.partner("p0", "p1") || !settle(s, 2)) continue
                    emit("${room.name}-emote${e.name}-floor-$ba+$bb", "emote", e.name, "floor", null, listOf(ba, bb), s,
                        { key -> "emote SLOT ${PlaybackKeys.emoteOf(key) ?: 0} ${if (e.loop) 1 else 0}" to null }, { "stand" })
                }
                for ((anim, name) in solo) for (b in singles) {
                    val s = sim(listOf("p0"), mapOf("p0" to bodies.getValue(b)), false, emptyList())
                    emit("${room.name}-solo$name-floor-$b", "solo", name, "floor", null, listOf(b), s,
                        { _ -> "emote SLOT $anim 1" to null }, { restOfSolo[anim] ?: "stand" })
                }
            }
            File(out, "plan_${room.name}.txt").writeText(plan.toString())
        }
        File(out, "cases.tsv").writeText(index.toString())
        println("audit plan: $cases cases in $out")
    }

    companion object {
        /** The test couch (no location has one): the SceneObject's model. */
        val COUCH_MODEL = SceneObject.COUCH.model
        /** The stage's floor (the Embassy's, where the people start) and the couch's place along +x. */
        const val STAGE_FLOOR = 1.72f
        const val COUCH_X = 6f
    }
}
