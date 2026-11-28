package com.logie.wifecraft.engine

/** Toy interaction's calls into the renderer (cpp/toy_jni.cpp; engine/orificedraw.h). On the GL thread, like [Native]. */
object ToyNative {
    init { Native.hashCode() }   // (loads the library)

    /** Holds prop [guid] on [actor]: [mode] 0 puts it away, 1 free at pixel ([fx], [fy]) of a [w] x [h] view, 2 snapped to the entry at [depth] metres. */
    @JvmStatic external fun setToy(scene: Long, actor: Int, guid: Long, doubleEnded: Boolean, mode: Int, fx: Float, fy: Float, w: Int, h: Int, depth: Float, vibration: Float, roll: Float, hidden: Boolean)
    /** [out] (14 floats): ready, hd, entry x, y, way-in x, y, pixels per metre, insertable length (m), radius (m), wet, depth (m), tip x, y. */
    @JvmStatic external fun toyInfo(scene: Long, out: FloatArray): Boolean
    /** The reactions' pose, 12 floats (see [com.logie.wifecraft.simulation.ToyPoseValues]); null takes the layer off. */
    @JvmStatic external fun setPose(scene: Long, actor: Int, values: FloatArray?)
}
