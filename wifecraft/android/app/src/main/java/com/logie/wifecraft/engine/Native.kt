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
    /**
     * Changes a standing actor's whole look in place (the creator calls it per tap); its place,
     * facing and animation stay. What the call leaves out is gone ([attach]'s items stay).
     *
     * - [geosetKeys]/[geosetValues]: geoset group -> value, as [addActor] takes them (empty: every geoset).
     * - [layerFiles]: one texture file id per skin layer (ChrCustomizationMaterial's), composed on the GPU
     *   into one texture per texture type, in layer order; a type that is one whole-material blit layer
     *   is bound as the file itself. A layer whose file isn't downloaded yet joins once it is there.
     * - [layerMeta]: [LAYER_META] ints per layer: texture type, layer, blend mode (ChrModelTextureLayer's
     *   TextureType, Layer, BlendMode), then its section x, y, width, height in the material's pixels
     *   (CharComponentTextureSections for the first section in TextureSectionTypeBitMask; width or
     *   height 0, as for a mask of -1, means the whole material), then the material's width and height
     *   (ChrModelMaterial for the layout and texture type).
     * - [tintColours]: [LAYER_TINTS] ints per layer, 0xAARRGGBB, alpha 0 for none: the first alone
     *   multiplies the layer's colour; with the second, the layer's colour maps second (dark) to first (light).
     * - [extraModels]: [EXTRA] ints per model: attachment point (-1: skinned to the body's bones, a
     *   ChrCustomizationSkinnedModel's collection model, showing only the geosets the look's groups set),
     *   model file id, texture file id for its replaceable slots the body's textures don't fill (0: none).
     */
    @JvmStatic external fun dress(
        scene: Long, actor: Int, geosetKeys: IntArray, geosetValues: IntArray,
        layerFiles: IntArray, layerMeta: IntArray, tintColours: IntArray, extraModels: IntArray,
    )

    /** Ints per layer in [dress]'s layerMeta, per layer in its tintColours, per model in its extraModels. */
    const val LAYER_META = 9
    const val LAYER_TINTS = 2
    const val EXTRA = 3

    /** A tint for [dress]'s tintColours: 0xRRGGBB (null: none). */
    @JvmStatic fun tint(rgb: Int?): Int = if (rgb == null) 0 else (0xFF000000.toInt() or (rgb and 0xFFFFFF))

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
