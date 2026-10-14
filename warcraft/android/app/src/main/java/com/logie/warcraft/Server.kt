// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.logie.warcraft.config.ConfFile
import com.logie.warcraft.logs.ConsoleText
import com.logie.warcraft.logs.LineWatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.Writer
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * The private server on the phone: MariaDB, the auth server (port 3724, where the game logs in)
 * and a realm's world server (ChromieCraft on port 8085, or Wrath Remix on 8086: [Realms]), each a
 * program from `files/server` run through its `ctl.sh`. [start] brings them up in order, setting up
 * what's missing on the way (unpacking the server, the databases); [stop] shuts them down cleanly.
 * [ServerService] keeps the app alive while they run, with its notification.
 */
object Server {
    enum class Phase { STOPPED, STARTING, RUNNING, STOPPING, FAILED }

    enum class Step { UNPACKING, CONFIGURING, DATABASE, IMPORT, RACES, MODULES, AUTH, WORLD }

    enum class World {
        /** Not started (the server is stopped, or still on an earlier step). */
        OFF,

        /** Waiting for the server's data, which comes from the client (Game files). */
        NO_DATA,
        STARTING,
        UP,
    }

    data class Status(
        val phase: Phase = Phase.STOPPED,
        val step: Step? = null,
        /** How far the step is, when that's known. */
        val progress: Float? = null,
        /** The step's latest line of output. */
        val detail: String? = null,
        val world: World = World.OFF,
        /** Why it failed, and the end of the log that says more. */
        val error: String? = null,
        val log: String? = null,
        /** The phone's address on the Wi-Fi, for a PC's client (`set realmlist ...`). */
        val lanAddress: String? = null,
        /** The realm being started or running (the one chosen when it started). */
        val realm: Realms.Realm = Realms.Realm.CHROMIECRAFT,
    )

    const val AUTH_PORT = 3724

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var job: Job? = null

    private var db: Process? = null
    private var auth: Process? = null
    private var world: Process? = null
    private var worldInput: Writer? = null

    /** A program run to its end as a step (dbimport), so stopping can end it too. */
    @Volatile private var stepProcess: Process? = null

    /** The programs are being stopped on purpose: their ending isn't a failure. */
    @Volatile private var stopping = false

    /** Starts the server, and the service that keeps it running. */
    fun start(context: Context) {
        val app = context.applicationContext
        if (_status.value.phase == Phase.STARTING || _status.value.phase == Phase.RUNNING) return
        // Starting already, so the service doesn't see the server stopped and end itself.
        update { Status(phase = Phase.STARTING, lanAddress = lanAddress, realm = Realms.current(app)) }
        ContextCompat.startForegroundService(app, Intent(app, ServerService::class.java))
        job = scope.launch { lock.withLock { run(app) } }
    }

    /** Stops the server. [then] runs once it's down (on a background thread). */
    fun stop(context: Context, then: (() -> Unit)? = null) {
        val app = context.applicationContext
        val running = job
        scope.launch {
            running?.cancel()
            lock.withLock { shutDown(app) }
            then?.invoke()
        }
    }

    /** Stops the server if it runs and waits for it to be down. Background thread. */
    suspend fun stopAndWait(context: Context) {
        val app = context.applicationContext
        job?.cancel()
        lock.withLock { shutDown(app) }
    }

    /** Sends a command to the world server's console (`.reload config` and the like, without the dot). */
    fun worldCommand(command: String): Boolean {
        val input = worldInput ?: return false
        return runCatching {
            input.write(command + "\n")
            input.flush()
        }.isSuccess
    }

    val databaseUp: Boolean get() = db?.isAlive == true

    private fun update(change: Status.() -> Status) {
        _status.value = _status.value.change()
    }

    private fun step(step: Step, detail: String? = null, progress: Float? = null) =
        update { copy(phase = Phase.STARTING, step = step, detail = detail, progress = progress, error = null, log = null) }

    private class StepFailed(message: String, val log: File? = null) : Exception(message)

    private suspend fun run(context: Context) {
        val realm = Realms.current(context)
        try {
            stopping = false
            update { Status(phase = Phase.STARTING, lanAddress = lanAddress(), realm = realm) }
            if (!WowFiles.isStandardPath(context)) {
                throw StepFailed(context.getString(R.string.wow_server_error_path, WowFiles.root(context).path))
            }

            if (!ServerPackage.isInstalled(context)) {
                step(Step.UNPACKING, progress = 0f)
                runCatching { ServerPackage.install(context) { step(Step.UNPACKING, progress = it) } }
                    .onFailure { throw StepFailed(context.getString(R.string.wow_server_error_unpack, it.message ?: "")) }
            }

            step(Step.CONFIGURING)
            endLeftovers(context)
            runToEnd(context, "configure", log = File(WowFiles.logs(context).also { it.mkdirs() }, "configure.log"))
            applySettings(context)

            step(Step.DATABASE)
            startDatabase(context)

            // The auth database and ChromieCraft's come first, whichever realm starts (the accounts
            // are there); Wrath Remix's own databases the first time it starts.
            for (imported in listOf(Realms.Realm.CHROMIECRAFT, realm).distinct()) {
                if (importedMarker(context, imported).exists()) continue
                step(Step.IMPORT, progress = null)
                runToEnd(context, "dbimport", imported.key, log = File(WowFiles.logs(context), "dbimport.log"), timeoutMinutes = 60) { line ->
                    step(Step.IMPORT, detail = ConsoleText.plain(line))
                }
                importedMarker(context, imported).writeText("${System.currentTimeMillis()}\n")
            }
            // The custom races of earlier versions out of the realm, once (LegacyRaces).
            if (LegacyRaces.inRealm(context)) {
                step(Step.RACES)
                runToEnd(context, "races", log = File(WowFiles.logs(context), "races.log"), timeoutMinutes = 60) { line ->
                    step(Step.RACES, detail = ConsoleText.plain(line))
                }
            }
            // The world database follows the realm's modules: what a module no longer in it put
            // there goes (ctl.sh modules). Quick unless one went; then a few minutes.
            val modulesGone = worldModulesGone(context, realm)
            if (modulesGone) step(Step.MODULES)
            runToEnd(context, "modules", realm.key, log = File(WowFiles.logs(context), "modules.log"), timeoutMinutes = 60) { line ->
                if (modulesGone) step(Step.MODULES, detail = ConsoleText.plain(line))
            }
            setRealmAddress(context)

            step(Step.AUTH)
            val authLog = File(WowFiles.logs(context), "auth.out")
            auth = launch(context, authLog, "auth")
            waitForPort(AUTH_PORT, 90, auth!!, authLog, context.getString(R.string.wow_server_error_auth))
            watch(context, auth!!, authLog, R.string.wow_server_error_auth_stopped)

            if (WowFiles.hasServerData(context)) {
                startWorld(context, realm)
            } else {
                update { copy(phase = Phase.RUNNING, step = null, detail = null, progress = null, world = World.NO_DATA) }
            }
        } catch (e: StepFailed) {
            fail(context, e.message ?: "", e.log)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(context, e.message ?: e.javaClass.simpleName, null)
        }
    }

    private suspend fun startWorld(context: Context, realm: Realms.Realm) {
        update { copy(phase = Phase.STARTING, step = Step.WORLD, detail = null, progress = null, world = World.STARTING) }
        val log = File(WowFiles.logs(context), "world.out")
        val process = launch(context, log, "world", realm.key, keepInput = true)
        world = process
        worldInput = process.outputStream.bufferedWriter()
        // Loading the world takes minutes on a phone (longer the first time, when it also fills in
        // the progression brackets' changes). Its log says what it's on.
        // Every line of the log is looked at for the ready one: a dozen others follow it within a
        // second, so a look at the last few lines each second misses it.
        val deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(30)
        val ready = LineWatch(READY)
        var read = 0L
        while (true) {
            if (!process.isAlive) throw StepFailed(context.getString(R.string.wow_server_error_world, process.exitValue()), log)
            val (text, end) = WowFiles.readFrom(log, read)
            read = end
            if (ready.feed(text)) break
            if (System.currentTimeMillis() > deadline) throw StepFailed(context.getString(R.string.wow_server_error_world_slow), log)
            update { copy(detail = WowFiles.tail(log, 3).lines().lastOrNull { it.isNotBlank() }?.take(160)) }
            delay(1000)
        }
        update { copy(phase = Phase.RUNNING, step = null, detail = null, world = World.UP) }
        watch(context, process, log, R.string.wow_server_error_world_stopped)
    }

    /** If [process] ends while the server should be running, the server has failed. */
    private fun watch(context: Context, process: Process, log: File, message: Int) {
        scope.launch {
            val code = withContext(Dispatchers.IO) { process.waitFor() }
            if (stopping) return@launch
            val phase = _status.value.phase
            if (phase != Phase.RUNNING && phase != Phase.STARTING) return@launch
            // While starting, the start holds the lock: end it first.
            job?.cancel()
            lock.withLock {
                if (!stopping) fail(context, context.getString(message, code), log)
            }
        }
    }

    private suspend fun fail(context: Context, message: String, log: File?) {
        val tail = log?.let { WowFiles.tail(it, 30) }
        killAll(context)
        update { copy(phase = Phase.FAILED, step = null, progress = null, detail = null, world = World.OFF, error = message, log = tail) }
    }

    private suspend fun shutDown(context: Context) {
        if (db == null && auth == null && world == null) {
            if (_status.value.phase != Phase.FAILED) update { Status(lanAddress = lanAddress()) }
            return
        }
        update { copy(phase = Phase.STOPPING, step = null, progress = null, detail = null) }
        killAll(context)
        update { Status(lanAddress = lanAddress()) }
    }

    /** World first (it saves the characters), then auth, then the databases. */
    private suspend fun killAll(context: Context) = withContext(Dispatchers.IO) {
        stopping = true
        stepProcess?.destroy()
        stepProcess = null
        world?.let { process ->
            if (process.isAlive) {
                // In a second: the command refuses 0.
                worldCommand("server shutdown 1")
                if (!process.waitFor(90, TimeUnit.SECONDS)) process.destroy()
                if (!process.waitFor(15, TimeUnit.SECONDS)) process.destroyForcibly()
            }
        }
        world = null
        runCatching { worldInput?.close() }
        worldInput = null
        auth?.let { process ->
            process.destroy()
            if (!process.waitFor(15, TimeUnit.SECONDS)) process.destroyForcibly()
        }
        auth = null
        db?.let { process ->
            // MariaDB shuts down cleanly on SIGTERM, flushing InnoDB.
            process.destroy()
            if (!process.waitFor(60, TimeUnit.SECONDS)) process.destroyForcibly()
        }
        db = null
    }

    private suspend fun startDatabase(context: Context) {
        val log = File(WowFiles.logs(context), "mariadb.log")
        val process = launch(context, File(WowFiles.logs(context), "db.out"), "db")
        db = process
        val deadline = System.currentTimeMillis() + 120_000
        while (true) {
            if (!process.isAlive) throw StepFailed(context.getString(R.string.wow_server_error_database), log)
            val ping = ctl(context, "db-ping").redirectErrorStream(true).start()
            ping.inputStream.readBytes()
            if (ping.waitFor() == 0) break
            if (System.currentTimeMillis() > deadline) throw StepFailed(context.getString(R.string.wow_server_error_database), log)
            delay(1000)
        }
        watch(context, process, log, R.string.wow_server_error_database_stopped)
    }

    /**
     * The settings the app chooses: the first time, ChromieCraft's start (its first brackets) and a
     * phone-sized bot count; every time, what the server's data allows (line of sight needs vmaps,
     * pathfinding needs mmaps).
     */
    private fun applySettings(context: Context) {
        val etc = WowFiles.etc(context)
        val firstTime = File(etc, ".defaults")
        if (!firstTime.exists()) {
            editConf(File(etc, "modules/progression_system.conf")) {
                setSwitch("ProgressionSystem.Bracket_0", true)
                setSwitch("ProgressionSystem.Bracket_1_19", true)
            }
            editConf(File(etc, "modules/playerbots.conf")) {
                set("Playerbots.MinRandomBots", "50")
                set("Playerbots.MaxRandomBots", "50")
                // No bots running (and no battery used) while nobody is logged in.
                setSwitch("Playerbots.DisabledWithoutRealPlayer", true)
            }
            firstTime.writeText("1\n")
        }
        val folders = WowFiles.dataFolders(context)
        for (realmEtc in listOf(etc, WowFiles.etcRemix(context))) {
            editConf(File(realmEtc, "worldserver.conf")) {
                setSwitch("vmap.enableLOS", "vmaps" in folders)
                setSwitch("vmap.enableHeight", "vmaps" in folders)
                setSwitch("vmap.enableIndoorCheck", "vmaps" in folders)
                setSwitch("MoveMaps.Enable", "mmaps" in folders)
            }
        }
    }

    private fun editConf(file: File, change: ConfFile.() -> Unit) {
        if (!file.isFile) return
        val conf = ConfFile(file.readText())
        conf.change()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(conf.toString())
        if (!tmp.renameTo(file)) throw IOException("Couldn't write ${file.name}")
    }

    /**
     * Both realms' addresses in the auth database (`ctl.sh realmlist`): the game on this phone
     * connects to 127.0.0.1, a PC's client on the Wi-Fi to the phone's address there.
     *
     * It also clears each realm's "version mismatch" flag: the world server sets it while it starts
     * and clears it once it's up, so a world server that stopped while loading leaves it set. The
     * auth server marks every realm offline when it starts and won't run if all of them are both,
     * so the server could never start again.
     */
    private suspend fun setRealmAddress(context: Context) {
        val lan = lanAddress()
        update { copy(lanAddress = lan) }
        runToEnd(context, "realmlist", lan ?: "127.0.0.1", log = File(WowFiles.logs(context), "realmlist.log"))
    }

    /** The phone's IPv4 address on Wi-Fi (or another local network), if it has one. */
    fun lanAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()

    /**
     * Ends server programs left running from before, when Android ended the app without stopping
     * them (they would hold their ports): those named in the pid files, if they're still the
     * server's.
     */
    private suspend fun endLeftovers(context: Context) = withContext(Dispatchers.IO) {
        val serverBin = File(WowFiles.server(context), "bin").path
        val pids = listOf("worldserver.pid", "authserver.pid", "mysqld.pid").mapNotNull { name ->
            File(WowFiles.run(context), name).takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()
        }.filter { pid ->
            runCatching { File("/proc/$pid/cmdline").readText().contains(serverBin) }.getOrDefault(false)
        }
        if (pids.isEmpty()) return@withContext
        for (pid in pids) android.os.Process.sendSignal(pid, 15) // SIGTERM: MariaDB flushes and ends
        repeat(60) {
            if (pids.none { File("/proc/$it").exists() }) return@withContext
            delay(500)
        }
        for (pid in pids) if (File("/proc/$pid").exists()) android.os.Process.sendSignal(pid, 9)
    }

    /** The realm's databases are made (ChromieCraft's: also the auth database). */
    private fun importedMarker(context: Context, realm: Realms.Realm) = File(
        WowFiles.databases(context),
        if (realm == Realms.Realm.CHROMIECRAFT) ".imported" else ".imported-${realm.key}",
    )

    /**
     * A module whose SQL went into the realm's world database (ctl.sh's record, kept with the
     * databases) isn't in the realm package now.
     */
    private fun worldModulesGone(context: Context, realm: Realms.Realm): Boolean {
        val name = if (realm == Realms.Realm.CHROMIECRAFT) ".world-modules" else ".world-modules-${realm.key}"
        val record = File(WowFiles.databases(context), name).takeIf { it.isFile } ?: return false
        val now = File(WowFiles.server(context), "src/modules").listFiles().orEmpty()
            .filter { module -> File(module, "data/sql").listFiles().orEmpty().any { it.isDirectory && it.name.startsWith("db-world") } }
            .map { it.name }
            .toSet()
        return runCatching { record.readLines() }.getOrDefault(emptyList()).map { it.trim() }.any { it.isNotEmpty() && it !in now }
    }

    /** Forgets that the databases are set up, after they were deleted. */
    fun forgetDatabases(context: Context) {
        for (realm in Realms.Realm.entries) importedMarker(context, realm).delete()
    }

    /** `sh ctl.sh <args>` with the app's folder. */
    fun ctl(context: Context, vararg args: String): ProcessBuilder {
        WowFiles.run(context).mkdirs()
        return ProcessBuilder(listOf("/system/bin/sh", WowFiles.ctl(context).path) + args).apply {
            // The server's paths are the ones it was built for (the same folder: isStandardPath).
            environment()["FILES"] = WowFiles.serverRoot(context)
            environment()["TZ"] = posixTimeZone()
            directory(WowFiles.run(context))
        }
    }

    /**
     * The phone's time zone for the server's programs, which can't read Android's: as a POSIX
     * TZ value with today's offset (`<+0200>-02:00` is two hours ahead of UTC, the sign inverted).
     */
    fun posixTimeZone(): String {
        val minutes = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000
        val abs = kotlin.math.abs(minutes)
        val root = java.util.Locale.ROOT
        val name = String.format(root, "%s%02d%02d", if (minutes >= 0) "+" else "-", abs / 60, abs % 60)
        return String.format(root, "<%s>%s%02d:%02d", name, if (minutes >= 0) "-" else "+", abs / 60, abs % 60)
    }

    /** Starts a server program (`ctl.sh <args>`) with its output going to [log]. */
    private fun launch(context: Context, log: File, vararg args: String, keepInput: Boolean = false): Process {
        log.parentFile?.mkdirs()
        val builder = ctl(context, *args).redirectErrorStream(true).redirectOutput(log)
        if (!keepInput) builder.redirectInput(File("/dev/null"))
        return builder.start()
    }

    /** Runs a ctl.sh command (and its arguments) to its end, passing its output lines to [onLine]. */
    private suspend fun runToEnd(
        context: Context,
        vararg command: String,
        log: File,
        timeoutMinutes: Long = 5,
        onLine: ((String) -> Unit)? = null,
    ) = withContext(Dispatchers.IO) {
        val process = ctl(context, *command).redirectErrorStream(true).redirectInput(File("/dev/null")).start()
        stepProcess = process
        // The output is read on its own thread, so a program that hangs without a word still
        // runs into the time limit.
        val reader = Thread {
            runCatching {
                log.outputStream().bufferedWriter().use { out ->
                    process.inputStream.bufferedReader().useLines { lines ->
                        for (line in lines) {
                            out.write(line)
                            out.newLine()
                            out.flush()
                            onLine?.invoke(line.take(160))
                        }
                    }
                }
            }
        }.apply { start() }
        val deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(timeoutMinutes)
        while (!process.waitFor(1, TimeUnit.SECONDS)) {
            if (!isActive || System.currentTimeMillis() > deadline) {
                process.destroy()
                if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly()
                break
            }
        }
        reader.join(5_000)
        stepProcess = null
        if (!isActive) throw kotlinx.coroutines.CancellationException()
        if (System.currentTimeMillis() > deadline) {
            throw StepFailed(context.getString(R.string.wow_server_error_step_slow, command.first()), log)
        }
        val code = process.exitValue()
        if (code != 0) throw StepFailed(context.getString(R.string.wow_server_error_step, command.first(), code), log)
    }

    private suspend fun waitForPort(port: Int, seconds: Int, process: Process, log: File, message: String) {
        repeat(seconds) {
            if (!process.isAlive) throw StepFailed(message, log)
            val open = withContext(Dispatchers.IO) {
                runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 500) } }.isSuccess
            }
            if (open) return
            delay(1000)
        }
        throw StepFailed(message, log)
    }

    /** The world server's last line of starting up. */
    private val READY = Regex("\\(worldserver-daemon\\) ready")
}
