// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft

import android.content.Context
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Wrath Remix's Timerunners going home to ChromieCraft (mod-wrath-remix: Back to Your Own Time, as
 * Legion Remix sends its characters home). The Remix world server writes the Timerunner's character
 * to `files/run/transfers` and lets them go; this sees it, closes the game, and starts the server
 * again on ChromieCraft, whose world server takes the character in, in their side's capital. The
 * next Press start logs in there.
 */
object Homecomings {
    fun folder(context: Context) = File(WowFiles.run(context), "transfers")

    /** A character waiting to go home: its dump, and the name it went with. */
    internal fun waiting(folder: File): List<String> =
        folder.listFiles { file -> file.isFile && file.name.endsWith(".dump") }
            ?.sortedBy { it.name }
            ?.map { dump -> File(folder, dump.name.removeSuffix(".dump") + ".who").takeIf { it.isFile }?.readLines()?.getOrNull(1) ?: dump.name }
            .orEmpty()

    /** While Wrath Remix runs: a Timerunner who went home takes the server to ChromieCraft. */
    suspend fun check(context: Context) {
        val status = Server.status.value
        if (status.phase != Server.Phase.RUNNING || status.realm != Realms.Realm.REMIX) return
        val names = withContext(Dispatchers.IO) { waiting(folder(context)) }
        if (names.isEmpty()) return
        val app = context.applicationContext
        Toast.makeText(app, app.getString(R.string.wow_remix_home, names.joinToString(", ")), Toast.LENGTH_LONG).show()
        RealmLink.leaveGame(app)
        Realms.set(app, Realms.Realm.CHROMIECRAFT)
        Server.stop(app) { Server.start(app) }
    }
}
