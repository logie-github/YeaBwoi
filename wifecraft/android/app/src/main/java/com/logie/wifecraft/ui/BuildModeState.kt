package com.logie.wifecraft.ui

import com.logie.wifecraft.catalog.Asset
import com.logie.wifecraft.catalog.AssetKind
import com.logie.wifecraft.editor.PlaceKind
import com.logie.wifecraft.editor.Placement

// Pure Kotlin, no android.* and no Compose: what the editor's build mode does with its buttons (JVM-tested:
// unit-tests/.../ui/BuildModeStateTest). The tool cluster's modes and one-tap actions, the Eyedropper, the catalogue bar's
// round tabs and category chips and its spellbook flyout, the spellbook pages that slide in and fold to side tabs.

/** The tool cluster's buttons, in the order they stand. [glyph]: an atlas glyph or the housing editor's own button; [icon]: an item icon key. */
enum class BuildTool(val label: String, val tip: String, val mode: Boolean) {
    POINTER("Select", "Tap to select, drag to move it on the floor", true),
    ROTATE("Rotate", "Drag the ring to turn the selected thing", true),
    SCALE("Scale", "Drag a box to make it bigger or smaller", true),
    DELETE("Delete", "Delete the selected thing", false),
    EYEDROPPER("Eyedropper", "Tap a placed thing to pick it up as the next to place", true),
    DUPLICATE("Duplicate", "A copy beside the selected thing", false),
    LOCK("Lock", "Lock or unlock the selected thing", false),
    HIDE("Hide", "Hide or show the selected thing", false),
    EMOTE("Emote", "Give the selected person or creature an emote", false),
    FOCUS("Focus", "Bring the camera to the selected thing", false);

    /** The gizmo this mode shows (Select moves). */
    val gizmo: GizmoTool?
        get() = when (this) { POINTER -> GizmoTool.MOVE; ROTATE -> GizmoTool.ROTATE; SCALE -> GizmoTool.SCALE; else -> null }

    companion object {
        fun of(g: GizmoTool): BuildTool = when (g) { GizmoTool.MOVE -> POINTER; GizmoTool.ROTATE -> ROTATE; GizmoTool.SCALE -> SCALE }
    }
}

/** What a tap on a tool asks of the editor: nothing more (a mode was chosen), or an action on the selected thing. */
enum class ToolAction { NONE, DELETE, DUPLICATE, TOGGLE_LOCK, TOGGLE_HIDE, OPEN_EMOTES, FOCUS }

/**
 * The tool cluster: the mode it is in (Select, Rotate, Scale or Eyedropper) and the tool whose name shows in the tooltip
 * strip ([hint]: a long press, or the last tap). Modes stay until another is chosen; the others act once on the selected
 * thing and leave the mode as it was. Rotate and Scale on a locked thing, and every action without a selection, are off.
 */
data class ToolCluster(val mode: BuildTool = BuildTool.POINTER, val hint: BuildTool? = null) {

    /** Whether [t] can be pressed with the selection [sel] (null: nothing selected). */
    fun enabled(t: BuildTool, sel: Placement?): Boolean = when (t) {
        BuildTool.POINTER, BuildTool.EYEDROPPER -> true
        BuildTool.ROTATE, BuildTool.SCALE -> sel == null || !sel.locked
        BuildTool.EMOTE -> sel != null && sel.kind != PlaceKind.PROP
        BuildTool.DELETE, BuildTool.DUPLICATE, BuildTool.LOCK, BuildTool.HIDE, BuildTool.FOCUS -> sel != null
    }

    /** Whether [t] is drawn lit (gold ring): the mode, a locked thing's Lock, a hidden thing's Hide. */
    fun lit(t: BuildTool, sel: Placement?): Boolean = when (t) {
        BuildTool.LOCK -> sel?.locked == true
        BuildTool.HIDE -> sel?.hidden == true
        else -> t.mode && t == mode
    }

    /** A tap on [t]: the cluster after it, and what the editor does. A disabled tool does nothing. */
    fun tap(t: BuildTool, sel: Placement?): Pair<ToolCluster, ToolAction> {
        if (!enabled(t, sel)) return copy(hint = t) to ToolAction.NONE
        if (t.mode) return copy(mode = t, hint = t) to ToolAction.NONE
        val a = when (t) {
            BuildTool.DELETE -> ToolAction.DELETE
            BuildTool.DUPLICATE -> ToolAction.DUPLICATE
            BuildTool.LOCK -> ToolAction.TOGGLE_LOCK
            BuildTool.HIDE -> ToolAction.TOGGLE_HIDE
            BuildTool.EMOTE -> ToolAction.OPEN_EMOTES
            BuildTool.FOCUS -> ToolAction.FOCUS
            else -> ToolAction.NONE
        }
        return copy(hint = t) to a
    }

    /** A long press: the tool's name and what it does in the tooltip strip. */
    fun longPress(t: BuildTool): ToolCluster = copy(hint = t)

    fun clearHint(): ToolCluster = copy(hint = null)

    /** The Eyedropper took a thing: back to Select (the picked thing is now armed for placing). */
    fun picked(): ToolCluster = copy(mode = BuildTool.POINTER)

    /** The gizmo mode the editor's handles follow (Select's Move while the Eyedropper is on). */
    val gizmo: GizmoTool get() = mode.gizmo ?: GizmoTool.MOVE

    /** A tap on the stage picks a thing up (instead of selecting) while the Eyedropper is on. */
    val eyedropping: Boolean get() = mode == BuildTool.EYEDROPPER

    /** The tooltip strip's words: the hint's name and what it does, or the mode's. */
    fun tip(sel: Placement?): String {
        val t = hint ?: mode
        val why = when {
            enabled(t, sel) -> t.tip
            t == BuildTool.EMOTE && sel != null -> "Only people and creatures have emotes"
            (t == BuildTool.ROTATE || t == BuildTool.SCALE) && sel?.locked == true -> "Unlock it first"
            else -> "Select something first"
        }
        return "${t.label}: $why"
    }
}

/**
 * The Eyedropper: the library entry a placed thing [p] was made from, to arm it for placing again. A prop or building is
 * found by its file ("p:" then "b:"), a creature by its display (a named creature with it, else its creature model), an
 * NPC of the cast by its actor ("n:"); a thing no longer in the library comes back as an entry made from the placement
 * itself, so it can still be placed.
 */
object Eyedropper {
    fun assetFor(p: Placement, find: (String) -> Asset?, byDisplay: (Int) -> Asset? = { null }): Asset = when (p.kind) {
        PlaceKind.PROP -> find("p:${p.asset}") ?: find("b:${p.asset}") ?: Asset("p:${p.asset}", AssetKind.PROP, p.asset, baseName(p.name), "")
        PlaceKind.CREATURE -> byDisplay(p.asset) ?: Asset("d:${p.asset}", AssetKind.CREATURE_MODEL, 0, baseName(p.name), "", display = p.asset)
        PlaceKind.NPC -> {
            val id = p.actorId.orEmpty()
            find("n:$id") ?: Asset("n:$id", AssetKind.OWN_NPC, 0, baseName(p.name), com.logie.wifecraft.catalog.Catalog.MY_PEOPLE)
        }
    }

    /** "Barrel (copy) (copy)" -> "Barrel": the name a copy of a copy started from. */
    fun baseName(name: String): String = name.replace(Regex("(\\s*\\(copy\\))+$"), "").ifBlank { name }
}

/** One round tab of the catalogue bar: its tab, its icon key (WarIcons), its label and how many it holds. */
data class CatalogueTab(val tab: LibraryTab, val icon: String, val label: String, val count: Int)

/** One chip of the category row: a tree row ([node]; null for "All"), lit when it is the one chosen. [more]: opens the flyout. */
data class CategoryChip(val id: String, val label: String, val count: Int, val icon: String?, val on: Boolean, val node: RailNode?, val more: Boolean = false)

/**
 * The catalogue bar along the bottom: [open] (its cards shown) or folded to its tabs; the category [flyout] (the spellbook
 * page with the whole tree of the chosen tab) open or not; the search box [searching] open or not.
 */
data class CatalogueBar(val open: Boolean = true, val flyout: Boolean = false, val searching: Boolean = false) {
    fun toggle(): CatalogueBar = if (open) copy(open = false, flyout = false) else copy(open = true)
    fun openFlyout(): CatalogueBar = copy(open = true, flyout = true)
    fun closeFlyout(): CatalogueBar = copy(flyout = false)
    fun search(on: Boolean): CatalogueBar = copy(searching = on, open = true, flyout = false)

    companion object {
        /** The tabs in the Sims' order: Starter pack, Props, Buildings, Creatures, My NPCs, Favourites, Recent (counts from the tree). */
        val ORDER = listOf(LibraryTab.STARTER, LibraryTab.PROPS, LibraryTab.BUILDINGS, LibraryTab.CREATURES, LibraryTab.MY_NPCS,
            LibraryTab.FAVOURITES, LibraryTab.RECENT)

        fun tabs(tree: List<RailNode>): List<CatalogueTab> = ORDER.map { t ->
            val n = tree.firstOrNull { it.tab == t && it.category == null && it.style == null }
            CatalogueTab(t, WarIcons.tab(t), n?.label ?: t.label, n?.count ?: 0)
        }

        /**
         * The chip row for [q]'s tab: "All" first, then the tab's categories (most entries first, as the tree has them) up to
         * [most], then "More…" when the tab has more categories or any styles (it opens the flyout). The chosen category or
         * style is always in the row (moved to just after "All" when it is beyond [most]). A tab with no tree under it (the
         * Starter pack, My NPCs, Favourites, Recent) has no chips.
         */
        fun chips(tree: List<RailNode>, q: LibraryQuery, most: Int = 8): List<CategoryChip> {
            val kind = tree.firstOrNull { it.tab == q.tab && it.category == null && it.style == null } ?: return emptyList()
            if (kind.children.isEmpty()) return emptyList()
            val sel = LibraryModel.selectedId(q)
            val cats = kind.children.filter { it.category != null }
            var shown = cats.take(most)
            val chosen = kind.children.firstOrNull { it.id == sel }
            if (chosen != null && chosen !in shown) shown = listOf(chosen) + shown.take(most - 1)
            val out = ArrayList<CategoryChip>()
            out += CategoryChip(kind.id, "All", kind.count, null, sel == kind.id, kind)
            for (c in shown) out += CategoryChip(c.id, c.label, c.count, c.category?.let(WarIcons::category), c.id == sel, c)
            val rest = kind.children.size - shown.size
            if (rest > 0) out += CategoryChip("${kind.id}:more", "More…", rest, null, false, null, more = true)
            return out
        }

        /** The flyout's sections for [q]'s tab: its categories, then its styles (each a list of tree rows). */
        fun flyout(tree: List<RailNode>, q: LibraryQuery): List<Pair<String, List<RailNode>>> {
            val kind = tree.firstOrNull { it.tab == q.tab && it.category == null && it.style == null } ?: return emptyList()
            return listOf("Categories" to kind.children.filter { it.category != null }, "Styles" to kind.children.filter { it.style != null })
                .filter { it.second.isNotEmpty() }
        }
    }
}

/** The spellbook pages on the right: Things (the outliner), Selected (the inspector), Animation (the emotes). */
enum class BookTab(val label: String, val icon: String) {
    THINGS("Things", "tool.things"), SELECTED("Selected", "tool.focus"), ANIMATION("Animation", "tool.emote");
}

/**
 * Which page is open, or none ([open] false: the pages are folded to their side tabs). Animation is only offered for a
 * selected person or creature; a page that stops being offered falls back to Selected (or Things with nothing selected).
 * The pages fold by themselves while the catalogue's flyout is open or a thing is being placed, and come back after.
 */
data class BookPages(val open: Boolean = true, val tab: BookTab = BookTab.THINGS) {
    fun offered(t: BookTab, sel: Placement?): Boolean = t != BookTab.ANIMATION || (sel != null && sel.kind != PlaceKind.PROP)

    fun tabs(sel: Placement?): List<BookTab> = BookTab.values().filter { offered(it, sel) }

    /** A side tab tapped: that page opens; tapping the open page's tab folds it. */
    fun tap(t: BookTab): BookPages = if (open && tab == t) copy(open = false) else copy(open = true, tab = t)

    fun fold(): BookPages = copy(open = false)

    /** The page to show for [sel] (Animation gone: Selected, or Things when nothing is selected). */
    fun shown(sel: Placement?): BookTab = when {
        offered(tab, sel) -> tab
        sel != null -> BookTab.SELECTED
        else -> BookTab.THINGS
    }

    /** Something was selected on the stage: Things moves on to Selected (as the old panel did). */
    fun selected(sel: Placement?): BookPages = if (sel != null && tab == BookTab.THINGS) copy(tab = BookTab.SELECTED) else this

    /** Whether the page is drawn now: open, and neither the flyout nor placing is over the stage. */
    fun visible(flyout: Boolean, placing: Boolean): Boolean = open && !flyout && !placing
}
