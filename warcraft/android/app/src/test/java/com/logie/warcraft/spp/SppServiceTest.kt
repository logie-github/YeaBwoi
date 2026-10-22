// SPDX-License-Identifier: GPL-3.0-or-later
package com.logie.warcraft.spp

import android.app.Application
import android.app.Service
import android.content.Intent
import com.logie.warcraft.local.LocalInstallJobs
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SppServiceTest {
    @Test fun verificationBelongsToTheBackgroundServiceAndSurvivesTaskRemoval() {
        val context = RuntimeEnvironment.getApplication()
        val controller = Robolectric.buildService(SppService::class.java).create()
        val service = controller.get()
        try {
            val restart = service.onStartCommand(Intent(context, SppService::class.java).putExtra("kind", LocalInstallJobs.Kind.VERIFY.name), 0, 1)
            service.onTaskRemoved(Intent())
            assertEquals(Service.START_REDELIVER_INTENT, restart)
            val worker = SppService::class.java.getDeclaredField("worker").apply { isAccessible = true }.get(service) as Thread
            worker.join(5000)
            assertFalse(worker.isAlive)
            val result = SppJobs.state.value as? LocalInstallJobs.State.Done
            assertNotNull("SPP verification must finish without a launcher", result)
            assertEquals(LocalInstallJobs.Kind.VERIFY, result?.kind)
        } finally { controller.destroy(); SppJobs.clearResult() }
    }
}
