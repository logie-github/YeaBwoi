// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.turtle

import android.content.Context
import android.system.Os
import com.logie.warcraft.BuildConfig
import com.logie.warcraft.WowUpdater
import com.logie.warcraft.client.ZipDownload
import com.logie.warcraft.turtle.core.SafeFs
import com.logie.warcraft.turtle.core.TurtlePackageFormat
import com.logie.warcraft.turtle.core.VerifiedDownload
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Turtle WoW's realm package (scripts/package-tortoise.sh): Tortoise-WoW's programs for arm64, its
 * own MariaDB, the SQL and a seed database. Not in the APK: CI publishes it as this repository's
 * release `turtle-server-<hash>`, and each build of the app names the one it's made for with its
 * size and SHA-256 ([pinned]). [install] downloads it (carrying on after a dropped connection),
 * checks it, unpacks it next to the one in place and swaps them, so a failure leaves what was there.
 */
object TurtlePackage {
    const val ASSET = "turtle-server.tar.xz"
    private const val STAMP = ".installed"

    data class Pin(val tag: String, val sha256: String, val size: Long, val unpacked: Long, val tortoise: String)

    /** The package this build of the app is made for, if CI gave it one (local builds may not). */
    val pinned: Pin?
        get() = if (BuildConfig.TURTLE_SERVER_TAG.isEmpty() || BuildConfig.TURTLE_SERVER_SHA256.length != 64) null
        else Pin(BuildConfig.TURTLE_SERVER_TAG, BuildConfig.TURTLE_SERVER_SHA256, BuildConfig.TURTLE_SERVER_SIZE,
            BuildConfig.TURTLE_SERVER_UNPACKED, BuildConfig.TURTLE_SERVER_COMMIT)

    /** The SHA-256 of the package in `server/`, if one is unpacked there. */
    fun installedSha(context: Context): String? =
        File(TurtleFiles.layout(context).server, STAMP).takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.length == 64 }

    fun isInstalled(context: Context): Boolean =
        installedSha(context) != null && TurtleFiles.layout(context).ctl.isFile

    /** The package in place is the one this build is made for. */
    fun isCurrent(context: Context): Boolean = pinned?.let { installedSha(context) == it.sha256 } ?: isInstalled(context)

    fun version(context: Context): String? =
        File(TurtleFiles.layout(context).server, "VERSION").takeIf { it.isFile }?.readText()

    /** Where the download is kept between tries. */
    fun downloadFile(context: Context) = File(TurtleFiles.layout(context).downloads, ASSET)

    fun downloadedSoFar(context: Context): Long = File(downloadFile(context).path + ".part").length()

    class NotPinned : IOException("This build of the app has no Turtle WoW realm package")

    /**
     * Downloads (or finishes downloading) and checks the pinned package. [onProgress]: bytes so far
     * and the total. Background thread.
     */
    fun download(context: Context, cancelled: () -> Boolean, onProgress: (Long, Long) -> Unit) {
        val pin = pinned ?: throw NotPinned()
        val asset = WowUpdater.releaseAssets(pin.tag).firstOrNull { it.name == ASSET }
            ?: throw IOException("The release ${pin.tag} has no $ASSET")
        if (asset.size != pin.size) throw IOException("The release's $ASSET is ${asset.size} bytes, not the ${pin.size} this app expects")
        val source = ZipDownload.Http(asset.url, WowUpdater.userAgent) { url -> WowUpdater.assetHeaders(url) }
        val download = VerifiedDownload(source, downloadFile(context), pin.size, pin.sha256, TurtleFiles.fs(context))
        download.run(cancelled) { onProgress(it, pin.size) }
    }

    /**
     * Unpacks the downloaded package into `server.new`, then puts it in place of `server/`.
     * [onProgress] gets the fraction read. The download is deleted once it's in. Background thread.
     */
    fun install(context: Context, cancelled: () -> Boolean, onProgress: (Float) -> Unit) {
        val pin = pinned ?: throw NotPinned()
        val layout = TurtleFiles.layout(context)
        val fs = TurtleFiles.fs(context)
        val file = downloadFile(context)
        if (!file.isFile || file.length() != pin.size) throw IOException("The package isn't downloaded")
        fs.delete(layout.serverStaging)
        fs.mkdirs(layout.serverStaging)
        val staging = fs.within(layout.serverStaging)
        try {
            val counting = Counting(file.inputStream())
            // Entries are server/...: they go in server.new/...
            TurtlePackageFormat.unpack(
                counting.buffered(1 shl 16), layout.serverStaging, staging,
                symlink = { target, link -> Os.symlink(target, link.path) },
                cancelled = cancelled,
                onEntry = { onProgress(counting.count.toFloat() / pin.size) },
            )
            if (!File(layout.serverStaging, "ctl.sh").isFile || !File(layout.serverStaging, "bin/mangosd").isFile) {
                throw IOException("Not a Turtle WoW realm package")
            }
            File(layout.serverStaging, STAMP).writeText(pin.sha256)
            fs.replace(layout.serverStaging, layout.server)
        } catch (e: Exception) {
            fs.delete(layout.serverStaging)
            throw e
        }
        fs.delete(file)
    }

    private class Counting(input: InputStream) : FilterInputStream(input) {
        var count = 0L
            private set

        override fun read(): Int = super.read().also { if (it >= 0) count++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }

        override fun skip(n: Long): Long = super.skip(n).also { count += it }
    }

    /** Deletes a download in progress (Repair, Uninstall). */
    fun forgetDownload(context: Context, fs: SafeFs = TurtleFiles.fs(context)) {
        val file = downloadFile(context)
        fs.delete(File(file.path + ".part"))
        fs.delete(file)
    }
}
