// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.dolphinxd

import android.app.ActivityManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import org.dolphinemu.dolphinemu.NativeLibrary
import org.dolphinemu.dolphinemu.utils.AfterDirectoryInitializationRunner
import org.dolphinemu.dolphinemu.utils.DirectoryInitialization

/**
 * The app's entry point: shows the splash screen while Dolphin starts up, then opens the
 * first-run setup or, once that's done, the main menu with the game starting right away.
 */
class XdLauncherActivity : AppCompatActivity() {
    private var keepSplash = true

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        splashScreen.setKeepOnScreenCondition { keepSplash }

        if (DirectoryInitialization.shouldStart(this)) {
            DirectoryInitialization.start(this)
        }
        AfterDirectoryInitializationRunner().runWithLifecycle(this) { route() }
    }

    private fun route() {
        XdDefaults.applyIfNeeded(this)

        if (!bringRunningGameToFront()) {
            val config = XdConfig(this)
            val next = if (config.setupComplete && config.romUri != null) {
                Intent(this, XdHomeActivity::class.java).putExtra(XdHomeActivity.EXTRA_AUTOPLAY, true)
            } else {
                Intent(this, XdSetupActivity::class.java)
            }
            startActivity(next)
        }
        keepSplash = false
        finish()
    }

    /** A game is already running (the launcher was started a second time): show it instead. */
    private fun bringRunningGameToFront(): Boolean {
        val emulationActivity = NativeLibrary.getEmulationActivity()
        if (emulationActivity == null || emulationActivity.isFinishing) {
            return false
        }
        if (emulationActivity.taskId != taskId) {
            getSystemService(ActivityManager::class.java)?.appTasks
                ?.firstOrNull { taskIdOf(it) == emulationActivity.taskId }
                ?.moveToFront()
        }
        return true
    }

    private fun taskIdOf(task: ActivityManager.AppTask): Int? {
        val info = task.taskInfo ?: return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            info.taskId
        } else {
            @Suppress("DEPRECATION")
            info.id
        }
    }
}
