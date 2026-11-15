package com.logie.wifecraft.sims

/**
 * One bone of a Sims 4 rig. [hash] is what clips name the bone by (FNV-32 of the lower-cased name);
 * [parent] the parent's index in [SimsRig.bones], -1 for a root. The bind pose is local to the parent,
 * as the rig stores it: [bindRotation] x,y,z,w, [bindPosition] x,y,z.
 */
class RigBone(val hash: Int, val name: String, val parent: Int, val bindRotation: FloatArray, val bindPosition: FloatArray)

/**
 * A Sims 4 rig (RIG resource, type 0x8EAF13DE) in its "clear" layout: u32 major (3 or 4), u32 minor
 * (1 or 2), u32 bone count, then per bone: position (3 f32), orientation (x,y,z,w f32), scale (3 f32),
 * a u32-length name, i32 opposing bone, i32 parent bone, u32 hash, u32 flags. Then (major 4) the
 * skeleton's name and the IK chains, which are not read here. Granny2 rigs (older content) are not read.
 */
class SimsRig(val bones: List<RigBone>) {
    // Clips name bones by FNV-32 of the lower-cased name (confirmed on a pose pack); the rig's own
    // stored hash is expected to be the same, and both are looked up in case a rig stores another.
    private val index: Map<Int, RigBone> = HashMap<Int, RigBone>().also { m ->
        for (b in bones) m.putIfAbsent(SimsHash.fnv32(b.name), b)
        for (b in bones) m[b.hash] = b
    }

    fun byHash(h: Int): RigBone? = index[h]

    /** How many of [hashes] this rig names: the rig that names most of a clip's bones is the clip's rig. */
    fun coverage(hashes: Collection<Int>): Int = hashes.count { it in index }

    companion object {
        const val TYPE = 0x8EAF13DE.toInt()

        fun parse(rigResource: ByteArray): SimsRig {
            val r = Reader(rigResource, "rig")
            if (rigResource.size < 12) r.fail("is only ${rigResource.size} bytes")
            val major = r.u32(0)
            val minor = r.u32(4)
            if (major == TYPE || major !in 3..4 || minor !in 1..2) {
                r.fail("is not in the clear rig layout The Sims 4 uses (it starts $major.$minor; Granny2 rigs are not supported)")
            }
            val count = r.u32(8)
            if (count < 1 || count > 4096) r.fail("claims $count bones; it is damaged")
            var p = 12
            val bones = ArrayList<RigBone>(count)
            for (i in 0 until count) {
                val pos = FloatArray(3) { r.f32(p + it * 4) }
                val rot = FloatArray(4) { r.f32(p + 12 + it * 4) }
                p += 40 // position, orientation, scale
                val (name, after) = r.string32(p)
                p = after
                r.u32(p) // opposing bone
                val parent = r.u32(p + 4)
                val hash = r.u32(p + 8)
                r.u32(p + 12) // flags
                p += 16
                if (parent < -1 || parent >= count) r.fail("gives bone $i ($name) parent $parent of $count bones; it is damaged")
                if ((pos + rot).any { !it.isFinite() }) r.fail("gives bone $i ($name) a non-number bind pose; it is damaged")
                bones += RigBone(hash, name, parent, rot, pos)
            }
            return SimsRig(bones)
        }
    }
}

/** The Sims 4's bone-name hash: FNV-1 32-bit of the name in lower case (e.g. "b__Head__"). */
object SimsHash {
    fun fnv32(s: String): Int {
        var h = 0x811C9DC5.toInt()
        for (c in s.lowercase().toByteArray(Charsets.UTF_8)) h = (h * 0x01000193) xor (c.toInt() and 0xFF)
        return h
    }
}
