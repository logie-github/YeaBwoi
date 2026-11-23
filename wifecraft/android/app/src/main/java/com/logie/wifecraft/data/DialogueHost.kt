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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** What Options → Mods → Dialogue model shows (DialogueHost.state). */
data class DialogueUiState(
    val spec: DialogueModelSpec = DialogueModels.FULL,
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
        (if (cfg.modelId?.startsWith("custom:") == true && cfg.customPath != null) DialogueModels.custom(cfg.customPath, bytes = File(cfg.customPath).length()) else DialogueModels.byId(cfg.modelId))
            ?: DialogueModels.FULL

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
        _state.value = DialogueUiState(spec, phase, if (phase == DialogueUiState.Phase.READY || phase == DialogueUiState.Phase.LOADED) spec.bytes else have, spec.bytes,
            if (prev.phase == DialogueUiState.Phase.FAILED && phase == prev.phase) prev.message else null, mem.totalMem, mem.availMem, oldModelBytes())
    }

    private fun modelsDir(): File = File(app.filesDir, "dialogue/models")

    /** Size of the files of the models the catalogue used to hold (never loaded: only [DialogueModels.FULL] is). */
    fun oldModelBytes(): Long = DialogueModels.staleFiles(modelsDir()).sumOf { it.length() }

    /** Deletes the old models' files (and their saved NPC places, which belong to a model that is gone) to free the space. */
    fun deleteOldModels() {
        if (!started.get()) return
        Thread({
            DialogueModels.staleFiles(modelsDir()).forEach { it.delete() }
            File(app.filesDir, "dialogue/state").listFiles()?.filter { it.name != DialogueModels.FULL.id.replace(Regex("[^A-Za-z0-9._-]"), "_") }?.forEach { it.deleteRecursively() }
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
                val err = model.load(spec, f, cfg.resolveThreads(Runtime.getRuntime().availableProcessors()), cfg.context, cfg.slots, cfg.ambientTokensPerMinute)
                if (err != null) failLoad(err) else { _state.value = _state.value.copy(loadProgress = -1f); refresh() }
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

    /** The app leaves the foreground: the NPCs' places are written to disk. */
    fun saveState() { if (started.get() && model.isLoaded) Thread { model.saveState() }.start() }

    // ---- the download --------------------------------------------------------------------------------------------------

    fun startDownload() {
        if (!started.get() || downloading) return
        ContextCompat.startForegroundService(app, Intent(app, ModelDownloadService::class.java))
    }

    fun cancelDownload() { haltDownload = true }

    /** Deletes the model's file (and a part of it) and its saved NPC places. */
    fun deleteModel() {
        val spec = specFor()
        if (spec.tier == ModelTier.CUSTOM) return
        Thread({
            model.unload()
            val f = fileFor(spec)
            f.delete(); File(f.path + ".part").delete()
            File(app.filesDir, "dialogue/state").deleteRecursively()
            refresh()
        }, "wc-dialogue-delete").start()
    }

    /** Runs on the download service's thread; returns when it ends. [note] gets the progress for the notification. */
    internal fun runDownload(note: (DialogueUiState) -> Unit): DialogueUiState {
        val cfg = config
        val spec = specFor(cfg)
        val f = fileFor(spec)
        haltDownload = false
        downloading = true
        var s = _state.value.copy(spec = spec, phase = DialogueUiState.Phase.DOWNLOADING, haveBytes = downloader.have(f), totalBytes = spec.bytes, message = null)
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
            if (_state.value.phase == DialogueUiState.Phase.READY) { settings.dialogueModel = config.copy(enabled = true); loadAsync() }
        }
    }

    private fun onUnmeteredNetwork(): Boolean {
        val cm = app.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}
