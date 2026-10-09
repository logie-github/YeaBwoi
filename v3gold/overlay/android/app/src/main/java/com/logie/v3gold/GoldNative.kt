// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.v3gold

/** Native side of the in-game menu's experiments (vita3k/android/jni/gold_lab.cpp). */
object GoldNative {
    /** Skip Persona 4 Golden's motion blur pass (renderer/gold_lab.h). */
    @JvmStatic external fun setBlurFix(enabled: Boolean)

    /** One line per full-screen draw that read back a render target in the last frame shown. */
    @JvmStatic external fun blurReport(): Array<String>

    /** Vita3K's FPS hack: wait one screen refresh where the game asks for two. */
    @JvmStatic external fun setFpsHack(enabled: Boolean)

    /** Frames per second the game draws right now. */
    @JvmStatic external fun fps(): Int

    /** Every 32-bit word equal to [value] in the game's executable: [segment, offset, address, permissions]... */
    @JvmStatic external fun findWords(value: Int): IntArray

    @JvmStatic external fun read32(address: Int): Int

    @JvmStatic external fun write32(address: Int, value: Int): Boolean

    /** Vita3K's patch folder: patch/<title id>*.txt files apply when the game starts. */
    @JvmStatic external fun patchPath(): String

    @JvmStatic external fun titleId(): String
}
