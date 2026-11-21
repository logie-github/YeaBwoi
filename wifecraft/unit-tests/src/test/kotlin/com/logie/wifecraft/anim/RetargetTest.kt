package com.logie.wifecraft.anim

import com.logie.wifecraft.sims.Channel
import com.logie.wifecraft.sims.RigBone
import com.logie.wifecraft.sims.SimsClip
import com.logie.wifecraft.sims.SimsHash
import com.logie.wifecraft.sims.SimsRig
import com.logie.wifecraft.sims.SimsTrack
import java.io.File
import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.*

/**
 * The Sims-to-WoW retarget on synthetic Sims rigs (no game data): a rig built from the reference WoW skeleton's
 * pivots (night elf female 921844, as in the profile's wowRest) taken into Sims axes, so the Sims bind lands on the
 * WoW rest; then the same rig with random bind frames per bone (the result must not depend on them), a T-posed rig
 * (the rest-pose conversion), the root's movement, and the failures.
 */
class RetargetTest {
    private val assets = File(System.getProperty("wifecraft.assets") ?: "../android/app/src/main/assets")
    private val profile = Profile.load(File(assets, "sims_profile.json").readText())

    // WoW model-space pivots of the reference skeleton (921844), for the bones the synthetic rig has.
    private val wow = linkedMapOf(
        "b__ROOT__" to Triple(null, doubleArrayOf(0.0, 0.0, 0.0), "ROOT"),
        "b__ROOT_bind__" to Triple("b__ROOT__", doubleArrayOf(-0.016, 0.0, 1.441), ""),
        "b__Pelvis__" to Triple("b__ROOT_bind__", doubleArrayOf(-0.014, 0.001, 1.434), ""),
        "b__Spine0__" to Triple("b__ROOT_bind__", doubleArrayOf(-0.016, 0.0, 1.473), ""),
        "b__Spine1__" to Triple("b__Spine0__", doubleArrayOf(-0.021, 0.0, 1.537), ""),
        "b__Spine2__" to Triple("b__Spine1__", doubleArrayOf(-0.021, 0.0, 1.611), ""),
        "b__Neck__" to Triple("b__Spine2__", doubleArrayOf(-0.067, 0.0, 1.965), ""),
        "b__Head__" to Triple("b__Neck__", doubleArrayOf(-0.054, 0.0, 2.042), ""),
        "b__L_Clavicle__" to Triple("b__Spine2__", doubleArrayOf(-0.043, 0.055, 1.877), ""),
        "b__L_UpperArm__" to Triple("b__L_Clavicle__", doubleArrayOf(-0.097, 0.170, 1.903), ""),
        "b__L_UpperArmTwist__" to Triple("b__L_UpperArm__", doubleArrayOf(-0.100, 0.300, 1.800), ""),
        "b__L_Forearm__" to Triple("b__L_UpperArm__", doubleArrayOf(-0.105, 0.461, 1.713), ""),
        "b__L_Hand__" to Triple("b__L_Forearm__", doubleArrayOf(-0.044, 0.746, 1.525), ""),
        "b__L_Mid0__" to Triple("b__L_Hand__", doubleArrayOf(-0.007, 0.847, 1.482), ""),
        "b__R_Clavicle__" to Triple("b__Spine2__", doubleArrayOf(-0.043, -0.055, 1.877), ""),
        "b__R_UpperArm__" to Triple("b__R_Clavicle__", doubleArrayOf(-0.097, -0.170, 1.903), ""),
        "b__R_Forearm__" to Triple("b__R_UpperArm__", doubleArrayOf(-0.105, -0.461, 1.713), ""),
        "b__R_Hand__" to Triple("b__R_Forearm__", doubleArrayOf(-0.044, -0.746, 1.525), ""),
        "b__R_Mid0__" to Triple("b__R_Hand__", doubleArrayOf(-0.007, -0.847, 1.482), ""),
        "b__L_Thigh__" to Triple("b__Pelvis__", doubleArrayOf(-0.009, 0.099, 1.301), ""),
        "b__L_Calf__" to Triple("b__L_Thigh__", doubleArrayOf(-0.035, 0.150, 0.703), ""),
        "b__L_Foot__" to Triple("b__L_Calf__", doubleArrayOf(-0.129, 0.199, 0.108), ""),
        "b__L_Toe__" to Triple("b__L_Foot__", doubleArrayOf(0.087, 0.200, 0.028), ""),
        "b__R_Thigh__" to Triple("b__Pelvis__", doubleArrayOf(-0.009, -0.099, 1.301), ""),
        "b__R_Calf__" to Triple("b__R_Thigh__", doubleArrayOf(-0.035, -0.149, 0.703), ""),
        "b__R_Foot__" to Triple("b__R_Calf__", doubleArrayOf(-0.129, -0.199, 0.108), ""),
        "b__R_Toe__" to Triple("b__R_Foot__", doubleArrayOf(0.087, -0.198, 0.028), ""),
    )

    /** WoW (x fwd, y left, z up) to Sims (x left, y up, z fwd), as the profile's axes invert. */
    private fun toSims(w: DoubleArray) = doubleArrayOf(w[1], w[2], w[0])
    private fun toWow(s: DoubleArray) = doubleArrayOf(s[2], s[0], s[1])

    private class Built(val rig: SimsRig, val world: Map<String, DoubleArray>, val worldRot: Map<String, DoubleArray>)

    /** The rig with each bone's world bind position [place] (Sims axes) and world bind rotation from [frame]. */
    private fun rig(place: (String) -> DoubleArray, frame: (String) -> DoubleArray): Built {
        val names = wow.keys.toList()
        val world = names.associateWith(place)
        val rot = names.associateWith { Q.norm(frame(it)) }
        val bones = names.map { n ->
            val parent = wow.getValue(n).first
            val pi = parent?.let { names.indexOf(it) } ?: -1
            val pr = parent?.let { rot.getValue(it) } ?: Q.ID
            val pp = parent?.let { world.getValue(it) } ?: doubleArrayOf(0.0, 0.0, 0.0)
            val lr = Q.mul(Q.inv(pr), rot.getValue(n))
            val lp = Q.rotate(Q.inv(pr), V.sub(world.getValue(n), pp))
            RigBone(SimsHash.fnv32(n), n, pi, lr.map { it.toFloat() }.toFloatArray(), lp.map { it.toFloat() }.toFloatArray())
        }
        return Built(SimsRig(bones), world, rot)
    }

    private fun matching(frame: (String) -> DoubleArray = { Q.ID }) = rig({ toSims(wow.getValue(it).second) }, frame)

    private fun axisAngle(axis: DoubleArray, deg: Double): DoubleArray {
        val u = V.unit(axis)!!
        val h = deg * PI / 360
        return doubleArrayOf(u[0] * sin(h), u[1] * sin(h), u[2] * sin(h), cos(h))
    }

    /** A clip keying [bones] (name -> local rotation) once; every other bone stays at its bind. */
    private fun clip(rot: Map<String, DoubleArray>, pos: Map<String, DoubleArray> = emptyMap(), name: String = "creator:pose 1") = SimsClip(
        name, 0.0667f,
        rot.map { (n, q) -> SimsTrack(SimsHash.fnv32(n), Channel.ROTATION, floatArrayOf(0f), q.map { it.toFloat() }.toFloatArray()) } +
            pos.map { (n, p) -> SimsTrack(SimsHash.fnv32(n), Channel.POSITION, floatArrayOf(0f), p.map { it.toFloat() }.toFloatArray()) },
        "6B20C4F3:00000000:0000000000000001",
    )

    /** The local rotation that turns bone [n] by [turn] in world (Sims axes) with its parent left at its bind. */
    private fun worldTurn(b: Built, n: String, turn: DoubleArray): DoubleArray {
        val parent = wow.getValue(n).first
        val pr = parent?.let { b.worldRot.getValue(it) } ?: Q.ID
        return Q.mul(Q.inv(pr), Q.mul(turn, b.worldRot.getValue(n)))
    }

    private fun rotOf(a: Anim, bone: String, key: Int = 0): DoubleArray {
        val t = a.tracks.single { it.bone == bone && it.channel == Channel.ROTATION }
        return DoubleArray(4) { t.values[key * 4 + it].toDouble() }
    }

    private fun assertQuat(expected: DoubleArray, actual: DoubleArray, tol: Double = 3e-3, what: String = "") {
        val d = abs(Q.dot(Q.norm(expected), Q.norm(actual)))
        assertTrue(d > 1 - tol, "$what: expected ${expected.toList()} got ${actual.toList()} (|dot| $d)")
    }

    @Test
    fun withoutASkeletonEveryClipFailsPlainly() {
        val e = assertFailsWith<IllegalStateException> { Retargeter(profile, null).convert(clip(emptyMap())) }
        assertTrue("needs your Sims 4 skeleton" in e.message!!)
    }

    @Test
    fun withoutASkeletonThePipelineReportsFailed() {
        val tmp = Files.createTempDirectory("rt").toFile()
        val ev = ArrayList<ImportEvent>()
        val out = ImportPipeline(Retargeter(profile, null), tmp, reader = { listOf(clip(emptyMap())) })
            .run(listOf(ImportInput("poses.package") { "DBPF....".toByteArray().inputStream() })) { ev.add(it) }
        assertTrue(out.isEmpty())
        val failed = ev.filterIsInstance<ImportEvent.Failed>().single()
        assertTrue("needs your Sims 4 skeleton" in failed.why, failed.why)
        tmp.deleteRecursively()
    }

    @Test
    fun theProfileNamesOnlyBonesTheEngineAndToolResolve() {
        val cpp = File(assets, "../cpp/engine/m2.cpp").readText()
        val tool = File(assets, "../../../../../tools/wow_bones.py").readText()
        for (b in profile.bones) {
            assertTrue("{\"${b.wow}\"," in cpp, "${b.wow} is not in m2.cpp kWowBones")
            val base = b.wow.removePrefix("L_").removePrefix("R_")
            assertTrue("\"$base\":" in tool, "${b.wow} is not in tools/wow_bones.py")
            if (b.aim != null) assertNotNull(profile.wowRest[b.wow], "${b.wow} aims at ${b.aim} but has no wowRest (rerun wow_bones.py --profile)")
        }
        assertEquals(Profile.REFERENCE_ROOT_HEIGHT, profile.rootHeight, 0.01f)
        assertTrue("kAnimRootHeight = ${Profile.REFERENCE_ROOT_HEIGHT}f" in File(assets, "../cpp/engine/m2.h").readText())
    }

    @Test
    fun theBindPoseOnAMatchingRigIsTheWowRest() {
        val b = matching()
        val a = Retargeter(profile, b.rig).convert(clip(mapOf("b__Spine0__" to Q.ID), name = "creator:PosePack_x_set_1"))
        assertEquals("PosePack_x_set_1", a.name)
        assertFalse(a.loop)
        assertTrue(a.tracks.isNotEmpty())
        for (t in a.tracks.filter { it.channel == Channel.ROTATION }) assertQuat(Q.ID, rotOf(a, t.bone), what = t.bone)
        val root = a.tracks.single { it.bone == "Root" && it.channel == Channel.POSITION }
        for (v in root.values) assertEquals(0f, v, 1e-5f)
        // only bones the rig has; a twist bone the profile leaves out is not a track
        assertNull(a.tracks.firstOrNull { it.bone == "Jaw" })
        assertTrue(a.tracks.none { "Twist" in it.bone })
    }

    @Test
    fun raisingTheLeftArmTurnsTheWowUpperArmAboutForward() {
        val b = matching()
        // Sims: the sim's forward is +z; turning +90 about it takes her left (+x) to up (+y): the arm goes up
        val turn = axisAngle(doubleArrayOf(0.0, 0.0, 1.0), 90.0)
        val a = Retargeter(profile, b.rig).convert(clip(mapOf("b__L_UpperArm__" to worldTurn(b, "b__L_UpperArm__", turn))))
        // WoW: forward is +x, so +90 about +x (left +y to up +z)
        assertQuat(axisAngle(doubleArrayOf(1.0, 0.0, 0.0), 90.0), rotOf(a, "L_UpperArm"), what = "L_UpperArm")
        assertQuat(Q.ID, rotOf(a, "L_Forearm"), what = "the forearm follows its parent")
        assertQuat(Q.ID, rotOf(a, "R_UpperArm"), what = "the other arm")
        // the arm's direction (pivot to forearm) ends above the shoulder
        val dir = V.sub(wow.getValue("b__L_Forearm__").second, wow.getValue("b__L_UpperArm__").second)
        val turned = Q.rotate(rotOf(a, "L_UpperArm"), dir)
        assertTrue(turned[2] > 0.25, "the arm points up: ${turned.toList()}")
    }

    @Test
    fun theResultDoesNotDependOnTheSimsBonesOwnFrames() {
        val rnd = Random(7)
        val framed = matching { doubleArrayOf(rnd.nextDouble(-1.0, 1.0), rnd.nextDouble(-1.0, 1.0), rnd.nextDouble(-1.0, 1.0), rnd.nextDouble(-1.0, 1.0)) }
        val plain = matching()
        val turns = mapOf(
            "b__L_UpperArm__" to axisAngle(doubleArrayOf(0.0, 0.0, 1.0), 70.0),
            "b__Head__" to axisAngle(doubleArrayOf(0.0, 1.0, 0.0), 35.0),
            "b__Spine1__" to axisAngle(doubleArrayOf(1.0, 0.0, 0.0), 20.0),
            "b__R_Forearm__" to axisAngle(doubleArrayOf(0.3, 1.0, 0.2), -60.0),
        )
        // the same world turns, each as a local rotation in its rig's own frames (a parent's turn carried to the child)
        fun keys(b: Built): Map<String, DoubleArray> {
            val g = HashMap<String, DoubleArray>()
            val out = HashMap<String, DoubleArray>()
            for (n in wow.keys) {
                val parent = wow.getValue(n).first
                val pg = parent?.let { g.getValue(it) } ?: Q.ID
                val pb = parent?.let { b.worldRot.getValue(it) } ?: Q.ID
                // posed world = (the parent's accumulated turn) * (this bone's own turn) * bind world
                val accum = Q.mul(pg, Q.inv(pb))
                val own = turns[n] ?: Q.ID
                val world = Q.mul(Q.mul(accum, own), b.worldRot.getValue(n))
                g[n] = world
                if (n in turns) out[n] = Q.mul(Q.inv(pg), world)
            }
            return out
        }
        val a1 = Retargeter(profile, plain.rig).convert(clip(keys(plain)))
        val a2 = Retargeter(profile, framed.rig).convert(clip(keys(framed)))
        for (t in a1.tracks.filter { it.channel == Channel.ROTATION }) assertQuat(rotOf(a1, t.bone), rotOf(a2, t.bone), 1e-4, t.bone)
        assertQuat(axisAngle(doubleArrayOf(0.0, 1.0, 0.0), 20.0), rotOf(a1, "Spine2"), what = "Sims x (left) is WoW y")
        assertQuat(axisAngle(doubleArrayOf(0.0, 0.0, 1.0), 35.0), rotOf(a1, "Head"), what = "Sims y (up) is WoW z")
    }

    @Test
    fun aTPosedSimsBindLandsOnTheWowSkeletonAsATPose() {
        // the Sims arms straight out sideways at the bind (WoW's rest has them 33 degrees down)
        val b = rig({ n ->
            val w = wow.getValue(n).second
            val side = if ("_L_" in n) 1.0 else -1.0
            val arm = listOf("UpperArm__", "UpperArmTwist__", "Forearm__", "Hand__", "Mid0__").firstOrNull { n.endsWith(it) && ("_L_" in n || "_R_" in n) }
            if (arm == null) toSims(w) else {
                val shoulder = wow.getValue(n.replace(arm, "UpperArm__")).second
                val reach = V.sub(w, shoulder).let { kotlin.math.sqrt(V.dot(it, it)) }
                toSims(doubleArrayOf(shoulder[0], shoulder[1] + side * reach, shoulder[2]))
            }
        }, { Q.ID })
        val a = Retargeter(profile, b.rig).convert(clip(mapOf("b__Spine0__" to Q.ID)))
        val r = rotOf(a, "L_UpperArm")
        val restDir = profile.wowRest.getValue("L_UpperArm").map { it.toDouble() }.toDoubleArray()
        val turned = Q.rotate(r, restDir)
        assertEquals(1.0, turned[1], 2e-3, "the WoW upper arm now points straight to her left: ${turned.toList()}")
        assertQuat(Q.ID, rotOf(a, "L_Forearm"), 1e-2, "the forearm is (nearly) straight on the upper arm in both")
        assertQuat(Q.ID, rotOf(a, "Spine1"), what = "the spine matches")
        val rr = Q.rotate(rotOf(a, "R_UpperArm"), profile.wowRest.getValue("R_UpperArm").map { it.toDouble() }.toDoubleArray())
        assertEquals(-1.0, rr[1], 2e-3, "and the right one to her right")
    }

    @Test
    fun theRootMovesByTheBindHeightsRatio() {
        val b = matching()
        val bind = b.rig.bones.first { it.name == "b__ROOT_bind__" }.bindPosition
        // sitting: the hips 0.5 lower and 0.1 back (Sims -z), and the whole body turned 90 degrees to her left
        val lowered = doubleArrayOf(bind[0].toDouble(), bind[1] - 0.5, bind[2] - 0.1)
        val a = Retargeter(profile, b.rig).convert(clip(
            mapOf("b__ROOT_bind__" to axisAngle(doubleArrayOf(0.0, 1.0, 0.0), 90.0)),
            mapOf("b__ROOT_bind__" to lowered)))
        val p = a.tracks.single { it.bone == "Root" && it.channel == Channel.POSITION }.values
        val scale = profile.rootHeight / toSims(wow.getValue("b__ROOT_bind__").second)[1]
        assertEquals(-0.5 * scale, p[2].toDouble(), 1e-4, "down")
        assertEquals(-0.1 * scale, p[0].toDouble(), 1e-4, "back")
        assertQuat(axisAngle(doubleArrayOf(0.0, 0.0, 1.0), 90.0), rotOf(a, "Root"), what = "turned about up")
        assertQuat(Q.ID, rotOf(a, "Spine1"), what = "the spine rides on the root")
    }

    @Test
    fun aRootLockedClipMovesByItsPelvisAsARootDrivenOneDoes() {
        val b = matching()
        fun bindOf(n: String) = b.rig.bones.first { it.name == n }.bindPosition.map { it.toDouble() }.toDoubleArray()
        val root = bindOf("b__ROOT_bind__"); val pelvis = bindOf("b__Pelvis__"); val spine = bindOf("b__Spine0__")
        val turn = axisAngle(doubleArrayOf(0.0, 1.0, 0.0), 90.0)
        // root-driven: the root lowered 0.5, 0.1 back and turned; the pelvis and spine keyed at their binds
        val driven = Retargeter(profile, b.rig).convert(clip(mapOf("b__ROOT_bind__" to turn),
            mapOf("b__ROOT_bind__" to doubleArrayOf(root[0], root[1] - 0.5, root[2] - 0.1), "b__Pelvis__" to pelvis, "b__Spine0__" to spine)))
        // root-locked: the root left at its bind (and turned), the same movement in the pelvis's and spine's own keys (root's frame)
        val back = Q.rotate(Q.inv(turn), doubleArrayOf(0.0, -0.5, -0.1))
        val locked = Retargeter(profile, b.rig).convert(clip(mapOf("b__ROOT_bind__" to turn),
            mapOf("b__ROOT_bind__" to root, "b__Pelvis__" to V.add(pelvis, back), "b__Spine0__" to V.add(spine, back))))
        val pd = driven.tracks.single { it.bone == "Root" && it.channel == Channel.POSITION }.values
        val pl = locked.tracks.single { it.bone == "Root" && it.channel == Channel.POSITION }.values
        val scale = profile.rootHeight / toSims(wow.getValue("b__ROOT_bind__").second)[1]
        assertEquals(-0.5 * scale, pl[2].toDouble(), 1e-4, "the root-locked body goes down with its pelvis")
        for (c in 0 until 3) assertEquals(pd[c].toDouble(), pl[c].toDouble(), 1e-4, "axis $c as the root-driven one")
        for (bone in listOf("Root", "Pelvis", "Spine1")) assertQuat(rotOf(driven, bone), rotOf(locked, bone), what = bone)
        assertEquals(Retargeter.CONVERSION_REVISION, locked.conversionRevision)
        // a pelvis key at its bind adds nothing: the root-driven clip converts as it did without one
        val noPelvis = Retargeter(profile, b.rig).convert(clip(mapOf("b__ROOT_bind__" to turn),
            mapOf("b__ROOT_bind__" to doubleArrayOf(root[0], root[1] - 0.5, root[2] - 0.1))))
        val pn = noPelvis.tracks.single { it.bone == "Root" && it.channel == Channel.POSITION }.values
        for (c in 0 until 3) assertEquals(pn[c].toDouble(), pd[c].toDouble(), 1e-5)
        assertEquals(scale, Retargeter(profile, b.rig).unitsPerMetre!!, 1e-6)
    }

    @Test
    fun aTiltedPelvisKeepsTheWowHipsOnTheSimsHips() {
        // as the game's rig has it: the Sims hip joints level with the root (the WoW ones 0.14 below the Root's pivot)
        val root = wow.getValue("b__ROOT_bind__").second
        val b = rig({ n -> val w = wow.getValue(n).second
            toSims(if (n == "b__L_Thigh__" || n == "b__R_Thigh__") doubleArrayOf(root[0], w[1], root[2]) else w) }, { Q.ID })
        val restHips = DoubleArray(3) { (BodyGeometry.REF.getValue("L_Thigh")[it] + BodyGeometry.REF.getValue("R_Thigh")[it]) / 2 }
        // pitched forward and back (on all fours, lying back) and rolled: the Sims hips stay where they were (they are on the
        // turn's axis), and so must the WoW ones, not swing round the Root's higher pivot (14 cm at 90 degrees, before)
        for ((axis, deg) in listOf(doubleArrayOf(1.0, 0.0, 0.0) to 90.0, doubleArrayOf(1.0, 0.0, 0.0) to -90.0, doubleArrayOf(0.0, 0.0, 1.0) to 60.0)) {
            val a = Retargeter(profile, b.rig).convert(clip(mapOf("b__ROOT_bind__" to axisAngle(axis, deg))))
            val f = BodyGeometry.frames(a).first()
            val hips = DoubleArray(3) { (f["L_Thigh"]!![it] + f["R_Thigh"]!![it]) / 2 * BodyGeometry.REF_UNIT }
            for (c in 0 until 3) assertEquals(restHips[c], hips[c], 3e-3, "turned $deg about ${axis.toList()}: hips ${hips.toList()}, axis $c")
            assertTrue(Retargeter.HIPS[2] < -0.13, "the WoW hips are below the Root's pivot")
        }
    }

    @Test
    fun aFlipNegatesTheAxis() {
        val flipped = Profile.load(File(assets, "sims_profile.json").readText()
            .replace("{\"sims\": \"b__Head__\", \"wow\": \"Head\", \"parent\": \"Neck1\"}", "{\"sims\": \"b__Head__\", \"wow\": \"Head\", \"parent\": \"Neck1\", \"flip\": \"z\"}"))
        assertEquals("z", flipped.wowRule("Head")!!.flip)
        val b = matching()
        val k = clip(mapOf("b__Head__" to worldTurn(b, "b__Head__", axisAngle(doubleArrayOf(0.0, 1.0, 0.0), 40.0))))
        assertQuat(axisAngle(doubleArrayOf(0.0, 0.0, 1.0), -40.0), rotOf(Retargeter(flipped, b.rig).convert(k), "Head"))
    }

    @Test
    fun keysAtSeveralTimesInterpolateAndLoop() {
        val b = matching()
        val up = worldTurn(b, "b__L_UpperArm__", axisAngle(doubleArrayOf(0.0, 0.0, 1.0), 90.0))
        val c = SimsClip("wave", 2f, listOf(
            SimsTrack(SimsHash.fnv32("b__L_UpperArm__"), Channel.ROTATION, floatArrayOf(0f, 1f, 2f),
                (Q.ID + up + Q.ID).map { it.toFloat() }.toFloatArray()),
            SimsTrack(SimsHash.fnv32("b__Head__"), Channel.ROTATION, floatArrayOf(0f, 0.5f),
                (Q.ID + Q.ID).map { it.toFloat() }.toFloatArray()),
        ), "k")
        val a = Retargeter(profile, b.rig).convert(c)
        assertTrue(a.loop)
        assertEquals(2f, a.duration)
        val t = a.tracks.first { it.bone == "L_UpperArm" }
        assertContentEquals(floatArrayOf(0f, 0.5f, 1f, 2f), t.times)
        assertQuat(axisAngle(doubleArrayOf(1.0, 0.0, 0.0), 45.0), rotOf(a, "L_UpperArm", 1), what = "halfway at 0.5 s")
        assertQuat(axisAngle(doubleArrayOf(1.0, 0.0, 0.0), 90.0), rotOf(a, "L_UpperArm", 2))
    }

    @Test
    fun aClipForAnotherBodyFailsWithAReason() {
        val b = matching()
        val e = assertFailsWith<IllegalArgumentException> {
            Retargeter(profile, b.rig).convert(SimsClip("dog", 1f, listOf(SimsTrack(SimsHash.fnv32("b__Tail0__"), Channel.ROTATION, floatArrayOf(0f), floatArrayOf(0f, 0f, 0f, 1f))), "k"))
        }
        assertTrue("None of the clip's" in e.message!!, e.message)
        val empty = assertFailsWith<IllegalArgumentException> { Retargeter(profile, b.rig).convert(SimsClip("x", 1f, emptyList(), "k")) }
        assertTrue("no bone curves" in empty.message!!)
    }

    @Test
    fun aMappedBoneThatIsAlsoDroppedIsRefused() {
        val json = File(assets, "sims_profile.json").readText().replace("\"b__ROOT__\",", "\"b__ROOT__\", \"b__Spine0*\",")
        val e = assertFailsWith<IllegalArgumentException> { Profile.load(json) }
        assertTrue("drop pattern" in e.message!!)
    }

    @Test
    fun theEngineArraysHoldEveryKeyInTrackOrder() {
        val a = Anim("a", "n", "c", 1f, true, listOf(
            BoneTrack("Head", Channel.ROTATION, floatArrayOf(0f, 1f), floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f)),
            BoneTrack("Root", Channel.POSITION, floatArrayOf(0.5f), floatArrayOf(1f, 2f, 3f)),
            BoneTrack("Bad", Channel.POSITION, floatArrayOf(0f, 1f), floatArrayOf(1f, 2f, 3f)),
        ), "s", 0)
        val f = AnimArrays.of(a)
        assertContentEquals(arrayOf("Head", "Root"), f.bones)
        assertContentEquals(intArrayOf(0, 1), f.channels)
        assertContentEquals(intArrayOf(2, 1), f.keyCounts)
        assertContentEquals(floatArrayOf(0f, 1f, 0.5f), f.times)
        assertContentEquals(floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 1f, 2f, 3f), f.values)
        assertTrue(f.loop)
        assertEquals(1f, f.duration)
    }

    /** With WIFECRAFT_ANIM_OUT set: writes a synthetic Sims pose, converted, as the engine harness's anim file. */
    @Test
    fun writesASyntheticPoseForTheEngineHarness() {
        val out = System.getenv("WIFECRAFT_ANIM_OUT") ?: return
        val b = matching()
        val keys = mapOf(
            // her left arm raised high to the side, her head turned to her left and tipped, the right forearm bent forward
            "b__L_UpperArm__" to worldTurn(b, "b__L_UpperArm__", axisAngle(doubleArrayOf(0.0, 0.0, 1.0), 110.0)),
            "b__Head__" to worldTurn(b, "b__Head__", axisAngle(doubleArrayOf(0.0, 1.0, 0.0), 40.0)),
            "b__R_Forearm__" to worldTurn(b, "b__R_Forearm__", axisAngle(doubleArrayOf(1.0, 0.0, 0.0), -90.0)),
        )
        val a = Retargeter(profile, b.rig).convert(clip(keys))
        val f = AnimArrays.of(a)
        val sb = StringBuilder("# ${a.name}: converted by anim/Retargeter from a synthetic Sims pose\nduration ${f.duration}\n")
        if (f.loop) sb.append("loop\n")
        var ti = 0
        var vi = 0
        for (i in f.bones.indices) {
            val per = if (f.channels[i] == 0) 4 else 3
            repeat(f.keyCounts[i]) {
                sb.append(if (per == 4) "rot" else "pos").append(' ').append(f.bones[i]).append(' ').append(f.times[ti])
                repeat(per) { sb.append(' ').append(f.values[vi++]) }
                sb.append('\n'); ti++
            }
        }
        File(out).writeText(sb.toString())
    }
}
