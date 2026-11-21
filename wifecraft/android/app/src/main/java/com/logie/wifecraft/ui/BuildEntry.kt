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

    /** What entering Build mode does to cinematic mode: nothing (they run together: the film frame, light and shots stay, the camera
     *  orbits the selection, auto-play stands on the shot). */
    fun cinematicAfterEnter(cinematic: Boolean): Boolean = cinematic

    /** What turning cinematic mode on or off does to Build mode: nothing. */
    fun buildAfterCinematic(build: Boolean): Boolean = build

    /** Whether the cinematic director owns the camera: not while Build mode is on (its orbit round the selection is the camera then). */
    fun directorCamera(cinematic: Boolean, buildOn: Boolean): Boolean = cinematic && !buildOn

    /** Whether the auto-play sequence is held on its current shot (Build mode on: no animation ends). */
    fun holdAnimations(buildOn: Boolean): Boolean = buildOn
}
