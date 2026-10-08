// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.dolphinxd

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.dolphinemu.dolphinemu.NativeLibrary
import org.dolphinemu.dolphinemu.features.cheats.model.ARCheat
import org.dolphinemu.dolphinemu.features.settings.model.NativeConfig
import org.dolphinemu.dolphinemu.features.settings.model.Settings
import org.dolphinemu.dolphinemu.model.GameFile

/**
 * 60 FPS instead of the games' 30, using the community 60 FPS Action Replay codes Dolphin ships in
 * its game settings ("$60 FPS [Nerdzilla]" for Pokémon Colosseum, "$60 FPS" for Pokémon XD, every
 * region). The game keeps its normal speed, but its CPU has to do twice the work per second, so
 * the code's author recommends a 200% emulated CPU overclock.
 *
 * Codes are loaded when a game starts, so [prepareCodes] and [applyRunConfig] run before every
 * start and a change takes effect the next time the game starts. The overclock and the cheats
 * switch go in Dolphin's CurrentRun config layer, which ends with the game, so the player's own
 * settings stay untouched.
 */
object XdFrameRate {
    private const val CODE_NAME_PREFIX = "60 FPS"
    private const val OVERCLOCK = 2.0f

    /**
     * Known issue: a few cutscenes softlock at 60 FPS. Slowing the game down for a moment drops it
     * to 30 FPS: right away for some (Rui and the Mayor), just before the stuck moment for others.
     */
    private const val UNSTICK_OVERCLOCK = 0.5f
    private const val UNSTICK_MS = 10_000L

    private const val KEY_ENABLE_CHEATS = "EnableCheats"
    private const val KEY_OVERCLOCK_ENABLE = "OverclockEnable"
    private const val KEY_OVERCLOCK = "Overclock"

    /** Whether the running game was started with 60 FPS. */
    @Volatile
    var activeThisRun = false
        private set

    /**
     * Turns the game's 60 FPS code on or off for the player's choice. Returns whether the game
     * will run at 60 FPS. Background thread, before starting the game at [path].
     */
    fun prepareCodes(context: Context, path: String): Boolean {
        val on = XdConfig(context).sixtyFps
        val game = GameFile.parse(path)
        var codeFound = false
        if (game != null) {
            val gameId = game.getGameId()
            val revision = game.getRevision()
            val codes = ARCheat.loadCodes(gameId, revision)
            var changed = false
            for (code in codes) {
                if (code.getUserDefined() || !code.getName().startsWith(CODE_NAME_PREFIX)) continue
                codeFound = true
                if (code.getEnabled() != on) {
                    code.setEnabled(on)
                    changed = true
                }
            }
            if (changed) {
                ARCheat.saveCodes(gameId, revision, codes)
            }
        }

        return on && codeFound
    }

    /**
     * Sets the cheats switch and the overclock for the game about to start. Main thread, right
     * before starting it: a game that is still shutting down clears the CurrentRun layer.
     */
    fun applyRunConfig(sixtyFps: Boolean) {
        val layer = NativeConfig.LAYER_CURRENT
        val file = Settings.FILE_DOLPHIN
        val section = Settings.SECTION_INI_CORE
        if (sixtyFps) {
            NativeConfig.setBoolean(layer, file, section, KEY_ENABLE_CHEATS, true)
            NativeConfig.setBoolean(layer, file, section, KEY_OVERCLOCK_ENABLE, true)
            NativeConfig.setFloat(layer, file, section, KEY_OVERCLOCK, OVERCLOCK)
        } else {
            for (key in listOf(KEY_ENABLE_CHEATS, KEY_OVERCLOCK_ENABLE, KEY_OVERCLOCK)) {
                NativeConfig.deleteKey(layer, file, section, key)
            }
        }
        activeThisRun = sixtyFps
    }

    /**
     * The code author's fix for a stuck cutscene: slow the game down for a moment, then go back
     * to full speed. Main thread, while a game started with 60 FPS runs.
     */
    fun unstickCutscene() {
        if (!activeThisRun || !NativeLibrary.IsRunning()) return
        setOverclock(UNSTICK_OVERCLOCK)
        Handler(Looper.getMainLooper()).postDelayed({
            if (activeThisRun && NativeLibrary.IsRunning()) {
                setOverclock(OVERCLOCK)
            }
        }, UNSTICK_MS)
    }

    private fun setOverclock(value: Float) = NativeConfig.setFloat(
        NativeConfig.LAYER_CURRENT, Settings.FILE_DOLPHIN, Settings.SECTION_INI_CORE, KEY_OVERCLOCK, value
    )
}
