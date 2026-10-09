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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The game: the emulator's picture, the gesture layer over it and the CRT switch-on. The in-game
 * menu ([MenuActivity]) opens over it and pauses it. The game always boots fresh, to its own title
 * screen: nothing carries over from the last time but the memory card and the save state slots.
 *
 * SwanStation draws with OpenGL ES and reads its state back from the GPU, so its state is only
 * saved, loaded or reset on the emulation thread while that runs with its context: once the game
 * draws again after the in-game menu.
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
    private var appliedResolution: Int? = null
    private var appliedSteady3d: Boolean? = null
    /** The BIOS the game started on ([AdFiles.biosTag]), noted with each state saved. */
    private lateinit var biosTag: String
    /** Memory card 1 as it was last written to its file. */
    private var savedCard: ByteArray? = null
    /** The card file is copied to a backup before the first time the game changes it. */
    private var cardBackedUp = false
    /** Looks for changes to the memory card every few seconds while the game runs. */
    private var cardWatch: Job? = null

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
        biosTag = AdFiles.biosTag(this)
        goFullscreen()

        val data = GLRetroViewData(this).apply {
            coreFilePath = AdCore.LIBRARY
            gameFilePath = disc.absolutePath
            systemDirectory = AdFiles.systemDir(this@GameActivity).absolutePath
            savesDirectory = AdFiles.savesDir(this@GameActivity).absolutePath
            variables = AdCore.variables(this@GameActivity, config)
            // Memory card 1 is the app's to keep (the game's own saves).
            saveRAMState = AdFiles.readMemoryCard(this@GameActivity).also { savedCard = it }
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

        lifecycleScope.launch {
            retroView.getGLRetroEvents().first { it is GLRetroView.GLRetroEvents.FrameRendered }
            started = true
            applySettings()
            watchMemoryCard()
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
            pendingAction?.let { action ->
                lifecycleScope.launch {
                    afterNextFrame()
                    run(action)
                }
            }
            watchMemoryCard()
            moteView.setRunning(true)
        }
        pendingAction = null
    }

    override fun onPause() {
        if (::gestureView.isInitialized) gestureView.releaseAll()
        if (::moteView.isInitialized) moteView.setRunning(false)
        cardWatch?.cancel()
        cardWatch = null
        // The game has paused (LibretroDroid follows the lifecycle), so its memory card can be read
        // here. Leaving the app can be the last chance before it's closed.
        if (started) runCatching { retroView.serializeSRAM(false) }.getOrNull()?.let { keepMemoryCard(it) }
        super.onPause()
    }

    /** Writes the memory card to its file every few seconds while the game changes it. */
    private fun watchMemoryCard() {
        cardWatch?.cancel()
        cardWatch = lifecycleScope.launch {
            while (true) {
                delay(CARD_WATCH_MS)
                val card = withContext(Dispatchers.IO) { runCatching { retroView.serializeSRAM() }.getOrNull() }
                card?.let { keepMemoryCard(it) }
            }
        }
    }

    /** Writes [card] (memory card 1, from the emulator) to its file if the game changed it. */
    private fun keepMemoryCard(card: ByteArray) {
        if (card.size != AdFiles.MEMORY_CARD_SIZE || card.contentEquals(savedCard)) return
        savedCard = card
        val backup = !cardBackedUp
        cardBackedUp = true
        val context = applicationContext
        cardWriter.launch {
            runCatching { AdFiles.writeMemoryCard(context, card, backup) }
                .onFailure { Log.w(TAG, "Couldn't save the memory card", it) }
        }
    }

    /** Waits for the game to draw again after a pause: from then on its OpenGL context is current. */
    private suspend fun afterNextFrame() {
        // The latest frame before the pause is replayed first.
        retroView.getGLRetroEvents().filterIsInstance<GLRetroView.GLRetroEvents.FrameRendered>().drop(1).first()
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
        moteView.visibility = if (config.hd2d && config.hd2dMotes) View.VISIBLE else View.GONE
        val resolution = config.resolution
        if (appliedResolution != null && resolution != appliedResolution) {
            retroView.updateVariables(AdCore.resolution(resolution))
        }
        appliedResolution = resolution
        val steady3d = config.steady3d
        if (appliedSteady3d != null && steady3d != appliedSteady3d) {
            retroView.updateVariables(AdCore.steady3d(steady3d))
        }
        appliedSteady3d = steady3d
    }

    private fun run(action: MenuActivity.Action) {
        when (action) {
            is MenuActivity.Action.Save -> lifecycleScope.launch {
                val saved = withContext(Dispatchers.IO) {
                    runCatching {
                        AdFiles.writeState(this@GameActivity, action.slot, retroView.serializeState(), biosTag)
                    }.isSuccess
                }
                toast(if (saved) getString(R.string.ad_state_saved, action.slot) else getString(R.string.ad_state_save_failed))
            }

            is MenuActivity.Action.Load -> lifecycleScope.launch { loadState(action.slot) }

            // Like saving and loading, this waits for the emulator's thread: never on this one.
            is MenuActivity.Action.Reset -> lifecycleScope.launch(Dispatchers.IO) { retroView.reset() }

            is MenuActivity.Action.Quit -> finish()
        }
    }

    private suspend fun loadState(slot: Int) {
        val loaded = withContext(Dispatchers.IO) {
            val file = AdFiles.stateFile(this@GameActivity, slot)
            if (!file.isFile) return@withContext false
            runCatching { retroView.unserializeState(file.readBytes()) }.getOrDefault(false)
        }
        toast(
            when {
                // A state made on another BIOS brings that one's code back with it.
                loaded && AdFiles.stateFromOtherBios(this, slot) -> getString(R.string.ad_state_loaded_other_bios, slot)
                loaded -> getString(R.string.ad_state_loaded, slot)
                else -> getString(R.string.ad_state_load_failed)
            }
        )
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
        private const val CARD_WATCH_MS = 3000L

        /** Writes the memory card one write after another, even after the game has closed. */
        @OptIn(ExperimentalCoroutinesApi::class)
        private val cardWriter = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

        /** Boots the game. */
        fun intent(context: Context): Intent = Intent(context, GameActivity::class.java)
    }
}
