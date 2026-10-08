// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.dolphinxd

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.logie.dolphinxd.savestate.CardGuard
import org.dolphinemu.dolphinemu.NativeLibrary
import org.dolphinemu.dolphinemu.R
import java.io.File
import java.util.concurrent.Executors

/**
 * Save states that can never leave the player with two different saves: a state only loads while
 * the memory card is exactly as it was when the state was made, so no in-game save has happened
 * since (see [CardGuard]). Used by the three-finger gestures and the in-game menu.
 */
object XdSaveStates {
    /** Dolphin's own Quick save slot, so the in-game menu's Quick save/load share it. */
    const val QUICK_SLOT = 9

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun quickSave(context: Context) = save(context, QUICK_SLOT)

    fun quickLoad(context: Context) = load(context, QUICK_SLOT)

    /** Saves state [slot] of the running game. Main thread. */
    fun save(context: Context, slot: Int) {
        val app = context.applicationContext
        val versionId = XdConfig(app).activeVersionId
        worker.execute {
            // Read the card before the state is made, never after (see CardGuard.record).
            val requestedAt = System.currentTimeMillis()
            val card = CardGuard.fingerprint(cardRoot(versionId))
            main.post {
                if (!NativeLibrary.IsRunning()) return@post
                NativeLibrary.SaveState(slot)
                XdConfig(app).setStateCardFingerprint(versionId, slot, CardGuard.record(card, requestedAt))
                toast(app, if (slot == QUICK_SLOT) {
                    app.getString(R.string.xd_quick_saved)
                } else {
                    app.getString(R.string.xd_state_saved, slot + 1)
                })
            }
        }
    }

    /** Loads state [slot] if no in-game save happened since it was made. Main thread. */
    fun load(context: Context, slot: Int) {
        val app = context.applicationContext
        val config = XdConfig(app)
        val versionId = config.activeVersionId
        val recorded = config.stateCardFingerprint(versionId, slot)
        worker.execute {
            val root = cardRoot(versionId)
            val loadable = CardGuard.canLoad(
                stateTime = NativeLibrary.GetUnixTimeOfStateSlot(slot),
                recorded = recorded,
                lastGameWrite = NativeLibrary.GetLastMemoryCardWriteTime(),
                currentCard = { CardGuard.fingerprint(root) },
                newestCardWrite = { CardGuard.newestWrite(root) },
            )
            main.post {
                val quick = slot == QUICK_SLOT
                val message = when (loadable) {
                    null -> if (quick) {
                        app.getString(R.string.xd_quick_empty)
                    } else {
                        app.getString(R.string.xd_state_empty, slot + 1)
                    }

                    false -> app.getString(
                        if (quick) R.string.xd_quick_blocked else R.string.xd_state_blocked
                    )

                    true -> {
                        if (!NativeLibrary.IsRunning()) return@post
                        NativeLibrary.LoadState(slot)
                        if (quick) {
                            app.getString(R.string.xd_quick_loaded)
                        } else {
                            app.getString(R.string.xd_state_loaded, slot + 1)
                        }
                    }
                }
                toast(app, message)
            }
        }
    }

    /**
     * Where game version [versionId] keeps its memory card: Dolphin's default location for the
     * original game, the mod's own folder for a mod (see [XdSaveProfiles]).
     */
    private fun cardRoot(versionId: String): File = if (versionId == XdConfig.ORIGINAL) {
        File(XdFiles.userDirectory, "GC")
    } else {
        File(XdFiles.xdDirectory, "Saves/$versionId")
    }

    private fun toast(context: Context, message: String) =
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}
