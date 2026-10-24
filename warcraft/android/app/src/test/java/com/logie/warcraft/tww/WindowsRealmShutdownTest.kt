// SPDX-License-Identifier: GPL-3.0-or-later
package com.logie.warcraft.tww

import android.app.Application
import com.logie.warcraft.local.WindowsRealmServer
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WindowsRealmShutdownTest {
    @Test fun closedProcessesWithPublisherAccessViolationAreNotReportedAsCleanShutdown() {
        val context = RuntimeEnvironment.getApplication()
        val runtime = File(TwwFiles.layout(context).root, "runtime").apply { mkdirs() }
        try {
            File(runtime, "world.exit").writeText("3221225477\r\n")
            File(runtime, "authentication.exit").writeText("0\r\n")
            File(runtime, "database.exit").writeText("0\r\n")
            val result = TwwServer.shutdownResult(context)
            assertEquals(WindowsRealmServer.Phase.STOPPED, result.phase)
            assertEquals(WindowsRealmServer.ComponentStatus.STOPPED, result.database)
            assertTrue(result.error.orEmpty().contains("world exited with code 3221225477"))
            assertTrue(result.error.orEmpty().contains("clean save has not been verified"))
            File(runtime, "world.exit").writeText("0\r\n")
            assertNull(TwwServer.shutdownResult(context).error)
            // Stop ends the world and authentication servers with taskkill /F: their code is 1.
            File(runtime, "world.exit").writeText("1\r\n")
            File(runtime, "authentication.exit").writeText("1\r\n")
            assertNull(TwwServer.shutdownResult(context).error)
            File(runtime, "database.exit").writeText("1\r\n")
            assertTrue(TwwServer.shutdownResult(context).error.orEmpty().contains("database exited with code 1"))
        } finally { runtime.deleteRecursively() }
    }
}
