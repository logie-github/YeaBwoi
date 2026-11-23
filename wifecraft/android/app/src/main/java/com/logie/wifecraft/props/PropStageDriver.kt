package com.logie.wifecraft.props

import com.logie.wifecraft.pack.PackKind
import kotlin.math.cos
import kotlin.math.sin

/** What the engine is given each frame: object ids and, for each, 16 floats of a column-major matrix. */
class PropFrame(val ids: LongArray, val matrices: FloatArray) {
    val count: Int get() = ids.size

    companion object { val NONE = PropFrame(LongArray(0), FloatArray(0)) }
}

/**
 * Where the props of the stage that plays are, frame by frame. The screen tells it what each person plays ([play]: the Sims
 * clip's name and when it started on the render clock) and where each stands ([place]); [frame] finds the stage whose actors' clips
 * are all playing ([PropStore.usesFor]), takes the first actor's origin as the animation's frame (their clips share it: the actors
 * stand where their own clips put them, offsets aside) and puts each prop of the stage at that frame moved by its own clip's root
 * track at the time into the cycle ([PropClip.sample], looped), turned to the actor's facing and scaled by [scale] (model units
 * per Sims metre). A prop with no clip stays at the origin. Props whose kind is switched off ([shows]) and ids with no model
 * ([PropStore.has]) are left out. Never touches GL: the matrices go through SceneView.setProps.
 */
class PropStageDriver(
    private val store: PropStore,
    private val shows: (PackKind) -> Boolean = { true },
    private val scale: () -> Float = { DEFAULT_SCALE },
) {
    private class Person(var clip: String? = null, var startMs: Int = 0, var x: Float = 0f, var y: Float = 0f, var z: Float = 0f, var facing: Float = 0f, var placed: Boolean = false)

    private val people = HashMap<Int, Person>()
    private var kinds: Map<Long, PackKind> = emptyMap()
    private var kindsAt = 0L
    private val clips = HashMap<String, PropClip?>()

    /** Person [index] began playing the Sims clip [clipName] (null: stopped) at [startMs] on the render clock. */
    fun play(index: Int, clipName: String?, startMs: Int) {
        val p = people.getOrPut(index) { Person() }
        p.clip = clipName?.takeIf { it.isNotBlank() }
        p.startMs = startMs
    }

    /** Person [index] stands at x, y, z facing [facing] (radians, counter-clockwise from +X). */
    fun place(index: Int, x: Float, y: Float, z: Float, facing: Float) {
        val p = people.getOrPut(index) { Person() }
        p.x = x; p.y = y; p.z = z; p.facing = facing; p.placed = true
    }

    /** The props of what plays at [nowMs]. */
    fun frame(nowMs: Int): PropFrame {
        val playing = people.values.filter { it.clip != null && it.placed }
        if (playing.isEmpty()) return PropFrame.NONE
        refreshKinds()
        val byClip = HashMap<String, Person>()
        for (p in playing) byClip.putIfAbsent(p.clip!!.lowercase(), p)
        val uses = store.usesFor(byClip.keys)
        if (uses.isEmpty()) return PropFrame.NONE
        val ids = ArrayList<Long>()
        val mats = ArrayList<Float>()
        val s = scale()
        for (u in uses) {
            val origin = byClip[u.actorClips.first()] ?: continue
            val clip = u.clip.takeIf { it.isNotEmpty() }?.let { c -> clips.getOrPut(c.lowercase()) { store.clip(c) } }
            val t = ((nowMs - origin.startMs).coerceAtLeast(0)) / 1000f
            val state = clip?.let { c -> c.sample(if (c.duration > 0f) t % c.duration else 0f) } ?: floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 1f)
            for (g in u.guids) {
                if (!store.has(g)) continue
                if (kinds[g]?.let { shows(it) } == false) continue
                ids += g
                mats.addAll(matrix(origin.x, origin.y, origin.z, origin.facing, state, s).toList())
            }
        }
        return if (ids.isEmpty()) PropFrame.NONE else PropFrame(ids.toLongArray(), mats.toFloatArray())
    }

    fun clear() { people.clear(); clips.clear() }

    private fun refreshKinds() {
        val stamp = System.nanoTime() / 5_000_000_000L
        if (stamp == kindsAt && kinds.isNotEmpty()) return
        kindsAt = stamp
        kinds = store.index().associate { it.guid to it.kind }
    }

    companion object {
        /** Model units per Sims metre of the Sims adult skeleton (anim/Retargeter.unitsPerMetre: 1.4233 on the shipped profile). */
        const val DEFAULT_SCALE = 1.4233f

        /**
         * The column-major matrix of a prop: [origin] x, y, z turned by [facing] about up, then the clip's root [state] (position x,
         * y, z and rotation x, y, z, w, in Warcraft axes, Sims metres) moved by [scale], then the mesh scaled by [scale].
         */
        fun matrix(ox: Float, oy: Float, oz: Float, facing: Float, state: FloatArray, scale: Float): FloatArray {
            val qx = state[3]; val qy = state[4]; val qz = state[5]; val qw = state[6]
            // rotation of the clip's root (a unit quaternion) as a 3 x 3
            val r = floatArrayOf(
                1 - 2 * (qy * qy + qz * qz), 2 * (qx * qy - qz * qw), 2 * (qx * qz + qy * qw),
                2 * (qx * qy + qz * qw), 1 - 2 * (qx * qx + qz * qz), 2 * (qy * qz - qx * qw),
                2 * (qx * qz - qy * qw), 2 * (qy * qz + qx * qw), 1 - 2 * (qx * qx + qy * qy),
            )
            val c = cos(facing); val sn = sin(facing)
            // facing: rotation about z. world = T(origin) * Rz(facing) * [T(p * scale) * R * S(scale)]
            val m = FloatArray(16)
            for (col in 0 until 3) {
                val rx = r[col]; val ry = r[3 + col]; val rz = r[6 + col]   // column `col` of R
                m[col * 4] = (c * rx - sn * ry) * scale
                m[col * 4 + 1] = (sn * rx + c * ry) * scale
                m[col * 4 + 2] = rz * scale
            }
            val px = state[0] * scale; val py = state[1] * scale; val pz = state[2] * scale
            m[12] = ox + c * px - sn * py
            m[13] = oy + sn * px + c * py
            m[14] = oz + pz
            m[15] = 1f
            return m
        }
    }
}
