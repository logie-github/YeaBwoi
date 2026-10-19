// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.felsong

import android.content.Context
import com.logie.warcraft.felsong.core.FelsongAccountRules
import com.logie.warcraft.felsong.core.FelsongRealm
import java.io.File

/**
 * Felsong's own settings, in their own preferences (`felsong_settings`), written into Felsong's
 * `etc/worldserver.conf` at each start of its realm ([applyToConfig]). The other realms' configs
 * are never read or written here.
 */
object FelsongSettings {
    private const val PREFS = "felsong_settings"
    private const val KEY_LAN = "lan"
    private const val KEY_CONF = "conf."

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** PCs on the same Wi-Fi may play on the realm (off: this phone only, the realm on 127.0.0.1). */
    fun lan(context: Context) = prefs(context).getBoolean(KEY_LAN, false)

    fun setLan(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_LAN, on).apply()

    fun value(context: Context, setting: FelsongRealm.Setting): String =
        prefs(context).getString(KEY_CONF + setting.key, null)?.takeIf { it in setting.choices } ?: setting.default

    fun setValue(context: Context, setting: FelsongRealm.Setting, value: String) {
        require(value in setting.choices)
        prefs(context).edit().putString(KEY_CONF + setting.key, value).apply()
    }

    /** Make pathfinding (mmaps, hours on a phone) when the server's data is made. */
    fun mmaps(context: Context) = prefs(context).getBoolean("mmaps", false)

    fun setMmaps(context: Context, on: Boolean) = prefs(context).edit().putBoolean("mmaps", on).apply()

    /** How many accounts the realm had when last asked (-1: never asked), so the launcher knows without the database. */
    fun knownAccounts(context: Context) = prefs(context).getInt("accounts", -1)

    fun setKnownAccounts(context: Context, count: Int) = prefs(context).edit().putInt("accounts", count).apply()

    /** Back to the defaults. */
    fun reset(context: Context) = prefs(context).edit().clear().apply()

    /** The settings into worldserver.conf, and what the server's data allows. */
    fun applyToConfig(context: Context) {
        val conf = File(FelsongFiles.layout(context).etc, "worldserver.conf")
        val parts = FelsongFiles.dataParts(context)
        FelsongServer.editConf(conf) {
            for (setting in FelsongRealm.SETTINGS) set(setting.key, value(context, setting))
            for ((key, value) in FelsongRealm.FIXED_SETTINGS) set(key, value)
            for ((key, value) in FelsongRealm.dataSettings(parts)) set(key, value)
        }
    }
}

/** Felsong's game accounts, in its own login database (auth). */
object FelsongAccounts {
    data class Account(val id: Int, val name: String, val level: Int)

    class InvalidException(val reason: Reason) : java.io.IOException(reason.name) {
        enum class Reason { NAME, PASSWORD, TAKEN }
    }

    fun list(context: Context): List<Account> =
        FelsongDb.query(
            context, "auth",
            "SELECT a.id, a.username, COALESCE(aa.gmlevel, 0) FROM account a LEFT JOIN account_access aa ON aa.id = a.id AND aa.RealmID = -1 ORDER BY a.id;",
        ).mapNotNull { row ->
            val id = row.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
            Account(id, row.getOrElse(1) { "" }, row.getOrNull(2)?.toIntOrNull() ?: 0)
        }.also { FelsongSettings.setKnownAccounts(context, it.size) }

    fun create(context: Context, name: String, password: String, level: Int) {
        val user = FelsongAccountRules.normalizeName(name) ?: throw InvalidException(InvalidException.Reason.NAME)
        if (!FelsongAccountRules.isPassword(password)) throw InvalidException(InvalidException.Reason.PASSWORD)
        if (list(context).any { it.name.equals(user, ignoreCase = true) }) throw InvalidException(InvalidException.Reason.TAKEN)
        FelsongDb.exec(context, "auth", FelsongAccountRules.createSql(user, password, level))
        list(context)
    }

    fun setPassword(context: Context, name: String, password: String) {
        val user = FelsongAccountRules.normalizeName(name) ?: throw InvalidException(InvalidException.Reason.NAME)
        if (!FelsongAccountRules.isPassword(password)) throw InvalidException(InvalidException.Reason.PASSWORD)
        FelsongDb.exec(context, "auth", FelsongAccountRules.passwordSql(user, password))
        if (FelsongLogin.get(context)?.name.equals(user, ignoreCase = true)) FelsongLogin.save(context, user, password)
    }

    fun setLevel(context: Context, name: String, level: Int) {
        val user = FelsongAccountRules.normalizeName(name) ?: throw InvalidException(InvalidException.Reason.NAME)
        FelsongDb.exec(context, "auth", FelsongAccountRules.levelSql(user, level))
    }

    fun isPassword(context: Context, name: String, password: String): Boolean {
        val user = FelsongAccountRules.normalizeName(name) ?: return false
        if (!FelsongAccountRules.isPassword(password)) return false
        val stored = FelsongDb.query(context, "auth", "SELECT sha_pass_hash FROM account WHERE username = '$user';")
            .firstOrNull()?.firstOrNull() ?: return false
        return FelsongAccountRules.matches(user, password, stored)
    }

    /** Deletes the account (its characters stay in the characters database, without an owner). */
    fun delete(context: Context, name: String) {
        val user = FelsongAccountRules.normalizeName(name) ?: throw InvalidException(InvalidException.Reason.NAME)
        FelsongDb.exec(
            context, "auth",
            "DELETE rc FROM realmcharacters rc JOIN account a ON a.id = rc.acctid WHERE a.username = '$user';\n" +
                "DELETE aa FROM account_access aa JOIN account a ON a.id = aa.id WHERE a.username = '$user';\n" +
                "DELETE FROM account WHERE username = '$user';",
        )
        if (FelsongLogin.get(context)?.name.equals(user, ignoreCase = true)) FelsongLogin.forget(context)
        list(context)
    }

    /** How many characters [account] has (none: the game goes to character creation). */
    fun characters(context: Context, account: Int): Int =
        FelsongDb.query(context, "characters", "SELECT COUNT(*) FROM characters WHERE account = $account;")
            .firstOrNull()?.firstOrNull()?.toIntOrNull() ?: 0
}

/**
 * The Felsong account the game logs in with by itself, and its password, in their own preferences
 * (`felsong_login`): never the other realms' saved logins.
 */
object FelsongLogin {
    data class Login(val name: String, val password: String)

    private const val PREFS = "felsong_login"

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

    /** Uninstall: Felsong's preferences go too (its settings and saved login). */
    fun forgetAll(context: Context) {
        forget(context)
        FelsongSettings.reset(context)
    }
}
