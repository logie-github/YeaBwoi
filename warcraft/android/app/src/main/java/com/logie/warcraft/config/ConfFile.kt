// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.config

/**
 * An AzerothCore config file (`worldserver.conf`, a module's `.conf`): `Key = value` lines among
 * comments. Changes keep the file as it is, comments and order, and only rewrite the lines of the
 * keys that change; a key the file doesn't have is added at the end.
 */
class ConfFile(text: String) {
    private val lines = text.lines().toMutableList()

    private fun indexOf(key: String): Int {
        val pattern = Regex("^${Regex.escape(key)}\\s*=")
        return lines.indexOfFirst { pattern.containsMatchIn(it) }
    }

    /** The value of [key], without the quotes around text, or null if the file doesn't set it. */
    operator fun get(key: String): String? {
        val index = indexOf(key)
        if (index < 0) return null
        val value = lines[index].substringAfter('=').trim()
        return if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) value.substring(1, value.length - 1) else value
    }

    /** Sets [key] to a number or a 0/1 switch, written as it is. */
    operator fun set(key: String, value: String) {
        val line = "$key = $value"
        val index = indexOf(key)
        if (index >= 0) {
            lines[index] = line
        } else {
            if (lines.isNotEmpty() && lines.last().isEmpty()) lines.add(lines.size - 1, line) else lines.add(line)
        }
    }

    /** Sets [key] to text, in quotes. */
    fun setText(key: String, value: String) {
        require('"' !in value && '\n' !in value) { "Config text can't hold quotes or line breaks" }
        set(key, "\"$value\"")
    }

    fun setSwitch(key: String, on: Boolean) = set(key, if (on) "1" else "0")

    override fun toString(): String = lines.joinToString("\n")
}
