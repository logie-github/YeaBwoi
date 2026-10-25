package com.logie.wifecraft.engine

/**
 * The renderer (src/main/cpp). A scene is a handle; every call but [decodeBlp] belongs on the GL
 * thread ([SceneView] runs them there).
 */
object Native {
    init {
        System.loadLibrary("wcengine")
    }

    @JvmStatic external fun create(filesDir: String): Long
    @JvmStatic external fun destroy(scene: Long)
    @JvmStatic external fun initGl(scene: Long)
    @JvmStatic external fun render(scene: Long, width: Int, height: Int, nowMs: Int)
    @JvmStatic external fun loadCity(scene: Long, root: Int, groups: IntArray, centres: FloatArray, radius: Float): Boolean
    @JvmStatic external fun addActor(
        scene: Long, model: Int, texKeys: IntArray, texValues: IntArray,
        geoKeys: IntArray, geoValues: IntArray, scale: Float,
    ): Int
    @JvmStatic external fun attach(scene: Long, actor: Int, attachment: Int, model: Int, texKeys: IntArray, texValues: IntArray)
    @JvmStatic external fun removeActor(scene: Long, actor: Int)
    @JvmStatic external fun place(scene: Long, actor: Int, x: Float, y: Float, z: Float, facing: Float)
    @JvmStatic external fun anim(scene: Long, actor: Int, anim: Int, loop: Boolean, nowMs: Int)
    @JvmStatic external fun idle(scene: Long, actor: Int, anim: Int)
    @JvmStatic external fun show(scene: Long, actor: Int, visible: Boolean)
    @JvmStatic external fun height(scene: Long, actor: Int): Float
    @JvmStatic external fun camera(scene: Long, ex: Float, ey: Float, ez: Float, tx: Float, ty: Float, tz: Float, fov: Float)
    @JvmStatic external fun light(scene: Long, values: FloatArray)

    /** [width, height, ARGB...] of a game texture, or null. Any thread. */
    @JvmStatic external fun decodeBlp(path: String, maxSize: Int): IntArray?
}
