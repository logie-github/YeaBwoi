// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.warcrafts.library

/**
 * Keeps the games off Blizzard's servers. The games' runtime (Wine on the phone) looks names up
 * with its own C library, which reads `etc/hosts` and `etc/nsswitch.conf` in the runtime's folder
 * (their paths are compiled into it); neither file comes with the runtime.
 *
 * Private (the default): no name server at all ([nsswitch]), and only this phone's own name in
 * [hosts]. Every other name fails to resolve, Blizzard's among them: to the games the phone is a
 * PC without a network, which they handle as offline (a name that resolves to nowhere can look
 * like a server that's down instead). Private off: names go to the name server, Blizzard's too.
 * Plain Kotlin, unit-tested.
 */
object Offline {
    /** The runtime's hosts file: this phone by its name, nothing else. */
    fun hosts(): String = "127.0.0.1\tlocalhost\n::1\tlocalhost\n"

    /** While [private], the hosts file alone (no name server: offline); else the name server after it. */
    fun nsswitch(private: Boolean): String = if (private) "hosts: files\n" else "hosts: files dns\n"
}
