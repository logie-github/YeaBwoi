package com.logie.wifecraft.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.logie.wifecraft.anim.Anim
import com.logie.wifecraft.anim.AnimLibrary
import com.logie.wifecraft.anim.PairAnim
import androidx.compose.foundation.layout.Row
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The animation library: search, filter by category, rename, move to another category, delete. Two tabs: single
 * animations, and Partner animations (two actors together), which can also be turned off for scenes. Not here: playing
 * an animation on a person (that needs a dressed actor on a stage); the scene builder's Pose beat has Preview for it.
 */
@Composable
fun AnimLibraryScreen(library: AnimLibrary, onImport: () -> Unit, onBack: () -> Unit) {
    var rev by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<Anim?>(null) }
    var moving by remember { mutableStateOf<Anim?>(null) }
    var newCategoryFor by remember { mutableStateOf<Anim?>(null) }
    var deleting by remember { mutableStateOf<Anim?>(null) }
    var partner by remember { mutableStateOf(false) }
    var renamingPair by remember { mutableStateOf<PairAnim?>(null) }
    var movingPair by remember { mutableStateOf<PairAnim?>(null) }
    var newPairCategoryFor by remember { mutableStateOf<PairAnim?>(null) }
    var deletingPair by remember { mutableStateOf<PairAnim?>(null) }
    // Compact library: animations still in the old, larger files converted to clip files (anim/ClipStore), with a progress line
    var compacting by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    @Suppress("UNUSED_EXPRESSION") rev

    // read and sorted once per change of the library, not on every letter typed in the search
    val all = remember(rev) { library.list() }
    val allPairs = remember(rev) { library.pairs() }
    val staleNotice = remember(rev) { AnimBrowse.staleNotice(library.stale()) }
    val legacy = remember(rev) { library.legacyCount() }
    val pairCats = remember(allPairs) { PairBrowse.categories(allPairs) }
    val cats = remember(all) { AnimBrowse.categories(all) }
    if (category != null && (if (partner) pairCats else cats).none { it.first.equals(category, true) }) category = null
    val shown = remember(all, query, category) { AnimBrowse.filter(all, query, category) }
    val shownPairs = remember(allPairs, query, category) { PairBrowse.filter(allPairs, query, category) }

    // Back closes an open dialog first (the screen stays), and only then leaves
    BackHandler {
        when {
            deleting != null -> deleting = null
            deletingPair != null -> deletingPair = null
            newPairCategoryFor != null -> newPairCategoryFor = null
            movingPair != null -> movingPair = null
            renamingPair != null -> renamingPair = null
            newCategoryFor != null -> newCategoryFor = null
            moving != null -> moving = null
            renaming != null -> renaming = null
            else -> onBack()
        }
    }
    EditorFrame("Animations", onBack, actions = { SmallButton("Import", onImport, width = 110.dp) }) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // clips an older conversion made: said plainly, with how to update them
            staleNotice?.let { Text(it, style = frizStyle(13.sp, WowColors.gold, false), modifier = Modifier.padding(horizontal = 4.dp)) }
            if (legacy > 0 || compacting != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(compacting ?: "$legacy animation${if (legacy == 1) " is" else "s are"} in the old, larger format.",
                    style = frizStyle(13.sp, WowColors.grey, false), modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
                SmallButton("Compact library", {
                    if (compacting == null) {
                        compacting = "Compacting…"
                        scope.launch {
                            val over = java.util.concurrent.atomic.AtomicBoolean(false)
                            withContext(Dispatchers.IO) {
                                library.compact { done, total -> scope.launch { if (!over.get()) compacting = "Compacting $done of $total…" } }
                            }
                            over.set(true)
                            compacting = null
                            rev++
                        }
                    }
                }, enabled = compacting == null, width = 170.dp)
            }
            // the two tabs: single animations, and partner animations (shown once there is one)
            if (allPairs.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EditorCard(onClick = { if (partner) { partner = false; category = null } }, modifier = Modifier.weight(1f), selected = !partner) {
                    Text("Single (${all.size})", style = frizStyle(15.sp), maxLines = 1)
                }
                EditorCard(onClick = { if (!partner) { partner = true; category = null } }, modifier = Modifier.weight(1f), selected = partner) {
                    Text("${AnimText.PARTNER} (${allPairs.size})", style = frizStyle(15.sp), maxLines = 1)
                }
            }
            if (partner && allPairs.isNotEmpty()) {
                EditorField(query, { query = it.take(60) }, "Search by name, category or file")
                PickRow(
                    "Category", listOf("" to "All (${allPairs.size})") + pairCats.map { it.first to "${it.first} (${it.second})" },
                    category ?: "", { category = it.ifEmpty { null } },
                )
                if (shownPairs.isEmpty()) Text("Nothing matches.", style = frizStyle(14.sp, WowColors.grey), modifier = Modifier.padding(8.dp))
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(shownPairs, key = { "pair_" + it.id }, contentType = { "pair" }) { p ->
                        EditorCard(onClick = null, modifier = Modifier.animateItem()) {
                            Column(Modifier.weight(1f)) {
                                Text(p.name, style = frizStyle(16.sp, if (p.enabled) WowColors.gold else WowColors.grey), maxLines = 1)
                                Text(p.category + " · " + PairBrowse.describe(p), style = frizStyle(12.sp, WowColors.grey, false), maxLines = 1)
                                WowCheckRow("Use in scenes", p.enabled, { on -> library.setPairEnabled(p.id, on); rev++ })
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                SmallButton("Rename", { renamingPair = p }, width = 92.dp)
                                SmallButton("Category", { movingPair = p }, width = 92.dp)
                                SmallButton("Delete", { deletingPair = p }, width = 92.dp)
                            }
                        }
                    }
                }
            } else if (all.isEmpty()) {
                Text("No animations yet. Import some from the title screen (Import Animations).", style = frizStyle(15.sp, WowColors.grey), modifier = Modifier.padding(8.dp))
            } else {
                EditorField(query, { query = it.take(60) }, "Search by name, category or file")
                PickRow(
                    "Category", listOf("" to "All (${all.size})") + cats.map { it.first to "${it.first} (${it.second})" },
                    category ?: "", { category = it.ifEmpty { null } },
                )
                if (shown.isEmpty()) Text("Nothing matches.", style = frizStyle(14.sp, WowColors.grey), modifier = Modifier.padding(8.dp))
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(shown, key = { it.id }, contentType = { "anim" }) { a ->
                        // a deleted or filtered-out row slides away and the rest close up, rather than snapping
                        EditorCard(onClick = null, modifier = Modifier.animateItem()) {
                            Column(Modifier.weight(1f)) {
                                Text(a.name, style = frizStyle(16.sp), maxLines = 1)
                                Text(a.category + " · " + AnimBrowse.describe(a), style = frizStyle(12.sp, WowColors.grey, false), maxLines = 1)
                            }
                            SmallButton("Rename", { renaming = a }, width = 92.dp)
                            SmallButton("Category", { moving = a }, width = 98.dp)
                            SmallButton("Delete", { deleting = a }, width = 82.dp)
                        }
                    }
                }
            }
        }
    }

    // each dialog keeps its last thing while it fades out (rememberHeld); key(): a new one never inherits the last one's typing
    rememberHeld(renaming)?.let { a ->
        key(a.id) {
            NameDialog("Rename", a.name, "Rename", { renaming = null }, visible = renaming != null) { n ->
                if (renaming == null) return@NameDialog
                renaming = null
                val name = AnimText.tidy(n, AnimText.MAX_NAME)
                if (name.isNotEmpty()) { library.rename(a.id, name); rev++ }
            }
        }
    }
    rememberHeld(moving)?.let { a ->
        val choices = remember(a, rev) { (library.categories() + AnimText.DEFAULT_CATEGORY).distinctBy { it.lowercase() }.sortedBy { it.lowercase() }.map { it to it } + ("\u0000new" to "New category…") }
        PickDialog("Category of ${a.name}", choices, a.category, { moving = null }, visible = moving != null) { c ->
            if (moving == null) return@PickDialog
            moving = null
            if (c == "\u0000new") newCategoryFor = a else { library.setCategory(a.id, c); rev++ }
        }
    }
    rememberHeld(newCategoryFor)?.let { a ->
        key(a.id) {
            NameDialog("New category", "", "Use", { newCategoryFor = null }, visible = newCategoryFor != null) { n ->
                if (newCategoryFor == null) return@NameDialog
                newCategoryFor = null
                library.setCategory(a.id, AnimText.category(n)); rev++
            }
        }
    }
    rememberHeld(renamingPair)?.let { p ->
        key("pair" + p.id) {
            NameDialog("Rename", p.name, "Rename", { renamingPair = null }, visible = renamingPair != null) { n ->
                if (renamingPair == null) return@NameDialog
                renamingPair = null
                val name = AnimText.tidy(n, AnimText.MAX_NAME)
                if (name.isNotEmpty()) { library.renamePair(p.id, name); rev++ }
            }
        }
    }
    rememberHeld(movingPair)?.let { p ->
        val choices = remember(p, rev) { (library.pairCategories() + AnimText.PARTNER).distinctBy { it.lowercase() }.sortedBy { it.lowercase() }.map { it to it } + ("\u0000new" to "New category…") }
        PickDialog("Category of ${p.name}", choices, p.category, { movingPair = null }, visible = movingPair != null) { c ->
            if (movingPair == null) return@PickDialog
            movingPair = null
            if (c == "\u0000new") newPairCategoryFor = p else { library.setPairCategory(p.id, c); rev++ }
        }
    }
    rememberHeld(newPairCategoryFor)?.let { p ->
        key("pair" + p.id) {
            NameDialog("New category", "", "Use", { newPairCategoryFor = null }, visible = newPairCategoryFor != null) { n ->
                if (newPairCategoryFor == null) return@NameDialog
                newPairCategoryFor = null
                library.setPairCategory(p.id, PairDraft.pairCategory(n)); rev++
            }
        }
    }
    rememberHeld(deletingPair)?.let { p ->
        ConfirmDialog("Delete the partner animation \"${p.name}\" for good?", "Delete", { deletingPair = null }, visible = deletingPair != null, onOk = {
            if (deletingPair != null) { deletingPair = null; library.deletePair(p.id); rev++ }
        })
    }
    rememberHeld(deleting)?.let { a ->
        ConfirmDialog("Delete \"${a.name}\" for good? A story that uses it will say it is missing.", "Delete", { deleting = null }, visible = deleting != null, onOk = {
            // a second tap on Delete while the question fades out deletes nothing more
            if (deleting != null) { deleting = null; library.delete(a.id); rev++ }
        })
    }
}
