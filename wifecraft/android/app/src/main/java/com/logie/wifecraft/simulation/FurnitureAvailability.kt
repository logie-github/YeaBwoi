package com.logie.wifecraft.simulation

import com.logie.wifecraft.anim.BodyFrame
import com.logie.wifecraft.anim.Requirement
import com.logie.wifecraft.anim.RequirementClassifier
import com.logie.wifecraft.anim.Surface
import kotlin.math.hypot

/**
 * How an animation can be played in this scene ([FurnitureAvailability.of]): [READY] where the people are, needing nothing (a
 * standing or floor one, or one read from its names alone); [ON_FURNITURE] on a piece of the scene's furniture it fits (they
 * walk there); [FLOOR] it needs furniture the scene hasn't got (or none here fits it), so it is played on the floor instead,
 * lowered until its lowest flesh meets the floor ([FurnitureAvailability.floorDrop]).
 */
enum class PlayState { READY, ON_FURNITURE, FLOOR }

/**
 * The player's own class for an animation (long press in the animation window: "Treat as"), kept per animation id
 * ([TreatAsMemory]) and used instead of what its bodies were read as: [BED], [SEAT] and [TABLE] look for that furniture
 * (any piece of the kind, its heights relaxed), [FLOOR] plays it on the floor, [STANDING] where the people are, as authored.
 */
enum class TreatAs(val label: String, val surface: Surface) {
    BED("Bed", Surface.BED), SEAT("Seat", Surface.ANY_SEAT), FLOOR("Floor", Surface.FLOOR), STANDING("Standing", Surface.NONE),
    TABLE("Table", Surface.TABLE_OR_COUNTER);

    companion object {
        fun of(name: String?): TreatAs? = entries.firstOrNull { it.name == name }
    }
}

/**
 * What [FurnitureAvailability.of] decided: the [state], the furniture the animation [needs] (as read, or as the player set it),
 * the [furniture] it plays on and where ([fit], [relaxed]: a near fit, heights or kind loosened), and for a floor play the
 * one-line [notice] ("No bed here: playing on the floor.").
 */
data class Availability(
    val state: PlayState,
    val needs: Surface,
    val furniture: Furniture? = null,
    val fit: FurnitureFit? = null,
    val relaxed: Boolean = false,
    val notice: String? = null,
    val treatedAs: TreatAs? = null,
    /** A floor play whose bodies, lowered, rest on the floor ([FurnitureAvailability.floorRests]); true when not known. */
    val floorRests: Boolean = true,
) {
    /** The animation window's chip: "Ready", "On the double bed", "Needs a bed: floor"... */
    val chip: String get() = when (state) {
        PlayState.READY -> "Ready"
        PlayState.ON_FURNITURE -> "On the " + (furniture?.kind?.label?.lowercase() ?: "furniture")
        PlayState.FLOOR -> "Floor"
    }
}

/**
 * Which animations play in a scene and where (pure): every animation plays, anywhere: what one is marked for (bed, couch,
 * table...) is only a preference, never a gate. One whose bodies need furniture plays on the nearest piece it fits
 * ([FurnitureFit.fit]), else on one it nearly fits ([FurnitureFit.fit] relaxed: a bed taller than the clip's, a couch for a
 * bed), else on any piece loosely (loose), else on the floor, lowered onto it, with a notice. The animation window's chips and counter, the placed orders,
 * the stage chain and cinematic auto-play all read it.
 */
object FurnitureAvailability {
    /**
     * How [r] plays among [pieces] (the scene's free furniture in reach; nearest to [fromX], [fromY] first when given) for
     * bodies [bodyUnit] world units to the body unit, the player's [treatAs] taking the place of what was read.
     */
    fun of(r: Requirement, pieces: List<Furniture>, bodyUnit: Float = FurnitureFit.DEFAULT_BODY_UNIT, treatAs: TreatAs? = null,
           fromX: Float = Float.NaN, fromY: Float = Float.NaN, drop: Float = Float.NaN): Availability {
        val need = applied(r, treatAs)
        val rests = drop.isNaN() || floorRests(r, drop)
        when (treatAs) {
            TreatAs.STANDING -> return Availability(PlayState.READY, Surface.NONE, treatedAs = treatAs)
            TreatAs.FLOOR -> return Availability(PlayState.FLOOR, Surface.FLOOR, treatedAs = treatAs, floorRests = rests)
            else -> Unit
        }
        // read from names alone, or not at all: plays as before (by its names, else where they are)
        if (treatAs == null && !placed(r)) return Availability(PlayState.READY, r.surface)
        if (need.surface in Requirement.FLOOR_LEVEL) return Availability(PlayState.READY, need.surface, treatedAs = treatAs)
        val ordered = if (fromX.isNaN() || fromY.isNaN()) pieces else pieces.sortedBy { hypot(it.x - fromX, it.y - fromY) }
        for (f in ordered) FurnitureFit.fit(need, f, bodyUnit)?.let { return Availability(PlayState.ON_FURNITURE, need.surface, f, it, treatedAs = treatAs) }
        for (f in ordered) FurnitureFit.fit(need, f, bodyUnit, relaxed = true)?.let {
            return Availability(PlayState.ON_FURNITURE, need.surface, f, it, relaxed = true, treatedAs = treatAs)
        }
        // what it is marked for is only a preference: any piece it can be put on loosely, before the floor
        for (f in ordered) FurnitureFit.fit(need, f, bodyUnit, loose = true)?.let {
            return Availability(PlayState.ON_FURNITURE, need.surface, f, it, relaxed = true, treatedAs = treatAs)
        }
        return Availability(PlayState.FLOOR, need.surface, notice = notice(need.surface, pieces.isNotEmpty(), rests), treatedAs = treatAs,
            floorRests = rests)
    }

    /** How near the floor (body units) the lowest raised support must come, lowered, for a floor play to rest on it (about 13 cm). */
    const val FLOOR_REST = 0.15f

    /**
     * Whether [r]'s bodies, lowered by [drop] (body units: [floorDrop]), rest on the floor: the lowest support of an actor off
     * the floor comes within [FLOOR_REST] of it. Not so where something else reaches the floor first (a partner standing beside
     * the bed, legs hanging over its edge): those would be played in the air, so only the player's own order plays them so.
     */
    fun floorRests(r: Requirement, drop: Float): Boolean {
        val raised = r.actors.filter { it.surface !in Requirement.FLOOR_LEVEL }
        if (raised.isEmpty()) return true
        return raised.minOf { it.restUnits } - drop <= FLOOR_REST
    }

    /** [r] as the player classed it ([treatAs]: the surface the furniture is looked for by; the bodies' own reading kept). */
    fun applied(r: Requirement, treatAs: TreatAs?): Requirement =
        if (treatAs == null || treatAs.surface == r.surface) r else r.copy(surface = treatAs.surface)

    /** Whether [r] was read from the bodies (where each actor goes is known), not from names alone or not at all. */
    fun placed(r: Requirement) = r.surface != Surface.UNKNOWN && "geometry" in r.basis

    /** "a bed", "a seat"... */
    fun placeName(surface: Surface): String = when (surface) {
        Surface.BED -> "a bed"
        Surface.COUCH -> "a couch"
        Surface.CHAIR -> "a chair"
        Surface.TABLE_OR_COUNTER -> "a table"
        Surface.FLOOR, Surface.NONE -> "the floor"
        else -> "a seat"
    }

    /**
     * The one-line notice of a floor play: "No bed here: playing on the floor." ([furnitureHere]: a piece there it doesn't fit,
     * "Nothing here fits it as a bed: ..."); one that, lowered, wouldn't rest on the floor ([rests] false: a partner standing
     * beside the bed holds it up) is played where they stand as it was made: "... playing it where they stand, as made."
     */
    fun notice(surface: Surface, furnitureHere: Boolean = false, rests: Boolean = true): String {
        val noun = placeName(surface).removePrefix("a ").removePrefix("the ")
        val how = if (rests) "playing on the floor." else "playing it where they stand, as made."
        return if (furnitureHere) "Nothing here fits it as ${placeName(surface)}: $how" else "No $noun here: $how"
    }

    /**
     * The animation window's counter: "530 playable here" (all of them), then how many play on the floor in place of
     * furniture: "530 playable here, 118 of them on the floor".
     */
    fun counter(states: Collection<PlayState>): String {
        val floor = states.count { it == PlayState.FLOOR }
        return "${states.size} playable here" + if (floor > 0) ", $floor of them on the floor" else ""
    }

    /** Whether the animation window lists one with [a] (null: not known) (every one is playable: nothing is hidden, [onlyPlayable] kept for the old setting). */
    @Suppress("UNUSED_PARAMETER")
    fun shown(a: Availability?, onlyPlayable: Boolean): Boolean = true

    /** One line for the animation window's details: where [a] plays here. */
    fun describe(a: Availability): String {
        val treated = a.treatedAs?.let { "Treated as ${it.label.lowercase()}: " } ?: ""
        return treated + when (a.state) {
            PlayState.READY -> if (a.treatedAs == TreatAs.STANDING) "plays where they are, as made." else "Plays where they are."
            PlayState.ON_FURNITURE -> "Plays on the ${a.furniture?.kind?.label?.lowercase() ?: "furniture"}" +
                (if (a.relaxed) ", the nearest fit (its height differs from the animation's)." else ".")
            PlayState.FLOOR -> a.notice ?: "Plays on the floor."
        }.let { if (treated.isNotEmpty()) it.replaceFirstChar(Char::lowercase) else it }
    }

    /**
     * How far (body units) a clip whose actors' bodies are [bodies] (the pair's shared frame) may be lowered for a floor play:
     * until the lowest flesh of any actor over the clip ([RequirementClassifier.FLESH]: no joint nearer the floor than the flesh
     * around it) meets the floor; 0 when something already rests there (a standing partner, legs hanging to the floor).
     */
    fun floorDrop(bodies: List<List<BodyFrame>>): Float {
        var low = Double.MAX_VALUE
        for (frames in bodies) for (f in frames) for ((j, pad) in RequirementClassifier.FLESH) {
            val p = f[j] ?: continue
            if (p[2] - pad < low) low = p[2] - pad
        }
        return if (low == Double.MAX_VALUE) 0f else maxOf(0.0, low).toFloat()
    }
}

/**
 * The player's "Treat as" ([TreatAs]) per animation id (a pair's id, a clip's), kept in the app's preferences as one string
 * ([encode]: a JSON object, id to class name); a damaged or missing string is an empty memory, unknown classes are dropped.
 */
class TreatAsMemory(entries: Map<String, TreatAs> = emptyMap()) {
    private val map = LinkedHashMap(entries)

    val all: Map<String, TreatAs> get() = map

    operator fun get(id: String): TreatAs? = map[id]

    /** [id] treated as [treat] (null: as its bodies were read again). True when that changed anything. */
    fun set(id: String, treat: TreatAs?): Boolean = if (treat == null) map.remove(id) != null else map.put(id, treat) != treat

    fun encode(): String = org.json.JSONObject().also { o -> for ((k, v) in map) o.put(k, v.name) }.toString()

    companion object {
        /** Where it is kept (the screen's SharedPreferences name and key). */
        const val PREFS = "treat_as"
        const val KEY = "animations"

        fun decode(s: String?): TreatAsMemory {
            if (s.isNullOrBlank()) return TreatAsMemory()
            return try {
                val o = org.json.JSONObject(s)
                val out = LinkedHashMap<String, TreatAs>()
                for (k in o.keys()) TreatAs.of(o.optString(k))?.let { out[k] = it }
                TreatAsMemory(out)
            } catch (e: Exception) { TreatAsMemory() }
        }
    }
}
