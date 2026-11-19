package com.logie.wifecraft.audio

import com.logie.wifecraft.catalog.Emote
import com.logie.wifecraft.catalog.LocationAudio
import com.logie.wifecraft.catalog.SoundCatalog
import kotlin.random.Random

/** One sound that is playing (the Android side wraps a MediaPlayer). Main thread. */
interface SoundVoice {
    fun setVolume(v: Float)
    fun stop(fadeMs: Int)
    fun pause()
    fun resume()
}

/** Plays a game file (an .ogg or .mp3 by id). [onDone] when a sound that doesn't loop has ended. Null: it couldn't be played. */
interface SoundOut {
    fun play(file: Int, loop: Boolean, volume: Float, fadeInMs: Int, onDone: () -> Unit): SoundVoice?
}

/** The game's files on the phone: [fetch] blocks and throws when the file can't be had (GameFiles.fetchNow). */
interface SoundFiles {
    fun has(id: Int): Boolean
    fun fetch(id: Int)
}

/** [background] runs off the main thread, [main] on it, [later] on it after a wait. */
interface SoundScheduler {
    fun background(task: () -> Unit)
    fun main(task: () -> Unit)
    fun later(ms: Long, task: () -> Unit)
}

/** The tables' answers (SoundCatalog), by display and area. May be slow the first time: asked on [SoundScheduler.background]. */
interface SoundLookup {
    fun location(areaId: Int, zoneMusic: Int, ambience: Int): LocationAudio?
    fun creature(displayId: Int): Map<String, List<Int>>
    fun gameObject(displayId: Int): Map<Int, List<Int>>
}

fun SoundCatalog.asLookup(): SoundLookup = object : SoundLookup {
    override fun location(areaId: Int, zoneMusic: Int, ambience: Int) = this@asLookup.location(areaId, zoneMusic, ambience)
    override fun creature(displayId: Int) = this@asLookup.creature(displayId)
    override fun gameObject(displayId: Int) = this@asLookup.gameObject(displayId)
}

/** 0..1 each: the options' music and sounds, with the ambience under the music and the voices of the people. */
data class SoundVolumes(val music: Float = 0.7f, val ambience: Float = 0.5f, val effects: Float = 0.85f, val voices: Float = 0.85f)

/**
 * What the scene editor's audio decides, apart from the phone: which file, when, how loud, what is muted. A place's
 * ambience loop and its music (one track after another, a silence between, as ZoneMusic says), an emote's voice, a
 * creature's or an object's sound, a placement's switch, a pause. Everything runs on the main thread except the tables'
 * lookups and the fetches ([SoundScheduler.background]); a file that can't be fetched is told to [onError] and the rest
 * goes on. Pure JVM.
 */
class SceneAudioCore(
    private val lookup: SoundLookup,
    private val files: SoundFiles,
    private val out: SoundOut,
    private val sched: SoundScheduler,
    private val onError: (String) -> Unit = {},
    /** How many voices (emotes' voices) are playing, told each time it changes: the music ducks under them (Audio). */
    private val onVoices: (Int) -> Unit = {},
) {
    private enum class Channel { MUSIC, AMBIENCE, EFFECT, VOICE }
    private class Live(val voice: SoundVoice, val channel: Channel, val placement: String?, var ended: Boolean = false)

    var volumes = SoundVolumes()
        set(v) { field = v; for (l in live) l.voice.setVolume(level(l.channel)) }

    private val live = ArrayList<Live>()
    private val muted = HashSet<String>()
    private var paused = false
    private var token = 0                 // a new place (or stop) makes the old place's pending work do nothing
    private var area = -1
    private var placeKey = 0L
    private var night = false
    private var music: Live? = null
    private var ambience: Live? = null
    private var resumeMusic: (() -> Unit)? = null
    private val counts = HashMap<Long, Int>()
    private val creatureCache = HashMap<Int, Map<String, List<Int>>>()
    private val objectCache = HashMap<Int, Map<Int, List<Int>>>()

    private fun level(c: Channel) = when (c) {
        Channel.MUSIC -> volumes.music
        Channel.AMBIENCE -> volumes.ambience * volumes.effects
        Channel.EFFECT -> volumes.effects
        Channel.VOICE -> volumes.voices
    }

    // --- places ------------------------------------------------------------------------------------------------------

    /**
     * The ambience loop and the music of an AreaTable area, the place before fading out. [zoneMusic] and [ambience] (a
     * ZoneMusic id, a SoundAmbience id: locations.json's ambience block) take the area's own place where the place's
     * rooms have theirs (the Embassy: area 9171, zoneMusic 1929, ambience 569).
     */
    fun enterLocation(areaId: Int, night: Boolean = false, zoneMusic: Int = 0, ambience: Int = 0) {
        val key = seed(areaId, zoneMusic, ambience)
        if (key == placeKey && night == this.night) return
        placeKey = key; area = areaId; this.night = night
        val t = ++token
        stopPlace(FADE_MS)
        sched.background {
            val a = try { lookup.location(areaId, zoneMusic, ambience) } catch (e: Exception) { sched.main { if (t == token) onError("The sound of area $areaId couldn't be read: ${e.message ?: e}") }; return@background }
            sched.main {
                if (t != token) return@main
                if (a == null) { onError("Area $areaId isn't in the game's tables."); return@main }
                val amb = (if (night) a.ambienceNight.ifEmpty { a.ambienceDay } else a.ambienceDay.ifEmpty { a.ambienceNight })
                pick(amb, seed(areaId, 1, 0))?.let { f -> ensure(f) { ok -> if (ok && t == token) this.ambience = start(f, Channel.AMBIENCE, null, loop = true, fadeMs = FADE_MS) } }
                val tracks = (if (night) a.musicNight.ifEmpty { a.musicDay } else a.musicDay.ifEmpty { a.musicNight })
                if (tracks.isNotEmpty()) playMusic(t, order(tracks, seed(areaId, 2, 0)), 0, a.silenceMs)
            }
        }
    }

    /** The tracks in an order fixed by [seed]. */
    private fun order(tracks: List<Int>, seed: Long): List<Int> = tracks.shuffled(Random(seed))

    private fun playMusic(t: Int, tracks: List<Int>, i: Int, silence: Pair<Int, Int>) {
        if (t != token) return
        if (paused) { resumeMusic = { playMusic(t, tracks, i, silence) }; return }
        val f = tracks[i % tracks.size]
        ensure(f) { ok ->
            if (!ok || t != token) return@ensure
            music = start(f, Channel.MUSIC, null, loop = false, fadeMs = FADE_MS) { 
                if (t == token) {
                    val wait = silence.let { (lo, hi) -> if (hi > lo) Random(seed(area, 3, i)).nextInt(lo, hi + 1).toLong() else lo.toLong() }
                    sched.later(wait.coerceAtLeast(MIN_SILENCE_MS)) { playMusic(t, tracks, i + 1, silence) }
                }
            }
        }
    }

    private fun stopPlace(fadeMs: Int) {
        music?.let { stopLive(it, fadeMs) }; music = null
        ambience?.let { stopLive(it, fadeMs) }; ambience = null
        resumeMusic = null
    }

    // --- emotes, creatures, objects ----------------------------------------------------------------------------------

    /** The voice of an emote ([Emote.soundFiles]: one at random, a stable seed per race, sex and emote); nothing when it has none. */
    fun emote(raceId: Int, sex: Int, emote: Emote, placementId: String? = null) {
        val f = pick(emote.soundFiles, nextSeed(seed(raceId, sex, emote.id))) ?: return
        oneShot(f, Channel.VOICE, placementId)
    }

    /**
     * A creature's sound by display: [kind] is a CreatureSoundData column's label ("Aggro", "Death", "Fidget_0"...) or one of
     * stand, attack, alert, wound, death, fidget, loop, shout. "loop" keeps playing until the placement is muted or [stop].
     */
    fun creature(displayId: Int, kind: String, placementId: String? = null) {
        if (isMuted(placementId)) return
        fun play(sounds: Map<String, List<Int>>) {
            val label = resolveKind(kind, sounds.keys) ?: return
            val f = pick(sounds.getValue(label), nextSeed(seed(displayId, label.hashCode(), 7))) ?: return
            if (label == "Loop") loopOnce(f, Channel.EFFECT, placementId) else oneShot(f, Channel.EFFECT, placementId)
        }
        creatureCache[displayId]?.let { play(it); return }
        sched.background {
            val sounds = try { lookup.creature(displayId) } catch (e: Exception) {
                sched.main { onError("The creature's sounds couldn't be read: ${e.message ?: e}") }; return@background
            }
            sched.main { creatureCache[displayId] = sounds; play(sounds) }
        }
    }

    /** A game object's sound by GameObjectDisplayInfo id: [state] (ObjectEffectPackageElem.StateType) or the lowest. No-op without one (201 displays have any). */
    fun gameObject(displayId: Int, placementId: String? = null, state: Int? = null) {
        if (isMuted(placementId)) return
        fun play(sounds: Map<Int, List<Int>>) {
            val key = state?.takeIf { it in sounds } ?: sounds.keys.minOrNull() ?: return
            val f = pick(sounds.getValue(key), nextSeed(seed(displayId, key, 9))) ?: return
            oneShot(f, Channel.EFFECT, placementId)
        }
        objectCache[displayId]?.let { play(it); return }
        sched.background {
            val sounds = try { lookup.gameObject(displayId) } catch (e: Exception) {
                sched.main { onError("The object's sounds couldn't be read: ${e.message ?: e}") }; return@background
            }
            sched.main { objectCache[displayId] = sounds; play(sounds) }
        }
    }

    /** The contract's name for [gameObject] (`object` is a Kotlin keyword). */
    fun `object`(displayId: Int) = gameObject(displayId)

    // --- switches ----------------------------------------------------------------------------------------------------

    /** A placement's sounds on or off; off silences what it is playing now. */
    fun setEnabled(placementId: String, on: Boolean) {
        if (on) muted -= placementId else { muted += placementId; stopPlacement(placementId) }
    }

    fun isEnabled(placementId: String) = placementId !in muted

    /** Silences one placement's sounds now (it was deleted, say). */
    fun stopPlacement(placementId: String) {
        for (l in live.filter { it.placement == placementId }) stopLive(l, 120)
    }

    /** No sound on a pause: what plays is held, nothing new starts until [resume]. */
    fun pause() {
        paused = true
        for (l in live) l.voice.pause()
    }

    fun resume() {
        if (!paused) return
        paused = false
        for (l in live) l.voice.resume()
        resumeMusic?.let { resumeMusic = null; it() }
    }

    /** Stops everything. */
    fun stop() {
        token++; area = -1; placeKey = 0L
        stopPlace(0)
        for (l in live.toList()) stopLive(l, 0)
    }

    // --- the parts ----------------------------------------------------------------------------------------------------

    private fun isMuted(p: String?) = p != null && p in muted

    private fun oneShot(file: Int, c: Channel, placement: String?) {
        if (paused || isMuted(placement)) return
        // a placement says one thing at a time
        if (placement != null) for (l in live.filter { it.placement == placement && it.channel == c && !it.ended }) stopLive(l, 60)
        ensure(file) { ok -> if (ok && !paused && !isMuted(placement)) start(file, c, placement, loop = false, fadeMs = 0) }
    }

    private fun loopOnce(file: Int, c: Channel, placement: String?) {
        if (paused || isMuted(placement)) return
        if (placement != null && live.any { it.placement == placement && it.channel == c && !it.ended }) return
        ensure(file) { ok -> if (ok && !paused && !isMuted(placement)) start(file, c, placement, loop = true, fadeMs = 200) }
    }

    private fun start(file: Int, c: Channel, placement: String?, loop: Boolean, fadeMs: Int, done: () -> Unit = {}): Live? {
        lateinit var l: Live
        val v = out.play(file, loop, level(c), fadeMs) { l.ended = true; live.remove(l); if (c == Channel.VOICE) tellVoices(); done() } ?: return null
        l = Live(v, c, placement)
        live += l
        if (paused) v.pause()
        if (c == Channel.VOICE) tellVoices()
        return l
    }

    private fun stopLive(l: Live, fadeMs: Int) {
        l.ended = true; live.remove(l)
        l.voice.stop(fadeMs)
        if (l.channel == Channel.VOICE) tellVoices()
    }

    private var toldVoices = 0
    private fun tellVoices() {
        val n = live.count { it.channel == Channel.VOICE }
        if (n != toldVoices) { toldVoices = n; onVoices(n) }
    }

    /** [then] on the main thread with whether the file is here: fetched first when it isn't (the error is told, once per try). */
    private fun ensure(file: Int, then: (Boolean) -> Unit) {
        if (files.has(file)) { then(true); return }
        sched.background {
            val err = try { files.fetch(file); null } catch (e: Exception) { e.message ?: e.toString() }
            sched.main {
                if (err != null && !files.has(file)) { onError("The sound $file couldn't be fetched: $err"); then(false) } else then(true)
            }
        }
    }

    private fun nextSeed(base: Long): Long = base + 0x9E3779B97F4A7C15uL.toLong() * (counts.merge(base, 1, Int::plus)!! - 1)

    companion object {
        const val FADE_MS = 1500
        const val MIN_SILENCE_MS = 1000L

        /** A seed from a few numbers (the same numbers, the same seed). */
        fun seed(a: Int, b: Int, c: Int): Long {
            var h = a.toLong() * -0x61c8864680b583ebL
            h = (h xor (h ushr 29)) + b.toLong() * -0x4b47_2d03_3a4f_8a8dL
            h = (h xor (h ushr 31)) + c.toLong() * 0x2545F4914F6CDD1DL
            return h xor (h ushr 32)
        }

        /** One of [files] by [seed] (null: none). */
        fun pick(files: List<Int>, seed: Long): Int? = if (files.isEmpty()) null else files[Random(seed).nextInt(files.size)]

        private val ALIASES = mapOf(
            "stand" to listOf("Stand", "Fidget_0"),
            "attack" to listOf("Aggro", "CustomAttack_0", "CustomAttack_1", "BattleShout"),
            "alert" to listOf("Alert"),
            "wound" to listOf("Injury", "InjuryCritical"),
            "death" to listOf("Death"),
            "fidget" to listOf("Fidget_0", "Fidget_1", "Fidget_2", "Fidget_3", "Fidget_4"),
            "loop" to listOf("Loop"),
            "shout" to listOf("BattleShout"),
        )

        /** The column label a kind means among [have] (stand: Stand, else Fidget_0; attack: Aggro...), or null. */
        fun resolveKind(kind: String, have: Collection<String>): String? {
            val k = kind.trim()
            have.firstOrNull { it.equals(k, ignoreCase = true) }?.let { return it }
            for (name in ALIASES[k.lowercase()].orEmpty()) have.firstOrNull { it == name }?.let { return it }
            return null
        }
    }
}
