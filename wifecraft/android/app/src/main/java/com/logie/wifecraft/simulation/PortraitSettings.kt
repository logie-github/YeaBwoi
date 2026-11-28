package com.logie.wifecraft.simulation

import org.json.JSONObject

// Pure Kotlin (JVM-tested: PortraitSettingsTest): the look of a person in Interact mode and the hidden mode: the portrait light, the life she
// is given (breathing, weight shifts, idle gestures, reactions), where she looks and how far round she turns to look. Kept on the phone as
// JSON (Interact: the "portrait" key of its preferences; the hidden mode: inside its own settings). The engine's side is cpp/engine/portrait.h
// (the light rig) and cpp/engine/life.h (the life and gaze layer); nothing here touches Android.

/** The portrait light rigs: Natural (default), Soft, Dramatic, Warm. Their numbers live in the engine (portrait.h, [engineId] is the index there). */
enum class PortraitLighting(val label: String, val engineId: Int) {
    NATURAL("Natural", 1), SOFT("Soft", 2), DRAMATIC("Dramatic", 3), WARM("Warm", 4);
    companion object { fun of(name: String?): PortraitLighting = entries.firstOrNull { it.name == name } ?: NATURAL }
}

/** How much she lives: Off (the engine's stand), Subtle, Natural (default), Lively. [scale] multiplies the amplitudes and the gestures' rate. */
enum class LifeLevel(val label: String, val engineId: Int) {
    OFF("Off", 0), SUBTLE("Subtle", 1), NATURAL("Natural", 2), LIVELY("Lively", 3);
    companion object { fun of(name: String?): LifeLevel = entries.firstOrNull { it.name == name } ?: NATURAL }
}

/** Where she looks: Off (the animation's own head), Camera (default), Camera with wander (her eyes leave you now and then). */
enum class GazeMode(val label: String, val engineId: Int) {
    OFF("Off", 0), CAMERA("Camera", 1), WANDER("Camera with wander", 2);
    companion object { fun of(name: String?): GazeMode = entries.firstOrNull { it.name == name } ?: CAMERA }
}

data class PortraitSettings(
    val lighting: PortraitLighting = PortraitLighting.NATURAL,
    val life: LifeLevel = LifeLevel.NATURAL,
    val gaze: GazeMode = GazeMode.CAMERA,
    /** How far round (degrees) she turns her head toward the camera: 20 to 90. */
    val lookAngle: Int = DEFAULT_ANGLE,
) {
    fun withAngle(degrees: Int): PortraitSettings = copy(lookAngle = clampAngle(degrees))

    fun toJson(): JSONObject = JSONObject().put("lighting", lighting.name).put("life", life.name).put("gaze", gaze.name).put("angle", clampAngle(lookAngle))

    companion object {
        const val MIN_ANGLE = 20
        const val MAX_ANGLE = 90
        const val DEFAULT_ANGLE = 70
        fun clampAngle(d: Int): Int = d.coerceIn(MIN_ANGLE, MAX_ANGLE)

        fun fromJson(o: JSONObject?): PortraitSettings {
            if (o == null) return PortraitSettings()
            return PortraitSettings(PortraitLighting.of(o.optString("lighting", "")), LifeLevel.of(o.optString("life", "")),
                GazeMode.of(o.optString("gaze", "")), clampAngle(o.optInt("angle", DEFAULT_ANGLE)))
        }

        /** Read from text (never throws; a missing or broken text is the defaults). */
        fun parse(text: String?): PortraitSettings =
            if (text.isNullOrBlank()) PortraitSettings() else try { fromJson(JSONObject(text)) } catch (e: Exception) { PortraitSettings() }
    }
}
