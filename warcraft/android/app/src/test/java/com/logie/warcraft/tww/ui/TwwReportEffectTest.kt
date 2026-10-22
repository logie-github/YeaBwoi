// SPDX-License-Identifier: GPL-3.0-or-later
package com.logie.warcraft.tww.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.logie.warcraft.local.WindowsRealmServer
import com.logie.warcraft.tww.TwwServer
import com.logie.warcraft.tww.TwwJobs
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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TwwReportEffectTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun frame() {
        compose.runOnUiThread { Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeBy(100)
        compose.waitForIdle()
    }

    @Test fun leavingTheScreenDoesNotReportCoroutineCancellation() {
        compose.mainClock.autoAdvance = false
        val visible = mutableStateOf(true)
        val read = CompletableDeferred<TwwInstall.Report>()
        val entered = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val errors = ConcurrentLinkedQueue<Exception>()
        val reports = ConcurrentLinkedQueue<TwwInstall.Report>()
        compose.setContent {
            Column {
                Text(if (visible.value) "Visible" else "Away")
                if (visible.value) TwwReportEffect(false, 0, 0, WindowsRealmServer.Phase.STOPPED,
                    read = {
                        entered.complete(Unit)
                        try { read.await() } finally { stopped.complete(Unit) }
                    }, onReport = { reports.add(it) }, onError = { errors.add(it) })
            }
        }
        frame()
        compose.waitUntil { entered.isCompleted }
        compose.runOnUiThread { Snapshot.withMutableSnapshot { visible.value = false } }
        frame()
        compose.onNodeWithText("Away").assertExists()
        compose.waitUntil { stopped.isCompleted }
        assertTrue("Leaving composition is normal, not an installation error", errors.isEmpty())
        assertTrue(reports.isEmpty())
        read.cancel()
    }

    @Test fun startingAJobCancelsTheOldScanAndCompletionReadsFreshFiles() {
        compose.mainClock.autoAdvance = false
        val job = mutableStateOf<TwwJobs.State>(TwwJobs.State.Idle)
        val firstRead = CompletableDeferred<TwwInstall.Report>()
        val stopped = CompletableDeferred<Unit>()
        val errors = ConcurrentLinkedQueue<Exception>()
        val reports = ConcurrentLinkedQueue<TwwInstall.Report>()
        val count = AtomicInteger()
        val root = Files.createTempDirectory("tww-ui-report-").toFile()
        try {
            val fresh = TwwInstall.inspect(File(root, "client"), File(root, "server"))
            compose.setContent {
                val current = job.value
                val busy = current is TwwJobs.State.Working
                Column {
                    Text(current.javaClass.simpleName)
                    TwwReportEffect(busy, if (busy) null else current, 0, WindowsRealmServer.Phase.STOPPED,
                        read = {
                            if (count.incrementAndGet() == 1) {
                                try { firstRead.await() } finally { stopped.complete(Unit) }
                            } else fresh
                        }, onReport = { reports.add(it) }, onError = { errors.add(it) })
                }
            }
            frame()
            compose.waitUntil { count.get() == 1 }
            compose.runOnUiThread { Snapshot.withMutableSnapshot { job.value = TwwJobs.State.Working(TwwJobs.Kind.VERIFY) } }
            frame()
            compose.onNodeWithText("Working").assertExists()
            compose.waitUntil { stopped.isCompleted }
            assertTrue(errors.isEmpty())
            assertTrue(reports.isEmpty())
            compose.runOnUiThread { Snapshot.withMutableSnapshot { job.value = TwwJobs.State.Done(TwwJobs.Kind.VERIFY, "Checked") } }
            frame()
            compose.onNodeWithText("Done").assertExists()
            assertEquals("reads=${count.get()}, errors=$errors", 1, reports.size)
            assertSame(fresh, reports.single())
            assertEquals(2, count.get())
            assertTrue(errors.isEmpty())
        } finally { firstRead.cancel(); root.deleteRecursively() }
    }

    @Test fun actualReadFailureStillShowsItsActionableError() {
        val errors = ConcurrentLinkedQueue<Exception>()
        val failure = IOException("Selected storage is unavailable")
        compose.setContent {
            TwwReportEffect(false, 0, 0, WindowsRealmServer.Phase.STOPPED,
                read = { throw failure }, onReport = { fail("A failed read must not publish a report") }, onError = { errors.add(it) })
        }
        compose.waitUntil { errors.isNotEmpty() }
        assertSame(failure, errors.single())
    }
}
