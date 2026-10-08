// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.dolphinxd

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.dolphinemu.dolphinemu.BuildConfig
import org.dolphinemu.dolphinemu.NativeLibrary
import org.dolphinemu.dolphinemu.R
import org.dolphinemu.dolphinemu.activities.EmulationActivity
import org.dolphinemu.dolphinemu.databinding.XdActivityHomeBinding
import org.dolphinemu.dolphinemu.databinding.XdHomeTileBinding
import org.dolphinemu.dolphinemu.features.settings.ui.MenuTag
import org.dolphinemu.dolphinemu.features.settings.ui.SettingsActivity

/**
 * Dolphin XD's main menu. Opening the app starts the game straight away with this screen
 * underneath it, so exiting the game (in-game menu → Exit) lands here.
 */
class XdHomeActivity : AppCompatActivity() {
    private lateinit var config: XdConfig
    private lateinit var binding: XdActivityHomeBinding
    private val launchHandler = Handler(Looper.getMainLooper())
    private var launching = false

    private data class Tile(
        @DrawableRes val icon: Int,
        @StringRes val title: Int,
        @StringRes val subtitle: Int,
        val action: () -> Unit,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        XdGame.enableEdgeToEdge(this)
        super.onCreate(savedInstanceState)
        config = XdConfig(this)

        binding = XdActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets()

        binding.xdHomePlay.setOnClickListener { play() }
        binding.xdHomeVersion.text =
            getString(R.string.xd_home_version, XdGame.appName(this), BuildConfig.VERSION_NAME)
        if (resources.getBoolean(R.bool.xd_header_stacked)) {
            // Wide logos (like Pokémon Colosseum's) go above the title instead of beside it.
            binding.xdHomeTitleBlock.orientation = LinearLayout.VERTICAL
            binding.xdHomeTitleBlock.gravity = Gravity.START
            (binding.xdHomeTitleText.layoutParams as ViewGroup.MarginLayoutParams).apply {
                marginStart = 0
                topMargin = (6 * resources.displayMetrics.density).toInt()
            }
        }
        addTiles()

        XdTasks.observe(this, binding.xdHomeTask, onChanged = { updatePlayButton() })

        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_AUTOPLAY, false)) {
            binding.xdHomeLaunching.visibility = View.VISIBLE
            play()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_AUTOPLAY, false)) {
            binding.xdHomeLaunching.visibility = View.VISIBLE
            play()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onRestart() {
        super.onRestart()
        // Back from the game (or another screen): show the menu.
        launching = false
        binding.xdHomeLaunching.visibility = View.GONE
    }

    override fun onDestroy() {
        launchHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val padding = (20 * resources.displayMetrics.density).toInt()
            binding.xdHomeTitleBlock.updatePadding(
                left = bars.left + padding,
                top = bars.top + padding,
                right = bars.right + padding
            )
            binding.xdHomeContent.updatePadding(
                left = bars.left + padding * 4 / 5,
                right = bars.right + padding * 4 / 5,
                bottom = bars.bottom + padding
            )
            insets
        }
    }

    private fun addTiles() {
        val tiles = listOf(
            Tile(R.drawable.xd_ic_texture, R.string.xd_home_textures, R.string.xd_home_textures_sub) {
                startActivity(Intent(this, XdTexturePacksActivity::class.java))
            },
            Tile(R.drawable.xd_ic_mod, R.string.xd_home_mods, R.string.xd_home_mods_sub) {
                startActivity(Intent(this, XdModsActivity::class.java))
            },
            Tile(R.drawable.xd_ic_settings, R.string.xd_home_settings, R.string.xd_home_settings_sub) {
                SettingsActivity.launch(this, MenuTag.SETTINGS)
            },
            Tile(R.drawable.xd_ic_controls, R.string.xd_home_controls, R.string.xd_home_controls_sub) {
                XdUi.showControls(this)
            },
            Tile(R.drawable.xd_ic_swap, R.string.xd_home_change_game, R.string.xd_home_change_game_sub) {
                if (XdTasks.isRunning) {
                    Toast.makeText(this, R.string.xd_busy, Toast.LENGTH_SHORT).show()
                } else {
                    startActivity(Intent(this, XdSetupActivity::class.java))
                }
            },
            Tile(R.drawable.xd_ic_exit, R.string.xd_home_exit, R.string.xd_home_exit_sub) {
                finishAffinity()
            },
        )

        val margin = (6 * resources.displayMetrics.density).toInt()
        for (tile in tiles) {
            val view = XdHomeTileBinding.inflate(layoutInflater, binding.xdHomeTiles, false)
            view.xdTileIcon.setImageResource(tile.icon)
            view.xdTileTitle.setText(tile.title)
            view.xdTileSubtitle.setText(tile.subtitle)
            view.root.setOnClickListener { tile.action() }
            view.root.layoutParams = GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED),
                GridLayout.spec(GridLayout.UNDEFINED, 1f)
            ).apply {
                width = 0
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                setMargins(margin, margin, margin, margin)
            }
            binding.xdHomeTiles.addView(view.root)
        }
    }

    private fun refresh() {
        val mod = config.activeMod
        val name = mod?.name ?: config.romTitle ?: getString(R.string.xd_mods_original)
        val gameId = config.activeGameId ?: ""
        binding.xdHomeNowPlaying.text = getString(R.string.xd_home_now_playing, name, gameId)
        updatePlayButton()
    }

    private fun updatePlayButton() {
        binding.xdHomePlay.isEnabled = !XdTasks.isRunning && !launching
    }

    private fun play() {
        if (XdTasks.isRunning) {
            binding.xdHomeLaunching.visibility = View.GONE
            Toast.makeText(this, R.string.xd_busy, Toast.LENGTH_SHORT).show()
            return
        }
        val path = config.activeGamePath
        if (!config.setupComplete || path == null) {
            startActivity(Intent(this, XdSetupActivity::class.java))
            finish()
            return
        }

        launching = true
        updatePlayButton()
        lifecycleScope.launch {
            val readable = withContext(Dispatchers.IO) { XdGameFiles.canRead(this@XdHomeActivity, path) }
            if (readable) {
                launchWhenIdle(path, 0)
            } else {
                launching = false
                updatePlayButton()
                binding.xdHomeLaunching.visibility = View.GONE
                val name = config.activeMod?.name ?: config.romTitle ?: path
                MaterialAlertDialogBuilder(this@XdHomeActivity)
                    .setTitle(R.string.xd_game_missing_title)
                    .setMessage(getString(R.string.xd_game_missing, name))
                    .setPositiveButton(R.string.xd_home_change_game) { _, _ ->
                        startActivity(Intent(this@XdHomeActivity, XdSetupActivity::class.java))
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
    }

    /** Waits for a previous emulation session to finish shutting down, then starts the game. */
    private fun launchWhenIdle(path: String, attempt: Int) {
        if (isFinishing || isDestroyed) return
        val idle = NativeLibrary.IsUninitialized() && !EmulationActivity.isIgnoringLaunchRequests()
        if (!idle) {
            if (attempt < MAX_LAUNCH_ATTEMPTS) {
                launchHandler.postDelayed({ launchWhenIdle(path, attempt + 1) }, LAUNCH_RETRY_MS)
            } else {
                launching = false
                updatePlayButton()
                binding.xdHomeLaunching.visibility = View.GONE
                Toast.makeText(this, R.string.xd_launch_failed, Toast.LENGTH_LONG).show()
            }
            return
        }
        EmulationActivity.launch(this, path, false)

        // If Dolphin stopped to ask something instead of starting the game, don't stay stuck.
        launchHandler.postDelayed({
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                launching = false
                updatePlayButton()
                binding.xdHomeLaunching.visibility = View.GONE
            }
        }, LAUNCH_TIMEOUT_MS)
    }

    companion object {
        const val EXTRA_AUTOPLAY = "com.logie.dolphinxd.AUTOPLAY"
        private const val MAX_LAUNCH_ATTEMPTS = 100
        private const val LAUNCH_RETRY_MS = 100L
        private const val LAUNCH_TIMEOUT_MS = 4000L
    }
}
