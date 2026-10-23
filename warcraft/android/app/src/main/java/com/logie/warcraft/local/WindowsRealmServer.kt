// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.local

import android.content.Context
import android.content.Intent
import com.logie.warcraft.MainActivity
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
 * Downloaded local realms' real Windows processes inside one Winlator session. The session stays alive when
 * its launcher is in front; the client joins the same session. Starting another Winlator guest
 * would kill the first one, so each command goes through that session's existing WinHandler.
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
    )

    private val mutableStatus = MutableStateFlow(Status())
    val status: StateFlow<Status> = mutableStatus
    val isBusy: Boolean get() = mutableStatus.value.let {
        ownsSession || it.phase in setOf(Phase.STARTING, Phase.RUNNING, Phase.STOPPING) ||
            listOf(it.database, it.authentication, it.world).any { component -> component == ComponentStatus.RUNNING }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val queryLock = Mutex()
    private var querySequence = 0L
    private var job: Job? = null
    @Volatile private var activity = WeakReference<XServerDisplayActivity>(null)
    @Volatile private var stopping = false
    // A database can still be initializing after its startup timeout without listening yet.
    // Keep installation writes locked for the lifetime of the actual Windows session.
    @Volatile private var ownsSession = false
    @Volatile private var profile: Profile? = null
    val authenticationDatabase: String? get() = profile?.login?.getOrNull(4)
    private val started = mutableSetOf<String>()

    private data class Profile(
        val detected: TwwInstall.DetectedServer,
        val databasePort: Int,
        val loginPorts: List<Int>,
        val worldPort: Int,
        val realmId: Int,
        val mysql: File,
        val mysqlAdmin: File,
        val login: List<String>,
    ) {
        val ports get() = listOf(databasePort) + loginPorts + worldPort
    }

    init { WindowsRealmRegistry.register(this, realm.sessionExtra) }

    internal fun onRuntimeAttached(runtime: XServerDisplayActivity) {
        activity = WeakReference(runtime)
        ownsSession = true
    }

    internal fun onRuntimeDetached(runtime: XServerDisplayActivity) {
        if (activity.get() !== runtime) return
        activity.clear()
        if (!stopping && isBusy) {
            mutableStatus.value = mutableStatus.value.copy(phase = Phase.FAILED,
                error = "The Windows session ended. Checking whether any server ports remain open…")
            scope.launch {
                delay(1000)
                val p = profile
                if (runtime.isDestroyed && (p == null || p.ports.none { open(it) })) ownsSession = false
                refreshObserved()
            }
        }
    }

    fun start(context: Context) {
        if (isBusy || realm.installationBusy) return
        val app = context.applicationContext
        stopping = false
        mutableStatus.value = Status(phase = Phase.STARTING, detail = "Checking the imported repack and its startup requirements…")
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
                val p = profile
                if (p != null) {
                    for (file in listOfNotNull(p.detected.worldExecutable, p.detected.authenticationExecutable, p.detected.databaseExecutable)) {
                        activity.get()?.winHandler?.killProcess(file.name)
                    }
                    val deadline = System.currentTimeMillis() + 15_000
                    while (p.ports.any { open(it) } && System.currentTimeMillis() < deadline) delay(250)
                }
                closeSession()
                if (p != null && p.ports.any { open(it) }) {
                    mutableStatus.value = mutableStatus.value.copy(phase = Phase.FAILED,
                        error = "Forced termination was requested, but a server port is still open. Installation files remain locked; close the Windows session and inspect the component logs.")
                    refreshObserved()
                    return@withLock
                }
                profile = null
                started.clear()
                File(layout(context).root, "runtime/login.cnf").delete()
                mutableStatus.value = Status(detail = "Windows processes were forcibly stopped. Verify the database before starting again.")
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
            replaceConfig(file, conf.toString())
        }
        // Revalidate the resulting profile before recording completion.
        readProfile(context)
    }

    fun launchGame(context: Context) {
        val app = context.applicationContext
        scope.launch {
            try {
                if (realm.installationBusy) throw IOException("Wait for the current installation job before launching the client.")
                if (mutableStatus.value.phase != Phase.RUNNING) throw IOException("Start the local server and wait for every component to be ready first.")
                if (!realm.localClientConfigured(app)) throw IOException("Configure the local client connection and review the Firestorm warning first.")
                val report = realm.report(app)
                val executable = report.clientExecutable ?: throw IOException("The client executable is missing.")
                if (report.client.status != TwwInstall.Status.INSTALLED || report.clientBuild?.build != realm.spec.build)
                    throw IOException("The installed client is incomplete or does not match build ${realm.spec.build}.")
                val layout = layout(app)
                realm.prepareClientLaunch(app)
                val command = TwwSession.argument(TwwSession.windows(executable, layout.client, 'D'))
                writeScript(app, "client", TwwSession.script(TwwSession.windows(executable.parentFile, layout.client, 'D'), command, "client"))
                execute("client")
                withContext(Dispatchers.Main) { realm.showSession(app) }
                mutableStatus.value = mutableStatus.value.copy(detail = "Client launch requested. Connection compatibility is confirmed only after the client logs in; see runtime/client.log if it exits.", error = null)
            } catch (error: Exception) {
                mutableStatus.value = mutableStatus.value.copy(error = error.message ?: error.javaClass.simpleName)
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
                " --defaults-extra-file=R:\\runtime\\login.cnf --default-character-set=utf8mb4 --batch --skip-column-names $database <\"R:\\runtime\\$name.sql\""
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
            if (!WindowsRealmRegistry.canOpen(this)) throw IOException("Close the active game's Windows session before starting this local realm.")
            val report = realm.report(context)
            val missing = listOf("Server Core" to report.core, "Server Database" to report.database, "Map Files" to report.maps)
                .filter { it.second.status != TwwInstall.Status.INSTALLED }
            if (!report.canBootstrap) throw IOException(missing.joinToString("\n") { (name, value) -> "$name: ${(value.missing + value.details).joinToString()}" }
                .ifBlank { "The server's startup requirements could not be verified." })
            val p = readProfile(context)
            profile = p
            started.clear()
            for (port in p.ports) if (open(port)) throw IOException("Local port $port is already in use. Stop the server using it before starting this realm.")
            val runtime = runtimeFolder(context)
            for (name in listOf("database", "authentication", "world", "realm", "shutdown-db", "shutdown-world", "shutdown-auth")) {
                File(runtime, "$name.exit").delete()
            }
            for ((role, command) in p.detected.commands) {
                val name = roleName(role)
                writeScript(context, name, TwwSession.launch(command, p.detected.root, name))
            }
            writeCredentials(context, p)
            mutableStatus.value = mutableStatus.value.copy(detail = "Opening this realm's Windows runtime…")
            realm.openSession(context)
            val deadline = System.currentTimeMillis() + 120_000
            while (activity.get() == null) {
                if (System.currentTimeMillis() >= deadline) throw IOException("The Windows session did not start. Check the Winlator runtime installation.")
                delay(250)
            }
            withContext(Dispatchers.Main) {
                context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }
            mutableStatus.value = mutableStatus.value.copy(database = ComponentStatus.STARTING, detail = "Starting ${p.detected.databaseExecutable?.name}…")
            startComponent("database")
            waitReady(context, "database", listOf(p.databasePort), 180)
            mutableStatus.value = mutableStatus.value.copy(database = ComponentStatus.RUNNING,
                detail = if (realm.spec.hasPinnedCore) "Checking the installed server release for build ${realm.spec.build}…"
                    else "Verifying build ${realm.spec.build} in the imported authentication database…")
            verifyServerBuild(context, p)
            setRealm(context, p)
            mutableStatus.value = mutableStatus.value.copy(authentication = ComponentStatus.STARTING, detail = "Starting ${p.detected.authenticationExecutable?.name}…")
            startComponent("authentication")
            waitReady(context, "authentication", p.loginPorts, 120)
            mutableStatus.value = mutableStatus.value.copy(authentication = ComponentStatus.RUNNING, world = ComponentStatus.STARTING, detail = "Starting ${p.detected.worldExecutable?.name}; initial database updates and map loading may take time…")
            startComponent("world")
            waitReady(context, "world", listOf(p.worldPort), 3600)
            mutableStatus.value = mutableStatus.value.copy(phase = Phase.RUNNING, world = ComponentStatus.RUNNING,
                detail = "Database, authentication and world ports are accepting local connections. Client login has not yet been verified.", error = null)
            withContext(Dispatchers.Main) {
                context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }
            while (scope.isActive && !stopping) {
                delay(2000)
                val lost = listOf("database" to listOf(p.databasePort), "authentication" to p.loginPorts, "world" to listOf(p.worldPort))
                    .firstOrNull { (name, ports) -> exitFile(context, name).exists() || ports.any { !open(it) } }
                if (lost != null) throw IOException("The ${lost.first} component stopped responding. ${logTail(context, lost.first)}")
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            mutableStatus.value = mutableStatus.value.copy(phase = Phase.FAILED, detail = null, error = error.message ?: error.javaClass.simpleName)
            // Keep the session and observed running components available to Stop Server; never
            // claim a failed startup shut down a database that may still be saving data.
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
        return Profile(detected, dbPort, listOfNotNull(authPort, rest).distinct(), worldPort, realmId, mysql, admin, login)
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
        val sql = "UPDATE realmlist SET name='${realm.displayName.replace("'", "''")}', address='127.0.0.1', localAddress='127.0.0.1', port=${p.worldPort}, gamebuild=${realm.spec.build} WHERE id=${p.realmId};\nSELECT COUNT(*) FROM realmlist WHERE id=${p.realmId} AND address='127.0.0.1' AND gamebuild=${realm.spec.build};\n"
        LocalConfig.atomicWrite(File(runtime, "realm.sql"), sql)
        val cmd = TwwSession.argument(TwwSession.windows(p.mysql, p.detected.root, 'S')) +
            " --defaults-extra-file=R:\\runtime\\login.cnf --default-character-set=utf8mb4 --batch --skip-column-names ${p.login[4]} <\"R:\\runtime\\realm.sql\""
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
            " --defaults-extra-file=R:\\runtime\\login.cnf --default-character-set=utf8mb4 --batch --skip-column-names ${p.login[4]} <\"R:\\runtime\\build-check.sql\""
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
        if (activity.get() == null && ownsSession) {
            mutableStatus.value = mutableStatus.value.copy(phase = Phase.FAILED,
                error = "The Windows session has not confirmed shutdown. Installed files remain locked, even if its database has not opened a port yet.")
            refreshObserved()
            return
        }
        if (p != null && activity.get() == null && p.ports.any { open(it) }) {
            mutableStatus.value = mutableStatus.value.copy(phase = Phase.FAILED,
                error = "A server port remains open after its Windows session ended. Stop the remaining process before replacing installation files.")
            refreshObserved()
            return
        }
        if (p == null || activity.get() == null) {
            closeSession()
            profile = null
            started.clear()
            File(layout(context).root, "runtime/login.cnf").delete()
            mutableStatus.value = Status()
            return
        }
        mutableStatus.value = mutableStatus.value.copy(phase = Phase.STOPPING, detail = "Requesting world shutdown, then authentication and database shutdown…", error = null)
        try {
            for ((name, executable, ports) in listOf(
                Triple("shutdown-world", p.detected.worldExecutable, listOf(p.worldPort)),
                Triple("shutdown-auth", p.detected.authenticationExecutable, p.loginPorts),
            )) {
                val component = name.removePrefix("shutdown-").let { if (it == "auth") "authentication" else it }
                if (component !in started && ports.none { open(it) }) continue
                val exe = executable ?: continue
                val command = "taskkill /IM ${TwwSession.argument(exe.name)}"
                writeScript(context, name, TwwSession.script("S:\\", command, name))
                execute(name)
                val deadline = System.currentTimeMillis() + 30_000
                while (ports.any { open(it) } || !exitFile(context, component).isFile) {
                    if (System.currentTimeMillis() >= deadline) throw IOException("${exe.name} did not close after the normal Windows shutdown request. Its session is retained. Use Force Stop only if you accept possible unsaved data.")
                    delay(500)
                }
            }
            if ("database" in started && !exitFile(context, "database").isFile) {
                val readyDeadline = System.currentTimeMillis() + 90_000
                while (!open(p.databasePort) && !exitFile(context, "database").isFile) {
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
                while (open(p.databasePort) || !exitFile(context, "database").isFile) {
                    if (System.currentTimeMillis() >= deadline) throw IOException("The database is still running after its shutdown request. Its session was retained; inspect runtime/shutdown-db.log.")
                    delay(500)
                }
            }
            val outcome = shutdownResult(context)
            closeSession()
            File(layout(context).root, "runtime/login.cnf").delete()
            profile = null
            started.clear()
            mutableStatus.value = outcome
        } catch (error: Exception) {
            mutableStatus.value = mutableStatus.value.copy(phase = Phase.FAILED, detail = null, error = error.message ?: error.javaClass.simpleName)
            refreshObserved()
        }
    }

    /** Closed ports establish that a process stopped, not that it saved and exited cleanly. */
    internal fun shutdownResult(context: Context): Status {
        val failures = listOf("world", "authentication", "database").mapNotNull { name ->
            val exit = exitFile(context, name)
            if (!exit.isFile) return@mapNotNull null
            val code = exit.readText().trim()
            if (code == "0") null else "$name exited with code $code during shutdown. Check runtime/$name.log before restarting; a clean save has not been verified."
        }
        return Status(detail = if (failures.isEmpty()) null else "Server processes stopped with errors.",
            error = failures.takeIf { it.isNotEmpty() }?.joinToString("\n"))
    }

    private suspend fun closeSession() {
        val current = activity.get() ?: run {
            if (ownsSession) throw IOException("The Windows session has not confirmed shutdown; installation files remain locked.")
            return
        }
        withContext(Dispatchers.Main) { current.finish() }
        val deadline = System.currentTimeMillis() + 15_000
        while (!current.isDestroyed) {
            if (System.currentTimeMillis() >= deadline) throw IOException("The Windows session did not finish closing; installation files remain locked.")
            delay(100)
        }
        activity.clear()
        ownsSession = false
    }

    private fun execute(name: String) {
        val current = activity.get() ?: throw IOException("The realm's Windows session ended.")
        current.winHandler.exec("cmd.exe", "/d /s /c \"\"R:\\runtime\\$name.cmd\"\"")
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

    private suspend fun waitReady(context: Context, name: String, ports: List<Int>, seconds: Int) {
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(seconds.toLong())
        while (true) {
            if (activity.get() == null) throw IOException("The Windows session ended while starting $name.\n" + sessionEnd(context, name))
            if (exitFile(context, name).isFile) throw IOException("$name exited with code ${exitFile(context, name).readText().trim()}. ${logTail(context, name)}")
            if (ports.all { open(it) }) return
            if (System.currentTimeMillis() >= deadline) throw IOException("$name did not open its configured local ports before the timeout. ${logTail(context, name)}")
            delay(1000)
        }
    }

    private suspend fun waitExit(context: Context, name: String, seconds: Int) {
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(seconds.toLong())
        val exit = exitFile(context, name)
        while (!exit.isFile) {
            if (activity.get() == null || System.currentTimeMillis() >= deadline) throw IOException("$name did not complete. ${logTail(context, name)}")
            delay(250)
        }
        if (exit.readText().trim() != "0") throw IOException("$name failed with code ${exit.readText().trim()}. ${logTail(context, name)}")
    }

    private fun refreshObserved() {
        val p = profile ?: return
        fun observed(ports: List<Int>) = if (ports.all { open(it) }) ComponentStatus.RUNNING else ComponentStatus.FAILED
        mutableStatus.value = mutableStatus.value.copy(database = observed(listOf(p.databasePort)), authentication = observed(p.loginPorts), world = observed(listOf(p.worldPort)))
    }

    private fun layout(context: Context) = LocalInstallation(realm.root(context))
    private fun fs(context: Context) = com.logie.warcraft.turtle.core.SafeFs(realm.root(context))

    private fun runtimeFolder(context: Context): File {
        val fs = fs(context)
        val folder = fs.check(File(layout(context).root, "runtime"))
        fs.mkdirs(folder)
        return folder
    }

    private fun exitFile(context: Context, name: String) = fs(context).check(File(runtimeFolder(context), "$name.exit"))
    private fun logTail(context: Context, name: String): String = runCatching {
        File(layout(context).root, "runtime/$name.log").readLines().takeLast(12).joinToString("\n").takeLast(3000)
    }.getOrDefault("No component log was produced.")

    /**
     * Why a Windows session ended: the component's own log, then the runtime's (Wine and Box64's
     * output and its exit status, runtime/session.log), so a failure on a phone explains itself.
     */
    internal fun sessionEnd(context: Context, name: String): String {
        val component = runCatching { File(layout(context).root, "runtime/$name.log").readLines().filter { it.isNotBlank() }.takeLast(8) }.getOrDefault(emptyList())
        val session = runCatching { File(layout(context).root, "runtime/session.log").readLines().filter { it.isNotBlank() }.takeLast(14) }.getOrDefault(emptyList())
        return buildString {
            append(if (component.isEmpty()) "$name.log: nothing written." else "$name.log:\n" + component.joinToString("\n"))
            append("\n")
            append(if (session.isEmpty()) "session.log: nothing written." else "session.log:\n" + session.joinToString("\n"))
        }.takeLast(4000)
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
