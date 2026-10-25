// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.felsong

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.logie.warcraft.R
import com.logie.warcraft.Server
import com.logie.warcraft.WowFiles
import com.logie.warcraft.config.ConfFile
import com.logie.warcraft.felsong.core.FelsongRealm
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
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * Felsong's realm on the phone: its own MariaDB (port 3312), the Battle.net login server bnetserver
 * (1119, its REST login on 8081) and the world server worldserver (8095, instances 8096), each a
 * program from `files/felsong/server` run through Felsong's own `ctl.sh`. Nothing here starts,
 * stops or reads the other realms; none of its ports is theirs, so it runs beside them (memory
 * allowing).
 */
object FelsongServer {
    enum class Phase { STOPPED, STARTING, RUNNING, STOPPING, FAILED }

    enum class Step { CONFIGURING, DATABASE, LOGIN, WORLD }

    data class Status(
        val phase: Phase = Phase.STOPPED,
        val step: Step? = null,
        val detail: String? = null,
        /** The world server is up (players can enter the world). */
        val worldUp: Boolean = false,
        /** Waiting for the server's data (made from the client). */
        val noData: Boolean = false,
        val error: String? = null,
        val log: String? = null,
        val lanAddress: String? = null,
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var job: Job? = null

    private var db: Process? = null
    private var bnet: Process? = null
    private var world: Process? = null
    private var worldInput: Writer? = null
    @Volatile private var stepProcess: Process? = null
    @Volatile private var stopping = false

    val databaseUp: Boolean get() = db?.isAlive == true

    /** Starting, running or stopping: the realm holds its ports. */
    val isBusy: Boolean
        get() = _status.value.phase.let { it == Phase.STARTING || it == Phase.RUNNING || it == Phase.STOPPING }

    private fun update(change: Status.() -> Status) {
        _status.value = _status.value.change()
    }

    private class StepFailed(message: String, val log: File? = null) : Exception(message)

    /** Starts the realm, and the service that keeps it running. */
    fun start(context: Context) {
        val app = context.applicationContext
        if (isBusy) return
        update { Status(phase = Phase.STARTING, step = Step.CONFIGURING) }
        FelsongService.keepAlive(app)
        job = scope.launch { lock.withLock { run(app) } }
    }

    fun stop(context: Context, then: (() -> Unit)? = null) {
        val app = context.applicationContext
        val running = job
        scope.launch {
            running?.cancel()
            lock.withLock { shutDown(app) }
            then?.invoke()
        }
    }

    suspend fun stopAndWait(context: Context) {
        job?.cancel()
        lock.withLock { shutDown(context.applicationContext) }
    }

    /**
     * Runs [block] with Felsong's database up: the running realm's, or its MariaDB started alone for
     * it and stopped after (accounts, backups, the installer). Background thread.
     */
    suspend fun <T> withDatabase(context: Context, block: suspend () -> T): T {
        if (databaseUp) return block()
        val app = context.applicationContext
        return lock.withLock {
            if (databaseUp) return@withLock block()
            stopping = false
            val logs = FelsongFiles.layout(app).logs
            FelsongFiles.fs(app).mkdirs(logs)
            runToEnd(app, listOf("configure") + if (FelsongSettings.lan(app)) listOf("lan") else emptyList(), File(logs, "configure.log"))
            try {
                try {
                    startDatabase(app)
                } catch (e: StepFailed) {
                    throw IOException(e.message)
                }
                block()
            } finally {
                stopping = true
                withContext(Dispatchers.IO) {
                    db?.let { process ->
                        process.destroy()
                        if (!process.waitFor(60, TimeUnit.SECONDS)) process.destroyForcibly()
                    }
                }
                db = null
            }
        }
    }

    /** A command for the world server's console (`server info`, `account onlinelist`...). */
    fun worldCommand(command: String): Boolean {
        val input = worldInput ?: return false
        return runCatching {
            input.write(command + "\n")
            input.flush()
        }.isSuccess
    }

    private suspend fun run(context: Context) {
        try {
            stopping = false
            FelsongFiles.blocker(context)?.let { throw StepFailed(context.getString(R.string.felsong_error_blocker)) }
            if (!FelsongPackage.isInstalled(context)) throw StepFailed(context.getString(R.string.felsong_error_not_installed))
            endLeftovers(context)
            busyPort()?.let { port ->
                val owner = FelsongRealm.OTHER_PORTS[port]
                throw StepFailed(
                    if (owner != null) context.getString(R.string.felsong_error_port_other, port, owner)
                    else context.getString(R.string.felsong_error_port, port),
                )
            }

            update { copy(phase = Phase.STARTING, step = Step.CONFIGURING, lanAddress = Server.lanAddress()) }
            val logs = FelsongFiles.layout(context).logs
            FelsongFiles.fs(context).mkdirs(logs)
            val lan = FelsongSettings.lan(context)
            runToEnd(context, listOf("configure") + if (lan) listOf("lan") else emptyList(), File(logs, "configure.log"))
            FelsongSettings.applyToConfig(context)
            // The Battle.net server tells the client where its REST login is: this phone, or, with
            // PCs on the Wi-Fi let in, the phone's address there.
            val restAddress = (if (lan) Server.lanAddress() else null) ?: "127.0.0.1"
            editConf(File(FelsongFiles.layout(context).etc, "bnetserver.conf")) {
                set("LoginREST.ExternalAddress", restAddress)
                set("LoginREST.LocalAddress", "127.0.0.1")
            }

            update { copy(step = Step.DATABASE, detail = null) }
            startDatabase(context)
            setRealm(context, lan)

            update { copy(step = Step.LOGIN, detail = null) }
            val bnetLog = File(logs, "bnetserver.out")
            val login = launch(context, "bnetserver", bnetLog)
            bnet = login
            waitForPort(FelsongRealm.BNET_PORT, 90, login, bnetLog, context.getString(R.string.felsong_error_login))
            waitForPort(FelsongRealm.REST_PORT, 30, login, bnetLog, context.getString(R.string.felsong_error_login))
            watch(context, login, bnetLog, R.string.felsong_error_login_stopped)

            if (FelsongFiles.hasData(context)) {
                startWorld(context)
            } else {
                update { copy(phase = Phase.RUNNING, step = null, detail = null, noData = true) }
            }
        } catch (e: StepFailed) {
            fail(context, e.message ?: "", e.log)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(context, e.message ?: e.javaClass.simpleName, null)
        }
    }

    private suspend fun startWorld(context: Context) {
        update { copy(phase = Phase.STARTING, step = Step.WORLD, detail = null, noData = false) }
        val log = File(FelsongFiles.layout(context).logs, "worldserver.out")
        val process = launch(context, "worldserver", log, keepInput = true)
        world = process
        worldInput = process.outputStream.bufferedWriter()
        // The first start applies LegionCore's database updates and loads 400,000 creatures: long on a phone.
        val deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(60)
        val ready = LineWatch(FelsongRealm.READY)
        var read = 0L
        while (true) {
            if (!process.isAlive) throw StepFailed(context.getString(R.string.felsong_error_world, process.exitValue()), log)
            val (text, end) = WowFiles.readFrom(log, read)
            read = end
            ConsoleText.plain(text).lineSequence().firstNotNullOfOrNull { FelsongRealm.Fatal.of(it) }?.let { fatal ->
                throw StepFailed(context.getString(fatalMessage(fatal)), log)
            }
            if (ready.feed(text)) break
            if (System.currentTimeMillis() > deadline) throw StepFailed(context.getString(R.string.felsong_error_world_slow), log)
            update { copy(detail = WowFiles.tail(log, 3).lines().lastOrNull { it.isNotBlank() }?.take(160)) }
            delay(1000)
        }
        FelsongFiles.updateState(context) { it.copy(databaseUpdated = true) }
        update { copy(phase = Phase.RUNNING, step = null, detail = null, worldUp = true) }
        watch(context, process, log, R.string.felsong_error_world_stopped)
    }

    private fun fatalMessage(fatal: FelsongRealm.Fatal) = when (fatal) {
        FelsongRealm.Fatal.DBC -> R.string.felsong_error_fatal_dbc
        FelsongRealm.Fatal.DATA -> R.string.felsong_error_fatal_data
        FelsongRealm.Fatal.UPDATE -> R.string.felsong_error_fatal_update
        FelsongRealm.Fatal.DATABASE -> R.string.felsong_error_fatal_database
    }

    private fun watch(context: Context, process: Process, log: File, message: Int) {
        scope.launch {
            val code = withContext(Dispatchers.IO) { process.waitFor() }
            if (stopping) return@launch
            if (!isBusy) return@launch
            job?.cancel()
            lock.withLock { if (!stopping) fail(context, context.getString(message, code), log) }
        }
    }

    private suspend fun fail(context: Context, message: String, log: File?) {
        val tail = log?.let { WowFiles.tail(it, 30) }
        killAll()
        update { copy(phase = Phase.FAILED, step = null, detail = null, worldUp = false, error = message, log = tail) }
    }

    private suspend fun shutDown(context: Context) {
        if (db == null && bnet == null && world == null) {
            if (_status.value.phase != Phase.FAILED) update { Status() }
            return
        }
        update { copy(phase = Phase.STOPPING, step = null, detail = null) }
        killAll()
        update { Status() }
    }

    /** World first (it saves the characters), then the login server, then the database. */
    private suspend fun killAll() = withContext(Dispatchers.IO) {
        stopping = true
        stepProcess?.destroy()
        stepProcess = null
        world?.let { process ->
            if (process.isAlive) {
                worldCommand("server shutdown 1")
                if (!process.waitFor(90, TimeUnit.SECONDS)) process.destroy()
                if (!process.waitFor(15, TimeUnit.SECONDS)) process.destroyForcibly()
            }
        }
        world = null
        runCatching { worldInput?.close() }
        worldInput = null
        bnet?.let { process ->
            process.destroy()
            if (!process.waitFor(15, TimeUnit.SECONDS)) process.destroyForcibly()
        }
        bnet = null
        db?.let { process ->
            process.destroy()
            if (!process.waitFor(60, TimeUnit.SECONDS)) process.destroyForcibly()
        }
        db = null
    }

    private suspend fun startDatabase(context: Context) {
        val logs = FelsongFiles.layout(context).logs
        val log = File(logs, "mariadb.log")
        val process = launch(context, "db", File(logs, "db.out"))
        db = process
        val deadline = System.currentTimeMillis() + 180_000
        while (true) {
            if (!process.isAlive) throw StepFailed(context.getString(R.string.felsong_error_database), log)
            val ping = ctl(context, "db-ping").redirectErrorStream(true).start()
            ping.inputStream.readBytes()
            if (ping.waitFor() == 0) break
            if (System.currentTimeMillis() > deadline) throw StepFailed(context.getString(R.string.felsong_error_database), log)
            delay(1000)
        }
        watch(context, process, log, R.string.felsong_error_database_stopped)
    }

    /**
     * The realm's row in Felsong's login database: this phone's client connects to 127.0.0.1, or,
     * with PCs on the Wi-Fi let in, everyone to the phone's address there.
     */
    private fun setRealm(context: Context, lan: Boolean) {
        val address = (if (lan) Server.lanAddress() else null) ?: "127.0.0.1"
        FelsongDb.exec(
            context, "auth",
            "UPDATE realmlist SET name = '${FelsongRealm.REALM_NAME}', address = '$address', localAddress = '127.0.0.1', " +
                "port = ${FelsongRealm.WORLD_PORT}, gamePort = ${FelsongRealm.INSTANCE_PORT}, gamebuild = ${FelsongRealm.CLIENT_BUILD}, flag = 0 WHERE id = 1;",
        )
        // The game options' rows (an undead paladin's start...), put in or taken out as switched.
        // (a failure here leaves the realm starting without them, said in its log)
        runCatching { FelsongDb.exec(context, "world", com.logie.warcraft.felsong.core.FelsongGameOptions.sql(FelsongSettings.options(context))) }
            .onFailure { android.util.Log.w("FelsongServer", "The game options' rows: ${it.message}") }
    }

    /** One of the realm's ports already taken (by another app, or another game's server). */
    private suspend fun busyPort(): Int? = withContext(Dispatchers.IO) {
        FelsongRealm.PORTS.firstOrNull { port ->
            runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 300) } }.isSuccess
        }
    }

    /** Felsong's own programs left running when Android ended the app (named in its pid files). */
    private suspend fun endLeftovers(context: Context) = withContext(Dispatchers.IO) {
        val layout = FelsongFiles.layout(context)
        val serverBin = File(layout.server, "bin").path
        val pids = listOf("worldserver.pid", "bnetserver.pid", "mysqld.pid").mapNotNull { name ->
            File(layout.run, name).takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()
        }.filter { pid ->
            // Only programs from Felsong's own folder: never the other realms'.
            runCatching { File("/proc/$pid/cmdline").readText().contains(serverBin) }.getOrDefault(false)
        }
        if (pids.isEmpty()) return@withContext
        for (pid in pids) android.os.Process.sendSignal(pid, 15)
        repeat(60) {
            if (pids.none { File("/proc/$it").exists() }) return@withContext
            delay(500)
        }
        for (pid in pids) if (File("/proc/$pid").exists()) android.os.Process.sendSignal(pid, 9)
    }

    /** `sh files/felsong/server/ctl.sh <args>`, with Felsong's folder. */
    fun ctl(context: Context, vararg args: String): ProcessBuilder {
        val layout = FelsongFiles.layout(context)
        FelsongFiles.fs(context).mkdirs(layout.run)
        return ProcessBuilder(listOf("/system/bin/sh", layout.ctl.path) + args).apply {
            environment()["FILES"] = FelsongFiles.serverRoot(context)
            environment()["OPENSSL_MODULES"] = File(layout.server, FelsongRealm.OPENSSL_MODULES).path
            environment()["TZ"] = Server.posixTimeZone()
            directory(layout.run)
        }
    }

    private fun launch(context: Context, command: String, log: File, keepInput: Boolean = false): Process {
        val builder = ctl(context, command).redirectErrorStream(true).redirectOutput(log)
        if (!keepInput) builder.redirectInput(File("/dev/null"))
        return builder.start()
    }

    /** Runs a ctl.sh command to its end, its output in [log] and each line to [onLine]. */
    suspend fun runToEnd(
        context: Context,
        args: List<String>,
        log: File,
        timeoutMinutes: Long = 5,
        onLine: ((String) -> Unit)? = null,
    ) = withContext(Dispatchers.IO) {
        val process = ctl(context, *args.toTypedArray()).redirectErrorStream(true).redirectInput(File("/dev/null")).start()
        stepProcess = process
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
        if (System.currentTimeMillis() > deadline) throw IOException(context.getString(R.string.felsong_error_step_slow, args.first()))
        val code = process.exitValue()
        if (code != 0) throw IOException(context.getString(R.string.felsong_error_step, args.first(), code))
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

    /** Clears a failure, back to stopped. */
    fun dismiss() {
        if (_status.value.phase == Phase.FAILED) _status.value = Status()
    }

    /** Settings in a config, written while the realm is stopped (taken at the next start). */
    fun editConf(file: File, change: ConfFile.() -> Unit) {
        if (!file.isFile) return
        val conf = ConfFile(file.readText())
        conf.change()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(conf.toString())
        if (!tmp.renameTo(file)) throw IOException("Couldn't write ${file.name}")
    }

    internal fun serviceIntent(context: Context) = Intent(context, FelsongService::class.java)

    internal fun startService(context: Context, intent: Intent) = ContextCompat.startForegroundService(context, intent)
}

/** SQL on Felsong's own databases, through its MariaDB client, while its MariaDB runs. */
object FelsongDb {
    class NotRunningException : IOException("Felsong's database isn't running")

    private val DATABASES = setOf("auth", "characters", "world", "hotfixes")

    fun query(context: Context, database: String, sql: String): List<List<String>> {
        require(database in DATABASES) { "Not one of Felsong's databases: $database" }
        if (!FelsongServer.databaseUp) throw NotRunningException()
        val process = FelsongServer.ctl(context, "sql", database).redirectErrorStream(false).start()
        process.outputStream.bufferedWriter().use { it.write(sql) }
        val output = process.inputStream.bufferedReader().readText()
        val errors = process.errorStream.bufferedReader().readText()
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroy()
            throw IOException("Felsong's database didn't answer")
        }
        if (process.exitValue() != 0) throw IOException(errors.lines().firstOrNull { it.isNotBlank() } ?: "SQL failed")
        return output.lines().filter { it.isNotEmpty() }.map { it.split('\t') }
    }

    fun exec(context: Context, database: String, sql: String) {
        query(context, database, sql)
    }
}
