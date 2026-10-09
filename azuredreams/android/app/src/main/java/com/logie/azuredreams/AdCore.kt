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

    fun shader(picture: Int): ShaderConfig = when (picture) {
        AdConfig.PICTURE_SMOOTH -> ShaderConfig.Default
        AdConfig.PICTURE_CRT -> ShaderConfig.CRT
        AdConfig.PICTURE_UPSCALED -> ShaderConfig.CUT2()
        else -> ShaderConfig.Sharp
    }
}
