package com.logie.wifecraft.ui

import kotlin.math.min

// Pure Kotlin, no android.* and no Compose (JVM-tested: unit-tests/.../ui/WarArtPlanTest): how the editor's build-mode art
// (WarArt) is loaded, what a fetched file must look like to be kept, and the glyph drawn in a button whose picture is not
// here (so a button is never blank).

/** What became of one file of the art (a sheet of pieces, or an icon). */
sealed class ArtFile {
    object Waiting : ArtFile()
    object Loading : ArtFile()
    object Loaded : ArtFile()
    /** Failed [attempts] times in a row; tried again by itself at [retryAt] (ms) while [attempts] < [ArtLoadBook.maxAuto]. */
    data class Failed(val reason: String, val attempts: Int, val retryAt: Long) : ArtFile()
}

/** The wait before trying a failed file again: 5 s, 15 s, 45 s, ... never more than five minutes. */
object ArtBackoff {
    const val FIRST_MS = 5_000L
    const val MAX_MS = 300_000L
    fun delayMs(attempts: Int): Long {
        var d = FIRST_MS
        repeat((attempts - 1).coerceAtLeast(0)) { d = min(d * 3, MAX_MS) }
        return min(d, MAX_MS)
    }
}

/**
 * Every file's state: waiting, loading, loaded, failed (with its reason and when it is tried again). A failure is never
 * kept as final: it is due again after [ArtBackoff], [maxAuto] times by itself, and [retryFailed] (the Options' Retry)
 * starts the count again. Not thread-safe by itself: WarArt holds it under one lock.
 */
class ArtLoadBook(ids: Collection<Int>, val maxAuto: Int = 4) {
    private val states = LinkedHashMap<Int, ArtFile>().apply { for (id in ids) put(id, ArtFile.Waiting) }

    val ids: Set<Int> get() = states.keys
    fun state(id: Int): ArtFile? = states[id]

    /** What may start now: the waiting ones, and the failed ones whose wait is over (and which have tries left). */
    fun due(now: Long): List<Int> = states.filter { (_, s) ->
        s is ArtFile.Waiting || (s is ArtFile.Failed && s.attempts < maxAuto && s.retryAt <= now)
    }.keys.toList()

    // the tries in a row of each file (a failed file loading again keeps its count, so its next wait is longer)
    private val tries = HashMap<Int, Int>()

    fun started(id: Int) { if (id in states) states[id] = ArtFile.Loading }
    fun loaded(id: Int) { if (id in states) { states[id] = ArtFile.Loaded; tries.remove(id) } }
    fun failed(id: Int, reason: String, now: Long) {
        if (id !in states) return
        val n = (tries[id] ?: 0) + 1
        tries[id] = n
        states[id] = ArtFile.Failed(reason, n, now + ArtBackoff.delayMs(n))
    }

    /** The Options' Retry: every failed file waits again, its count of tries from zero. */
    fun retryFailed(): Int {
        var n = 0
        for ((id, s) in states.entries) if (s is ArtFile.Failed) { states[id] = ArtFile.Waiting; tries.remove(id); n++ }
        return n
    }

    /** The earliest time a failed file is due by itself (null: none will be). */
    fun nextRetryAt(): Long? = states.values.filterIsInstance<ArtFile.Failed>().filter { it.attempts < maxAuto }.minOfOrNull { it.retryAt }

    val loadedCount: Int get() = states.values.count { it == ArtFile.Loaded }
    val busy: Boolean get() = states.values.any { it == ArtFile.Waiting || it == ArtFile.Loading }
    /** Nothing left to do by itself: all loaded, or the failed ones out of tries. */
    val settled: Boolean get() = !busy && nextRetryAt() == null
    fun failures(): List<Pair<Int, String>> = states.mapNotNull { (id, s) -> (s as? ArtFile.Failed)?.let { id to it.reason } }
}

/** What a fetched file must be to be kept: wago.tools answers a file it lacks as 200 with JSON, or with nothing. */
object ArtCheck {
    /** Null when [head] (the file's first bytes) of a [length]-byte answer with [code] and [contentType] is a BLP2 texture. */
    fun problem(code: Int, contentType: String?, length: Long, head: ByteArray): String? = answer(code, contentType) ?: body(length, head)

    /** What the answer's status and type say before its body is read (null: read it). */
    fun answer(code: Int, contentType: String?): String? = when {
        code != 200 -> "HTTP $code"
        contentType?.startsWith("application/json") == true -> "wago.tools has no such file (JSON answer)"
        else -> null
    }

    /** What the body's length and first bytes say (null: a BLP2 texture). */
    fun body(length: Long, head: ByteArray): String? = when {
        length <= 0L -> "came empty"
        head.size < 4 || head[0] != 'B'.code.toByte() || head[1] != 'L'.code.toByte() || head[2] != 'P'.code.toByte() || head[3] != '2'.code.toByte() ->
            "not a BLP2 texture"
        else -> null
    }
}

/** The vector glyphs WarWidgets draws in a button when its picture is not here (a clear shape, never a blank button). */
enum class WarGlyph {
    HAND, ROTATE, SCALE, TRASH, EYEDROPPER, COPY, LOCK, EYE, SPEECH, CROSSHAIR, STAR, SEARCH, UNDO, REDO, CAMERA, PLAY,
    BACK, PLUS, MINUS, CUBE, HOUSE, PAW, PERSON, HEART, CLOCK, COG, CHECK, CLOSE, PAGE,
}

/** Which glyph stands in for which picture: every icon key (ui_art.json "icons"), gold glyph (WarIcons) and tool picture. */
object WarGlyphs {
    val TABLE: Map<String, WarGlyph> = buildMap {
        // the library's tabs
        put("tab.starter", WarGlyph.STAR); put("tab.props", WarGlyph.CUBE); put("tab.buildings", WarGlyph.HOUSE)
        put("tab.creatures", WarGlyph.PAW); put("tab.npcs", WarGlyph.PERSON); put("tab.favourites", WarGlyph.HEART)
        put("tab.recent", WarGlyph.CLOCK)
        // the categories
        for (c in listOf("lighting", "furniture", "food and drink", "containers", "books and papers", "tools and crafts",
            "carts and wagons", "weapons and armor", "banners and flags", "statues", "water", "nature", "bones and remains",
            "other", "not specified")) put("cat.$c", WarGlyph.CUBE)
        for (c in listOf("architecture", "buildings", "tents", "ships and docks")) put("cat.$c", WarGlyph.HOUSE)
        put("cat.magic", WarGlyph.STAR); put("cat.mechanical", WarGlyph.COG)
        for (c in listOf("beast", "dragonkin", "demon", "elemental", "critter", "totem", "non-combat pet", "gas cloud", "wild pet",
            "aberration", "creatures")) put("cat.$c", WarGlyph.PAW)
        for (c in listOf("giant", "undead", "humanoid", "my people")) put("cat.$c", WarGlyph.PERSON)
        // the tools
        put("tool.eyedropper", WarGlyph.EYEDROPPER); put("tool.duplicate", WarGlyph.COPY); put("tool.lock", WarGlyph.LOCK)
        put("tool.emote", WarGlyph.PERSON); put("tool.focus", WarGlyph.CROSSHAIR); put("tool.stage", WarGlyph.HOUSE)
        put("tool.camera", WarGlyph.CAMERA); put("tool.hide", WarGlyph.EYE); put("tool.tips", WarGlyph.SPEECH)
        put("tool.things", WarGlyph.PAGE)
        // the emote groups
        put("emote.emotes", WarGlyph.SPEECH); put("emote.dance", WarGlyph.HEART); put("emote.sit", WarGlyph.PERSON)
        put("emote.work", WarGlyph.COG); put("emote.combat", WarGlyph.PERSON); put("emote.spells", WarGlyph.STAR)
        put("emote.other", WarGlyph.PERSON)
        // the projects, the cast, the scenes
        put("menu.project", WarGlyph.PAGE); put("menu.scene", WarGlyph.PAGE); put("menu.cast", WarGlyph.PERSON)
        put("menu.studio", WarGlyph.HOUSE); put("menu.location", WarGlyph.HOUSE)
        // the gold glyphs (atlas pieces)
        put(WarIcons.BACK, WarGlyph.BACK); put(WarIcons.PLAY, WarGlyph.PLAY); put(WarIcons.UNDO, WarGlyph.UNDO)
        put(WarIcons.REDO, WarGlyph.REDO); put(WarIcons.DELETE, WarGlyph.TRASH); put(WarIcons.COPY, WarGlyph.COPY)
        put(WarIcons.CHECK, WarGlyph.CHECK); put(WarIcons.PLUS, WarGlyph.PLUS); put(WarIcons.MINUS, WarGlyph.MINUS)
        put(WarIcons.CLOSE, WarGlyph.CLOSE); put(WarIcons.SPEAK, WarGlyph.SPEECH); put(WarIcons.SOUND, WarGlyph.SPEECH)
        put(WarIcons.ZOOM, WarGlyph.SEARCH); put(WarIcons.CAMERA, WarGlyph.CAMERA); put(WarIcons.LOCK, WarGlyph.LOCK)
        put(WarIcons.STAR, WarGlyph.STAR); put(WarIcons.STAR_OFF, WarGlyph.STAR); put(WarIcons.SEARCH, WarGlyph.SEARCH)
        // the housing editor's tool pictures
        for (s in listOf("default", "active", "pressed")) {
            put("decor-ability-move-$s", WarGlyph.HAND); put("decor-ability-expert-rotate-$s", WarGlyph.ROTATE)
            put("decor-ability-expert-scale-$s", WarGlyph.SCALE); put("decor-ability-snap-$s", WarGlyph.CROSSHAIR)
        }
    }

    /** The glyph for [key] (an icon key, a glyph piece or a tool picture); one by its kind for a key not in the table. */
    fun of(key: String?): WarGlyph {
        key ?: return WarGlyph.CUBE
        TABLE[key]?.let { return it }
        return when {
            key.startsWith("emote.") || key.startsWith("menu.cast") -> WarGlyph.PERSON
            key.startsWith("menu.") -> WarGlyph.PAGE
            else -> WarGlyph.CUBE
        }
    }
}
