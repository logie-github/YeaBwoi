package com.logie.wifecraft.graphics

import org.json.JSONObject

// Pure Kotlin (no Android): Options' Graphics page (ui/GraphicsOptions.kt), kept as JSON in the app's preferences
// ("wifecraft", key "graphics": graphics/Graphics.kt), its presets, the governor's steps laid on it (Governor.kt) and the
// float array the renderer takes (Native.graphics; cpp engine/graphics_settings.h, the same order).

/** The page's preset chips. CUSTOM: any switch or slider moved off a preset. */
enum class GraphicsPreset(val label: String) {
    STANDARD("Standard"), HIGH("High"), ULTRA("Ultra"), CUSTOM("Custom");

    companion object {
        fun of(name: String?): GraphicsPreset = entries.firstOrNull { it.name == name } ?: STANDARD
    }
}

/** The tone curve (engine/postfx.cpp): off (clamped, as before), a filmic highlight shoulder (skin kept), ACES fitted. */
enum class ToneCurve(val label: String, val native: Int) {
    OFF("Off", 0), FILMIC("Filmic", 1), ACES("ACES", 2);

    companion object {
        fun of(name: String?): ToneCurve = entries.firstOrNull { it.name == name } ?: OFF
    }
}

/**
 * Options' Graphics page. Percentages are 0..100 (0: off); [renderScale] is a percentage of the view's size (60..125, above
 * 100 supersampled); [exposure] tenths of a stop (-20..20); [msaa] 0, 2 or 4; [anisotropy] 1..16; [textureDetail] 1 or 2;
 * [aoQuality] 0..2 and [shadowQuality] 0..1; [targetFps] 60, 90 or 120. [adaptive]: the governor may lower effects (never
 * the target); [overlay]: the performance readout over the scene.
 */
data class GraphicsSettings(
    val preset: GraphicsPreset = GraphicsPreset.STANDARD,
    val targetFps: Int = 60,
    val adaptive: Boolean = true,
    val overlay: Boolean = false,
    val renderScale: Int = 100,
    val msaa: Int = 0,
    val anisotropy: Int = 1,
    val textureDetail: Int = 1,
    val tone: ToneCurve = ToneCurve.OFF,
    val exposure: Int = 0,
    val bloom: Int = 0,
    val dof: Int = 0,
    val aperture: Int = 50,
    val dofInEditor: Boolean = false,
    val ao: Int = 0,
    val aoQuality: Int = 1,
    val contact: Int = 0,
    val shadows: Int = 0,
    val shadowQuality: Int = 1,
    val skin: Int = 0,
    val hair: Int = 0,
    val shafts: Int = 0,
    val haze: Int = 0,
    val vignette: Int = 0,
    val grain: Int = 0,
    val chroma: Int = 0,
    val sharpen: Int = 0,
) {
    /** Within limits (a hand-edited or old file can't break the renderer). */
    fun clamped(): GraphicsSettings = copy(
        targetFps = TARGETS.minByOrNull { kotlin.math.abs(it - targetFps) } ?: 60,
        renderScale = renderScale.coerceIn(SCALE_MIN, SCALE_MAX),
        msaa = when { msaa >= 4 -> 4; msaa >= 2 -> 2; else -> 0 },
        anisotropy = ANISOTROPY.lastOrNull { it <= anisotropy } ?: 1,
        textureDetail = textureDetail.coerceIn(1, 2),
        exposure = exposure.coerceIn(-20, 20),
        bloom = bloom.coerceIn(0, 100), dof = dof.coerceIn(0, 100), aperture = aperture.coerceIn(0, 100),
        ao = ao.coerceIn(0, 100), aoQuality = aoQuality.coerceIn(0, 2), contact = contact.coerceIn(0, 100),
        shadows = shadows.coerceIn(0, 100), shadowQuality = shadowQuality.coerceIn(0, 1),
        skin = skin.coerceIn(0, 100), hair = hair.coerceIn(0, 100), shafts = shafts.coerceIn(0, 100), haze = haze.coerceIn(0, 100),
        vignette = vignette.coerceIn(0, 100), grain = grain.coerceIn(0, 100), chroma = chroma.coerceIn(0, 100),
        sharpen = sharpen.coerceIn(0, 100),
    )

    /** The effects alone (the preset, the target, the switches about the page itself left out): what a preset sets. */
    fun effects(): GraphicsSettings = copy(preset = GraphicsPreset.STANDARD, targetFps = 60, adaptive = true, overlay = false)

    /** A preset's effects, keeping this page's target, Adaptive quality and overlay. CUSTOM keeps the effects as they are. */
    fun withPreset(p: GraphicsPreset): GraphicsSettings =
        if (p == GraphicsPreset.CUSTOM) copy(preset = p)
        else presetEffects(p).copy(preset = p, targetFps = targetFps, adaptive = adaptive, overlay = overlay)

    /** After a switch or slider moved: the preset whose effects these are, else CUSTOM. */
    fun withMatchingPreset(): GraphicsSettings {
        val mine = effects()
        val match = listOf(GraphicsPreset.STANDARD, GraphicsPreset.HIGH, GraphicsPreset.ULTRA).firstOrNull { presetEffects(it).effects() == mine }
        return copy(preset = match ?: GraphicsPreset.CUSTOM)
    }

    /** The governor's steps that would change something here, in the order it takes them. */
    fun applicableSteps(): List<QualityStep> = QualityStep.entries.filter { it.appliesTo(this) }

    /** These settings with the first [level] applicable steps laid on (the governor's level). */
    fun lowered(level: Int): GraphicsSettings {
        var s = this
        for (step in applicableSteps().take(level.coerceAtLeast(0))) s = step.apply(s)
        return s
    }

    /** The renderer's array (Native.graphics; engine/graphics_settings.h's Index order); [timers]: GPU timer queries on. */
    fun toNative(timers: Boolean): FloatArray {
        val c = clamped()
        val v = FloatArray(NATIVE_COUNT)
        v[0] = c.renderScale / 100f
        v[1] = c.msaa.toFloat()
        v[2] = c.anisotropy.toFloat()
        v[3] = c.textureDetail.toFloat()
        v[4] = c.tone.native.toFloat()
        v[5] = c.exposure / 10f
        v[6] = c.bloom / 100f
        v[7] = c.dof / 100f
        v[8] = c.aperture / 100f
        v[9] = if (c.dofInEditor) 1f else 0f
        v[10] = c.ao / 100f
        v[11] = c.aoQuality.toFloat()
        v[12] = c.contact / 100f
        v[13] = c.shadows / 100f
        v[14] = c.shadowQuality.toFloat()
        v[15] = c.skin / 100f
        v[16] = c.hair / 100f
        v[17] = c.shafts / 100f
        v[18] = c.haze / 100f
        v[19] = c.vignette / 100f
        v[20] = c.grain / 100f
        v[21] = c.chroma / 100f
        v[22] = c.sharpen / 100f
        v[23] = if (timers) 1f else 0f
        return v
    }

    fun toJson(): JSONObject = JSONObject()
        .put("preset", preset.name).put("targetFps", targetFps).put("adaptive", adaptive).put("overlay", overlay)
        .put("renderScale", renderScale).put("msaa", msaa).put("anisotropy", anisotropy).put("textureDetail", textureDetail)
        .put("tone", tone.name).put("exposure", exposure).put("bloom", bloom).put("dof", dof).put("aperture", aperture)
        .put("dofInEditor", dofInEditor).put("ao", ao).put("aoQuality", aoQuality).put("contact", contact)
        .put("shadows", shadows).put("shadowQuality", shadowQuality).put("skin", skin).put("hair", hair)
        .put("shafts", shafts).put("haze", haze).put("vignette", vignette).put("grain", grain).put("chroma", chroma)
        .put("sharpen", sharpen)

    companion object {
        /** engine/graphics_settings.h's kCount. */
        const val NATIVE_COUNT = 24
        const val SCALE_MIN = 60
        const val SCALE_MAX = 125
        val TARGETS = listOf(60, 90, 120)
        val ANISOTROPY = listOf(1, 2, 4, 8, 16)
        val DEFAULT = GraphicsSettings()

        /**
         * A preset's effects. Standard: everything off, the picture exactly as before. High: filmic tone, subtle bloom, depth
         * of field, AO and contact shadows, the key light's shadows (1024), skin and hair shading, 2x MSAA, 8x anisotropic,
         * a light vignette. Ultra: all of it fuller, 4x MSAA, 16x anisotropic, sharper textures, soft shadows (2048),
         * light shafts and haze, supersampled at 125%.
         */
        fun presetEffects(p: GraphicsPreset): GraphicsSettings = when (p) {
            GraphicsPreset.STANDARD, GraphicsPreset.CUSTOM -> GraphicsSettings()
            GraphicsPreset.HIGH -> GraphicsSettings(
                renderScale = 100, msaa = 2, anisotropy = 8, textureDetail = 1, tone = ToneCurve.FILMIC, bloom = 35,
                dof = 50, aperture = 40, ao = 60, aoQuality = 1, contact = 50, shadows = 60, shadowQuality = 0,
                skin = 60, hair = 50, vignette = 25,
            )
            GraphicsPreset.ULTRA -> GraphicsSettings(
                renderScale = 125, msaa = 4, anisotropy = 16, textureDetail = 2, tone = ToneCurve.FILMIC, bloom = 45,
                dof = 70, aperture = 55, ao = 80, aoQuality = 2, contact = 70, shadows = 75, shadowQuality = 1,
                skin = 80, hair = 70, shafts = 50, haze = 30, vignette = 30, sharpen = 25,
            )
        }

        /** Settings from their JSON ([toJson]); null, empty or broken: the defaults; a missing field: its default. */
        fun parse(text: String?): GraphicsSettings {
            if (text.isNullOrBlank()) return DEFAULT
            return try {
                val o = JSONObject(text)
                val d = DEFAULT
                GraphicsSettings(
                    preset = GraphicsPreset.of(o.optString("preset", d.preset.name)),
                    targetFps = o.optInt("targetFps", d.targetFps),
                    adaptive = o.optBoolean("adaptive", d.adaptive),
                    overlay = o.optBoolean("overlay", d.overlay),
                    renderScale = o.optInt("renderScale", d.renderScale),
                    msaa = o.optInt("msaa", d.msaa),
                    anisotropy = o.optInt("anisotropy", d.anisotropy),
                    textureDetail = o.optInt("textureDetail", d.textureDetail),
                    tone = ToneCurve.of(o.optString("tone", d.tone.name)),
                    exposure = o.optInt("exposure", d.exposure),
                    bloom = o.optInt("bloom", d.bloom),
                    dof = o.optInt("dof", d.dof),
                    aperture = o.optInt("aperture", d.aperture),
                    dofInEditor = o.optBoolean("dofInEditor", d.dofInEditor),
                    ao = o.optInt("ao", d.ao),
                    aoQuality = o.optInt("aoQuality", d.aoQuality),
                    contact = o.optInt("contact", d.contact),
                    shadows = o.optInt("shadows", d.shadows),
                    shadowQuality = o.optInt("shadowQuality", d.shadowQuality),
                    skin = o.optInt("skin", d.skin),
                    hair = o.optInt("hair", d.hair),
                    shafts = o.optInt("shafts", d.shafts),
                    haze = o.optInt("haze", d.haze),
                    vignette = o.optInt("vignette", d.vignette),
                    grain = o.optInt("grain", d.grain),
                    chroma = o.optInt("chroma", d.chroma),
                    sharpen = o.optInt("sharpen", d.sharpen),
                ).clamped()
            } catch (e: Exception) { DEFAULT }
        }
    }
}

/**
 * What the governor lowers, in the order it lowers them (and raises them back in reverse): the render scale first, then
 * the AO's and shadows' quality, bloom, depth of field, MSAA, the Ultra extras, the render scale further, AO and shadows
 * altogether, the skin and hair shading last. A step that changes nothing for the settings is skipped.
 */
enum class QualityStep(val label: String) {
    RENDER_SCALE("Render scale to 85%"),
    AO_SHADOW_QUALITY("AO and shadow quality lowered"),
    BLOOM("Bloom off"),
    DOF("Depth of field off"),
    MSAA("MSAA off"),
    SHAFTS_HAZE("Light shafts and haze off"),
    RENDER_SCALE_LOW("Render scale to 70%"),
    AO_SHADOWS("AO and shadows off"),
    SKIN("Skin and hair shading off");

    fun appliesTo(s: GraphicsSettings): Boolean = when (this) {
        RENDER_SCALE -> s.renderScale > 85
        AO_SHADOW_QUALITY -> (s.ao > 0 && s.aoQuality > 0) || (s.shadows > 0 && s.shadowQuality > 0)
        BLOOM -> s.bloom > 0
        DOF -> s.dof > 0
        MSAA -> s.msaa > 0
        SHAFTS_HAZE -> s.shafts > 0 || s.haze > 0
        RENDER_SCALE_LOW -> s.renderScale > 70
        AO_SHADOWS -> s.ao > 0 || s.contact > 0 || s.shadows > 0
        SKIN -> s.skin > 0 || s.hair > 0
    }

    fun apply(s: GraphicsSettings): GraphicsSettings = when (this) {
        RENDER_SCALE -> s.copy(renderScale = minOf(s.renderScale, 85))
        AO_SHADOW_QUALITY -> s.copy(aoQuality = 0, shadowQuality = 0)
        BLOOM -> s.copy(bloom = 0)
        DOF -> s.copy(dof = 0)
        MSAA -> s.copy(msaa = 0)
        SHAFTS_HAZE -> s.copy(shafts = 0, haze = 0)
        RENDER_SCALE_LOW -> s.copy(renderScale = minOf(s.renderScale, 70))
        AO_SHADOWS -> s.copy(ao = 0, contact = 0, shadows = 0)
        SKIN -> s.copy(skin = 0, hair = 0)
    }
}
