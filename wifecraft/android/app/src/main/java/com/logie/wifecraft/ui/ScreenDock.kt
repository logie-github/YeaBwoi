package com.logie.wifecraft.ui

// Pure Kotlin (JVM-tested: ScreenPlanTest): what the one dock at the bottom of the scene holds in each mode, and the registry of
// every control the scene had with where it is now and how many taps it takes. The dock never pages: what does not fit goes
// into the More sheet, so nothing is "on page 2".

/** What the dock is for: the NPC's orders, the animation playing, Build's tools, JOI, Chat, or nothing. */
enum class DockKind { NONE, NORMAL, PLAYING, SOFT, BUILD, JOI, CHAT, PERSUADE }

/** What the row over the dock holds (one at a time): Chat's chips, JOI's dirty talk levels or its steer field. */
enum class DockRowKind { NONE, CHAT, JOI_LEVEL, JOI_STEER }

/** One target in the dock: [id], its short [label], the order it gives ([order], null: a dock-only target), [lit], its narrowest width. */
data class DockItem(val id: String, val label: String, val order: HudOrder? = null, val lit: Boolean = false, val minW: Float = ScreenPlan.TOUCH)

/** The dock: its [kind], its [items] and the orders that went into More ([more]; the "more" item is in [items] when there are any). */
data class DockPlan(val kind: DockKind, val items: List<DockItem>, val more: List<HudSlot> = emptyList()) {
    val size: Int get() = items.size
    /** The narrowest the dock can be without scrolling: the targets and the gaps between. */
    val minWidth: Float get() = items.sumOf { it.minW.toDouble() }.toFloat() + ScreenPlan.GAP * max0(items.size - 1)
    private fun max0(n: Int) = if (n < 0) 0 else n
}

object DockModel {
    /** The most targets any dock holds (six fit 328 dp: a 360 dp phone less its margins). */
    const val MAX = 6

    val BUILD_TOOLS = listOf("Select", "Marker", "Move", "Interact", "New", "Send", "Undo", "Redo", "Done")

    private fun short(slot: HudSlot): DockItem = DockItem(slot.key, when (slot.order) {
        HudOrder.PLAY -> "Play"
        HudOrder.PARTNER -> "Partner"
        else -> slot.label
    }, slot.order, slot.lit)

    /**
     * The dock for the NPC's [slots] ([SceneHud.orders]). While an animation plays ([performing]) it holds its Stop, Skip, Stop repeat
     * and, with a pair ([pair]), Switch; otherwise Walk, Play, Partner (or Actions), JOI and Chat where they are offered. Everything
     * else the orders hold is in [DockPlan.more], behind the More target.
     */
    fun normal(slots: List<HudSlot>, performing: Boolean, pair: Boolean): DockPlan {
        if (slots.isEmpty()) return DockPlan(DockKind.NONE, emptyList())
        val want: List<HudOrder> = if (performing) listOf(HudOrder.STOP, HudOrder.SKIP, HudOrder.STOP_REPEAT)
            else buildList {
                add(HudOrder.WALK); add(HudOrder.PLAY)
                if (slots.any { it.order == HudOrder.PARTNER }) add(HudOrder.PARTNER) else add(HudOrder.LIFE)
                add(HudOrder.JOI); add(HudOrder.CHAT_MODE)
            }
        val primary = want.mapNotNull { o -> slots.firstOrNull { it.order == o } }
        val items = ArrayList<DockItem>()
        for (s in primary) items += short(s).let { if (s.order == HudOrder.STOP_REPEAT) it.copy(label = "Loop off") else it }
        if (performing && pair) items.add(minOf(1, items.size), DockItem("switch", "Switch"))
        val rest = slots.filter { s -> primary.none { it.key == s.key } }
        if (rest.isNotEmpty()) items += DockItem("more", "More")
        return DockPlan(if (performing) DockKind.PLAYING else DockKind.NORMAL, items.take(MAX), rest)
    }

    /** The flow's dock: the soft controls (Slower, Faster, Closer, Position, Continue, End), one target each. */
    fun soft(ids: List<String>, labels: List<String>): DockPlan =
        DockPlan(DockKind.SOFT, ids.zip(labels).map { (id, label) -> DockItem(id, label) }.take(MAX))

    /** Build's dock: its tools, undo, redo and Done (it scrolls where the screen is narrow). */
    fun build(): DockPlan = DockPlan(DockKind.BUILD, BUILD_TOOLS.map { DockItem(it.lowercase(), it, minW = 72f) })

    /**
     * JOI's dock: Stop, Pause (Resume), Skip, Level (opens the level picker in the row), Frame (Auto, Face, Torso, Legs up: one tap goes to
     * the next; none for a creature, whose camera circles her head) and Steer (opens the steer field in the row). Six at most.
     */
    fun joi(creature: Boolean = false, paused: Boolean = false, framing: String = "Auto", row: DockRowKind = DockRowKind.NONE): DockPlan =
        DockPlan(DockKind.JOI, listOfNotNull(
            DockItem("stop", "Stop"), DockItem("pause", if (paused) "Resume" else "Pause"), DockItem("skip", "Skip"),
            DockItem("level", "Level", lit = row == DockRowKind.JOI_LEVEL),
            if (creature) null else DockItem("frame", framing),
            DockItem("steer", "Steer", lit = row == DockRowKind.JOI_STEER)))

    /** Chat's dock: the text field, Send and Leave. Its chips (zoom, who you are, length, the log, topics) are the row over it. */
    fun chat(): DockPlan = DockPlan(DockKind.CHAT, listOf(DockItem("field", "Say", minW = 160f), DockItem("send", "Send"), DockItem("leave", "Leave", minW = 56f)))

    /** The dock while an NPC hesitates and the player may calm them: one chip per approach and Later (scrolls like Build's). */
    fun persuade(labels: List<String>): DockPlan =
        DockPlan(DockKind.PERSUADE, (labels.map { DockItem(it, it, minW = maxOf(72f, 12f + 8f * it.length)) } + DockItem("later", "Later", minW = 72f)))

    /** Whether the dock of [kind] scrolls instead of sharing its width. */
    fun scrolls(kind: DockKind): Boolean = kind == DockKind.BUILD || kind == DockKind.PERSUADE
}

/** The taps each thing takes, from the code (the NPC chosen is not counted); asserted by ScreenPlanTest so a change that adds a tap fails it. */
object TapCounts {
    const val CHAT_FIRST_REMEMBERED = 2        // Chat (dock), Continue as <name>
    const val CHAT_FIRST_NEW = 2               // Chat, Start (the choices are made once, on the sheet)
    const val CHAT_SEND = 1
    const val CHAT_LEAVE = 1
    const val CHAT_TOPIC = 1
    const val CHAT_ZOOM = 1
    const val JOI_STOP = 1
    const val JOI_PAUSE = 1
    const val JOI_SKIP = 1
    const val JOI_FRAMING = 1
    const val JOI_LEVEL = 2                    // Level, the level
    const val JOI_STEER_TEXT = 2               // Steer, then type and Enter on the keyboard
    const val PERSUADE = 1
    const val BUILD_FROM_MORE = 2              // More, Build mode (the sheet's first row)
    const val BUILD_FROM_MENU = 2              // the menu, Build mode
    const val PAIR_CONFIRM_SWITCH_PLACES = 2   // Options, Scene, the switch (and the animation window's own switch)
    const val MAX_FREQUENT = 3
}

/** One control the scene had: its [id], where it was ([old]), where it is ([slot]: a slot or sheet), the taps it takes now and whether it is used often. */
data class ScreenFeature(val id: String, val old: String, val slot: String, val taps: Int, val frequent: Boolean = true)

/** Every control the scene screen had, kept: nothing is dropped by the redesign (a test counts them). */
object ScreenFeatures {
    val all: List<ScreenFeature> = listOf(
        ScreenFeature("menu", "gear", "TOP_START menu", 1),
        ScreenFeature("menu.resume", "scene menu: Resume", "menu window", 2),
        ScreenFeature("menu.life", "scene menu: NPC's life", "menu window / More", 2),
        ScreenFeature("menu.freeze", "scene menu: Freeze", "menu window", 2, false),
        ScreenFeature("menu.build", "scene menu: Build mode", "menu window / More (first row) / mode chip", 2),
        ScreenFeature("menu.steer", "scene menu: Steer the talk", "menu window -> SHEET (Steer)", 2),
        ScreenFeature("menu.limits", "scene menu: Consent and limits", "menu window / More", 3, false),
        ScreenFeature("menu.era", "scene menu: Era", "menu window", 3, false),
        ScreenFeature("menu.options", "scene menu: Options", "menu window", 2, false),
        ScreenFeature("menu.end", "scene menu: End scene", "menu window", 3, false),
        ScreenFeature("menu.time", "feed button, clock speeds", "menu window / TOP_START place", 1),
        ScreenFeature("place.clock", "clock face and speed strip", "TOP_START place -> Time sheet", 1),
        ScreenFeature("place.feed", "Feed button and panel", "Time sheet", 2),
        ScreenFeature("camera.mode", "camera button", "CAMERA", 1),
        ScreenFeature("camera.keys", "camera up, down, zoom", "CAMERA keys", 1),
        ScreenFeature("cinematic", "Cinematic button", "TOP_END / mode chip", 1),
        ScreenFeature("build.chip", "Build chip under Cinematic", "menu window / More / mode chip", 2),
        ScreenFeature("cast.select", "cast frames", "CAST", 1),
        ScreenFeature("dock.walk", "Walk slot", "dock", 1),
        ScreenFeature("dock.actions", "Actions slot", "dock / More", 1),
        ScreenFeature("dock.play", "Play animation slot", "dock", 3),
        ScreenFeature("dock.partner", "Partner animation slot", "dock", 4),
        ScreenFeature("dock.joi", "JOI slot", "dock", 2),
        ScreenFeature("dock.chat", "Chat slot", "dock", 2),
        ScreenFeature("more.say", "Say slot", "More sheet", 4, false),
        ScreenFeature("more.chatwith", "Chat with slot", "More sheet", 4, false),
        ScreenFeature("more.release", "Release slot", "More sheet", 2),
        ScreenFeature("more.use", "Use <object> slots", "More sheet", 2),
        ScreenFeature("more.freewill", "Free will slot", "More sheet", 2, false),
        ScreenFeature("more.clearqueue", "Clear queue slot", "More sheet", 2, false),
        ScreenFeature("play.stop", "Stop slot", "dock (playing)", 1),
        ScreenFeature("play.skip", "Skip slot", "dock (playing)", 1),
        ScreenFeature("play.loop", "Stop repeat slot", "dock (playing)", 1),
        ScreenFeature("play.switch", "Switch positions button", "dock (playing)", 1),
        ScreenFeature("chip.partner", "Partner chip", "DOCK_CONTEXT", 1),
        ScreenFeature("chip.part", "Part chip", "More sheet", 2, false),
        ScreenFeature("chip.gear", "Gear on/off chip", "More sheet", 2),
        ScreenFeature("chip.allgear", "All gear chip", "More sheet", 2, false),
        ScreenFeature("line.help", "line of help", "DOCK_CONTEXT", 0),
        ScreenFeature("toast.answer", "life toast", "TOAST", 0),
        ScreenFeature("toast.problem", "problem note", "TOAST", 0),
        ScreenFeature("toast.hint", "first-run Build hint", "TOAST", 0),
        ScreenFeature("soft", "soft radial (continue, flow)", "dock (flow)", 1),
        ScreenFeature("autoplay", "cinematic auto-play panel", "Auto-play sheet", 1),
        ScreenFeature("build.bar", "Build bar", "dock (Build)", 1),
        ScreenFeature("build.panel", "Build panel", "SHEET", 1),
        ScreenFeature("move.panel", "Move panel", "SHEET", 1),
        ScreenFeature("joi.stop", "JOI Stop button", "dock (JOI)", 1),
        ScreenFeature("joi.pause", "JOI Pause / Resume button", "dock (JOI)", 1),
        ScreenFeature("joi.skip", "JOI Skip stage button", "dock (JOI)", 1),
        ScreenFeature("joi.framing", "JOI framing tabs (Auto, Face, Torso, Legs)", "dock (JOI) frame chip", 1),
        ScreenFeature("joi.level", "JOI dirty talk level slider", "dock (JOI) level chip -> row", 2),
        ScreenFeature("joi.steer.chips", "JOI steer chips (Slower, Faster...)", "dock (JOI) steer chip -> row", 2),
        ScreenFeature("joi.steer.text", "JOI steer text field", "dock (JOI) steer chip -> row", 2),
        ScreenFeature("joi.steer.clear", "JOI's standing steer chip (tap to clear)", "row (steer)", 1, false),
        ScreenFeature("chat.leave", "Chat Leave", "mode chip / dock (Chat)", 1),
        ScreenFeature("chat.field", "Chat text field", "dock (Chat)", 1),
        ScreenFeature("chat.send", "Chat Send", "dock (Chat)", 1),
        ScreenFeature("chat.zoom", "Chat zoom presets and slider", "row (Chat) zoom chip", 1),
        ScreenFeature("chat.relationship", "Chat: who you are to her", "row (Chat) chip -> window", 2, false),
        ScreenFeature("chat.length", "Chat reply length tabs", "row (Chat) length chip", 1, false),
        ScreenFeature("chat.topics", "Chat topic chips", "row (Chat)", 1),
        ScreenFeature("chat.log", "Chat log", "SHEET (Chat log) / DOCK_CONTEXT last line", 1),
        ScreenFeature("chat.setup", "Chat setup sheet (Continue as <name>)", "window", 2),
        ScreenFeature("persuade.chips", "hesitation bar's persuasion chips", "dock (persuasion)", 1),
        ScreenFeature("persuade.later", "leave a hesitation for later", "dock (persuasion)", 1, false),
        ScreenFeature("tip.firstrun", "first-run tip (the dock and the menu)", "TOAST", 0, false),
        ScreenFeature("options.scene.pair", "Ask before partner animations play (Options > Scene)", "Options", 2, false),
        ScreenFeature("anim.browser", "Animation browser", "window", 3),
        ScreenFeature("pair.confirm", "Ready to play panel", "Options toggle (off), animation window", 0, false),
        ScreenFeature("radial", "radial NPC menu", "long press / More", 1),
        ScreenFeature("life.window", "Life window", "window", 2),
        ScreenFeature("consent", "Consent window", "window", 3, false),
        ScreenFeature("options", "Options", "window", 2, false),
        ScreenFeature("perf", "performance overlay", "TOP_END (debug)", 0, false),
    )

    /** The slots a feature may name (the first word before " / " or " -> "), to check none is left without a place. */
    val places: Set<String> = setOf("TOP_START", "TOP_END", "CAMERA", "CAST", "dock", "More", "Time", "menu", "DOCK_CONTEXT", "TOAST", "SHEET",
        "Options", "window", "long", "mode", "Auto-play", "scene", "Steer", "row")
}
