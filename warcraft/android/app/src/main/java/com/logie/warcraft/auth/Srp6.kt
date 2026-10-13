// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.auth

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * How AzerothCore stores a password: SRP6 with the 3.3.5a client's numbers. The account table
 * keeps a random 32-byte salt and the verifier g^x mod N, where x is SHA-1(salt, SHA-1("USER:PASS")),
 * every number little-endian. The password itself is never stored, and never leaves this class.
 */
object Srp6 {
    private val N = BigInteger("894B645E89E1535BBDAD5B8B290650530801B18EBFBF5E8FAB3C82872A3E9BB7", 16)
    private val G = BigInteger.valueOf(7)
    const val SALT_LENGTH = 32

    /** Account names the 3.3.5a client can type and AzerothCore accepts. */
    val NAME = Regex("^[A-Za-z0-9]{3,16}$")

    /** Passwords the client's login box takes (it sends them uppercased). */
    val PASSWORD = Regex("^[\\x21-\\x7E]{4,16}$")

    fun newSalt(random: SecureRandom = SecureRandom()): ByteArray = ByteArray(SALT_LENGTH).also { random.nextBytes(it) }

    /** The verifier for [name] and [password] with [salt], as the 32 bytes AzerothCore stores. */
    fun verifier(name: String, password: String, salt: ByteArray): ByteArray {
        require(salt.size == SALT_LENGTH)
        val inner = sha1("${name.uppercase()}:${password.uppercase()}".toByteArray(Charsets.UTF_8))
        val x = littleEndian(sha1(salt, inner))
        return toLittleEndian(G.modPow(x, N), 32)
    }

    private fun sha1(vararg parts: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-1")
        for (part in parts) digest.update(part)
        return digest.digest()
    }

    private fun littleEndian(bytes: ByteArray): BigInteger = BigInteger(1, bytes.reversedArray())

    private fun toLittleEndian(value: BigInteger, size: Int): ByteArray {
        val big = value.toByteArray().let { if (it.size > size) it.copyOfRange(it.size - size, it.size) else it }
        val out = ByteArray(size)
        for (i in big.indices) out[i] = big[big.size - 1 - i]
        return out
    }

    /**
     * Whether [password] is [name]'s, given the account's salt and verifier as the database
     * shows them in hex (`HEX(salt)`, `HEX(verifier)`).
     */
    fun matches(name: String, password: String, saltHex: String, verifierHex: String): Boolean {
        val salt = unhex(saltHex) ?: return false
        val stored = unhex(verifierHex) ?: return false
        return salt.size == SALT_LENGTH && PASSWORD.matches(password) && verifier(name, password, salt).contentEquals(stored)
    }

    fun unhex(text: String): ByteArray? {
        if (text.length % 2 != 0) return null
        return runCatching { ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() } }.getOrNull()
    }

    fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (b in bytes) {
            append(HEX[(b.toInt() shr 4) and 0xF])
            append(HEX[b.toInt() and 0xF])
        }
    }

    private const val HEX = "0123456789abcdef"
}
