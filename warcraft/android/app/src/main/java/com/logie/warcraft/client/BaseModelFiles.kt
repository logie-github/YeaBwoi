// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.client

import java.io.File

/**
 * Base Model (the launcher's Game options; the realm's mod-base-model): every character drawn without its
 * gear while it's on. The switch is a file in the app's files (`base-model/enabled`), which the world
 * server looks for every two seconds, so it works while the realm runs.
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
