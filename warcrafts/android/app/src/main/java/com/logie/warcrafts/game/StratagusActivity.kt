// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.warcrafts.game

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.logie.warcrafts.library.Title
import org.libsdl.app.SDLActivity

/**
 * Warcraft I or II, native: the Stratagus engine (libmain.so, scripts/build-stratagus.sh) on SDL's
 * screen, playing War1gus or Wargus from the data the app made from the user's GOG files
 * ([EXTRA_DATA]), with the app's gestures over it ([StratagusGestureView]) in place of SDL's touch.
 * In a process of its own (the manifest), ended when the game is left: each game starts clean.
 */
class StratagusActivity : SDLActivity() {
    private val title: Title get() = Title.of(intent.getStringExtra(GestureView.EXTRA_TITLE)) ?: Title.WC1

    /** SDL is linked into libmain.so (scripts/build-stratagus.sh). */
    override fun getLibraries(): Array<String> = arrayOf("main")

    /**
     * Stratagus plays the game whose data is in the folder after -d, full screen (-F: over the
     * game's own preference, windowed in Wargus, which on Android leaves the phone's bars showing).
     */
    override fun getArguments(): Array<String> = arrayOf("-F", "-d", intent.getStringExtra(EXTRA_DATA) ?: "")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (SDLActivity.mBrokenLibraries) return
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        addContentView(
            StratagusGestureView(this, title, onLeave = { leave() }),
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
    }

    /** Back: the game's Escape (cancel an order, close a window). */
    @Deprecated("SDL's activity handles Back this way too")
    override fun onBackPressed() {
        StratagusGestureView.escape()
    }

    private fun leave() {
        finish()
        // The engine keeps its state in the process: the next game starts in a new one.
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    companion object {
        const val EXTRA_DATA = "com.logie.warcrafts.STRATAGUS_DATA"

        fun intent(context: Context, title: Title, dataDir: String): Intent =
            Intent(context, StratagusActivity::class.java)
                .putExtra(EXTRA_DATA, dataDir)
                .putExtra(GestureView.EXTRA_TITLE, title.key)
    }
}
