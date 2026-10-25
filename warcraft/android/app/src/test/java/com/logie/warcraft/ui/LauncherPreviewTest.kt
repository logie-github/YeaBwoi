// SPDX-License-Identifier: GPL-3.0-or-later
package com.logie.warcraft.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.logie.warcraft.felsong.ui.FelsongHomeRoute
import com.logie.warcraft.felsong.ui.FelsongTheme
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Pictures of the launchers (build/reports/launcher-previews), to compare their looks. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class LauncherPreviewTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun screenshot(name: String) {
        compose.mainClock.advanceTimeBy(3000)
        compose.waitForIdle()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val output = File("build/reports/launcher-previews/$name.jpg")
            output.parentFile.mkdirs()
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            bitmap.recycle()
        }
    }

    @Test fun wrath() {
        compose.setContent { WowHomeRoute(compose.activity, checkUpdateOnOpen = false) }
        screenshot("wrath")
    }

    @Test fun legion() {
        compose.setContent { FelsongTheme { FelsongHomeRoute {} } }
        screenshot("legion")
    }
}
