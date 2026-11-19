package com.logie.wifecraft.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import com.logie.wifecraft.catalog.Emote
import com.logie.wifecraft.catalog.SoundCatalog
import com.logie.wifecraft.creator.GameTables
import com.logie.wifecraft.creator.Tables
import com.logie.wifecraft.data.GameFiles
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.Executors

/**
 * The scene editor's sound (SPEC3): a location's ambience loop and music ([enterLocation]), an emote's voice ([emote]),
 * a creature's ([creature]) and an object's ([gameObject]) sounds, a placement's switch ([setEnabled]), [stop]. The game's
 * files (.ogg, .mp3 by id) are fetched on demand through [GameFiles.fetchNow], off the main thread, and played as they
 * arrive; what couldn't be fetched is in [error] (shown by the editor), the rest plays. What each one is, which file and
 * when is [SceneAudioCore]'s (JVM-tested). Which kits there are comes from the game's tables ([SoundCatalog]) and
 * `files/cache/sound-index.bin` ([SoundIndex]: made once from SoundKitEntry, 37 MB streamed, kept at about 1 MB).
 * Call every method on the main thread.
 */
class SceneAudio(private val context: Context, private val files: GameFiles, private val tables: Tables) : PartnerSoundPlayer {
    private val handler = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(3) { r -> Thread(r, "scene-audio").apply { isDaemon = true } }
    private val _error = MutableStateFlow<String?>(null)

    /** The last thing that went wrong (a file or the tables couldn't be fetched), null when nothing has. */
    val error: StateFlow<String?> = _error

    private val _preparing = MutableStateFlow(false)

    /** True while the sound index is being built or downloaded (the first sound of a session; the editor says "Preparing sounds"). */
    val preparing: StateFlow<Boolean> = _preparing

    private val index by lazy {
        if (!GameTables.has(files.tablesDir, "AreaTable") || !GameTables.has(files.tablesDir, "CreatureSoundData"))
            throw IllegalStateException("the game's sound tables aren't on the phone yet")
        // built on a background thread (the lazy runs inside the first sound's task): the flag is read on the main thread
        handler.post { _preparing.value = true }
        try {
            SoundIndex.load(File(context.filesDir, "cache/sound-index.bin"), indexKey(), tables) { GameTables.download("SoundKitEntry") }.first
        } finally {
            handler.post { _preparing.value = false }
        }
    }

    private fun indexKey(): String = GameTables.BUILD + SOUND_TABLES.joinToString("-", "-") { File(files.tablesDir, "$it.csv").length().toString(36) }

    private val catalog by lazy { SoundCatalog(tables) { index.files(it) } }

    private val core = SceneAudioCore(
        lookup = object : SoundLookup {
            override fun location(areaId: Int, zoneMusic: Int, ambience: Int) = catalog.location(areaId, zoneMusic, ambience)
            override fun creature(displayId: Int) = catalog.creature(displayId)
            override fun gameObject(displayId: Int) = catalog.gameObject(displayId)
        },
        files = object : SoundFiles {
            override fun has(id: Int) = files.has(id)
            override fun fetch(id: Int) = files.fetchNow(id)
        },
        out = object : SoundOut {
            override fun play(file: Int, loop: Boolean, volume: Float, fadeInMs: Int, onDone: () -> Unit): SoundVoice? =
                PlayerVoice.create(handler, files.file(file).path, loop, volume, fadeInMs, onDone)
        },
        sched = object : SoundScheduler {
            override fun background(task: () -> Unit) { pool.execute { try { task() } catch (e: Exception) { handler.post { _error.value = e.message ?: e.toString() } } } }
            override fun main(task: () -> Unit) { handler.post(task) }
            override fun later(ms: Long, task: () -> Unit) { handler.postDelayed(task, ms) }
        },
        onError = { _error.value = it },
        onVoices = { coreVoices = it; tellVoices() },
    )

    /**
     * Told on the main thread whenever the number of voices playing changes (the emotes' and the partner sounds'): the
     * activity ducks the music under them (Audio.voices).
     */
    var onVoices: (Int) -> Unit = {}
    private var coreVoices = 0
    private var toldVoices = 0
    private fun tellVoices() {
        val n = coreVoices + partnerVoices.size
        if (n != toldVoices) { toldVoices = n; onVoices(n) }
    }

    /** Music, ambience, effects and voices, 0..1 each (the options' sliders; voices default to the sounds' level). */
    var volumes: SoundVolumes
        get() = core.volumes
        set(v) { core.volumes = v }

    /** The place's ambience loop and music (area 9171, the Embassy: with zoneMusic 1929 and ambience 569 from locations.json's ambience block). */
    fun enterLocation(areaId: Int, zoneMusic: Int = 0, ambience: Int = 0, night: Boolean = false) = core.enterLocation(areaId, night, zoneMusic, ambience)
    fun emote(raceId: Int, sex: Int, emote: Emote, placementId: String? = null) = core.emote(raceId, sex, emote, placementId)
    fun creature(displayId: Int, kind: String, placementId: String? = null) = core.creature(displayId, kind, placementId)
    fun gameObject(displayId: Int, placementId: String? = null) = core.gameObject(displayId, placementId)
    /** The contract's `object(displayId)`. */
    fun `object`(displayId: Int) = core.gameObject(displayId)
    fun setEnabled(placementId: String, on: Boolean) = core.setEnabled(placementId, on)
    fun isEnabled(placementId: String) = core.isEnabled(placementId)
    fun stopPlacement(placementId: String) = core.stopPlacement(placementId)
    fun pause() { core.pause(); partnerVoices.forEach { it.second.pause() } }
    fun resume() { core.resume(); partnerVoices.forEach { it.second.resume() } }
    fun stop() = core.stop()
    fun clearError() { _error.value = null }

    // ---- partner sounds: the user's own files, matched to the sound names of partner animations (PartnerSounds). Main thread.

    /** The user's sounds folder (files/partner-sounds). */
    val partner = PartnerSounds(File(context.filesDir, "partner-sounds"))
    init { pool.execute { runCatching { partner.warm() } } }

    /** Settings.partnerSoundsOn and partnerSoundVolume (0..1): set by the activity. */
    @Volatile var partnerOn = true
    @Volatile var partnerVolume = 0.85f

    private val partnerVoices = ArrayList<Pair<String?, SoundVoice>>()
    private val partnerPlays = HashMap<String, Int>()
    private var partnerGen = 0

    /**
     * Plays the user's file for the sound [name] (one of its numbered variants, picked by a seed that is stable per call) as a
     * voice; the file is opened off the main thread. False, and nothing played, when the sounds are off or no file matches (not
     * an error: the caller carries on). A looping sound with an [actorKey] replaces that actor's earlier loop of the same name.
     */
    override fun partnerSound(name: String, actorKey: String?, loop: Boolean, volume: Float): Boolean {
        if (!partnerOn) return false
        val found = try { partner.resolve(name) } catch (e: Exception) { emptyList() }
        val tag = SoundNames.normalise(name) + "|" + (actorKey ?: "")
        val n = (partnerPlays[tag] ?: 0) + 1
        partnerPlays[tag] = n
        val file = SoundNames.choose(found, SoundNames.seed(name, actorKey, n)) { it.name } ?: return false
        val level = (volume * partnerVolume * volumes.voices).coerceIn(0f, 1f)
        val gen = partnerGen
        val loopKey = if (loop && actorKey != null) tag else null
        val box = arrayOfNulls<SoundVoice>(1)
        pool.execute {
            val v = PlayerVoice.create(handler, file.path, loop, level, 0) { handler.post { partnerVoices.removeAll { it.second === box[0] }; tellVoices() } }
            box[0] = v
            handler.post {
                when {
                    v == null -> {}
                    gen != partnerGen || !partnerOn -> v.stop(0)
                    else -> {
                        if (loopKey != null) { partnerVoices.filter { it.first == loopKey }.forEach { it.second.stop(0) }; partnerVoices.removeAll { it.first == loopKey } }
                        partnerVoices.add(loopKey to v)
                        tellVoices()
                    }
                }
            }
        }
        return true
    }

    /** Stops every partner sound (and any still opening). */
    override fun stopAll() {
        partnerGen++
        partnerVoices.forEach { it.second.stop(0) }
        partnerVoices.clear()
        tellVoices()
    }

    fun release() {
        stopAll()
        core.stop()
        handler.removeCallbacksAndMessages(null)
        pool.shutdown()
    }

    /** A MediaPlayer with its own fade; released when it ends or is stopped. */
    private class PlayerVoice(private val handler: Handler, private val player: MediaPlayer, private var level: Float) : SoundVoice {
        private var gain = 0f
        private var target = 1f
        private var step = 1f
        private var releasing = false
        private var held = false
        private var ramping = false

        private fun apply() { if (!releasing) runCatching { val v = (level * gain).coerceIn(0f, 1f); player.setVolume(v, v) } }

        private fun ramp() {
            if (ramping) return
            ramping = true
            handler.post(object : Runnable {
                override fun run() {
                    if (releasing) { ramping = false; return }
                    gain = if (gain < target) minOf(target, gain + step) else maxOf(target, gain - step)
                    apply()
                    if (gain == target) {
                        ramping = false
                        if (target == 0f) release()
                    } else handler.postDelayed(this, TICK_MS)
                }
            })
        }

        fun fadeTo(t: Float, ms: Int) {
            target = t
            if (ms <= 0) { gain = t; apply(); if (t == 0f) release() } else { step = TICK_MS.toFloat() / ms; ramp() }
        }

        override fun setVolume(v: Float) { level = v; apply() }
        override fun stop(fadeMs: Int) { if (held || fadeMs <= 0) release() else fadeTo(0f, fadeMs) }
        override fun pause() { held = true; runCatching { if (player.isPlaying) player.pause() } }
        override fun resume() { held = false; if (!releasing) runCatching { player.start() } }
        fun release() { if (releasing) return; releasing = true; runCatching { player.release() } }

        companion object {
            private const val TICK_MS = 50L

            fun create(handler: Handler, path: String, loop: Boolean, volume: Float, fadeInMs: Int, onDone: () -> Unit): PlayerVoice? = try {
                val p = MediaPlayer()
                val v = PlayerVoice(handler, p, volume)
                p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                p.setDataSource(path)
                p.isLooping = loop
                p.setVolume(0f, 0f)
                p.setOnCompletionListener { if (!v.releasing) { v.release(); onDone() } }
                p.setOnErrorListener { _, _, _ -> if (!v.releasing) { v.release(); onDone() }; true }
                p.prepare()
                p.start()
                v.fadeTo(1f, fadeInMs)
                v
            } catch (e: Exception) {
                null
            }
        }
    }

    private companion object {
        /** The tables [SoundCatalog] reads, less SoundKitEntry and the creator's own (creator/GameTables.NAMES): the index key. */
        val SOUND_TABLES = listOf("AreaTable", "SoundAmbience", "SoundAmbienceFlavor", "ZoneMusic", "ZoneIntroMusicTable",
            "CreatureSoundData", "NPCSounds", "GameObjectDisplayInfo", "ObjectEffectPackageElem", "ObjectEffect")
    }
}
