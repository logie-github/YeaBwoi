package com.logie.wifecraft.anim

import com.logie.wifecraft.sims.Channel
import com.logie.wifecraft.sims.SimsClip
import com.logie.wifecraft.sims.SimsTrack
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClipValidationTest {
    private fun turn(deg: Double): FloatArray { val h = Math.toRadians(deg) / 2; return floatArrayOf(0f, 0f, sin(h).toFloat(), cos(h).toFloat()) }
    private fun rot(bone: String, vararg keys: Pair<Float, Double>) = BoneTrack(bone, Channel.ROTATION,
        keys.map { it.first }.toFloatArray(), keys.flatMap { turn(it.second).toList() }.toFloatArray())
    private fun anim(duration: Float, vararg tracks: BoneTrack) = Anim("", "c", "", duration, true, tracks.toList(), "s", 0)

    private val good = anim(1f, rot("Chest", 0f to 0.0, 0.5f to 20.0, 1f to 0.0), rot("L_UpperArm", 0f to 0.0, 1f to 30.0),
        BoneTrack("Root", Channel.POSITION, floatArrayOf(0f, 1f), floatArrayOf(0f, 0f, 0f, 0.1f, 0f, 0f)))

    @Test fun aSoundClipPasses() = assertNull(ClipValidation.problem(good))

    @Test fun brokenClipsAreNamed() {
        assertNotNull(ClipValidation.problem(anim(1f)))                                                    // no tracks
        assertNotNull(ClipValidation.problem(anim(1f, rot("L_Index1", 0f to 0.0, 1f to 40.0))))            // fingers only
        assertNotNull(ClipValidation.problem(anim(1f, BoneTrack("Chest", Channel.ROTATION, floatArrayOf(0f), floatArrayOf(0f, 0f, Float.NaN, 1f)))))
        assertNotNull(ClipValidation.problem(anim(1f, BoneTrack("Chest", Channel.ROTATION, floatArrayOf(0f), floatArrayOf(0f, 0f, 0f, 0f)))))
        assertNotNull(ClipValidation.problem(anim(0f, rot("Chest", 0f to 0.0, 0f to 40.0))))               // moves in no time
        assertNotNull(ClipValidation.problem(anim(700f, rot("Chest", 0f to 0.0, 700f to 40.0))))
        assertNotNull(ClipValidation.problem(anim(1f, rot("Chest", 0f to 0.0, 5f to 40.0))))               // keys past the end
        // a flip: 170 degrees in one 30 fps frame
        val flip = ClipValidation.problem(anim(1f, rot("L_Forearm", 0f to 0.0, 0.033f to 170.0, 1f to 170.0)))
        assertTrue(flip != null && "L_Forearm" in flip, "$flip")
        // the same turn spread over a second is a movement, not a flip
        assertNull(ClipValidation.problem(anim(1f, rot("L_Forearm", 0f to 0.0, 1f to 170.0))))
        // a sign flip alone (q then -q, the same turn) is no flip
        assertNull(ClipValidation.problem(anim(1f, BoneTrack("Chest", Channel.ROTATION, floatArrayOf(0f, 0.033f),
            floatArrayOf(0f, 0f, 0.2588f, 0.9659f, 0f, 0f, -0.2588f, -0.9659f)))))
        assertNotNull(ClipValidation.problem(anim(1f, BoneTrack("Root", Channel.POSITION, floatArrayOf(0f, 0.5f),
            floatArrayOf(0f, 0f, 0f, 5f, 0f, 0f)))))                                                        // a teleport
    }

    @Test fun pairsSayWhichActor() {
        val bad = anim(1f)
        val p = PairAnim("", "p", PairAnim.CATEGORY, good, bad, PairOffset.ZERO, PairOffset.ZERO, true, emptySet(), emptyList(), source = "x", importedAt = 0)
        assertTrue(ClipValidation.problem(p)!!.startsWith("Actor B"))
        assertNull(ClipValidation.problem(p.copy(b = good)))
    }

    /** The import leaves a broken conversion out with its reason, and keeps the rest. */
    @Test fun theImportSkipsBrokenClips() {
        val retarget = object : Retarget {
            override fun convert(clip: SimsClip): Anim =
                if (clip.name == "broken") anim(1f, rot("L_Index1", 0f to 0.0, 1f to 40.0)).copy(name = clip.name)
                else good.copy(name = clip.name)
        }
        val reader: (ByteArray) -> List<SimsClip> = { bytes ->
            String(bytes).lines().drop(1).filter { it.isNotEmpty() }.map {
                SimsClip(it, 1f, listOf(SimsTrack(1, Channel.ROTATION, floatArrayOf(0f), floatArrayOf(0f, 0f, 0f, 1f))), "hash:$it")
            }
        }
        val tmp = Files.createTempDirectory("cv").toFile()
        try {
            val pkg = "DBPF\nfine\nbroken\n".toByteArray()
            val events = ArrayList<ImportEvent>()
            val out = ImportPipeline(retarget, tmp, reader = reader, tuning = { emptyList() })
                .run(listOf(ImportInput("a.package") { pkg.inputStream() })) { events += it }
            assertEquals(listOf("fine"), out.map { it.name })
            val skipped = events.filterIsInstance<ImportEvent.Skipped>().single()
            assertTrue("broken" in skipped.name && "main bones" in skipped.why, "${skipped.name}: ${skipped.why}")
        } finally { tmp.deleteRecursively() }
    }

    /** The engine's copy of the rest directions (cpp engine/boneanim.h kAnimRest) is the profile's wowRest, aim for aim. */
    @Test fun theEngineRestTableMatchesTheProfile() {
        val assets = System.getProperty("wifecraft.assets") ?: return
        val header = File(assets, "../cpp/engine/boneanim.h").readText()
        val rows = Regex("""\{"(\w+)", "(\w+)", (-?[\d.]+)f, (-?[\d.]+)f, (-?[\d.]+)f\}""").findAll(header)
            .associate { m -> m.groupValues[1] to (m.groupValues[2] to List(3) { m.groupValues[3 + it].toFloat() }) }
        val profile = JSONObject(File(assets, "sims_profile.json").readText())
        val rest = profile.getJSONObject("wowRest")
        val bones = profile.getJSONArray("bones")
        var n = 0
        for (i in 0 until bones.length()) {
            val b = bones.getJSONObject(i)
            val aim = b.optString("aim").ifEmpty { null } ?: continue
            val wow = b.getString("wow")
            if (!rest.has(wow)) continue
            val row = rows[wow] ?: error("boneanim.h has no rest direction for $wow")
            assertEquals(aim, row.first, wow)
            val dir = rest.getJSONArray(wow)
            for (c in 0 until 3) assertEquals(dir.getDouble(c).toFloat(), row.second[c], 1e-4f, wow)
            n++
        }
        assertEquals(rows.size, n, "boneanim.h has rows the profile doesn't")
    }
}
