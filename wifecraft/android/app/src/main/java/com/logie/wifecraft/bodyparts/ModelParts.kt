package com.logie.wifecraft.bodyparts

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/**
 * Model parts (cpp engine/modelpart.h): a character the game draws with her own creature model (Sylvanas, Calia, Jaina,
 * Alexstrasza, Alleria, Xal'atath, Valeera) has her armour baked into it. Her partly undressed look is her stand-in's bare
 * body (the playable race's model: the roster's "bare" look) with its own head cut away and tinted to her skin, wearing
 * the pieces kept from her model: her head (hair, helm, face), her pauldrons, a back-piece. The pieces are cut on the
 * phone from the downloaded model (Native.buildModelParts) into files/bodyparts/<set>/: set.json
 * ([SetIndex]) and <region>_<n>.wcbp (part file version 2), and drawn by SceneView.putModelSet.
 *
 * The manifest is assets/model_parts.json ([Manifest]). Pure: parsed here, tested on the JVM.
 */
object ModelParts {
    /** The manifest's asset. */
    const val ASSET = "model_parts.json"

    /** One set: the pieces of [regions] cut from [model] (the creature display [display]'s) for [character]'s [look]. */
    data class PartSet(
        val set: String,
        val character: String,
        val look: String,
        val display: Int,
        val model: Int,
        val standIn: Int,
        val standInLook: String,
        val regions: List<String>,
        val cut: List<String>,
        val skinMatch: Boolean,
    )

    data class Manifest(val sets: List<PartSet>, val regions: Set<String>) {
        fun forLook(character: String, look: String): PartSet? = sets.firstOrNull { it.character == character && it.look == look }
        fun forCharacter(character: String): List<PartSet> = sets.filter { it.character == character }
    }

    /** The regions the engine defines itself (modelpart.cpp defaultPartRegions). */
    val BUILT_IN = setOf("head", "shoulder_l", "shoulder_r", "hand_l", "hand_r", "cloak")

    /** The manifest read; a set naming a region neither built in nor defined, or without a set name or model, is an error. */
    fun parse(text: String): Manifest {
        val root = JSONObject(text)
        val regions = BUILT_IN.toMutableSet()
        root.optJSONObject("regions")?.keys()?.forEach { regions += it }
        val sets = mutableListOf<PartSet>()
        val arr = root.optJSONArray("sets") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val name = o.optString("set")
            require(name.isNotEmpty() && '/' !in name && ".." !in name) { "set $i needs a set name without slashes" }
            val model = o.optInt("model")
            require(model > 0) { "set $name has no model" }
            val rs = strings(o.optJSONArray("regions"))
            require(rs.isNotEmpty()) { "set $name has no regions" }
            rs.firstOrNull { it !in regions }?.let { throw IllegalArgumentException("set $name names region $it, which isn't defined") }
            sets += PartSet(
                set = name, character = o.optString("character"), look = o.optString("look"), display = o.optInt("display"),
                model = model, standIn = o.optInt("standIn"), standInLook = o.optString("standInLook", "bare"), regions = rs,
                cut = if (o.has("cut")) strings(o.optJSONArray("cut")) else listOf("Head"), skinMatch = o.optBoolean("skinMatch", true),
            )
        }
        return Manifest(sets, regions)
    }

    /** One part of a cut set (set.json's "parts"): its file, region, bone, texture file, blend and fit. */
    data class Part(
        val file: String, val region: String, val bone: String, val texture: Int, val blend: Int,
        val dx: Float, val dy: Float, val dz: Float, val scale: Float,
    )

    /** A cut set's set.json: the stand-in, its cut, the source's skin colour (0xRRGGBB, -1: don't match) and the parts. */
    data class SetIndex(val set: String, val model: Int, val standIn: Int, val cut: List<String>, val skinColour: Int, val parts: List<Part>)

    fun parseIndex(text: String): SetIndex {
        val o = JSONObject(text)
        val parts = mutableListOf<Part>()
        val arr = o.optJSONArray("parts") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i)
            val off = p.optJSONArray("offset")
            fun at(k: Int) = off?.optDouble(k, 0.0)?.toFloat() ?: 0f
            parts += Part(
                file = p.getString("file"), region = p.optString("region"), bone = p.optString("bone"),
                texture = p.optInt("texture"), blend = p.optInt("blend"), dx = at(0), dy = at(1), dz = at(2),
                scale = p.optDouble("scale", 1.0).toFloat(),
            )
        }
        val skin = o.optString("skinColour").takeIf { it.length == 6 }?.toIntOrNull(16) ?: -1
        return SetIndex(o.optString("set"), o.optInt("model"), o.optInt("standIn"), strings(o.optJSONArray("cut")), skin, parts)
    }

    /**
     * The stand-in's tint (Native.bodyTint: r, g, b) that brings its own face colour [own] (0xRRGGBB: its skin sampled where
     * Native.faceSkinUvs says) to the source's [want] (SetIndex.skinColour); each channel clamped to 0.25..2. White (no
     * change) when either is unknown (-1).
     */
    fun tint(own: Int, want: Int): FloatArray {
        if (own < 0 || want < 0) return floatArrayOf(1f, 1f, 1f)
        return FloatArray(3) { k ->
            val o = ((own shr (16 - 8 * k)) and 255).toFloat()
            val w = ((want shr (16 - 8 * k)) and 255).toFloat()
            if (o <= 1f) 1f else min(2f, max(0.25f, w / o))
        }
    }

    private fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }
}
