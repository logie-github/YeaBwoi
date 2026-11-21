package com.logie.wifecraft.tools

import com.logie.wifecraft.anim.Anim
import com.logie.wifecraft.anim.BoneTrack
import com.logie.wifecraft.anim.FleshProfile
import com.logie.wifecraft.anim.HumanScale
import com.logie.wifecraft.anim.Posture
import com.logie.wifecraft.anim.Requirement
import com.logie.wifecraft.location.LocationInfo
import com.logie.wifecraft.sims.Channel
import com.logie.wifecraft.simulation.Furniture
import com.logie.wifecraft.simulation.FurnitureFit
import com.logie.wifecraft.simulation.InteractionSpot
import com.logie.wifecraft.simulation.PartnerPair
import com.logie.wifecraft.simulation.Pose
import com.logie.wifecraft.simulation.SceneObject
import com.logie.wifecraft.simulation.SceneSimulation
import com.logie.wifecraft.story.Vec3
import java.io.File
import java.util.Locale
import kotlin.random.Random

/**
 * The alignment audit's bed mode (PairAlignmentAudit, ALIGN_BED): two-person stages played on a location's bed as the app
 * plays them there, end to end: the live screen's simulation ([SceneSimulation.partnerOn], the furniture's Play, with the
 * location's furniture row and spot as SceneSessionScreen gives them: the bed's measured mattress, its fit, each role's
 * lift), the screen's clips (each role's slot height in its Root track) and its sink (a clip raised over its floor placed
 * raised: `place ... raw`). Writes engine-test/audit's plan (the real bed's solid, the human man and woman dressed at human
 * scale with the clips' root motion), measured with AUDIT_JOINTS=1 for the hips; tools/align/bed.py reads the CSV and the
 * joints against the author's placement (each case's `ahd`/`ahz` keys: B's hips from A's, 3D and up, yards, per moment).
 */
object PairBedPlan {
    private val locale = Locale.ROOT
    private fun f(v: Double) = String.format(locale, "%.4f", v)
    private fun f(v: Float) = f(v.toDouble())

    /** One stage: its two clips (synced), the pair as the app makes it, what it needs, the authored hips per moment (ms). */
    class Case(val slug: String, val name: String, val cls: String, val pair: PartnerPair, val req: Requirement,
               val fleshA: FleshProfile?, val fleshB: FleshProfile?, val ms: List<Int>, val authDist: List<Double>, val authUp: List<Double>)

    private fun rest(p: Posture): String = when {
        p.lying -> "lie"
        p.seated -> "sit"
        p == Posture.KNEEL || p == Posture.ALL_FOURS -> "kneel"
        else -> "stand"
    }

    /** SceneSessionScreen's lifted(): a role's slot height carried in its Root track. */
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

    /**
     * The plan for [cases] on [locationId]'s bed into [out] (plan.txt, clips/, cases.tsv). [renders]: name keys of the stages
     * to draw (side and three-quarter views at a quarter, half and three quarters of the clip, 960x540).
     */
    fun write(cases: List<Case>, out: File, locationId: String, locationsJson: File, files: String, looks: String, renders: List<String>,
              perClass: Int = Int.MAX_VALUE) {
        val info = LocationInfo.parseAll(locationsJson.readText()).first { it.id == locationId }
        val bedRow = info.interactions.first { it.item == SceneObject.BED.name }
        val origin = bedRow.entry
        val toLocal: (Vec3) -> Vec3 = { it - origin }
        val spot = InteractionSpot(toLocal(bedRow.entry), toLocal(bedRow.pose), bedRow.facing)
        val piece = info.furniture.firstOrNull { it.item == SceneObject.BED.name }?.let { Furniture.of(it, toLocal) }
        val model = bedRow.models.first()
        val clipDir = File(out, "clips").apply { mkdirs() }
        val plan = StringBuilder("files $files\nlocation ${locationsJson.absolutePath} $locationId inside\nfloor ${f(origin.z)}\n")
        val index = StringBuilder("case\tname\tclass\tsurface\tposture0\tposture1\tslots\tfitLift\tlift0\tlift1\trole0\n")
        var n = 0
        val taken = HashMap<String, Int>()
        for (c in cases) {
            if ((taken[c.cls] ?: 0) >= perClass) continue
            val s = SceneSimulation(listOf("p0", "p1"), setOf(SceneObject.BED), Random(7), pairs = listOf(c.pair))
            s.sizes = mapOf("p0" to 1f, "p1" to 1f)
            s.bodyScales = mapOf("p0" to HumanScale.bodyScale(1, false), "p1" to HumanScale.bodyScale(1, true))
            s.flesh = listOfNotNull(c.fleshA?.let { "p0" to it }, c.fleshB?.let { "p1" to it }).toMap()
            s.setSpot(SceneObject.BED, spot)
            if (piece != null) s.setFurniture(SceneObject.BED, piece)
            s.requirements = mapOf(c.pair.id to c.req)
            // the furniture's Play: the man (p0) plays the pair's actor 0, the woman actor 1, as the author cast them
            if (!s.partnerOn(SceneObject.BED, c.pair.id, "p0", "p1", fixedRoles = true)) { System.err.println("bed: ${c.name}: ${s.takeRefusal()}"); continue }
            val p = Pose()
            var ready = false
            for (step in 0 until 60 * 60) {
                s.tick(1f / 60f)
                if ((0 until 2).all { s.pose(it, p); p.playback != null }) { repeat(40) { s.tick(1f / 60f) }; ready = true; break }
            }
            if (!ready) { System.err.println("bed: ${c.name}: never started"); continue }
            val fit = s.furniture(SceneObject.BED)?.let { FurnitureFit.fit(c.req, it, SceneSimulation.HUMAN_BODY_UNIT) }
            val roleOf = IntArray(2)
            val lines = StringBuilder()
            val keys = StringBuilder("case ${c.slug} kind=bed")
            val at = ArrayList<Vec3>()
            for (i in 0 until 2) {
                s.pose(i, p)
                val key = p.playback!!.clip
                val role = key.substringAfterLast(':').toIntOrNull() ?: 0
                roleOf[i] = role
                val w = Vec3(p.x, p.y, p.z) + origin
                at += w
                val posture = c.req.actors.getOrNull(role)?.posture ?: Posture.UNKNOWN
                keys.append(" rest$i=${rest(posture)} role$i=$role lift$i=${f(p.lift)} z$i=${f(w.z)} f$i=${f(p.facing)}")
                val a = if (role == 0) c.pair.a else c.pair.b
                val slot = if (role == 0) c.pair.slotA else c.pair.slotB
                val file = File(clipDir, "${c.slug}_$role.anim").also { it.writeText(animText(lifted(a.copy(loop = false), slot.z))) }
                val female = i == 1
                lines.append("actor $i ${File(looks, if (female) "human_f.look" else "human_m.look").absolutePath} " +
                    "${f(HumanScale.target(female))} ${f(HumanScale.PAIR * HumanScale.pairScale(1, female))}\n")
                lines.append("place $i ${f(w.x)} ${f(w.y)} ${f(w.z)} ${f(p.facing)}${if (p.lift > 0.01f) " raw" else ""}\n")
                lines.append("clip $i ${file.absolutePath}\n")
            }
            keys.append(" bedtop=${f(origin.z + (piece?.spec?.regions?.firstOrNull()?.height ?: 0f))}")
            keys.append(" ahd=${c.authDist.joinToString("/") { f(it) }} ahz=${c.authUp.joinToString("/") { f(it) }} ams=${c.ms.joinToString("/")}")
            plan.append(keys).append('\n')
            plan.append("clearsolids\nclearprops\nsolid BED doodad ${model.id} ${f(model.position.x)} ${f(model.position.y)} ${f(model.position.z)}\n")
            plan.append(lines)
            for (t in c.ms) plan.append("measure $t\n")
            if (renders.any { PairAlignmentAudit.nameKey(c.name).contains(it) }) {
                // side and three-quarter views from across the bed's long axis at the pair's middle
                val cx = (at[0].x + at[1].x) / 2.0; val cy = (at[0].y + at[1].y) / 2.0
                val top = origin.z + (piece?.spec?.regions?.firstOrNull()?.height ?: 0.85f) + 0.35
                val yaw = (piece?.yaw ?: 0f).toDouble()
                for ((view, turn) in listOf("side" to yaw + Math.PI / 2, "3q" to yaw + Math.PI / 4)) {
                    val ex = cx + Math.cos(turn) * 3.0; val ey = cy + Math.sin(turn) * 3.0
                    plan.append(String.format(locale, "camera %.3f %.3f %.3f %.3f %.3f %.3f 45\n", ex, ey, top + 0.9, cx, cy, top))
                    val d = maxOf(c.pair.a.duration, c.pair.b.duration)
                    for (frac in listOf(0.25, 0.5)) plan.append("render ${File(out, "${c.slug}_${view}_${(frac * 100).toInt()}.png").absolutePath} 960 540 ${(d * frac * 1000).toInt()}\n")
                }
            }
            index.append(listOf(c.slug, c.name, c.cls, c.req.surface, c.req.actors.getOrNull(0)?.posture, c.req.actors.getOrNull(1)?.posture,
                fit?.slots?.joinToString("/") ?: "", fit?.lift?.let(::f) ?: "", "", "", roleOf[0]).joinToString("\t")).append('\n')
            n++
            taken[c.cls] = (taken[c.cls] ?: 0) + 1
        }
        File(out, "plan.txt").writeText(plan.toString())
        File(out, "cases.tsv").writeText(index.toString())
        println("bed plan: $n cases in $out")
    }
}
