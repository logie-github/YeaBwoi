package com.logie.wifecraft.bodyparts

import com.logie.wifecraft.creator.Appearance
import com.logie.wifecraft.creator.Sex
import java.io.File

/*
 * The app's bodies (README "Bodies"): exactly two kinds. A race and sex with a Liard's Art set imported (HD Models, the .whd
 * sets drawn by engine/hdbody.cpp) is drawn as that HD figure; every other race and sex is drawn as the game's own default
 * (Shadowlands-era) character model. The user: "ignore eve and base except those that don't have a liard ... Scrap eve
 * completely. Either liard or shadowlands." There is no third kind, and no Eve body.
 */

/** Which of the two bodies an NPC is drawn with. */
enum class BodyKind { LIARD, GAME }

object Bodies {
    /** The result of [resolve]: the kind and, for the game's model, why the Liard figure isn't drawn (for the creator's note). */
    class Resolution(val kind: BodyKind, val why: String?) {
        /** The creator's read-only note: "Body: HD (Liard)" or "Body: Game model (why)". */
        val note: String get() = if (kind == BodyKind.LIARD) "Body: HD (Liard)" else "Body: Game model" + (why?.let { " ($it)" } ?: "")
    }

    /**
     * The set key of a race and sex: race * 10 + (1 for a woman, 0 for a man), as [HdSetInfo.key]. The Pandaren's three races
     * (24 neutral, 25 Alliance, 26 Horde) share one model and the creator offers them by the Alliance's id (creator/Catalog),
     * so a Pandaren set (made for race 24) answers to all three.
     */
    fun key(raceId: Int, sex: Sex): Int = (if (raceId == 25 || raceId == 26) 24 else raceId) * 10 + if (sex == Sex.FEMALE) 1 else 0

    /**
     * The body [drawn] (what is actually drawn: a named character's variant; null: a display as the game has it) is drawn with:
     * the Liard figure where the Options' HD Models switch is on, a set is imported for its race and sex, and the character
     * hasn't asked for the game's model ([HdOptions.useGame]); else the game's own model. Same answer as [HdDress.decide].
     */
    fun resolve(own: Appearance?, drawn: Appearance?, ctx: HdContext?): Resolution {
        val d = HdDress.decide(own, drawn, ctx)
        return if (d.on) Resolution(BodyKind.LIARD, null) else Resolution(BodyKind.GAME, d.why)
    }

    /** The names of the races (ChrRaces ids) a Liard set can be made for, for the lists. */
    val RACE_NAMES: Map<Int, String> = linkedMapOf(
        1 to "Human", 2 to "Orc", 3 to "Dwarf", 4 to "Night Elf", 5 to "Undead", 6 to "Tauren", 7 to "Gnome", 8 to "Troll",
        9 to "Goblin", 10 to "Blood Elf", 11 to "Draenei", 22 to "Worgen", 24 to "Pandaren", 25 to "Pandaren", 26 to "Pandaren",
        27 to "Nightborne", 28 to "Highmountain Tauren", 29 to "Void Elf", 30 to "Lightforged Draenei", 31 to "Zandalari Troll",
        32 to "Kul Tiran", 34 to "Dark Iron Dwarf", 35 to "Vulpera", 36 to "Mag'har Orc", 37 to "Mechagnome")

    fun raceName(id: Int): String = RACE_NAMES[id] ?: "race $id"

    fun setName(race: Int, sex: Int): String = raceName(race) + if (sex == 1) " woman" else " man"

    /** One line for Options: which races and sexes have a Liard set, and that every other one is the game's model. */
    fun coverageLine(sets: Collection<HdSetInfo>): String =
        if (sets.isEmpty()) "No Liard set is imported: every race and sex is drawn as the game's own model."
        else "Liard (HD): " + sets.sortedBy { it.key }.joinToString(", ") { setName(it.race, it.sex) } +
            ". Every other race and sex: the game's own model."
}

/** A stable short hash (FNV-1a, 8 hex digits) of a text: the pictures' cache marks. */
object LookHash {
    fun fnv(s: String): String {
        var h = 0x811C9DC5.toInt()
        for (c in s) { h = h xor c.code; h *= 0x01000193 }
        return "%08x".format(h)
    }
}

/**
 * The Eve body is scrapped (the user: "Scrap eve completely"). What the phone may still hold of it is deleted once, at the
 * first launch of a build without it, and never loaded: the imported set (files/bodyparts/eve, its staging folder, files/eve)
 * and the import's cache folder. The settings key "eveDefault" and an appearance's `"eve"` block are ignored (Appearance
 * reads no such field; an NPC saved with one loads as before and is written back without it).
 */
object LegacyEve {
    private val THUMB_MARK = Regex("_e[0-9a-f]{8}(_h[0-9a-f]{8})?_[0-9]+\\.png$")

    /** The folders to delete under the files folder [filesDir] and the cache folder [cacheDir]. */
    fun folders(filesDir: File, cacheDir: File?): List<File> = buildList {
        add(File(filesDir, "bodyparts/eve")); add(File(filesDir, "bodyparts/eve.new")); add(File(filesDir, "eve"))
        if (cacheDir != null) add(File(cacheDir, "eve-import"))
    }

    /**
     * Deletes those folders when [alreadyDone] is false; says each deletion to [log]. Returns whether the cleanup is now done
     * (the caller keeps that flag; a folder that couldn't be deleted is tried again at the next launch).
     */
    fun cleanup(filesDir: File, cacheDir: File?, alreadyDone: Boolean, log: (String) -> Unit = {}): Boolean {
        if (alreadyDone) return true
        var all = true
        // the pictures drawn with the Eve body (their cache mark "_e" and eight hex digits): drawn again as they are now
        val shots = (File(filesDir, "thumbs").listFiles() ?: emptyArray()).filter { it.isFile && THUMB_MARK.containsMatchIn(it.name) }
        if (shots.isNotEmpty()) {
            val n = shots.count { it.delete() }
            log("Eve body scrapped: deleted $n of ${shots.size} pictures drawn with it")
        }
        for (f in folders(filesDir, cacheDir)) {
            if (!f.exists()) continue
            val ok = f.deleteRecursively()
            log("Eve body scrapped: " + (if (ok) "deleted " else "could not delete ") + f.path)
            if (!ok) all = false
        }
        return all
    }
}
