package com.logie.wifecraft.tools

import com.logie.wifecraft.anim.ArchiveImport
import com.logie.wifecraft.anim.BodyGeometry
import com.logie.wifecraft.anim.CheckedAnimation
import com.logie.wifecraft.anim.FleshLift
import com.logie.wifecraft.anim.FleshProfile
import com.logie.wifecraft.anim.HumanScale
import com.logie.wifecraft.anim.ImportInput
import com.logie.wifecraft.anim.ImportPipeline
import com.logie.wifecraft.anim.Profile
import com.logie.wifecraft.anim.Requirement
import com.logie.wifecraft.anim.Retargeter
import com.logie.wifecraft.anim.animationChoices
import com.logie.wifecraft.sims.DbpfFile
import com.logie.wifecraft.sims.SimsClip
import com.logie.wifecraft.sims.SimsHash
import com.logie.wifecraft.sims.SimsReader
import com.logie.wifecraft.simulation.FurnitureAnimation
import com.logie.wifecraft.simulation.PairFit
import com.logie.wifecraft.simulation.PairSpacing
import com.logie.wifecraft.simulation.PartnerPair
import com.logie.wifecraft.simulation.PartnerSlot
import com.logie.wifecraft.tools.PairAlignment.Body
import com.logie.wifecraft.tools.PairAlignment.CM_PER_YARD
import com.logie.wifecraft.tools.PairAlignment.Pose
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test

/**
 * The pair alignment audit (README, "Alignment audit"): every two-actor stage of a real WickedWhims-format pack, imported by
 * the app's own pipeline, measured frame by frame (about 10 a second) as the app places it against its author's own placement
 * of the two Sims clips ([PairAlignment]), for several body pairs and both of the app's ways of placing a pair:
 *
 *  - **order**: the animation window's order on the floor (SceneSessionScreen.request, SceneSimulation.perform): the roles'
 *    offsets as they are, fitted to a gnome's size (PairFit.roles), nothing moved apart, nothing raised.
 *  - **pair**: an NPC's own pick, a placed order and the furniture's Play (PartnerPair.of, SceneSimulation.spaced for the two
 *    bodies' flesh, PairFit.slots, each role's lift for its own flesh as SceneSimulation.roleLifts gives it).
 *
 * Does nothing without ALIGN_PACK. ALIGN_PACK a pack (a .package, or the .zip/.7z it comes in); ALIGN_RIG a package holding
 * the Sims humanoid rigs (WickedWhims' tuning package); ALIGN_OUT the output folder; ALIGN_FLESH a folder of measured bodies
 * (engine-test/audit's human_m.flesh, human_f.flesh, dwarf_m.flesh, gnome_f.flesh; without it only the generic body);
 * ALIGN_LABEL the pack's name in the report. Writes stages.csv (one row per stage, body pair and way), summary.txt (per pose
 * class: count, failures, worst) and worst.tsv (the worst stages per class, for the stills). Desktop only.
 */
class PairAlignmentAudit {
    private val locale = Locale.ROOT
    private fun f(x: Double) = String.format(locale, "%.2f", x)

    /** The tolerances a stage is flagged at (cm, degrees): see README, "Alignment audit". */
    object Tol {
        const val RETARGET_CM = 2.0
        const val PLACE_CM = 3.0
        const val CONTACT_CM = 5.0
        const val PEN_CM = 4.0
        const val FACING_DEG = 15.0
        const val SINK_CM = 4.0
        const val FLOAT_CM = 6.0
        const val DRIFT_CM = 3.0
    }

    class Combo(val name: String, val a: Body, val b: Body, val sizeA: Float, val sizeB: Float, val scaleA: Float, val scaleB: Float)

    @Test
    fun audit() {
        val packPath = System.getenv("ALIGN_PACK") ?: return
        val rigPath = System.getenv("ALIGN_RIG") ?: error("ALIGN_RIG: a package with the Sims humanoid rigs")
        val out = File(System.getenv("ALIGN_OUT") ?: error("ALIGN_OUT")).also { it.mkdirs() }
        val label = System.getenv("ALIGN_LABEL") ?: File(packPath).name
        val assets = File(System.getProperty("wifecraft.assets"))
        val log = File(out, "log.txt").printWriter()

        // the bodies
        val fleshDir = System.getenv("ALIGN_FLESH")?.let(::File)
        fun measured(name: String): FleshProfile? = fleshDir?.let { File(it, "$name.flesh") }?.takeIf { it.isFile }?.let { file ->
            FleshProfile.of(file.readLines().first().trim().split(Regex("\\s+")).map { it.toFloat() }.toFloatArray())
        }
        val combos = ArrayList<Combo>()
        combos += Combo("generic", Body.GENERIC, Body.GENERIC, 1f, 1f, 1f, 1f)
        val hm = measured("human_m"); val hf = measured("human_f"); val dm = measured("dwarf_m"); val gf = measured("gnome_f")
        if (hm != null && hf != null) {
            val m = Body("human_m", hm, hm.pelvis.toDouble()); val w = Body("human_f", hf, hf.pelvis.toDouble())
            combos += Combo("human_m+human_f", m, w, 1f, 1f, 1f, 1f)
            combos += Combo("human_f+human_m", w, m, 1f, 1f, 1f, 1f)
            if (dm != null) combos += Combo("dwarf_m+human_f", Body("dwarf_m", dm, dm.pelvis.toDouble()), w, 1f, 1f, HumanScale.DWARF_MALE, 1f)
            if (gf != null) combos += Combo("human_m+gnome_f", m, Body("gnome_f", gf, gf.pelvis.toDouble(), HumanScale.GNOME_FEMALE.toDouble()),
                1f, HumanScale.GNOME_FEMALE, 1f, HumanScale.GNOME_FEMALE)
        }
        log.println("bodies: " + combos.joinToString { "${it.name} (pelvis ${f(it.a.pelvis)}/${f(it.b.pelvis)} yd)" })

        // the pack through the app's own pipeline, the "player's skeleton" being the rig that covers its clips best
        val profile = Profile.load(File(assets, "sims_profile.json").readText())
        val rigs = DbpfFile(File(rigPath)).use { SimsReader.humanoidRigs(it) }
        val tmp = File(out, "x"); ArchiveImport.deleteTree(tmp)
        val src = File(packPath)
        val packages = when (val k = ArchiveImport.sniff(src)) {
            ArchiveImport.Kind.PACKAGE -> listOf(src.name to src)
            ArchiveImport.Kind.ZIP, ArchiveImport.Kind.SEVENZ -> ArchiveImport.extract(src, k, tmp).files
            else -> error("not a pack: $k")
        }
        val hashes = HashSet<Int>()
        for ((_, fl) in packages) DbpfFile(fl).use { pkg -> SimsReader.visitClips(pkg, onClip = { c -> c.tracks.mapTo(hashes) { it.boneHash } }, onFailure = { _, _ -> }) }
        val rig = rigs.maxBy { it.coverage(hashes) }
        val pipeline = ImportPipeline(Retargeter(profile, rig), File(out, "tmp"), embeddedRetargeter = { r -> Retargeter(profile, r) })
        val res = pipeline.runAll(packages.map { (n, fl) -> ImportInput(n) { fl.inputStream() } }) { }
        val choices = animationChoices(res.anims.map { CheckedAnimation(it) })
        val two = choices.filter { it.participantCount == 2 && it.playable }
        log.println("pack $label: anims ${res.anims.size}, choices ${choices.size}, two-role playable ${two.size}; rig ${rig.bones.size} bones")
        val wanted = two.flatMap { c -> c.clips.map { it.clip.sourceName.lowercase() } }.toHashSet()
        val sims = HashMap<String, SimsClip>()
        for ((_, fl) in packages) DbpfFile(fl).use { pkg ->
            SimsReader.visitClips(pkg, onClip = { c -> if (c.name.lowercase() in wanted) sims[c.name.lowercase()] = c }, onFailure = { _, _ -> })
        }
        ArchiveImport.deleteTree(tmp)

        val debug = System.getenv("ALIGN_DEBUG")
        val dbg = File(out, "debug.txt").printWriter()
        val csv = File(out, "stages.csv").printWriter()
        csv.println("pack,id,hash,name,group,setting,class,postureA,postureB,surface,combo,way,frames,durA,durB," +
            "retarget_cm,body_cm,place_cm,placeV_cm,push_yd,liftA_cm,liftB_cm,contact_auth_cm,contact_app_cm,contact_err_cm," +
            "pen_auth_cm,pen_app_cm,pen_extra_cm,facing_err_deg,sink_cm,float_cm,drift_err_cm,relAuth_x,relAuth_y,relAuth_z,flags")
        class Row(val cls: String, val combo: String, val way: String, val flags: List<String>, val score: Double, val line: String, val id: String, val name: String)
        val rows = ArrayList<Row>()
        var skipped = 0
        for (c in two) {
            val anA = c.clips[0].forPlayback() ?: continue
            val anB = c.clips[1].forPlayback() ?: continue
            val sa = sims[anA.sourceName.lowercase()]; val sb = sims[anB.sourceName.lowercase()]
            if (sa == null || sb == null) { skipped++; continue }
            val ra = c.roles[0]; val rb = c.roles[1]
            val k = c.roleScale.toDouble()
            val ypm = k * HumanScale.PAIR
            val times = PairAlignment.times(max(anA.duration, anB.duration))
            val authA = PairAlignment.authored(sa, rig, times, ra.offset.x.toDouble(), ra.offset.y.toDouble(), ra.offset.z.toDouble(), ra.facing.toDouble(), ypm)
            val authB = PairAlignment.authored(sb, rig, times, rb.offset.x.toDouble(), rb.offset.y.toDouble(), rb.offset.z.toDouble(), rb.facing.toDouble(), ypm)
            if (authA == null || authB == null) { skipped++; continue }
            val pp = PartnerPair.of(c) ?: continue
            val req: Requirement = runCatching { FurnitureAnimation.of(PairSpacing.spaced(pp)).requirement }.getOrNull() ?: continue
            val pa = req.actors.getOrNull(0)?.posture ?: com.logie.wifecraft.anim.Posture.UNKNOWN
            val pb = req.actors.getOrNull(1)?.posture ?: com.logie.wifecraft.anim.Posture.UNKNOWN
            val mid = times.size / 2
            val cls = PairAlignment.poseClass(authA[mid], authB[mid], pa, pb)
            val floorLevel = req.surface in Requirement.FLOOR_LEVEL
            val hash = "%08x".format(SimsHash.fnv32(sa.name.lowercase() + "|" + sb.name.lowercase()))

            // the generic body's order: the retarget alone, against the author
            val genA = PairAlignment.played(anA, Body.GENERIC, times, ra.offset.x * k * HumanScale.PAIR, ra.offset.y * k * HumanScale.PAIR, ra.offset.z * k, Math.toDegrees(ra.facing.toDouble()))
            val genB = PairAlignment.played(anB, Body.GENERIC, times, rb.offset.x * k * HumanScale.PAIR, rb.offset.y * k * HumanScale.PAIR, rb.offset.z * k, Math.toDegrees(rb.facing.toDouble()))
            val retarget = times.indices.maxOf { i -> relErr(genA[i], genB[i], authA[i], authB[i]).first }

            if (debug != null && c.name.contains(debug, true)) {
                dbg.println("== ${c.name} id=${c.id} roles=${c.roles} scale=$k req=${req.surface} ${req.actors.map { it.posture }}")
                for (i in times.indices step max(1, times.size / 6)) {
                    fun s(p: Pose, j: String) = p[j]!!.joinToString("/") { String.format(locale, "%.3f", it) }
                    dbg.println("t=${times[i]} AUTH A root ${s(authA[i], "Root")} neck ${s(authA[i], "Neck1")} yaw ${f(Math.toDegrees(PairAlignment.yaw(authA[i])))} | B root ${s(authB[i], "Root")} neck ${s(authB[i], "Neck1")} yaw ${f(Math.toDegrees(PairAlignment.yaw(authB[i])))}")
                    dbg.println("        GEN  A root ${s(genA[i], "Root")} neck ${s(genA[i], "Neck1")} yaw ${f(Math.toDegrees(PairAlignment.yaw(genA[i])))} | B root ${s(genB[i], "Root")} neck ${s(genB[i], "Neck1")} yaw ${f(Math.toDegrees(PairAlignment.yaw(genB[i])))}")
                    dbg.println("        AUTH A Lthigh ${s(authA[i], "L_Thigh")} Rthigh ${s(authA[i], "R_Thigh")} head ${s(authA[i], "Head")}; GEN A Lthigh ${s(genA[i], "L_Thigh")} Rthigh ${s(genA[i], "R_Thigh")} head ${s(genA[i], "Head")}")
                }
                dbg.flush()
            }
            val authContact = times.indices.map { i -> PairAlignment.surface(authA[i], authB[i], PairAlignment.HIP_PARTS, PairAlignment.HIP_PARTS) }
            val authPen = times.indices.map { i -> PairAlignment.surface(authA[i], authB[i], skip = PairAlignment.mayTouch) }

            for (combo in combos) {
                // order: offsets as they are, fitted to the bodies' sizes
                val (fa, fb) = PairFit.slots(pp.slotA, pp.slotB, combo.sizeA, combo.sizeB)
                val orderA = PairAlignment.played(anA, combo.a, times, fa.x.toDouble(), fa.y.toDouble(), ra.offset.z * k, Math.toDegrees(ra.facing.toDouble()))
                val orderB = PairAlignment.played(anB, combo.b, times, fb.x.toDouble(), fb.y.toDouble(), rb.offset.z * k, Math.toDegrees(rb.facing.toDouble()))
                // pair: spaced for the two bodies, fitted, each role lifted as roleLifts lifts it
                val spaced = PairSpacing.spaced(pp, combo.a.flesh, combo.b.flesh)
                val push = PairAlignment.horiz(doubleArrayOf((spaced.slotB.x - pp.slotB.x).toDouble(), (spaced.slotB.y - pp.slotB.y).toDouble(), 0.0))
                val (sA, sB) = PairFit.slots(spaced.slotA, spaced.slotB, combo.sizeA, combo.sizeB)
                val unit = SceneUnit.HUMAN * (combo.scaleA + combo.scaleB) / 2.0
                fun lift(i: Int, body: Body, slot: PartnerSlot, anim: com.logie.wifecraft.anim.Anim): Double {
                    val need = req.actors.getOrNull(i) ?: return 0.0
                    val own = if (body.flesh.measured) FleshLift.adjusted(need, BodyGeometry.frames(anim, z = slot.z.toDouble(), flesh = body.flesh), body.flesh).liftUnits
                        else need.liftUnits
                    return ((own - if (floorLevel) 0f else need.liftUnits) * unit)
                }
                val la = lift(0, combo.a, spaced.slotA, anA); val lb = lift(1, combo.b, spaced.slotB, anB)
                val pairA = PairAlignment.played(anA, combo.a, times, sA.x.toDouble(), sA.y.toDouble(), sA.z.toDouble(), sA.yawDeg.toDouble(), la)
                val pairB = PairAlignment.played(anB, combo.b, times, sB.x.toDouble(), sB.y.toDouble(), sB.z.toDouble(), sB.yawDeg.toDouble(), lb)

                for ((way, appA, appB) in listOf(Triple("order", orderA, orderB), Triple("pair", pairA, pairB))) {
                    var body = 0.0; var place = 0.0; var placeV = 0.0; var cErr = 0.0; var cApp = Double.MAX_VALUE; var cAuth = Double.MAX_VALUE
                    var penApp = 0.0; var penAuth = 0.0; var penExtra = 0.0; var facing = 0.0; var sink = 0.0; var float = Double.MAX_VALUE
                    for (i in times.indices) {
                        body = max(body, relErr(appA[i], appB[i], authA[i], authB[i]).first)
                        val pe = relErr(appA[i], appB[i], orderA[i], orderB[i])
                        place = max(place, pe.first); placeV = max(placeV, pe.second)
                        val ca = authContact[i]
                        val cp = PairAlignment.surface(appA[i], appB[i], PairAlignment.HIP_PARTS, PairAlignment.HIP_PARTS)
                        cApp = min(cApp, cp); cAuth = min(cAuth, ca)
                        // the hips' contact: only where the author made them meet (within 3 cm)
                        if (ca < 0.03 / CM_PER_YARD * 100) cErr = if (abs(cp - ca) > abs(cErr)) cp - ca else cErr
                        val pApp = -min(0.0, PairAlignment.surface(appA[i], appB[i], skip = PairAlignment.mayTouch))
                        val pAuth = -min(0.0, authPen[i])
                        penApp = max(penApp, pApp); penAuth = max(penAuth, pAuth); penExtra = max(penExtra, pApp - pAuth)
                        val fe = abs(PairAlignment.wrapDeg(Math.toDegrees((PairAlignment.yaw(appB[i]) - PairAlignment.yaw(appA[i])) -
                            (PairAlignment.yaw(authB[i]) - PairAlignment.yaw(authA[i])))))
                        facing = max(facing, fe)
                        if (floorLevel) {
                            val low = min(PairAlignment.lowest(appA[i]), PairAlignment.lowest(appB[i]))
                            sink = max(sink, -low); float = min(float, low)
                        }
                    }
                    if (!floorLevel || float == Double.MAX_VALUE) float = 0.0
                    // drift: the relation's change from the first moment to the last, against the author's
                    val n = times.size - 1
                    val drift = abs(PairAlignment.len(PairAlignment.sub(rel(appA[n], appB[n]), rel(appA[0], appB[0]))) -
                        PairAlignment.len(PairAlignment.sub(rel(authA[n], authB[n]), rel(authA[0], authB[0]))))
                    val cm = CM_PER_YARD
                    val flags = ArrayList<String>()
                    if (retarget * cm > Tol.RETARGET_CM) flags += "RETARGET"
                    if (place * cm > Tol.PLACE_CM) flags += "PLACE"
                    if (abs(cErr) * cm > Tol.CONTACT_CM) flags += if (cErr > 0) "GAP" else "TIGHT"
                    if (penExtra * cm > Tol.PEN_CM) flags += "PEN"
                    if (facing > Tol.FACING_DEG) flags += "FACING"
                    if (sink * cm > Tol.SINK_CM) flags += "SINK"
                    if (float * cm > Tol.FLOAT_CM && req.actors.all { it.posture != com.logie.wifecraft.anim.Posture.STAND }) flags += "FLOAT"
                    if (drift * cm > Tol.DRIFT_CM) flags += "DRIFT"
                    if (abs(sa.duration - sb.duration) > max(sa.tickLength, sb.tickLength) + 1e-4f) flags += "LENGTH"
                    val ra0 = rel(authA[mid], authB[mid])
                    val line = listOf(q(label), q(c.id), hash, q(c.name), q(c.group), q(c.setting), q(cls), pa, pb, req.surface, combo.name, way, times.size,
                        f(anA.duration.toDouble()), f(anB.duration.toDouble()), f(retarget * cm), f(body * cm), f(place * cm), f(placeV * cm),
                        String.format(locale, "%.3f", push), f(la * cm), f(lb * cm), f(cAuth * cm), f(cApp * cm), f(cErr * cm),
                        f(penAuth * cm), f(penApp * cm), f(penExtra * cm), f(facing), f(sink * cm), f(float * cm), f(drift * cm),
                        String.format(locale, "%.4f", ra0[0]), String.format(locale, "%.4f", ra0[1]), String.format(locale, "%.4f", ra0[2]),
                        flags.joinToString("|")).joinToString(",")
                    csv.println(line)
                    val score = max(max(place, abs(cErr)), max(penExtra, sink)) * cm + facing / 5
                    rows += Row(cls, combo.name, way, flags, score, line, c.id, c.name)
                }
            }
        }
        csv.close(); dbg.close()
        log.println("stages measured ${two.size - skipped}; skipped (no Sims clip or joint) $skipped")

        // per class: count, failures (by flag), the worst
        val sum = File(out, "summary.txt").printWriter()
        sum.println("PACK $label  (tolerances: retarget ${Tol.RETARGET_CM} cm, placement ${Tol.PLACE_CM} cm, hip contact ${Tol.CONTACT_CM} cm, " +
            "extra penetration ${Tol.PEN_CM} cm, facing ${Tol.FACING_DEG} deg, sink ${Tol.SINK_CM} cm, float ${Tol.FLOAT_CM} cm, drift ${Tol.DRIFT_CM} cm)")
        for ((key, group) in rows.groupBy { Triple(it.combo, it.way, "") }.entries.sortedBy { it.key.first + it.key.second }) {
            sum.println("\n== ${key.first} / ${key.second}: ${group.size} stages, ${group.count { it.flags.isNotEmpty() }} flagged; by flag " +
                group.flatMap { it.flags }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.joinToString { "${it.key} ${it.value}" })
            for ((cls, g) in group.groupBy { it.cls }.entries.sortedByDescending { it.value.size }) {
                val bad = g.filter { it.flags.isNotEmpty() }
                val worst = g.maxBy { it.score }
                sum.println(String.format(locale, "  %-34s %5d  flagged %5d  %s  worst: %s (%.1f) %s", cls, g.size, bad.size,
                    bad.flatMap { it.flags }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.joinToString(" ") { "${it.key}:${it.value}" },
                    worst.name.take(48), worst.score, worst.flags.joinToString("|")))
            }
        }
        sum.close()
        val worst = File(out, "worst.tsv").printWriter()
        for ((cls, g) in rows.filter { it.way == "pair" && it.combo != "generic" }.groupBy { it.cls }) {
            for (r in g.sortedByDescending { it.score }.take(3)) worst.println(listOf(label, cls, r.combo, r.id, r.name, f(r.score), r.flags.joinToString("|")).joinToString("\t"))
        }
        worst.close()
        log.close()
    }

    private fun rel(a: Pose, b: Pose) = PairAlignment.sub(b["Root"]!!, a["Root"]!!)

    /** How far B's pelvis is from where it should be against A's: across the floor and up and down (yards). */
    private fun relErr(a: Pose, b: Pose, a0: Pose, b0: Pose): Pair<Double, Double> {
        val d = PairAlignment.sub(rel(a, b), rel(a0, b0))
        return PairAlignment.len(d) to abs(d[2])
    }

    private fun q(s: String) = "\"" + s.replace('"', '\'') + "\""

    /** SceneSimulation's HUMAN_BODY_UNIT (private there): one body unit of a human-scale body in yards. */
    object SceneUnit { val HUMAN = BodyGeometry.REF_UNIT * HumanScale.PAIR }
}
