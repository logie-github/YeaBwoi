package com.logie.wifecraft.pack

import org.json.JSONObject
import java.io.File

/**
 * Options → Mods → Import pack's switches, kept in `files/pack/options.json` (plain JSON, written whole and moved into place):
 *  - [props]: the pack's props (toys, dolls, handcuffs) are drawn in the stages that use them;
 *  - [bodyLayer]: the animation-linked body layer (the cum mesh) is drawn in the stages that name it;
 *  - [furniture]: the pack's furniture models are offered in Build mode's New tab.
 * All three are on once a pack is imported; turning one off only stops it being drawn, it deletes nothing.
 */
class PackOptions(private val file: File) {
    @Volatile var props: Boolean = true
        private set
    @Volatile var bodyLayer: Boolean = true
        private set
    @Volatile var furniture: Boolean = true
        private set

    init { load() }

    fun load() {
        val o = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return
        props = o.optBoolean("props", true)
        bodyLayer = o.optBoolean("bodyLayer", true)
        furniture = o.optBoolean("furniture", true)
    }

    fun set(props: Boolean = this.props, bodyLayer: Boolean = this.bodyLayer, furniture: Boolean = this.furniture) {
        this.props = props; this.bodyLayer = bodyLayer; this.furniture = furniture
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(JSONObject().put("props", props).put("bodyLayer", bodyLayer).put("furniture", furniture).toString())
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    /** Whether a prop of [kind] is drawn now. */
    fun shows(kind: PackKind): Boolean = when (kind) {
        PackKind.BODY_LAYER -> bodyLayer
        PackKind.FURNITURE -> furniture
        else -> props
    }
}
