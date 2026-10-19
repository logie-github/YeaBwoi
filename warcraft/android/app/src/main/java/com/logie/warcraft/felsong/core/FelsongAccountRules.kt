// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.felsong.core

import java.security.MessageDigest

/**
 * Felsong's accounts, in its own login database (`auth.account`), apart from ChromieCraft's
 * (`acore_auth`) and Turtle's (`tw_logon`): made the way LegionCore's own `account create` makes
 * them (src/server/game/Accounts/AccountMgr.cpp). Legion logs in Battle.net style, with an e-mail
 * as the name: the name and password in capitals (Latin letters only), and `sha_pass_hash` the
 * SHA-256 of `<SHA-256 of the name, in capital hex>:<password>`, its bytes reversed, in capital hex
 * (ByteArrayToHexStr with reverse, as the core calls it). The Battle.net
 * server's REST login compares the same hash (bnetserver/REST/LoginRESTService.cpp). The SQL here
 * is only ever made from names and passwords that passed [NAME] and [PASSWORD], which allow no
 * quote or backslash.
 */
object FelsongAccountRules {
    /**
     * An e-mail address of up to 64 characters (LegionCore's MAX_EMAIL_STR is 320; the login box
     * takes an e-mail): letters, digits, `.`, `_`, `-` and one `@`.
     */
    val NAME = Regex("[A-Za-z0-9._-]{1,48}@[A-Za-z0-9.-]{1,15}")

    /** Up to 16 printable characters without quotes, backslashes or spaces (MAX_PASS_STR is 16). */
    val PASSWORD = Regex("""[A-Za-z0-9!#$%&()*+,\-./:;<=>?@\[\]^_{|}~]{1,16}""")

    /** LegionCore's security levels (AccountTypes): the ones offered in the app. */
    const val LEVEL_PLAYER = 0
    const val LEVEL_MODERATOR = 1
    const val LEVEL_GAMEMASTER = 2
    const val LEVEL_ADMINISTRATOR = 3
    val LEVELS = listOf(LEVEL_PLAYER, LEVEL_MODERATOR, LEVEL_GAMEMASTER, LEVEL_ADMINISTRATOR)

    fun normalizeName(name: String): String? = name.trim().takeIf { NAME.matches(it) }?.uppercase()

    fun isPassword(password: String): Boolean = PASSWORD.matches(password)

    private fun sha256(text: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02X".format(it) }

    /** `sha_pass_hash`: SHA-256 of `HEX(SHA-256(NAME)):PASSWORD` (both in capitals), bytes reversed, capital hex. */
    fun shaPassHash(name: String, password: String): String =
        hex(sha256(hex(sha256(name.uppercase())) + ":" + password.uppercase()).reversedArray())

    /** The SQL that makes an account (already checked: [name] from [normalizeName]) with security [level] on every realm. */
    fun createSql(name: String, password: String, level: Int): String {
        require(NAME.matches(name) && isPassword(password)) { "Unchecked account" }
        val security = level.coerceIn(LEVEL_PLAYER, LEVEL_ADMINISTRATOR)
        val user = name.uppercase()
        return "INSERT INTO account (username, sha_pass_hash, email, joindate) VALUES " +
            "('$user', '${shaPassHash(name, password)}', '$user', NOW());\n" +
            (if (security > LEVEL_PLAYER) "INSERT INTO account_access (id, gmlevel, RealmID) SELECT id, $security, -1 FROM account WHERE username = '$user';\n" else "") +
            // As the server does: the account's character count on each realm.
            "REPLACE INTO realmcharacters (realmid, acctid, numchars) SELECT realmlist.id, account.id, 0 " +
            "FROM realmlist, account LEFT JOIN realmcharacters ON acctid = account.id WHERE acctid IS NULL;"
    }

    /** A new password: the hash alone is what the login checks. */
    fun passwordSql(name: String, password: String): String {
        require(NAME.matches(name) && isPassword(password)) { "Unchecked account" }
        return "UPDATE account SET v = '', s = '', sessionkey = '', sha_pass_hash = '${shaPassHash(name, password)}' " +
            "WHERE username = '${name.uppercase()}';"
    }

    fun levelSql(name: String, level: Int): String {
        require(NAME.matches(name)) { "Unchecked account" }
        val security = level.coerceIn(LEVEL_PLAYER, LEVEL_ADMINISTRATOR)
        val user = name.uppercase()
        return "DELETE aa FROM account_access aa JOIN account a ON a.id = aa.id WHERE a.username = '$user' AND aa.RealmID = -1;\n" +
            if (security > LEVEL_PLAYER) "INSERT INTO account_access (id, gmlevel, RealmID) SELECT id, $security, -1 FROM account WHERE username = '$user';" else ""
    }

    /** Whether [password] is the account's: the hash it makes is the stored one. */
    fun matches(name: String, password: String, storedHash: String): Boolean =
        isPassword(password) && shaPassHash(name, password).equals(storedHash.trim(), ignoreCase = true)
}
