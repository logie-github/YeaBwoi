// SPDX-License-Identifier: GPL-3.0-or-later
package com.logie.warcraft.game

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.preference.PreferenceManager
import com.winlator.services.ForegroundService
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BackgroundSessionTest {
    private lateinit var context: Context
    private lateinit var controller: ServiceController<ForegroundService>
    private lateinit var service: ForegroundService

    @Before fun startSession() {
        context = RuntimeEnvironment.getApplication()
        // The hidden upstream preferences used to silently disable protection in this app.
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean("enable_background_protection", false)
            .putBoolean("enable_background_wakelock", false).commit()
        controller = Robolectric.buildService(ForegroundService::class.java).create()
        service = controller.get()
        ForegroundService.startSession(context)
        controller.startCommand(0, 1)
    }

    @After fun stopSession() {
        ForegroundService.stopSession(context)
        controller.destroy()
    }

    private fun wakeLock(): PowerManager.WakeLock = ForegroundService::class.java.getDeclaredField("wakeLock")
        .apply { isAccessible = true }.get(service) as PowerManager.WakeLock

    @Test fun homeKeepsTheActiveSessionAwakeWithoutHiddenOptIns() {
        assertTrue(ForegroundService.isSessionActive())
        ForegroundService.onPauseSession(context)
        assertTrue("The CPU must keep the local realm running while the screen is away", wakeLock().isHeld)
    }

    @Test fun removingLauncherTaskDoesNotKillAnActiveRuntime() {
        ForegroundService.onPauseSession(context)
        service.onTaskRemoved(Intent())
        assertTrue("The runtime notification must continue to own the active session", ForegroundService.isSessionActive())
        assertTrue(wakeLock().isHeld)
    }

    @Test fun explicitStopReleasesBackgroundResources() {
        ForegroundService.onPauseSession(context)
        assertTrue(wakeLock().isHeld)
        ForegroundService.stopSession(context)
        assertFalse(ForegroundService.isSessionActive())
        assertFalse(wakeLock().isHeld)
    }
}
