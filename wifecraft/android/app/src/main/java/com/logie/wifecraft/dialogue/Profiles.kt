package com.logie.wifecraft.dialogue

import com.logie.wifecraft.creator.Initiation
import com.logie.wifecraft.creator.Pace
import com.logie.wifecraft.creator.Sex
import com.logie.wifecraft.editor.Actor
import com.logie.wifecraft.simulation.Traits

/** An NPC of the scene's cast as the dialogue knows them: the creator's race, sex and class, the traits chosen at creation (else the ones their id gives, as the life simulation does), the bio and preferences. */
object Profiles {
    fun of(a: Actor): NpcProfile {
        val ap = a.appearance
        val p = ap.personality
        val female = ap.sex == Sex.FEMALE
        val traits = p?.traits?.takeIf { it.isNotEmpty() } ?: Traits.assign(a.id, ap.raceId, female).map { it.name }
        val prefs = p?.preferences
        val d = Disposition.of(traits, p?.bio.orEmpty(), prefs?.pace == Pace.SLOW, prefs?.pace == Pace.INTENSE, prefs?.initiation == Initiation.ASK)
        return NpcProfile(a.id, a.name, ap.raceId, female, ap.classId, traits, p?.bio.orEmpty(), d, named = ap.named?.character)
    }
}
