// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.client

import java.io.File

/** Body: female character creation previews without starter gear, applied at the realm's next start.
 * The marker keeps the old name so existing launcher preferences carry over.
 */
object BaseModelFiles {
    private fun marker(root: File) = File(File(root, "base-model"), "enabled")

    fun isOn(root: File): Boolean = marker(root).isFile

    fun setOn(root: File, on: Boolean) {
        val marker = marker(root)
        if (on) {
            marker.parentFile?.mkdirs()
            marker.writeText("on\n")
        } else {
            marker.delete()
        }
    }
}
