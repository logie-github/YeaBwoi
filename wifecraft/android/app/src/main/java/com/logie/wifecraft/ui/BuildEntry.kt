package com.logie.wifecraft.ui

// Pure Kotlin (JVM-tested: unit-tests/.../ui/BuildEntryTest): where Build mode can be entered from in a live scene and the
// first-run hint that points to it. The Build chip sits first in the HUD's chip row (which scrolls sideways), the scene menu
// (the top bar's gear) has a Build mode button too, and a one-line hint shows until it is dismissed or Build mode was entered.

object BuildEntry {
    /** The HUD chip's words. */
    const val CHIP = "Build"
    /** The scene menu's button: it enters, or leaves when Build mode is on. */
    fun menuLabel(on: Boolean): String = if (on) "Leave Build mode" else "Build mode"
    /** The scene menu's line under the button. */
    fun menuDetail(on: Boolean): String = if (on) "Back to the scene's own controls" else "Move things, mark places, make props interactive"
    const val HINT = "New: Build mode. Tap Build in the row above the action bar, or open it from the gear menu."
    const val PREFS = "build_entry"
    const val KEY_HINT_SEEN = "hint_seen"

    /** Whether the Build entry is offered now: in any revealed scene, but not while Move mode (the item mapper) holds the HUD. */
    fun chipShown(mapperOn: Boolean): Boolean = !mapperOn

    /** The menu's button is there in every scene (its entry never depends on the HUD being up). */
    fun menuShown(): Boolean = true

    /** The first-run hint: until it was dismissed or Build mode was used, never while Build mode is on or the HUD is hidden. */
    fun hintShown(seen: Boolean, buildOn: Boolean, hudHidden: Boolean, revealed: Boolean): Boolean =
        !seen && !buildOn && !hudHidden && revealed

    /** What entering does to the other modes: cinematic ends (Build mode and cinematic are exclusive). */
    fun cinematicAfterEnter(@Suppress("UNUSED_PARAMETER") cinematic: Boolean): Boolean = false
}
