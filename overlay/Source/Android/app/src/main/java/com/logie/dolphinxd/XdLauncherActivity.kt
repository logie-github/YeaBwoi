// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.dolphinxd

import android.app.ActivityManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.dolphinemu.dolphinemu.NativeLibrary
import org.dolphinemu.dolphinemu.R
import org.dolphinemu.dolphinemu.activities.EmulationActivity
import org.dolphinemu.dolphinemu.databinding.XdActivitySetupBinding
import org.dolphinemu.dolphinemu.utils.AfterDirectoryInitializationRunner
import org.dolphinemu.dolphinemu.utils.DirectoryInitialization

/**
 * The app's entry point. The first time, it asks for the setup files and the Pokémon XD game
 * file. After that it boots straight into the game and closes again when the game is exited,
 * so the app behaves like a native game instead of an emulator front end.
 *
 * This activity stays at the bottom of the task underneath [EmulationActivity] so the launcher
 * icon and the recents screen always return to the running game.
 */
class XdLauncherActivity : AppCompatActivity() {
    private lateinit var config: XdConfig
    private var binding: XdActivitySetupBinding? = null

    private var keepSplash = true
    private var emulationLaunched = false
    private var romMissing = false
    private var busy = false

    private val launchHandler = Handler(Looper.getMainLooper())

    private val pickBios = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            onBiosPicked(uri)
        }
    }

    private val pickRom = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            onRomPicked(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        splashScreen.setKeepOnScreenCondition { keepSplash }

        config = XdConfig(this)
        if (savedInstanceState != null) {
            emulationLaunched = savedInstanceState.getBoolean(KEY_EMULATION_LAUNCHED)
            romMissing = savedInstanceState.getBoolean(KEY_ROM_MISSING)
        }

        if (DirectoryInitialization.shouldStart(this)) {
            DirectoryInitialization.start(this)
        }
        AfterDirectoryInitializationRunner().runWithLifecycle(this) { onDirectoriesReady() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_EMULATION_LAUNCHED, emulationLaunched)
        outState.putBoolean(KEY_ROM_MISSING, romMissing)
    }

    override fun onRestart() {
        super.onRestart()
        // We become visible again once the EmulationActivity on top of us has finished.
        if (emulationLaunched && DirectoryInitialization.areDolphinDirectoriesReady()) {
            onReturnedFromEmulation()
        }
    }

    override fun onDestroy() {
        launchHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun onDirectoriesReady() {
        XdDefaults.applyIfNeeded(this)

        if (emulationLaunched) {
            onReturnedFromEmulation()
            return
        }

        if (bringRunningGameToFront()) {
            finish()
            return
        }

        val rom = config.romUri
        if (config.setupComplete && !config.reconfigureRequested && rom != null) {
            lifecycleScope.launch {
                val readable = withContext(Dispatchers.IO) {
                    XdGameFiles.canRead(this@XdLauncherActivity, rom)
                }
                if (readable) {
                    startGame()
                } else {
                    romMissing = true
                    showSetup()
                }
            }
        } else {
            showSetup()
        }
    }

    private fun onReturnedFromEmulation() {
        emulationLaunched = false
        if (config.reconfigureRequested) {
            showSetup()
        } else {
            finish()
        }
    }

    /**
     * Handles a second launcher instance being started while a game is already running. Returns
     * true if this instance should simply go away so the running game is visible again.
     */
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

    private fun startGame() {
        val rom = config.romUri
        if (rom == null) {
            showSetup()
            return
        }
        emulationLaunched = true
        launchWhenIdle(rom, 0)
    }

    /** Waits for a previous emulation session (e.g. after "Change game file") to shut down. */
    private fun launchWhenIdle(rom: String, attempt: Int) {
        if (isFinishing || isDestroyed) {
            return
        }
        val idle = NativeLibrary.IsUninitialized() && !EmulationActivity.isIgnoringLaunchRequests()
        if (!idle) {
            if (attempt < MAX_LAUNCH_ATTEMPTS) {
                launchHandler.postDelayed({ launchWhenIdle(rom, attempt + 1) }, LAUNCH_RETRY_MS)
            } else {
                emulationLaunched = false
                showSetup()
                Toast.makeText(this, R.string.xd_launch_failed, Toast.LENGTH_LONG).show()
            }
            return
        }
        EmulationActivity.launch(this, rom, false)
        keepSplash = false
    }

    private fun showSetup() {
        keepSplash = false
        val existing = binding
        if (existing == null) {
            val newBinding = XdActivitySetupBinding.inflate(layoutInflater)
            binding = newBinding
            setContentView(newBinding.root)

            ViewCompat.setOnApplyWindowInsetsListener(newBinding.xdSetupContent) { view, insets ->
                val bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
                )
                val padding = resources.getDimensionPixelSize(R.dimen.spacing_large)
                view.updatePadding(
                    left = bars.left + padding,
                    top = bars.top + padding,
                    right = bars.right + padding,
                    bottom = bars.bottom + padding
                )
                insets
            }

            newBinding.xdBiosChoose.setOnClickListener { pickBios.launch(arrayOf("*/*")) }
            newBinding.xdBiosSkip.setOnClickListener {
                XdGameFiles.discardStagedBios(this)
                config.stagedBiosRegion = null
                config.setupFilesDone = true
                render()
            }
            newBinding.xdRomChoose.setOnClickListener { pickRom.launch(arrayOf("*/*")) }
            newBinding.xdPlay.setOnClickListener { onPlay() }
        }
        render()
    }

    private fun render() {
        val b = binding ?: return
        val setupFilesDone = config.setupFilesDone
        val stagedRegion = config.stagedBiosRegion

        b.xdBiosStatus.apply {
            text = when {
                stagedRegion != null -> getString(R.string.xd_setup_files_added, stagedRegion)
                config.installedBiosRegionDir != null -> getString(R.string.xd_setup_files_installed)
                setupFilesDone -> getString(R.string.xd_setup_files_skipped)
                else -> ""
            }
            visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        b.xdBiosSkip.visibility = if (setupFilesDone) View.GONE else View.VISIBLE
        b.xdBiosChoose.isEnabled = !busy

        val title = config.romTitle
        val gameId = config.romGameId
        b.xdRomStatus.apply {
            text = when {
                busy -> getString(R.string.xd_checking)
                romMissing -> getString(R.string.xd_rom_missing)
                config.romUri != null && title != null && gameId != null ->
                    getString(R.string.xd_rom_selected, title, gameId)
                else -> ""
            }
            setTextColor(
                getColor(if (romMissing && !busy) R.color.xd_red else R.color.xd_on_surface)
            )
            visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        b.xdRomCard.alpha = if (setupFilesDone) 1f else 0.5f
        b.xdRomChoose.isEnabled = setupFilesDone && !busy
        b.xdProgress.visibility = if (busy) View.VISIBLE else View.GONE

        b.xdPlay.isEnabled = setupFilesDone && config.romUri != null && !romMissing && !busy
    }

    private fun onBiosPicked(uri: Uri) {
        busy = true
        render()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                XdGameFiles.stageBios(this@XdLauncherActivity, uri)
            }
            busy = false
            when (result) {
                is XdGameFiles.BiosCheck.Ok -> {
                    config.stagedBiosRegion = result.region
                    config.setupFilesDone = true
                }
                XdGameFiles.BiosCheck.NotABios -> showError(getString(R.string.xd_error_not_bios))
                XdGameFiles.BiosCheck.Unreadable -> showError(getString(R.string.xd_error_unreadable_bios))
            }
            render()
        }
    }

    private fun onRomPicked(uri: Uri) {
        val path = uri.toString()
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Not every document provider supports persistable permissions; we'll find out at
            // the next launch whether the file can still be opened.
        }

        busy = true
        render()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { XdGameFiles.checkRom(path) }
            busy = false
            when (result) {
                is XdGameFiles.RomCheck.PokemonXd -> {
                    val previous = config.romUri
                    if (previous != null && previous != path) {
                        releasePermission(previous)
                    }
                    config.setRom(path, result.title, result.gameId)
                    romMissing = false
                }
                is XdGameFiles.RomCheck.OtherGame -> {
                    releasePermission(path)
                    showError(getString(R.string.xd_error_wrong_game, result.title, result.gameId))
                }
                XdGameFiles.RomCheck.Unreadable -> {
                    releasePermission(path)
                    showError(getString(R.string.xd_error_unreadable_rom))
                }
            }
            render()
        }
    }

    private fun onPlay() {
        val gameId = config.romGameId ?: return
        busy = true
        render()
        lifecycleScope.launch {
            val install = withContext(Dispatchers.IO) {
                XdGameFiles.installStagedBios(this@XdLauncherActivity, config.stagedBiosRegion, gameId)
            }
            config.stagedBiosRegion = null
            when (install) {
                XdGameFiles.BiosInstall.REGION_MISMATCH ->
                    Toast.makeText(this@XdLauncherActivity, R.string.xd_bios_region_mismatch, Toast.LENGTH_LONG).show()
                XdGameFiles.BiosInstall.FAILED ->
                    Toast.makeText(this@XdLauncherActivity, R.string.xd_bios_install_failed, Toast.LENGTH_LONG).show()
                else -> {}
            }

            config.setupComplete = true
            config.reconfigureRequested = false
            busy = false
            render()
            startGame()
        }
    }

    private fun releasePermission(path: String) {
        if (!path.startsWith("content://")) {
            return
        }
        try {
            contentResolver.releasePersistableUriPermission(
                Uri.parse(path),
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
        }
    }

    private fun showError(message: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.xd_error_title)
            .setMessage(message)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    companion object {
        private const val KEY_EMULATION_LAUNCHED = "emulation_launched"
        private const val KEY_ROM_MISSING = "rom_missing"
        private const val MAX_LAUNCH_ATTEMPTS = 100
        private const val LAUNCH_RETRY_MS = 100L
    }
}
