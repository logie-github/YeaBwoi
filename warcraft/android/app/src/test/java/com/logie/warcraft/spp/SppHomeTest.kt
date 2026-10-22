// SPDX-License-Identifier: GPL-3.0-or-later
package com.logie.warcraft.spp

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.logie.warcraft.felsong.ui.FelsongTheme
import com.logie.warcraft.local.LocalInstallJobs
import com.logie.warcraft.spp.ui.SppHomeRoute
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class SppHomeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun home() {
        SppJobs.clearResult()
        SppFiles.layout(compose.activity).root.deleteRecursively()
        compose.setContent { FelsongTheme { SppHomeRoute {} } }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(2000)
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 10000) {
            compose.onAllNodesWithText("Checking installed files…").fetchSemanticsNodes().isEmpty()
        }
    }
    private fun screenshot(name: String) {
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val output = File("build/reports/spp-previews/spp-$name.jpg")
            output.parentFile.mkdirs()
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            bitmap.recycle()
        }
    }
    @Test fun portraitAndOneTapInstallUsesTheRealForegroundInstaller() {
        home()
        compose.onNodeWithText("Legion — 7.3.5", ignoreCase = true).assertExists()
        val install = compose.onNodeWithText("DOWNLOAD & INSTALL")
        install.assertHasClickAction()
        screenshot("portrait")
        try {
            install.performClick()
            val request = shadowOf(compose.activity.application as Application).nextStartedService ?: error("The one-tap installer did not start its foreground service")
            assertEquals(SppService::class.java.name, request.component?.className)
            assertEquals(LocalInstallJobs.Kind.INSTALL.name, request.getStringExtra("kind"))
        } finally {
            SppJobs.failed(LocalInstallJobs.Kind.INSTALL, "Test request complete")
            SppJobs.clearResult()
        }
    }
    @Test @Config(qualifiers = "w891dp-h411dp-xxhdpi") fun landscapeKeepsThePrimaryActionInView() {
        home()
        compose.onNodeWithText("DOWNLOAD & INSTALL").assertIsDisplayed()
        screenshot("landscape")
    }
    @Test @Config(qualifiers = "w840dp-h900dp-xhdpi") fun unfoldedShowsTheNormalRealmTilesAndSeparateComponents() {
        home()
        compose.onNodeWithText("GAME FILES").assertExists()
        screenshot("unfolded")
        compose.onNodeWithText("GAME FILES").performScrollTo().performClick()
        for (name in listOf("Game Client", "Server Core", "Server Database", "Map Files")) compose.onNodeWithText(name).assertExists()
    }
}
