// SPDX-License-Identifier: GPL-3.0-or-later
package com.logie.warcraft.local

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest

object LocalConfig {
    fun backup(file: File) {
        if (!file.isFile) return
        val backup = File(file.parentFile, file.name + ".before-local")
        if (Files.isSymbolicLink(file.toPath()) || Files.isSymbolicLink(backup.toPath()))
            throw IOException("Configuration backup paths must not be symbolic links.")
        if (!backup.exists()) file.copyTo(backup)
    }

    fun atomicWrite(file: File, content: String) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, file.name + ".tmp")
        if (Files.isSymbolicLink(file.toPath()) || Files.isSymbolicLink(temporary.toPath()))
            throw IOException("Configuration paths must not be symbolic links.")
        temporary.writeText(content)
        if (!temporary.renameTo(file)) { temporary.delete(); throw IOException("Could not write ${file.name}.") }
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
