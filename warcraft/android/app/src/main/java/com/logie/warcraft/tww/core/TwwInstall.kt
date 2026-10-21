// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.tww.core

import com.logie.warcraft.client.ClientBuild
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Locale
import java.util.Properties

/** Read-only discovery. A file being present is never treated as a successful server start. */
object TwwInstall {
    enum class Status { MISSING, INCOMPLETE, INSTALLED, INCOMPATIBLE, UNVERIFIED }

    data class Component(
        val status: Status,
        val missing: List<String> = emptyList(),
        val details: List<String> = emptyList(),
    )

    enum class Role { DATABASE, AUTHENTICATION, WORLD }

    data class StartupCommand(
        val executable: File,
        val arguments: List<String>,
        val workingDirectory: File,
        /** The actual startup script this command came from. */
        val source: File,
    )

    data class DetectedServer(
        val root: File,
        val databaseExecutable: File?,
        val authenticationExecutable: File?,
        val worldExecutable: File?,
        val authenticationConfig: File?,
        val worldConfig: File?,
        val databaseConfig: File?,
        /** Explicit DataDir from the world configuration, resolved against its startup directory. */
        val dataDirectory: File?,
        val startupScripts: List<File>,
        val commands: Map<Role, StartupCommand>,
        val issues: List<String>,
        val documentedBuild: Int?,
        /** A completed runtime build_info query, bound to the two unchanged core executables. */
        val buildProof: Boolean = false,
    )

    data class Report(
        val client: Component,
        val core: Component,
        val database: Component,
        val maps: Component,
        val clientExecutable: File?,
        val clientBuild: ClientBuild?,
        val firestormModified: Boolean,
        val detected: DetectedServer?,
        val blockers: List<String>,
        val localConnectionConfigured: Boolean = false,
    ) {
        /** May start the database to ask build_info; no other unknown requirements are waived. */
        val canBootstrap: Boolean get() = detected?.let { server ->
            database.status == Status.INSTALLED && maps.status == Status.INSTALLED &&
                core.missing.isEmpty() && server.dataDirectory != null &&
                server.commands.keys == Role.entries.toSet() &&
                (core.status == Status.INSTALLED || core.status == Status.UNVERIFIED &&
                    server.documentedBuild == null && server.issues == listOf(UNKNOWN_BUILD_ISSUE))
        } == true

        val canStartServer: Boolean get() = canBootstrap && client.status == Status.INSTALLED && localConnectionConfigured

        val ready: Boolean get() = blockers.isEmpty() &&
            listOf(client, core, database, maps).all { it.status == Status.INSTALLED }
    }

    fun inspect(client: File, server: File, maps: File = server): Report {
        val clientFiles = files(client)
        val serverFiles = files(server)
        val mapFiles = (if (maps == server) serverFiles else files(maps) + serverFiles).distinctBy { it.absolutePath }
        val clientProblems = mutableListOf<String>()
        val candidates = clientFiles.filter { WOW_EXE.matches(it.name) }
        val executable = when {
            candidates.size == 1 -> candidates.single()
            candidates.size > 1 -> {
                clientProblems += "More than one game executable was found; choose a single client folder: ${candidates.joinToString { it.relativeTo(client).path }}"
                null
            }
            else -> null
        }
        val build = executable?.let { readBuild(it) }
        val clientRoot = executable?.parentFile ?: client
        val dataRoots = listOf(clientRoot, clientRoot.parentFile ?: clientRoot, client).distinct()
        val dataFiles = clientFiles.filter { file -> dataRoots.any { root ->
            file.relativeToOrNull(root)?.path?.replace('\\', '/')?.startsWith("Data/data/", ignoreCase = true) == true
        } }
        val missingClient = mutableListOf<String>()
        if (executable == null) missingClient += "A single Wow.exe or Wow-64.exe"
        if (dataFiles.none { it.extension.equals("idx", true) && it.length() > 0 }) missingClient += "Data/data/*.idx"
        if (dataFiles.none { it.name.startsWith("data.", true) && it.length() > 0 }) missingClient += "Data/data/data.NNN"
        val infoFiles = dataRoots.mapNotNull { root -> root.listFiles()?.firstOrNull { it.name.equals(".build.info", true) } }.distinct()
        val recordedVersions = infoFiles.mapNotNull { versionFromBuildInfo(readText(it)) }
        val mismatched = build != null && !expected(build) || recordedVersions.any { !expected(it) }
        if (build == null && executable != null) clientProblems += "The executable's Windows version resource could not be verified"
        if (mismatched) clientProblems += "Expected 11.2.5.${TwwRealm.CLIENT_BUILD}; found executable ${build ?: "unknown"}, launcher ${recordedVersions.joinToString().ifEmpty { "unknown" }}"
        val firestorm = clientFiles.any { it.name.contains("firestorm", true) } || executable?.let { binaryContains(it, "firestorm") } == true ||
            clientFiles.filter { it.extension.equals("wtf", true) || it.name.equals(".build.info", true) }
                .any { readText(it).contains("firestorm", true) }
        if (firestorm) clientProblems += "Firestorm files or connection settings were detected; local-server compatibility requires verification"
        if (executable != null && missingClient.isEmpty()) clientProblems += "Executable version and CASC file presence checked; no trusted complete-client checksum manifest was supplied"
        val clientStatus = when {
            clientFiles.isEmpty() -> Status.MISSING
            mismatched -> Status.INCOMPATIBLE
            missingClient.isNotEmpty() || candidates.size > 1 -> Status.INCOMPLETE
            build == null -> Status.UNVERIFIED
            else -> Status.INSTALLED
        }

        val detected = detect(server, serverFiles)
        val missingCore = buildList {
            if (detected?.authenticationExecutable == null) add("Authentication server executable")
            if (detected?.worldExecutable == null) add("World server executable")
            if (detected?.authenticationConfig == null) add("Authentication server configuration")
            if (detected?.worldConfig == null) add("World server configuration")
        }
        val coreStatus = when {
            detected == null -> Status.MISSING
            detected.documentedBuild != null && detected.documentedBuild != TwwRealm.CLIENT_BUILD -> Status.INCOMPATIBLE
            missingCore.isNotEmpty() -> Status.INCOMPLETE
            detected.documentedBuild == null || detected.issues.isNotEmpty() -> Status.UNVERIFIED
            else -> Status.INSTALLED
        }
        val databaseNames = listOfNotNull(detected?.worldConfig, detected?.authenticationConfig)
            .flatMap { databaseNames(readText(it)) }.distinct()
        val dbDirectories = serverFiles.filter { it.extension.lowercase(Locale.ROOT) in setOf("ibd", "frm", "myd", "myi", "mai", "mad") }
            .groupBy { it.parentFile.name.lowercase(Locale.ROOT) }
        val missingDb = buildList {
            if (detected?.databaseExecutable == null) add("Database server executable")
            if (detected?.databaseConfig == null) add("Database configuration (my.ini or my.cnf)")
            // MariaDB/MySQL 5 use a mysql schema directory; MySQL 8 stores its system
            // dictionary in mysql.ibd at the data directory's top level.
            if ("mysql" !in dbDirectories && serverFiles.none { it.name.equals("mysql.ibd", true) && it.length() > 0 }) add("Initialized mysql system database")
            if (databaseNames.isEmpty()) add("Database names from the server's connection configurations")
            databaseNames.filter { it.lowercase(Locale.ROOT) !in dbDirectories }.forEach { add("Initialized database: $it") }
        }
        val sqlDumps = serverFiles.count { it.extension.equals("sql", true) }
        val db = Component(
            when {
                detected?.databaseExecutable == null && dbDirectories.isEmpty() && sqlDumps == 0 -> Status.MISSING
                missingDb.isNotEmpty() -> Status.INCOMPLETE
                else -> Status.INSTALLED
            },
            missingDb,
            buildList { if (sqlDumps > 0) add("Found $sqlDumps SQL seed file(s); a SQL dump alone is not an initialized database") },
        )
        val missingMaps = buildList {
            if (mapFiles.none { under(it, "maps") && it.extension.equals("map", true) && it.length() > 8 }) add("maps/*.map")
            if (mapFiles.none { under(it, "vmaps") && it.extension.equals("vmtree", true) && it.length() > 8 }) add("vmaps/*.vmtree")
            if (mapFiles.none { under(it, "mmaps") && it.extension.equals("mmap", true) && it.length() >= 28 }) add("mmaps/*.mmap")
            if (mapFiles.none { under(it, "mmaps") && it.extension.equals("mmtile", true) && it.length() > 20 }) add("mmaps/*.mmtile")
            if (mapFiles.none { (under(it, "dbc") || under(it, "db2")) && it.extension.equals("db2", true) && it.length() > 4 }) add("dbc/*.db2 or db2/*.db2")
        }
        val relevantMaps = mapFiles.filter { mapPrefix(it) != null || under(it, "mmaps") && it.extension.equals("mmap", true) }
        val badMaps = relevantMaps.asSequence().filter { file ->
            val prefix = mapPrefix(file)
            if (prefix == null) file.length() < 28 else file.inputStream().use { input ->
                val head = ByteArray(prefix.length)
                input.read(head) != head.size || !head.contentEquals(prefix.toByteArray())
            }
        }.take(10).toList()
        val mapsComponent = Component(
            when {
                relevantMaps.isEmpty() -> Status.MISSING
                missingMaps.isNotEmpty() || badMaps.isNotEmpty() -> Status.INCOMPLETE
                else -> Status.INSTALLED
            },
            missingMaps,
            buildList {
                if (badMaps.isNotEmpty()) add("Invalid map headers: ${badMaps.joinToString { it.name }}")
                if (missingMaps.isEmpty() && badMaps.isEmpty()) add("Map folders and map headers are present. Full map coverage/version compatibility is checked by the world server on startup; no trusted file-by-file manifest was supplied.")
            },
        )
        val localPortal = clientFiles.filter { it.name.equals("Config.wtf", true) }.any {
            Regex("(?im)^\\s*SET\\s+portal\\s+\"127\\.0\\.0\\.1(?::\\d+)?\"\\s*$").containsMatchIn(readText(it))
        }
        val blockers = buildList {
            if (clientStatus != Status.INSTALLED) add("Game Client: ${if (missingClient.isNotEmpty()) missingClient.joinToString() else clientProblems.joinToString()}")
            if (coreStatus != Status.INSTALLED) add("Server Core: ${(missingCore + (detected?.issues ?: emptyList())).joinToString().ifEmpty { "Expected server build is unverified" }}")
            if (db.status != Status.INSTALLED) add("Server Database: ${missingDb.joinToString()}")
            if (mapsComponent.status != Status.INSTALLED) add("Map Files: ${(missingMaps + mapsComponent.details).joinToString()}")
            if (!localPortal) add("Local connection is not configured: set the imported client's portal to 127.0.0.1 after reviewing its Firestorm modifications. No executable has been modified.")
        }
        return Report(Component(clientStatus, missingClient, clientProblems), Component(coreStatus, missingCore, detected?.issues ?: emptyList()), db,
            mapsComponent, executable, build, firestorm, detected, blockers, localPortal)
    }

    /** Keeps the CASC Data folder and .build.info beside _retail_ when that is their layout. */
    fun findClientRoot(folder: File): File? {
        val candidates = files(folder).filter { WOW_EXE.matches(it.name) }
        if (candidates.size != 1) return null
        val parent = candidates.single().parentFile
        return if (parent.name.equals("_retail_", true)) parent.parentFile else parent
    }

    /** Keep database/controller siblings in a repack, even when its core lives several levels down. */
    fun findServerRoot(folder: File): File? = folder.takeIf { detect(it, files(it)) != null }

    /** Reads the executable itself; a launcher record never overrides a different executable build. */
    fun readBuild(executable: File): ClientBuild? = runCatching {
        if (peMachine(executable) != 0x8664) return null
        executable.inputStream().buffered().use { ClientBuild.read(it) }
    }.getOrNull()

    private fun peMachine(executable: File): Int? = runCatching {
        RandomAccessFile(executable, "r").use { file ->
            if (file.length() < 64 || file.readUnsignedByte() != 0x4d || file.readUnsignedByte() != 0x5a) return null
            file.seek(0x3c)
            val pe = Integer.reverseBytes(file.readInt()).toLong() and 0xffffffffL
            if (pe > file.length() - 6) return null
            file.seek(pe)
            if (file.readInt() != 0x50450000) return null
            java.lang.Short.reverseBytes(file.readShort()).toInt() and 0xffff
        }
    }.getOrNull()

    fun versionFromBuildInfo(text: String): ClientBuild? {
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        val header = lines.firstOrNull()?.split('|')?.map { it.substringBefore('!').trim() } ?: return null
        val version = header.indexOfFirst { it.equals("Version", true) }
        val active = header.indexOfFirst { it.equals("Active", true) }
        if (version < 0) return null
        val versions = lines.drop(1).mapNotNull { line ->
            val cells = line.split('|')
            if (active >= 0 && cells.getOrNull(active)?.trim() != "1") return@mapNotNull null
            VERSION.matchEntire(cells.getOrNull(version)?.trim() ?: "")?.destructured?.let { (a,b,c,d) ->
                val numbers = listOf(a,b,c,d).map { it.toIntOrNull() ?: return@mapNotNull null }
                ClientBuild(numbers[0], numbers[1], numbers[2], numbers[3])
            }
        }.distinct()
        return versions.singleOrNull()
    }

    private fun expected(build: ClientBuild) = build.major == 11 && build.minor == 2 && build.patch == 5 && build.build == TwwRealm.CLIENT_BUILD

    private fun detect(root: File, files: List<File>): DetectedServer? {
        val exes = files.filter { it.extension.equals("exe", true) }
        // UniController discovers its database as mysqld_<name>.exe (normally mysqld_z.exe).
        // Match actual PE files and still reject multiple candidates.
        val dbs = exes.filter { it.nameWithoutExtension.lowercase(Locale.ROOT) in setOf("mysqld", "mariadbd") ||
            Regex("(?i)mysqld_[a-z0-9_]+\\.exe").matches(it.name) }
        val auths = exes.filter { it.nameWithoutExtension.lowercase(Locale.ROOT) in setOf("bnetserver", "authserver", "realmd", "loginserver") }
        val worlds = exes.filter { it.nameWithoutExtension.lowercase(Locale.ROOT) in setOf("worldserver", "mangosd", "arcemu-world") }
        if (dbs.isEmpty() && auths.isEmpty() && worlds.isEmpty()) return null
        val issues = mutableListOf<String>()
        fun one(candidates: List<File>, role: String): File? {
            if (candidates.size > 1) issues += "Ambiguous $role: ${candidates.joinToString { it.relativeTo(root).path }}"
            return candidates.singleOrNull()
        }
        val db = one(dbs, "database executable")
        val auth = one(auths, "authentication executable")
        val world = one(worlds, "world executable")
        listOfNotNull(db, auth, world).forEach { executable ->
            if (peMachine(executable) !in setOf(0x8664, 0x14c)) issues += "Not a readable Windows PE executable: ${executable.relativeTo(root).path}"
        }
        val configs = files.filter { it.extension.lowercase(Locale.ROOT) in setOf("conf", "ini", "cnf") && it.length() < MAX_TEXT }
        val worldConf = one(configs.filter { readText(it).contains(Regex("(?im)^\\s*WorldDatabaseInfo\\s*=")) }, "world configuration")
        val authConf = one(configs.filter { file -> file != worldConf &&
            (file.nameWithoutExtension.equals(auth?.nameWithoutExtension, true) || readText(file).contains(Regex("(?im)^\\s*(BattlenetPort|RealmServerPort)\\s*="))) }, "authentication configuration")
        val databaseConfigs = configs.filter { file -> file.extension.lowercase(Locale.ROOT) in setOf("ini", "cnf") && readText(file).contains(Regex("(?im)^\\s*\\[mysqld\\]")) }
        val databaseHome = db?.parentFile?.let { if (it.name.equals("bin", true)) it.parentFile else it }
        val primaryConfigs = databaseConfigs.filter { file ->
            file.name.lowercase(Locale.ROOT) in setOf("my.ini", "my.cnf") &&
                (file.parentFile.canonicalFile == databaseHome?.canonicalFile || file.parentFile.canonicalFile == db?.parentFile?.canonicalFile)
        }
        // Uniform Server also ships my_skip_grant.ini. It is a recovery configuration and
        // must not displace the normal my.ini alongside the discovered database installation.
        val dbConf = one(primaryConfigs.ifEmpty { databaseConfigs }, "database configuration")
        val scripts = files.filter { it.extension.lowercase(Locale.ROOT) in setOf("bat", "cmd", "sh") && it.length() < MAX_TEXT }
        val executables = mapOf(Role.DATABASE to db, Role.AUTHENTICATION to auth, Role.WORLD to world)
        val commands = mutableMapOf<Role, StartupCommand>()
        executables.forEach { (role, exe) ->
            if (exe != null) {
                val found = scripts.flatMap { commandsIn(it, root) }.filter { it.executable.canonicalFile == exe.canonicalFile }
                    .distinctBy { Triple(it.executable.canonicalPath, it.arguments, it.workingDirectory.canonicalPath) }
                if (found.size == 1) commands[role] = found.single()
                else if (found.isEmpty()) {
                    val referenced = scripts.any { readText(it).contains(exe.nameWithoutExtension, true) }
                    // Trinity-style cores read <executable>.conf from their working directory.
                    // Only infer this where that actual, unique default-named file is present.
                    val config = when (role) { Role.AUTHENTICATION -> authConf; Role.WORLD -> worldConf; Role.DATABASE -> dbConf }
                    if (!referenced && role != Role.DATABASE && config != null && config.nameWithoutExtension.equals(exe.nameWithoutExtension, true)) {
                        commands[role] = StartupCommand(exe, emptyList(), config.parentFile, config)
                    } else if (!referenced && role == Role.DATABASE && dbConf != null) {
                        // MySQL/MariaDB require --defaults-file to be the first option. The actual
                        // repack configuration supplies datadir, port and remaining startup options.
                        commands[role] = StartupCommand(exe, listOf("--defaults-file=${dbConf.path}"), dbConf.parentFile, dbConf)
                    } else issues += "No unambiguous startup command for ${exe.name} was found in the repack's scripts or configuration"
                } else issues += "Multiple startup commands for ${exe.name}; startup requirements need review"
            }
        }
        val evidence = files.filter { file -> file.extension.lowercase(Locale.ROOT) in setOf("md", "txt", "conf", "ini") && file.length() < MAX_TEXT }
            .flatMap { file ->
                val text = readText(file)
                BUILD.findAll(text).map { it.groupValues[1].toInt() }.toList() +
                    Regex("11\\.2\\.5[. \\(]+(\\d{5,6})\\b").findAll(text).map { it.groupValues[1].toInt() }.toList()
            }.distinct()
        val documentedBuild = evidence.singleOrNull()
        if (evidence.size > 1) issues += "The repack documents conflicting client builds: ${evidence.sorted().joinToString()}"
        val proof = evidence.none { it != TwwRealm.CLIENT_BUILD } &&
            auth != null && world != null && runtimeProof(root, auth, world)
        val build = documentedBuild ?: TwwRealm.CLIENT_BUILD.takeIf { proof }
        if (build == null) issues += UNKNOWN_BUILD_ISSUE
        if (build != null && build != TwwRealm.CLIENT_BUILD) issues += "Repack documents client build $build; expected ${TwwRealm.CLIENT_BUILD}"
        val dataPath = worldConf?.let { conf -> Regex("(?im)^\\s*DataDir\\s*=\\s*(\"[^\"]*\"|[^\\r\\n#]+)").find(readText(conf))?.groupValues?.get(1)?.trim()?.removeSurrounding("\"") }
        val dataDirectory = dataPath?.let { resolve(commands[Role.WORLD]?.workingDirectory ?: world?.parentFile ?: root, it) }
            ?.takeIf { it.toPath().startsWith(root.canonicalFile.toPath()) }
        if (dataPath != null && dataDirectory == null) issues += "World DataDir points outside the imported repack or uses an unresolved Windows path"
        return DetectedServer(root, db, auth, world, authConf, worldConf, dbConf, dataDirectory, scripts, commands, issues, build, proof)
    }

    private fun runtimeProof(root: File, auth: File, world: File): Boolean = runCatching {
        val proof = File(root, ".tww-build-proof.properties")
        if (!proof.isFile || proof.length() > 4096) return@runCatching false
        val properties = Properties().apply { proof.inputStream().use { load(it) } }
        properties.getProperty("source") == "authentication-database-build_info" &&
            properties.getProperty("build") == TwwRealm.CLIENT_BUILD.toString() &&
            properties.getProperty("authenticationSha256") == sha256(auth) &&
            properties.getProperty("worldSha256") == sha256(world)
    }.getOrDefault(false)

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Conservative BAT/CMD extraction: never interprets arbitrary scripts or substitutes user variables. */
    private fun commandsIn(script: File, root: File): List<StartupCommand> {
        var directory = script.parentFile
        var directoryKnown = true
        val result = mutableListOf<StartupCommand>()
        for (raw in readText(script).lines()) {
            val line = raw.trim().removePrefix("@").replace("%~dp0", script.parentFile.path + File.separator, ignoreCase = true)
            if (line.isEmpty() || line.startsWith("::") || line.startsWith("#") || line.startsWith("rem ", true)) continue
            val tokens = TOKEN.findAll(line).map { it.value.removeSurrounding("\"").removeSurrounding("'") }.toMutableList()
            if (tokens.firstOrNull()?.lowercase(Locale.ROOT) in setOf("cd", "chdir")) {
                val name = tokens.drop(1).filter { !it.equals("/d", true) }.singleOrNull()
                if (name == null || '%' in name || '$' in name) { directoryKnown = false; continue }
                val target = resolve(directory, name)
                directoryKnown = target != null && target.toPath().startsWith(root.canonicalFile.toPath())
                if (directoryKnown) directory = target!!
                continue
            }
            if (!directoryKnown || '%' in line || '$' in line || line.any { it in "&|<>" }) continue
            if (tokens.firstOrNull().equals("start", true)) {
                tokens.removeAt(0)
                if (Regex("(?i)^start\\s+\"").containsMatchIn(line) && tokens.isNotEmpty()) tokens.removeAt(0)
                while (tokens.firstOrNull()?.lowercase(Locale.ROOT) in setOf("/b", "/wait", "/min", "/max")) tokens.removeAt(0)
            }
            if (tokens.firstOrNull().equals("call", true)) tokens.removeAt(0)
            val program = tokens.firstOrNull() ?: continue
            val file = resolve(directory, program) ?: continue
            val executable = if (file.isFile) file else File(file.path + ".exe")
            if (!executable.isFile || !executable.extension.equals("exe", true) || !executable.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())) continue
            result += StartupCommand(executable, tokens.drop(1), directory, script)
        }
        return result
    }

    private fun resolve(base: File, path: String): File? {
        val normalized = path.replace('\\', '/')
        if (':' in normalized) return null
        var current = if (normalized.startsWith('/')) File("/") else base
        for (part in normalized.split('/').filter { it.isNotEmpty() }) {
            current = when (part) {
                "." -> current
                ".." -> current.parentFile ?: return null
                else -> File(current, part).let { exact ->
                    if (exact.exists()) exact else current.listFiles()?.filter { it.name.equals(part, true) }?.singleOrNull() ?: exact
                }
            }
        }
        return current.canonicalFile
    }

    private fun databaseNames(text: String): List<String> = DB_CONNECTION.findAll(text).mapNotNull { match ->
        match.groupValues[1].trim().removeSurrounding("\"").split(';').lastOrNull()?.trim()?.takeIf { it.matches(Regex("[A-Za-z0-9_]+")) }
    }.toList()

    private fun under(file: File, folder: String): Boolean = file.parentFile.toPath().any { it.toString().equals(folder, true) }

    /** Only the format signature is checked here; the repack decides its exact format version. */
    private fun mapPrefix(file: File): String? = when {
        under(file, "maps") && file.extension.equals("map", true) -> "MAPS"
        under(file, "vmaps") && file.extension.lowercase(Locale.ROOT) in setOf("vmtree", "vmtile", "vmo") -> "VMAP_"
        under(file, "mmaps") && file.extension.equals("mmtile", true) -> "PAMM"
        (under(file, "dbc") || under(file, "db2")) && file.extension.equals("db2", true) -> "WDC"
        else -> null
    }

    private fun readText(file: File): String = if (file.length() > MAX_TEXT) "" else runCatching { file.readText() }.getOrDefault("")

    private fun binaryContains(file: File, needle: String): Boolean = file.inputStream().buffered().use { input ->
        val buffer = ByteArray(1 shl 16)
        var carry = ""
        while (true) {
            val n = input.read(buffer)
            if (n < 0) return@use false
            val text = carry + String(buffer, 0, n, Charsets.ISO_8859_1).lowercase(Locale.ROOT)
            if (needle in text) return@use true
            carry = text.takeLast(needle.length - 1)
        }
        @Suppress("UNREACHABLE_CODE") false
    }

    private fun files(root: File): List<File> {
        if (!root.exists()) return emptyList()
        if (Files.isSymbolicLink(root.toPath())) throw IOException("Installation root must not be a symbolic link: ${root.path}")
        val files = mutableListOf<File>()
        Files.walkFileTree(root.toPath(), object : SimpleFileVisitor<java.nio.file.Path>() {
            override fun visitFile(path: java.nio.file.Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isSymbolicLink) throw IOException("Installation contains a symbolic link: $path")
                if (attrs.isRegularFile) files += path.toFile()
                if (files.size > 2_000_000) throw IOException("Installation contains too many files")
                return FileVisitResult.CONTINUE
            }
        })
        return files
    }

    private const val MAX_TEXT = 2_000_000L
    const val UNKNOWN_BUILD_ISSUE = "The repack does not identify one verifiable client build in its configuration or documentation"
    private val WOW_EXE = Regex("(?i)wow(?:-64)?\\.exe")
    private val VERSION = Regex("(\\d+)\\.(\\d+)\\.(\\d+)\\.(\\d+)")
    private val BUILD = Regex("(?i)(?:client[ _.-]*build|expected[ _.-]*build|build[ _.-]*(?:number)?)[ \":=]+(\\d{5,6})\\b")
    private val TOKEN = Regex("\"[^\"]*\"|'[^']*'|\\S+")
    private val DB_CONNECTION = Regex("(?im)^\\s*(?:Login|World|Character|Hotfix)DatabaseInfo\\s*=\\s*(\"[^\"]*\"|[^\\r\\n#]+)")
}
