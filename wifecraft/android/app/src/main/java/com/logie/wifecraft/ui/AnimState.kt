package com.logie.wifecraft.ui

import com.logie.wifecraft.anim.AddResult
import com.logie.wifecraft.anim.Anim
import com.logie.wifecraft.anim.ImportEvent
import java.io.File

// Pure Kotlin, no android.* and no Compose: what the Import Animations and Animations screens (AnimImportScreen,
// AnimLibraryScreen) decide, so the JVM tests (unit-tests/.../ui/AnimStateTest.kt) cover it. Conversion itself is
// in anim/ (ImportPipeline, Retargeter); this file only follows its events and saves its results.

object AnimText {
    /** The category an imported animation gets when the author picks none. */
    const val DEFAULT_CATEGORY = "Imported"
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

data class ImportRow(val name: String, val status: ItemStatus, val note: String = "", val anim: Anim? = null)

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
        val done = r.count { it.status == ItemStatus.DONE }
        val skipped = r.count { it.status == ItemStatus.SKIPPED }
        val failed = r.count { it.status == ItemStatus.FAILED }
        val parts = listOfNotNull(
            "$done converted", skipped.takeIf { it > 0 }?.let { "$it skipped" }, failed.takeIf { it > 0 }?.let { "$it failed" },
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
