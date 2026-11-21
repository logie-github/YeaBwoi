package com.logie.wifecraft.tools

import com.logie.wifecraft.anim.Anim
import com.logie.wifecraft.anim.ArchiveImport
import com.logie.wifecraft.anim.BoneTrack
import com.logie.wifecraft.anim.CheckedAnimation
import com.logie.wifecraft.anim.HumanScale
import com.logie.wifecraft.anim.ImportInput
import com.logie.wifecraft.anim.ImportPipeline
import com.logie.wifecraft.anim.Profile
import com.logie.wifecraft.anim.Requirement
import com.logie.wifecraft.anim.RequirementJson
import com.logie.wifecraft.anim.Retargeter
import com.logie.wifecraft.anim.Surface
import com.logie.wifecraft.anim.animationChoices
import com.logie.wifecraft.location.LocationInfo
import com.logie.wifecraft.sims.Channel
import com.logie.wifecraft.sims.DbpfFile
import com.logie.wifecraft.sims.SimsReader
import com.logie.wifecraft.simulation.Furniture
import com.logie.wifecraft.simulation.FurnitureAnimation
import com.logie.wifecraft.simulation.FurnitureAvailability
import com.logie.wifecraft.simulation.FurnitureFit
import com.logie.wifecraft.simulation.PairSpacing
import com.logie.wifecraft.simulation.PartnerPair
import com.logie.wifecraft.simulation.PerformanceOrder
import com.logie.wifecraft.simulation.PlayState
import com.logie.wifecraft.simulation.PlaybackKeys
import com.logie.wifecraft.simulation.PlaybackRole
import com.logie.wifecraft.simulation.Pose
import com.logie.wifecraft.simulation.SceneSimulation
import com.logie.wifecraft.story.Vec3
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.random.Random
import kotlin.test.Test

/**
 * The furniture availability audit (README, "Furniture"): every two-person stage of a real pack, read for what it needs as the
 * live scene reads it (its pair spaced, then classified: SceneSimulation.requirementOf), against every place's furniture
 * (locations.json "furniture", measured), the way the scene decided before ([before]: a stage read from its bodies as needing
 * a bed, seat or top played only where it fitted a piece, else "Nothing free here fits ...: it needs a bed") and now
 * ([FurnitureAvailability]: on a piece it fits, else one it nearly fits, else on the floor).
 *
 * Does nothing without AVAIL_OUT. AVAIL_PACK a pack (.package, .zip, .7z) and AVAIL_RIG a package holding the Sims humanoid
 * rigs (WickedWhims' tuning package) import it and keep its requirements in AVAIL_OUT/<AVAIL_LABEL>.reqs.json; without
 * AVAIL_PACK the kept files there are read. Writes AVAIL_OUT/availability.txt. With the pack, AVAIL_RENDER (name keys,
 * "|"-separated), AVAIL_FILES and AVAIL_LOOKS write engine-test/audit plans of those stages as the live simulation plays them
 * in A House in Brill: on its bed (render/bed, [PairBedPlan]) and on its floor in place of a bed (render/floor). Desktop only.
 */
class FurnitureAvailabilityAudit {
    class Stage(val pack: String, val id: String, val name: String, val requirement: Requirement, val drop: Float)

    private val locale = Locale.ROOT
    private fun f(v: Float) = String.format(locale, "%.4f", v)

    @Test
    fun audit() {
        val out = File(System.getenv("AVAIL_OUT") ?: return).also { it.mkdirs() }
        val assets = File(System.getProperty("wifecraft.assets") ?: "../android/app/src/main/assets")
        System.getenv("AVAIL_PACK")?.let { importPack(File(it), File(System.getenv("AVAIL_RIG") ?: error("AVAIL_RIG")), out,
            System.getenv("AVAIL_LABEL") ?: File(it).nameWithoutExtension, assets) }
        val stages = out.listFiles { fl -> fl.name.endsWith(".reqs.json") }.orEmpty().sortedBy { it.name }.flatMap { fl ->
            val arr = JSONArray(fl.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                RequirementJson.fromJson(o.getJSONObject("requires"))?.let {
                    Stage(o.getString("pack"), o.getString("id"), o.getString("name"), it, o.optDouble("drop", Double.NaN).toFloat())
                }
            }
        }
        val report = StringBuilder()
        report.append("stages: ${stages.size} (${stages.groupBy { it.pack }.map { "${it.key} ${it.value.size}" }.joinToString()})\n")
        report.append("by surface: ${stages.groupingBy { it.requirement.surface }.eachCount()}\n")
        report.append("needing furniture: ${stages.count { needsFurniture(it.requirement) }}, of them with a partner on the floor beside it (feet down): " +
            "${stages.count { needsFurniture(it.requirement) && it.requirement.actors.any { a -> a.feetOnFloor } }}; lowered onto the floor they rest " +
            "there: ${stages.count { needsFurniture(it.requirement) && FurnitureAvailability.floorRests(it.requirement, it.drop) }}\n\n")
        val unit = SceneSimulation.HUMAN_BODY_UNIT
        val locations = LocationInfo.parseAll(File(assets, "locations.json").readText())
        report.append("before: play = plays (floor or names), onfur = on a piece it fits, REFUSED = \"Nothing free here fits\"\n")
        report.append("now:    ready = where they are, fits = on a piece it fits, near = a piece it nearly fits (relaxed), floor = on the floor (rests: lowered onto it; air: held up by a partner standing or legs hanging)\n")
        report.append(String.format(locale, "%-16s %-34s | %5s %5s %7s | %5s %5s %5s %5s %5s\n", "place", "furniture (surface yd)", "play", "onfur", "REFUSED",
            "ready", "fits", "near", "rests", "air"))
        for (loc in locations) {
            val pieces = loc.furniture.mapNotNull { p -> Furniture.of(p) }
            val desc = pieces.joinToString(" ") { fu -> "${fu.kind.name.lowercase().replace("bed_double", "bed")}@${"%.2f".format(locale, fu.spec.surface)}" }.ifEmpty { "-" }
            var play = 0; var refused = 0; var onFurniture = 0
            for (s in stages) when (before(s.requirement, pieces, unit)) { 0 -> play++; 1 -> onFurniture++; else -> refused++ }
            val avail = stages.map { it to FurnitureAvailability.of(it.requirement, pieces, unit, drop = it.drop) }
            fun n(st: PlayState, relaxed: Boolean = false) = avail.count { it.second.state == st && it.second.relaxed == relaxed }
            val rests = avail.count { it.second.state == PlayState.FLOOR && it.second.floorRests }
            report.append(String.format(locale, "%-16s %-34s | %5d %5d %7d | %5d %5d %5d %5d %5d\n", loc.id.take(16), desc.take(34), play, onFurniture, refused,
                n(PlayState.READY), n(PlayState.ON_FURNITURE), n(PlayState.ON_FURNITURE, true), rests, n(PlayState.FLOOR) - rests))
            if (System.getenv("AVAIL_DETAIL") == loc.id) for (s in stages) if (before(s.requirement, pieces, unit) == 2) {
                val r = s.requirement
                val a = FurnitureAvailability.of(r, pieces, unit, drop = s.drop)
                report.append("   refused ${s.name.take(56)}: ${r.surface} ${r.actors.joinToString { "${it.posture}/${it.surface}@${"%.2f".format(locale, it.height)}${if (it.feetOnFloor) "F" else ""}" }} -> ${a.state}${if (a.relaxed) " near" else ""}${if (a.state == PlayState.FLOOR) (if (a.floorRests) " rests" else " air") else ""}\n")
            }
        }
        File(out, "availability.txt").writeText(report.toString())
        println(report)
    }

    private fun needsFurniture(r: Requirement) = r.surface != Surface.UNKNOWN && "geometry" in r.basis && r.surface !in Requirement.FLOOR_LEVEL

    /** How the scene decided before: 0 plays as before (on the floor, or by its names), 1 plays on a piece it fits, 2 refused. */
    private fun before(r: Requirement, pieces: List<Furniture>, unit: Float): Int {
        if (!needsFurniture(r)) return 0
        return if (pieces.any { FurnitureFit.fit(r, it, unit) != null }) 1 else 2
    }

    private fun importPack(src: File, rigPath: File, out: File, label: String, assets: File) {
        val profile = Profile.load(File(assets, "sims_profile.json").readText())
        val rigs = DbpfFile(rigPath).use { SimsReader.humanoidRigs(it) }
        val tmp = File(out, "x"); ArchiveImport.deleteTree(tmp)
        val packages = when (val k = ArchiveImport.sniff(src)) {
            ArchiveImport.Kind.PACKAGE -> listOf(src.name to src)
            ArchiveImport.Kind.ZIP, ArchiveImport.Kind.SEVENZ -> ArchiveImport.extract(src, k, tmp).files
            else -> error("not a pack: $k")
        }
        val hashes = HashSet<Int>()
        for ((_, fl) in packages) DbpfFile(fl).use { pkg -> SimsReader.visitClips(pkg, onClip = { c -> c.tracks.mapTo(hashes) { it.boneHash } }, onFailure = { _, _ -> }) }
        val rig = rigs.maxBy { it.coverage(hashes) }
        val work = File(out, "tmp")
        val pipeline = ImportPipeline(Retargeter(profile, rig), work, embeddedRetargeter = { r -> Retargeter(profile, r) })
        val res = pipeline.runAll(packages.map { (n, fl) -> ImportInput(n) { fl.inputStream() } }) { }
        ArchiveImport.deleteTree(tmp)
        val two = animationChoices(res.anims.map { CheckedAnimation(it) }).filter { it.participantCount == 2 && it.playable }
        val arr = JSONArray()
        val renders = System.getenv("AVAIL_RENDER")?.split('|')?.map { PairAlignmentAudit.nameKey(it) }?.filter { it.isNotEmpty() }.orEmpty()
        val chosen = ArrayList<Pair<PartnerPair, Requirement>>()
        for (c in two) {
            val pp = PartnerPair.of(c) ?: continue
            val spaced = runCatching { PairSpacing.spaced(pp) }.getOrNull() ?: continue
            val r = runCatching { FurnitureAnimation.of(spaced).requirement }.getOrNull() ?: continue
            arr.put(JSONObject().put("pack", label).put("id", c.id).put("name", c.name).put("requires", RequirementJson.toJson(r))
                .put("drop", FurnitureAnimation.floorDrop(spaced).toDouble()))
            if (renders.any { PairAlignmentAudit.nameKey(c.name).contains(it) }) chosen += pp to r
        }
        ArchiveImport.deleteTree(work)
        File(out, "$label.reqs.json").writeText(arr.toString())
        if (chosen.isNotEmpty()) {
            val files = System.getenv("AVAIL_FILES") ?: error("AVAIL_FILES"); val looks = System.getenv("AVAIL_LOOKS") ?: error("AVAIL_LOOKS")
            val locations = File(assets, "locations.json")
            val cases = chosen.map { (pp, r) ->
                val slug = (pp.name.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').take(40)).lowercase()
                PairBedPlan.Case(slug, pp.name, "bed", pp, r, null, null, listOf(1000, 2000), listOf(0.0, 0.0), listOf(0.0, 0.0))
            }
            PairBedPlan.write(cases, File(out, "render/bed").also { it.mkdirs() }, "brill_house", locations, files, looks, renders)
            floorPlan(cases, File(out, "render/floor").also { it.mkdirs() }, locations, files, looks)
        }
    }

    /**
     * Brill's floor, no bed in the scene: each case ordered from the animation window as the screen orders it (its pair named:
     * SceneSimulation.perform, a placed order with nothing to place it on: on the floor, lowered), drawn where they play.
     */
    private fun floorPlan(cases: List<PairBedPlan.Case>, out: File, locationsJson: File, files: String, looks: String) {
        val info = LocationInfo.parseAll(locationsJson.readText()).first { it.id == "brill_house" }
        val floorZ = info.interactions.first { it.item == "BED" }.entry.z
        // open floor in the room, toward its door (the inside view's eye stands at -2.7, 3.7)
        val origin = Vec3(-3.9f, 2.6f, floorZ)
        val plan = StringBuilder("files $files\nlocation ${locationsJson.absolutePath} brill_house inside\nfloor ${f(floorZ)}\n")
        val notes = StringBuilder()
        for (c in cases) {
            val pair = c.pair
            val s = SceneSimulation(listOf("p0", "p1"), emptySet(), Random(7), pairs = listOf(pair))
            s.sizes = mapOf("p0" to 1f, "p1" to 1f)
            s.bodyScales = mapOf("p0" to HumanScale.bodyScale(1, false), "p1" to HumanScale.bodyScale(1, true))
            s.requirements = mapOf(pair.id to c.req)
            val order = PerformanceOrder("p1", listOf(PlaybackRole(PlaybackKeys.pair(pair.id, 0), Vec3(0f, 0f, 0f), 0f),
                PlaybackRole(PlaybackKeys.pair(pair.id, 1), Vec3(pair.slotB.x, pair.slotB.y, 0f), 0f)), 4f, name = pair.name, pairId = pair.id)
            if (!s.perform("p0", order)) { notes.append("${c.name}: refused ${s.takeRefusal()}\n"); continue }
            notes.append("${c.name}: ${s.takeQueueNotice()}\n")
            val p = Pose()
            var ready = false
            for (step in 0 until 60 * 60) {
                s.tick(1f / 60f)
                if ((0 until 2).all { s.pose(it, p); p.playback != null }) { repeat(40) { s.tick(1f / 60f) }; ready = true; break }
            }
            if (!ready) { notes.append("${c.name}: never started\n"); continue }
            plan.append("case ${c.slug} kind=floor\nclearsolids\nclearprops\n")
            val at = ArrayList<Vec3>()
            for (i in 0 until 2) {
                s.pose(i, p)
                val role = p.playback!!.clip.substringAfterLast(':').toIntOrNull() ?: 0
                val a = if (role == 0) pair.a else pair.b
                val slot = if (role == 0) pair.slotA else pair.slotB
                val file = File(out, "${c.slug}_$role.anim").also { it.writeText(animText(lifted(a.copy(loop = false), slot.z))) }
                val w = Vec3(p.x, p.y, p.z) + origin
                at += w
                val female = i == 1
                plan.append("actor $i ${File(looks, if (female) "human_f.look" else "human_m.look").absolutePath} " +
                    "${f(HumanScale.target(female))} ${f(HumanScale.PAIR * HumanScale.pairScale(1, female))}\n")
                plan.append("place $i ${f(w.x)} ${f(w.y)} ${f(w.z)} ${f(p.facing)}${if (kotlin.math.abs(p.lift) > 0.01f) " raw" else ""}\n")
                plan.append("clip $i ${file.absolutePath}\n")
                notes.append("   actor $i role $role z ${f(p.z)} lift ${f(p.lift)}\n")
            }
            val cx = (at[0].x + at[1].x) / 2.0; val cy = (at[0].y + at[1].y) / 2.0
            for ((view, turn) in listOf("side" to Math.PI / 2, "3q" to Math.PI / 4)) {
                val ex = cx + Math.cos(turn) * 3.0; val ey = cy + Math.sin(turn) * 3.0
                plan.append(String.format(locale, "camera %.3f %.3f %.3f %.3f %.3f %.3f 45\n", ex, ey, floorZ + 1.5, cx, cy, floorZ + 0.35))
                val d = maxOf(pair.a.duration, pair.b.duration)
                for (frac in listOf(0.25, 0.5)) plan.append("render ${File(out, "${c.slug}_${view}_${(frac * 100).toInt()}.png").absolutePath} 960 540 ${(d * frac * 1000).toInt()}\n")
            }
        }
        File(out, "plan.txt").writeText(plan.toString())
        File(out, "notes.txt").writeText(notes.toString())
    }

    private fun lifted(a: Anim, lift: Float): Anim = if (lift == 0f) a else a.copy(tracks = a.tracks.map { t ->
        if (t.bone == "Root" && t.channel == Channel.POSITION) BoneTrack(t.bone, t.channel, t.times,
            t.values.copyOf().also { v -> for (i in 2 until v.size step 3) v[i] += lift }) else t
    })

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
}
