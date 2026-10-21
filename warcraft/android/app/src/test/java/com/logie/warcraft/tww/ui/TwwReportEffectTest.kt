// SPDX-License-Identifier: GPL-3.0-or-later
package com.logie.warcraft.tww.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.logie.warcraft.tww.TwwServer
import com.logie.warcraft.tww.core.TwwInstall
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TwwReportEffectTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun leavingTheScreenDoesNotReportCoroutineCancellation() {
        val visible = mutableStateOf(true)
        val read = CompletableDeferred<TwwInstall.Report>()
        val entered = CompletableDeferred<Unit>()
        val errors = mutableListOf<Exception>()
        val reports = mutableListOf<TwwInstall.Report>()
        compose.setContent {
            if (visible.value) TwwReportEffect(false, 0, 0, TwwServer.Phase.STOPPED,
                read = { entered.complete(Unit); read.await() }, onReport = reports::add, onError = errors::add)
        }
        compose.waitUntil { entered.isCompleted }
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        assertTrue("Leaving composition is normal, not an installation error", errors.isEmpty())
        assertTrue(reports.isEmpty())
        read.cancel()
    }

    @Test fun startingAJobCancelsTheOldScanAndCompletionReadsFreshFiles() {
        val busy = mutableStateOf(false)
        val version = mutableStateOf(0)
        val firstRead = CompletableDeferred<TwwInstall.Report>()
        val errors = mutableListOf<Exception>()
        val reports = mutableListOf<TwwInstall.Report>()
        var count = 0
        val root = Files.createTempDirectory("tww-ui-report-").toFile()
        try {
            val fresh = TwwInstall.inspect(File(root, "client"), File(root, "server"))
            compose.setContent {
                TwwReportEffect(busy.value, version.value, 0, TwwServer.Phase.STOPPED,
                    read = { count++; if (count == 1) firstRead.await() else fresh },
                    onReport = reports::add, onError = errors::add)
            }
            compose.waitUntil { count == 1 }
            compose.runOnIdle { busy.value = true }
            compose.waitForIdle()
            assertTrue(errors.isEmpty())
            assertTrue(reports.isEmpty())
            compose.runOnIdle { busy.value = false; version.value++ }
            compose.waitUntil { reports.size == 1 }
            assertSame(fresh, reports.single())
            assertEquals(2, count)
            assertTrue(errors.isEmpty())
        } finally { firstRead.cancel(); root.deleteRecursively() }
    }

    @Test fun actualReadFailureStillShowsItsActionableError() {
        val errors = mutableListOf<Exception>()
        val failure = IOException("Selected storage is unavailable")
        compose.setContent {
            TwwReportEffect(false, 0, 0, TwwServer.Phase.STOPPED,
                read = { throw failure }, onReport = { fail("A failed read must not publish a report") }, onError = errors::add)
        }
        compose.waitUntil { errors.isNotEmpty() }
        assertSame(failure, errors.single())
    }
}
