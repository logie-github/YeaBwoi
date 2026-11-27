package com.logie.wifecraft.simulation

import com.logie.wifecraft.anim.Anim
import com.logie.wifecraft.anim.BodyGeometry
import com.logie.wifecraft.anim.BoneTrack
import com.logie.wifecraft.anim.ClipInspection
import com.logie.wifecraft.anim.ClipType
import com.logie.wifecraft.sims.Channel
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.sin

// Pure Kotlin (JVM-tested: SecretModelTest): what the hidden screen (ui/SecretScene.kt) decides. Her light, the settings kept on the phone, who is
// shown, which kneeling pose she takes and how far she is lowered so her knees rest on the floor of the void. Nothing here touches Android.

/** The three lights of the void (the engine has one directional light and an ambient one: the key is the sun, the fill is the ambient). */
enum class SecretLight(val label: String, private val sun: FloatArray, private val sunColour: FloatArray, private val ambient: FloatArray) {
    /** A key from her front left and above, a faint rim from behind her on the other side comes with the ambient. */
    STUDIO("Studio", floatArrayOf(-0.55f, -0.35f, -0.75f), floatArrayOf(0.95f, 0.91f, 0.86f), floatArrayOf(0.34f, 0.35f, 0.38f)),
    /** Soft: a low key and a strong fill. */
    SOFT("Soft", floatArrayOf(-0.45f, 0.1f, -0.55f), floatArrayOf(0.55f, 0.53f, 0.5f), floatArrayOf(0.55f, 0.55f, 0.58f)),
    /** The key from behind her: an edge of light on the hair and shoulders, the front from the fill. */
    RIM("Rim", floatArrayOf(0.6f, -0.45f, -0.5f), floatArrayOf(1.0f, 0.96f, 0.9f), floatArrayOf(0.30f, 0.31f, 0.34f));

    /** The engine's light array (SceneView.light: 3 sky top, 3 horizon, 3 below, 3 sun direction, 3 sun colour, 3 ambient, 3 fog colour, fog start, fog end): a dark neutral void. */
    fun values(): FloatArray {
        val out = ArrayList<Float>(23)
        for (c in VOID_TOP) out += c
        for (c in VOID_HORIZON) out += c
        for (c in VOID_BELOW) out += c
        for (c in sun) out += c
        for (c in sunColour) out += c
        for (c in ambient) out += c
        for (c in VOID_HORIZON) out += c
        out += 600f; out += 4000f
        return out.toFloatArray()
    }

    fun next(): SecretLight = entries[(ordinal + 1) % entries.size]

    companion object {
        val VOID_TOP = floatArrayOf(0.035f, 0.037f, 0.045f)
        val VOID_HORIZON = floatArrayOf(0.115f, 0.118f, 0.13f)
        val VOID_BELOW = floatArrayOf(0.05f, 0.05f, 0.056f)
        fun of(name: String?): SecretLight = entries.firstOrNull { it.name == name } ?: STUDIO
    }
}

/** The hidden screen's own settings, kept on the phone. */
data class SecretSettings(
    val floorShadow: Boolean = true,
    val breathing: Boolean = true,
    val depthOfField: Boolean = true,
    val light: SecretLight = SecretLight.STUDIO,
    /** The character shown last (an NPC's id); null: none yet. */
    val character: String? = null,
) {
    fun toJson(): String = JSONObject().put("floorShadow", floorShadow).put("breathing", breathing).put("dof", depthOfField)
        .put("light", light.name).also { o -> character?.let { o.put("character", it) } }.toString()

    companion object {
        fun parse(text: String?): SecretSettings {
            if (text.isNullOrBlank()) return SecretSettings()
            return try {
                val o = JSONObject(text)
                val d = SecretSettings()
                SecretSettings(o.optBoolean("floorShadow", d.floorShadow), o.optBoolean("breathing", d.breathing), o.optBoolean("dof", d.depthOfField),
                    SecretLight.of(o.optString("light", "")), o.optString("character", "").takeIf { it.isNotBlank() })
            } catch (e: Exception) { SecretSettings() }
        }
    }
}

/** Where the settings are kept (the screen gives it SharedPreferences; tests a map). Never throws. */
interface SecretStore {
    fun read(): String?
    fun write(text: String)
}

class MemorySecretStore(var text: String? = null) : SecretStore {
    override fun read(): String? = text
    override fun write(text: String) { this.text = text }
}

/** Who stands in the void. */
object SecretCast {
    /** One choice: the id, whether it is one of the app's two starters (not the user's own NPC) and whether she is a woman. */
    data class Entry(val id: String, val starter: Boolean, val female: Boolean)

    /**
     * The remembered character when she is still in the [roster]; else the user's own first NPC; else the starter woman; else whoever is first; null when
     * the roster is empty (the screen then makes the default human woman).
     */
    fun pick(roster: List<Entry>, remembered: String?): String? {
        roster.firstOrNull { it.id == remembered }?.let { return it.id }
        roster.firstOrNull { !it.starter }?.let { return it.id }
        roster.firstOrNull { it.starter && it.female }?.let { return it.id }
        return roster.firstOrNull()?.id
    }
}

/** One imported solo clip as the choice reads it. */
data class SecretClip(val id: String, val name: String, val group: String, val participants: Int, val type: ClipType, val seconds: Float)

/** Which kneeling pose she takes. */
enum class KneelSource { IMPORTED, GAME_EMOTE, PROCEDURAL }

class KneelChoice(val source: KneelSource, val clip: SecretClip? = null)

object SecretKneel {
    /** The game's EmoteKneel animation id (data/Stagecraft Emotes). */
    const val EMOTE_ANIM = 75

    private val WORDS = Regex("\\bkneel(?:s|ing)?\\b|\\bknees\\b")
    private val NOT = Regex("all fours|crawl|get up|getup|stand up|standup|rise|to stand|from kneel|kneel to|stagger|sex|pair:|partner")
    private val BEST = Regex("idle|loop|hold|pose|stand by|waiting")

    /** A clip's worth as a held kneeling pose: 0 when it is not one. A one person clip with the word in its name; loops, holds and poses before motions. */
    fun score(c: SecretClip): Int {
        if (c.participants != 1 || c.type == ClipType.INVALID) return 0
        val t = "${c.name} ${c.group} ${c.id}".lowercase()
        if (!WORDS.containsMatchIn(t) || NOT.containsMatchIn(t)) return 0
        var s = 10
        if (BEST.containsMatchIn(t)) s += 4
        if (c.type == ClipType.POSE) s += 3 else if (c.type == ClipType.LOOP) s += 2
        return s
    }

    /**
     * The pose by the order: an imported kneeling clip when the library has one; else the game's kneel emote only where it is known to hold ([emoteHolds]:
     * the emote is a one-shot of the model's own files, not verified on a phone, so the screen says false); else the pose built from the skeleton.
     */
    fun choose(library: List<SecretClip>, emoteHolds: Boolean = false, modelHasEmote: Boolean = false): KneelChoice {
        val best = library.filter { score(it) > 0 }.maxWithOrNull(compareBy<SecretClip> { score(it) }.thenBy { -it.seconds }.thenBy { it.id })
        if (best != null) return KneelChoice(KneelSource.IMPORTED, best)
        if (emoteHolds && modelHasEmote) return KneelChoice(KneelSource.GAME_EMOTE)
        return KneelChoice(KneelSource.PROCEDURAL)
    }

    /**
     * The clip as she holds it: a loop plays on, a pose stays, a one-shot is taken at its last moment and held there ([Anim.duration] 0: a pose), so
     * she never stands up again.
     */
    fun held(anim: Anim, type: ClipType = ClipInspection.inspect(anim).type): Anim = when (type) {
        ClipType.POSE -> anim.copy(duration = 0f, loop = false)
        ClipType.LOOP -> anim.copy(loop = true)
        else -> lastFrame(anim)
    }

    /** [anim] reduced to the last key of every track (a still pose). */
    fun lastFrame(anim: Anim): Anim = anim.copy(duration = 0f, loop = false, tracks = anim.tracks.mapNotNull { t ->
        val per = if (t.channel == Channel.ROTATION) 4 else 3
        val n = t.times.size
        if (n == 0 || t.values.size != n * per) null
        else BoneTrack(t.bone, t.channel, floatArrayOf(0f), t.values.copyOfRange((n - 1) * per, n * per))
    })

    private fun rot(axis: Int, degrees: Float): FloatArray {
        val h = Math.toRadians(degrees.toDouble()).toFloat() / 2f
        val s = sin(h)
        return floatArrayOf(if (axis == 0) s else 0f, if (axis == 1) s else 0f, if (axis == 2) s else 0f, cos(h))
    }

    /**
     * The kneeling pose built from the skeleton (no clip needed): upright, hips over the knees, the shins folded back along the floor with the feet
     * flat on it, the arms down with the hands on the thighs. Axes as BodyGeometry has them (x forward, y left, z up; a turn about y folds a
     * hanging leg backward, one about x swings an arm down).
     */
    fun procedural(): Anim {
        fun track(bone: String, axis: Int, deg: Float) = BoneTrack(bone, Channel.ROTATION, floatArrayOf(0f), rot(axis, deg))
        val tracks = listOf(
            track("L_Calf", 1, 81f), track("R_Calf", 1, 81f),
            track("L_Foot", 1, 79f), track("R_Foot", 1, 79f),
            track("L_UpperArm", 0, -78f), track("R_UpperArm", 0, 78f),
            track("L_Forearm", 1, -32f), track("R_Forearm", 1, -32f),
            // a little forward lean of the spine and the head up, so she sits tall and looks ahead
            track("Spine1", 1, -3f), track("Head", 1, 3f),
        )
        return Anim("secret:kneel", "Kneel", "Secret", 0f, false, tracks, "procedural", 0L)
    }

    /**
     * How far (body units: one is her standing pelvis height) she is lowered so the lowest flesh of the pose meets the floor; 0 when the pose gives
     * nothing to read. Her world drop is this times the body unit (SceneSimulation.HUMAN_BODY_UNIT and her size).
     */
    fun floorDrop(anim: Anim): Float {
        val frames = try { BodyGeometry.frames(anim) } catch (e: Exception) { emptyList() }
        if (frames.isEmpty()) return 0f
        return FurnitureAvailability.floorDrop(listOf(frames)).coerceIn(0f, 1f)
    }
}

/** The void: what is and is not there, and the limits of her camera. */
object SecretVoid {
    /** Nothing but her: no place, no others, no music of a place, no clock or feed. */
    const val LOCATION = ""
    const val OTHERS = 0
    const val SOUND_SILENT = true

    /** The shot she starts in: her whole body from the front. */
    val START_VIEW = InteractView.FULL

    /** How far the view may go from her, in body heights (InteractZoom's limits). */
    fun distanceRange(): ClosedFloatingPointRange<Float> = InteractZoom.MIN_DISTANCE..InteractZoom.MAX_DISTANCE

    /**
     * The sharp band of depth of field round her at [distance] (body heights) for a body of [height]: near, far (world units) and the blur's strength.
     * Off: a band that holds everything (no blur), not the renderer's own band, which would blur her in an empty void.
     */
    fun focusBand(on: Boolean, distance: Float, height: Float): Triple<Float, Float, Float> {
        if (!on || !(height > 0f)) return Triple(0.05f, 100000f, 0f)
        val d = distance * height
        return Triple(maxOf(0.05f, d - 0.75f * height), d + 0.75f * height, 0.7f)
    }

    /** The disc of her floor shadow: its radius in body heights, and how dark (0..1) at its middle. */
    const val SHADOW_RADIUS = 0.34f
    const val SHADOW_DARK = 0.55f

    /** The breathing's strength when on: tiny. */
    fun breathing(on: Boolean): Float = if (on) 0.5f else 0f
}
