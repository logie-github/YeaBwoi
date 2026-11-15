package com.logie.wifecraft.anim

import org.json.JSONArray
import org.json.JSONObject

/**
 * How a Sims 4 skeleton's animation becomes a WoW character's ([Retargeter]): the data file
 * assets/sims_profile.json, read by [load]. Everything that is a choice about the two rigs lives in
 * the file, so a better mapping is an edit there, not a code change.
 *
 * - [axes]: where each Sims axis goes in WoW's model space ("+z" etc. for Sims x, y, z). Sims 4 is
 *   y up, facing +z; WoW's models are z up, facing +x, their left +y. Must be a rotation (no mirror).
 * - [positionScale]: Sims metres to WoW model units for the root's movement.
 * - [bones]: Sims rig bone name -> WoW-side bone name (the names the engine resolves, tools/wow_bones.py),
 *   with the WoW parent the local rotations are taken against, the WoW child it aims at (rest-pose
 *   conversion, [wowRest]), per-bone sign flips of the rotation's axis, and whether its position moves
 *   the WoW bone (the root alone, as a rule).
 * - [drop]: Sims bones never converted (slots, IK targets, dangles, the ROOT bind), as globs (`*` any run).
 * - [wowRest]: a reference WoW skeleton's rest direction (bone pivot to its aim's pivot, model space,
 *   night elf female 921844), so the Sims bind pose lands on the M2 rest pose.
 * - [rootHeight]: the reference WoW skeleton's Root pivot height (model units, 921844: 1.441). When > 0, the
 *   root's movement is scaled by rootHeight / the Sims root bone's bind height instead of [positionScale]; the
 *   engine scales again by each model's own Root height over [REFERENCE_ROOT_HEIGHT].
 */
class Profile(
    val version: Int,
    val name: String,
    val axes: Array<String>,
    val positionScale: Float,
    val bones: List<BoneRule>,
    val drop: List<String>,
    val wowRest: Map<String, FloatArray>,
    /** "absolute": a clip's keys are the bone's local transform (Sims 4's CLIPs); "bindRelative": offsets from the bind pose. */
    val trackSpace: String = "absolute",
    val rootHeight: Float = 0f,
) {
    data class BoneRule(
        val sims: String,
        val wow: String,
        val wowParent: String?,
        val aim: String?,
        val flip: String,
        val position: Boolean,
    )

    private val dropRegex = drop.map { glob ->
        Regex(glob.split('*').joinToString(".*") { Regex.escape(it) }, RegexOption.IGNORE_CASE)
    }
    private val bySims = bones.associateBy { it.sims.lowercase() }
    private val byWow = bones.associateBy { it.wow }

    fun rule(simsBone: String): BoneRule? = bySims[simsBone.lowercase()]
    fun wowRule(wowBone: String): BoneRule? = byWow[wowBone]
    fun dropped(simsBone: String): Boolean = dropRegex.any { it.matches(simsBone) }

    /** The rotation taking Sims axes to WoW's, as a 3x3 row-major matrix of -1/0/1. */
    fun axisMatrix(): FloatArray {
        val m = FloatArray(9)
        for (s in 0 until 3) {
            val a = axes[s]
            val w = "xyz".indexOf(a.last())
            m[w * 3 + s] = if (a.startsWith("-")) -1f else 1f
        }
        return m
    }

    companion object {
        const val VERSION = 1
        /** The Root pivot height of the skeleton positions are given for (night elf female 921844); cpp m2 kAnimRootHeight. */
        const val REFERENCE_ROOT_HEIGHT = 1.441f

        fun load(json: String): Profile {
            val o = JSONObject(json)
            val v = o.optInt("version", 0)
            require(v in 1..VERSION) { "Sims profile version $v is not supported (this app reads version $VERSION)" }
            val ax = o.getJSONObject("axes")
            val axes = arrayOf(ax.getString("x"), ax.getString("y"), ax.getString("z"))
            for (a in axes) require(Regex("[+-]?[xyz]").matches(a)) { "Sims profile: axis '$a' is not one of +x..-z" }
            require(axes.map { it.last() }.toSet().size == 3) { "Sims profile: the axes must use x, y and z once each" }
            val bones = ArrayList<BoneRule>()
            val arr = o.getJSONArray("bones")
            for (i in 0 until arr.length()) {
                val b = arr.getJSONObject(i)
                bones.add(BoneRule(
                    sims = b.getString("sims"), wow = b.getString("wow"),
                    wowParent = b.optString("parent", "").ifEmpty { null },
                    aim = b.optString("aim", "").ifEmpty { null },
                    flip = b.optString("flip", ""),
                    position = b.optBoolean("position", false),
                ))
            }
            val names = bones.map { it.wow }.toSet()
            for (b in bones) {
                require(b.wowParent == null || b.wowParent in names) { "Sims profile: ${b.wow}'s parent ${b.wowParent} is not a mapped bone" }
                require(b.flip.all { it in "xyz" }) { "Sims profile: ${b.wow}'s flip '${b.flip}' may hold only x, y, z" }
            }
            require(bones.map { it.wow }.distinct().size == bones.size) { "Sims profile: a WoW bone is mapped twice" }
            val rest = HashMap<String, FloatArray>()
            o.optJSONObject("wowRest")?.let { r ->
                for (k in r.keys()) {
                    val a: JSONArray = r.getJSONArray(k)
                    rest[k] = floatArrayOf(a.getDouble(0).toFloat(), a.getDouble(1).toFloat(), a.getDouble(2).toFloat())
                }
            }
            val drop = o.optJSONArray("drop")?.let { d -> List(d.length()) { d.getString(it) } } ?: emptyList()
            val space = o.optString("trackSpace", "absolute")
            require(space == "absolute" || space == "bindRelative") { "Sims profile: trackSpace '$space' is not absolute or bindRelative" }
            val p = Profile(v, o.optString("name", ""), axes, o.optDouble("positionScale", 1.0).toFloat(), bones, drop, rest, space,
                o.optDouble("rootHeight", 0.0).toFloat())
            val m = p.axisMatrix()
            val det = m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) + m[2] * (m[3] * m[7] - m[4] * m[6])
            require(det > 0) { "Sims profile: the axes mirror the skeleton (a left-right flip); swap one sign" }
            for (b in bones) require(!p.dropped(b.sims)) { "Sims profile: ${b.sims} is mapped to ${b.wow} but also matches a drop pattern" }
            return p
        }
    }
}
