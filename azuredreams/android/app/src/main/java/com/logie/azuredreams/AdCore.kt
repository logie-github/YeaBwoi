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
            *pgxp(config),
            // 24-bit color instead of the PlayStation's dithered 15-bit: smooth shading when upscaled.
            Variable("swanstation_GPU_TrueColor", "true"),
            // Memory card 1 is handed to the app (libretro save RAM), which keeps it in its file.
            Variable("swanstation_MemoryCards_Card1Type", "Libretro"),
            Variable("swanstation_BIOS_PathNTSCU", bios),
            Variable("swanstation_BIOS_PathNTSCJ", bios),
            Variable("swanstation_BIOS_PathPAL", bios),
            // Straight into the game, past the PlayStation logo.
            Variable("swanstation_BIOS_PatchFastBoot", "true"),
            Variable("swanstation_Display_ShowOSDMessages", "false"),
        )
    }

    fun resolution(scale: Int) = Variable("swanstation_GPU_ResolutionScale", scale.toString())

    /**
     * PGXP: Steady 3D, and for 2D-HD the scene's depth, which SwanStation then hands over in the
     * frame's alpha (and 2D-HD needs PGXP for it).
     */
    fun pgxp(config: AdConfig): Array<Variable> = arrayOf(
        Variable("swanstation_GPU_PGXPEnable", (config.steady3d || config.hd2d).toString()),
        Variable("swanstation_GPU_SceneDepth", config.hd2d.toString()),
    )

    fun shader(config: AdConfig): ShaderConfig = when {
        config.hd2d -> ShaderConfig.HD2D(
            depthOfField = when (config.hd2dDepth) {
                AdConfig.DEPTH_OFF -> 0f
                AdConfig.DEPTH_STRONG -> 1f
                else -> 0.6f
            },
            bloom = if (config.hd2dBloom) 0.45f else 0f,
            motes = if (config.hd2dMotes) 1f else 0f,
            resolution = config.resolution,
        )
        config.picture == AdConfig.PICTURE_SMOOTH -> ShaderConfig.Default
        config.picture == AdConfig.PICTURE_CRT -> ShaderConfig.CRT
        config.picture == AdConfig.PICTURE_UPSCALED -> ShaderConfig.CUT2()
        else -> ShaderConfig.Sharp
    }
}
