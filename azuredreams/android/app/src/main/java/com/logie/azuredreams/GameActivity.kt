// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.azuredreams

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroViewData
import com.swordfish.libretrodroid.ShaderConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The game: the emulator's picture, the gesture layer over it and the CRT switch-on. The in-game
 * menu ([MenuActivity]) opens over it and pauses it. Leaving or pausing the game saves where it
 * was (the resume state), which Play picks up next time.
 */
class GameActivity : ComponentActivity() {
    private lateinit var retroView: GLRetroView
    private lateinit var gestureView: GestureView
    private lateinit var moteView: MoteView
    private lateinit var crtView: CrtView
    private lateinit var config: AdConfig

    /** The game is running (its first frame was drawn); before that, its state can't be saved. */
    private var started = false
    /** What the in-game menu asked for, done once the game runs again. */
    private var pendingAction: MenuActivity.Action? = null
    private var errorShown = false
    /** The picture settings the emulator has, to only change them when the player did. */
    private var appliedShader: ShaderConfig? = null
    private var appliedSharper3d: Boolean? = null

    private val menu = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val action = MenuActivity.actionOf(result.resultCode, result.data)
        if (action is MenuActivity.Action.Quit) {
            finish()
        } else {
            pendingAction = action
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val disc = AdFiles.launchFile(this)
        if (disc == null) {
            finish()
            return
        }
        config = AdConfig(this)
        goFullscreen()

        val data = GLRetroViewData(this).apply {
            coreFilePath = AdCore.LIBRARY
            gameFilePath = disc.absolutePath
            systemDirectory = AdFiles.systemDir(this@GameActivity).absolutePath
            savesDirectory = AdFiles.savesDir(this@GameActivity).absolutePath
            variables = AdCore.variables(config)
            shader = AdCore.shader(config)
            rumbleEventsEnabled = false
            preferLowLatencyAudio = true
        }
        retroView = GLRetroView(this, data)
        lifecycle.addObserver(retroView)
        gestureView = GestureView(this, retroView, onMenu = ::openMenu)
        moteView = MoteView(this).apply { visibility = View.GONE }
        crtView = CrtView(this)

        val match = ViewGroup.LayoutParams.MATCH_PARENT
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(retroView, FrameLayout.LayoutParams(match, match))
            addView(moteView, FrameLayout.LayoutParams(match, match))
            addView(gestureView, FrameLayout.LayoutParams(match, match))
            addView(crtView, FrameLayout.LayoutParams(match, match))
        })
        // Game controllers go straight to the emulator.
        retroView.isFocusable = true
        retroView.requestFocus()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = openMenu()
        })

        val resume = intent.getBooleanExtra(EXTRA_RESUME, false)
        lifecycleScope.launch {
            retroView.getGLRetroEvents().first { it is GLRetroView.GLRetroEvents.FrameRendered }
            if (resume) loadState(AdFiles.RESUME_SLOT, quiet = true)
            started = true
            applySettings()
            moteView.setRunning(true)
            crtView.switchOn()
        }
        lifecycleScope.launch {
            retroView.getGLRetroErrors().collect { showError(it) }
        }
    }

    override fun onResume() {
        super.onResume()
        goFullscreen()
        if (started) {
            applySettings()
            pendingAction?.let { run(it) }
            moteView.setRunning(true)
        }
        pendingAction = null
    }

    override fun onPause() {
        // The emulator has already paused itself (LibretroDroid follows the lifecycle), so its
        // state can be read here, on this thread.
        if (::gestureView.isInitialized) gestureView.releaseAll()
        if (::moteView.isInitialized) moteView.setRunning(false)
        if (started) {
            try {
                val state = retroView.serializeState(false)
                if (state.isNotEmpty()) {
                    val context = applicationContext
                    io.launch { runCatching { AdFiles.writeState(context, AdFiles.RESUME_SLOT, state) } }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't save the resume state", e)
            }
        }
        super.onPause()
    }

    private fun openMenu() {
        if (isFinishing) return
        gestureView.releaseAll()
        menu.launch(Intent(this, MenuActivity::class.java))
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private fun applySettings() {
        retroView.frameSpeed = config.speed
        val shader = AdCore.shader(config)
        if (shader != appliedShader) {
            appliedShader = shader
            retroView.shader = shader
        }
        moteView.visibility = if (config.picture == AdConfig.PICTURE_HD2D && config.hd2dMotes) View.VISIBLE else View.GONE
        val sharper3d = config.sharper3d
        if (appliedSharper3d != null && sharper3d != appliedSharper3d) {
            retroView.updateVariables(AdCore.sharper3d(sharper3d))
        }
        appliedSharper3d = sharper3d
    }

    private fun run(action: MenuActivity.Action) {
        when (action) {
            is MenuActivity.Action.Save -> lifecycleScope.launch {
                val saved = withContext(Dispatchers.IO) {
                    runCatching {
                        AdFiles.writeState(this@GameActivity, action.slot, retroView.serializeState())
                    }.isSuccess
                }
                toast(if (saved) getString(R.string.ad_state_saved, action.slot) else getString(R.string.ad_state_save_failed))
            }

            is MenuActivity.Action.Load -> lifecycleScope.launch { loadState(action.slot, quiet = false) }

            // Like saving and loading, this waits for the emulator's thread, which only starts
            // again after onResume: never on this thread.
            is MenuActivity.Action.Reset -> lifecycleScope.launch(Dispatchers.IO) { retroView.reset() }

            is MenuActivity.Action.Quit -> finish()
        }
    }

    private suspend fun loadState(slot: Int, quiet: Boolean) {
        val loaded = withContext(Dispatchers.IO) {
            val file = AdFiles.stateFile(this@GameActivity, slot)
            if (!file.isFile) return@withContext false
            runCatching { retroView.unserializeState(file.readBytes()) }.getOrDefault(false)
        }
        if (!quiet || !loaded && AdFiles.stateTime(this, slot) != null) {
            toast(
                when {
                    loaded -> getString(R.string.ad_state_loaded, slot)
                    slot == AdFiles.RESUME_SLOT -> getString(R.string.ad_resume_failed)
                    else -> getString(R.string.ad_state_load_failed)
                }
            )
        }
    }

    private fun showError(error: Int) {
        Log.e(TAG, "Emulator error $error")
        if (error == GLRetroView.ERROR_SERIALIZATION) {
            toast(getString(R.string.ad_state_load_failed))
            return
        }
        if (errorShown || isFinishing) return
        errorShown = true
        val message = when (error) {
            GLRetroView.ERROR_LOAD_LIBRARY -> R.string.ad_error_core
            GLRetroView.ERROR_LOAD_GAME -> R.string.ad_error_game
            GLRetroView.ERROR_GL_NOT_COMPATIBLE -> R.string.ad_error_gl
            else -> R.string.ad_error_generic
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ad_error_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun goFullscreen() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    companion object {
        private const val TAG = "AzureDreams"
        private const val EXTRA_RESUME = "com.logie.azuredreams.RESUME"

        /** Background work that has to finish even when the game closes (saving states). */
        private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Starts the game, from where it was left if [resume] and there's a resume state. */
        fun intent(context: Context, resume: Boolean): Intent =
            Intent(context, GameActivity::class.java).putExtra(EXTRA_RESUME, resume)
    }
}
