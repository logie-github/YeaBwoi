// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.tww.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TwwPlacementTest {
    private fun temporary(run: (File) -> Unit) {
        val root = Files.createTempDirectory("tww-place-").toFile()
        try { run(root) } finally { root.deleteRecursively() }
    }

    private fun folder(root: File, path: String, text: String): File = File(root, path).apply {
        mkdirs()
        File(this, "payload").writeText(text)
    }

    @Test fun installsMasterAndServerMapCopyTogether() = temporary { root ->
        val maps = folder(root, "maps", "previous maps")
        val deployed = folder(root, "server/data/mmaps", "previous deployed maps")
        val nextMaps = folder(root, "staging/maps", "new maps")
        val nextDeployed = folder(root, "staging/deployed", "new deployed maps")
        TwwPlacement.replace(root, listOf(nextMaps to maps, nextDeployed to deployed))
        assertEquals("new maps", File(maps, "payload").readText())
        assertEquals("new deployed maps", File(deployed, "payload").readText())
        assertFalse(TwwPlacement.pending(root))
        assertFalse(File(root, "maps.tww-old").exists())
        assertFalse(File(root, "server/data/mmaps.tww-old").exists())
    }

    @Test fun failedSecondPlacementRestoresBothPreviousComponents() = temporary { root ->
        val maps = folder(root, "maps", "previous maps")
        val deployed = folder(root, "server/data/mmaps", "previous deployed maps")
        val next = folder(root, "staging/maps", "new maps")
        assertFailsWith<IOException> {
            TwwPlacement.replace(root, listOf(next to maps, File(root, "staging/missing") to deployed))
        }
        assertEquals("previous maps", File(maps, "payload").readText())
        assertEquals("previous deployed maps", File(deployed, "payload").readText())
        assertFalse(TwwPlacement.pending(root))
    }

    @Test fun recoversInterruptedReplacementAndFirstTimeInstallation() = temporary { root ->
        val old = folder(root, "maps.tww-old", "original")
        val maps = folder(root, "maps", "uncommitted replacement")
        val fresh = folder(root, "client", "uncommitted new client")
        File(root, "install.transaction").writeText(
            "staging/maps\tmaps\tmaps.tww-old\ttrue\n" +
                "staging/client\tclient\tclient.tww-old\tfalse\n")
        assertTrue(TwwPlacement.pending(root))
        TwwPlacement.recover(root)
        assertEquals("original", File(maps, "payload").readText())
        assertFalse(fresh.exists())
        assertFalse(old.exists())
        assertFalse(TwwPlacement.pending(root))
        // Recovery is idempotent after another service start.
        TwwPlacement.recover(root)
        assertEquals("original", File(maps, "payload").readText())
    }

    @Test fun rejectsOutsideTargetsAndUnsafeRecoveryBeforeTouchingFiles() = temporary { parent ->
        val root = File(parent, "tww").apply { mkdirs() }
        val source = folder(root, "staging/client", "new")
        val other = folder(parent, "other-expansion", "protected")
        assertFailsWith<IOException> { TwwPlacement.replace(root, listOf(source to other)) }
        assertEquals("protected", File(other, "payload").readText())
        File(root, "install.transaction").writeText("staging/client\t../other-expansion\tclient.tww-old\ttrue")
        assertFailsWith<IOException> { TwwPlacement.recover(root) }
        assertEquals("protected", File(other, "payload").readText())
        assertTrue(source.isDirectory)
        assertTrue(TwwPlacement.pending(root))
    }

    @Test fun damagedJournalPreservesInstallationForManualRecovery() = temporary { root ->
        val backup = folder(root, "server.tww-old", "original")
        File(root, "install.transaction").writeText("")
        assertFailsWith<IOException> { TwwPlacement.recover(root) }
        assertEquals("original", File(backup, "payload").readText())
        assertTrue(TwwPlacement.pending(root))
    }
}
