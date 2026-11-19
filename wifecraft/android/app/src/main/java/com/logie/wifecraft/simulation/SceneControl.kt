package com.logie.wifecraft.simulation

/**
 * Who the player controls in the live scene and who that NPC's partner is (the partner animations play with them). Taps
 * on NPCs in the 3D view: with nobody controlled, a tap takes control of that NPC; with one controlled, a tap on another
 * makes it the partner (a tap on the partner again clears it; a tap on the controlled one changes nothing). The cast bar
 * switches who is controlled (its tab again lets go). A new controlled NPC starts with no partner. Pure: the screen keeps
 * one in its state.
 */
data class SceneControl(val selected: String? = null, val partner: String? = null) {
    /** An NPC tapped in the 3D view. */
    fun tapNpc(id: String): SceneControl = when {
        selected == null -> SceneControl(id, null)
        id == selected -> this
        id == partner -> copy(partner = null)
        else -> copy(partner = id)
    }

    /** An NPC's tab in the cast bar: control it, or let it go when it is the one controlled. */
    fun tapCast(id: String): SceneControl = if (id == selected) SceneControl() else SceneControl(id, null)

    /** The partner chip's clear button. */
    fun clearPartner(): SceneControl = copy(partner = null)

    /** Nobody controlled (Back, a tap on empty space with the room camera). */
    fun release(): SceneControl = SceneControl()

    /** The cast changed: anyone no longer in [ids] is dropped. */
    fun keep(ids: Collection<String>): SceneControl = when {
        selected != null && selected !in ids -> SceneControl()
        partner != null && partner !in ids -> copy(partner = null)
        else -> this
    }
}
