package com.logie.wifecraft.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.logie.wifecraft.data.GameFiles
import com.logie.wifecraft.play.Sounds
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * The app's soundscape on the phone: a music bed and an ambience bed, each through a [Crossfader] (two MediaPlayers fading
 * into one another), the title's music ([title]) and a place's ([place]: ZoneMusic's tracks one after another with a
 * short gap, its ambience looped under them), the interface's sounds through a SoundPool ([ui]), the Sound page's mixer
 * ([levels]), the beds ducking while someone speaks ([voices]), and silence when the app is paused. The decisions are
 * Soundscape.kt's (JVM-tested); this only plays them.
 *
 * Files are the game's, by id (assets/sounds.json), fetched from wago.tools as they are first needed, off the main thread
 * ([GameFiles.fetchNow]); one that can't be fetched leaves its bed silent and is tried again later ([RetryPolicy]): nothing
 * here throws. MediaPlayer decodes the game's .mp3 music and .ogg sounds itself. Main thread only. NOT heard on a phone: it
 * compiles in CI; no one has listened to it.
 */
class Audio(context: Context, private val files: GameFiles) : Sounds {
    private val book: SoundBook = SoundBook.parse(context.assets.open("sounds.json").bufferedReader().use { it.readText() })
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "audio-fetch").apply { isDaemon = true } }

    /** The Sound page's levels (Options): applied at once to everything playing. */
    var levels = MixLevels()
        set(value) { field = value; applyVolumes(); if (!value.enabled) pool.autoPause() else if (!paused) pool.autoResume() }

    private val ducker = Ducker()

    /**
     * Whether a file not on the phone may be fetched now (the player has gathered the game's files, so has said yes to
     * downloading): until then only what is already here plays. Turning it on starts what should already be playing.
     */
    var fetchAllowed = false
        set(value) {
            val was = field
            field = value
            if (value && !was) { bed?.let { b -> bed = null; ambienceKey = ""; setBed(b, MusicPicker.FADE_MS) }; filesArrived() }
        }

    // ---- the beds ---------------------------------------------------------------------------------------------------

    private val music = Crossfader<Int>()
    private val ambience = Crossfader<Int>()
    private val players = HashMap<Int, MediaPlayer>()        // deck id -> its player (music and ambience decks: ids never clash, see deckKey)
    private var bed: MusicBed? = null
    private var ambienceKey = ""
    private var token = 0                                    // a new bed makes the old one's pending work do nothing
    private var played = 0                                   // tracks of this bed so far
    private var seed = 0L
    private var paused = false
    private val attempts = HashMap<Int, Int>()

    /** Off the main thread (nothing once released). */
    private fun background(task: () -> Unit) { runCatching { io.execute(task) } }

    private fun deckKey(isAmbience: Boolean, id: Int) = if (isAmbience) -id else id

    /** The title's music (sounds.json's title), the ambience off. */
    fun title() { leavePlace(); setBed(MusicPicker.title(book), MusicPicker.TITLE_FADE_MS) }

    /**
     * A place's music and ambience (sounds.json's places, by the location's id); silence for a place without sound. By
     * day or by [night]: by default as the world's clock last said ([timeOfDay]).
     */
    fun place(locationId: String, night: Boolean = clockNight) {
        val p = book.places[locationId]?.let { MusicPicker.place(it, night) }
        if (p == null) { leavePlace(); silence(); return }
        if (placeId != locationId) enterPlace(locationId)
        setBed(p, MusicPicker.FADE_MS)
    }

    /** Both beds fade out (a screen without music: the creator, the importers). */
    fun silence(fadeMs: Int = MusicPicker.FADE_MS) {
        leavePlace()
        token++; bed = null; ambienceKey = ""
        music.stop(fadeMs); ambience.stop(fadeMs)
        tick()
    }

    private fun setBed(b: MusicBed, fadeMs: Int) {
        if (bed?.key == b.key) return
        val old = bed
        if (old != null && old.tracks.isNotEmpty() && old.tracks == b.tracks && old.loop == b.loop && old.gapMs == b.gapMs) {
            // the same music (a place by day and by night): it plays on; only the ambience crosses, slowly
            bed = b
            crossAmbience(b.ambience.firstOrNull(), TimeOfDaySound.AMBIENCE_CROSS_MS)
            return
        }
        bed = b
        val t = ++token
        played = 0
        seed = b.key.hashCode().toLong() * 31 + SystemClock.elapsedRealtime() / 60_000
        // the ambience: the new loop crossfades in, or the old one fades away
        val amb = b.ambience.firstOrNull()
        val ak = amb?.toString() ?: ""
        if (ak != ambienceKey) {
            ambienceKey = ak
            if (amb == null || !files.has(amb)) ambience.stop(fadeMs)
            if (amb != null) ready(amb, t) { startDeck(amb, isAmbience = true, loop = true, fadeMs = fadeMs, t = t) }
        }
        if (b.tracks.isEmpty()) music.stop(fadeMs) else nextTrack(t, fadeMs)
        tick()
    }

    private fun nextTrack(t: Int, fadeMs: Int) {
        val b = bed ?: return
        if (t != token) return
        val file = (if (b.loop) b.tracks.firstOrNull() else MusicPicker.at(b.tracks, seed, played)) ?: return
        // what plays now fades at once when the next file has to be fetched first (no old music carrying on meanwhile)
        if (!files.has(file)) music.stop(fadeMs)
        ready(file, t) { startDeck(file, isAmbience = false, loop = b.loop, fadeMs = fadeMs, t = t) }
    }

    /** [then] on the main thread once [file] is on the phone (fetched now when it isn't); retried later when it can't be had. */
    private fun ready(file: Int, t: Int, then: () -> Unit) {
        if (files.has(file)) { then(); return }
        if (!fetchAllowed) return
        background {
            val ok = try { files.fetchNow(file); files.has(file) } catch (e: Exception) { false }
            handler.post {
                if (t != token) return@post
                if (ok) { attempts.remove(file); then() }
                else {
                    // offline, or wago.tools said no: this bed stays quiet and tries again in a while
                    val n = (attempts[file] ?: 0) + 1
                    attempts[file] = n
                    handler.postDelayed({ if (t == token) ready(file, t, then) }, RetryPolicy.delayMs(n))
                }
            }
        }
    }

    private fun startDeck(file: Int, isAmbience: Boolean, loop: Boolean, fadeMs: Int, t: Int) {
        if (t != token) return
        val fader = if (isAmbience) ambience else music
        val player = try {
            MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                setDataSource(files.file(file).path)
                isLooping = loop
                setVolume(0f, 0f)
            }
        } catch (e: Exception) { return }
        val deck = fader.play(file, fadeMs)
        val key = deckKey(isAmbience, deck.id)
        players[key] = player
        player.setOnErrorListener { _, _, _ ->
            // a file the phone can't decode: dropped quietly (a music bed moves on to its next track)
            fader.end(deck.id); players.remove(key)?.let { runCatching { it.release() } }
            if (!isAmbience && t == token) handler.postDelayed({ played++; nextTrack(t, MusicPicker.FADE_MS) }, MusicPicker.MIN_GAP_MS)
            true
        }
        player.setOnCompletionListener {
            fader.end(deck.id); players.remove(key)?.let { runCatching { it.release() } }
            val b = bed
            if (!isAmbience && t == token && b != null) {
                played++
                handler.postDelayed({ nextTrack(t, MusicPicker.FADE_MS) }, MusicPicker.gapMs(b.gapMs, seed + played))
            }
        }
        player.setOnPreparedListener { p ->
            if (players[key] !== p) return@setOnPreparedListener
            applyVolumes()
            if (!paused) runCatching { p.start() }
            tick()
        }
        runCatching { player.prepareAsync() }.onFailure { fader.end(deck.id); players.remove(key); runCatching { player.release() } }
        tick()
    }

    /** The ambience loop crosses to [amb] over [fadeMs] (none: fades out); the music is left as it is. */
    private fun crossAmbience(amb: Int?, fadeMs: Int) {
        val ak = amb?.toString() ?: ""
        if (ak == ambienceKey) return
        ambienceKey = ak
        val t = token
        if (amb == null || !files.has(amb)) ambience.stop(fadeMs)
        if (amb != null) ready(amb, t) { if (ambienceKey == ak) startDeck(amb, isAmbience = true, loop = true, fadeMs = fadeMs, t = t) }
        tick()
    }

    // ---- the time of day (simulation/WorldClock): night ambience, the music's level, flavour sounds, bells -----------

    private var clockNight = false
    private var clockHour = Double.NaN
    private var nightMusic = 1f
    private val cineMusic = CinematicMusic()
    private var placeId: String? = null
    private var placeSounds: PlaceTimeSounds? = null
    private var placeToken = 0                               // a new place (or none) makes the old one's flavour and bells stop
    private var flavorCount = 0L

    /**
     * The world's clock: its hour of the day ([hours], WorldClock.lightHour) and how many real seconds a game hour takes
     * now ([realSecondsPerHour], WorldClock.realSecondsPerHour). Call it as the clock moves (a few times a second is plenty).
     * At dusk and dawn the place's ambience crosses to its night or day loop (the music plays on), the music settles a
     * little quieter by night, and on a bell hour a place with a bell rings it. Main thread.
     */
    fun timeOfDay(hours: Double, realSecondsPerHour: Double = 60.0) {
        if (!hours.isFinite()) return
        val before = clockHour
        clockHour = hours
        val night = TimeOfDaySound.isNight(hours)
        val level = TimeOfDaySound.musicLevel(hours)
        if (kotlin.math.abs(level - nightMusic) > 0.002f) { nightMusic = level; applyVolumes() }
        if (night != clockNight) {
            clockNight = night
            placeId?.let { id -> book.places[id]?.let { p -> MusicPicker.place(p, night)?.let { setBed(it, MusicPicker.FADE_MS) } } }
        }
        val struck = TimeOfDaySound.struck(before, hours) ?: return
        val bell = placeSounds?.bell.orEmpty()
        if (bell.isEmpty() || paused) return
        val strokes = TimeOfDaySound.bellStrokes(struck, realSecondsPerHour)
        val vol = placeSounds?.bellVolume ?: 1f
        val pt = placeToken
        for (i in 0 until strokes) {
            val file = TimeOfDaySound.pick(bell, i.toLong()) ?: break
            handler.postDelayed({ if (pt == placeToken && !paused) playShort(file, Bus.AMBIENCE, vol) }, i * TimeOfDaySound.BELL_SPACING_MS)
        }
    }

    private fun enterPlace(id: String) {
        placeId = id
        placeSounds = TimeOfDaySound.PLACES[id]
        val pt = ++placeToken
        // its flavour and bell sounds loaded ahead (fetched when they aren't here: small files)
        placeSounds?.files?.forEach { load(it) }
        if (placeSounds?.flavor(false).isNullOrEmpty() && placeSounds?.flavor(true).isNullOrEmpty()) return
        scheduleFlavor(pt)
    }

    private fun leavePlace() {
        if (placeId == null) return
        placeId = null
        placeSounds = null
        placeToken++
    }

    /** Now and then, while the place is the same: one of its flavour sounds, the day's or the night's. */
    private fun scheduleFlavor(pt: Int) {
        val wait = TimeOfDaySound.flavorDelayMs(pt * 7919L + flavorCount)
        handler.postDelayed({
            if (pt != placeToken) return@postDelayed
            val ps = placeSounds
            if (ps != null && !paused) {
                flavorCount++
                TimeOfDaySound.pick(ps.flavor(clockNight), flavorCount * 31 + pt)?.let { playShort(it, Bus.AMBIENCE, ps.flavorVolume) }
            }
            scheduleFlavor(pt)
        }, wait)
    }

    // ---- the mixer and its clock ------------------------------------------------------------------------------------

    /** Cinematic mode on or off: the music eases down to 70% while it runs (CinematicMusic), the ambience stays. Main thread. */
    fun cinematic(on: Boolean) {
        if (cineMusic.on == on) return
        cineMusic.on = on
        tick()
    }

    /** How many voices are speaking now (SceneAudio.onVoices): the beds dip under them. */
    fun voices(n: Int) {
        ducker.setVoices(n)
        tick()
    }

    private var ticking = false
    private var last = 0L

    private fun tick() {
        if (ticking) return
        ticking = true
        last = SystemClock.uptimeMillis()
        handler.post(object : Runnable {
            override fun run() {
                val now = SystemClock.uptimeMillis()
                val dt = (now - last).coerceIn(1, 200)
                last = now
                for (d in music.step(dt)) players.remove(deckKey(false, d.id))?.let { runCatching { it.release() } }
                for (d in ambience.step(dt)) players.remove(deckKey(true, d.id))?.let { runCatching { it.release() } }
                ducker.step(dt)
                cineMusic.step(dt)
                applyVolumes()
                if (music.busy || ambience.busy || ducker.moving || cineMusic.moving) handler.postDelayed(this, TICK_MS) else ticking = false
            }
        })
    }

    private fun applyVolumes() {
        val m = Mixer.gain(levels, Bus.MUSIC, ducker.level) * nightMusic * cineMusic.level
        val a = Mixer.gain(levels, Bus.AMBIENCE, ducker.ambienceLevel)
        for (d in music.decks) players[deckKey(false, d.id)]?.let { p -> val v = d.level * m; runCatching { p.setVolume(v, v) } }
        for (d in ambience.decks) players[deckKey(true, d.id)]?.let { p -> val v = d.level * a; runCatching { p.setVolume(v, v) } }
    }

    // ---- short sounds: the interface, the story's effects and voices --------------------------------------------------

    private val pool = SoundPool.Builder().setMaxStreams(8).setAudioAttributes(
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
    ).build()
    private val loaded = HashMap<Int, Int>()            // file id -> sound pool id, once loaded
    private val loading = HashMap<Int, Int>()           // sound pool id -> file id, while loading
    private val fetching = HashSet<Int>()
    private val playWhenLoaded = HashMap<Int, Pair<Bus, Float>>()     // sound pool ids asked for before they loaded, and their level

    init {
        pool.setOnLoadCompleteListener { p, id, status ->
            val file = loading.remove(id) ?: return@setOnLoadCompleteListener
            val want = playWhenLoaded.remove(id)
            if (status != 0) return@setOnLoadCompleteListener
            loaded[file] = id
            if (want != null && !paused) (Mixer.gain(levels, want.first) * want.second).let { v -> if (v > 0f) p.play(id, v, v, 1, 0, 1f) }
        }
        filesArrived()
    }

    private fun load(file: Int): Int? {
        loaded[file]?.let { return it }
        loading.entries.firstOrNull { it.value == file }?.let { return it.key }
        if (!files.has(file)) {
            // the interface's sounds are tiny: fetched as they are first wanted (played from the next time)
            if (fetchAllowed && fetching.add(file)) background {
                val ok = runCatching { files.fetchNow(file) }.isSuccess
                handler.post { fetching.remove(file); if (ok) load(file) }
            }
            return null
        }
        val id = runCatching { pool.load(files.file(file).path, 1) }.getOrNull() ?: return null
        loading[id] = file
        return id
    }

    /** [file] once on [bus], at [scale] of the bus's level (a sound kit's own volume). */
    private fun playShort(file: Int, bus: Bus, scale: Float = 1f) {
        if (paused) return
        val v = Mixer.gain(levels, bus) * scale.coerceIn(0f, 1f)
        if (v <= 0f) return
        val id = load(file) ?: return
        if (loaded[file] == id) pool.play(id, v, v, 1, 0, 1f) else playWhenLoaded[id] = bus to scale.coerceIn(0f, 1f)
    }

    private fun pick(list: List<Int>?): Int? = list?.takeIf { it.isNotEmpty() }?.let { it[Random.nextInt(it.size)] }

    override fun ui(name: String) {
        val n = UiSounds.resolve(name, book.ui.keys) ?: return
        pick(book.ui[n])?.let { playShort(it, Bus.UI) }
    }

    override fun effect(name: String) { pick(book.effects[name])?.let { playShort(it, Bus.EFFECTS) } }

    override fun voice(look: String, emote: String) { pick(book.voices[look]?.get(emote))?.let { playShort(it, Bus.DIALOG) } }

    // ---- the story's named music and ambience (play/Sounds) -----------------------------------------------------------

    override fun music(name: String, fadeMs: Int) {
        leavePlace()
        val file = book.music[name]
        if (file == null) { token++; bed = null; music.stop(fadeMs); tick(); return }
        val b = MusicBed("story:$name", listOf(file), loop = true)
        if (bed?.key == b.key) return
        bed = b
        val t = ++token
        played = 0
        nextTrack(t, fadeMs)
        tick()
    }

    override fun ambience(name: String) {
        val file = book.ambience[name]
        val key = file?.toString() ?: ""
        if (key == ambienceKey) return
        ambienceKey = key
        if (file == null) { ambience.stop(1200); tick(); return }
        val t = token
        ready(file, t) { startDeck(file, isAmbience = true, loop = true, fadeMs = 1200, t = t) }
    }

    // ---- the app's life ---------------------------------------------------------------------------------------------

    /** The app went to the background: everything holds, silent. */
    fun pause() {
        paused = true
        for (p in players.values) runCatching { if (p.isPlaying) p.pause() }
        pool.autoPause()
    }

    /** Back: what was playing carries on. */
    fun resume() {
        if (!paused) return
        paused = false
        for (p in players.values) runCatching { p.start() }
        if (levels.enabled) pool.autoResume()
        tick()
    }

    /** The sound files were gathered: the short sounds loaded ahead, so they play on time. */
    fun filesArrived() {
        // what is here is loaded; what isn't is fetched when fetching is allowed (load decides)
        for (id in book.shortFiles) load(id)
    }

    fun release() {
        token++
        placeToken++
        handler.removeCallbacksAndMessages(null)
        for (p in players.values) runCatching { p.release() }
        players.clear(); music.clear(); ambience.clear()
        io.shutdownNow()
        pool.release()
    }

    private companion object {
        const val TICK_MS = 40L
    }
}
