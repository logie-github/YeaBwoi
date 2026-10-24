// SPDX-License-Identifier: GPL-3.0-or-later
package com.logie.warcraft.felsong

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.logie.warcraft.WowFiles
import com.logie.warcraft.felsong.ui.FelsongTheme
import com.logie.warcraft.felsong.ui.LegionHomeRoute
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.File

/**
 * Legion's launcher as the user sees it, on a phone-sized screen: the numbered steps, the one
 * thing to do next for where the setup stands, the tiles and their screens, and that the buttons
 * start Legion's own installer (and only it). Previews are written to build/reports/legion-previews.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class LegionHomeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun arm64PhoneAtTheStandardPath() {
        ReflectionHelpers.setStaticField(Build::class.java, "SUPPORTED_ABIS", arrayOf("arm64-v8a"))
        ReflectionHelpers.setField(WowFiles, "standardPath", true)
    }

    /** A job the test started stays "working" in the app's state: back to idle. */
    private fun idle() {
        @Suppress("UNCHECKED_CAST")
        val state = ReflectionHelpers.getField<kotlinx.coroutines.flow.MutableStateFlow<FelsongJobs.State>>(FelsongJobs, "_state")
        state.value = FelsongJobs.State.Idle
    }

    @After fun clean() {
        FelsongFiles.layout(compose.activity).root.deleteRecursively()
        idle()
        ReflectionHelpers.setField(WowFiles, "standardPath", null)
    }

    /** The launcher, on a phone where the realm [installed] or nothing is. */
    private fun home(installed: Boolean = false) {
        FelsongFiles.layout(compose.activity).root.deleteRecursively()
        idle()
        if (installed) realmInstalled()
        show()
    }

    private fun show() {
        compose.setContent { FelsongTheme { LegionHomeRoute {} } }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(2000)
        compose.waitForIdle()
    }

    private fun screenshot(name: String) {
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val output = File("build/reports/legion-previews/legion-$name.jpg")
            output.parentFile.mkdirs()
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            bitmap.recycle()
        }
    }

    /** What the setup has done by the time of the client: the realm's programs and databases. */
    private fun realmInstalled() {
        val layout = FelsongFiles.layout(compose.activity)
        File(layout.server, "bin").mkdirs()
        File(layout.server, "ctl.sh").writeText("#!/bin/sh\n")
        File(layout.server, ".installed").writeText("a".repeat(64))
        File(layout.databases, "mysql").mkdirs()
    }

    @Test fun aFreshPhoneShowsTheStepsAndSaysTheBuildHasNoRealmPackage() {
        home()
        compose.onNodeWithText("Legion — 7.3.5", ignoreCase = true).assertExists()
        for (step in listOf("The realm (LegionCore)", "The realm's databases", "The Legion client (7.3.5, build 26972)", "The server's data from the client", "The client set up for the realm", "Your account")) {
            compose.onNodeWithText(step).assertExists()
        }
        // A build made without CI's pin of the realm package has nothing to download (as Turtle's).
        compose.onNodeWithText("This build of the app has no Legion realm package", substring = true).assertExists()
        screenshot("fresh")
    }

    @Test fun withTheRealmInTheNextStepIsTheClientAndItsDownloadStartsLegionsInstaller() {
        home(installed = true)
        val download = compose.onNodeWithText("DOWNLOAD THE LEGION CLIENT")
        download.performScrollTo().assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithText("ADD MY OWN CLIENT").assertExists()
        screenshot("client-next")
        download.performClick()
        val request = shadowOf(compose.activity.application as Application).nextStartedService
            ?: error("The download didn't start Legion's foreground service")
        assertEquals(FelsongService::class.java.name, request.component?.className)
        assertEquals(FelsongJobs.Kind.DOWNLOAD_CLIENT.name, request.getStringExtra("kind"))
    }

    @Test @Config(qualifiers = "w891dp-h411dp-xxhdpi") fun landscapeKeepsTheHeroAndScrollsToTheAction() {
        home(installed = true)
        compose.onNodeWithText("DOWNLOAD THE LEGION CLIENT").performScrollTo().assertIsDisplayed()
        screenshot("landscape")
    }

    @Test fun theTilesOpenTheirScreens() {
        home(installed = true)
        compose.onNodeWithText("GAME FILES").performScrollTo().performClick()
        compose.waitForIdle()
        for (row in listOf("Client", "Server data", "Set up for the realm", "Space")) compose.onNodeWithText(row).assertExists()
        screenshot("files")
    }
}
