// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.tww.core

import java.io.File

/** Resolves one database installation, following Uniform Server's actual controller layout. */
object TwwDatabase {
    data class Profile(
        val executable: File?,
        val configuration: File?,
        val dataDirectory: File?,
        val controller: File?,
        val port: Int?,
        val client: File?,
        val admin: File?,
        val issues: List<String>,
    )

    internal fun discover(root: File, files: List<File>, layout: LocalServerLayout? = null): Profile {
        if (layout != null) {
            fun member(path: String) = TwwInstall.resolve(root, path)?.takeIf { it.toPath().startsWith(root.canonicalFile.toPath()) }
            val executable = member(layout.databaseExecutable)?.takeIf { it.isFile }
            val configuration = member(layout.databaseConfig)?.takeIf { it.isFile && hasServerSection(it) }
            val data = member(layout.databaseData)
            fun tool(name: String) = executable?.parentFile?.let { TwwInstall.resolve(it, name) }?.takeIf { it.isFile }
            return Profile(executable, configuration, data, null, ini(configuration, "mysqld", "port")?.toIntOrNull()?.takeIf { it in 1..65535 },
                tool("mysql.exe"), tool("mysqladmin.exe"), emptyList())
        }
        val daemons = files.filter { DAEMON.matches(it.name) }
        val controllers = files.filter { it.name.equals("UniController.exe", true) &&
            TwwInstall.resolve(it.parentFile, "core/mysql/bin")?.isDirectory == true }
        val issues = mutableListOf<String>()
        fun one(candidates: List<File>, role: String): File? {
            if (candidates.size > 1) issues += "Ambiguous $role: ${candidates.joinToString { it.relativeTo(root).path }}"
            return candidates.singleOrNull()
        }
        val controller = one(controllers, "database controller")
        val controllerRoot = controller?.parentFile
        val bin = controllerRoot?.let { TwwInstall.resolve(it, "core/mysql/bin") }
        // us_get_mysql_exe scans only core/mysql/bin for mysqld_<name>.exe. A normal
        // mysqld.exe in that folder and core/old are not alternative active installations.
        val candidates = if (bin != null && controllerRoot != null) {
            val active = daemons.filter { it.parentFile.canonicalFile == bin.canonicalFile }
            val renamed = active.filter { RENAMED.matches(it.name) }
            val foreign = daemons.filterNot { it.toPath().startsWith(controllerRoot.canonicalFile.toPath()) }
            renamed.ifEmpty { active } + foreign
        } else if (controllers.isEmpty()) daemons else emptyList()
        val executable = one(candidates, "database executable")
        val home = executable?.parentFile?.let { if (it.name.equals("bin", true)) it.parentFile else it }
        val controllerSettings = controllerRoot?.let { TwwInstall.resolve(it, "home/us_config/us_config.ini") }
        val userSettings = controllerRoot?.let { TwwInstall.resolve(it, "home/us_config/us_user.ini") }
        fun configuredPath(path: String, base: File): File? {
            val value = path.trim().removeSurrounding("\"").replace('\\', '/')
            if (value.startsWith("S:/", true)) return TwwInstall.resolve(root, value.substring(3))
                ?.takeIf { it.toPath().startsWith(root.canonicalFile.toPath()) }
            val resolved = TwwInstall.resolve(base, value)
            if (resolved != null && resolved.toPath().startsWith(root.canonicalFile.toPath())) return resolved
            // A portable repack may retain its publisher's Windows drive. Relocate only
            // the known controller-relative suffix, never arbitrary external directories.
            if (controllerRoot != null && Regex("(?i)^[a-z]:/").containsMatchIn(value)) {
                val parts = value.split('/')
                val index = parts.indexOfLast { it.equals(controllerRoot.name, true) }
                if (index >= 0) return TwwInstall.resolve(controllerRoot, parts.drop(index + 1).joinToString("/"))
                    ?.takeIf { it.toPath().startsWith(controllerRoot.canonicalFile.toPath()) }
            }
            return null
        }
        val configuration = if (controllerRoot != null) {
            val path = ini(controllerSettings, "MYSQL", "defaults-file") ?: "core/mysql/my.ini"
            configuredPath(path, controllerRoot)?.takeIf { it.isFile && hasServerSection(it) }.also {
                if (it == null) issues += "Uniform Server's configured database defaults file is missing or unresolved: $path"
            }
        } else {
            val configs = files.filter { it.extension.lowercase() in setOf("ini", "cnf") && hasServerSection(it) }
            val primary = configs.filter { it.name.lowercase() in setOf("my.ini", "my.cnf") &&
                (it.parentFile.canonicalFile == home?.canonicalFile || it.parentFile.canonicalFile == executable?.parentFile?.canonicalFile) }
            one(primary.ifEmpty { configs }, "database configuration")
        }
        val dataPath = if (controllerRoot != null) ini(controllerSettings, "MYSQL", "datadir") ?: "core/mysql/data"
            else ini(configuration, "mysqld", "datadir")
        val data = dataPath?.let { configuredPath(it, controllerRoot ?: home ?: root) }
        if (dataPath != null && data == null) issues += "The database data directory is outside the imported repack or unresolved: $dataPath"
        val port = ini(configuration, "mysqld", "port")?.toIntOrNull()
            ?: ini(userSettings, "USER", "MYSQL_TCP_PORT")?.toIntOrNull()
        fun tool(names: Set<String>) = executable?.parentFile?.listFiles()?.filter {
            it.isFile && it.name.lowercase() in names
        }?.singleOrNull()
        return Profile(executable, configuration, data, controller, port?.takeIf { it in 1..65535 },
            tool(setOf("mysql.exe", "mariadb.exe")), tool(setOf("mysqladmin.exe", "mariadb-admin.exe")), issues)
    }

    private fun hasServerSection(file: File) = file.length() < 2_000_000 &&
        Regex("(?im)^\\s*\\[mysqld\\]\\s*(?:[#;].*)?$").containsMatchIn(file.readText())

    internal fun ini(file: File?, section: String, key: String): String? {
        if (file?.isFile != true || file.length() > 2_000_000) return null
        var active = false
        for (line in file.readLines()) {
            val text = line.trim()
            if (text.startsWith('[')) active = text.equals("[$section]", true)
            else if (active && !text.startsWith('#') && !text.startsWith(';') &&
                text.substringBefore('=').trim().equals(key, true) && '=' in text) {
                return text.substringAfter('=').trim().removeSurrounding("\"")
            }
        }
        return null
    }

    private val DAEMON = Regex("(?i)(?:mysqld(?:_[a-z0-9_]+)?|mariadbd)\\.exe")
    private val RENAMED = Regex("(?i)mysqld_[a-z0-9_]+\\.exe")
}
