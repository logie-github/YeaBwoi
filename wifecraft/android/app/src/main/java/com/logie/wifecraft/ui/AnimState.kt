package com.logie.wifecraft.ui

import com.logie.wifecraft.anim.AddResult
import com.logie.wifecraft.anim.Anim
import com.logie.wifecraft.anim.ImportEvent
import com.logie.wifecraft.anim.PairAddResult
import com.logie.wifecraft.anim.PairAnim
import com.logie.wifecraft.sims.SimsClip
import java.io.File

// Pure Kotlin, no android.* and no Compose: what the Import Animations and Animations screens (AnimImportScreen,
// AnimLibraryScreen) decide, so the JVM tests (unit-tests/.../ui/AnimStateTest.kt) cover it. Conversion itself is
// in anim/ (ImportPipeline, Retargeter); this file only follows its events and saves its results.

object AnimText {
    /** The category an imported animation gets when the author picks none. */
    const val DEFAULT_CATEGORY = "Imported"
    /** The category, tab and row label of partner animations (two actors together). */
    const val PARTNER = PairAnim.CATEGORY

    /** How a pair was found, for its row: "from the package's tuning" etc. */
    fun pairedBy(mode: com.logie.wifecraft.anim.Pairing.Mode): String = when (mode) {
        com.logie.wifecraft.anim.Pairing.Mode.TUNING -> "two actors, paired by the package's tuning"
        com.logie.wifecraft.anim.Pairing.Mode.NAME -> "two actors, paired by their clip names"
        com.logie.wifecraft.anim.Pairing.Mode.GEOMETRY -> "two actors, paired by their actor names and start positions"
        com.logie.wifecraft.anim.Pairing.Mode.HAND -> "two actors, paired by hand"
    }
    const val MAX_NAME = 60
    const val MAX_CATEGORY = 40

    /** Why nothing converts without the Sims 4 skeleton (each file of an import is reported Failed with this). */
    const val NO_RIG = "The Sims 4 skeleton isn't loaded. Choose your Sims 4 package for the skeleton first " +
        "(a game package such as ClientFullBuild0.package from the Sims 4 install); animations can't be converted without it."

    /** A name or category as stored: spaces tidied, no control characters, cut to [max]. */
    fun tidy(s: String, max: Int): String =
        s.filter { !it.isISOControl() || it == ' ' }.trim().replace(Regex("\\s+"), " ").take(max).trim()

    fun name(s: String, fallback: String): String = tidy(s, MAX_NAME).ifEmpty { tidy(fallback, MAX_NAME) }.ifEmpty { "Animation" }
    fun category(s: String): String = tidy(s, MAX_CATEGORY).ifEmpty { DEFAULT_CATEGORY }

    /** A file name without its folders (an archive's entries) and extension, as the default display name. */
    fun baseName(path: String): String = path.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.', path.substringAfterLast('/'))
}

// ------------------------------------------------------------------------ the Sims skeleton

/**
 * The Sims 4 skeleton (the rig resource of a game package), kept as its own bytes under files/anims/rig so the game's
 * big package is read once. The bytes are parsed by sims/SimsRig when a conversion starts.
 */
class RigStore(dir: File) {
    private val file = File(File(dir, "rig"), "rig.bin")

    fun has(): Boolean = file.isFile && file.length() > 0

    fun bytes(): ByteArray? = if (has()) runCatching { file.readBytes() }.getOrNull() else null

    /** Replaces the stored rig, atomically (a half-written file is never read). */
    fun save(bytes: ByteArray) {
        require(bytes.isNotEmpty()) { "The skeleton is empty." }
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "rig.bin.tmp")
        tmp.writeBytes(bytes)
        try {
            java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
            java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun clear() { file.delete() }

    companion object {
        /** The skeleton row's words: whether one is loaded and how big. */
        fun status(has: Boolean, bones: Int?): String =
            if (!has) "No Sims 4 skeleton: converting needs one."
            else "Sims 4 skeleton loaded" + (bones?.let { " ($it bones)" } ?: "") + "."
    }
}

// ------------------------------------------------------------------------ the import's progress

enum class ItemStatus(val label: String) {
    WAITING("Reading"), DONE("Done"), SKIPPED("Skipped"), FAILED("Failed"),
    /** A file or archive that was found and produced the rows after it: not shown itself. */
    OPENED("");
}

data class ImportRow(val name: String, val status: ItemStatus, val note: String = "", val anim: Anim? = null, val pair: PairAnim? = null)

/**
 * What an import has done so far, built from the pipeline's events ([apply]; any thread, call from one at a time):
 * a row per file or clip with Done, Skipped or Failed (and the reason). [finish] settles rows nothing came of.
 */
class ImportRun {
    private val all = mutableListOf<ImportRow>()
    var finished = false
        private set

    /** The rows to show: files being read and results, in the order they came. */
    val rows: List<ImportRow> get() = all.filter { it.status != ItemStatus.OPENED }

    /** The converted animations, in order. */
    val converted: List<Anim> get() = all.mapNotNull { it.anim }

    /** The partner animations found and converted, in order. */
    val pairs: List<PairAnim> get() = all.mapNotNull { it.pair }

    /** Clips that look like one actor of a pair whose partner was not found (to pair by hand). */
    val unpaired: List<ImportEvent.Unpaired> get() = halves.toList()
    private val halves = mutableListOf<ImportEvent.Unpaired>()

    private fun lastWaiting(name: String? = null): Int =
        all.indexOfLast { it.status == ItemStatus.WAITING && (name == null || it.name == name) }

    /** Puts a result in the row that waits under [name], else in the one read last (a clip's result is named by the clip). */
    private fun settle(name: String, row: ImportRow) {
        val at = lastWaiting(name).takeIf { it >= 0 } ?: lastWaiting()
        if (at >= 0) all[at] = row else all += row
    }

    fun apply(e: ImportEvent) {
        when (e) {
            is ImportEvent.Found -> {
                // a file that goes on to list its clips is shown by them
                val last = all.lastOrNull()
                if (last != null && last.status == ItemStatus.WAITING) all[all.size - 1] = last.copy(status = ItemStatus.OPENED)
                all += ImportRow(e.name, ItemStatus.WAITING)
            }
            is ImportEvent.Done -> settle(e.anim.name, ImportRow(e.anim.name, ItemStatus.DONE, anim = e.anim))
            is ImportEvent.Skipped -> settle(e.name, ImportRow(e.name, ItemStatus.SKIPPED, e.why))
            is ImportEvent.Failed -> settle(e.name, ImportRow(e.name, ItemStatus.FAILED, e.why))
            // a pair's row is the "a + b" label read last; its result is named by the pair
            is ImportEvent.Paired -> settle(e.name, ImportRow("${AnimText.PARTNER}: ${e.name}", if (e.pair != null) ItemStatus.DONE else ItemStatus.SKIPPED,
                AnimText.pairedBy(e.mode), pair = e.pair))
            // the clip has its own row already (converted alone): only remembered for pairing by hand
            is ImportEvent.Unpaired -> halves += e
        }
    }

    /** Called when the pipeline returns: a file that produced nothing is said to have failed, never left "Reading". */
    fun finish() {
        finished = true
        for (i in all.indices) if (all[i].status == ItemStatus.WAITING) {
            all[i] = all[i].copy(status = ItemStatus.FAILED, note = "No animation could be read from it.")
        }
    }

    /** Counts of each result, e.g. "3 converted, 1 skipped, 2 failed". */
    fun summary(): String {
        val r = rows
        val done = r.count { it.status == ItemStatus.DONE && it.pair == null }
        val paired = r.count { it.status == ItemStatus.DONE && it.pair != null }
        val skipped = r.count { it.status == ItemStatus.SKIPPED }
        val failed = r.count { it.status == ItemStatus.FAILED }
        val parts = listOfNotNull(
            "$done converted", paired.takeIf { it > 0 }?.let { "$it partner" }, skipped.takeIf { it > 0 }?.let { "$it skipped" },
            failed.takeIf { it > 0 }?.let { "$it failed" },
        )
        return parts.joinToString(", ")
    }
}

// ------------------------------------------------------------------------ the review and the save

/** What the review needs of the library: AnimLibrary's addResult (its duplicate policy) and categories. */
interface AnimStore {
    fun add(a: Anim): AddResult
    fun categories(): List<String>
}

class ReviewItem(val anim: Anim, var name: String, var category: String, var include: Boolean = true)

sealed interface SaveOutcome {
    val name: String
    data class Added(override val name: String, val renamedFrom: String?) : SaveOutcome
    data class Updated(override val name: String) : SaveOutcome
    data class Duplicate(override val name: String, val existing: String) : SaveOutcome
    data class Failed(override val name: String, val why: String) : SaveOutcome
}

/**
 * The review screen's state: each converted animation with its name (default: its own) and category (existing or new;
 * [setAllCategories] for a batch); [save] adds the included ones to the library, whose duplicate policy decides the rest.
 */
class ReviewDraft(anims: List<Anim>, defaultCategory: String? = null) {
    val items: List<ReviewItem> = anims.map {
        ReviewItem(it, AnimText.name(it.name, it.name), AnimText.category(defaultCategory ?: it.category.ifBlank { AnimText.DEFAULT_CATEGORY }))
    }

    fun setName(i: Int, name: String) { items.getOrNull(i)?.name = name }
    fun setCategory(i: Int, category: String) { items.getOrNull(i)?.category = category }
    fun setAllCategories(category: String) { for (it in items) it.category = category }
    fun toggle(i: Int) { items.getOrNull(i)?.let { it.include = !it.include } }
    val chosen: Int get() = items.count { it.include }

    /** Leaves out the singles made from these clips (their sources): they were paired by hand. */
    fun exclude(sources: Collection<String>) { for (it in items) if (it.anim.source in sources) it.include = false }

    /** The categories to offer: the library's, those these items use now, and the default; sorted. */
    fun categoryChoices(existing: List<String>): List<String> =
        (existing + items.map { AnimText.category(it.category) } + AnimText.DEFAULT_CATEGORY).distinctBy { it.lowercase() }.sortedBy { it.lowercase() }

    fun save(store: AnimStore): List<SaveOutcome> = items.filter { it.include }.map { item ->
        val name = AnimText.name(item.name, item.anim.name)
        try {
            val r = store.add(item.anim.copy(name = name, category = AnimText.category(item.category)))
            when {
                r.updated -> SaveOutcome.Updated(r.anim.name)
                r.duplicate -> SaveOutcome.Duplicate(name, r.anim.name)
                r.anim.name != name -> SaveOutcome.Added(r.anim.name, name)
                else -> SaveOutcome.Added(r.anim.name, null)
            }
        } catch (e: Exception) {
            SaveOutcome.Failed(name, e.message ?: e.toString())
        }
    }

    companion object {
        /** One line for the saved results: "2 added, 1 already in the library". */
        fun summary(r: List<SaveOutcome>): String {
            val added = r.count { it is SaveOutcome.Added }
            val updated = r.count { it is SaveOutcome.Updated }
            val dup = r.count { it is SaveOutcome.Duplicate }
            val failed = r.count { it is SaveOutcome.Failed }
            return listOfNotNull(
                "$added added", updated.takeIf { it > 0 }?.let { "$it updated" }, dup.takeIf { it > 0 }?.let { "$it already in the library (skipped)" }, failed.takeIf { it > 0 }?.let { "$it failed" },
            ).joinToString(", ")
        }
    }
}

// ------------------------------------------------------------------------ the library browser

object AnimBrowse {
    /** Animations matching [query] (words in the name, category or source file) in [category] (null: all), by category then name. */
    fun filter(all: List<Anim>, query: String, category: String?): List<Anim> {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return all.filter { a ->
            (category == null || a.category.equals(category, ignoreCase = true)) &&
                words.all { w -> a.name.lowercase().contains(w) || a.category.lowercase().contains(w) || a.source.lowercase().contains(w) }
        }.sortedWith(compareBy({ it.category.lowercase() }, { it.name.lowercase() }))
    }

    /** Each category with how many animations it holds, sorted. */
    fun categories(all: List<Anim>): List<Pair<String, Int>> =
        all.groupBy { it.category.lowercase() }.map { (_, v) -> v.first().category to v.size }.sortedBy { it.first.lowercase() }

    /**
     * The library screen's line about [stale] animations an older conversion made (AnimLibrary.stale), null when none: importing
     * their pack again (Import WickedWhims, or the same files) converts them anew and replaces them, names and categories kept.
     */
    fun staleNotice(stale: Int): String? = if (stale <= 0) null else
        "Re-import to update $stale animation${if (stale == 1) "" else "s"}: ${if (stale == 1) "it was" else "they were"} converted by an " +
            "older version (bodies on beds and seats could stand in one spot). Import WickedWhims again, or the same files: " +
            "names and categories are kept."

    /** "1.5 s, plays once" / "Pose" for the list's second line. */
    fun describe(a: Anim): String = when {
        a.duration < 0.2f -> "Pose"
        else -> "%.1f s, ".format(java.util.Locale.ROOT, a.duration) + if (a.loop) "loops" else "plays once"
    }
}

/** The library as the editor's pickers list it. */
object PoseChoices {
    /** (animation id, "Category: Name") for a Pose beat's picker. */
    fun items(all: List<Anim>): List<Pair<String, String>> =
        AnimBrowse.filter(all, "", null).map { it.id to "${it.category}: ${it.name}" }

    /** (Say emote value, label) for the Say beat's emote picker: "anim:ID" to "Pose: Name". */
    fun emotes(all: List<Anim>): List<Pair<String, String>> =
        AnimBrowse.filter(all, "", null).map { "anim:${it.id}" to "Pose: ${it.name}" }
}

// ------------------------------------------------------------------------ partner animations: review and pairing by hand

/** What the pair review needs of the library: AnimLibrary's addPairResult (its duplicate policy) and categories. */
interface PairStore {
    fun addPair(p: PairAnim): PairAddResult
    fun pairCategories(): List<String>
}

class PairReviewItem(val pair: PairAnim, var name: String, var category: String, var enabled: Boolean = true, var include: Boolean = true, val byHand: Boolean = false)

/**
 * The review's partner animations: the pairs found (name, category: "Partner" unless changed, enabled for scenes,
 * kept or skipped) and the clips that look like half of a pair, which the person can pair by hand: [pick] one as
 * actor A, another as actor B ([swap] exchanges them), then [confirm] converts them together.
 */
class PairDraft(pairs: List<PairAnim>, unpaired: List<ImportEvent.Unpaired>) {
    val items: MutableList<PairReviewItem> = pairs.map { PairReviewItem(it, AnimText.name(it.name, it.name), pairCategory(it.category)) }.toMutableList()
    val unpaired: MutableList<ImportEvent.Unpaired> = unpaired.distinctBy { it.clip }.toMutableList()

    /** The clips picked for actor A and actor B (indices into [unpaired]), null while not picked. */
    var pickA: Int? = null
        private set
    var pickB: Int? = null
        private set

    val isEmpty: Boolean get() = items.isEmpty() && unpaired.isEmpty()
    val chosen: Int get() = items.count { it.include }
    val canPair: Boolean get() = pickA != null && pickB != null

    /** Tapping a clip: the first pick is A, the next B; tapping a picked one un-picks it. */
    fun pick(i: Int) {
        if (i !in unpaired.indices) return
        when (i) {
            pickA -> { pickA = pickB; pickB = null }
            pickB -> pickB = null
            else -> if (pickA == null) pickA = i else pickB = i
        }
    }

    fun swap() { if (canPair) { val a = pickA; pickA = pickB; pickB = a } }

    fun clearPicks() { pickA = null; pickB = null }

    /** "A: clip · B: clip" while picking, for the confirm row. */
    fun pickLabel(): String {
        fun n(i: Int?) = i?.let { unpaired.getOrNull(it)?.clip?.name?.substringAfterLast(':') } ?: "(tap a clip)"
        return "A: ${n(pickA)} · B: ${n(pickB)}"
    }

    /**
     * Converts the picked clips together with [convert] (ImportPipeline.pairByHand). On success the pair joins
     * [items] and the two clips leave [unpaired]; returns null, or the reason it failed (the picks stay).
     */
    fun confirm(convert: (SimsClip, SimsClip) -> PairAnim): String? {
        val ia = pickA ?: return "Pick actor A first."
        val ib = pickB ?: return "Pick actor B."
        val a = unpaired[ia]
        val b = unpaired[ib]
        val p = try { convert(a.clip, b.clip) } catch (e: Exception) { return e.message ?: e.toString() }
        items += PairReviewItem(p, AnimText.name(p.name, p.name), pairCategory(p.category), byHand = true)
        unpaired.removeAll { it === a || it === b }
        clearPicks()
        return null
    }

    fun setName(i: Int, name: String) { items.getOrNull(i)?.name = name }
    fun setCategory(i: Int, category: String) { items.getOrNull(i)?.category = category }
    fun toggle(i: Int) { items.getOrNull(i)?.let { it.include = !it.include } }
    fun setEnabled(i: Int, on: Boolean) { items.getOrNull(i)?.enabled = on }

    fun categoryChoices(existing: List<String>): List<String> =
        (existing + items.map { pairCategory(it.category) } + AnimText.PARTNER).distinctBy { it.lowercase() }.sortedBy { it.lowercase() }

    fun save(store: PairStore): List<SaveOutcome> = items.filter { it.include }.map { item ->
        val name = AnimText.name(item.name, item.pair.name)
        try {
            val r = store.addPair(item.pair.copy(name = name, category = pairCategory(item.category), enabled = item.enabled))
            when {
                r.updated -> SaveOutcome.Updated(r.pair.name)
                r.duplicate -> SaveOutcome.Duplicate(name, r.pair.name)
                r.pair.name != name -> SaveOutcome.Added(r.pair.name, name)
                else -> SaveOutcome.Added(r.pair.name, null)
            }
        } catch (e: Exception) {
            SaveOutcome.Failed(name, e.message ?: e.toString())
        }
    }

    companion object {
        fun pairCategory(s: String): String = AnimText.tidy(s, AnimText.MAX_CATEGORY).ifEmpty { AnimText.PARTNER }
    }
}

/** The library's partner animations, as the Animations screen's Partner tab lists them. */
object PairBrowse {
    fun filter(all: List<PairAnim>, query: String, category: String?): List<PairAnim> {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return all.filter { p ->
            (category == null || p.category.equals(category, ignoreCase = true)) &&
                words.all { w -> p.name.lowercase().contains(w) || p.category.lowercase().contains(w) || p.source.lowercase().contains(w) }
        }.sortedWith(compareBy({ it.category.lowercase() }, { it.name.lowercase() }))
    }

    fun categories(all: List<PairAnim>): List<Pair<String, Int>> =
        all.groupBy { it.category.lowercase() }.map { (_, v) -> v.first().category to v.size }.sortedBy { it.first.lowercase() }

    /** "Pose for two" / "2.5 s, loops, 3 sounds, for BED" / "... off" for the list's second line. */
    fun describe(p: PairAnim): String {
        val base = if (p.duration < 0.2f) "Pose for two" else "%.1f s, ".format(java.util.Locale.ROOT, p.duration) + if (p.loops) "loops" else "plays once"
        val extra = listOfNotNull(
            p.sounds.size.takeIf { it > 0 }?.let { if (it == 1) "1 sound" else "$it sounds" },
            p.locations.takeIf { it.isNotEmpty() }?.let { "for " + it.sorted().joinToString("/") },
            "off".takeIf { !p.enabled },
        )
        return (listOf(base) + extra).joinToString(", ")
    }
}
