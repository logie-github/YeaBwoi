// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft

import android.content.Context
import android.system.Os
import com.logie.warcraft.modules.ModuleBuild
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * The server package (`server.tar.xz`, made by scripts/build-azerothcore.sh) that ships in the APK:
 * unpacked into `files/server` the first time, and again after each update of the app. Or, in its
 * place, a realm built with the user's modules (ModuleStore), for this version of the app. The
 * databases, configs and data stay.
 */
object ServerPackage {
    private const val ASSET = "server.tar.xz"
    private const val STAMP = ".installed"

    /** This build of the app carries a server (local builds without one don't). */
    fun isBundled(context: Context): Boolean =
        runCatching { context.assets.openFd(ASSET).use { true } }.getOrDefault(false)

    private fun bundledStamp(context: Context): String {
        val size = context.assets.openFd(ASSET).use { it.length }
        return "${BuildConfig.VERSION_CODE}:$size"
    }

    /** What's to be unpacked: the modules realm for this version, if there is one, or the app's. */
    private fun expectedStamp(context: Context): String =
        ModuleStore.realmForThisVersion(context)?.let { ModuleBuild.stamp(it) } ?: bundledStamp(context)

    /** The unpacked server is the one to run. */
    fun isInstalled(context: Context): Boolean {
        val file = File(WowFiles.server(context), STAMP)
        return file.isFile && runCatching { file.readText() == expectedStamp(context) }.getOrDefault(false)
    }

    /** What the package was built from (its VERSION file), once unpacked. */
    fun version(context: Context): String? =
        File(WowFiles.server(context), "VERSION").takeIf { it.isFile }?.readText()

    /**
     * Unpacks the app's own server next to the old one, then swaps them, so a failure leaves the
     * old one; a modules realm there goes. [onProgress] gets the fraction of the package read.
     * Background thread.
     */
    fun install(context: Context, onProgress: (Float) -> Unit) {
        if (!isBundled(context)) throw FileNotFoundException("This build of the app has no server in it")
        val total = context.assets.openFd(ASSET).use { it.length }
        unpack(context, context.assets.open(ASSET), total, bundledStamp(context), onProgress)
        ModuleStore.forgetRealm(context)
    }

    /** As [install], from a package downloaded to [file] (a modules realm), stamped [stamp]. */
    fun installFile(context: Context, file: File, stamp: String, onProgress: (Float) -> Unit) {
        unpack(context, file.inputStream(), file.length(), stamp, onProgress)
    }

    private fun unpack(context: Context, input: java.io.InputStream, size: Long, stamp: String, onProgress: (Float) -> Unit) {
        val root = WowFiles.root(context)
        val target = WowFiles.server(context)
        val staging = File(root, "server.new")
        staging.deleteRecursively()
        staging.mkdirs()

        val total = size.toFloat()
        val counting = CountingInputStream(input)
        TarArchiveInputStream(XZCompressorInputStream(counting.buffered(1 shl 16))).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                // Entries are server/...: put them in staging/... instead.
                val relative = entry.name.removePrefix("./").removePrefix("server").trimStart('/')
                if (relative.isEmpty()) continue
                if (relative.split('/').any { it == ".." }) throw IOException("Bad path in the server package: ${entry.name}")
                val file = File(staging, relative)
                when {
                    entry.isDirectory -> file.mkdirs()
                    entry.isSymbolicLink -> {
                        file.parentFile?.mkdirs()
                        Os.symlink(entry.linkName, file.path)
                    }
                    else -> {
                        file.parentFile?.mkdirs()
                        file.outputStream().use { tar.copyTo(it, 1 shl 16) }
                        if (entry.mode and 0b001_000_000 != 0) file.setExecutable(true, true)
                    }
                }
                onProgress(counting.count / total)
            }
        }
        if (!File(staging, "ctl.sh").isFile) throw IOException("Not a server package")
        File(staging, STAMP).writeText(stamp)

        val old = File(root, "server.old")
        old.deleteRecursively()
        if (target.exists() && !target.renameTo(old)) throw IOException("Couldn't replace the old server")
        if (!staging.renameTo(target)) throw IOException("Couldn't put the server in place")
        old.deleteRecursively()
    }

    private class CountingInputStream(private val input: java.io.InputStream) : java.io.FilterInputStream(input) {
        var count = 0L
            private set

        override fun read(): Int = super.read().also { if (it >= 0) count++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }

        override fun skip(n: Long): Long = super.skip(n).also { count += it }
    }
}
