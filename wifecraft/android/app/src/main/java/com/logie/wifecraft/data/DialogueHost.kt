package com.logie.wifecraft.data

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.logie.wifecraft.dialogue.DialogueConfig
import com.logie.wifecraft.dialogue.DialogueEngine
import com.logie.wifecraft.dialogue.DialogueModel
import com.logie.wifecraft.dialogue.DialogueModelSpec
import com.logie.wifecraft.dialogue.DialogueModels
import com.logie.wifecraft.dialogue.ModelDownloader
import com.logie.wifecraft.dialogue.ModelTier
import com.logie.wifecraft.dialogue.ThreadCalibration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** What Options → Mods → Dialogue model shows (DialogueHost.state). */
data class DialogueUiState(
    /** The model in use (the one the player picked when it is downloaded, else Fast, else the Quality one: [DialogueModels.active]). */
    val spec: DialogueModelSpec = DialogueModels.FAST,
    val phase: Phase = Phase.NOT_DOWNLOADED,
    val haveBytes: Long = 0,
    val totalBytes: Long = 0,
    val message: String? = null,
    val totalRam: Long = 0,
    val availableRam: Long = 0,
    /** Bytes of the previous (Standard 4B / Lite 1.7B) models' files still on the phone: offered for deletion once the new model is there. */
    val oldModelBytes: Long = 0,
    /** While LOADING: how far the model's weights are read, 0 to 1 (a 7.5 GB file takes a while), or -1 when not known. */
    val loadProgress: Float = -1f,
    /** The catalogue's models whose file is on the phone, whole (the Options page lists each with its own buttons). */
    val installed: Set<String> = emptySet(),
    /** Bytes of each catalogue model's file (or its unfinished download) on the phone, by id. */
    val haveById: Map<String, Long> = emptyMap(),
    /** The model being downloaded now (null: none). */
    val downloadingId: String? = null,
) {
    enum class Phase { NOT_DOWNLOADED, PARTIAL, DOWNLOADING, CHECKING, READY, LOADING, LOADED, FAILED }
    val fraction: Float get() = if (totalBytes <= 0) 0f else (haveBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
}

/**
 * The app's one dialogue model (dialogue/DialogueModel) with its file, its download and its load. The persona layer takes
 * [engine]; until a model is downloaded, switched on and loaded it answers nothing, so the game goes on silent. The model is
 * NOT part of the app: the player asks for it in Options → Mods → Dialogue model and it is fetched from Hugging Face by
 * [ModelDownloadService] (resumable, checksummed). Not run on a phone.
 */
object DialogueHost {
    @Volatile var renderLevel = 0   // Graphics' governor step: the dialogue model yields to a struggling renderer
    private lateinit var app: Context
    private lateinit var settings: Settings
    private val started = AtomicBoolean(false)
    @Volatile private var haltDownload = false
    @Volatile private var downloading = false

    private val model by lazy { DialogueModel(File(app.filesDir, "dialogue/state")) { DialogueModel.loadFromGovernor(renderLevel) } }
    private val _state = MutableStateFlow(DialogueUiState())
    val state: StateFlow<DialogueUiState> get() = _state

    /** What the persona layer talks to: the model once loaded, else an engine that answers null. */
    val engine: DialogueEngine get() = if (started.get()) model.engine else com.logie.wifecraft.dialogue.NoDialogueEngine

    fun init(context: Context) {
        if (!started.compareAndSet(false, true)) return
        app = context.applicationContext
        settings = Settings(app)
        refresh()
    }

    val config: DialogueConfig get() = settings.dialogueModel

    fun totalRam(): Long = memory().totalMem
    private fun memory(): ActivityManager.MemoryInfo =
        ActivityManager.MemoryInfo().also { app.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }

    fun specFor(cfg: DialogueConfig = config): DialogueModelSpec =
        if (cfg.modelId?.startsWith("custom:") == true && cfg.customPath != null) DialogueModels.custom(cfg.customPath, bytes = File(cfg.customPath).length())
        else DialogueModels.active(cfg) { isInstalled(it) }

    /** The model's file is on the phone, whole. */
    fun isInstalled(spec: DialogueModelSpec): Boolean = fileFor(spec).let { it.isFile && (spec.tier == ModelTier.CUSTOM || it.length() == spec.bytes) }

    fun fileFor(spec: DialogueModelSpec): File =
        if (spec.tier == ModelTier.CUSTOM) File(spec.id.removePrefix("custom:")) else File(modelsDir(), spec.file)

    private val downloader = ModelDownloader()

    /** Reads the disk and the settings into [state] (not while a download or a load is running). */
    fun refresh() {
        if (!started.get() || downloading) return
        val cfg = config
        val spec = specFor(cfg)
        val f = fileFor(spec)
        val mem = memory()
        val loaded = model.isLoaded && model.spec?.id == spec.id
        val have = downloader.have(f)
        val phase = when {
            loaded -> DialogueUiState.Phase.LOADED
            f.isFile && (spec.tier == ModelTier.CUSTOM || f.length() == spec.bytes) -> DialogueUiState.Phase.READY
            have > 0 -> DialogueUiState.Phase.PARTIAL
            else -> DialogueUiState.Phase.NOT_DOWNLOADED
        }
        val prev = _state.value
        val ids = DialogueModels.all.filter { isInstalled(it) }.map { it.id }.toSet()
        val haves = DialogueModels.all.associate { it.id to (if (it.id in ids) it.bytes else downloader.have(fileFor(it))) }
        _state.value = DialogueUiState(spec, phase, if (phase == DialogueUiState.Phase.READY || phase == DialogueUiState.Phase.LOADED) spec.bytes else have, spec.bytes,
            if (prev.phase == DialogueUiState.Phase.FAILED && phase == prev.phase) prev.message else null, mem.totalMem, mem.availMem, oldModelBytes(),
            installed = ids, haveById = haves)
    }

    private fun modelsDir(): File = File(app.filesDir, "dialogue/models")

    /** Size of the files of the models the catalogue used to hold (never loaded: only the catalogue's two are). */
    fun oldModelBytes(): Long = DialogueModels.staleFiles(modelsDir()).sumOf { it.length() }

    /** Deletes the old models' files (and their saved NPC places, which belong to a model that is gone) to free the space. */
    fun deleteOldModels() {
        if (!started.get()) return
        Thread({
            DialogueModels.staleFiles(modelsDir()).forEach { it.delete() }
            File(app.filesDir, "dialogue/state").listFiles()?.filter { d -> DialogueModels.all.none { it.stateName == d.name } }?.forEach { it.deleteRecursively() }
            refresh()
        }, "wc-dialogue-old").start()
    }

    fun setConfig(cfg: DialogueConfig) {
        settings.dialogueModel = cfg
        refresh()
    }

    /** Switches the model on or off (off unloads it and frees its memory). */
    fun setEnabled(on: Boolean) {
        setConfig(config.copy(enabled = on))
        if (on) loadAsync() else unload()
    }

    /** Loads the chosen model in the background when it is on the phone and switched on (the app's start, a scene's start). */
    fun ensureLoaded() { if (started.get() && config.enabled) loadAsync() }

    private val loading = AtomicBoolean(false)
    private fun loadAsync() {
        val cfg = config
        val spec = specFor(cfg)
        val f = fileFor(spec)
        if (!f.isFile || (model.isLoaded && model.spec?.id == spec.id) || !loading.compareAndSet(false, true)) return
        _state.value = _state.value.copy(spec = spec, phase = DialogueUiState.Phase.LOADING, message = null, loadProgress = 0f)
        Thread({
            // loading reads the whole file (several GB): it runs here, never on the UI thread, and the page shows how far it is
            val watcher = Thread({
                try { while (true) { Thread.sleep(400); val p = model.loadProgress(); if (_state.value.phase == DialogueUiState.Phase.LOADING) _state.value = _state.value.copy(loadProgress = p) } } catch (_: InterruptedException) { }
            }, "wc-dialogue-load-progress").apply { isDaemon = true; start() }
            try {
                val err = model.load(spec, f, cfg.threads, cfg.context, cfg.slots, cfg.ambientTokensPerMinute, cfg.calibrationFor(spec.id))
                if (err != null) failLoad(err) else { _state.value = _state.value.copy(loadProgress = -1f); refresh(); calibrateOnce(spec, cfg) }
            } catch (e: OutOfMemoryError) {
                failLoad("the phone ran out of memory loading the model; close other apps and try again")
            } catch (t: Throwable) {
                failLoad(t.message ?: "the model did not load")
            } finally { watcher.interrupt(); loading.set(false) }
        }, "wc-dialogue-load").start()
    }

    /** A load that failed (out of memory, a bad file): whatever was half loaded is freed, the page says why, the game goes on silent. */
    private fun failLoad(why: String) {
        runCatching { model.unload() }
        _state.value = _state.value.copy(phase = DialogueUiState.Phase.FAILED, message = why, loadProgress = -1f)
    }

    fun unload() { Thread({ model.unload(); refresh() }, "wc-dialogue-unload").start() }

    /**
     * The first time a model is loaded with Speed on Auto: it writes 32 tokens with 3, 4, 5 and 6 threads ([ThreadCalibration], about
     * 3 seconds each with the Fast model) and the fastest count is kept for this model ([DialogueConfig.calibratedThreads]) and used at once.
     * Skipped for the 12B model (each trial would take 10 seconds or more), when the player chose a thread count by hand, and when it was done.
     */
    private fun calibrateOnce(spec: DialogueModelSpec, cfg: DialogueConfig) {
        if (cfg.threads > 0 || cfg.calibrationFor(spec.id) > 0 || !spec.compact) return
        Thread({
            try {
                val trials = kotlinx.coroutines.runBlocking { model.calibrate() } ?: return@Thread
                val best = ThreadCalibration.best(trials)
                lastCalibration = ThreadCalibration.describe(trials)
                if (best > 0 && model.spec?.id == spec.id) {
                    setConfig(config.copy(calibratedThreads = best, calibratedFor = spec.id))
                    model.setSpeed(config.threads, best)
                }
            } catch (_: Throwable) { }
        }, "wc-dialogue-calibrate").start()
    }

    /** What the last calibration measured, in words (shown with the Speed test; null before one ran). */
    @Volatile var lastCalibration: String? = null
        private set

    // ---- speed: the timings, the thread count, the speed test ------------------------------------------------------------

    /** What every request cost, the last 100 (empty before a model is loaded). */
    val timings get() = model.timings

    /** "Last replies: first word after X s, Y tok/s" and what it was made of. */
    fun summary() = model.timings.summary()

    /** The text "Copy timings" copies: the model's load time, the CPU, the kernels, the threads, and the last 100 requests. */
    fun timingsReport(): String = model.report()

    /** Speed in Options: 0 Auto, 3 to 6 threads; saved, and applied to the next answer without a reload. */
    fun setSpeed(threads: Int) {
        setConfig(config.copy(threads = threads))
        model.setSpeed(threads, config.calibrationFor(specFor().id))
    }

    /** "Report slow replies": the slow requests with their phases, the model, the CPU's features and the token counts, as text to paste (numbers only). */
    fun slowReport(): String = model.slowReport()

    /** The thread plan in force, in words. */
    fun speedNote(): String = model.plan.note

    /** The CPU's features and cores, in words (the kernels expected, the performance cores). */
    fun cpuNote(): String = model.cpu.describe()

    /** Runs the 64-token Speed test; null when no model is loaded. Call from a coroutine (it takes seconds). */
    suspend fun speedTest(): com.logie.wifecraft.dialogue.SpeedResult? = if (started.get() && model.isLoaded) model.speedTest() else null

    /** The app leaves the foreground: the NPCs' places are written to disk. */
    fun saveState() { if (started.get() && model.isLoaded) Thread { model.saveState() }.start() }

    // ---- the download --------------------------------------------------------------------------------------------------

    /** The model the next download fetches (set by [startDownload]; null: the one in use). */
    @Volatile private var target: DialogueModelSpec? = null

    /** Downloads [spec] (the catalogue's, by default the one in use); one download at a time. */
    fun startDownload(spec: DialogueModelSpec? = null) {
        if (!started.get() || downloading) return
        target = spec
        ContextCompat.startForegroundService(app, Intent(app, ModelDownloadService::class.java))
    }

    /** The player's switch: [spec] is the model in use from now on (when its file is on the phone it is loaded, replacing the one loaded). */
    fun chooseModel(spec: DialogueModelSpec) {
        if (!started.get()) return
        setConfig(config.copy(modelId = spec.id, modelPicked = true))
        if (config.enabled && isInstalled(spec)) loadAsync()
    }

    fun cancelDownload() { haltDownload = true }

    /** Deletes the model's file (and a part of it) and its saved NPC places. */
    fun deleteModel(spec: DialogueModelSpec = specFor()) {
        if (spec.tier == ModelTier.CUSTOM) return
        Thread({
            if (model.spec?.id == spec.id) model.unload()
            val f = fileFor(spec)
            f.delete(); File(f.path + ".part").delete()
            File(app.filesDir, "dialogue/state/${spec.stateName}").deleteRecursively()
            refresh()
            // the other model is there: it takes over (the switch's fallback)
            if (config.enabled) loadAsync()
        }, "wc-dialogue-delete").start()
    }

    /** Runs on the download service's thread; returns when it ends. [note] gets the progress for the notification. */
    internal fun runDownload(note: (DialogueUiState) -> Unit): DialogueUiState {
        val cfg = config
        val spec = target ?: specFor(cfg)
        target = null
        val f = fileFor(spec)
        haltDownload = false
        downloading = true
        var s = _state.value.copy(spec = spec, phase = DialogueUiState.Phase.DOWNLOADING, haveBytes = downloader.have(f), totalBytes = spec.bytes, message = null, downloadingId = spec.id)
        _state.value = s
        try {
            if (!cfg.mobileData && !onUnmeteredNetwork()) {
                s = s.copy(phase = DialogueUiState.Phase.PARTIAL, message = "Waiting for Wi-Fi: turn on \"Also on mobile data\" to download now.")
                _state.value = s
                return s
            }
            val r = downloader.download(spec.url, f, spec.bytes, spec.sha256, cancelled = { haltDownload }) { ph, done, total ->
                s = s.copy(phase = if (ph == ModelDownloader.Phase.CHECKING) DialogueUiState.Phase.CHECKING else DialogueUiState.Phase.DOWNLOADING, haveBytes = done, totalBytes = if (total > 0) total else spec.bytes)
                _state.value = s
                note(s)
            }
            s = when (r) {
                is ModelDownloader.Result.Done -> s.copy(phase = DialogueUiState.Phase.READY, haveBytes = spec.bytes, message = "Downloaded and checked.")
                is ModelDownloader.Result.Cancelled -> s.copy(phase = DialogueUiState.Phase.PARTIAL, message = "Paused. What is downloaded is kept.")
                is ModelDownloader.Result.Failed -> s.copy(phase = DialogueUiState.Phase.FAILED, message = r.message)
            }
            _state.value = s
            return s
        } finally {
            downloading = false
            val finished = _state.value.phase == DialogueUiState.Phase.READY
            if (finished) settings.dialogueModel = config.copy(enabled = true)
            _state.value = _state.value.copy(downloadingId = null)
            refresh()
            if (finished) loadAsync()
        }
    }

    private fun onUnmeteredNetwork(): Boolean {
        val cm = app.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}
