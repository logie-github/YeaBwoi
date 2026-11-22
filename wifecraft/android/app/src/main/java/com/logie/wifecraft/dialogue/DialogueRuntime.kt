package com.logie.wifecraft.dialogue

/**
 * Where the on-device model meets the dialogue layer: the runtime sets [engine] when its model is loaded and clears it when it
 * is unloaded; the scenes' directors ask for it each time ([DialogueDirector]), so a model loaded after a scene started is
 * used from then on. Null: no model, so the layer is inactive and everything runs as it always did.
 */
object DialogueRuntime {
    @Volatile var engine: DialogueEngine? = null
}
