package com.logie.warcraft.turtle.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the world server needs to let a player in on the phone: on the user's phone it crashed
 * (SIGSEGV) as each player came in. Tortoise's anticheat, on by its own defaults, set up Warden's
 * RC4, which needs OpenSSL's legacy module, and nothing told OpenSSL where the package keeps it.
 */
class TurtleWorldServerTest {
    private val ctl = File("../turtle/server/ctl.sh").readText()

    @Test
    fun theAnticheatIsOffInTheSectionTortoiseReads() {
        // Tortoise's Config looks values up inside sections (Config::GetValueHelper): a bare
        // "Enable = 0" would be ignored and its default (on) used.
        val lines = TurtleRealm.ANTICHEAT_CONF.lines().filter { it.isNotBlank() }
        assertEquals("[AnticheatConf]", lines.first())
        assertEquals("Enable = 0", lines[1])
    }

    @Test
    fun theRealmScriptWritesTheSameAnticheatConfAsTheApp() {
        val printf = Regex("""printf '([^']*)' > "\${'$'}RUN/anticheat.conf"""").find(ctl)
            ?: error("ctl.sh doesn't write \$RUN/anticheat.conf")
        assertEquals(TurtleRealm.ANTICHEAT_CONF, printf.groupValues[1].replace("\\n", "\n"))
    }

    @Test
    fun bothRealmScriptsPointOpenSslAtThePackagedModules() {
        assertTrue("export OPENSSL_MODULES=\"\$SERVER/${TurtleRealm.OPENSSL_MODULES}\"" in ctl, "Turtle's ctl.sh")
        // ChromieCraft's has always had it (it's where the line came from).
        val wrath = File("../server/ctl.sh").readText()
        assertTrue("export OPENSSL_MODULES=\"\$SERVER/lib/ossl-modules\"" in wrath, "the 3.3.5a ctl.sh")
        assertTrue("\"\$pkg/lib/ossl-modules\"" in File("../../scripts/package-tortoise.sh").readText(), "package-tortoise.sh packs it there")
    }
}
