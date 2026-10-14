// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.turtle.core

import java.security.MessageDigest

/**
 * Turtle WoW's accounts, in its own login database (`tw_logon.account`), apart from ChromieCraft's
 * (`acore_auth`): made the way Tortoise-WoW's own `account create` makes them
 * (src/game/AccountMgr.cpp): the name and password in capitals, and `sha_pass_hash` the SHA-1 of
 * `NAME:PASSWORD` in capital hex. The login server works out its SRP6 values from that at the
 * first login. The SQL here is only ever made from names and passwords that passed [NAME] and
 * [PASSWORD], which allow no quote or backslash.
 */
object TurtleAccountRules {
    /** Tortoise's MAX_ACCOUNT_STR is 16; letters and digits only, as the game's login box takes them. */
    val NAME = Regex("[A-Za-z0-9]{1,16}")

    /** Up to 16 printable characters without quotes, backslashes or spaces. */
    val PASSWORD = Regex("""[A-Za-z0-9!#$%&()*+,\-./:;<=>?@\[\]^_{|}~]{1,16}""")

    /** Tortoise's ranks (docs/setup/accounts.md): the ones offered in the app. */
    const val RANK_PLAYER = 0
    const val RANK_MODERATOR = 2
    const val RANK_DEVELOPER = 3
    const val RANK_ADMINISTRATOR = 4
    val RANKS = listOf(RANK_PLAYER, RANK_MODERATOR, RANK_DEVELOPER, RANK_ADMINISTRATOR)

    fun normalizeName(name: String): String? = name.trim().takeIf { NAME.matches(it) }?.uppercase()

    fun isPassword(password: String): Boolean = PASSWORD.matches(password)

    /** `sha_pass_hash`: SHA-1 of `NAME:PASSWORD` (both in capitals), capital hex. */
    fun shaPassHash(name: String, password: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest("${name.uppercase()}:${password.uppercase()}".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02X".format(it) }
    }

    /** The SQL that makes an account (already checked: [name] from [normalizeName]). */
    fun createSql(name: String, password: String, rank: Int): String {
        require(NAME.matches(name) && isPassword(password)) { "Unchecked account" }
        val level = rank.coerceIn(RANK_PLAYER, RANK_ADMINISTRATOR)
        return "INSERT INTO account (username, sha_pass_hash, `rank`, joindate) VALUES " +
            "('${name.uppercase()}', '${shaPassHash(name, password)}', $level, NOW());\n" +
            // As the server's own account create does: the account's character count on each realm.
            "REPLACE INTO realmcharacters (realmid, acctid, numchars) SELECT realmlist.id, account.id, 0 " +
            "FROM realmlist, account LEFT JOIN realmcharacters ON acctid = account.id WHERE acctid IS NULL;"
    }

    /** A new password: the login server makes its SRP6 values again from the new hash. */
    fun passwordSql(name: String, password: String): String {
        require(NAME.matches(name) && isPassword(password)) { "Unchecked account" }
        return "UPDATE account SET v = '0', s = '0', sha_pass_hash = '${shaPassHash(name, password)}' " +
            "WHERE username = '${name.uppercase()}';"
    }

    fun rankSql(name: String, rank: Int): String {
        require(NAME.matches(name)) { "Unchecked account" }
        return "UPDATE account SET `rank` = ${rank.coerceIn(RANK_PLAYER, RANK_ADMINISTRATOR)} WHERE username = '${name.uppercase()}';"
    }

    /** Whether [password] is the account's: the hash it makes is the stored one. */
    fun matches(name: String, password: String, storedHash: String): Boolean =
        isPassword(password) && shaPassHash(name, password).equals(storedHash.trim(), ignoreCase = true)
}
