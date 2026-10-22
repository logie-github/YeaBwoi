// SPDX-License-Identifier: GPL-3.0-or-later
package com.logie.warcraft.spp.core

import com.logie.warcraft.tww.core.TwwInstall
import java.io.File
import java.nio.file.Files

object SppInstall {
    fun inspect(client: File, server: File, maps: File = server): TwwInstall.Report {
        val local = member(client, SppRealm.CLIENT_EXE)?.isFile == true
        val spec = when {
            local -> SppRealm.SPEC
            member(client, "Wow-64.exe")?.isFile == true -> SppRealm.SPEC.copy(executableNames = setOf("wow-64.exe"))
            else -> SppRealm.SPEC.copy(executableNames = setOf("wow.exe"))
        }
        return TwwInstall.inspect(client, server, maps, spec)
    }

    fun member(root: File, path: String): File? = TwwInstall.resolve(root, path)?.takeIf {
        it.toPath().startsWith(root.canonicalFile.toPath())
    }

    fun clientRoot(root: File): File? {
        val candidates = root.walkTopDown().maxDepth(10).onEnter { !Files.isSymbolicLink(it.toPath()) }
            .filter { it.isFile && (it.name.equals("Wow-64.exe", true) || it.name.equals("Wow.exe", true) || it.name.equals(SppRealm.CLIENT_EXE, true)) }
            .map { it.parentFile }.distinct().toList()
        return candidates.singleOrNull()
    }

    fun serverRoot(root: File): File? {
        fun matches(folder: File) = member(folder, "Server/bin64/bnetserver.exe")?.isFile == true &&
            member(folder, "Realms/1/Settings/world.conf")?.isFile == true &&
            member(folder, "Server/Database/my-large.ini")?.isFile == true && member(folder, "Launcher.bat")?.isFile == true
        if (matches(root)) return root
        return root.walkTopDown().maxDepth(8).onEnter { !Files.isSymbolicLink(it.toPath()) }
            .filter { it.isDirectory && it.name.equals("spp-legion", true) && matches(it) }.toList().singleOrNull()
    }
}
