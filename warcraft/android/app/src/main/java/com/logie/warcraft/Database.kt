// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft

import android.content.Context
import com.logie.warcraft.auth.Srp6
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * SQL on the server's databases, through MariaDB's own client (`ctl.sh sql`), while MariaDB runs.
 * Only the app's own statements go through here; anything the user typed is checked against
 * [Srp6.NAME] first, and passwords only ever arrive as SRP6 verifiers.
 */
object Database {
    class NotRunningException : IOException("The database isn't running")

    /** Runs [sql] on [database] and returns its rows, each a list of columns. Background thread. */
    fun query(context: Context, database: String, sql: String): List<List<String>> {
        if (!Server.databaseUp) throw NotRunningException()
        val process = Server.ctl(context, "sql", database).redirectErrorStream(false).start()
        process.outputStream.bufferedWriter().use { it.write(sql) }
        val output = process.inputStream.bufferedReader().readText()
        val errors = process.errorStream.bufferedReader().readText()
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroy()
            throw IOException("The database didn't answer")
        }
        if (process.exitValue() != 0) throw IOException(errors.lines().firstOrNull { it.isNotBlank() } ?: "SQL failed")
        return output.lines().filter { it.isNotEmpty() }.map { it.split('\t') }
    }

    fun exec(context: Context, database: String, sql: String) {
        query(context, database, sql)
    }

    /** Deletes the databases' files, for a fresh start. The server must be stopped. */
    fun deleteAll(context: Context) {
        check(!Server.databaseUp)
        val dir = WowFiles.databases(context)
        val trash = File(dir.parentFile, "mysql.deleting")
        trash.deleteRecursively()
        if (dir.exists() && !dir.renameTo(trash)) throw IOException("Couldn't delete the databases")
        trash.deleteRecursively()
        Server.forgetDatabases(context)
    }
}

/** The game accounts, in the auth database. */
object Accounts {
    data class Account(val id: Int, val name: String, val gmLevel: Int)

    /** GM levels: a player, a moderator, a game master, an administrator (all commands). */
    const val GM_PLAYER = 0
    const val GM_ADMIN = 3

    class InvalidException(val reason: Reason) : IOException(reason.name) {
        enum class Reason { NAME, PASSWORD, TAKEN }
    }

    fun list(context: Context): List<Account> =
        Database.query(
            context, "acore_auth",
            "SELECT a.id, a.username, COALESCE(MAX(aa.gmlevel), 0) FROM account a " +
                "LEFT JOIN account_access aa ON aa.id = a.id GROUP BY a.id, a.username ORDER BY a.id;",
        ).mapNotNull { row ->
            val id = row.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
            Account(id, row.getOrElse(1) { "" }, row.getOrNull(2)?.toIntOrNull() ?: 0)
        }.filterNot { it.name.startsWith(BOT_ACCOUNT_PREFIX, ignoreCase = true) }

    /** The playerbots module's own accounts for its random bots (rndbot1, ...): not the user's. */
    private const val BOT_ACCOUNT_PREFIX = "RNDBOT"

    fun create(context: Context, name: String, password: String, gmLevel: Int) {
        val user = checkName(name)
        checkPassword(password)
        if (list(context).any { it.name.equals(user, ignoreCase = true) }) throw InvalidException(InvalidException.Reason.TAKEN)
        val salt = Srp6.newSalt()
        val verifier = Srp6.verifier(user, password, salt)
        Database.exec(
            context, "acore_auth",
            "INSERT INTO account (username, salt, verifier, expansion, joindate) VALUES " +
                "('$user', X'${Srp6.hex(salt)}', X'${Srp6.hex(verifier)}', 2, NOW());\n" +
                gmSql(user, gmLevel),
        )
    }

    fun setPassword(context: Context, name: String, password: String) {
        val user = checkName(name)
        checkPassword(password)
        val salt = Srp6.newSalt()
        val verifier = Srp6.verifier(user, password, salt)
        Database.exec(
            context, "acore_auth",
            "UPDATE account SET salt = X'${Srp6.hex(salt)}', verifier = X'${Srp6.hex(verifier)}' WHERE username = '$user';",
        )
    }

    fun setGmLevel(context: Context, name: String, gmLevel: Int) {
        Database.exec(context, "acore_auth", gmSql(checkName(name), gmLevel))
    }

    /** Deletes the account. Its characters stay in the characters database, without an owner. */
    fun delete(context: Context, name: String) {
        val user = checkName(name)
        Database.exec(
            context, "acore_auth",
            "DELETE aa FROM account_access aa JOIN account a ON a.id = aa.id WHERE a.username = '$user';\n" +
                "DELETE FROM account WHERE username = '$user';",
        )
    }

    private fun gmSql(user: String, gmLevel: Int): String {
        val level = gmLevel.coerceIn(GM_PLAYER, GM_ADMIN)
        val delete = "DELETE aa FROM account_access aa JOIN account a ON a.id = aa.id WHERE a.username = '$user';"
        if (level == GM_PLAYER) return delete
        return "$delete\nINSERT INTO account_access (id, gmlevel, RealmID) SELECT id, $level, -1 FROM account WHERE username = '$user';"
    }

    private fun checkName(name: String): String {
        val trimmed = name.trim()
        if (!Srp6.NAME.matches(trimmed)) throw InvalidException(InvalidException.Reason.NAME)
        return trimmed.uppercase()
    }

    private fun checkPassword(password: String) {
        if (!Srp6.PASSWORD.matches(password)) throw InvalidException(InvalidException.Reason.PASSWORD)
    }
}
