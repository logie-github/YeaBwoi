package com.logie.wifecraft.pack

import com.logie.wifecraft.anim.AddResult
import com.logie.wifecraft.anim.AnimLibrary
import com.logie.wifecraft.anim.ImportEvent
import com.logie.wifecraft.anim.ImportInput
import com.logie.wifecraft.anim.ImportPipeline
import com.logie.wifecraft.anim.ImportSpace
import com.logie.wifecraft.anim.PairAddResult
import com.logie.wifecraft.anim.PairAnim
import com.logie.wifecraft.anim.Retarget
import com.logie.wifecraft.props.PropStore
import com.logie.wifecraft.sims.SimsClip
import com.logie.wifecraft.sims.SimsRig
import com.logie.wifecraft.ui.AnimStore
import com.logie.wifecraft.ui.PairDraft
import com.logie.wifecraft.ui.PairStore
import com.logie.wifecraft.ui.ReviewDraft
import com.logie.wifecraft.ui.SaveOutcome
import java.io.File

/**
 * Animation packages into the animation library, through the same importer as Choose objects → Animation library → Import rig
 * and clips ([ImportPipeline]: the retarget onto the Warcraft skeleton, partner pairing from the stage tuning, the checks), then
 * saved under the entry's category without the review step (as WickedWhims' own pack is). Needs the Sims skeleton ([rig]): without
 * one the entry is skipped with what to do. The package's stages are also read for their props and the clips that move them
 * ([props]), whether or not the skeleton is there. Entries for three or more people are not played by a scene: the importer
 * leaves them out and this reports how many.
 */
class AnimationHandler(
    private val library: AnimLibrary,
    private val rig: () -> SimsRig?,
    private val retargeter: (SimsRig) -> Retarget,
    private val tmp: File,
    private val props: PropStore?,
) : PackHandler {
    override fun handle(entry: PackEntry, file: File, content: PackContent?): HandlerResult {
        val propNote = props?.let { runCatching { it.importPackage(file, PackKind.ANIMATIONS, entry.source) }.getOrNull() }
            ?.takeIf { it.clips > 0 || it.uses > 0 }?.let { "; ${it.uses} prop uses and ${it.clips} prop animations read" }.orEmpty()
        val sims = rig() ?: return HandlerResult(EntryStatus.SKIPPED,
            "needs a Sims 4 skeleton: import WickedWhims first (Animation library → Import WickedWhims) or load one under Import rig and clips, then import the pack again$propNote")
        ImportSpace.refusal(library.freeBytes())?.let { return HandlerResult(EntryStatus.FAILED, it) }
        val staging = library.newStaging()
        try {
            val skipped = LinkedHashMap<String, Int>()
            var failed = 0
            val pipeline = ImportPipeline(retargeter(sims), tmp, staging = staging)
            val result = pipeline.runAll(listOf(ImportInput(entry.file) { file.inputStream() })) { e ->
                when (e) {
                    is ImportEvent.Skipped -> skipped.merge(reasonOf(e.why), 1, Int::plus)
                    is ImportEvent.Failed -> failed++
                    else -> Unit
                }
            }
            val category = entry.category.ifBlank { "Imported" }
            val singles = ReviewDraft(result.anims, category).save(libraryStore())
            val pairs = PairDraft(result.pairs, emptyList()).save(libraryPairStore())
            val added = singles.count { it is SaveOutcome.Added || it is SaveOutcome.Updated } to pairs.count { it is SaveOutcome.Added || it is SaveOutcome.Updated }
            val dup = singles.count { it is SaveOutcome.Duplicate } + pairs.count { it is SaveOutcome.Duplicate }
            val bad = singles.count { it is SaveOutcome.Failed } + pairs.count { it is SaveOutcome.Failed } + failed
            val big = entryGroupsOver2(content)
            val parts = ArrayList<String>()
            parts += "${added.first} clips and ${added.second} partner animations added to \"$category\""
            if (dup > 0) parts += "$dup already there"
            if (skipped.isNotEmpty()) parts += "left out: " + skipped.entries.joinToString("; ") { "${it.value} ${it.key}" }
            if (big > 0) parts += "$big stages for three or more people are not played by a scene"
            if (bad > 0) parts += "$bad failed"
            val status = if (added.first + added.second == 0 && dup == 0) EntryStatus.FAILED else EntryStatus.IMPORTED
            return HandlerResult(status, parts.joinToString(", ") + propNote)
        } finally {
            runCatching { staging.delete() }
        }
    }

    private fun entryGroupsOver2(c: PackContent?): Int = c?.stages?.count { it.actors.size > 2 } ?: 0

    private fun reasonOf(why: String): String = when {
        why.contains("three or more", true) -> "stages for three or more people"
        why.contains("prop animation", true) || why.contains("furniture or prop", true) -> "prop animations (drawn with their props)"
        else -> why.take(60)
    }

    private fun libraryStore() = object : AnimStore {
        override fun add(a: com.logie.wifecraft.anim.Anim): AddResult = library.addResult(a)
        override fun categories() = library.categories()
        override fun <T> batch(block: () -> T): T = library.batch(block)
    }

    private fun libraryPairStore() = object : PairStore {
        override fun addPair(p: PairAnim): PairAddResult = library.addPairResult(p)
        override fun pairCategories() = library.pairCategories()
        override fun <T> batch(block: () -> T): T = library.batch(block)
    }
}

/**
 * Object packages (toys, dolls, handcuffs, the bed, the cum mesh) into the prop store: each object's first-LOD meshes and its
 * diffuse texture (the variant its definition names) as an engine prop file, the clips that move it, and the stage links.
 * [kind] says what the pack calls them; a bed or any [PackKind.FURNITURE] model is also what Build mode's New tab can place
 * ([PropStore.index]).
 */
class ObjectHandler(private val store: PropStore, private val kind: PackKind) : PackHandler {
    override fun handle(entry: PackEntry, file: File, content: PackContent?): HandlerResult {
        val r = store.importPackage(file, kind, entry.source)
        val parts = ArrayList<String>()
        parts += "${r.objects} ${if (kind == PackKind.FURNITURE) "furniture models" else "props"} loaded"
        if (r.clips > 0) parts += "${r.clips} prop animations"
        if (r.uses > 0) parts += "${r.uses} stage links"
        if (r.problems.isNotEmpty()) parts += "${r.problems.size} problems (${r.problems.first()})"
        val status = if (r.objects > 0) EntryStatus.IMPORTED else EntryStatus.FAILED
        return HandlerResult(status, if (status == EntryStatus.FAILED) "no model could be read" + (r.problems.firstOrNull()?.let { ": $it" } ?: "") else parts.joinToString(", "))
    }
}

/** A script's note: the text is the pack builder's, shown as it is; nothing is run. */
class ScriptNoteHandler : PackHandler {
    override fun handle(entry: PackEntry, file: File, content: PackContent?): HandlerResult {
        val first = runCatching { file.readLines().firstOrNull { it.isNotBlank() } }.getOrNull().orEmpty().take(120)
        return HandlerResult(EntryStatus.IMPORTED, "read as a note, never run" + if (first.isNotEmpty()) ": $first" else "")
    }
}
