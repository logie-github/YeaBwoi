// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.tww

import android.content.Context
import android.content.Intent
import com.logie.warcraft.MainActivity
import com.logie.warcraft.config.ConfFile
import com.logie.warcraft.tww.core.TwwInstall
import com.logie.warcraft.tww.core.TwwRealm
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
 * Draconic's real Windows processes inside one Winlator session. The session stays alive when
 * its launcher is in front; the client joins the same session. Starting another Winlator guest
 * would kill the first one, so each command goes through that session's existing WinHandler.
 */
object TwwServer {
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
        it.phase in setOf(Phase.STARTING, Phase.RUNNING, Phase.STOPPING) ||
            listOf(it.database, it.authentication, it.world).any { component -> component == ComponentStatus.RUNNING }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var job: Job? = null
    @Volatile private var activity = WeakReference<XServerDisplayActivity>(null)
    @Volatile private var otherActivity = WeakReference<XServerDisplayActivity>(null)
    @Volatile private var stopping = false
    @Volatile private var profile: Profile? = null
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

    /** Called from the tiny lifecycle patch to the pinned Winlator Activity. */
    @JvmStatic fun runtimeAttached(runtime: XServerDisplayActivity) {
        if (runtime.intent.getBooleanExtra(TwwGame.SESSION_EXTRA, false)) activity = WeakReference(runtime)
        else otherActivity = WeakReference(runtime)
    }

    /** Runs before Winlator changes its shared Wine home, so another launch cannot kill a database. */
    @JvmStatic fun allowRuntimeStart(runtime: XServerDisplayActivity): Boolean {
        val ours = activity.get()
        val other = otherActivity.get()
        return if (runtime.intent.getBooleanExtra(TwwGame.SESSION_EXTRA, false)) {
            (ours == null || ours === runtime) && other == null
        } else ours == null || ours === runtime
    }

    @JvmStatic fun runtimeDetached(runtime: XServerDisplayActivity) {
        if (otherActivity.get() === runtime) otherActivity.clear()
        if (activity.get() !== runtime) return
        activity.clear()
        if (!stopping && isBusy) {
            mutableStatus.value = mutableStatus.value.copy(phase = Phase.FAILED,
                error = "The Windows session ended. Checking whether any server ports remain open…")
            scope.launch { delay(1000); refreshObserved() }
        }
    }

    fun start(context: Context) {
        if (isBusy || TwwJobs.busy) return
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
                File(TwwFiles.layout(context).root, "runtime/login.cnf").delete()
                mutableStatus.value = Status(detail = "Windows processes were forcibly stopped. Verify the database before starting again.")
            }
        }
    }

    /**
     * Configures only the imported repack. Values come from its actual configurations and
     * initialized database folders. Original text files are kept as .before-local backups.
     */
    fun configureLocal(context: Context) {
        if (isBusy || TwwJobs.busy) throw IOException("Stop this realm and wait for its installation job before changing configuration.")
        val detected = TwwFiles.report(context).detected ?: throw IOException("Import the Draconic server repack first.")
        val world = detected.worldConfig ?: throw IOException("The world server configuration is missing.")
        val auth = detected.authenticationConfig ?: throw IOException("The authentication server configuration is missing.")
        val database = detected.databaseConfig ?: throw IOException("The database configuration is missing.")
        val mysql = detected.databaseExecutable ?: throw IOException("The database executable is missing.")
        val basedir = mysql.parentFile.takeIf { it.name.equals("bin", true) }?.parentFile
            ?: throw IOException("The database's installation directory could not be established from ${mysql.name}.")
        val controllerRoot = basedir.parentFile?.parentFile?.takeIf { directory ->
            directory.listFiles()?.any { it.isFile && it.name.equals("UniController.exe", true) } == true
        }
        val controllerUser = controllerRoot?.let { File(it, "home/us_config/us_user.ini") }?.takeIf { it.isFile }
        val configuredPort = iniValue(database.readText(), "mysqld", "port")
            ?: controllerUser?.let { iniValue(it.readText(), "USER", "MYSQL_TCP_PORT") }
        val databasePort = configuredPort?.toIntOrNull()?.takeIf { it in 1..65535 }
            ?: throw IOException("The repack's database port is unverified in my.ini or UniController's us_user.ini.")
        val dataCandidates = detected.root.walkTopDown().filter { directory ->
            directory.isDirectory && !java.nio.file.Files.isSymbolicLink(directory.toPath()) &&
                (directory.listFiles()?.any { it.isDirectory && it.name.equals("mysql", true) } == true || File(directory, "mysql.ibd").isFile) &&
                (File(directory, "ibdata1").isFile || File(directory, "aria_log_control").isFile || File(directory, "auto.cnf").isFile)
        }.toList()
        val datadir = dataCandidates.singleOrNull()
            ?: throw IOException("The repack must contain one initialized database data directory; found ${dataCandidates.size}.")
        val replacement = mutableMapOf(
            "port" to databasePort.toString(),
            "bind-address" to TwwRealm.ADDRESS,
            "basedir" to TwwSession.windows(basedir, detected.root, 'S').replace('\\', '/'),
            "datadir" to TwwSession.windows(datadir, detected.root, 'S').replace('\\', '/'),
        )
        // UniController normally supplies these directory overrides on the command line.
        // Keep them explicit in the managed configuration when starting its actual daemon.
        if (controllerRoot != null) {
            val temporary = TwwFiles.fs(context).mkdirs(File(controllerRoot, "tmp"))
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
            conf.setText("BindIP", TwwRealm.ADDRESS)
            for (key in listOf("LoginDatabaseInfo", "WorldDatabaseInfo", "CharacterDatabaseInfo", "HotfixDatabaseInfo")) {
                val original = conf[key] ?: continue
                val values = original.split(';').toMutableList()
                if (values.size != 5) throw IOException("$key in ${file.name} is not a supported five-field database connection.")
                values[0] = TwwRealm.ADDRESS
                values[1] = databasePort.toString()
                conf.setText(key, values.joinToString(";"))
            }
            if (conf["LoginREST.ExternalAddress"] != null) conf.setText("LoginREST.ExternalAddress", TwwRealm.ADDRESS)
            if (conf["LoginREST.LocalAddress"] != null) conf.setText("LoginREST.LocalAddress", TwwRealm.ADDRESS)
            replaceConfig(file, conf.toString())
        }
        // Revalidate the resulting profile before recording completion.
        readProfile(context)
    }

    fun launchGame(context: Context) {
        val app = context.applicationContext
        scope.launch {
            try {
                if (TwwJobs.busy) throw IOException("Wait for the current installation job before launching the client.")
                if (mutableStatus.value.phase != Phase.RUNNING) throw IOException("Start the local server and wait for every component to be ready first.")
                if (!TwwGame.localConfigured(app)) throw IOException("Configure the local client connection and review the Firestorm warning first.")
                val report = TwwFiles.report(app)
                val executable = report.clientExecutable ?: throw IOException("The client executable is missing.")
                if (report.clientBuild?.build != TwwRealm.CLIENT_BUILD) throw IOException("The installed client does not match build ${TwwRealm.CLIENT_BUILD}.")
                val layout = TwwFiles.layout(app)
                val command = TwwSession.argument(TwwSession.windows(executable, layout.client, 'D'))
                writeScript(app, "client", TwwSession.script(TwwSession.windows(executable.parentFile, layout.client, 'D'), command, "client"))
                execute("client")
                withContext(Dispatchers.Main) { TwwGame.showSession(app) }
                mutableStatus.value = mutableStatus.value.copy(detail = "Client launch requested. Connection compatibility is confirmed only after the client logs in; see runtime/client.log if it exits.", error = null)
            } catch (error: Exception) {
                mutableStatus.value = mutableStatus.value.copy(error = error.message ?: error.javaClass.simpleName)
            }
        }
    }

    private suspend fun startNow(context: Context) {
        try {
            if (otherActivity.get() != null) throw IOException("Close the active game's Windows session before starting The War Within server.")
            val report = TwwFiles.report(context)
            val missing = listOf("Server Core" to report.core, "Server Database" to report.database, "Map Files" to report.maps)
                .filter { it.second.status != TwwInstall.Status.INSTALLED }
            if (!report.canBootstrap) throw IOException(missing.joinToString("\n") { (name, value) -> "$name: ${(value.missing + value.details).joinToString()}" }
                .ifBlank { "The server's startup requirements could not be verified." })
            val p = readProfile(context)
            profile = p
            started.clear()
            for (port in p.ports) if (open(port)) throw IOException("Local port $port is already in use. Stop the server using it before starting Draconic.")
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
            TwwGame.openSession(context)
            val deadline = System.currentTimeMillis() + 120_000
            while (activity.get() == null) {
                if (System.currentTimeMillis() >= deadline) throw IOException("The Windows session did not start. Check the Winlator runtime installation.")
                delay(250)
            }
            mutableStatus.value = mutableStatus.value.copy(database = ComponentStatus.STARTING, detail = "Starting ${p.detected.databaseExecutable?.name}…")
            startComponent("database")
            waitReady(context, "database", listOf(p.databasePort), 180)
            mutableStatus.value = mutableStatus.value.copy(database = ComponentStatus.RUNNING,
                detail = "Verifying build ${TwwRealm.CLIENT_BUILD} in the imported authentication database…")
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
        val detected = TwwFiles.report(context).detected ?: throw IOException("The Draconic server is missing.")
        if (detected.documentedBuild != null && detected.documentedBuild != TwwRealm.CLIENT_BUILD) {
            throw IOException("The repack documents build ${detected.documentedBuild}; expected ${TwwRealm.CLIENT_BUILD}.")
        }
        // Missing documentation permits database-only bootstrap. The live build_info query
        // below must succeed before the authentication/world executables can be launched.
        val startupIssues = detected.issues.filterNot { it == TwwInstall.UNKNOWN_BUILD_ISSUE }
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
        if (auth["BindIP"] != TwwRealm.ADDRESS || world["BindIP"] != TwwRealm.ADDRESS || iniValue(dbConfig.readText(), "mysqld", "bind-address") != TwwRealm.ADDRESS) {
            throw IOException("Configure the server for localhost before starting it; original configurations will be backed up.")
        }
        val data = detected.dataDirectory ?: throw IOException("The world server's DataDir is unresolved.")
        if (File(data, "mmaps").list()?.isNotEmpty() != true) throw IOException("Install the additional MMAPS into the repack's detected DataDir first.")
        val login = (auth["LoginDatabaseInfo"] ?: world["LoginDatabaseInfo"] ?: "").split(';')
        if (login.size != 5 || login[0] != TwwRealm.ADDRESS || login[1].toIntOrNull() != dbPort || !login[4].matches(Regex("[A-Za-z0-9_]+"))) {
            throw IOException("The authentication database connection is not verified for localhost and this database port.")
        }
        val tools = detected.root.walkTopDown().filter { it.isFile && it.extension.equals("exe", true) }.toList()
        val mysql = tools.filter { it.nameWithoutExtension.lowercase() in setOf("mysql", "mariadb") }.singleOrNull()
            ?: throw IOException("The repack must contain one mysql.exe or mariadb.exe client to configure its realmlist.")
        val admin = tools.filter { it.nameWithoutExtension.lowercase() in setOf("mysqladmin", "mariadb-admin") }.singleOrNull()
            ?: throw IOException("The repack must contain mysqladmin.exe or mariadb-admin.exe for safe database shutdown.")
        return Profile(detected, dbPort, listOfNotNull(authPort, rest).distinct(), worldPort, realmId, mysql, admin, login)
    }

    private fun writeCredentials(context: Context, p: Profile) {
        val runtime = runtimeFolder(context)
        val credentials = TwwFiles.fs(context).check(File(runtime, "login.cnf"))
        val user = p.login[2]
        val password = p.login[3]
        if (listOf(user, password).any { it.any { char -> char in "\r\n\u0000" } }) throw IOException("Database credentials contain unsupported control characters.")
        fun iniQuote(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        TwwGame.atomicWrite(credentials, "[client]\nhost=127.0.0.1\nport=${p.databasePort}\nuser=${iniQuote(user)}\npassword=${iniQuote(password)}\n")
        credentials.setReadable(false, false); credentials.setWritable(false, false)
        credentials.setReadable(true, true); credentials.setWritable(true, true)
    }

    private suspend fun setRealm(context: Context, p: Profile) {
        val runtime = runtimeFolder(context)
        val sql = "UPDATE realmlist SET name='The War Within — 11.2.5', address='127.0.0.1', localAddress='127.0.0.1', port=${p.worldPort}, gamebuild=${TwwRealm.CLIENT_BUILD} WHERE id=${p.realmId};\nSELECT COUNT(*) FROM realmlist WHERE id=${p.realmId} AND address='127.0.0.1' AND gamebuild=${TwwRealm.CLIENT_BUILD};\n"
        TwwGame.atomicWrite(File(runtime, "realm.sql"), sql)
        val cmd = TwwSession.argument(TwwSession.windows(p.mysql, p.detected.root, 'S')) +
            " --defaults-extra-file=R:\\runtime\\login.cnf --default-character-set=utf8mb4 --batch --skip-column-names ${p.login[4]} <\"R:\\runtime\\realm.sql\""
        writeScript(context, "realm", TwwSession.script("S:\\", cmd, "realm"))
        execute("realm")
        waitExit(context, "realm", 60)
        if (File(runtime, "realm.log").readLines().lastOrNull { it.isNotBlank() }?.trim() != "1") {
            throw IOException("The authentication database has no verified realm row ${p.realmId} for build ${TwwRealm.CLIENT_BUILD}; inspect runtime/realm.log.")
        }
    }

    /** A realmlist row is routing configuration, so changing its gamebuild cannot prove compatibility. */
    private suspend fun verifyServerBuild(context: Context, p: Profile) {
        val runtime = runtimeFolder(context)
        val proof = TwwFiles.fs(context).check(File(p.detected.root, ".tww-build-proof.properties"))
        if (proof.exists() && !proof.delete()) throw IOException("The previous build verification record could not be invalidated.")
        TwwGame.atomicWrite(File(runtime, "build-check.sql"), "SELECT COUNT(*) FROM build_info WHERE build=${TwwRealm.CLIENT_BUILD};\n")
        val command = TwwSession.argument(TwwSession.windows(p.mysql, p.detected.root, 'S')) +
            " --defaults-extra-file=R:\\runtime\\login.cnf --default-character-set=utf8mb4 --batch --skip-column-names ${p.login[4]} <\"R:\\runtime\\build-check.sql\""
        writeScript(context, "build-check", TwwSession.script("S:\\", command, "build-check"))
        execute("build-check")
        waitExit(context, "build-check", 60)
        val count = File(runtime, "build-check.log").readLines().lastOrNull { it.isNotBlank() }?.trim()?.toLongOrNull()
        if (count == null || count < 1) {
            throw IOException("The imported authentication database's build_info table does not support build ${TwwRealm.CLIENT_BUILD}. No realmlist build was changed and authentication/world were not started.")
        }
        val authentication = p.detected.authenticationExecutable ?: throw IOException("Authentication executable is missing.")
        val world = p.detected.worldExecutable ?: throw IOException("World executable is missing.")
        TwwGame.atomicWrite(proof, "build=${TwwRealm.CLIENT_BUILD}\n" +
            "authenticationSha256=${TwwGame.sha256(authentication)}\n" +
            "worldSha256=${TwwGame.sha256(world)}\n" +
            "source=authentication-database-build_info\n")
    }

    private suspend fun stopNow(context: Context) {
        val p = profile
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
            File(TwwFiles.layout(context).root, "runtime/login.cnf").delete()
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
            closeSession()
            File(TwwFiles.layout(context).root, "runtime/login.cnf").delete()
            profile = null
            started.clear()
            mutableStatus.value = Status()
        } catch (error: Exception) {
            mutableStatus.value = mutableStatus.value.copy(phase = Phase.FAILED, detail = null, error = error.message ?: error.javaClass.simpleName)
            refreshObserved()
        }
    }

    private suspend fun closeSession() {
        val current = activity.get() ?: return
        withContext(Dispatchers.Main) { current.finish() }
        activity.clear()
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
        TwwGame.atomicWrite(File(runtime, "$name.cmd"), script)
    }

    private suspend fun waitReady(context: Context, name: String, ports: List<Int>, seconds: Int) {
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(seconds.toLong())
        while (true) {
            if (activity.get() == null) throw IOException("The Windows session ended while starting $name.")
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

    private fun runtimeFolder(context: Context): File {
        val fs = TwwFiles.fs(context)
        val folder = fs.check(File(TwwFiles.layout(context).root, "runtime"))
        fs.mkdirs(folder)
        return folder
    }

    private fun exitFile(context: Context, name: String) = TwwFiles.fs(context).check(File(runtimeFolder(context), "$name.exit"))
    private fun logTail(context: Context, name: String): String = runCatching {
        File(TwwFiles.layout(context).root, "runtime/$name.log").readLines().takeLast(12).joinToString("\n").takeLast(3000)
    }.getOrDefault("No component log was produced.")

    private fun roleName(role: TwwInstall.Role) = when (role) {
        TwwInstall.Role.DATABASE -> "database"
        TwwInstall.Role.AUTHENTICATION -> "authentication"
        TwwInstall.Role.WORLD -> "world"
    }

    private fun open(port: Int): Boolean = runCatching { Socket().use { it.connect(InetSocketAddress(TwwRealm.ADDRESS, port), 300) } }.isSuccess
    private fun port(value: String?, role: String): Int = value?.toIntOrNull()?.takeIf { it in 1..65535 }
        ?: throw IOException("The $role port is not explicitly configured in the repack.")

    private fun replaceConfig(file: File, text: String) { TwwGame.backup(file); TwwGame.atomicWrite(file, text) }

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
