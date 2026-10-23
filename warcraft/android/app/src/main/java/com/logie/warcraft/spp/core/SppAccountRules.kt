// SPDX-License-Identifier: GPL-3.0-or-later
package com.logie.warcraft.spp.core

import java.security.MessageDigest
import java.util.Locale

/** AshamaneCore's linked Battle.net/game-account model, checked against the real realmd schema. */
object SppAccountRules {
    private val EMAIL = Regex("[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9.-]{1,63}")
    fun normalizeEmail(value: String): String? = value.trim().takeIf { EMAIL.matches(it) }?.uppercase(Locale.ROOT)
    fun validPassword(value: String) = value.length in 1..16 && value.all { it.code in 33..126 }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02X".format(it) }
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
    fun hash(email: String, password: String): String {
        val name = normalizeEmail(email) ?: error("Enter a valid email address")
        require(validPassword(password)) { "Use 1–16 printable password characters" }
        return hex(digest(hex(digest(name)) + ":" + password.uppercase(Locale.ROOT)).reversedArray())
    }
    fun text(value: String): String = "CONVERT(X'${hex(value.toByteArray(Charsets.UTF_8))}' USING utf8)"
    fun createSql(email: String, password: String, administrator: Boolean = false): String {
        val name = normalizeEmail(email) ?: error("Enter a valid email address")
        require(validPassword(password)) { "Use 1–16 printable password characters" }
        return "START TRANSACTION;\n" +
            "INSERT INTO battlenet_accounts(email,sha_pass_hash) VALUES(${text(name)},'${hash(name, password)}');\n" +
            "SET @spp_bnet=LAST_INSERT_ID(); SET @spp_name=CONCAT(@spp_bnet,'#1');\n" +
            "INSERT INTO account(username,sha_pass_hash,email,reg_mail,expansion,battlenet_account,battlenet_index) " +
            "VALUES(@spp_name,UPPER(SHA1(CONCAT(UPPER(@spp_name),':',${text(password.uppercase(Locale.ROOT))}))),${text(name)},${text(name)},6,@spp_bnet,1);\n" +
            "SET @spp_game=LAST_INSERT_ID();\n" +
            (if (administrator) "INSERT INTO account_access(id,gmlevel,RealmID) VALUES(@spp_game,3,-1);\n" else "") +
            "INSERT INTO realmcharacters(realmid,acctid,numchars) SELECT id,@spp_game,0 FROM realmlist;\nCOMMIT;\n"
    }
    fun passwordSql(email: String, password: String): String {
        val name = normalizeEmail(email) ?: error("Enter a valid email address")
        require(validPassword(password))
        return "START TRANSACTION;\nUPDATE battlenet_accounts SET sha_pass_hash='${hash(name, password)}',LoginTicket=NULL,LoginTicketExpiry=NULL WHERE email=${text(name)};\n" +
            "UPDATE account a JOIN battlenet_accounts b ON b.id=a.battlenet_account " +
            "SET a.sha_pass_hash=UPPER(SHA1(CONCAT(UPPER(a.username),':',${text(password.uppercase(Locale.ROOT))}))),a.v='',a.s='',a.sessionkey='' WHERE b.email=${text(name)};\nCOMMIT;\n"
    }
}
