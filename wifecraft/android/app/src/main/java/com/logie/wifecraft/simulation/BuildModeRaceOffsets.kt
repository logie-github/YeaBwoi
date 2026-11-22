package com.logie.wifecraft.simulation

import com.logie.wifecraft.anim.HumanScale
import com.logie.wifecraft.story.Vec3
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Build mode's race-specific placements ("Remember for gnomes"): after the player rearranges a playing animation with the
 * gizmo, the correction is kept for that animation, the role and the race (or the race pair, or the size class) as a DELTA
 * from the computed placement, in the pair's own frame (x its front, y its left, z up; [RoleDelta]), so it holds wherever
 * the pair's origin or the bed is. The next time that race plays that animation in that role (the live scene, autoplay,
 * cinematic, the Ready-to-play preview, a switch of positions: applied after the swap, by role), the delta is laid on top of
 * the computed placement; the animation itself is never changed or copied. A single actor on furniture (a gnome on a chair)
 * keys the item too. Precedence: the phone's own (files/build/race_offsets.json) over the shipped file
 * (assets/race_offsets.json, merged from build reports by tools/item_reports.py) over none. Pure.
 */

/** The body sizes a remembered placement can be shared by (HumanScale's dwarves and gnomes, the tall races, everyone else). */
enum class SizeClass(val id: String, val label: String) {
    GNOME("gnome", "Gnome-size"), DWARF("dwarf", "Dwarf-size"), HUMAN("human", "Human-size"), TALL("tall", "Tall");

    companion object {
        /** Night elves, tauren, draenei, worgen, nightborne, Highmountain tauren, Lightforged draenei, Zandalari, Kul Tirans. */
        val TALL_RACES = setOf(4, 6, 11, 22, 27, 28, 30, 31, 32)
        fun of(id: String?): SizeClass? = entries.firstOrNull { it.id == id }
        fun of(race: Int): SizeClass = when (race) {
            in HumanScale.GNOMES -> GNOME
            in HumanScale.DWARVES -> DWARF
            in TALL_RACES -> TALL
            else -> HUMAN
        }
    }
}

/** How widely a remembered placement applies: this race pair only, this race in this role with anyone, or this size class. */
enum class RaceScope(val id: String, val label: String, val rank: Int) {
    PAIR("pair", "Only this race pair", 0),
    ROLE("role", "This race in this role (any partner)", 1),
    SIZE("size", "This size class", 2);

    companion object { fun of(id: String?): RaceScope? = entries.firstOrNull { it.id == id } }
}

/** A correction in the pair's frame: yards along its front ([dx]), its left ([dy]), up ([dz]) and a turn ([dyawDeg]). */
data class RoleDelta(val dx: Float, val dy: Float, val dz: Float, val dyawDeg: Float) {
    val none: Boolean get() = abs(dx) < 1e-4f && abs(dy) < 1e-4f && abs(dz) < 1e-4f && abs(dyawDeg) < 0.05f
    operator fun plus(o: RoleDelta) = RoleDelta(dx + o.dx, dy + o.dy, dz + o.dz, ItemFrame.wrapDeg(dyawDeg + o.dyawDeg))
    operator fun minus(o: RoleDelta) = RoleDelta(dx - o.dx, dy - o.dy, dz - o.dz, ItemFrame.wrapDeg(dyawDeg - o.dyawDeg))

    /** [role] moved by it (its offset from the pair's origin and its facing, both in the pair's frame). */
    fun applyTo(role: PlaybackRole): PlaybackRole =
        if (none) role else role.copy(offset = role.offset + Vec3(dx, dy, dz), facing = role.facing + Math.toRadians(dyawDeg.toDouble()).toFloat())

    companion object { val ZERO = RoleDelta(0f, 0f, 0f, 0f) }
}

/** Who plays a role, as a remembered placement matches it: race id, sex, and the other's race (-1: alone). */
data class RoleActor(val race: Int, val female: Boolean, val otherRace: Int = -1)

/**
 * One remembered placement: [animation] (its id: the name it plays under), [role] (0 the lead's, 1 the partner's), the
 * actor's [race] and sex, the [otherRace] (-1 any or alone), the furniture class it was on ([furniture]: bed, couch, chair,
 * table, floor, standing; "" any), the item ([item]: a single actor's item interaction, its ItemMapping key; null any), the
 * [scope], and the [delta]. [reports] and [outliers]: how many reports a shipped one was merged from.
 */
data class RaceOffset(
    val animation: String, val animationName: String, val role: Int, val race: Int, val female: Boolean, val otherRace: Int,
    val furniture: String, val item: String?, val scope: RaceScope, val delta: RoleDelta,
    val reports: Int = 1, val outliers: List<Int> = emptyList(), val at: Long = 0L,
    /** How it was made: "both" (the pair moved together: a shared correction), "single" (that role moved alone), "" (older). */
    val move: String = "",
) {
    val sizeClass: SizeClass get() = SizeClass.of(race)

    /** The key two offsets of the same thing share (a newer one replaces the older). */
    val key: String get() = listOf(animation, role, scope.id, if (scope == RaceScope.SIZE) sizeClass.id else "$race:${if (female) "f" else "m"}",
        if (scope == RaceScope.PAIR) otherRace else "*", furniture, item ?: "").joinToString("|")

    /** Whether it applies to [who] in [role] of [animation] on [furnitureClass] (and [itemKey]). */
    fun matches(animation: String, role: Int, who: RoleActor, furnitureClass: String, itemKey: String?): Boolean {
        if (animation != this.animation || role != this.role) return false
        if (furniture.isNotEmpty() && furniture != furnitureClass) return false
        if (item != null && item != itemKey) return false
        return when (scope) {
            RaceScope.PAIR -> who.race == race && who.female == female && who.otherRace == otherRace
            RaceScope.ROLE -> who.race == race && who.female == female
            RaceScope.SIZE -> SizeClass.of(who.race) == sizeClass
        }
    }

    fun describe(): String = buildString {
        append(animationName.ifEmpty { animation })
        append(" · ").append(if (role == 0) "role A" else "role B")
        append(" · ").append(when (scope) {
            RaceScope.PAIR -> "race $race ${if (female) "female" else "male"} with race $otherRace"
            RaceScope.ROLE -> "race $race ${if (female) "female" else "male"}, any partner"
            RaceScope.SIZE -> sizeClass.label
        })
        if (furniture.isNotEmpty()) append(" · ").append(furniture)
        append(String.format(java.util.Locale.ROOT, " · %+.2f %+.2f %+.2f yd %+.0f°", delta.dx, delta.dy, delta.dz, delta.dyawDeg))
    }

    fun toJson(): JSONObject = JSONObject().put("animationId", animation).put("animationName", animationName).put("role", if (role == 0) "A" else "B")
        .put("race", race).put("sex", if (female) "female" else "male").put("otherRace", otherRace).put("furnitureClass", furniture)
        .put("item", item ?: JSONObject.NULL).put("scope", scope.id).put("sizeClass", sizeClass.id)
        .put("dx", r(delta.dx)).put("dy", r(delta.dy)).put("dz", r(delta.dz)).put("dyawDeg", r(delta.dyawDeg))
        .put("reports", reports).put("outliers", JSONArray(outliers)).put("at", at).also { if (move.isNotEmpty()) it.put("move", move) }

    companion object {
        private fun r(v: Float): Double = Math.round(v * 1000.0) / 1000.0

        /** One from its JSON (a report's raceOffsets[] or the files'); null when it is not one. */
        fun of(o: JSONObject): RaceOffset? {
            val animation = o.optString("animationId").ifEmpty { return null }
            val role = when (o.opt("role")) { "A", 0, "0" -> 0; "B", 1, "1" -> 1; else -> return null }
            val scope = RaceScope.of(o.optString("scope")) ?: return null
            val d = RoleDelta(o.optDouble("dx", Double.NaN).toFloat(), o.optDouble("dy", Double.NaN).toFloat(),
                o.optDouble("dz", Double.NaN).toFloat(), o.optDouble("dyawDeg", 0.0).toFloat())
            if (!d.dx.isFinite() || !d.dy.isFinite() || !d.dz.isFinite() || !d.dyawDeg.isFinite()) return null
            if (abs(d.dx) > RaceOffsets.MAX_YARDS || abs(d.dy) > RaceOffsets.MAX_YARDS || abs(d.dz) > RaceOffsets.MAX_YARDS) return null
            var race = o.optInt("race", -1)
            // a size class alone: its first race stands for it
            if (race < 0 && scope == RaceScope.SIZE) race = when (SizeClass.of(o.optString("sizeClass"))) {
                SizeClass.GNOME -> HumanScale.GNOMES.first(); SizeClass.DWARF -> HumanScale.DWARVES.first()
                SizeClass.TALL -> SizeClass.TALL_RACES.first(); else -> 1
            }
            if (race < 0) return null
            return RaceOffset(animation, o.optString("animationName"), role, race, o.optString("sex") == "female", o.optInt("otherRace", -1),
                o.optString("furnitureClass"), o.optString("item").takeIf { o.has("item") && !o.isNull("item") && it.isNotEmpty() },
                scope, d, o.optInt("reports", 1),
                o.optJSONArray("outliers")?.let { a -> (0 until a.length()).map { a.optInt(it) } }.orEmpty(), o.optLong("at"),
                o.optString("move"))
        }
    }
}

object RaceOffsets {
    const val SCHEMA = 1
    /** A delta beyond this (yards) is not a correction: it is refused. */
    const val MAX_YARDS = 3f

    fun parse(json: String): List<RaceOffset> {
        val root = try { JSONObject(json) } catch (e: Exception) { return emptyList() }
        val a = root.optJSONArray("offsets") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let(RaceOffset::of) }
    }

    fun toJson(list: Collection<RaceOffset>): String =
        JSONObject().put("schema", SCHEMA).put("offsets", JSONArray(list.map { it.toJson() })).toString(1)

    /** The furniture class a performance is on: what build mode keys ([SceneObject] null: the floor, or standing). */
    fun furnitureClass(item: SceneObject?, standing: Boolean = false): String = when (item) {
        SceneObject.BED -> "bed"; SceneObject.COUCH -> "couch"; SceneObject.CHAIR -> "chair"; SceneObject.TABLE -> "table"
        null -> if (standing) "standing" else "floor"
    }

    /** The most specific of [list] matching (an exact pair before a role's race before a size class; the newest of a rank). */
    fun best(list: List<RaceOffset>, animation: String, role: Int, who: RoleActor, furnitureClass: String, itemKey: String?): RaceOffset? =
        list.filter { it.matches(animation, role, who, furnitureClass, itemKey) }
            .minWithOrNull(compareBy<RaceOffset> { it.scope.rank }.thenBy { if (it.item != null) 0 else 1 }.thenByDescending { it.at })

    /** The phone's own first, then the shipped file's; none: the computed placement stands. */
    fun lookup(local: List<RaceOffset>, shipped: List<RaceOffset>, animation: String, role: Int, who: RoleActor,
               furnitureClass: String, itemKey: String?): Pair<RaceOffset?, OverrideSource> =
        best(local, animation, role, who, furnitureClass, itemKey)?.let { it to OverrideSource.LOCAL }
            ?: best(shipped, animation, role, who, furnitureClass, itemKey)?.let { it to OverrideSource.SHIPPED }
            ?: (null to OverrideSource.HEURISTIC)

    /** [list] with [o] in place of the one of its key (a new correction of the same thing replaces the old). */
    fun upsert(list: List<RaceOffset>, o: RaceOffset): List<RaceOffset> = list.filter { it.key != o.key } + o

    /**
     * The correction, in the pair's frame as computed ([baseAnchor] turned [baseHeading]), that takes a role whose computed
     * place is [role] to where it is drawn now: the pair moved to [anchor] turned [heading] and the role nudged by [extra]
     * (in the moved pair's frame). The role's world place and facing before and after, the difference turned into the base frame.
     */
    fun deltaFor(role: PlaybackRole, baseAnchor: Vec3, baseHeading: Float, anchor: Vec3, heading: Float, extra: RoleDelta = RoleDelta.ZERO): RoleDelta {
        fun world(a: Vec3, h: Float, o: Vec3): Vec3 {
            val c = cos(h); val s = sin(h)
            return a + Vec3(o.x * c - o.y * s, o.x * s + o.y * c, o.z)
        }
        val before = world(baseAnchor, baseHeading, role.offset)
        val after = world(anchor, heading, role.offset + Vec3(extra.dx, extra.dy, extra.dz))
        val d = after - before
        val c = cos(baseHeading); val s = sin(baseHeading)
        val turn = ItemFrame.wrapDeg(Math.toDegrees((heading - baseHeading).toDouble()).toFloat() + extra.dyawDeg)
        return RoleDelta(d.x * c + d.y * s, -d.x * s + d.y * c, d.z, turn)
    }

    /**
     * Several reports' offsets of one key as one: the median of each part; with three or more, those over 0.35 yards or 45
     * degrees from the median are left out (listed as outliers by their [RaceOffset.at], the issue number for reports) and
     * the median taken again (tools/item_reports.py does the same).
     */
    fun merge(samples: List<RaceOffset>): RaceOffset? {
        if (samples.isEmpty()) return null
        fun med(v: List<Float>): Float { val s = v.sorted(); return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2f }
        fun median(l: List<RaceOffset>) = RoleDelta(med(l.map { it.delta.dx }), med(l.map { it.delta.dy }), med(l.map { it.delta.dz }), med(l.map { it.delta.dyawDeg }))
        var kept = samples
        var m = median(kept)
        val out = ArrayList<Int>()
        if (samples.size >= 3) {
            kept = samples.filter { s ->
                val far = hypot(s.delta.dx - m.dx, s.delta.dy - m.dy) > ItemOverrides.OUTLIER_YARDS || abs(s.delta.dz - m.dz) > ItemOverrides.OUTLIER_YARDS ||
                    abs(ItemFrame.wrapDeg(s.delta.dyawDeg - m.dyawDeg)) > ItemOverrides.OUTLIER_DEG
                if (far) out += s.at.toInt()
                !far
            }
            if (kept.isEmpty()) { kept = samples; out.clear() }
            m = median(kept)
        }
        val newest = samples.maxBy { it.at }
        return newest.copy(delta = m, reports = kept.size, outliers = out.sorted())
    }
}

/** The phone's remembered placements (files/build/race_offsets.json): listed in the Build panel and Options, deletable. */
class RaceOffsetStore(private val file: File) {
    fun load(): List<RaceOffset> = runCatching { RaceOffsets.parse(file.readText()) }.getOrDefault(emptyList())

    fun save(list: List<RaceOffset>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(RaceOffsets.toJson(list))
        if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
    }

    fun remember(o: RaceOffset): List<RaceOffset> = RaceOffsets.upsert(load(), o).also(::save)
    fun delete(key: String): List<RaceOffset> = load().filter { it.key != key }.also(::save)
    fun reset(): List<RaceOffset> = emptyList<RaceOffset>().also(::save)
}
