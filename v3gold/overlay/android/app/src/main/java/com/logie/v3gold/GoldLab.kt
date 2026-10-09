// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.v3gold

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File

/**
 * The in-game menu's experiments for Persona 4 Golden:
 *
 * - Motion blur: the renderer skips the pass that draws the previous frame back over the new one
 *   (vita3k/renderer/include/renderer/gold_lab.h).
 * - 60 FPS: Vita3K's FPS hack makes the game draw a frame every screen refresh instead of every
 *   other one. Persona 4's engine moves the game on by a stored time per frame, 1/30 s (the PS2
 *   game's 60 FPS patch sets it to 1/60), so at 60 FPS the game would run twice as fast. The speed
 *   fix finds the 1/30 values in the game's executable and changes them to 1/60: the ones in its
 *   data while it runs (kept at 1/60 every second, in case the game writes them again), and
 *   optionally the ones in its code through a Vita3K patch file, which applies from the next start.
 *
 * Main thread.
 */
object GoldLab {
    const val SPEED_FIX_OFF = 0
    const val SPEED_FIX_ALL = -1

    /** 1/30 and 1/60 as 32-bit floats. */
    const val ONE_THIRTIETH = 0x3D088889
    const val ONE_SIXTIETH = 0x3C888889

    private const val TAG = "V3Gold"
    private const val TICK_MS = 1000L
    private const val FIRST_TICK_MS = 3000L
    /** How often to look again while the game's executable has no 1/30 value in its data. */
    private const val RESCAN_TICKS = 5

    /** A 1/30 value in the game's executable. Segment 0 is its code, the others its data. */
    data class Value(val segment: Int, val offset: Int, val address: Int) {
        val inCode: Boolean get() = segment == 0
    }

    private val handler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var ticksSinceScan = 0

    /** The values found by the last search, in the game's data and in its code. */
    var dataValues: List<Value> = emptyList()
        private set
    var codeValues: List<Value> = emptyList()
        private set
    var searched = false
        private set

    /** Data values currently changed to 1/60. */
    private val changed = HashSet<Int>()

    private val tick = object : Runnable {
        override fun run() {
            apply()
            handler.postDelayed(this, TICK_MS)
        }
    }

    /** The game started (Emulator.setCurrentGameId): switch the experiments on as saved. */
    @JvmStatic
    fun onGameStarted(context: Context) {
        appContext = context.applicationContext
        val config = GoldConfig(context)
        runCatching {
            GoldNative.setBlurFix(config.blurFix)
            GoldNative.setFpsHack(config.sixtyFps)
        }.onFailure { Log.w(TAG, "Couldn't apply the experiments", it) }
        dataValues = emptyList()
        codeValues = emptyList()
        searched = false
        changed.clear()
        ticksSinceScan = RESCAN_TICKS
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, FIRST_TICK_MS)
    }

    /** The game closed. */
    @JvmStatic
    fun onGameStopped() {
        handler.removeCallbacks(tick)
        changed.clear()
    }

    fun setBlurFix(context: Context, enabled: Boolean) {
        GoldConfig(context).blurFix = enabled
        runCatching { GoldNative.setBlurFix(enabled) }
    }

    fun setSixtyFps(context: Context, enabled: Boolean) {
        GoldConfig(context).sixtyFps = enabled
        runCatching { GoldNative.setFpsHack(enabled) }
        apply()
    }

    fun setSpeedFix(context: Context, choice: Int) {
        GoldConfig(context).speedFix = choice
        apply()
    }

    fun setSpeedFixCode(context: Context, enabled: Boolean) {
        GoldConfig(context).speedFixCode = enabled
        apply()
    }

    /** Looks for the game's 1/30 values again (the ones changed to 1/60 are kept). */
    fun searchAgain() {
        scan()
        apply()
    }

    private fun scan() {
        ticksSinceScan = 0
        val words = runCatching { GoldNative.findWords(ONE_THIRTIETH) }.getOrNull() ?: return
        val found = words.toList().chunked(4).map { Value(segment = it[0], offset = it[1], address = it[2]) }
        // Values already changed to 1/60 no longer match; keep them.
        val keptData = dataValues.filter { it.address in changed }
        dataValues = (keptData + found.filter { !it.inCode }).distinctBy { it.address }.sortedBy { it.address }
        codeValues = found.filter { it.inCode }.ifEmpty { patchedCodeValues() }
        searched = true
        Log.i(TAG, "Speed fix: ${dataValues.size} values in data, ${codeValues.size} in code")
    }

    /** Puts the speed fix in the state the settings ask for. */
    private fun apply() {
        val context = appContext ?: return
        val config = GoldConfig(context)
        val wanted: Set<Int> = if (config.sixtyFps && config.speedFix != SPEED_FIX_OFF) {
            if (dataValues.isEmpty() && ++ticksSinceScan >= RESCAN_TICKS) {
                scan()
            }
            when (val choice = config.speedFix) {
                SPEED_FIX_ALL -> dataValues.map { it.address }.toSet()
                else -> dataValues.getOrNull(choice - 1)?.let { setOf(it.address) } ?: emptySet()
            }
        } else {
            emptySet()
        }

        runCatching {
            // Back to 1/30 where the fix no longer applies.
            for (address in changed.toList()) {
                if (address !in wanted) {
                    if (GoldNative.read32(address) == ONE_SIXTIETH) {
                        GoldNative.write32(address, ONE_THIRTIETH)
                    }
                    changed.remove(address)
                }
            }
            // 1/60 where it does, unless the game has since put something else there.
            for (address in wanted) {
                val current = GoldNative.read32(address)
                if (current == ONE_THIRTIETH || (current == ONE_SIXTIETH && address !in changed)) {
                    GoldNative.write32(address, ONE_SIXTIETH)
                    changed.add(address)
                } else if (current == ONE_SIXTIETH) {
                    changed.add(address)
                }
            }
        }.onFailure { Log.w(TAG, "Speed fix failed", it) }

        updatePatchFile(config)
    }

    /**
     * The code values the patch file already changed when the game started: they read 1/60 now, so
     * the search can't find them, but they're still the speed fix's.
     */
    private fun patchedCodeValues(): List<Value> {
        val file = patchFile() ?: return emptyList()
        if (!file.exists()) return emptyList()
        return runCatching {
            file.readLines().mapNotNull { line ->
                val match = Regex("""^(\d+):0x([0-9a-fA-F]+) """).find(line) ?: return@mapNotNull null
                Value(segment = match.groupValues[1].toInt(), offset = match.groupValues[2].toInt(16), address = 0)
            }
        }.getOrDefault(emptyList())
    }

    private fun patchFile(): File? {
        val folder = runCatching { GoldNative.patchPath() }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        val titleId = runCatching { GoldNative.titleId() }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        return File(folder, "${titleId}_V3GOLD_SPEED.txt")
    }

    /**
     * The game's code can't be changed while it runs, so its 1/30 values go in a Vita3K patch file
     * that applies when the game starts. Only with 60 FPS on: at 30 FPS they'd slow the game down.
     */
    private fun updatePatchFile(config: GoldConfig) {
        val file = patchFile() ?: return
        val wanted = config.sixtyFps && config.speedFix != SPEED_FIX_OFF && config.speedFixCode && codeValues.isNotEmpty()
        runCatching {
            if (wanted) {
                val text = buildString {
                    appendLine("# V3 Gold: 1/30 s values in the code changed to 1/60 s for 60 FPS.")
                    appendLine("# Made by the in-game menu's speed fix; it rewrites or deletes this file.")
                    appendLine("[eboot.bin]")
                    for (value in codeValues) {
                        // Little-endian bytes of 1/60 (0x3C888889).
                        appendLine("${value.segment}:0x${value.offset.toString(16)} 0x8988883C")
                    }
                }
                if (!file.exists() || file.readText() != text) {
                    file.parentFile?.mkdirs()
                    file.writeText(text)
                }
            } else if (file.exists()) {
                file.delete()
            }
        }.onFailure { Log.w(TAG, "Couldn't update $file", it) }
    }

    /** The frame rate the game draws at right now. */
    fun fps(): Int = runCatching { GoldNative.fps() }.getOrDefault(0)

    fun blurReport(): List<String> = runCatching { GoldNative.blurReport().toList() }.getOrDefault(emptyList())
}
