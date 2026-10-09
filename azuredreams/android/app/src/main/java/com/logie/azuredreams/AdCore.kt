// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.azuredreams

import com.swordfish.libretrodroid.ShaderConfig
import com.swordfish.libretrodroid.Variable

/** The PlayStation emulator core (PCSX ReARMed) and the options the app runs it with. */
object AdCore {
    /** Built by scripts/build-pcsx-rearmed.sh and packaged as one of the app's native libraries. */
    const val LIBRARY = "libpcsx_rearmed_libretro_android.so"

    fun variables(config: AdConfig): Array<Variable> = arrayOf(
        // One memory card file for the app (saves/pcsx-card1.mcd), written as soon as the game saves.
        Variable("pcsx_rearmed_memcard1", "shared"),
        // The player's BIOS if they added one, the core's own replacement otherwise.
        Variable("pcsx_rearmed_bios", "auto"),
        Variable("pcsx_rearmed_show_bios_bootlogo", "disabled"),
        sharper3d(config.sharper3d),
    )

    fun sharper3d(enabled: Boolean) =
        Variable("pcsx_rearmed_neon_enhancement_enable", if (enabled) "enabled" else "disabled")

    fun shader(config: AdConfig): ShaderConfig = when {
        config.hd2d -> ShaderConfig.HD2D(
            depthOfField = when (config.hd2dDepth) {
                AdConfig.DEPTH_OFF -> 0f
                AdConfig.DEPTH_STRONG -> 1f
                else -> 0.6f
            },
            bloom = if (config.hd2dBloom) 0.5f else 0f,
        )
        config.picture == AdConfig.PICTURE_SMOOTH -> ShaderConfig.Default
        config.picture == AdConfig.PICTURE_CRT -> ShaderConfig.CRT
        config.picture == AdConfig.PICTURE_UPSCALED -> ShaderConfig.CUT2()
        else -> ShaderConfig.Sharp
    }
}
