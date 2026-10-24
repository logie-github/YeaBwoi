// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.local

import android.content.Context
import com.logie.warcraft.config.ConfFile
import com.logie.warcraft.tww.core.TwwInstall
import com.logie.warcraft.tww.core.TwwSession
import com.winlator.XServerDisplayActivity
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
import java.lang.ref.WeakReference
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * A downloaded local realm's real Windows processes (its database, authentication and world servers,
 * their tools), run by Wine with no screen ([WindowsRealmHost]): no Activity starts, holds or ends
 * the realm, so Android closing, trimming or refusing to open a screen never stops it. The game's
 * client runs in a session of its own (RealmSessionActivity, opened from Launch Game) that joins the
 * realm's Wine server; its end (Leave, the game exiting, Android closing it) ends only the game.
 */
open class WindowsRealmServer(protected val realm: ManagedWindowsRealm) {
    enum class Phase { STOPPED, STARTING, RUNNING, STOPPING, FAILED }
    enum class ComponentStatus { STOPPED, STARTING, RUNNING, FAILED }

    data class Status(
        val phase: Phase = Phase.STOPPED,
        val database: ComponentStatus = ComponentStatus.STOPPED,
        val authentication: ComponentStatus = ComponentStatus.STOPPED,
        val world: ComponentStatus = ComponentStatus.STOPPED,
        val detail: String? = null,
        val error: String? = null,
        /** The game's client was started in its session and hasn't exited ([launchGame] returns to it). */
        val clientRunning: Boolean = false,
        /** Why the game's session didn't open or ended early (the realm itself unaffected). */
        val clientError: String? = null,
    )

    private val mutableStatus = MutableStateFlow(Status())
    val status: StateFlow<Status> = mutableStatus
    val isBusy: Boolean get() = mutableStatus.value.let {
        host.running || sessionAlive || it.phase in setOf(Phase.STARTING, Phase.RUNNING, Phase.STOPPING) ||
            listOf(it.database, it.authentication, it.world).any { component -> component == ComponentStatus.RUNNING }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val queryLock = Mutex()
    private var querySequence = 0L
    private var job: Job? = null
    /** The realm's Wine, with no screen. */
    private val host = WindowsRealmHost(realm.sessionExtra)
    /** The game's session (its screen), only while it's open: never the realm's processes'. */
    @Volatile private var session = WeakReference<XServerDisplayActivity>(null)
    private val sessionAlive get() = session.get()?.let { !it.isDestroyed && !it.isFinishing } == true
    /** The realm's Wine is running for this realm (a game's session for it joins it). */
    internal val hostRunning get() = host.running
    @Volatile private var stopping = false
    @Volatile private var profile: Profile? = null
    /** One client launch at a time; [clientLaunched]: the client was started in this session. */
    private val clientLock = Mutex()
    @Volatile private var clientLaunched = false
    /** The client's program, as Windows names its process (for Leave). */
    @Volatile private var clientProcess: String? = null
    /** The crash reports there were before this start's servers began ([crashReports]). */
    @Volatile private var knownCrashes: Set<String> = emptySet()
    val authenticationDatabase: String? get() = profile?.login?.getOrNull(4)
    private val started = mutableSetOf<String>()

    private data class Profile(
        val detected: TwwInstall.DetectedServer,
        val databasePort: Int,
        val loginPorts: List<Int>,
        val worldPort: Int,
        /** The world server's second port (TrinityCore's InstanceServerPort), when it has one. */
        val instancePort: Int?,
        val realmId: Int,
        val mysql: File,
        val mysqlAdmin: File,
        val login: List<String>,
    ) {
        val worldPorts get() = listOfNotNull(worldPort, instancePort)
        val ports get() = listOf(databasePort) + loginPorts + worldPorts
    }

    init { WindowsRealmRegistry.register(this, realm.sessionExtra) }

    /**
     * Leave (the game's menu) in the game's session: the game ends with its session (its programs by
     * their marker, as the session ends; not through Winlator's helper, which the session's end stops
     * while it may still be sending); the realm runs on.
     */
    @Suppress("UNUSED_PARAMETER")
    internal fun leaveClient(runtime: XServerDisplayActivity) {
        clientLaunched = false
        mutableStatus.value = mutableStatus.value.copy(clientRunning = false)
    }

    /** The game's session for this realm opens (it joins the realm's Wine; nothing of the realm depends on it). */
    internal fun onSessionAttached(runtime: XServerDisplayActivity) {
        session = WeakReference(runtime)
        // (a realm's session is its game: one that opened after Launch Game gave up waiting is it all the same)
        clientLaunched = true
        mutableStatus.value = mutableStatus.value.copy(clientRunning = true, clientError = null)
    }

    /** The game's session ended (Leave, the game exiting, Android closing its screen): the game only. */
    internal fun onSessionDetached(runtime: XServerDisplayActivity) {
        if (session.get() !== runtime) return
        session.clear()
        clientLaunched = false
        mutableStatus.value = mutableStatus.value.copy(clientRunning = false)
    }

    /** The game's session wasn't allowed to open (another Windows session is open). */
    internal fun onSessionRejected() {
        WindowsRealmGame.cancelPending()
        clientLaunched = false
        mutableStatus.value = mutableStatus.value.copy(clientRunning = false,
            clientError = "The game's screen didn't open: another Windows session is open. Close it, then Launch Game again.")
    }

    /** A game's session may open for this realm: its Wine is running and ready for the game. */
    internal fun allowsSession(): Boolean = host.running && mutableStatus.value.phase == Phase.RUNNING

    fun start(context: Context) {
        if (isBusy || realm.installationBusy) return
        val app = context.applicationContext
        stopping = false
        mutableStatus.value = Status(phase = Phase.STARTING, detail = "Checking the imported repack and its startup requirements…")
        // (the app kept running, as a foreground service, from the tap: the realm's Wine is its children)
        LocalRealmService.start(app)
        job = scope.launch { lock.withLock { startNow(app) } }
    }

    fun stop(context: Context) {
        val app = context.applicationContext
        scope.launch { runCatching { stopAndWait(app) } }
    }

    suspend fun stopAndWait(context: Context) {
        stopping = true
        job?.cancel()
        lock.withLock { stopNow(context.applicationContext) }
        if (isBusy) throw IOException(mutableStatus.value.error ?: "The Windows server has not stopped; installed files were left unchanged.")
    }

    /** Explicit last resort, offered separately by the UI after ordinary shutdown fails. */
    fun forceStop(context: Context) {
        stopping = true
        scope.launch {
            job?.cancel()
            lock.withLock {
              try {
                val p = profile
                closeDisplaySession()
                // (every process of the realm's Wine at once, then its server)
                withContext(Dispatchers.IO) { host.kill() }
                if (p != null) {
                    val deadline = System.currentTimeMillis() + 15_000
                    while (p.ports.any { open(it) } && System.currentTimeMillis() < deadline) delay(250)
                }
                WindowsRealmGame.cancelPending()
                if (!withContext(Dispatchers.IO) { host.stop() }) {
                    mutableStatus.value = mutableStatus.value.copy(phase = Phase.FAILED,
                        error = "Forced termination was requested, but some of the realm's Windows processes did not end. Installation files remain locked.")
                    refreshObserved()
                    return@withLock
                }
                if (p != null && p.ports.any { open(it) }) {
                    mutableStatus.value = mutableStatus.value.copy(phase = Phase.FAILED,
                        error = "Forced termination was requested, but a server port is still open. Installation files remain locked; inspect the component logs.")
                    refreshObserved()
                    return@withLock
                }
                WindowsRealmRegistry.release(this@WindowsRealmServer)
                profile = null
                started.clear()
                File(layout(context).root, "runtime/login.cnf").delete()
                mutableStatus.value = Status(detail = "Windows processes were forcibly stopped. Verify the database before starting again.")
              } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
              } catch (error: Exception) {
                // (never out of the coroutine: uncaught, it would end the app)
                mutableStatus.value = mutableStatus.value.copy(phase = Phase.FAILED, detail = null, error = error.message ?: error.javaClass.simpleName)
                refreshObserved()
              }
            }
        }
    }

    /**
     * Configures only the imported repack. Values come from its actual configurations and
     * initialized database folders. Original text files are kept as .before-local backups.
     */
    fun configureLocal(context: Context) = configureLocal(context, false)

    internal fun configureInstallation(context: Context) = configureLocal(context, true)

    private fun configureLocal(context: Context, installing: Boolean) {
        if (isBusy || realm.installationBusy && !installing) throw IOException("Stop this realm and wait for its installation job before changing configuration.")
        val detected = realm.report(context).detected ?: throw IOException("Install the local server repack first.")
        val world = detected.worldConfig ?: throw IOException("The world server configuration is missing.")
        val auth = detected.authenticationConfig ?: throw IOException("The authentication server configuration is missing.")
        val database = detected.databaseConfig ?: throw IOException("The database configuration is missing.")
        val mysql = detected.databaseExecutable ?: throw IOException("The database executable is missing.")
        val basedir = mysql.parentFile.takeIf { it.name.equals("bin", true) }?.parentFile
            ?: throw IOException("The database's installation directory could not be established from ${mysql.name}.")
        val databaseProfile = detected.databaseProfile ?: throw IOException("The database installation could not be resolved.")
        val controllerRoot = databaseProfile.controller?.parentFile
        val databasePort = databaseProfile.port
            ?: throw IOException("The repack's database port is unverified in my.ini or UniController's us_user.ini.")
        val datadir = databaseProfile.dataDirectory?.takeIf { it.isDirectory }
            ?: throw IOException("The active database's configured data directory is missing or unresolved.")
        val report = realm.report(context)
        if (report.database.status != TwwInstall.Status.INSTALLED) throw IOException(report.database.missing.joinToString("\n"))
        val replacement = mutableMapOf(
            "port" to databasePort.toString(),
            "bind-address" to realm.address,
            "basedir" to TwwSession.windows(basedir, detected.root, 'S').replace('\\', '/'),
            "datadir" to TwwSession.windows(datadir, detected.root, 'S').replace('\\', '/'),
        )
        // UniController normally supplies these directory overrides on the command line.
        // Keep them explicit in the managed configuration when starting its actual daemon.
        if (controllerRoot != null) {
            val temporary = fs(context).mkdirs(File(controllerRoot, "tmp"))
            replacement["tmpdir"] = TwwSession.windows(temporary, detected.root, 'S').replace('\\', '/')
            for (key in listOf("innodb_data_home_dir", "innodb_log_group_home_dir")) {
                if (iniValue(database.readText(), "mysqld", key) != null) replacement[key] = replacement.getValue("datadir")
            }
        }
        var dbText = database.readText()
        for ((key, value) in replacement) dbText = setIni(dbText, "mysqld", key, value)
        replaceConfig(database, dbText)
        for (file in listOf(auth, world)) {
            val conf = ConfFile(file.readText())
            conf.setText("BindIP", realm.address)
            for (key in listOf("LoginDatabaseInfo", "WorldDatabaseInfo", "CharacterDatabaseInfo", "HotfixDatabaseInfo")) {
                val original = conf[key] ?: continue
                val values = original.split(';').toMutableList()
                if (values.size != 5) throw IOException("$key in ${file.name} is not a supported five-field database connection.")
                values[0] = realm.address
                values[1] = databasePort.toString()
                conf.setText(key, values.joinToString(";"))
            }
            if (conf["LoginREST.ExternalAddress"] != null) conf.setText("LoginREST.ExternalAddress", realm.address)
            if (conf["LoginREST.LocalAddress"] != null) conf.setText("LoginREST.LocalAddress", realm.address)
            if (file == world) conf.setSwitch("Console.Enable", false)
            replaceConfig(file, conf.toString())
        }
        // Revalidate the resulting profile before recording completion.
        readProfile(context)
    }

    /**
     * The world server without its console: in the Windows session its input is no console (Wine's
     * unix one, at its end), so TrinityCore's console thread reads the end at once and stops the world
     * right after it loads, before it opens its port (and its crash report on the way out hangs, so the
     * process stays, the port never opening). Seen under Wine on x86-64 and under Box64 alike.
     * With it, the realm's own settings ([ManagedWindowsRealm.worldSettings]).
     */
    private fun headlessWorld(world: File) {
        val conf = ConfFile(world.readText())
        val wanted = mapOf("Console.Enable" to "0") + realm.worldSettings
        if (wanted.all { (key, value) -> conf[key]?.trim() == value }) return
        for ((key, value) in wanted) conf[key] = value
        replaceConfig(world, conf.toString())
    }

    /**
     * The realm's SQL for its databases ([ManagedWindowsRealm.worldStartupSql], [ManagedWindowsRealm.loginStartupSql]),
     * once the database is up and before the world server loads them. A failure doesn't stop the realm
     * (it plays without what the SQL gives); it's reported.
     */
    private suspend fun prepareDatabases(context: Context, p: Profile) {
        val world = p.detected.worldConfig?.let { ConfFile(it.readText())["WorldDatabaseInfo"] }?.split(';')
            ?.takeIf { it.size == 5 }?.last()
        realm.worldStartupSql?.let { sql -> world?.let { runStartupSql(context, p, "world-setup", it, sql) } }
        realm.loginStartupSql?.let { sql -> runStartupSql(context, p, "login-setup", p.login[4], sql) }
    }

    private suspend fun runStartupSql(context: Context, p: Profile, name: String, database: String, sql: String) {
        if (!database.matches(Regex("[A-Za-z0-9_]+"))) return
        val runtime = runtimeFolder(context)
        LocalConfig.atomicWrite(File(runtime, "$name.sql"), sql)
        File(runtime, "$name.exit").delete()
        val command = TwwSession.argument(TwwSession.windows(p.mysql, p.detected.root, 'S')) +
            " --defaults-extra-file=R:\\runtime\\login.cnf --default-character-set=utf8mb4 --batch --skip-column-names $database " + TwwSession.input("R:\\runtime\\$name.sql")
        writeScript(context, name, TwwSession.script("S:\\", command, name))
        execute(name)
        try {
            waitExit(context, name, 120)
        } catch (e: IOException) {
            if (!host.running) throw e
            com.logie.warcraft.FailureReports.record(context, "${realm.displayName} $name", e.message ?: "failed",
                runCatching { File(runtime, "$name.log").readText() }.getOrNull())
        }
    }

    /**
     * The game, in a session of its own joining the realm's Wine (opened from [context], the launcher
     * on screen: Android opens no screen for an app in the background, so it waits for the launcher
     * to be shown); back to it if it's running.
     */
    fun launchGame(context: Context) {
        val app = context.applicationContext
        scope.launch {
          clientLock.withLock {
            try {
                // The client already running in its session: back to it, not a second one.
                if (clientLaunched && sessionAlive && !exitFile(app, "client").isFile) {
                    withContext(Dispatchers.Main) { realm.showSession(context) }
                    return@withLock
                }
                // (a session whose game has ended, still closing: gone before the new one opens)
                closeDisplaySession()
                if (realm.installationBusy) throw IOException("Wait for the current installation job before launching the client.")
                if (mutableStatus.value.phase != Phase.RUNNING || !host.running) throw IOException("Start the local server and wait for every component to be ready first.")
                // (its settings written first: the client rewrites its Config.wtf itself, and one that
                // stopped early can leave it without the realm's address)
                realm.prepareClientLaunch(app)
                if (!realm.localClientConfigured(app)) throw IOException("Configure the local client connection and review the Firestorm warning first.")
                val report = realm.report(app)
                val executable = report.clientExecutable ?: throw IOException("The client executable is missing.")
                if (report.client.status != TwwInstall.Status.INSTALLED || report.clientBuild?.build != realm.spec.build)
                    throw IOException("The installed client is incomplete or does not match build ${realm.spec.build}.")
                val layout = layout(app)
                val command = TwwSession.argument(TwwSession.windows(executable, layout.client, 'D'))
                writeScript(app, "client", TwwSession.script(TwwSession.windows(executable.parentFile, layout.client, 'D'), command, "client"))
                val container = host.container ?: throw IOException("The realm's Windows runtime is not running.")
                clientLaunched = true
                clientProcess = executable.name
                mutableStatus.value = mutableStatus.value.copy(clientRunning = true, clientError = null,
                    detail = "Client launch requested. Connection compatibility is confirmed only after the client logs in; see runtime/client.log if it exits.", error = null)
                withContext(Dispatchers.Main) { realm.openClientSession(context, container) }
                // (opened with the launcher on screen, but no screen in a minute: said; one waiting for the
                // launcher to be shown isn't waited on)
                val requested = System.currentTimeMillis()
                while (!WindowsRealmGame.launchPending && clientLaunched && !sessionAlive && System.currentTimeMillis() - requested < 60_000) delay(500)
                if (!WindowsRealmGame.launchPending && clientLaunched && !sessionAlive) {
                    clientLaunched = false
                    mutableStatus.value = mutableStatus.value.copy(clientRunning = false,
                        clientError = "The game's screen didn't open. Tap Launch Game again with the app on screen.")
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                clientLaunched = false
                mutableStatus.value = mutableStatus.value.copy(clientRunning = false, clientError = error.message ?: error.javaClass.simpleName)
            }
          }
        }
    }

    /** Real SQL through this realm's running database client; each request has private temporary files. */
    suspend fun query(context: Context, database: String, sql: String): String = queryLock.withLock {
        val p = profile ?: throw IOException("Start the local server before managing accounts.")
        if (mutableStatus.value.phase != Phase.RUNNING || !open(p.databasePort))
            throw IOException("Wait for the local server to finish starting before managing accounts.")
        if (!database.matches(Regex("[A-Za-z0-9_]+"))) throw IOException("Invalid database name.")
        val configured = listOfNotNull(p.detected.authenticationConfig, p.detected.worldConfig).flatMap { file ->
            val conf = ConfFile(file.readText())
            listOf("LoginDatabaseInfo", "WorldDatabaseInfo", "CharacterDatabaseInfo", "HotfixDatabaseInfo")
                .mapNotNull { conf[it]?.split(';')?.takeIf { fields -> fields.size == 5 }?.last() }
        }.toSet()
        if (database !in configured) throw IOException("This database does not belong to the local realm.")
        val name = "query-${++querySequence}"
        val runtime = runtimeFolder(context)
        try {
            LocalConfig.atomicWrite(File(runtime, "$name.sql"), sql)
            val command = TwwSession.argument(TwwSession.windows(p.mysql, p.detected.root, 'S')) +
                " --defaults-extra-file=R:\\runtime\\login.cnf --default-character-set=utf8mb4 --batch --skip-column-names $database " + TwwSession.input("R:\\runtime\\$name.sql")
            writeScript(context, name, TwwSession.script("S:\\", command, name))
            execute(name)
            waitExit(context, name, 60)
            File(runtime, "$name.log").readText()
        } finally {
            for (suffix in listOf("sql", "cmd", "log", "exit")) File(runtime, "$name.$suffix").delete()
        }
    }

    private suspend fun startNow(context: Context) {
        try {
            if (!WindowsRealmRegistry.reserve(this)) throw IOException("Close the active game's Windows session before starting this local realm.")
            val report = realm.report(context)
            val missing = listOf("Server Core" to report.core, "Server Database" to report.database, "Map Files" to report.maps)
                .filter { it.second.status != TwwInstall.Status.INSTALLED }
            if (!report.canBootstrap) throw IOException(missing.joinToString("\n") { (name, value) -> "$name: ${(value.missing + value.details).joinToString()}" }
                .ifBlank { "The server's startup requirements could not be verified." })
            // (the realm's settings first: they can change the ports the profile reads)
            report.detected?.worldConfig?.let { headlessWorld(it) }
            val p = readProfile(context)
            profile = p
            started.clear()
            knownCrashes = crashReports(p)
            for (port in p.ports) if (open(port)) throw IOException("Local port $port is already in use. Stop the server using it before starting this realm.")
            val runtime = runtimeFolder(context)
            for (name in listOf("database", "authentication", "world", "realm", "shutdown-db", "shutdown-world", "shutdown-auth", "probe", "client")) {
                File(runtime, "$name.exit").delete()
            }
            // (this start's logs only: an earlier run's, kept as *.previous.log, never read as this one's)
            for (name in LOGS) {
                val log = File(runtime, "$name.log")
                if (log.isFile) log.renameTo(File(runtime, "$name.previous.log"))
            }
            for ((role, command) in p.detected.commands) {
                val name = roleName(role)
                writeScript(context, name, TwwSession.launch(command, p.detected.root, name))
            }
            writeCredentials(context, p)
            mutableStatus.value = mutableStatus.value.copy(detail = "Preparing this realm's Windows runtime…")
            val container = realm.prepareRuntime(context)
            withContext(Dispatchers.IO) { host.start(context, container, File(runtime, "host.log")) }
            // (Wine with no screen, answering, before the database is trusted to it)
            mutableStatus.value = mutableStatus.value.copy(detail = "Starting Windows (Wine and Box64)…")
            writeScript(context, "probe", TwwSession.script("C:\\", "ver", "probe"))
            execute("probe")
            waitExit(context, "probe", 600)
            mutableStatus.value = mutableStatus.value.copy(database = ComponentStatus.STARTING, detail = "Starting ${p.detected.databaseExecutable?.name}…")
            startComponent("database")
            waitReady(context, "database", listOf(p.databasePort), 180)
            mutableStatus.value = mutableStatus.value.copy(database = ComponentStatus.RUNNING,
                detail = if (realm.spec.hasPinnedCore) "Checking the installed server release for build ${realm.spec.build}…"
                    else "Verifying build ${realm.spec.build} in the imported authentication database…")
            verifyServerBuild(context, p)
            setRealm(context, p)
            prepareDatabases(context, p)
            mutableStatus.value = mutableStatus.value.copy(authentication = ComponentStatus.STARTING, detail = "Starting ${p.detected.authenticationExecutable?.name}…")
            startComponent("authentication")
            waitReady(context, "authentication", p.loginPorts, 120, p)
            mutableStatus.value = mutableStatus.value.copy(authentication = ComponentStatus.RUNNING, world = ComponentStatus.STARTING, detail = "Starting ${p.detected.worldExecutable?.name}; initial database updates and map loading may take time…")
            startComponent("world")
            waitReady(context, "world", p.worldPorts, 3600, p)
            mutableStatus.value = mutableStatus.value.copy(phase = Phase.RUNNING, world = ComponentStatus.RUNNING,
                detail = "Database, authentication and world ports are accepting local connections. Client login has not yet been verified.", error = null)
            while (scope.isActive && !stopping) {
                delay(2000)
                val lost = listOf("database" to listOf(p.databasePort), "authentication" to p.loginPorts, "world" to p.worldPorts)
                    .firstOrNull { (name, ports) -> exitFile(context, name).exists() || ports.any { !open(it) } }
                if (lost != null) throw IOException("The ${lost.first} component stopped responding. ${processEnd(lost.first)}${logTail(context, lost.first)}")
                // (a server that crashes keeps its port: its crash report hangs, so the report is what tells)
                (crashReports(p) - knownCrashes).firstOrNull()?.let { report ->
                    knownCrashes = knownCrashes + report
                    throw IOException("A server crashed (its report: $report).\n" + sessionEnd(context, "world"))
                }
                if (clientLaunched && exitFile(context, "client").isFile) {
                    clientLaunched = false
                    val code = exitFile(context, "client").readText().trim()
                    mutableStatus.value = mutableStatus.value.copy(clientRunning = false,
                        clientError = if (code == "0") null else "The game exited with code $code. ${logTail(context, "client")}")
                }
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            mutableStatus.value = mutableStatus.value.copy(phase = Phase.FAILED, detail = null, error = error.message ?: error.javaClass.simpleName)
            // Keep the realm's Wine and observed running components available to Stop Server; never
            // claim a failed startup shut down a database that may still be saving data. A start that
            // failed before its Wine ran leaves nothing to stop: the runtime is free again.
            if (!host.running) WindowsRealmRegistry.release(this)
            WindowsRealmGame.cancelPending()
            refreshObserved()
        }
    }

    private fun readProfile(context: Context): Profile {
        val detected = realm.report(context).detected ?: throw IOException("The local server is missing.")
        if (detected.documentedBuild != null && detected.documentedBuild != realm.spec.build) {
            throw IOException("The repack documents build ${detected.documentedBuild}; expected ${realm.spec.build}.")
        }
        // Missing documentation permits database-only bootstrap. The live build_info query
        // below must succeed before the authentication/world executables can be launched.
        val startupIssues = detected.issues.filterNot { it == realm.spec.pendingBuildCheck }
        if (startupIssues.isNotEmpty()) throw IOException(startupIssues.joinToString("\n"))
        if (detected.commands.keys != TwwInstall.Role.entries.toSet()) throw IOException("The repack does not supply a complete unambiguous database/authentication/world startup profile.")
        val auth = ConfFile(detected.authenticationConfig?.readText() ?: throw IOException("Authentication configuration is missing."))
        val worldText = detected.worldConfig?.readText() ?: throw IOException("World configuration is missing.")
        val world = ConfFile(worldText)
        val dbConfig = detected.databaseConfig ?: throw IOException("Database configuration is missing.")
        val dbPort = port(iniValue(dbConfig.readText(), "mysqld", "port"), "database")
        val authPort = port(auth["BattlenetPort"] ?: auth["BnetServerPort"] ?: auth["RealmServerPort"], "authentication")
        val rest = (auth["LoginREST.Port"] ?: auth["REST.Port"])?.let { port(it, "REST authentication") }
        val worldPort = port(world["WorldServerPort"], "world")
        val instancePort = world["InstanceServerPort"]?.let { port(it, "instance") }
        val realmId = TwwSession.realmId(worldText)
        if (auth["BindIP"] != realm.address || world["BindIP"] != realm.address || iniValue(dbConfig.readText(), "mysqld", "bind-address") != realm.address) {
            throw IOException("Configure the server for localhost before starting it; original configurations will be backed up.")
        }
        val data = detected.dataDirectory ?: throw IOException("The world server's DataDir is unresolved.")
        if (File(data, "mmaps").list()?.isNotEmpty() != true) throw IOException("Install the additional MMAPS into the repack's detected DataDir first.")
        val login = (auth["LoginDatabaseInfo"] ?: world["LoginDatabaseInfo"] ?: "").split(';')
        if (login.size != 5 || login[0] != realm.address || login[1].toIntOrNull() != dbPort || !login[4].matches(Regex("[A-Za-z0-9_]+"))) {
            throw IOException("The authentication database connection is not verified for localhost and this database port.")
        }
        val mysql = detected.databaseProfile?.client
            ?: throw IOException("The active database's bin folder must contain one mysql.exe or mariadb.exe client to configure its realmlist.")
        val admin = detected.databaseProfile?.admin
            ?: throw IOException("The active database's bin folder must contain mysqladmin.exe or mariadb-admin.exe for safe database shutdown.")
        return Profile(detected, dbPort, listOfNotNull(authPort, rest).distinct(), worldPort, instancePort, realmId, mysql, admin, login)
    }

    private fun writeCredentials(context: Context, p: Profile) {
        val runtime = runtimeFolder(context)
        val credentials = fs(context).check(File(runtime, "login.cnf"))
        val user = p.login[2]
        val password = p.login[3]
        if (listOf(user, password).any { it.any { char -> char in "\r\n\u0000" } }) throw IOException("Database credentials contain unsupported control characters.")
        fun iniQuote(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        LocalConfig.atomicWrite(credentials, "[client]\nhost=127.0.0.1\nport=${p.databasePort}\nuser=${iniQuote(user)}\npassword=${iniQuote(password)}\n")
        credentials.setReadable(false, false); credentials.setWritable(false, false)
        credentials.setReadable(true, true); credentials.setWritable(true, true)
    }

    private suspend fun setRealm(context: Context, p: Profile) {
        val runtime = runtimeFolder(context)
        // (the repack's other realms, which the app doesn't run, out of the client's list: the
        // authentication server lists only the rows whose flag isn't 3; SPP's lists a "Haunted Lands")
        val sql = "UPDATE realmlist SET name='${realm.displayName.replace("'", "''")}', address='127.0.0.1', localAddress='127.0.0.1', port=${p.worldPort}, gamebuild=${realm.spec.build} WHERE id=${p.realmId};\n" +
            "UPDATE realmlist SET flag=3 WHERE id<>${p.realmId} AND flag<>3;\n" +
            "SELECT COUNT(*) FROM realmlist WHERE id=${p.realmId} AND address='127.0.0.1' AND gamebuild=${realm.spec.build};\n"
        LocalConfig.atomicWrite(File(runtime, "realm.sql"), sql)
        val cmd = TwwSession.argument(TwwSession.windows(p.mysql, p.detected.root, 'S')) +
            " --defaults-extra-file=R:\\runtime\\login.cnf --default-character-set=utf8mb4 --batch --skip-column-names ${p.login[4]} " + TwwSession.input("R:\\runtime\\realm.sql")
        writeScript(context, "realm", TwwSession.script("S:\\", cmd, "realm"))
        execute("realm")
        waitExit(context, "realm", 60)
        if (File(runtime, "realm.log").readLines().lastOrNull { it.isNotBlank() }?.trim() != "1") {
            throw IOException("The authentication database has no verified realm row ${p.realmId} for build ${realm.spec.build}; inspect runtime/realm.log.")
        }
    }

    /** A realmlist row is routing configuration, so changing its gamebuild cannot prove compatibility. */
    private suspend fun verifyServerBuild(context: Context, p: Profile) {
        if (realm.spec.hasPinnedCore) {
            if (p.detected.authenticationExecutable?.let { LocalConfig.sha256(it) } != realm.spec.authenticationSha256 ||
                p.detected.worldExecutable?.let { LocalConfig.sha256(it) } != realm.spec.worldSha256)
                throw IOException("The server executables no longer match the inspected build ${realm.spec.build} release.")
            return
        }
        val runtime = runtimeFolder(context)
        val proof = fs(context).check(File(p.detected.root, realm.spec.proofFile))
        if (proof.exists() && !proof.delete()) throw IOException("The previous build verification record could not be invalidated.")
        LocalConfig.atomicWrite(File(runtime, "build-check.sql"), realm.spec.buildQuery())
        val command = TwwSession.argument(TwwSession.windows(p.mysql, p.detected.root, 'S')) +
            " --defaults-extra-file=R:\\runtime\\login.cnf --default-character-set=utf8mb4 --batch --skip-column-names ${p.login[4]} " + TwwSession.input("R:\\runtime\\build-check.sql")
        writeScript(context, "build-check", TwwSession.script("S:\\", command, "build-check"))
        execute("build-check")
        waitExit(context, "build-check", 60)
        val count = File(runtime, "build-check.log").readLines().lastOrNull { it.isNotBlank() }?.trim()?.toLongOrNull()
        if (count == null || count < 1) {
            throw IOException("The imported authentication database's build_info table does not support build ${realm.spec.build}. No realmlist build was changed and authentication/world were not started.")
        }
        val authentication = p.detected.authenticationExecutable ?: throw IOException("Authentication executable is missing.")
        val world = p.detected.worldExecutable ?: throw IOException("World executable is missing.")
        LocalConfig.atomicWrite(proof, "build=${realm.spec.build}\n" +
            "authenticationSha256=${LocalConfig.sha256(authentication)}\n" +
            "worldSha256=${LocalConfig.sha256(world)}\n" +
            "source=${realm.spec.proofSource}\n")
    }

    private suspend fun stopNow(context: Context) {
        val p = profile
        WindowsRealmGame.cancelPending()
        // (the game first: its session ends; the realm's processes are apart from it)
        closeDisplaySession()
        if (p == null || !host.running) {
            if (p != null && p.ports.any { open(it) }) {
                mutableStatus.value = mutableStatus.value.copy(phase = Phase.FAILED,
                    error = "A server port remains open, but the realm's Windows runtime isn't running. Stop the remaining process before replacing installation files.")
                refreshObserved()
                return
            }
            endRuntime()
            profile = null
            started.clear()
            File(layout(context).root, "runtime/login.cnf").delete()
            mutableStatus.value = Status()
            return
        }
        mutableStatus.value = mutableStatus.value.copy(phase = Phase.STOPPING, detail = "Stopping the world and authentication servers, then shutting the database down…", error = null)
        try {
            // The world and authentication servers are console programs without a window, which
            // Wine's taskkill can only end with /F ("Could not find process" without it). The world
            // server saves characters at logout and every few minutes; the database, which holds
            // everything, is shut down cleanly after them (mysqladmin).
            for ((name, executable, ports) in listOf(
                Triple("shutdown-world", p.detected.worldExecutable, p.worldPorts),
                Triple("shutdown-auth", p.detected.authenticationExecutable, p.loginPorts),
            )) {
                val component = name.removePrefix("shutdown-").let { if (it == "auth") "authentication" else it }
                if (component !in started && ports.none { open(it) }) continue
                val exe = executable ?: continue
                val command = "taskkill /F /IM ${TwwSession.argument(exe.name)}"
                writeScript(context, name, TwwSession.script("S:\\", command, name))
                execute(name)
                val deadline = System.currentTimeMillis() + 30_000
                while (ports.any { open(it) } || (component in started && !exitFile(context, component).isFile && host.exitStatus(component) == null)) {
                    if (System.currentTimeMillis() >= deadline) throw IOException("${exe.name} did not stop. Its session is retained; use Force Stop.")
                    delay(500)
                }
            }
            if ("database" in started && !exitFile(context, "database").isFile) {
                val readyDeadline = System.currentTimeMillis() + 90_000
                while (!open(p.databasePort) && !exitFile(context, "database").isFile && host.exitStatus("database") == null) {
                    if (System.currentTimeMillis() >= readyDeadline) throw IOException("The database is still initializing and cannot yet accept a safe shutdown request. Its Windows session was retained.")
                    delay(500)
                }
            }
            if (open(p.databasePort)) {
                val command = TwwSession.argument(TwwSession.windows(p.mysqlAdmin, p.detected.root, 'S')) + " --defaults-extra-file=R:\\runtime\\login.cnf shutdown"
                writeScript(context, "shutdown-db", TwwSession.script("S:\\", command, "shutdown-db"))
                execute("shutdown-db")
                waitExit(context, "shutdown-db", 90)
                val deadline = System.currentTimeMillis() + 90_000
                while (open(p.databasePort) || (!exitFile(context, "database").isFile && host.exitStatus("database") == null)) {
                    if (System.currentTimeMillis() >= deadline) throw IOException("The database is still running after its shutdown request. Its session was retained; inspect runtime/shutdown-db.log.")
                    delay(500)
                }
            }
            val outcome = shutdownResult(context)
            endRuntime()
            File(layout(context).root, "runtime/login.cnf").delete()
            profile = null
            started.clear()
            mutableStatus.value = outcome
        } catch (error: Exception) {
            mutableStatus.value = mutableStatus.value.copy(phase = Phase.FAILED, detail = null, error = error.message ?: error.javaClass.simpleName)
            refreshObserved()
        }
    }

    /**
     * Closed ports establish that a process stopped, not that it saved and exited cleanly. The
     * world and authentication servers are stopped by force (taskkill /F: their exit code is 1);
     * any other code (an access violation on the way out) is reported.
     */
    internal fun shutdownResult(context: Context): Status {
        val failures = listOf("world", "authentication", "database").mapNotNull { name ->
            val exit = exitFile(context, name)
            if (!exit.isFile) return@mapNotNull null
            val code = exit.readText().trim()
            val expected = if (name == "database") setOf("0") else setOf("0", "1")
            if (code in expected) null else "$name exited with code $code during shutdown. Check runtime/$name.log before restarting; a clean save has not been verified."
        }
        return Status(detail = if (failures.isEmpty()) null else "Server processes stopped with errors.",
            error = failures.takeIf { it.isNotEmpty() }?.joinToString("\n"))
    }

    /** The game's session closed (its screen finished; its own programs end with it), the realm left running. */
    private suspend fun closeDisplaySession() {
        val current = session.get()?.takeUnless { it.isDestroyed } ?: return
        withContext(Dispatchers.Main) { current.finish() }
        val deadline = System.currentTimeMillis() + 15_000
        while (!current.isDestroyed && System.currentTimeMillis() < deadline) delay(100)
        if (session.get() === current) session.clear()
        clientLaunched = false
        mutableStatus.value = mutableStatus.value.copy(clientRunning = false)
    }

    /** The realm's Wine ended (its server and every program left of it), the runtime released. */
    private suspend fun endRuntime() {
        if (!withContext(Dispatchers.IO) { host.stop() })
            throw IOException("Some of the realm's Windows processes did not end; installation files remain locked. Use Force Stop.")
        WindowsRealmRegistry.release(this)
    }

    private fun execute(name: String) {
        if (!host.running) throw IOException("The realm's Windows runtime is not running.")
        host.run(name)
    }

    private fun startComponent(name: String) {
        started += name
        execute(name)
    }

    private fun writeScript(context: Context, name: String, script: String) {
        val runtime = runtimeFolder(context)
        File(runtime, "$name.exit").delete()
        LocalConfig.atomicWrite(File(runtime, "$name.cmd"), script)
    }

    private suspend fun waitReady(context: Context, name: String, ports: List<Int>, seconds: Int, profile: Profile? = null) {
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(seconds.toLong())
        while (true) {
            if (!host.running) throw IOException("The realm's Windows runtime stopped while starting $name.\n" + sessionEnd(context, name))
            if (exitFile(context, name).isFile) throw IOException("$name exited with code ${exitFile(context, name).readText().trim()}. ${logTail(context, name)}")
            host.exitStatus(name)?.let { throw IOException("$name ended while starting. ${processEnd(name)}\n" + sessionEnd(context, name)) }
            if (profile != null) (crashReports(profile) - knownCrashes).firstOrNull()?.let { report ->
                knownCrashes = knownCrashes + report
                throw IOException("$name crashed while starting (its report: $report).\n" + sessionEnd(context, name))
            }
            if (ports.all { open(it) }) return
            if (System.currentTimeMillis() >= deadline) throw IOException("$name did not open its configured local ports before the timeout. ${logTail(context, name)}")
            delay(1000)
        }
    }

    private suspend fun waitExit(context: Context, name: String, seconds: Int) {
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(seconds.toLong())
        val exit = exitFile(context, name)
        while (!exit.isFile) {
            // (its process ended without the exit file: killed, or Wine itself failed; the file can trail it a moment)
            if (host.exitStatus(name) != null) {
                delay(1000)
                if (!exit.isFile) throw IOException("$name did not complete. ${processEnd(name)}\n" + sessionEnd(context, name))
            }
            if (!host.running || System.currentTimeMillis() >= deadline) throw IOException("$name did not complete. ${logTail(context, name)}\n" + sessionEnd(context, name))
            delay(250)
        }
        if (exit.readText().trim() != "0") throw IOException("$name failed with code ${exit.readText().trim()}. ${logTail(context, name)}")
    }

    private fun refreshObserved() {
        val p = profile ?: return
        fun observed(ports: List<Int>) = if (ports.all { open(it) }) ComponentStatus.RUNNING else ComponentStatus.FAILED
        mutableStatus.value = mutableStatus.value.copy(database = observed(listOf(p.databasePort)), authentication = observed(p.loginPorts), world = observed(p.worldPorts))
    }

    private fun layout(context: Context) = LocalInstallation(realm.root(context))
    private fun fs(context: Context) = com.logie.warcraft.turtle.core.SafeFs(realm.root(context))

    private fun runtimeFolder(context: Context): File {
        val fs = fs(context)
        val folder = fs.check(File(layout(context).root, "runtime"))
        fs.mkdirs(folder)
        return folder
    }

    /**
     * The crash reports the servers have written (TrinityCore's, in a Crashes folder beside its
     * programs): a server that crashes under Wine hangs writing its report and never exits, so a
     * new report is how its crash shows.
     */
    private fun crashReports(p: Profile): Set<String> =
        listOfNotNull(p.detected.worldExecutable, p.detected.authenticationExecutable).mapNotNull { it.parentFile }.distinct()
            .flatMap { dir -> File(dir, "Crashes").list()?.filter { it.endsWith(".txt", true) }?.map { "${dir.name}/Crashes/$it" }.orEmpty() }
            .toSet()

    private fun exitFile(context: Context, name: String) = fs(context).check(File(runtimeFolder(context), "$name.exit"))
    private fun logTail(context: Context, name: String): String = runCatching {
        File(layout(context).root, "runtime/$name.log").readLines().takeLast(12).joinToString("\n").takeLast(3000)
    }.getOrDefault("No component log was produced.")

    /** How [name]'s Windows process ended, when it did without its exit file (killed: a signal). */
    private fun processEnd(name: String): String {
        val status = host.exitStatus(name) ?: return ""
        return if (status > 128) "Its process was ended by signal ${status - 128}${if (status == 137) " (killed: by Android for memory, or by force)" else ""}. "
            else "Its process ended with status $status before writing its exit code. "
    }

    /**
     * Why a component failed: its own log, then the realm's Wine and Box64 output (runtime/host.log),
     * so a failure on a phone explains itself.
     */
    internal fun sessionEnd(context: Context, name: String): String {
        val component = runCatching { File(layout(context).root, "runtime/$name.log").readLines().filter { it.isNotBlank() }.takeLast(8) }.getOrDefault(emptyList())
        val wine = runCatching { File(layout(context).root, "runtime/host.log").readLines().filter { it.isNotBlank() }.takeLast(14) }.getOrDefault(emptyList())
        return buildString {
            append(if (component.isEmpty()) "$name.log: nothing written." else "$name.log:\n" + component.joinToString("\n"))
            append("\n")
            append(if (wine.isEmpty()) "host.log: nothing written." else "host.log:\n" + wine.joinToString("\n"))
        }.takeLast(4000)
    }

    /** The realm's name, as the app shows it. */
    val displayName: String get() = realm.displayName

    /** The ends of this start's Windows logs (its servers', its Wine's, the game's session), for a failure's report. */
    fun runtimeLogs(context: Context): String = listOf("database", "authentication", "world", "host", "session").joinToString("\n\n") { name ->
        val lines = runCatching { File(layout(context).root, "runtime/$name.log").readLines().filter { it.isNotBlank() }.takeLast(60) }.getOrDefault(emptyList())
        if (lines.isEmpty()) "$name.log: nothing written." else "$name.log:\n" + lines.joinToString("\n")
    }

    private companion object {
        /** The logs a start writes anew (the earlier run's kept beside them as *.previous.log). */
        val LOGS = listOf("database", "authentication", "world", "host", "session", "client", "probe")
    }

    private fun roleName(role: TwwInstall.Role) = when (role) {
        TwwInstall.Role.DATABASE -> "database"
        TwwInstall.Role.AUTHENTICATION -> "authentication"
        TwwInstall.Role.WORLD -> "world"
    }

    private fun open(port: Int): Boolean = runCatching { Socket().use { it.connect(InetSocketAddress(realm.address, port), 300) } }.isSuccess
    private fun port(value: String?, role: String): Int = value?.toIntOrNull()?.takeIf { it in 1..65535 }
        ?: throw IOException("The $role port is not explicitly configured in the repack.")

    private fun replaceConfig(file: File, text: String) { LocalConfig.backup(file); LocalConfig.atomicWrite(file, text) }

    private fun iniValue(text: String, section: String, key: String): String? {
        var active = false
        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("[")) active = trimmed.equals("[$section]", true)
            else if (active && !trimmed.startsWith("#") && !trimmed.startsWith(";") && trimmed.substringBefore('=').trim().equals(key, true)) {
                return trimmed.substringAfter('=', "").trim().removeSurrounding("\"")
            }
        }
        return null
    }

    private fun setIni(text: String, section: String, key: String, value: String): String {
        val lines = text.lines().toMutableList()
        val begin = lines.indexOfFirst { it.trim().equals("[$section]", true) }
        if (begin < 0) throw IOException("Missing [$section] in the database configuration.")
        val end = ((begin + 1) until lines.size).firstOrNull { lines[it].trim().startsWith("[") } ?: lines.size
        val index = ((begin + 1) until end).firstOrNull { lines[it].trim().substringBefore('=').trim().equals(key, true) }
        if (index != null) lines[index] = "$key=$value" else lines.add(end, "$key=$value")
        return lines.joinToString("\n")
    }
}
