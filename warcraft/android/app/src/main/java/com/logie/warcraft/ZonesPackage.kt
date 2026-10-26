// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft

import android.content.Context
import com.logie.warcraft.client.ZipDownload
import com.logie.warcraft.turtle.core.SafeFs
import com.logie.warcraft.turtle.core.VerifiedDownload
import com.logie.warcraft.update.ZonesPackageFormat
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/**
 * The Worgen's and Goblins' starting areas (Gilneas, Kezan and the Lost Isles from Cataclysm:
 * scripts/package-zones.sh, mod-worgen-goblin's data/zones): too big for the APK, published by CI as
 * this repository's release `cata-zones-<hash>`, which each build of the app names with its size and
 * SHA-256 ([pinned]). [downloadAndInstall] downloads it (carrying on after a dropped connection),
 * checks it and unpacks it into `files/zones`, where ctl.sh finds it (WOW_ZONES_DIR) and the realm
 * takes the areas in at its next start.
 */
object ZonesPackage {
    const val ASSET = "cata-zones.tar.xz"
    private const val STAMP = ".installed"

    data class Pin(val tag: String, val sha256: String, val size: Long, val unpacked: Long)

    /** The package this build of the app is made for, if CI gave it one (local builds may not). */
    val pinned: Pin?
        get() = if (BuildConfig.ZONES_TAG.isEmpty() || BuildConfig.ZONES_SHA256.length != 64) null
        else Pin(BuildConfig.ZONES_TAG, BuildConfig.ZONES_SHA256, BuildConfig.ZONES_SIZE, BuildConfig.ZONES_UNPACKED)

    fun folder(context: Context) = File(WowFiles.root(context), "zones")

    private fun staging(context: Context) = File(WowFiles.root(context), "zones.new")

    private fun downloadFile(context: Context) = File(File(WowFiles.root(context), "zones.download"), ASSET)

    private fun fs(context: Context) = SafeFs(WowFiles.root(context))

    /** The SHA-256 of the package in `zones/`, if one is unpacked there. */
    fun installedSha(context: Context): String? =
        File(folder(context), STAMP).takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.length == 64 }

    /** The package in place is the one this build is made for (or there's none to have). */
    fun isCurrent(context: Context): Boolean = pinned?.let { installedSha(context) == it.sha256 } ?: true

    /** The starting areas are to be downloaded: this build names a package, the realm's set up, it isn't in. */
    fun needed(context: Context): Boolean = pinned != null && WowFiles.hasServerData(context) && !isCurrent(context)

    /**
     * Downloads (or finishes downloading), checks and unpacks the pinned package, in place of the one
     * there. [onProgress] gets the part (1: download, 2: unpack), how far and what to show. Background
     * thread.
     */
    fun downloadAndInstall(context: Context, cancelled: () -> Boolean, onProgress: (part: Int, progress: Float, detail: String?) -> Unit) {
        val pin = pinned ?: throw IOException("This build of the app has no starting areas' package")
        val fs = fs(context)
        val file = downloadFile(context)
        val have = File(file.path + ".part").length().coerceAtMost(pin.size)
        val free = WowFiles.root(context).usableSpace
        val wanted = (pin.size - have) + pin.unpacked + SPACE_MARGIN
        if (free < wanted) throw IOException(context.getString(R.string.wow_zones_error_space, (wanted - free) / (1 shl 20)))

        val asset = WowUpdater.releaseAssets(pin.tag).firstOrNull { it.name == ASSET }
            ?: throw IOException("The release ${pin.tag} has no $ASSET")
        if (asset.size != pin.size) throw IOException("The release's $ASSET is ${asset.size} bytes, not the ${pin.size} this app expects")
        val source = ZipDownload.Http(asset.url, WowUpdater.userAgent) { url -> WowUpdater.assetHeaders(url) }
        VerifiedDownload(source, file, pin.size, pin.sha256, fs).run(cancelled) { done ->
            onProgress(1, done.toFloat() / pin.size, "${done / (1 shl 20)} / ${pin.size / (1 shl 20)} MB")
        }

        val staging = staging(context)
        fs.delete(staging)
        fs.mkdirs(staging)
        try {
            val counting = Counting(file.inputStream())
            unpack(counting.buffered(1 shl 16), staging, fs.within(staging), cancelled) {
                onProgress(2, counting.count.toFloat() / pin.size, null)
            }
            if (!File(staging, "TAG").isFile || !File(staging, "client/files.txt").isFile || !File(staging, "server/maps").isDirectory) {
                throw IOException("Not the starting areas' package")
            }
            File(staging, STAMP).writeText(pin.sha256)
            fs.replace(staging, folder(context))
        } catch (e: Exception) {
            fs.delete(staging)
            throw e
        }
        fs.delete(file)
    }

    /** The package's entries (cata-zones/...) into [into]: plain files and folders, each path checked. */
    private fun unpack(input: InputStream, into: File, staging: SafeFs, cancelled: () -> Boolean, onEntry: () -> Unit) {
        TarArchiveInputStream(XZCompressorInputStream(input)).use { tar ->
            while (true) {
                if (cancelled()) throw VerifiedDownload.Cancelled()
                val entry = tar.nextEntry ?: break
                val relative = ZonesPackageFormat.relative(entry.name) ?: throw IOException("Bad path in the package: ${entry.name}")
                if (relative.isEmpty()) continue
                val target = staging.check(File(into, relative))
                when {
                    entry.isDirectory -> staging.mkdirs(target)
                    entry.isFile -> {
                        target.parentFile?.let { staging.mkdirs(it) }
                        target.outputStream().use { tar.copyTo(it, 1 shl 16) }
                    }
                    else -> throw IOException("Unexpected entry in the package: ${entry.name}")
                }
                onEntry()
            }
        }
    }

    private class Counting(input: InputStream) : FilterInputStream(input) {
        var count = 0L
            private set

        override fun read(): Int = super.read().also { if (it >= 0) count++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }

        override fun skip(n: Long): Long = super.skip(n).also { count += it }
    }

    private const val SPACE_MARGIN = 200L shl 20
}
