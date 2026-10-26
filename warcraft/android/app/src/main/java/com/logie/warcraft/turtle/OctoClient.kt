// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.turtle

import android.content.Context
import com.logie.warcraft.WowUpdater
import com.logie.warcraft.client.ZipDownload
import com.logie.warcraft.turtle.core.ClientSync
import com.logie.warcraft.turtle.core.ClientTorrent
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/**
 * OctoWoW's client, from OctoWoW's own download server (dl.octowow.st), as its launcher gets it:
 * the torrent that lists its files with their checksums ([ClientTorrent]), and each file over
 * HTTPS with ranges from the torrent's web seed. [ClientSync] does the downloading and checking.
 */
object OctoClient {
    /** OctoWoW's current torrent (its bytes), from its server. Background thread. */
    fun fetch(): ByteArray {
        val connection = (URI(ClientTorrent.OCTOWOW_URL).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", WowUpdater.userAgent)
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${connection.responseCode}")
            val out = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > ClientTorrent.MAX_SIZE) throw IOException("The client's torrent is too large")
                }
            }
            return out.toByteArray()
        } finally {
            connection.disconnect()
        }
    }

    /** The torrent the client on the phone was last made to match, or null. */
    fun saved(context: Context): ClientTorrent? = savedBytes(context)?.let { runCatching { ClientTorrent.parse(it) }.getOrNull() }

    fun savedBytes(context: Context): ByteArray? = runCatching { TurtleFiles.layout(context).clientTorrent.readBytes() }.getOrNull()

    fun save(context: Context, bytes: ByteArray) {
        TurtleFiles.fs(context).writeAtomically(TurtleFiles.layout(context).clientTorrent, bytes)
    }

    /** Where each of the torrent's files comes from: its web seed (BEP 19), over HTTPS with ranges. */
    fun source(torrent: ClientTorrent): (ClientTorrent.Entry) -> ZipDownload.Source {
        val seed = torrent.webSeeds.firstOrNull() ?: throw IOException("OctoWoW's torrent names no web seed to download from")
        return { entry -> ZipDownload.Http(torrent.url(seed, entry), WowUpdater.userAgent) }
    }

    /** A sync of [folder] (the client while it's worked on) with [torrent]. */
    fun sync(context: Context, torrent: ClientTorrent, folder: java.io.File): ClientSync {
        val layout = TurtleFiles.layout(context)
        return ClientSync(torrent, folder, layout.clientKept, TurtleFiles.fs(context), source(torrent))
    }

    /** What OctoWoW's newer client would change on the phone, or null when the phone has the current one. */
    data class Update(val torrent: ClientTorrent, val changes: ClientTorrent.Changes?)

    /** Asks OctoWoW's server for its current client. Background thread; throws if it can't be reached. */
    fun checkUpdate(context: Context): Update? {
        val current = ClientTorrent.parse(fetch())
        val state = TurtleFiles.state(context)
        if (state.clientTorrent == current.infoHash) return null
        return Update(current, saved(context)?.let { current.changesFrom(it) })
    }
}
