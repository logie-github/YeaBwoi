// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.azuredreams

import android.content.Context
import com.swordfish.libretrodroid.ShaderConfig
import com.swordfish.libretrodroid.Variable

/** The PlayStation emulator core (SwanStation) and the options the app runs it with. */
object AdCore {
    /** Built by scripts/build-swanstation.sh and packaged as one of the app's native libraries. */
    const val LIBRARY = "libswanstation_libretro_android.so"

    fun variables(context: Context, config: AdConfig): Array<Variable> {
        // The player's BIOS, whatever its file is called and whichever region it's from; without
        // one, the core finds none and uses OpenBIOS.
        val bios = AdFiles.bios(context)?.name ?: ""
        return arrayOf(
            // Drawn with OpenGL ES, at several times the PlayStation's resolution.
            Variable("swanstation_GPU_Renderer", "OpenGL"),
            resolution(config.resolution),
            steady3d(config.steady3d),
            // 24-bit color instead of the PlayStation's dithered 15-bit: smooth shading when upscaled.
            Variable("swanstation_GPU_TrueColor", "true"),
            // One memory card file for the app (saves/duckstation_shared_card_1.mcd), written a few
            // seconds after the game saves.
            Variable("swanstation_MemoryCards_Card1Type", "Shared"),
            Variable("swanstation_BIOS_PathNTSCU", bios),
            Variable("swanstation_BIOS_PathNTSCJ", bios),
            Variable("swanstation_BIOS_PathPAL", bios),
            // Straight into the game, past the PlayStation logo.
            Variable("swanstation_BIOS_PatchFastBoot", "true"),
            Variable("swanstation_Display_ShowOSDMessages", "false"),
        )
    }

    fun resolution(scale: Int) = Variable("swanstation_GPU_ResolutionScale", scale.toString())

    fun steady3d(enabled: Boolean) = Variable("swanstation_GPU_PGXPEnable", enabled.toString())

    fun shader(config: AdConfig): ShaderConfig = when {
        config.hd2d -> ShaderConfig.HD2D(
            depthOfField = when (config.hd2dDepth) {
                AdConfig.DEPTH_OFF -> 0f
                AdConfig.DEPTH_STRONG -> 1f
                else -> 0.6f
            },
            bloom = if (config.hd2dBloom) 0.5f else 0f,
            resolution = config.resolution,
        )
        config.picture == AdConfig.PICTURE_SMOOTH -> ShaderConfig.Default
        config.picture == AdConfig.PICTURE_CRT -> ShaderConfig.CRT
        config.picture == AdConfig.PICTURE_UPSCALED -> ShaderConfig.CUT2()
        else -> ShaderConfig.Sharp
    }
}
