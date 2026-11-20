package com.logie.wifecraft.audio

import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

// The app's soundscape, apart from the phone (pure JVM: unit-tests/.../audio/SoundscapeTest): the Sound page's mixer (Master,
// Music, Ambience, Sound effects, Dialog, as the game's own Sound settings), the music ducking under voices, the two-deck
// crossfade the music and the ambience beds play through, which music plays where (the title's, a place's: ZoneMusic's
// tracks one after another with a short gap), the retry when a file can't be fetched, and assets/sounds.json read. Audio.kt
// plays what these decide.

/** The Sound page: Enable Sound and five levels, 0 to 100 each (WoW's Master, Music, Ambience, Sound effects, Dialog). */
data class MixLevels(
    val enabled: Boolean = true,
    val master: Int = 100,
    val music: Int = 70,
    val ambience: Int = 60,
    val effects: Int = 85,
    val dialog: Int = 90,
)

/** Where a sound goes: the beds (music, ambience), the scene's effects, the people's voices, the interface's clicks. */
enum class Bus { MUSIC, AMBIENCE, EFFECTS, DIALOG, UI }

object Mixer {
    /**
     * A slider's 0..100 as a player's gain: squared, so the slider's middle sounds like the middle (a player's volume is a
     * plain amplitude scale; the ear hears it roughly logarithmically).
     */
    fun curve(percent: Int): Float = (percent.coerceIn(0, 100) / 100f).let { it * it }

    /** The gain of [bus] under [levels]: Master times the bus (the interface's clicks under Sound effects), times [duck] for the beds. */
    fun gain(levels: MixLevels, bus: Bus, duck: Float = 1f): Float {
        if (!levels.enabled) return 0f
        val own = when (bus) {
            Bus.MUSIC -> levels.music
            Bus.AMBIENCE -> levels.ambience
            Bus.EFFECTS, Bus.UI -> levels.effects
            Bus.DIALOG -> levels.dialog
        }
        val d = if (bus == Bus.MUSIC || bus == Bus.AMBIENCE) duck.coerceIn(0f, 1f) else 1f
        return (curve(levels.master) * curve(own) * d).coerceIn(0f, 1f)
    }

    /**
     * The scene's sounds' levels (SceneAudio: its ambience is played at ambience x effects) so that each comes out at
     * [gain]: the ambience given as its share of the effects'.
     */
    fun sceneVolumes(levels: MixLevels): SoundVolumes {
        val fx = gain(levels, Bus.EFFECTS)
        val amb = gain(levels, Bus.AMBIENCE)
        return SoundVolumes(
            music = gain(levels, Bus.MUSIC),
            ambience = if (fx > 0f) min(1f, amb / fx) else 0f,
            effects = fx,
            voices = gain(levels, Bus.DIALOG),
        )
    }
}

/**
 * The beds dip while someone speaks: [voiceStarted] and [voiceEnded] count the voices (or [setVoices] sets the count), [step]
 * moves [level] toward [depth] (a voice) or 1 (none) over [attackMs] down and [releaseMs] back up. The ambience dips half as
 * far ([ambienceLevel]).
 */
class Ducker(val depth: Float = 0.45f, val attackMs: Int = 180, val releaseMs: Int = 700) {
    var voices = 0
        private set
    var level = 1f
        private set

    val target: Float get() = if (voices > 0) depth else 1f
    val ambienceLevel: Float get() = 1f - (1f - level) * 0.5f
    val moving: Boolean get() = level != target

    fun voiceStarted() { voices++ }
    fun voiceEnded() { voices = max(0, voices - 1) }
    fun setVoices(n: Int) { voices = max(0, n) }

    /** [dtMs] later; true when [level] changed. */
    fun step(dtMs: Long): Boolean {
        val t = target
        if (level == t) return false
        val span = 1f - depth
        val ms = if (t < level) attackMs else releaseMs
        val delta = if (ms <= 0 || span <= 0f) 1f else span * dtMs / ms
        level = if (t < level) max(t, level - delta) else min(t, level + delta)
        return true
    }
}

/**
 * Cinematic mode's music: it eases down to [MUSIC] of its level over [FADE_MS] while the mode runs and back up after (the
 * ambience stays as it is). [on] sets where it goes; [step] moves [level] on, linear in time; true while it moved.
 */
class CinematicMusic {
    companion object {
        const val MUSIC = 0.7f
        const val FADE_MS = 1500
    }

    var on = false
    var level = 1f
        private set
    val moving: Boolean get() = level != (if (on) MUSIC else 1f)

    fun step(dtMs: Long): Boolean {
        val t = if (on) MUSIC else 1f
        if (level == t) return false
        val delta = (1f - MUSIC) * dtMs / FADE_MS
        level = if (t < level) max(t, level - delta) else min(t, level + delta)
        return true
    }
}

/**
 * Two (or more, briefly) decks that fade into one another: [play] fades every deck out and a new one in; [step] moves the
 * levels on and gives back the decks that have faded out (to release); [end] drops a deck that finished by itself. The
 * player's volume is a deck's [Deck.level] times its bus's gain. Pure: the decks hold an [item] (a file id) and an id.
 */
class Crossfader<T> {
    class Deck<T>(val id: Int, val item: T) {
        var level = 0f
            internal set
        var target = 0f
            internal set
        internal var perMs = 1f
        val fadingOut: Boolean get() = target == 0f
    }

    private var nextId = 1
    private val list = ArrayList<Deck<T>>()

    val decks: List<Deck<T>> get() = list
    /** The deck fading in or playing (the newest not fading out), if any. */
    val current: Deck<T>? get() = list.lastOrNull { !it.fadingOut }
    /** True while any level is still moving. */
    val busy: Boolean get() = list.any { it.level != it.target }

    /** A new deck for [item] faded in over [fadeInMs]; every other deck fades out over [fadeOutMs]. */
    fun play(item: T, fadeInMs: Int, fadeOutMs: Int = fadeInMs): Deck<T> {
        for (d in list) fadeTo(d, 0f, fadeOutMs)
        val d = Deck(nextId++, item)
        list += d
        fadeTo(d, 1f, fadeInMs)
        return d
    }

    /** Every deck faded out over [fadeMs]. */
    fun stop(fadeMs: Int) { for (d in list) fadeTo(d, 0f, fadeMs) }

    /** A deck that ended by itself (its track finished): gone without a fade. */
    fun end(id: Int): Deck<T>? = list.firstOrNull { it.id == id }?.also { list.remove(it) }

    /** Everything gone at once (released by the caller). */
    fun clear(): List<Deck<T>> = list.toList().also { list.clear() }

    /** [dtMs] later: the levels moved on; the decks that reached silence on their way out are removed and returned. */
    fun step(dtMs: Long): List<Deck<T>> {
        for (d in list) {
            if (d.level < d.target) d.level = min(d.target, d.level + d.perMs * dtMs)
            else if (d.level > d.target) d.level = max(d.target, d.level - d.perMs * dtMs)
        }
        val gone = list.filter { it.fadingOut && it.level <= 0f }
        list.removeAll(gone)
        return gone
    }

    private fun fadeTo(d: Deck<T>, t: Float, ms: Int) {
        d.target = t
        if (ms <= 0) { d.level = t; d.perMs = 1f } else d.perMs = 1f / ms
    }
}

/**
 * What plays as a bed: [tracks] (file ids) one after another in a shuffled order with a gap of [gapMs] between, or one
 * looped ([loop]); [ambience] looped under it (none: empty). [key] tells two beds apart: the same key again changes nothing.
 */
data class MusicBed(
    val key: String,
    val tracks: List<Int>,
    val ambience: List<Int> = emptyList(),
    val gapMs: LongRange = MusicPicker.MIN_GAP_MS..MusicPicker.MIN_GAP_MS,
    val loop: Boolean = false,
)

/** A place's sound as sounds.json's places give it (the tables' ids: AreaTable, WMOAreaTable's ZoneMusic, SoundAmbience). */
data class PlaceSound(
    val id: String,
    val areaId: Int,
    val zoneMusic: Int,
    val music: List<Int>,
    val ambienceDay: List<Int>,
    val ambienceNight: List<Int>,
    /** ZoneMusic's silence between tracks (ms): 3 to 5 minutes in the game, shortened here ([MusicPicker.gap]). */
    val silenceMs: Pair<Int, Int>,
)

object MusicPicker {
    /** The gap between two of a place's tracks: the game's minutes of silence would leave a scene quiet too long. */
    const val MIN_GAP_MS = 4_000L
    const val MAX_GAP_MS = 18_000L
    const val FADE_MS = 2_000
    const val TITLE_FADE_MS = 1_200

    /** The title's music: one track looped (sounds.json's title). */
    fun title(book: SoundBook): MusicBed = MusicBed("title", book.title, loop = true)

    /** A place's music and ambience, by day or by [night] (the other's when one is empty); null when the place has no sound. */
    fun place(p: PlaceSound, night: Boolean): MusicBed? {
        val amb = if (night) p.ambienceNight.ifEmpty { p.ambienceDay } else p.ambienceDay.ifEmpty { p.ambienceNight }
        if (p.music.isEmpty() && amb.isEmpty()) return null
        return MusicBed("place:${p.id}:${if (night) "night" else "day"}", p.music, amb.take(1), gap(p.silenceMs))
    }

    /** The table's silence cut down to [MIN_GAP_MS]..[MAX_GAP_MS] (kept in order: never a backwards range). */
    fun gap(silence: Pair<Int, Int>): LongRange {
        val lo = silence.first.toLong().coerceIn(MIN_GAP_MS, MAX_GAP_MS)
        val hi = silence.second.toLong().coerceIn(lo, MAX_GAP_MS)
        return lo..hi
    }

    /** One gap from [range] by [seed]. */
    fun gapMs(range: LongRange, seed: Long): Long =
        if (range.last <= range.first) range.first else range.first + Random(seed).nextLong(range.last - range.first + 1)

    /**
     * The tracks in play order for round [round] (a fresh shuffle each round, by [seed]); a round never starts with the
     * track the last one ended on, so nothing plays twice in a row.
     */
    fun order(tracks: List<Int>, seed: Long, round: Int): List<Int> {
        if (tracks.size <= 1) return tracks
        var o: MutableList<Int> = tracks.toMutableList()
        var prev: Int? = null
        for (r in 0..maxOf(0, round)) {
            o = tracks.shuffled(Random(seed + r)).toMutableList()
            if (prev != null && o[0] == prev) { o[0] = o[1]; o[1] = prev }
            prev = o.last()
        }
        return o
    }

    /** The track at play position [n] (0, 1, 2...: rounds of [order] one after another). */
    fun at(tracks: List<Int>, seed: Long, n: Int): Int? {
        if (tracks.isEmpty()) return null
        return order(tracks, seed, n / tracks.size)[n % tracks.size]
    }
}

/** How long to wait before fetching a file again after it failed: 5 s, 15 s, 45 s, then every 2 minutes. */
object RetryPolicy {
    fun delayMs(attempt: Int): Long = when {
        attempt <= 0 -> 0L
        attempt == 1 -> 5_000L
        attempt == 2 -> 15_000L
        attempt == 3 -> 45_000L
        else -> 120_000L
    }
}

/**
 * The interface's sounds by the app's names, each a sound kit of the game's (SoundKitConstants.lua, the names Blizzard's
 * own interface code plays them by): the files are in sounds.json's ui, from SoundKitEntry (tools/sounds.py). There is no
 * hover sound: the game's buttons play none on hover.
 */
object UiSounds {
    val KITS: Map<String, Pair<String, Int>> = linkedMapOf(
        "open" to ("IG_MAINMENU_OPEN" to 850),
        "close" to ("IG_MAINMENU_CLOSE" to 851),
        "click" to ("IG_MAINMENU_OPTION" to 852),
        "ok" to ("GS_TITLE_OPTION_OK" to 798),
        "check_on" to ("IG_MAINMENU_OPTION_CHECKBOX_ON" to 856),
        "check_off" to ("IG_MAINMENU_OPTION_CHECKBOX_OFF" to 857),
        "tab" to ("IG_CHARACTER_INFO_TAB" to 841),
        "page" to ("IG_ABILITY_PAGE_TURN" to 836),
        "select" to ("IG_QUEST_LIST_SELECT" to 877),
        "create" to ("GS_CHARACTER_SELECTION_CREATE_NEW" to 813),
        "begin" to ("GS_CHARACTER_SELECTION_ENTER_WORLD" to 809),
        "gossip" to ("IG_QUEST_LIST_OPEN" to 875),
        "ending" to ("kit 12891 (achievmentsound1.ogg, the achievement toast)" to 12891),
    )

    /** A name the app may ask for that has no kit falls back to the plain click (an unknown name: nothing). */
    fun resolve(name: String, have: Set<String>): String? = when {
        name in have -> name
        name == "hover" -> null
        name in KITS || name.startsWith("check") -> "click".takeIf { it in have }
        else -> null
    }
}

/** assets/sounds.json: the story's named music and ambience, the interface's sounds, the title's music, the places' sound. */
class SoundBook(
    val music: Map<String, Int>,
    val ambience: Map<String, Int>,
    val ui: Map<String, List<Int>>,
    val effects: Map<String, List<Int>>,
    val voices: Map<String, Map<String, List<Int>>>,
    val title: List<Int>,
    val places: Map<String, PlaceSound>,
) {
    /** Every short sound (loaded into the sound pool ahead). */
    val shortFiles: Set<Int> get() = (ui.values.flatten() + effects.values.flatten() + voices.values.flatMap { it.values.flatten() }).toSet()

    companion object {
        fun parse(json: String): SoundBook {
            val o = JSONObject(json)
            fun ints(m: JSONObject?) = m?.keys()?.asSequence()?.associateWith { m.getInt(it) }.orEmpty()
            fun list(a: org.json.JSONArray?) = if (a == null) emptyList() else List(a.length()) { a.getInt(it) }
            fun lists(m: JSONObject?) = m?.keys()?.asSequence()?.associateWith { k -> list(m.optJSONArray(k)) }.orEmpty()
            val v = o.optJSONObject("voices")
            val places = LinkedHashMap<String, PlaceSound>()
            o.optJSONObject("places")?.let { p ->
                for (id in p.keys()) {
                    val e = p.getJSONObject(id)
                    val s = e.optJSONArray("silenceMs")
                    places[id] = PlaceSound(
                        id, e.optInt("area"), e.optInt("zoneMusic"), list(e.optJSONArray("music")),
                        list(e.optJSONArray("ambienceDay")), list(e.optJSONArray("ambienceNight")),
                        if (s != null && s.length() >= 2) s.getInt(0) to s.getInt(1) else 0 to 0,
                    )
                }
            }
            return SoundBook(
                ints(o.optJSONObject("music")), ints(o.optJSONObject("ambience")), lists(o.optJSONObject("ui")),
                lists(o.optJSONObject("effects")),
                v?.keys()?.asSequence()?.associateWith { lists(v.getJSONObject(it)) }.orEmpty(),
                list(o.optJSONObject("title")?.optJSONArray("music")),
                places,
            )
        }
    }
}
