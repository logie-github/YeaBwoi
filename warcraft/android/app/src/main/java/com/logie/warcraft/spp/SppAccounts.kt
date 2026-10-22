// SPDX-License-Identifier: GPL-3.0-or-later
package com.logie.warcraft.spp

import android.content.Context
import com.logie.warcraft.spp.core.SppAccountRules
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

object SppAccounts {
    data class Account(val id: Long, val email: String)
    private val creation = Mutex()
    private fun database() = SppServer.authenticationDatabase ?: throw IOException("Start the SPP server before managing accounts.")
    suspend fun list(context: Context): List<Account> = SppServer.query(context, database(),
        "SELECT id,email FROM battlenet_accounts ORDER BY email;\n").lineSequence().mapNotNull { line ->
        val values = line.trim().split('\t')
        if (values.size != 2) null else values[0].toLongOrNull()?.let { Account(it, values[1]) }
    }.toList()
    suspend fun create(context: Context, email: String, password: String, administrator: Boolean = false) = creation.withLock {
        val name = SppAccountRules.normalizeEmail(email) ?: throw IOException("Enter a valid email address.")
        if (list(context).any { it.email.equals(name, true) }) throw IOException("That Battle.net account already exists. Choose it or change its password.")
        SppServer.query(context, database(), SppAccountRules.createSql(name, password, administrator))
        if (!list(context).any { it.email.equals(name, true) }) throw IOException("The new account could not be confirmed in SPP's authentication database.")
    }
    suspend fun isPassword(context: Context, email: String, password: String): Boolean {
        val name = SppAccountRules.normalizeEmail(email) ?: return false
        if (!SppAccountRules.validPassword(password)) return false
        val rows = SppServer.query(context, database(),
            "SELECT sha_pass_hash FROM battlenet_accounts WHERE email=${SppAccountRules.text(name)};\n")
            .lineSequence().filter { it.matches(Regex("[a-fA-F0-9]{64}")) }.toList()
        return rows.singleOrNull()?.equals(SppAccountRules.hash(name, password), true) == true
    }
    suspend fun changePassword(context: Context, email: String, password: String) {
        SppServer.query(context, database(), SppAccountRules.passwordSql(email, password))
        if (!isPassword(context, email, password)) throw IOException("The password change could not be confirmed.")
    }
}
