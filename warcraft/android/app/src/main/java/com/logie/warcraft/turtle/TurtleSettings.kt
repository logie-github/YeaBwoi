// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.turtle

import android.content.Context
import com.logie.warcraft.turtle.core.TurtleAccountRules
import com.logie.warcraft.turtle.core.TurtleRealm
import java.io.File

/**
 * Turtle WoW's own settings, in their own preferences (`turtle_settings`), written into Turtle's
 * `etc/mangosd.conf` at each start of its realm ([applyToConfig]). The ChromieCraft realm's configs
 * (`files/etc`) are never read or written here.
 */
object TurtleSettings {
    private const val PREFS = "turtle_settings"
    private const val KEY_LAN = "lan"
    private const val KEY_RATE = "conf."

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** PCs on the same Wi-Fi may play on the realm (off: this phone only, the realm on 127.0.0.1). */
    fun lan(context: Context) = prefs(context).getBoolean(KEY_LAN, false)

    fun setLan(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_LAN, on).apply()

    fun value(context: Context, setting: TurtleRealm.Setting): String =
        prefs(context).getString(KEY_RATE + setting.key, null)?.takeIf { it in setting.choices } ?: setting.default

    fun setValue(context: Context, setting: TurtleRealm.Setting, value: String) {
        require(value in setting.choices)
        prefs(context).edit().putString(KEY_RATE + setting.key, value).apply()
    }

    /** Make pathfinding (mmaps, hours on a phone) when the server's data is made. */
    fun mmaps(context: Context) = prefs(context).getBoolean("mmaps", false)

    fun setMmaps(context: Context, on: Boolean) = prefs(context).edit().putBoolean("mmaps", on).apply()

    /** How many accounts the realm had when last asked (-1: never asked), so the launcher knows without the database. */
    fun knownAccounts(context: Context) = prefs(context).getInt("accounts", -1)

    fun setKnownAccounts(context: Context, count: Int) = prefs(context).edit().putInt("accounts", count).apply()

    /** Back to Tortoise's defaults. */
    fun reset(context: Context) = prefs(context).edit().clear().apply()

    /**
     * The settings into mangosd.conf, and what the server's data allows: line of sight and heights
     * need vmaps, pathfinding mmaps. A local realm: normal (not PvP), no queue.
     */
    fun applyToConfig(context: Context) {
        val conf = File(TurtleFiles.layout(context).etc, "mangosd.conf")
        val parts = TurtleFiles.dataParts(context)
        TurtleServer.editConf(conf) {
            for (setting in TurtleRealm.SETTINGS) set(setting.key, value(context, setting))
            setSwitch("vmap.enableLOS", "vmaps" in parts)
            setSwitch("vmap.enableHeight", "vmaps" in parts)
            setSwitch("vmap.enableIndoorCheck", "vmaps" in parts)
            setSwitch("mmap.enabled", "mmaps" in parts)
            set("GameType", "0")
            set("PlayerLimit", "0")
        }
        TurtleServer.editConf(File(TurtleFiles.layout(context).etc, "realmd.conf")) {
            for ((key, value) in TurtleRealm.REALMD_SETTINGS) set(key, value)
        }
    }
}

/** Turtle WoW's game accounts, in its own login database (tw_logon). */
object TurtleAccounts {
    data class Account(val id: Int, val name: String, val rank: Int)

    class InvalidException(val reason: Reason) : java.io.IOException(reason.name) {
        enum class Reason { NAME, PASSWORD, TAKEN }
    }

    fun list(context: Context): List<Account> =
        TurtleDb.query(context, "tw_logon", "SELECT id, username, `rank` FROM account ORDER BY id;").mapNotNull { row ->
            val id = row.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
            Account(id, row.getOrElse(1) { "" }, row.getOrNull(2)?.toIntOrNull() ?: 0)
        }.also { TurtleSettings.setKnownAccounts(context, it.size) }

    fun create(context: Context, name: String, password: String, rank: Int) {
        val user = TurtleAccountRules.normalizeName(name) ?: throw InvalidException(InvalidException.Reason.NAME)
        if (!TurtleAccountRules.isPassword(password)) throw InvalidException(InvalidException.Reason.PASSWORD)
        if (list(context).any { it.name.equals(user, ignoreCase = true) }) throw InvalidException(InvalidException.Reason.TAKEN)
        TurtleDb.exec(context, "tw_logon", TurtleAccountRules.createSql(user, password, rank))
        list(context)
    }

    fun setPassword(context: Context, name: String, password: String) {
        val user = TurtleAccountRules.normalizeName(name) ?: throw InvalidException(InvalidException.Reason.NAME)
        if (!TurtleAccountRules.isPassword(password)) throw InvalidException(InvalidException.Reason.PASSWORD)
        TurtleDb.exec(context, "tw_logon", TurtleAccountRules.passwordSql(user, password))
        if (TurtleLogin.get(context)?.name.equals(user, ignoreCase = true)) TurtleLogin.save(context, user, password)
    }

    fun setRank(context: Context, name: String, rank: Int) {
        val user = TurtleAccountRules.normalizeName(name) ?: throw InvalidException(InvalidException.Reason.NAME)
        TurtleDb.exec(context, "tw_logon", TurtleAccountRules.rankSql(user, rank))
    }

    fun isPassword(context: Context, name: String, password: String): Boolean {
        val user = TurtleAccountRules.normalizeName(name) ?: return false
        if (!TurtleAccountRules.isPassword(password)) return false
        val stored = TurtleDb.query(context, "tw_logon", "SELECT sha_pass_hash FROM account WHERE username = '$user';")
            .firstOrNull()?.firstOrNull() ?: return false
        return TurtleAccountRules.matches(user, password, stored)
    }

    /** Deletes the account (its characters stay in tw_char, without an owner, like ChromieCraft's). */
    fun delete(context: Context, name: String) {
        val user = TurtleAccountRules.normalizeName(name) ?: throw InvalidException(InvalidException.Reason.NAME)
        TurtleDb.exec(
            context, "tw_logon",
            "DELETE rc FROM realmcharacters rc JOIN account a ON a.id = rc.acctid WHERE a.username = '$user';\n" +
                "DELETE FROM account WHERE username = '$user';",
        )
        if (TurtleLogin.get(context)?.name.equals(user, ignoreCase = true)) TurtleLogin.forget(context)
        list(context)
    }

    /** How many characters [account] has (none: the game goes to character creation). */
    fun characters(context: Context, account: Int): Int =
        TurtleDb.query(context, "tw_char", "SELECT COUNT(*) FROM characters WHERE account = $account;")
            .firstOrNull()?.firstOrNull()?.toIntOrNull() ?: 0
}

/**
 * The Turtle WoW account the game logs in with by itself, and its password, in their own
 * preferences (`turtle_login`): never the ChromieCraft realm's saved login (SavedLogin).
 */
object TurtleLogin {
    data class Login(val name: String, val password: String)

    private const val PREFS = "turtle_login"

    fun get(context: Context): Login? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val name = prefs.getString("name", null) ?: return null
        val password = prefs.getString("password", null) ?: return null
        return Login(name, password)
    }

    fun save(context: Context, name: String, password: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("name", name.uppercase()).putString("password", password).apply()
    }

    fun forget(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /** Uninstall: Turtle's preferences go too (its settings and saved login). */
    fun forgetAll(context: Context) {
        forget(context)
        TurtleSettings.reset(context)
    }
}
