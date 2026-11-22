package com.logie.wifecraft.characters

import com.logie.wifecraft.anim.Anim
import com.logie.wifecraft.anim.AnimArrays
import com.logie.wifecraft.anim.BoneTrack
import com.logie.wifecraft.anim.ClipLayers
import com.logie.wifecraft.anim.ClipTuning
import com.logie.wifecraft.anim.Ease
import com.logie.wifecraft.anim.HumanScale
import com.logie.wifecraft.anim.PairAnim
import com.logie.wifecraft.anim.PairOffset
import com.logie.wifecraft.anim.PoseKey
import com.logie.wifecraft.anim.PoseTrack
import com.logie.wifecraft.anim.WeightKey
import com.logie.wifecraft.sims.Channel
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.sin

/**
 * A partner stage only a creature NPC takes part in (Sindragosa's: [DragonStages]): a person in the active role (role 0) and the
 * creature receiving (role 1), made of three baked things the engine plays together from one start (cpp engine/bonelayer.h):
 *  * [creatureLayer], the creature's pose keys laid over her own game animation (her neck lowers, her head tilts into the hand;
 *    her breathing, wings and tail go on under it, and the bones the layer holds ([holdBones]) stop following her stand
 *    animation's swings while it plays, so the head the hand is on stays where the layer puts it);
 *  * [personClip], the person's pose (a lean, the head turned up to her, the free arm relaxed, the hand's fingers laid flat);
 *  * [reachKeys], the person's [hand] put on a point of the creature's body ([ReachSpec]: a bone of her model, an offset from its
 *    pivot, the direction the fingers lie along): the engine solves the arm each frame from where her head really is, so the hand
 *    keeps its contact whatever her animation does, and a person of any size meets it (out of reach it stretches straight and
 *    stops short).
 * It is data ([PET_HEAD_JSON] is the table), tuned by [speed] (the stage's pace) and [amount] (how far the neck lowers: the
 * per-stage head height), and registered as an ordinary [PairAnim] ([pair]) that only plays when [creature] is in the pair
 * ([PairAnim.exclusiveTo]); it names no furniture, so it plays on the open floor. Pure JVM.
 */
class CreatureStage(
    val id: String,
    val name: String,
    /** The [CreatureSpec.character] it is for. */
    val creature: String,
    /** One loop, authored seconds. */
    val duration: Float,
    val speed: Float,
    val amount: Float,
    val fps: Int,
    /** Where each stands from the pair's origin, yards and degrees: the person (role 0) and the creature (role 1). */
    val personAt: List<Float>,
    val creatureAt: List<Float>,
    /** Names of the creature's bones to indexes of its model (frostwurmnorthrend: no names in the file). */
    val creatureBones: Map<String, Int>,
    val creatureTracks: List<PoseTrack>,
    /** Names of the bones held while the layer plays, and how strongly over the loop. */
    val creatureFreeze: List<String>,
    val creatureHold: List<WeightKey>,
    val personTracks: List<PoseTrack>,
    val reach: ReachSpec,
) {
    val tuning: ClipTuning get() = ClipTuning(speed, 1f, fps)

    /** One loop's seconds as played. */
    val seconds: Float get() = duration / speed

    /** The creature's layer and the reach, as the engine's arrays (baked once; the screen sends them when the stage starts). */
    class Baked(val layer: AnimArrays, val reach: AnimArrays)

    val baked: Baked by lazy { Baked(AnimArrays.of(creatureLayer()), AnimArrays.of(reachKeys())) }

    /** The model bones the layer holds ([creatureFreeze] by index). */
    val holdBones: List<Int> get() = creatureFreeze.map { checkNotNull(creatureBones[it]) { "No bone $it" } }

    private fun boneIndex(name: String): Int = checkNotNull(creatureBones[name]) { "The stage has no bone named $name" }

    /**
     * The creature's layer as the engine takes it ([com.logie.wifecraft.engine.Native.boneLayer]): rotation tracks named "#N" (the
     * model's bone N) with every angle times [amount], a marker "!#N" for each bone it holds, and the "layer.hold" weight. Loops.
     */
    fun creatureLayer(amountOverride: Float = amount): Anim {
        val t = ClipTuning(speed, amountOverride, fps)
        val base = ClipLayers.bake("$id:creature", name, creatureTracks, duration, true, t) { "#" + boneIndex(it) }
        val marks = holdBones.map { BoneTrack("!#$it", Channel.ROTATION, floatArrayOf(0f), floatArrayOf(0f, 0f, 0f, 1f)) }
        val hold = ClipLayers.bakeScalar("layer.hold", creatureHold, duration, true, t)
        return base.copy(tracks = base.tracks + marks + hold)
    }

    /** The person's pose clip (WoW-side bone names; plays once per cycle: the stage's pair holds it as role 0). */
    fun personClip(): Anim = ClipLayers.bake("$id:person", name, personTracks, duration, false, tuning)

    /** The reach's keys ([com.logie.wifecraft.engine.Native.reach]): "reach.w", "reach.off", "reach.along", "reach.pole". Loops. */
    fun reachKeys(): Anim {
        val n = ClipLayers.frames(duration, fps)
        val times = FloatArray(n) { it * duration / n / speed }
        val off = FloatArray(n * 3)
        for (i in 0 until n) reach.offsetAt(i * duration / n, duration).copyInto(off, i * 3)
        val tracks = listOf(
            ClipLayers.bakeScalar("reach.w", reach.weight, duration, true, tuning),
            BoneTrack("reach.off", Channel.POSITION, times, off),
            ClipLayers.constant("reach.along", reach.along[0], reach.along[1], reach.along[2]),
            ClipLayers.constant("reach.pole", reach.pole[0], reach.pole[1], reach.pole[2]),
        )
        return Anim("$id:reach", name, "Stage", seconds, true, tracks, "layer:$id:reach", 0L)
    }

    /**
     * The stage as a partner animation of the library's kind: [PairAnim.a] the person's clip, [PairAnim.b] the creature's (no Sims
     * clip is ever played on her: the screen lays [creatureLayer] on her own animation; this one only holds the length), the
     * slots in the library's reference units (yards over [HumanScale.PAIR], as an imported pair's), looping, no furniture.
     */
    fun pair(): PairAnim {
        fun slot(p: List<Float>) = PairOffset(p[0] / HumanScale.PAIR, p[1] / HumanScale.PAIR, 0f, p[2])
        val a = personClip()
        val hold = BoneTrack("Root", Channel.ROTATION, floatArrayOf(0f), floatArrayOf(0f, 0f, 0f, 1f))   // (a held pose: the clip must have a track)
        val b = Anim("$id:creature", name, "Stage", seconds, false, listOf(hold), "stage:$id:creature", 0L)
        return PairAnim(id, name, PairAnim.CATEGORY, a, b, slot(personAt), slot(creatureAt), true, emptySet(), emptyList(),
            true, PairAnim.sourceOf(a.source, b.source), 0L, KIND, exclusiveTo = creature)
    }

    companion object {
        /** The stage kind the pair carries ([PairAnim.kind]): a creature's own stage. */
        const val KIND = "creature"

        fun parse(text: String): CreatureStage = fromJson(JSONObject(text))

        private fun floats(a: JSONArray) = List(a.length()) { a.getDouble(it).toFloat() }

        private fun poseTracks(a: JSONArray): List<PoseTrack> = List(a.length()) { i ->
            val o = a.getJSONObject(i)
            val keys = o.getJSONArray("keys")
            PoseTrack(o.getString("bone"), List(keys.length()) { k ->
                val v = keys.getJSONArray(k)
                PoseKey(v.getDouble(0).toFloat(), v.getDouble(1).toFloat(), v.getDouble(2).toFloat(), v.getDouble(3).toFloat(),
                    Ease.of(v.optString(4, "s")))
            })
        }

        private fun weightKeys(a: JSONArray): List<WeightKey> = List(a.length()) { i ->
            val v = a.getJSONArray(i)
            WeightKey(v.getDouble(0).toFloat(), v.getDouble(1).toFloat(), Ease.of(v.optString(2, "s")))
        }

        fun fromJson(o: JSONObject): CreatureStage {
            require(o.optInt("v", 0) == 1) { "Creature stage table version ${o.optInt("v", 0)} is not supported" }
            val bones = o.getJSONObject("creatureBones")
            val place = o.getJSONObject("placement")
            val person = o.getJSONObject("person")
            val r = o.getJSONObject("reach")
            val stroke = r.optJSONObject("stroke")
            val duration = o.getDouble("duration").toFloat()
            require(duration.isFinite() && duration > 0f) { "A stage needs a duration" }
            return CreatureStage(
                id = o.getString("id"), name = o.getString("name"), creature = o.getString("creature"),
                duration = duration, speed = o.optDouble("speed", 1.0).toFloat(), amount = o.optDouble("amount", 1.0).toFloat(),
                fps = o.optInt("fps", 15),
                personAt = floats(place.getJSONArray("person")), creatureAt = floats(place.getJSONArray("creature")),
                creatureBones = bones.keys().asSequence().associateWith { bones.getInt(it) },
                creatureTracks = poseTracks(o.getJSONArray("creatureTracks")),
                creatureFreeze = List(o.getJSONArray("creatureFreeze").length()) { o.getJSONArray("creatureFreeze").getString(it) },
                creatureHold = weightKeys(o.getJSONArray("creatureHold")),
                personTracks = poseTracks(person.getJSONArray("tracks")),
                reach = ReachSpec(
                    left = person.optString("hand", "R") == "L", bone = r.getInt("bone"), offset = floats(r.getJSONArray("offset")),
                    along = floats(r.getJSONArray("along")), pole = floats(r.getJSONArray("pole")),
                    weight = weightKeys(r.getJSONArray("weight")),
                    stroke = stroke?.let {
                        Stroke(floats(it.getJSONArray("axis")), it.getDouble("amplitude").toFloat(), it.getDouble("period").toFloat(),
                            it.getDouble("from").toFloat(), it.getDouble("to").toFloat())
                    },
                ),
            ).also { s ->
                require(s.personAt.size == 3 && s.creatureAt.size == 3) { "A slot is x, y and the facing" }
                require(s.reach.offset.size == 3 && s.reach.along.size == 3 && s.reach.pole.size == 3) { "A reach vector has three numbers" }
                for (f in s.creatureFreeze) require(f in s.creatureBones) { "Held bone $f is not in creatureBones" }
                for (t in s.creatureTracks) require(t.bone in s.creatureBones) { "Track ${t.bone} is not in creatureBones" }
            }
        }
    }
}

/** A stroking motion of the hand along the head: the offset swings by [amplitude] along [axis] (model units, the head bone's axes), a sine of [period] seconds, between [from] and [to] (eased in and out over 0.8 s). */
class Stroke(val axis: List<Float>, val amplitude: Float, val period: Float, val from: Float, val to: Float)

/**
 * Where the person's hand goes on the creature: [left] or right hand, [bone] (an index of the creature's model) plus [offset]
 * from its pivot (model units, the model's own axes at rest; the creature's scale is the engine's), the fingers lying [along] a
 * direction (the bone's axes) and the elbow bending toward [pole] (the person's axes); [weight] 0..1 over the loop is how far
 * the arm is on it (0: as the clip posed it), [stroke] the hand's stroking.
 */
class ReachSpec(
    val left: Boolean,
    val bone: Int,
    val offset: List<Float>,
    val along: List<Float>,
    val pole: List<Float>,
    val weight: List<WeightKey>,
    val stroke: Stroke?,
) {
    /** The point's offset from the bone's pivot at [t] authored seconds of a loop of [duration]: [offset] plus the stroke. */
    fun offsetAt(t: Float, duration: Float): FloatArray {
        val o = floatArrayOf(offset[0], offset[1], offset[2])
        val s = stroke ?: return o
        if (t < s.from || t > s.to || s.period <= 0f) return o
        val env = minOf(1f, (t - s.from) / EASE_S, (s.to - t) / EASE_S).coerceIn(0f, 1f)
        val k = s.amplitude * env * sin(2.0 * PI * (t - s.from) / s.period).toFloat()
        for (i in 0..2) o[i] += s.axis[i] * k
        return o
    }

    private companion object { const val EASE_S = 0.8f }
}

/** The creature stages. */
object DragonStages {
    /**
     * Petting Sindragosa's head: the person stands facing her, she lowers her head to them and tilts into the hand on it, the
     * hand strokes along the bridge of her muzzle; ten seconds a loop, her neck easing down and back. The table, tuned by
     * rendering (engine-test: the hand's distance to her skinned head every frame).
     */
    val PET_HEAD_JSON = """{
 "v": 1, "id": "sindragosa_pet_head", "name": "Petting Sindragosa's head", "creature": "sindragosa",
 "duration": 10.0, "speed": 1.0, "amount": 1.0, "fps": 15,
 "placement": {"person": [0, 0, 0], "creature": [2.7, -0.25, 180]},
 "creatureBones": {"neck1": 21, "neck2": 24, "neck3": 31, "head": 42, "jaw": 64, "torso1": 2, "torso2": 15},
 "creatureFreeze": ["torso1", "torso2", "neck1", "neck2", "neck3", "head", "jaw"],
 "creatureHold": [[0, 0, "s"], [1.4, 1, "s"], [8.6, 1, "s"], [10, 0, "s"]],
 "creatureTracks": [
  {"bone": "neck1", "keys": [[0, 0.0, 0.0, 0.0, "s"], [1.8, 9.1, 0.0, 0.0, "s"], [3.4, 9.75, 0.0, 0.0, "s"], [5.2, 8.45, 0.0, 0.0, "s"], [6.8, 9.75, 0.0, 0.0, "s"], [8.2, 9.1, 0.0, 0.0, "s"], [10, 0.0, 0.0, 0.0, "s"]]},
  {"bone": "neck2", "keys": [[0, 0.0, 0.0, 0.0, "s"], [1.8, 9.1, 0.0, 0.0, "s"], [3.4, 9.75, 0.0, 0.0, "s"], [5.2, 8.45, 0.0, 0.0, "s"], [6.8, 9.75, 0.0, 0.0, "s"], [8.2, 9.1, 0.0, 0.0, "s"], [10, 0.0, 0.0, 0.0, "s"]]},
  {"bone": "neck3", "keys": [[0, 0.0, 0.0, 0.0, "s"], [1.8, 7.8, 0.0, 0.0, "s"], [3.4, 8.45, 0.0, 0.0, "s"], [5.2, 7.15, 0.0, 0.0, "s"], [6.8, 8.45, 0.0, 0.0, "s"], [8.2, 7.8, 0.0, 0.0, "s"], [10, 0.0, 0.0, 0.0, "s"]]},
  {"bone": "head", "keys": [[0, 0.0, 0.0, 0.0, "s"], [1.8, -5.2, 0.0, 3.9, "s"], [3.4, -6.5, 0.0, 5.85, "s"], [5.2, -3.9, 0.0, 3.9, "s"], [6.8, -6.5, 0.0, 5.85, "s"], [8.2, -5.2, 0.0, 3.9, "s"], [10, 0.0, 0.0, 0.0, "s"]]},
  {"bone": "jaw", "keys": [[0, 0.0, 0.0, 0.0, "s"], [2.4, 0.0, 0.0, 0.0, "s"], [4.0, -1.3, 0.0, 0.0, "s"], [8.0, -1.3, 0.0, 0.0, "s"], [10, 0.0, 0.0, 0.0, "s"]]}
 ],
 "person": {"hand": "R", "tracks": [
  {"bone": "Spine1", "keys": [[0, 0.0, 0.0, 0.0, "s"], [1.8, 3.9, 0.0, 0.0, "s"], [8.2, 3.9, 0.0, 0.0, "s"], [10, 0.0, 0.0, 0.0, "s"]]},
  {"bone": "Spine2", "keys": [[0, 0.0, 0.0, 0.0, "s"], [1.8, 3.25, 0.0, 0.0, "s"], [8.2, 3.25, 0.0, 0.0, "s"], [10, 0.0, 0.0, 0.0, "s"]]},
  {"bone": "Neck1", "keys": [[0, 0.0, 0.0, 0.0, "s"], [1.8, -5.2, 0.0, 0.0, "s"], [8.2, -5.2, 0.0, 0.0, "s"], [10, 0.0, 0.0, 0.0, "s"]]},
  {"bone": "Head", "keys": [[0, 0.0, 0.0, 0.0, "s"], [1.8, -5.2, 0.0, 0.0, "s"], [8.2, -5.2, 0.0, 0.0, "s"], [10, 0.0, 0.0, 0.0, "s"]]},
  {"bone": "L_UpperArm", "keys": [[0, 0.0, 0.0, 0.0, "s"], [1.8, 0.0, 0.0, -22.75, "s"], [8.2, 0.0, 0.0, -22.75, "s"], [10, 0.0, 0.0, 0.0, "s"]]},
  {"bone": "R_Index1", "keys": [[0, 0.0, 0.0, 0.0, "s"], [1.4, 0.0, 0.0, -16.9, "s"], [8.6, 0.0, 0.0, -16.9, "s"], [10, 0.0, 0.0, 0.0, "s"]]},
  {"bone": "R_Index2", "keys": [[0, 0.0, 0.0, 0.0, "s"], [1.4, 0.0, 0.0, -9.1, "s"], [8.6, 0.0, 0.0, -9.1, "s"], [10, 0.0, 0.0, 0.0, "s"]]},
  {"bone": "R_Middle1", "keys": [[0, 0.0, 0.0, 0.0, "s"], [1.4, 0.0, 0.0, -16.9, "s"], [8.6, 0.0, 0.0, -16.9, "s"], [10, 0.0, 0.0, 0.0, "s"]]},
  {"bone": "R_Middle2", "keys": [[0, 0.0, 0.0, 0.0, "s"], [1.4, 0.0, 0.0, -9.1, "s"], [8.6, 0.0, 0.0, -9.1, "s"], [10, 0.0, 0.0, 0.0, "s"]]},
  {"bone": "R_Ring1", "keys": [[0, 0.0, 0.0, 0.0, "s"], [1.4, 0.0, 0.0, -16.9, "s"], [8.6, 0.0, 0.0, -16.9, "s"], [10, 0.0, 0.0, 0.0, "s"]]},
  {"bone": "R_Ring2", "keys": [[0, 0.0, 0.0, 0.0, "s"], [1.4, 0.0, 0.0, -9.1, "s"], [8.6, 0.0, 0.0, -9.1, "s"], [10, 0.0, 0.0, 0.0, "s"]]},
  {"bone": "R_Pinky1", "keys": [[0, 0.0, 0.0, 0.0, "s"], [1.4, 0.0, 0.0, -16.9, "s"], [8.6, 0.0, 0.0, -16.9, "s"], [10, 0.0, 0.0, 0.0, "s"]]},
  {"bone": "R_Pinky2", "keys": [[0, 0.0, 0.0, 0.0, "s"], [1.4, 0.0, 0.0, -9.1, "s"], [8.6, 0.0, 0.0, -9.1, "s"], [10, 0.0, 0.0, 0.0, "s"]]}
 ]},
 "reach": {"bone": 42, "offset": [2.6, 0, 1.0], "along": [-1, 0, 0.4], "pole": [-0.35, -0.8, -0.5], "weight": [[0, 0, "s"], [1.8, 1, "s"], [8.2, 1, "s"], [10, 0, "s"]], "stroke": {"axis": [1, 0, -1.1], "amplitude": 0.45, "period": 1.8, "from": 2.4, "to": 7.8}}
}"""

    val PET_HEAD: CreatureStage by lazy { CreatureStage.parse(PET_HEAD_JSON) }

    val ALL: List<CreatureStage> get() = listOf(PET_HEAD)

    /** The stages' partner animations (the screen offers and plays them with the library's). */
    fun pairs(): List<PairAnim> = ALL.map { it.pair() }

    /** The stage whose pair id is [pairId], or null. */
    fun of(pairId: String?): CreatureStage? = if (pairId == null) null else ALL.firstOrNull { it.id == pairId }

    /** The stage a playback key ("pair:<id>:<role>", [com.logie.wifecraft.simulation.PlaybackKeys.pair]) names, and the role. */
    fun ofKey(key: String): Pair<CreatureStage, Int>? {
        if (!key.startsWith("pair:")) return null
        val rest = key.removePrefix("pair:")
        val cut = rest.lastIndexOf(':')
        if (cut < 0) return null
        val role = rest.substring(cut + 1).toIntOrNull() ?: return null
        return of(rest.substring(0, cut))?.let { it to role }
    }
}
