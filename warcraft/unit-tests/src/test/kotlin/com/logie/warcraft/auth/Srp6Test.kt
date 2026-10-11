package com.logie.warcraft.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Srp6Test {
    // Expected values from warcraft/server/auth_check.py, the client that logs in to the server in
    // CI (scripts/test-warcraft-server.sh): the same numbers make an account it can log in to.
    @Test
    fun verifierMatchesTheLoginClient() {
        val salt = ByteArray(32) { it.toByte() }
        assertEquals(
            "67a9594bf96decde7bcdfe46b14110cfc9a72e3894ed31081a4eeeee7149c92c",
            Srp6.hex(Srp6.verifier("TESTER", "secret123", salt)),
        )
        assertEquals(
            "1d1ca8bd5f41dce1c03e702bdd5a33aea2be2f2869530a8fc285d6d8259b9f3e",
            Srp6.hex(Srp6.verifier("PLAYER", "pw!x", ByteArray(32) { 0xFF.toByte() })),
        )
    }

    @Test
    fun caseDoesNotMatter() {
        val salt = ByteArray(32) { it.toByte() }
        assertEquals(Srp6.hex(Srp6.verifier("TESTER", "secret123", salt)), Srp6.hex(Srp6.verifier("tester", "SECRET123", salt)))
    }

    @Test
    fun verifierIs32Bytes() {
        repeat(20) {
            val salt = Srp6.newSalt()
            assertEquals(32, salt.size)
            assertEquals(32, Srp6.verifier("A$it", "password", salt).size)
        }
    }

    @Test
    fun namesAndPasswords() {
        assertTrue(Srp6.NAME.matches("Arthas"))
        assertTrue(Srp6.NAME.matches("abc123"))
        assertFalse(Srp6.NAME.matches("ab"))
        assertFalse(Srp6.NAME.matches("has space"))
        assertFalse(Srp6.NAME.matches("x'; DROP TABLE account; --"))
        assertFalse(Srp6.NAME.matches("a".repeat(17)))
        assertTrue(Srp6.PASSWORD.matches("Fr0stm0urne!"))
        assertFalse(Srp6.PASSWORD.matches("abc"))
        assertFalse(Srp6.PASSWORD.matches("two words"))
        assertFalse(Srp6.PASSWORD.matches("a".repeat(17)))
    }
}
