package com.logie.wifecraft.ui

import com.logie.wifecraft.editor.PlaceKind
import com.logie.wifecraft.editor.Placement
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

// Pure Kotlin, no android.* and no Compose: the editor's and the app's menus laid out as World of Warcraft's own (JVM-tested:
// unit-tests/.../ui/MenuModelTest). On screen while editing only the scene, a Menu button, Undo, Redo and Play, and an
// action bar of six slots (Select, Rotate, Scale, Delete, Add, More); everything else is one tap further: in More's dropdown,
// in the selected thing's long-press dropdown, in the Game Menu, in windows (the Library, Objects in this scene, Emotes,
// Options, Projects). The Compose side (WowMenus.kt, EditorPlacement.kt, TitleScreen.kt, Menus.kt) draws what these say.

/** The action bar's six slots, in the order they stand. [tool]: the cluster's tool a slot stands for (Add and More have none). */
enum class ActionSlot(val label: String, val tip: String, val tool: BuildTool?) {
    SELECT("Select", "Tap to select, drag to move it on the floor", BuildTool.POINTER),
    ROTATE("Rotate", "Drag the ring to turn the selected thing", BuildTool.ROTATE),
    SCALE("Scale", "Drag a box to make it bigger or smaller", BuildTool.SCALE),
    DELETE("Delete", "Delete the selected thing", BuildTool.DELETE),
    ADD("Add", "Open the Library: props, buildings, people", null),
    MORE("More", "Duplicate, lock, hide, snap, the camera, the stage…", null);

    companion object {
        /** At most six: what the bar holds. */
        const val MAX = 6
    }
}

/** What the editor shows over the scene besides the action bar: nothing, one window or one dropdown at a time. */
enum class EditorWindow {
    NONE,
    /** The Library window (props, buildings, people, favourites). */
    LIBRARY,
    /** The More slot's dropdown. */
    MORE,
    /** The selected thing's long-press dropdown. */
    CONTEXT,
    /** The Game Menu. */
    GAME_MENU,
    /** Objects in this scene (the outliner). */
    OBJECTS,
    /** The selected person's or creature's emotes. */
    EMOTES;

    /** A window that covers the scene (the action bar and the top buttons are put away under it; a tap outside closes it). */
    val covers: Boolean get() = this == LIBRARY || this == GAME_MENU || this == OBJECTS || this == EMOTES
}

/**
 * The editor's chrome: which window is open ([window]), the selected thing's [details] folded open in its tooltip, the More
 * dropdown's and the context menu's anchors. One window at a time; the Back key closes what is open, and with nothing open
 * opens the Game Menu (as Escape does in the game); Back on the Game Menu is Resume.
 */
data class EditorChrome(val window: EditorWindow = EditorWindow.NONE, val details: Boolean = false) {
    fun open(w: EditorWindow): EditorChrome = copy(window = w)
    /** Tapping a slot or a menu button that opens [w]: opens it, or closes it when it is already open. */
    fun toggle(w: EditorWindow): EditorChrome = copy(window = if (window == w) EditorWindow.NONE else w)
    fun close(): EditorChrome = copy(window = EditorWindow.NONE)
    fun toggleDetails(): EditorChrome = copy(details = !details)

    /** The Back key: closes the open window, else opens the Game Menu. */
    fun back(): EditorChrome = if (window != EditorWindow.NONE) close() else open(EditorWindow.GAME_MENU)

    /** The action bar and the top buttons are drawn (not under a covering window). */
    val barShown: Boolean get() = !window.covers

    /** The selected thing's tooltip is drawn: something is selected, nothing is being placed, no window or dropdown is over it. */
    fun inspectorShown(selected: Boolean, placing: Boolean): Boolean = selected && !placing && window == EditorWindow.NONE

    companion object {
        /**
         * How many things can be tapped on the editor's screen (the scene itself not counted): Menu, Undo, Redo, Play, the six
         * slots; the tip's Got it when a tip shows; the selected thing's tooltip (Emote for a person or creature, Details).
         */
        fun tappables(chrome: EditorChrome, sel: Placement?, tip: Boolean, placing: Boolean): Int {
            if (!chrome.barShown) return 0
            var n = 1 + 3 + ActionSlot.values().size
            if (tip) n += 1
            if (chrome.inspectorShown(sel != null, placing)) n += (if (sel?.kind != PlaceKind.PROP) 2 else 1)
            return n
        }
    }
}

/** What a slot does: enabled with the selection [sel] and the cluster, lit for the mode (or the open dropdown/window). */
object ActionBarModel {
    fun enabled(s: ActionSlot, cluster: ToolCluster, sel: Placement?): Boolean = s.tool?.let { cluster.enabled(it, sel) } ?: true

    fun lit(s: ActionSlot, cluster: ToolCluster, chrome: EditorChrome, placing: Boolean): Boolean = when (s) {
        ActionSlot.ADD -> placing || chrome.window == EditorWindow.LIBRARY
        ActionSlot.MORE -> chrome.window == EditorWindow.MORE || cluster.eyedropping
        else -> s.tool != null && s.tool.mode && cluster.mode == s.tool
    }
}

/** Everything the More dropdown and the context menu can ask for. */
enum class MenuAction(val icon: String?) {
    DUPLICATE("tool.duplicate"), LOCK(WarIcons.LOCK), HIDE("tool.hide"), EMOTE("tool.emote"), FOCUS("tool.focus"),
    DETAILS("menu.scene"), DELETE(WarIcons.DELETE),
    EYEDROPPER("tool.eyedropper"), SNAP(null), SHOW_HIDDEN(null),
    HOME_VIEW("tool.camera"), OBJECTS("tool.things"), STAGE("tool.stage"), TIPS("tool.tips");
}

/** One row of a WoW dropdown: its words, an icon (an icon key or a glyph), a check box ([checked] not null), dimmed, red, a rule before it. */
data class MenuRow(
    val action: MenuAction,
    val label: String,
    val icon: String?,
    val enabled: Boolean = true,
    val checked: Boolean? = null,
    val danger: Boolean = false,
    val ruleBefore: Boolean = false,
)

object EditorMenus {
    private fun selRows(sel: Placement?, rule: Boolean): List<MenuRow> {
        val has = sel != null
        val living = has && sel!!.kind != PlaceKind.PROP
        return listOf(
            MenuRow(MenuAction.DUPLICATE, "Duplicate", MenuAction.DUPLICATE.icon, has, ruleBefore = rule),
            MenuRow(MenuAction.LOCK, if (sel?.locked == true) "Unlock" else "Lock", MenuAction.LOCK.icon, has),
            MenuRow(MenuAction.HIDE, if (sel?.hidden == true) "Show" else "Hide", MenuAction.HIDE.icon, has),
            MenuRow(MenuAction.EMOTE, "Emote…", MenuAction.EMOTE.icon, living),
            MenuRow(MenuAction.FOCUS, "Look at it", MenuAction.FOCUS.icon, has),
        )
    }

    /**
     * The More slot's dropdown, in three groups: the selected thing (Duplicate, Lock, Hide, Emote, Look at it: dimmed without
     * a selection), the tools (Eyedropper, Snap to grid, Show hidden things: check boxes), the view and the scene (Home view,
     * Objects in this scene, Stage, Tips again).
     */
    fun more(sel: Placement?, cluster: ToolCluster, snap: Boolean, showHidden: Boolean, hasStages: Boolean): List<MenuRow> =
        selRows(sel, false) + listOf(
            MenuRow(MenuAction.EYEDROPPER, "Eyedropper", null, checked = cluster.eyedropping, ruleBefore = true),
            MenuRow(MenuAction.SNAP, "Snap to grid", null, checked = snap),
            MenuRow(MenuAction.SHOW_HIDDEN, "Show hidden things", null, checked = showHidden),
            MenuRow(MenuAction.HOME_VIEW, "Home view", MenuAction.HOME_VIEW.icon, ruleBefore = true),
            MenuRow(MenuAction.OBJECTS, "Objects in this scene…", MenuAction.OBJECTS.icon),
        ) + (if (hasStages) listOf(MenuRow(MenuAction.STAGE, "Stage…", MenuAction.STAGE.icon)) else emptyList()) +
            MenuRow(MenuAction.TIPS, "Tips again", MenuAction.TIPS.icon)

    /** The long-press dropdown of [sel]: what can be done to it, Details, and Delete last in red. */
    fun context(sel: Placement): List<MenuRow> =
        selRows(sel, false).filter { it.action != MenuAction.EMOTE || it.enabled } + listOf(
            MenuRow(MenuAction.DETAILS, "Details…", MenuAction.DETAILS.icon),
            MenuRow(MenuAction.DELETE, "Delete", MenuAction.DELETE.icon, danger = true, ruleBefore = true),
        )

    /** A row's height and a rule's, in dp (48: a finger). */
    const val ROW = 48f
    const val RULE = 13f
    const val TITLE = 34f

    /** The height a dropdown of [rows] wants (title, rows, rules, padding), before it is cut to the room it has. */
    fun height(rows: List<MenuRow>, title: Boolean): Float =
        (if (title) TITLE else 6f) + rows.size * ROW + rows.count { it.ruleBefore } * RULE + 8f
}

// ------------------------------------------------------------------------------------------------ the Library window

/** The Library window's four tabs along its bottom edge. */
enum class LibraryWindowTab(val label: String) {
    PROPS("Props"), BUILDINGS("Buildings"), PEOPLE("People"), FAVOURITES("Favourites");

    companion object {
        /** The window's tab a query stands on (the Starter pack is Props' suggestions; Creatures and My NPCs are People; Recent is Favourites). */
        fun of(t: LibraryTab): LibraryWindowTab = when (t) {
            LibraryTab.STARTER, LibraryTab.PROPS -> PROPS
            LibraryTab.BUILDINGS -> BUILDINGS
            LibraryTab.CREATURES, LibraryTab.MY_NPCS -> PEOPLE
            LibraryTab.FAVOURITES, LibraryTab.RECENT -> FAVOURITES
        }
    }
}

/** One row of the category dropdown: its words, its count, the tree row it narrows to (null: the tab's whole kind). */
data class DropRow(val id: String, val label: String, val count: Int, val tab: LibraryTab, val node: RailNode?, val header: Boolean = false)

/**
 * The Library window: four tabs, one search box, one category dropdown with counts, a grid of cards. Props shows the place's
 * suggestions (its starter pack) on top; People holds your own NPCs and the game's creatures (the dropdown picks); Favourites
 * shows the recent picks on top.
 */
object LibraryWindow {
    /** The query once tab [t] is tapped (the words stay). People opens on your NPCs when you have any, else the creatures. */
    fun select(q: LibraryQuery, t: LibraryWindowTab, ownCount: Int): LibraryQuery = when (t) {
        LibraryWindowTab.PROPS -> q.withTab(LibraryTab.PROPS)
        LibraryWindowTab.BUILDINGS -> q.withTab(LibraryTab.BUILDINGS)
        LibraryWindowTab.PEOPLE -> if (LibraryWindowTab.of(q.tab) == LibraryWindowTab.PEOPLE) q.withTab(q.tab)
            else q.withTab(if (ownCount > 0) LibraryTab.MY_NPCS else LibraryTab.CREATURES)
        LibraryWindowTab.FAVOURITES -> q.withTab(LibraryTab.FAVOURITES)
    }

    /**
     * The dropdown's rows for [q]'s window tab: Props and Buildings: all of it, its categories (most first), then its styles;
     * People: your NPCs, all creatures, then the creatures' types; Favourites: none (its grid shows the recent picks, then the
     * favourites).
     */
    fun dropdown(tree: List<RailNode>, q: LibraryQuery): List<DropRow> {
        fun kind(t: LibraryTab) = tree.firstOrNull { it.tab == t && it.category == null && it.style == null }
        fun under(k: RailNode, allLabel: String): List<DropRow> {
            val cats = k.children.filter { it.category != null }.map { DropRow(it.id, it.label, it.count, k.tab, it) }
            val styles = k.children.filter { it.style != null }.map { DropRow(it.id, it.label, it.count, k.tab, it) }
            return listOf(DropRow(k.id, allLabel, k.count, k.tab, null)) + cats +
                (if (styles.isEmpty()) emptyList() else listOf(DropRow("${k.id}:styles", "Styles", 0, k.tab, null, header = true)) + styles)
        }
        return when (LibraryWindowTab.of(q.tab)) {
            LibraryWindowTab.PROPS -> kind(LibraryTab.PROPS)?.let { under(it, "All props") }.orEmpty()
            LibraryWindowTab.BUILDINGS -> kind(LibraryTab.BUILDINGS)?.let { under(it, "All buildings") }.orEmpty()
            LibraryWindowTab.PEOPLE -> {
                val mine = kind(LibraryTab.MY_NPCS)
                val creatures = kind(LibraryTab.CREATURES)
                listOfNotNull(mine?.let { DropRow(it.id, "Your NPCs", it.count, LibraryTab.MY_NPCS, null) }) +
                    (creatures?.let { under(it, "All creatures") }.orEmpty())
            }
            LibraryWindowTab.FAVOURITES -> emptyList()
        }
    }

    /** The row the dropdown shows as chosen for [q] (its button's words). */
    fun chosen(rows: List<DropRow>, q: LibraryQuery): DropRow? {
        val sel = LibraryModel.selectedId(q)
        return rows.firstOrNull { !it.header && (it.node?.id ?: it.id) == sel && it.tab == q.tab } ?: rows.firstOrNull { !it.header && it.tab == q.tab && it.node == null }
    }

    /** The query once [r] is picked from the dropdown. */
    fun apply(q: LibraryQuery, r: DropRow): LibraryQuery = when {
        r.header -> q
        // a dropdown picks, it never un-picks: from the kind's whole list, so picking the chosen row again keeps it
        r.node != null -> LibraryModel.apply(q.withTab(r.tab).copy(category = null, styleFilter = null, page = 0), r.node)
        else -> q.withTab(r.tab).copy(category = null, styleFilter = null, page = 0)
    }

    /** "Furniture (6,120)": the dropdown button's words. */
    fun buttonLabel(r: DropRow?): String = r?.let { "${it.label}  (${"%,d".format(it.count)})" } ?: "Everything"

    /** Props on its whole kind with nothing typed: the place's suggestions (its starter pack) go on top of the grid. */
    fun suggests(q: LibraryQuery): Boolean = q.tab == LibraryTab.PROPS && q.category == null && q.styleFilter == null && q.text.isBlank()

    /** Favourites with nothing typed: the recent picks go on top. */
    fun showsRecent(q: LibraryQuery): Boolean = q.tab == LibraryTab.FAVOURITES && q.text.isBlank()

    /** How many suggestions (or recent picks) on top: a row's worth at most. */
    const val TOP_ROW = 8

    /** The search box's hint for [t]. */
    fun hint(t: LibraryWindowTab): String = when (t) {
        LibraryWindowTab.PROPS -> "Search props (chair, lamp…)"
        LibraryWindowTab.BUILDINGS -> "Search buildings (tower, inn…)"
        LibraryWindowTab.PEOPLE -> "Search people and creatures (wolf…)"
        LibraryWindowTab.FAVOURITES -> "Search your favourites"
    }
}

// ------------------------------------------------------------------------------------------------ the Game Menu and the title

/** The Game Menu's buttons, in WoW's order of importance: Resume first, Save and Exit last. */
enum class GameMenuItem(val label: String) {
    RESUME("Resume"), SCENES("Scenes"), CAST("Cast"), ANIMATIONS("Animations"), OPTIONS("Options"), PLAY("Play"), SAVE_EXIT("Save and Exit");

    companion object {
        const val MAX = 7
    }
}

/** The title's buttons: Continue (or Gather the Files while the game's files aren't here), New Project, Projects, Options, Import Animations, Updates. */
enum class TitleItem(val label: String) {
    CONTINUE("Continue"), GATHER("Gather the Files"), NEW_PROJECT("New Project"), PROJECTS("Projects"), OPTIONS("Options"),
    IMPORT_ANIMATIONS("Import Animations"), UPDATES("Updates");

    companion object {
        const val MAX = 6
    }
}

/** A stacked button and whether it can be pressed now. */
data class StackButton<T>(val item: T, val enabled: Boolean)

object Menus {
    /** The Game Menu: all seven; Play only with a scene open. */
    fun game(canPlay: Boolean): List<StackButton<GameMenuItem>> = GameMenuItem.values().map { StackButton(it, it != GameMenuItem.PLAY || canPlay) }

    /** The title's six: Gather the Files in Continue's place until the files are here (New Project and Projects wait for them). */
    fun title(filesReady: Boolean, hasLast: Boolean): List<StackButton<TitleItem>> = listOf(
        if (filesReady) StackButton(TitleItem.CONTINUE, hasLast) else StackButton(TitleItem.GATHER, true),
        StackButton(TitleItem.NEW_PROJECT, filesReady),
        StackButton(TitleItem.PROJECTS, filesReady),
        StackButton(TitleItem.OPTIONS, true),
        StackButton(TitleItem.IMPORT_ANIMATIONS, true),
        StackButton(TitleItem.UPDATES, true),
    )

    /**
     * Columns for a stack of [n] buttons [button] dp high with [gap] between them in a screen [hDp] high, after [chrome] dp
     * of title and margins: one column when it fits (the game's own look), else two.
     */
    fun columns(hDp: Float, n: Int, chrome: Float, button: Float = 48f, gap: Float = 6f): Int =
        if (chrome + n * button + (n - 1) * gap <= hDp) 1 else 2

    /** The Game Menu's own chrome: its head (52), its padding (28), a margin above and below (24). */
    const val GAME_MENU_CHROME = 104f
}

// ------------------------------------------------------------------------------------------------ Options

/** The Options window's pages: the categories down its left side, and Game's Imports page under Game. */
enum class OptionsPage(val label: String) {
    GAME("Game"), SOUND("Sound"), GRAPHICS("Graphics"), INTERFACE("Interface"), ADVANCED("Advanced"), IMPORTS("Imports");

    val parent: OptionsPage? get() = if (this == IMPORTS) GAME else null

    companion object {
        /** The left list, Advanced last. */
        val CATEGORIES = listOf(GAME, SOUND, GRAPHICS, INTERFACE, ADVANCED)
    }
}

/** Every option the app has, as one row each. */
enum class OptionKey(val label: String) {
    IMPORTS("Imports"), INTERFACE_ART("Interface art"), PARTNER_SOUNDS("Partner sounds"),
    MUSIC("Music"), AMBIENCE("Ambience"), EFFECTS("Effects"), VOICES("Voices"),
    SOFT_BODY("Soft body"), DOWNLOAD_SPEED("Download speed"),
    TEXT_SPEED("Text speed"), PARCHMENT("Parchment talking head"),
    WIFI_ONLY("Gather on Wi-Fi only"), GAME_FILES("The game's files"),
    RALIA("Ralia's mod"), ALTERNATE_FEMALE("Alternate Female Options"), BODY("Body options"), UNDEAD("Undead skins"), SIMS_SKELETON("Sims 4 skeleton");
}

object OptionsModel {
    /** The rows of [p], top to bottom. */
    fun rows(p: OptionsPage): List<OptionKey> = when (p) {
        OptionsPage.GAME -> listOf(OptionKey.IMPORTS, OptionKey.INTERFACE_ART, OptionKey.PARTNER_SOUNDS)
        OptionsPage.SOUND -> listOf(OptionKey.MUSIC, OptionKey.AMBIENCE, OptionKey.EFFECTS, OptionKey.VOICES)
        OptionsPage.GRAPHICS -> listOf(OptionKey.SOFT_BODY, OptionKey.DOWNLOAD_SPEED)
        OptionsPage.INTERFACE -> listOf(OptionKey.TEXT_SPEED, OptionKey.PARCHMENT)
        OptionsPage.ADVANCED -> listOf(OptionKey.WIFI_ONLY, OptionKey.GAME_FILES)
        OptionsPage.IMPORTS -> listOf(OptionKey.RALIA, OptionKey.ALTERNATE_FEMALE, OptionKey.BODY, OptionKey.UNDEAD, OptionKey.SIMS_SKELETON)
    }

    /** The left list shown beside the page (a wide window), else a category dropdown over it. */
    fun sideList(windowWDp: Float): Boolean = windowWDp >= 560f

    /** The Soft body choices (Settings.SOFT_NAMES' order) and the download speeds. */
    val SOFT_BODY = listOf("Off", "Subtle", "Natural", "Strong")
    val DOWNLOAD = listOf(24 to "Gentle (24 at once)", 48 to "Normal (48 at once)", 96 to "Fast (96 at once)")
}

// ------------------------------------------------------------------------------------------------ where things go

/**
 * Where the editor's few things go on a screen [wDp] x [hDp] (the Fold's inner screen about 832 x 750 dp either way, its
 * cover 960 x 411 or 411 x 960): the action bar's slot size (60 dp, down to 48 on a narrow screen) and its width, the zone
 * name's room beside the Menu button (none when Undo, Redo and Play need it), the selected thing's tooltip width, and the
 * windows' sizes (never taller than the screen; their lists scroll inside).
 */
data class ChromeLayout(
    val wDp: Float,
    val hDp: Float,
    val slot: Float,
    val barW: Float,
    val barH: Float,
    val nameW: Float,
    val inspectorW: Float,
    val libraryW: Float,
    val libraryH: Float,
    val card: Float,
    val gameMenuCols: Int,
    val titleCols: Int,
    val optionsW: Float,
    val optionsH: Float,
    val listW: Float,
    val listH: Float,
) {
    /** The top of the action bar from the bottom of the screen (its margin included). */
    val barTop: Float get() = barH + BAR_MARGIN

    companion object {
        const val G = 12f
        const val GAP = 8f
        const val BAR_PAD = 36f
        const val BAR_MARGIN = 8f
        const val MENU = 52f
        /** Undo, Redo and Play with their gaps. */
        const val RIGHT_CLUSTER = 190f
        const val TABS = 42f

        fun of(wDp: Float, hDp: Float): ChromeLayout {
            val slot = ((wDp - 2 * G - BAR_PAD - 5 * GAP) / 6f).coerceIn(48f, 60f)
            val barW = 6 * slot + 5 * GAP + BAR_PAD
            val barH = slot + 30f
            val name = (wDp - G - MENU - 10f - (G + RIGHT_CLUSTER + 12f)).let { if (it < 96f) 0f else min(it, 420f) }
            val inspector = if (wDp < 600f) wDp - 2 * G else min(360f, wDp * 0.42f)
            val libW = min(760f, wDp - 2 * G)
            val libH = max(0f, min(640f, hDp - 2 * G - TABS))
            val card = if (libH >= 480f && libW >= 480f) 92f else 76f
            val titleHead = if (hDp < 520f) 98f else 140f
            return ChromeLayout(
                wDp, hDp, slot, barW, barH, name, inspector, libW, libH, card,
                gameMenuCols = Menus.columns(hDp, GameMenuItem.MAX, Menus.GAME_MENU_CHROME),
                titleCols = Menus.columns(hDp, TitleItem.MAX, titleHead + 36f, button = 48f, gap = 6f),
                optionsW = min(720f, wDp - 2 * G), optionsH = max(0f, min(520f, hDp - 2 * G)),
                listW = min(600f, wDp - 2 * G), listH = max(0f, min(560f, hDp - 2 * G)),
            )
        }

        /** Cards across a grid [widthDp] wide at [card] dp with 14 dp between them: at least two. */
        fun cardColumns(widthDp: Float, card: Float): Int = max(2, ((widthDp + 14f) / (card + 14f)).toInt())

        /** A dropdown's height on screen: what it wants, cut to [room] (its rows then scroll). */
        fun dropdownH(wants: Float, room: Float): Float = min(wants, max(room, EditorMenus.ROW * 2))

        /** Rows of a stack of [n] in [cols] columns. */
        fun rows(n: Int, cols: Int): Int = ceil(n / cols.toFloat()).toInt()
    }
}
