// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.logie.warcraft.game.GestureView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * The realm's server runs in a process of its own (`:realm`, [ServerService]), apart from the app's
 * screens and the game: when Android closes the app, or the game, or takes back its memory, the
 * programs a process started go with it, and the server must not. Only Stop stops it.
 *
 * The app's process sees the server through this: [Server.start] and [Server.stop] there ask the
 * realm's process ([ServerService]), [Server.worldCommand] is passed on to it, and [Server.status]
 * there is the realm's own, sent over as it changes ([publish]) and asked for when the app's
 * process starts ([attach]). The broadcasts stay within the app (its package, and a permission
 * only it holds).
 */
object RealmLink {
    const val ACTION_START = "com.logie.warcraft.realm.START"
    const val ACTION_STOP = "com.logie.warcraft.STOP_SERVER"
    private const val ACTION_STATUS = "com.logie.warcraft.realm.STATUS"
    private const val ACTION_HELLO = "com.logie.warcraft.realm.HELLO"
    private const val ACTION_COMMAND = "com.logie.warcraft.realm.COMMAND"
    private const val ACTION_LEAVE_GAME = "com.logie.warcraft.realm.LEAVE_GAME"
    const val EXTRA_REALM = "realm"
    private const val EXTRA_STATUS = "status"
    private const val EXTRA_TEXT = "text"
    private const val PERMISSION = "com.logie.warcraft.permission.REALM"

    /** This is the realm's process. */
    val inRealm: Boolean by lazy {
        runCatching { File("/proc/self/cmdline").readText().substringBefore('\u0000').endsWith(":realm") }.getOrDefault(false)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var app: Context? = null

    // ---- The app's process -------------------------------------------------------------------

    /** Once, as the app's process starts: hear the realm, and ask how it is. */
    fun attach(context: Context) {
        if (inRealm || app != null) return
        val app = context.applicationContext.also { this.app = it }
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    ACTION_STATUS -> intent.getStringExtra(EXTRA_STATUS)?.let { json ->
                        runCatching { decode(json) }.getOrNull()?.let { Server.mirror(it) }
                    }
                    ACTION_LEAVE_GAME -> GestureView.leaveGame()
                }
            }
        }, IntentFilter().apply { addAction(ACTION_STATUS); addAction(ACTION_LEAVE_GAME) }, PERMISSION, null)
        send(app, Intent(ACTION_HELLO))
    }

    fun start(context: Context, realm: Realms.Realm) = toRealm(context, Intent(ACTION_START).putExtra(EXTRA_REALM, realm.key))

    fun stop(context: Context) = toRealm(context, Intent(ACTION_STOP))

    /** A line for the world server's console, if the realm's process is there to take it. */
    fun command(text: String): Boolean {
        val context = app ?: return false
        send(context, Intent(ACTION_COMMAND).putExtra(EXTRA_TEXT, text))
        return true
    }

    private fun toRealm(context: Context, intent: Intent) {
        val app = context.applicationContext
        ContextCompat.startForegroundService(app, intent.setClass(app, ServerService::class.java))
    }

    // ---- The realm's process -----------------------------------------------------------------

    /** The realm's process listening: the app asking how it is, and console lines. */
    fun listen(context: Context): BroadcastReceiver {
        val app = context.applicationContext.also { this.app = it }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    ACTION_HELLO -> publishNow(Server.status.value)
                    ACTION_COMMAND -> intent.getStringExtra(EXTRA_TEXT)?.let { Server.worldCommand(it) }
                }
            }
        }
        app.registerReceiver(receiver, IntentFilter().apply { addAction(ACTION_HELLO); addAction(ACTION_COMMAND) }, PERMISSION, null)
        return receiver
    }

    @Volatile private var lastSent = 0L
    @Volatile private var lastPhase: Server.Phase? = null
    @Volatile private var pending = false

    /**
     * The realm's status, to the app: at once when its phase changes, otherwise at most a few
     * times a second (a database import has thousands of lines).
     */
    fun publish(status: Server.Status) {
        if (!inRealm || app == null) return
        val now = System.currentTimeMillis()
        if (status.phase != lastPhase || now - lastSent >= 300) {
            publishNow(status)
            return
        }
        if (pending) return
        pending = true
        scope.launch {
            delay(300)
            pending = false
            publishNow(Server.status.value)
        }
    }

    private fun publishNow(status: Server.Status) {
        val context = app ?: return
        lastSent = System.currentTimeMillis()
        lastPhase = status.phase
        send(context, Intent(ACTION_STATUS).putExtra(EXTRA_STATUS, encode(status)))
    }

    /** The game, on the app's side, closes (a Timerunner going home). */
    fun leaveGame(context: Context) = send(context.applicationContext, Intent(ACTION_LEAVE_GAME))

    private fun send(context: Context, intent: Intent) {
        runCatching { context.sendBroadcast(intent.setPackage(context.packageName), PERMISSION) }
    }

    // ---- The status, across ------------------------------------------------------------------

    internal fun encode(s: Server.Status): String = JSONObject().apply {
        put("phase", s.phase.name)
        s.step?.let { put("step", it.name) }
        s.progress?.let { put("progress", it.toDouble()) }
        s.detail?.let { put("detail", it) }
        put("world", s.world.name)
        s.error?.let { put("error", it) }
        s.log?.let { put("log", it) }
        s.lanAddress?.let { put("lan", it) }
        put("realm", s.realm.name)
        put("databaseUp", s.databaseUp)
    }.toString()

    internal fun decode(json: String): Server.Status {
        val o = JSONObject(json)
        return Server.Status(
            phase = Server.Phase.valueOf(o.getString("phase")),
            step = o.optString("step").takeIf { it.isNotEmpty() }?.let { Server.Step.valueOf(it) },
            progress = if (o.has("progress")) o.getDouble("progress").toFloat() else null,
            detail = o.optString("detail").takeIf { o.has("detail") },
            world = Server.World.valueOf(o.getString("world")),
            error = o.optString("error").takeIf { o.has("error") },
            log = o.optString("log").takeIf { o.has("log") },
            lanAddress = o.optString("lan").takeIf { o.has("lan") },
            realm = Realms.Realm.valueOf(o.getString("realm")),
            databaseUp = o.optBoolean("databaseUp"),
        )
    }
}
