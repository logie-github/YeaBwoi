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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.logie.wifecraft.anim.Anim
import com.logie.wifecraft.anim.AnimLibrary

/**
 * The animation library: search, filter by category, rename, move to another category, delete. Not here: playing an
 * animation on a person (that needs a dressed actor on a stage); the scene builder's Pose beat has Preview for it.
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
    @Suppress("UNUSED_EXPRESSION") rev

    // read and sorted once per change of the library, not on every letter typed in the search
    val all = remember(rev) { library.list() }
    val cats = remember(all) { AnimBrowse.categories(all) }
    if (category != null && cats.none { it.first.equals(category, true) }) category = null
    val shown = remember(all, query, category) { AnimBrowse.filter(all, query, category) }

    // Back closes an open dialog first (the screen stays), and only then leaves
    BackHandler {
        when {
            deleting != null -> deleting = null
            newCategoryFor != null -> newCategoryFor = null
            moving != null -> moving = null
            renaming != null -> renaming = null
            else -> onBack()
        }
    }
    EditorFrame("Animations", onBack, actions = { SmallButton("Import", onImport, width = 110.dp) }) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (all.isEmpty()) {
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
    rememberHeld(deleting)?.let { a ->
        ConfirmDialog("Delete \"${a.name}\" for good? A story that uses it will say it is missing.", "Delete", { deleting = null }, visible = deleting != null, onOk = {
            // a second tap on Delete while the question fades out deletes nothing more
            if (deleting != null) { deleting = null; library.delete(a.id); rev++ }
        })
    }
}
