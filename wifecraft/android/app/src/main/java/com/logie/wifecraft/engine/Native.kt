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
    /** Whether the city is drawn and stood on (false: the studio and the creator, people alone in the void; it stays loaded). */
    @JvmStatic external fun showCity(scene: Long, shown: Boolean)
    @JvmStatic external fun addActor(
        scene: Long, model: Int, texKeys: IntArray, texValues: IntArray,
        geoKeys: IntArray, geoValues: IntArray, scale: Float,
    ): Int
    /** Why [addActor] couldn't read [model] the last time (the file, its skin or skeleton, and what of it), "" when it could. */
    @JvmStatic external fun modelError(scene: Long, model: Int): String
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
     *   model file id, texture file id for its replaceable slots the body's textures don't fill (0: none), and the
     *   skinned model's own geoset (group × 100 + id, 0: none), shown on it whatever the groups say.
     */
    @JvmStatic external fun dress(
        scene: Long, actor: Int, geosetKeys: IntArray, geosetValues: IntArray,
        layerFiles: IntArray, layerMeta: IntArray, tintColours: IntArray, extraModels: IntArray,
    )

    /** Ints per layer in [dress]'s layerMeta, per layer in its tintColours, per model in its extraModels. */
    const val LAYER_META = 9
    const val LAYER_TINTS = 2
    const val EXTRA = 4

    /** A tint for [dress]'s tintColours: 0xRRGGBB (null: none). */
    @JvmStatic fun tint(rgb: Int?): Int = if (rgb == null) 0 else (0xFF000000.toInt() or (rgb and 0xFFFFFF))

    /**
     * Wrath++'s body options on a standing actor (a woman of the ten races of 3.3.5), kept until changed ([dress] leaves it):
     *
     * - [morph]: the Shape, [BODY_STRIDE] floats per vertex of Wrath++'s looks model (creator/BodyShapes.load: its place,
     *   how far the shape moves it, normal before and after, texture coordinates before and after); null or empty: as drawn.
     *   Today's model is moved vertex by vertex where it has the looks' vertex, and in between as the nearest ones move.
     * - [k], [fold]: the race's body layer square (BodyShapes.frame); k 0: no layer.
     *
     * The layer's design (Style x Density) comes with the look: [dress]'s layer of texture type 1 at layer 100
     * (LookResolver.BODY_LAYER), which is then not composed into the skin but drawn over the body's front below the navel,
     * as Wrath++'s native mod draws it: on the body's own triangles under the square, lifted off the skin, the hair's
     * colour times the design, alpha blended.
     */
    @JvmStatic external fun body(scene: Long, actor: Int, morph: FloatArray?, k: Float, fold: Float)

    /** Floats per looks vertex in [body]'s morph (creator/BodyShapes.STRIDE, the engine's kBodyMorphStride). */
    const val BODY_STRIDE = 16

    @JvmStatic external fun removeActor(scene: Long, actor: Int)
    @JvmStatic external fun place(scene: Long, actor: Int, x: Float, y: Float, z: Float, facing: Float)
    @JvmStatic external fun anim(scene: Long, actor: Int, anim: Int, loop: Boolean, nowMs: Int)
    @JvmStatic external fun idle(scene: Long, actor: Int, anim: Int)

    /**
     * An imported animation (anim/Anim, as anim/AnimArrays flattens it) over a standing actor's own: track i names
     * the WoW-side bone [bones][i] (resolved in the model by key bone id, bone name CRC or "first child of": cpp m2
     * kWowBones, tools/wow_bones.py), [channels][i] 0 a rotation (x,y,z,w: the bone's local rotation about its pivot,
     * the model's axes at rest), 1 a position (model units for the night elf woman's skeleton, scaled to this model's);
     * [keyCounts][i] its keys; [times] (seconds) and [values] all tracks' keys in order. Those bones and every bone
     * above them follow it (rest where it says nothing); the rest keep the actor's animation. [loop] false: the last
     * key holds (a pose). Kept until [clearBoneAnim]; [dress] keeps it. Returns how many tracks found their bone.
     */
    @JvmStatic external fun boneAnim(
        scene: Long, actor: Int, bones: Array<String>, channels: IntArray, keyCounts: IntArray,
        times: FloatArray, values: FloatArray, duration: Float, loop: Boolean, nowMs: Int,
    ): Int
    @JvmStatic external fun clearBoneAnim(scene: Long, actor: Int)

    /**
     * Options' Soft body for everyone on stage, from the next frame: [SOFT_OFF], [SOFT_SUBTLE], [SOFT_NATURAL] (the
     * default) or [SOFT_STRONG]. The chest, belly, rear, thighs and upper arms hang under gravity and bounce, lag and settle
     * as the body moves (cpp engine/softbody.h: one damped spring per region, 120 Hz, moved in the vertex shader).
     */
    @JvmStatic external fun softBody(scene: Long, level: Int)
    const val SOFT_OFF = 0
    const val SOFT_SUBTLE = 1
    const val SOFT_NATURAL = 2
    const val SOFT_STRONG = 3
    @JvmStatic external fun show(scene: Long, actor: Int, visible: Boolean)
    @JvmStatic external fun height(scene: Long, actor: Int): Float
    @JvmStatic external fun camera(scene: Long, ex: Float, ey: Float, ez: Float, tx: Float, ty: Float, tz: Float, fov: Float)
    @JvmStatic external fun light(scene: Long, values: FloatArray)

    /** [width, height, ARGB...] of a game texture, or null. Any thread. */
    @JvmStatic external fun decodeBlp(path: String, maxSize: Int): IntArray?
}
