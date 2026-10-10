// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.pe

import android.app.ActivityManager
import android.content.Context
import com.logie.pe.hd.HdTextures
import com.swordfish.libretrodroid.ShaderConfig
import com.swordfish.libretrodroid.Variable

/** The PlayStation emulator core (Beetle PSX HW) and the options the app runs it with. */
object PeCore {
    /** Built by scripts/build-beetle.sh and packaged as one of the app's native libraries. */
    const val LIBRARY = "libbeetle_psx_hw_libretro_android.so"

    /** [hdAvailable]: the HD texture pack is installed and linked for the core ([HdTextures.link]). */
    fun variables(context: Context, config: PeConfig, hdAvailable: Boolean): Array<Variable> {
        val (ramBudget, vramBudget) = hdCacheBudgets(context)
        return arrayOf(
            // Drawn with OpenGL ES, at several times the PlayStation's resolution.
            Variable("beetle_psx_hw_renderer", "hardware_gl"),
            resolution(config.resolution),
            *pgxp(config),
            // 24-bit color instead of the PlayStation's dithered 15-bit: smooth shading when upscaled.
            Variable("beetle_psx_hw_depth", "32bpp"),
            Variable("beetle_psx_hw_dither_mode", "disabled"),
            // Memory card 1 is handed to the app (libretro save RAM), which keeps it in its file.
            Variable("beetle_psx_hw_use_mednafen_memcard0_method", "libretro"),
            // Straight into the game, past the PlayStation logo.
            Variable("beetle_psx_hw_skip_bios", "enabled"),
            // HD textures: "<game>-texture-replacements" in the system folder (HdTextures.link).
            Variable("beetle_psx_hw_texture_directory", "system"),
            *hdTextures(config.hdTextures && hdAvailable),
            Variable("beetle_psx_hw_hd_caching_method", "eager"),
            Variable("beetle_psx_hw_hd_cache_ram_budget", ramBudget.toString()),
            Variable("beetle_psx_hw_hd_cache_vram_budget", vramBudget.toString()),
        )
    }

    fun resolution(scale: Int) =
        Variable("beetle_psx_hw_internal_resolution", if (scale <= 1) "1x(native)" else "${scale}x")

    /** PGXP: Steady 3D, with perspective-correct textures. */
    fun pgxp(config: PeConfig) = arrayOf(
        Variable("beetle_psx_hw_pgxp_mode", if (config.steady3d) "memory only" else "disabled"),
        Variable("beetle_psx_hw_pgxp_texture", if (config.steady3d) "enabled" else "disabled"),
    )

    /**
     * The HD texture pack, drawn in place of the game's textures when [on]. Textures the game loaded
     * before it's switched on change the next time the game loads them (the next room, say).
     */
    fun hdTextures(on: Boolean): Array<Variable> {
        val value = if (on) "enabled" else "disabled"
        return arrayOf(
            Variable("beetle_psx_hw_track_textures", value),
            Variable("beetle_psx_hw_replace_textures", value),
        )
    }

    /**
     * How much memory decoded HD textures may keep (MB), in RAM and on the GPU: Beetle's defaults
     * (2 and 3 GB) are a PC's; a phone's GPU shares its RAM, so they're sized by how much it has.
     */
    private fun hdCacheBudgets(context: Context): Pair<Int, Int> {
        val memory = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
        val gigabytes = memory.totalMem / (1024L * 1024 * 1024)
        return when {
            gigabytes >= 11 -> 1024 to 1536
            gigabytes >= 7 -> 512 to 1024
            else -> 256 to 512
        }
    }

    fun shader(config: PeConfig): ShaderConfig = when {
        config.picture == PeConfig.PICTURE_SMOOTH -> ShaderConfig.Default
        config.picture == PeConfig.PICTURE_CRT -> ShaderConfig.CRT
        config.picture == PeConfig.PICTURE_UPSCALED -> ShaderConfig.CUT2()
        else -> ShaderConfig.Sharp
    }
}
