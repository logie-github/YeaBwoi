package com.logie.wifecraft.simulation

/**
 * The rules of the stages only a creature NPC takes part in ([com.logie.wifecraft.characters.DragonStages], PartnerPair.exclusiveTo):
 * who may play one and which role each plays. Pure.
 */
object CreatureStages {
    /** The pair id a role's clip key names ("pair:<id>:<role>", [PlaybackKeys.pair]), else null. */
    fun pairIdOf(key: String): String? {
        if (!key.startsWith("pair:")) return null
        val rest = key.removePrefix("pair:")
        val cut = rest.lastIndexOf(':')
        return if (cut > 0) rest.substring(0, cut) else null
    }

    /**
     * Whether two people may play a stage made for [exclusiveTo] (null: any two may): one of them is that creature
     * ([characterA], [characterB]: the named character each is, null for a person) and the other is no creature at all.
     */
    fun eligible(exclusiveTo: String?, characterA: String?, characterB: String?, creatureA: Boolean, creatureB: Boolean): Boolean {
        if (exclusiveTo == null) return true
        val a = creatureA && characterA == exclusiveTo
        val b = creatureB && characterB == exclusiveTo
        return (a && !creatureB) || (b && !creatureA)
    }
}
