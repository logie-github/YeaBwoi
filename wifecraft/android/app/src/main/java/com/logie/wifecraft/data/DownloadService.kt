package com.logie.wifecraft.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.logie.wifecraft.audio.SoundIndex
import com.logie.wifecraft.characters.NamedCast
import com.logie.wifecraft.creator.Catalog
import com.logie.wifecraft.creator.CsvTables
import com.logie.wifecraft.creator.GameTables
import com.logie.wifecraft.creator.ImportStore
import com.logie.wifecraft.creator.M2Files
import com.logie.wifecraft.creator.Prefetch
import com.logie.wifecraft.creator.TransmogCatalog
import com.logie.wifecraft.engine.PropLoader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** What Options' Downloads page and the notification show of "Download everything" (DownloadHub.snapshot). */
data class DownloadSnapshot(
    val state: SavedState = SavedState(),
    /** The pack being fetched or checked: its place among the run's packs, and its entries done of those known so far. */
    val packNumber: Int = 0,
    val packCount: Int = 0,
    val done: Int = 0,
    val total: Int = 0,
    /** Files fetched in this run, and their bytes. */
    val files: Int = 0,
    val bytes: Long = 0,
    val estimates: Map<PackKey, PackEstimate> = emptyMap(),
    val version: String = DownloadPlan.version(GameTables.BUILD),
    /** Bytes free where the game's files go. */
    val free: Long = 0,
) {
    val phase: Phase get() = state.phase
    val running: Boolean get() = DownloadMachine.isActive(state.phase)
    /** The whole run's progress, 0..1. */
    val fraction: Float get() = if (packCount == 0) 0f else ((packNumber - 1).coerceAtLeast(0) + (if (total == 0) 0f else done.toFloat() / total)) / packCount
}

/**
 * Options' "Download everything": the packs (PackKey, AssetDownloads) fetched by [DownloadService], a foreground service,
 * so it goes on with the app in the background or the screen off; then every file checked ("Verify") and the damaged ones
 * fetched again. Its state ([snapshot]) is the process's, so the Options page, the notification and a rotated or folded
 * activity all see the same; it is kept in files/cache/download-state.txt (SavedState) and taken up again when the app
 * is opened after it was stopped ([resumeIfInterrupted]). Every file goes through GameFiles (its limiter: up to the
 * player's download speed at once, less when wago.tools asks).
 */
object DownloadHub {
    private val _snapshot = MutableStateFlow(DownloadSnapshot())
    val snapshot: StateFlow<DownloadSnapshot> = _snapshot

    private val lock = Any()
    @Volatile private var app: Context? = null
    @Volatile private var runner: Runner? = null
    private val failed = FailedQueue()
    /** Told when a run ends (the service stops itself). */
    @Volatile internal var onRunEnded: (() -> Unit)? = null

    private fun stateFile(c: Context) = File(c.filesDir, "cache/download-state.txt")
    internal fun idsFile(c: Context, pack: PackKey) = File(c.filesDir, "cache/packs/${pack.id}.ids")

    /** Reads the kept state and the estimates once per process. Any thread. */
    fun init(context: Context) {
        if (app != null) return
        synchronized(lock) {
            if (app != null) return
            val c = context.applicationContext
            val saved = SavedState.decode(runCatching { stateFile(c).readText() }.getOrNull())
            failed.restore(saved.failed)
            _snapshot.value = DownloadSnapshot(saved, estimates = readEstimates(c), free = freeBytes(c))
            app = c
        }
    }

    private fun readEstimates(c: Context): Map<PackKey, PackEstimate> = runCatching {
        val o = JSONObject(c.assets.open("files.json").bufferedReader().use { it.readText() }).optJSONObject("estimates") ?: return emptyMap()
        val raw = HashMap<String, Pair<Int, Long>>()
        for (k in o.keys()) o.optJSONObject(k)?.let { raw[k] = it.optInt("files") to it.optLong("bytes") }
        DownloadPlan.estimates(raw)
    }.getOrDefault(emptyMap())

    fun freeBytes(c: Context): Long = runCatching { File(c.filesDir, "game").apply { mkdirs() }.usableSpace }.getOrDefault(0L)

    /** The basics' weight is GameFiles' own count (exact once its files are here). */
    fun estimates(c: Context): Map<PackKey, PackEstimate> {
        val e = _snapshot.value.estimates.toMutableMap()
        val files = GameFiles.shared(c)
        val p = files.progress.value
        e[PackKey.BASICS] = PackEstimate(p.filesTotal.takeIf { it > 0 } ?: (e[PackKey.BASICS]?.files ?: 0), files.totalBytes, p.allDone && !p.estimated)
        return e
    }

    // ---- the page's choices --------------------------------------------------------------------------------------

    fun setChosen(chosen: Set<PackKey>) = change { it.copy(chosen = chosen) }
    fun setMobileData(on: Boolean) = change { it.copy(mobileData = on) }
    fun setPrebuild(on: Boolean) = change { it.copy(prebuild = on) }

    /** Updates the free space shown (the page asks when it opens). */
    fun refreshFree(c: Context) { _snapshot.value = _snapshot.value.copy(free = freeBytes(c), estimates = estimates(c)) }

    /** Whether [packs] fit (each one's bytes still to come against the free space). */
    fun check(c: Context, packs: Collection<PackKey>): SpaceCheck.Result {
        val s = _snapshot.value
        val est = estimates(c)
        val needs = packs.associateWith { DownloadPlan.remaining(est[it], s.state.records[it], s.version) }
        return SpaceCheck.check(freeBytes(c), needs)
    }

    // ---- commands (main thread: they change the state and start or stop the service) ------------------------------

    /** Starts fetching [packs] (null: the ticked ones, every required pack included). */
    fun start(c: Context, packs: Set<PackKey>? = null) {
        init(c)
        change { s -> s.copy(phase = DownloadMachine.next(s.phase, DownloadEvent.START), message = null, stoppedBySystem = false, only = packs) }
        launchService(c)
    }

    /** A run is going in this process. */
    fun isRunning(): Boolean = runner?.isAlive == true

    fun pause() {
        change { s -> s.copy(phase = DownloadMachine.next(s.phase, DownloadEvent.PAUSE), message = "Paused. Files already here are kept.") }
        stopRunner()
    }

    fun resume(c: Context) {
        init(c)
        change { s -> s.copy(phase = DownloadMachine.next(s.phase, DownloadEvent.RESUME), message = null, stoppedBySystem = false) }
        if (DownloadMachine.isActive(_snapshot.value.phase)) launchService(c)
    }

    fun cancel() {
        change { s -> s.copy(phase = DownloadMachine.next(s.phase, DownloadEvent.CANCEL), current = null, message = "Stopped. Files already here are kept.", only = null) }
        stopRunner()
    }

    /** Every failed file tried again now. */
    fun retryFailed(c: Context) {
        failed.retryAll()
        change { s -> s.copy(failed = failed.list()) }
        start(c, _snapshot.value.state.only)
    }

    /** Checks every file of the ticked packs and fetches the damaged ones again. */
    fun verify(c: Context) {
        init(c)
        change { s -> s.copy(phase = DownloadMachine.next(s.phase, DownloadEvent.VERIFY), message = null) }
        launchService(c)
    }

    /**
     * When the app comes to the front: a run the system stopped (the app was closed, or Android's time limit for a
     * background download) is taken up again.
     */
    fun resumeIfInterrupted(c: Context) {
        init(c)
        val s = _snapshot.value.state
        if (runner == null && (DownloadMachine.resumesAfterRestart(s.phase) || (s.phase == Phase.PAUSED && s.stoppedBySystem))) {
            change { it.copy(phase = if (it.phase == Phase.PAUSED) Phase.DOWNLOADING else it.phase, stoppedBySystem = false, message = null) }
            launchService(c)
        }
    }

    /** The service's time limit (Android 15: six hours of dataSync a day) is up: paused, to go on when the app is opened. */
    internal fun pauseBySystem() {
        change { s ->
            s.copy(phase = DownloadMachine.next(s.phase, DownloadEvent.PAUSE), stoppedBySystem = true,
                message = "Android pauses long downloads in the background. It carries on when you open Wifecraft.")
        }
        stopRunner()
    }

    private fun launchService(c: Context) {
        ContextCompat.startForegroundService(c, Intent(c, DownloadService::class.java))
    }

    /** The service is up: the run starts unless one is going. */
    internal fun attach(c: Context) {
        init(c)
        synchronized(lock) {
            if (runner?.isAlive == true) return
            if (!DownloadMachine.isActive(_snapshot.value.phase)) { onRunEnded?.invoke(); return }
            runner = Runner(c.applicationContext).also { it.start() }
        }
    }

    private fun stopRunner() {
        runner?.halt()
    }

    // ---- state -------------------------------------------------------------------------------------------------------

    private fun change(f: (SavedState) -> SavedState) {
        synchronized(lock) {
            val s = _snapshot.value
            _snapshot.value = s.copy(state = f(s.state))
        }
        save()
    }

    private fun event(e: DownloadEvent, message: String? = null) =
        change { s -> s.copy(phase = DownloadMachine.next(s.phase, e), message = message ?: s.message) }

    private fun save() {
        val c = app ?: return
        val text = _snapshot.value.state.copy(failed = failed.list()).encode()
        runCatching { writeAtomic(stateFile(c), text) }
    }

    internal fun writeAtomic(f: File, text: String) {
        f.parentFile?.mkdirs()
        val part = File(f.path + ".part")
        part.writeText(text)
        if (!part.renameTo(f)) { f.delete(); part.renameTo(f) }
    }

    // ---- the run -------------------------------------------------------------------------------------------------------

    private class Runner(private val c: Context) : Thread("download-everything") {
        private val stop = AtomicBoolean(false)
        private val files = GameFiles.shared(c)
        private val tables = CsvTables(files.tablesDir)
        private val settings = Settings(c)
        private val version = _snapshot.value.version
        private val pool = Executors.newFixedThreadPool(GameFiles.MAX_PARALLEL) { r -> Thread(r, "download-everything-file").apply { isDaemon = true } }
        private val done = AtomicInteger(0)
        private val total = AtomicInteger(0)
        private val fetched = AtomicInteger(0)
        private val bytes = AtomicLong(0)
        @Volatile private var packNumber = 0
        @Volatile private var packCount = 0
        @Volatile private var collected = ConcurrentHashMap<Int, Long>()
        @Volatile private var wifiCheckedAt = 0L
        @Volatile private var wifiOk = true
        private val ticker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "download-everything-tick").apply { isDaemon = true } }
        private var wake: PowerManager.WakeLock? = null

        fun halt() {
            stop.set(true)
            files.pause()   // the basics' gathering, if it is the pack being fetched
        }

        override fun run() {
            wake = runCatching {
                (c.getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wifecraft:downloads")
                    .apply { setReferenceCounted(false); acquire(WAKE_MS) }
            }.getOrNull()
            ticker.scheduleWithFixedDelay({ publish() }, 0, TICK_MS, TimeUnit.MILLISECONDS)
            try {
                if (_snapshot.value.phase == Phase.VERIFYING) verifyAll() else downloadAll()
            } catch (e: Throwable) {
                if (!stop.get()) event(DownloadEvent.ERROR, "The download stopped: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                ticker.shutdownNow()
                pool.shutdownNow()
                publish()
                change { it.copy(current = null) }
                runCatching { wake?.release() }
                // resumed while this run was still stopping: the next one starts now
                val again = synchronized(lock) {
                    if (runner === this) runner = null
                    stop.get() && DownloadMachine.isActive(_snapshot.value.phase)
                }
                if (again) attach(c) else onRunEnded?.invoke()
            }
        }

        private fun publish() {
            val failures = failed.list()
            val shown = synchronized(lock) {
                val s = _snapshot.value
                s.copy(
                    state = s.state.copy(failed = failures), packNumber = packNumber, packCount = packCount,
                    done = done.get(), total = total.get(), files = fetched.get(), bytes = bytes.get(),
                ).also { _snapshot.value = it }
            }
            DownloadService.notifyProgress(c, shown)
            // the failures and the progress kept now and then (a restart carries on from them)
            val now = System.currentTimeMillis()
            if (now - savedAt > SAVE_MS) { savedAt = now; save() }
        }
        @Volatile private var savedAt = 0L

        private fun downloadAll() {
            val chosen = _snapshot.value.state.packs
            packCount = chosen.size
            for ((i, pack) in chosen.withIndex()) {
                if (stop.get()) return
                packNumber = i + 1
                val record = _snapshot.value.state.records[pack]
                if (record != null && record.version == version && record.complete && pack !in failedPacks()) continue
                if (files.dir.usableSpace < SpaceCheck.RESERVE_BYTES) { outOfRoom(); return }
                change { it.copy(current = pack) }
                runPack(pack)
            }
            retryRounds()
            if (stop.get()) return
            if (!failed.isEmpty()) {
                event(DownloadEvent.FINISHED_WITH_FAILURES, "${ByteSize.count(failed.size)} files couldn't be downloaded. Retry failed tries them again.")
                return
            }
            event(DownloadEvent.FINISHED)
            verifyAll()
        }

        private fun failedPacks(): Set<PackKey> = failed.list().mapNotNull { it.pack }.toSet()

        /** The failed files tried again as each one's wait is up, while some are left to try. */
        private fun retryRounds() {
            while (!stop.get()) {
                val wait = failed.nextDueIn() ?: return
                if (wait > 0) { sleepUnlessStopped(wait.coerceAtMost(5_000)); continue }
                val dueIds = failed.due().toHashSet()
                val due = failed.list().filter { it.id in dueIds }
                if (due.isEmpty()) return
                total.set(due.size); done.set(0)
                for (batch in due.chunked(BATCH)) {
                    if (stop.get()) return
                    val tasks = batch.map { e ->
                        pool.submit { get(e.id, e.pack, art = if (e.pack == PackKey.ART) artFiles[e.id] else null); done.incrementAndGet() }
                    }
                    waitAll(tasks)
                }
            }
        }

        private val planner by lazy {
            AssetDownloads(c, tables, NamedCast.parse(asset("characters.json"), asset(com.logie.wifecraft.bodyparts.ModelParts.ASSET)))
        }
        private val artFiles by lazy { planner.art().associateBy { it.id } }

        /** Model files read at once while their needs are listed (a character's model is some 15 MB). */
        private fun gateFor(pack: PackKey) = java.util.concurrent.Semaphore(when (pack) {
            PackKey.ANIMATIONS, PackKey.NAMED -> 4
            PackKey.CREATURES, PackKey.PLACES -> 12
            else -> 48
        })
        @Volatile private var gate = java.util.concurrent.Semaphore(48)

        private fun asset(name: String): String? = runCatching { c.assets.open(name).bufferedReader().use { it.readText() } }.getOrNull()

        private fun runPack(pack: PackKey) {
            collected = ConcurrentHashMap()
            done.set(0); total.set(0)
            when (pack) {
                PackKey.BASICS -> basics()
                PackKey.ART -> art()
                else -> {
                    ensureTables()
                    if (stop.get()) return
                    val work = planner.plan(pack) {
                        GameTables.fetch(files.tablesDir, names = TransmogCatalog.TABLES)
                        TransmogCatalog.load(tables)
                    }
                    gate = gateFor(pack)
                    total.set(work.direct.size + work.models.size)
                    for (batch in work.direct.chunked(BATCH)) {
                        if (stop.get()) break
                        waitAll(batch.map { id -> pool.submit { get(id, pack); done.incrementAndGet() } })
                    }
                    for (batch in work.models.chunked(BATCH)) {
                        if (stop.get()) break
                        waitAll(batch.map { id -> pool.submit { model(id, pack, work.closure); done.incrementAndGet() } })
                    }
                    record(pack, work.sizes)
                }
            }
        }

        /** The game's tables, if the basics didn't bring them (a pack fetched on its own). */
        private fun ensureTables() {
            if (files.tablesComplete) return
            awaitNetwork()
            GameTables.fetch(files.tablesDir, stop = { stop.get() })
        }

        private fun basics() {
            if (files.planner == null) files.planner = { Prefetch.plan(Catalog.build(tables), tables) }
            if (!awaitNetwork()) return
            total.set(1)
            files.gatherBlocking()
            val p = files.progress.value
            for (id in p.failed) failed.failed(id, PackKey.BASICS, p.error ?: "wago.tools didn't give it")
            if (p.error != null && !stop.get()) change { it.copy(message = p.error) }
            val list = files.basicsFiles()
            for ((id, _) in list) collected[id] = files.file(id).length().coerceAtLeast(0)
            done.set(1)
            record(PackKey.BASICS, list.toMap(), complete = p.allDone)
        }

        private fun art() {
            val list = artFiles.values.toList()
            total.set(list.size)
            for (batch in list.chunked(BATCH)) {
                if (stop.get()) break
                waitAll(batch.map { a -> pool.submit { get(a.id, PackKey.ART, art = a); done.incrementAndGet() } })
            }
            record(PackKey.ART, emptyMap())
        }

        /** A model and what it needs, by [closure]. */
        private fun model(id: Int, pack: PackKey, closure: AssetDownloads.Closure) {
            if (stop.get()) return
            val fetch = { ids: Collection<Int> -> for (x in ids) { if (stop.get()) break; get(x, pack) } }
            // the model itself at full speed; reading it for its needs a few at a time
            get(id, pack)
            if (closure == AssetDownloads.Closure.NONE || !files.has(id)) return
            gate.acquire()
            try {
                when (closure) {
                    AssetDownloads.Closure.MODEL, AssetDownloads.Closure.MODEL_ALL_ANIMATIONS -> {
                        val anims = if (closure == AssetDownloads.Closure.MODEL_ALL_ANIMATIONS) null else emptySet<Int>()
                        M2Files.closure(id, anims, { x -> if (files.has(x)) files.file(x).readBytes() else null }, fetch)
                    }
                    else -> {
                        val why = runBlocking { PropLoader.closure(files, id) { fetch(it) } }
                        if (why == PropLoader.UNREAD) failed.failed(id, pack, "file $id came but couldn't be read", permanent = true)
                    }
                }
            } catch (e: Exception) {
                if (!stop.get()) failed.failed(id, pack, "file $id: ${e.message ?: e.javaClass.simpleName}", permanent = true)
            } finally {
                gate.release()
            }
        }

        /** One file: here already, or fetched (waiting for Wi-Fi when the player asked for it); a failure goes to the queue. */
        private fun get(id: Int, pack: PackKey?, art: AssetDownloads.ArtFile? = null) {
            if (id !in 1 until ImportStore.FIRST_ID || stop.get()) return
            val target = art?.target ?: files.file(id)
            val have = target.length()
            if (have > 0) { collected[id] = have; failed.succeeded(id); return }
            collected.putIfAbsent(id, 0L)
            if (!awaitNetwork()) return
            try {
                if (art != null) files.fetchTo(id, art.build, art.target, stop) else files.fetchUnless(id, stop)
                val len = target.length()
                if (len > 0) {
                    collected[id] = len
                    bytes.addAndGet(len)
                    if (fetched.incrementAndGet() % SPACE_EVERY == 0 && files.dir.usableSpace < SpaceCheck.RESERVE_BYTES) outOfRoom()
                    failed.succeeded(id)
                } else if (!stop.get()) {
                    failed.failed(id, pack, "file $id didn't come")
                }
            } catch (e: GameFiles.NoSuchFile) {
                failed.failed(id, pack, e.message ?: "wago.tools has no file $id", permanent = true)
            } catch (e: Exception) {
                if (!stop.get()) failed.failed(id, pack, e.message ?: e.javaClass.simpleName)
            }
        }

        private fun outOfRoom() {
            if (stop.compareAndSet(false, true)) {
                event(DownloadEvent.ERROR, "The phone is nearly full (${ByteSize.format(SpaceCheck.RESERVE_BYTES)} is kept free). Free some space, then Resume.")
                files.pause()
            }
        }

        /** True once files may be fetched on this connection; waits (saying so) while only Wi-Fi is allowed and there is none. */
        private fun awaitNetwork(): Boolean {
            var waiting = false
            while (!stop.get()) {
                if (networkAllowed()) {
                    if (waiting) event(DownloadEvent.WIFI_BACK)
                    return true
                }
                if (!waiting) { waiting = true; event(DownloadEvent.NO_WIFI) }
                sleepUnlessStopped(2_000)
            }
            return false
        }

        private fun networkAllowed(): Boolean {
            val s = _snapshot.value.state
            if (s.mobileData || !settings.wifiOnly) return true
            val now = System.currentTimeMillis()
            if (now - wifiCheckedAt > 2_000) { wifiOk = files.onWifi(); wifiCheckedAt = now }
            return wifiOk
        }

        private fun sleepUnlessStopped(ms: Long) {
            val end = System.currentTimeMillis() + ms
            while (!stop.get() && System.currentTimeMillis() < end) Thread.sleep(minOf(200L, end - System.currentTimeMillis()).coerceAtLeast(1))
        }

        private fun waitAll(tasks: List<Future<*>>) {
            for (t in tasks) runCatching { t.get() }
        }

        /**
         * The pack's record and its files' list (for Verify) from what this run touched; [sizes]: the lengths the asset
         * lists give (the places'), checked against instead of the length on the phone.
         */
        private fun record(pack: PackKey, sizes: Map<Int, Long>, complete: Boolean = !stop.get()) {
            val list = LinkedHashMap<Int, Long>()
            for ((id, len) in collected) list[id] = sizes[id]?.takeIf { it > 0 } ?: len
            runCatching { writeAtomic(idsFile(c, pack), PackFiles.encode(list)) }
            val have = collected.values.count { it > 0 }
            val planned = if (complete) list.size else maxOf(list.size, _snapshot.value.estimates[pack]?.files ?: 0)
            val rec = PackRecord(pack, version, planned, have, collected.values.sum())
            change { s -> s.copy(records = s.records + (pack to rec)) }
        }

        /** Every file of the ticked packs checked; the damaged and missing ones fetched again. */
        private fun verifyAll() {
            val packs = _snapshot.value.state.packs
            packCount = packs.size
            var bad = 0
            val notHere = ArrayList<PackKey>()
            for ((i, pack) in packs.withIndex()) {
                if (stop.get()) return
                packNumber = i + 1
                change { it.copy(current = pack) }
                val list: Map<Int, Long> = when (pack) {
                    PackKey.ART -> artFiles.keys.associateWith { 0L }
                    else -> runCatching { PackFiles.decode(idsFile(c, pack).readText()) }.getOrDefault(LinkedHashMap())
                }
                val art = if (pack == PackKey.ART) artFiles else emptyMap()
                val length = { id: Int -> (art[id]?.target ?: files.file(id)).length() }
                total.set(list.size); done.set(0)
                val damaged = ArrayList<Int>()
                for ((id, exp) in list) {
                    if (stop.get()) return
                    if (!PackFiles.whole(length(id), exp)) damaged += id
                    done.incrementAndGet()
                }
                bad += damaged.size
                if (list.isEmpty()) notHere += pack
                collected = ConcurrentHashMap(list.mapValues { (id, _) -> length(id).coerceAtLeast(0) })
                if (damaged.isNotEmpty()) {
                    total.set(damaged.size); done.set(0)
                    for (batch in damaged.chunked(BATCH)) {
                        if (stop.get()) return
                        waitAll(batch.map { id ->
                            pool.submit {
                                (art[id]?.target ?: files.file(id)).delete()
                                get(id, pack, art[id])
                                done.incrementAndGet()
                            }
                        })
                    }
                }
                val have = collected.values.count { it > 0 }
                val rec = PackRecord(pack, version, list.size, have, collected.values.sum(), verified = have >= list.size)
                change { s -> s.copy(records = s.records + (pack to rec)) }
            }
            if (stop.get()) return
            val all = _snapshot.value.state.records.filterKeys { it in packs }.values
            val n = all.sumOf { it.have }
            val b = all.sumOf { it.bytes }
            if (notHere.isNotEmpty()) {
                event(DownloadEvent.VERIFIED_WITH_FAILURES, "Not downloaded yet: ${notHere.joinToString(", ") { it.label }}. Download everything fetches them.")
            } else if (failed.isEmpty()) {
                event(DownloadEvent.VERIFIED, "Everything is here (${ByteSize.count(n)} files, ${ByteSize.format(b)})" +
                    if (bad > 0) ". ${ByteSize.count(bad)} damaged files were fetched again." else ".")
                if (_snapshot.value.state.prebuild) prebuild()
            } else {
                event(DownloadEvent.VERIFIED_WITH_FAILURES, "${ByteSize.count(failed.size)} files are still missing. Retry failed tries them again.")
            }
        }

        /**
         * Options' "Pre-build caches" (off by default): what is slow the first time it is wanted and needs no GPU. For now
         * the scene sounds' index (audio/SoundIndex: SoundKitEntry, 37 MB of table, streamed into about 1 MB), made with
         * the same key audio/SceneAudio reads it by (keep in step with SceneAudio.SOUND_TABLES).
         */
        private fun prebuild() {
            runCatching {
                val key = GameTables.BUILD + SOUND_TABLES.joinToString("-", "-") { File(files.tablesDir, "$it.csv").length().toString(36) }
                SoundIndex.load(File(c.filesDir, "cache/sound-index.bin"), key, tables) { GameTables.download("SoundKitEntry") }
            }
        }
    }

    private const val BATCH = 2_000
    private const val TICK_MS = 500L
    private const val SPACE_EVERY = 500
    private const val SAVE_MS = 5_000L
    private const val WAKE_MS = 6 * 60 * 60 * 1000L
    private val SOUND_TABLES = listOf("AreaTable", "SoundAmbience", "SoundAmbienceFlavor", "ZoneMusic", "ZoneIntroMusicTable",
        "CreatureSoundData", "NPCSounds", "GameObjectDisplayInfo", "ObjectEffectPackageElem", "ObjectEffect")
}

/**
 * The foreground service (type dataSync) that runs "Download everything" (DownloadHub) with an ongoing notification:
 * the pack, the progress, Pause and Cancel. It stops itself when the run ends, leaving a notification that says how it
 * ended. On Android 15 a dataSync service may run six hours a day: at the limit ([onTimeout]) the download pauses and
 * carries on when the app is opened.
 */
class DownloadService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // every start through startForegroundService must go foreground at once, whatever it asks
        goForeground(DownloadHub.snapshot.value)
        DownloadHub.onRunEnded = { finish() }
        val action = intent?.action
        when (action) {
            ACTION_PAUSE, ACTION_CANCEL -> {
                DownloadHub.init(this)
                if (action == ACTION_PAUSE) DownloadHub.pause() else DownloadHub.cancel()
                // the run ends by itself and stops the service; with none going (an old notification), at once
                if (!DownloadHub.isRunning()) finish()
                return START_NOT_STICKY
            }
            ACTION_RESUME -> DownloadHub.resume(this)
        }
        DownloadHub.attach(this)
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        DownloadHub.pauseBySystem()
        finish()
    }

    override fun onDestroy() {
        DownloadHub.onRunEnded = null
        super.onDestroy()
    }

    private fun finish() {
        val s = DownloadHub.snapshot.value
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        endNotice(this, s)
        stopSelf()
    }

    private fun goForeground(s: DownloadSnapshot) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        runCatching { ServiceCompat.startForeground(this, NOTE_PROGRESS, progressNote(this, s), type) }
    }

    companion object {
        const val ACTION_PAUSE = "com.logie.wifecraft.download.PAUSE"
        const val ACTION_CANCEL = "com.logie.wifecraft.download.CANCEL"
        const val ACTION_RESUME = "com.logie.wifecraft.download.RESUME"
        private const val CHANNEL = "downloads"
        private const val NOTE_PROGRESS = 4100
        private const val NOTE_DONE = 4101
        @Volatile private var lastNote = 0L

        private fun channel(c: Context): NotificationManager {
            val nm = c.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "The game's files being downloaded"
                    setShowBadge(false)
                })
            }
            return nm
        }

        private fun openApp(c: Context): PendingIntent? =
            c.packageManager.getLaunchIntentForPackage(c.packageName)?.let {
                PendingIntent.getActivity(c, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            }

        private fun action(c: Context, what: String, code: Int): PendingIntent {
            val i = Intent(c, DownloadService::class.java).setAction(what)
            return if (what == ACTION_RESUME) PendingIntent.getForegroundService(c, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            else PendingIntent.getService(c, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        private fun progressNote(c: Context, s: DownloadSnapshot): Notification {
            channel(c)
            val pack = s.state.current?.label ?: "Game files"
            val title = when (s.phase) {
                Phase.WAITING_FOR_WIFI -> "Waiting for Wi-Fi"
                Phase.VERIFYING -> "Checking the files: $pack"
                else -> "Downloading: $pack"
            }
            val line = "${ByteSize.count(s.files)} files (${ByteSize.format(s.bytes)})" +
                (if (s.packCount > 0) " · pack ${s.packNumber} of ${s.packCount}" else "") +
                (if (s.state.failed.isNotEmpty()) " · ${ByteSize.count(s.state.failed.size)} failed" else "")
            val pct = (s.fraction * 1000).toInt().coerceIn(0, 1000)
            return NotificationCompat.Builder(c, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText(line)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setProgress(1000, pct, s.total == 0 && s.running)
                .setContentIntent(openApp(c))
                .addAction(0, "Pause", action(c, ACTION_PAUSE, 1))
                .addAction(0, "Cancel", action(c, ACTION_CANCEL, 2))
                .build()
        }

        /** The ongoing notification brought up to date, at most once a second, while the service runs. */
        internal fun notifyProgress(c: Context, s: DownloadSnapshot) {
            if (!s.running) return
            val now = System.currentTimeMillis()
            if (now - lastNote < 1_000) return
            lastNote = now
            runCatching { channel(c).notify(NOTE_PROGRESS, progressNote(c, s)) }
        }

        /** How the run ended: complete, paused (with Resume), failed or stopped. */
        private fun endNotice(c: Context, s: DownloadSnapshot) {
            val title = when (s.phase) {
                Phase.COMPLETE -> "Download complete"
                Phase.PAUSED -> "Download paused"
                Phase.FAILED -> "Download incomplete"
                else -> return
            }
            val b = NotificationCompat.Builder(c, CHANNEL)
                .setSmallIcon(if (s.phase == Phase.COMPLETE) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_sys_warning)
                .setContentTitle(title)
                .setContentText(s.state.message ?: "")
                .setStyle(NotificationCompat.BigTextStyle().bigText(s.state.message ?: ""))
                .setAutoCancel(true)
                .setContentIntent(openApp(c))
            if (s.phase == Phase.PAUSED && !s.state.stoppedBySystem) b.addAction(0, "Resume", action(c, ACTION_RESUME, 3))
            runCatching { channel(c).notify(NOTE_DONE, b.build()) }
        }
    }
}
