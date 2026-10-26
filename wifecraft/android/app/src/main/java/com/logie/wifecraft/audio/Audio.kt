package com.logie.wifecraft.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import com.logie.wifecraft.data.GameFiles
import com.logie.wifecraft.play.Sounds
import org.json.JSONObject
import kotlin.random.Random

/**
 * The game's music and sounds (assets/sounds.json, tools/sounds.py), played from the files fetched
 * from wago.tools: music crossfades between two players and loops; the city's ambience loops under
 * it; effects, voices and the interface's sounds go through a sound pool. Anything not fetched yet
 * stays silent. Main thread only.
 */
class Audio(context: Context, private val files: GameFiles) : Sounds {
    private val music: Map<String, Int>
    private val ambiences: Map<String, Int>
    private val ui: Map<String, List<Int>>
    private val voices: Map<String, Map<String, List<Int>>>
    private val effects: Map<String, List<Int>>

    init {
        val o = JSONObject(context.assets.open("sounds.json").bufferedReader().use { it.readText() })
        fun ints(m: JSONObject) = m.keys().asSequence().associateWith { m.getInt(it) }
        fun lists(m: JSONObject) = m.keys().asSequence().associateWith { k -> m.getJSONArray(k).let { a -> List(a.length()) { a.getInt(it) } } }
        music = ints(o.getJSONObject("music"))
        ambiences = ints(o.getJSONObject("ambience"))
        ui = lists(o.getJSONObject("ui"))
        effects = lists(o.getJSONObject("effects"))
        val v = o.getJSONObject("voices")
        voices = v.keys().asSequence().associateWith { lists(v.getJSONObject(it)) }
    }

    /** 0..1, from the options. */
    var musicVolume = 0.7f
        set(value) { field = value; applyVolumes() }
    var soundVolume = 0.85f
        set(value) { field = value; applyVolumes() }

    private val handler = Handler(Looper.getMainLooper())
    private val pool = SoundPool.Builder().setMaxStreams(8).setAudioAttributes(
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
    ).build()
    private val loaded = mutableMapOf<Int, Int>()       // file id -> sound pool id, once loaded
    private val loading = mutableMapOf<Int, Int>()      // sound pool id -> file id, while loading
    private val playWhenLoaded = mutableSetOf<Int>()    // sound pool ids asked for before they loaded

    private class Track(val name: String, val player: MediaPlayer, val isAmbience: Boolean = false) {
        var level = 0f       // its own fade, 0..1
        var target = 0f
        var step = 0f        // per tick
    }
    private var current: Track? = null
    private val fading = mutableListOf<Track>()
    private var ambience: Track? = null
    private var paused = false
    private var wantedMusic = ""

    init {
        pool.setOnLoadCompleteListener { p, id, status ->
            val file = loading.remove(id) ?: return@setOnLoadCompleteListener
            if (status != 0) return@setOnLoadCompleteListener
            loaded[file] = id
            if (playWhenLoaded.remove(id) && !paused) p.play(id, soundVolume, soundVolume, 1, 0, 1f)
        }
        // The short sounds are loaded ahead, so they play on time.
        for (ids in ui.values + effects.values + voices.values.flatMap { it.values }) for (id in ids) load(id)
    }

    private fun load(file: Int): Int? {
        loaded[file]?.let { return it }
        loading.entries.firstOrNull { it.value == file }?.let { return it.key }
        if (!files.has(file)) return null
        val id = pool.load(files.file(file).path, 1)
        loading[id] = file
        return id
    }

    private fun playShort(file: Int) {
        if (paused) return
        val id = load(file) ?: return
        if (loaded[file] == id) pool.play(id, soundVolume, soundVolume, 1, 0, 1f) else playWhenLoaded += id
    }

    override fun music(name: String, fadeMs: Int) {
        wantedMusic = name
        if (current?.name == name) return
        current?.let { fadeOut(it, fadeMs) }
        current = null
        val file = music[name] ?: return
        val player = start(file, loop = true) ?: return
        current = Track(name, player).also { t ->
            t.target = 1f
            t.step = if (fadeMs <= 0) 1f else TICK_MS.toFloat() / fadeMs
            if (fadeMs <= 0) t.level = 1f
        }
        applyVolumes()
        tick()
    }

    override fun ambience(name: String) {
        val file = ambiences[name]
        if (ambience?.name == name) return
        ambience?.let { fadeOut(it, 1200) }
        ambience = null
        if (file == null) return
        val player = start(file, loop = true) ?: return
        ambience = Track(name, player, isAmbience = true).also { it.target = 1f; it.step = TICK_MS / 1200f }
        applyVolumes()
        tick()
    }

    override fun effect(name: String) {
        effects[name]?.takeIf { it.isNotEmpty() }?.let { playShort(it[Random.nextInt(it.size)]) }
    }

    override fun voice(look: String, emote: String) {
        voices[look]?.get(emote)?.takeIf { it.isNotEmpty() }?.let { playShort(it[Random.nextInt(it.size)]) }
    }

    override fun ui(name: String) {
        ui[name]?.firstOrNull()?.let { playShort(it) }
    }

    private fun start(file: Int, loop: Boolean): MediaPlayer? {
        if (!files.has(file)) return null
        return try {
            MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                setDataSource(files.file(file).path)
                isLooping = loop
                setVolume(0f, 0f)
                prepare()
                if (!paused) start()
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun fadeOut(t: Track, ms: Int) {
        t.target = 0f
        t.step = if (ms <= 0) 1f else TICK_MS.toFloat() / ms
        fading += t
        tick()
    }

    private var ticking = false
    private fun tick() {
        if (ticking) return
        ticking = true
        handler.post(object : Runnable {
            override fun run() {
                var busy = false
                val all = listOfNotNull(current, ambience) + fading
                for (t in all) {
                    if (t.level < t.target) t.level = minOf(t.target, t.level + t.step)
                    else if (t.level > t.target) t.level = maxOf(t.target, t.level - t.step)
                    if (t.level != t.target) busy = true
                }
                for (t in fading.filter { it.level <= 0f }) {
                    runCatching { t.player.release() }
                    fading.remove(t)
                }
                applyVolumes()
                if (busy || fading.isNotEmpty()) handler.postDelayed(this, TICK_MS.toLong()) else ticking = false
            }
        })
    }

    private fun applyVolumes() {
        for (t in listOfNotNull(current, ambience) + fading) {
            val v = t.level * if (t.isAmbience) soundVolume * AMBIENCE_LEVEL else musicVolume
            runCatching { t.player.setVolume(v, v) }
        }
    }

    /** The app went to the background (or came back). */
    fun pause() {
        paused = true
        for (t in listOfNotNull(current, ambience) + fading) runCatching { if (t.player.isPlaying) t.player.pause() }
        pool.autoPause()
    }

    fun resume() {
        paused = false
        for (t in listOfNotNull(current, ambience)) runCatching { t.player.start() }
        pool.autoResume()
    }

    /** Once the sounds are fetched: start what should already be playing. */
    fun filesArrived() {
        for (ids in ui.values + effects.values + voices.values.flatMap { it.values }) for (id in ids) load(id)
        if (current == null && wantedMusic.isNotEmpty()) music(wantedMusic, 1500)
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        for (t in listOfNotNull(current, ambience) + fading) runCatching { t.player.release() }
        current = null; ambience = null; fading.clear()
        pool.release()
    }

    private companion object {
        const val TICK_MS = 50
        const val AMBIENCE_LEVEL = 0.55f   // under the music
    }
}
