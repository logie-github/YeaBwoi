// SPDX-License-Identifier: GPL-3.0-or-later
package com.logie.warcraft.tww

import android.app.Application
import android.app.Service
import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TwwServiceTest {
    @Test fun verificationSurvivesRemovingTheLauncherAndRetainsItsRestartIntent() {
        val context = RuntimeEnvironment.getApplication()
        val controller = Robolectric.buildService(TwwService::class.java).create()
        val service = controller.get()
        try {
            val intent = Intent(context, TwwService::class.java).putExtra("kind", TwwJobs.Kind.VERIFY.name)
            val restart = service.onStartCommand(intent, 0, 1)
            service.onTaskRemoved(Intent())
            assertEquals(Service.START_REDELIVER_INTENT, restart)
            val worker = TwwService::class.java.getDeclaredField("worker").apply { isAccessible = true }.get(service) as Thread
            worker.join(5000)
            assertFalse("Verification should finish without a launcher activity", worker.isAlive)
            val done = TwwJobs.state.value as? TwwJobs.State.Done
            assertNotNull("Removing the task must not cancel the job: ${TwwJobs.state.value}", done)
            assertEquals(TwwJobs.Kind.VERIFY, done?.kind)
        } finally { controller.destroy(); TwwJobs.clearResult() }
    }
}
