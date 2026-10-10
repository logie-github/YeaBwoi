// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.pe.hd

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.system.Os
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.Properties
import java.util.zip.ZipInputStream

/**
 * HD texture packs in Beetle PSX's format (like VieRock Retro HD's Parasite Eve HD): a folder of
 * images named <texture hash>-<palette hash>.png, which Beetle draws in place of the game's own
 * textures. The player picks the pack's downloaded archive (all the parts of a split .7z, or .zip
 * files) and the app unpacks the images into one folder of its own storage, files/hd/textures/.
 *
 * Beetle looks for "<game file's name>-texture-replacements" in the system folder (its texture
 * folder option set to "system"), so before the game starts [link] points that name at the pack.
 */
object HdTextures {
    data class Pack(val files: Long, val bytes: Long)

    sealed interface State {
        data object Idle : State
        /** Reading the archive's contents, before unpacking. */
        data object Checking : State
        data class Unpacking(val done: Long, val total: Long) : State
        /** Installed; [parts] are the files it came from ([partsBytes]), which the player may delete now. */
        data class Done(val pack: Pack, val parts: List<Uri>, val partsBytes: Long) : State
        data class Failed(val reason: Reason, val detail: String = "") : State
    }

    enum class Reason {
        NOT_AN_ARCHIVE, MIXED_FILES, PART_MISSING, CANT_READ, NOT_SEEKABLE, NO_IMAGES, NO_SPACE, DAMAGED,
        UNSUPPORTED, WRITE_FAILED, OUT_OF_MEMORY, CANCELLED,
    }

    private val mutableState = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = mutableState.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var cancelRequested = false

    private const val SPARE_BYTES = 256L * 1024 * 1024
    private val IMAGE_EXTENSIONS = setOf("png", "dds", "webp", "jpg", "jpeg", "bmp", "tga")

    private fun hdDir(context: Context) = File(context.filesDir, "hd")
    fun packDir(context: Context) = File(hdDir(context), "textures")
    private fun infoFile(context: Context) = File(hdDir(context), "pack.properties")

    /** The installed pack, or null. */
    fun installed(context: Context): Pack? {
        val info = infoFile(context)
        if (!info.isFile || !packDir(context).isDirectory) return null
        val properties = Properties().apply { runCatching { info.inputStream().use { load(it) } } }
        return Pack(
            files = properties.getProperty("files")?.toLongOrNull() ?: return null,
            bytes = properties.getProperty("bytes")?.toLongOrNull() ?: return null,
        )
    }

    val isWorking: Boolean get() = state.value is State.Checking || state.value is State.Unpacking

    /** The title screen is showing, and with it how the unpacking went: no notification needed. */
    @Volatile var appVisible = false

    /** Deletes the installed pack. Background thread. */
    fun delete(context: Context) {
        infoFile(context).delete()
        packDir(context).deleteRecursively()
        File(hdDir(context), "textures.importing").deleteRecursively()
    }

    /**
     * Points "<[gameFile]'s name>-texture-replacements" in [systemDir] at the pack, where Beetle
     * looks for it, and takes away links to it under other names (from other game files). Returns
     * whether the pack is there for the core.
     */
    fun link(context: Context, systemDir: File, gameFile: File): Boolean {
        val name = gameFile.nameWithoutExtension + "-texture-replacements"
        val target = packDir(context)
        systemDir.listFiles()?.filter { it.name.endsWith("-texture-replacements") && it.name != name }?.forEach {
            if (isLink(it)) it.delete()
        }
        val link = File(systemDir, name)
        if (installed(context) == null || isWorking) {
            if (isLink(link)) link.delete()
            return false
        }
        if (isLink(link) && runCatching { Os.readlink(link.path) }.getOrNull() == target.path) return true
        // Anything else by that name is the core's: a folder it made when there was no pack.
        if (isLink(link) || link.isFile) link.delete() else if (link.isDirectory) link.deleteRecursively()
        if (!link.exists()) runCatching { Os.symlink(target.path, link.path) }
        return isLink(link)
    }

    private fun isLink(file: File) = runCatching { java.nio.file.Files.isSymbolicLink(file.toPath()) }.getOrDefault(false)

    /** Starts installing the pack from [uris] (the files the player picked), in the background. */
    fun start(context: Context, uris: List<Uri>) {
        if (isWorking) return
        val app = context.applicationContext
        cancelRequested = false
        mutableState.value = State.Checking
        ContextCompat.startForegroundService(app, Intent(app, HdImportService::class.java))
        job = scope.launch {
            mutableState.value = runCatching { install(app, uris) }.getOrElse {
                State.Failed(Reason.CANT_READ, it.message ?: it.javaClass.simpleName)
            }
        }
    }

    fun cancel() {
        cancelRequested = true
    }

    /** Back to idle once the player has seen how it went. */
    fun acknowledge() {
        if (!isWorking) mutableState.value = State.Idle
    }

    /**
     * Deletes the downloaded files the pack came from, where the app is allowed to. Returns how
     * many it couldn't. Background thread.
     */
    fun deleteParts(context: Context, parts: List<Uri>): Int = parts.count { uri ->
        !runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }.getOrDefault(false)
    }

    private class Part(val uri: Uri, val name: String)

    private fun install(context: Context, uris: List<Uri>): State {
        val parts = uris.map { Part(it, displayName(context, it)) }.sortedWith(compareBy(NaturalOrder) { it.name })
        val kinds = parts.map { kindOf(it.name) }
        if (kinds.any { it == null }) return State.Failed(Reason.NOT_AN_ARCHIVE, parts[kinds.indexOf(null)].name)
        if (kinds.toSet().size > 1) return State.Failed(Reason.MIXED_FILES)
        return if (kinds.first() == Kind.ZIP) installZips(context, parts) else install7z(context, parts)
    }

    private enum class Kind { SEVEN_ZIP, ZIP }

    private fun kindOf(name: String): Kind? {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".7z") || Regex("""\.7z\.\d{3}$""").containsMatchIn(lower) -> Kind.SEVEN_ZIP
            lower.endsWith(".zip") -> Kind.ZIP
            else -> null
        }
    }

    private fun install7z(context: Context, parts: List<Part>): State {
        // The parts of a split archive are numbered from .001; a gap means one wasn't picked.
        val numbers = parts.map { Regex("""\.7z\.(\d{3})$""").find(it.name.lowercase())?.groupValues?.get(1)?.toInt() }
        if (parts.size > 1 || numbers.first() != null) {
            if (numbers.any { it == null }) return State.Failed(Reason.MIXED_FILES)
            val expected = (1..numbers.size).toList()
            if (numbers != expected) {
                val missing = expected.firstOrNull { it !in numbers } ?: numbers.size + 1
                return State.Failed(Reason.PART_MISSING, "%03d".format(missing))
            }
        }

        val descriptors = ArrayList<ParcelFileDescriptor>()
        try {
            for (part in parts) {
                val pfd = context.contentResolver.openFileDescriptor(part.uri, "r")
                    ?: return State.Failed(Reason.CANT_READ, part.name)
                descriptors.add(pfd)
                if (pfd.statSize <= 0) return State.Failed(Reason.NOT_SEEKABLE, part.name)
            }
            val fds = descriptors.map { it.fd }.toIntArray()
            val sizes = descriptors.map { it.statSize }.toLongArray()

            val summary = SevenZip.inspect(fds, sizes)
            if (!summary.ok) return failure(summary.result)
            if (summary.files == 0L) return State.Failed(Reason.NO_IMAGES)
            checkSpace(context, summary.bytes)?.let { return it }

            val staging = prepareStaging(context)
            mutableState.value = State.Unpacking(0, summary.bytes)
            val result = SevenZip.extract(fds, sizes, staging.path) { done, total ->
                mutableState.value = State.Unpacking(done, total)
                cancelRequested
            }
            if (!result.ok) {
                staging.deleteRecursively()
                return failure(result.result)
            }
            return finish(context, staging, Pack(result.files, result.bytes), parts, sizes.sum())
        } finally {
            descriptors.forEach { runCatching { it.close() } }
        }
    }

    /** One or more .zip files (not split ones), each unpacked in turn. */
    private fun installZips(context: Context, parts: List<Part>): State {
        val total = parts.sumOf { size(context, it.uri) }
        // A zip's images unpack to about its size (images hardly compress).
        checkSpace(context, total)?.let { return it }
        val staging = prepareStaging(context)
        var done = 0L
        var files = 0L
        var bytes = 0L
        val buffer = ByteArray(1 shl 20)
        mutableState.value = State.Unpacking(0, total)
        try {
            for (part in parts) {
                val input = context.contentResolver.openInputStream(part.uri) ?: return State.Failed(Reason.CANT_READ, part.name)
                val counting = CountingInputStream(input.buffered(1 shl 20))
                ZipInputStream(counting).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val name = entry.name.substringAfterLast('/').substringAfterLast('\\')
                        if (entry.isDirectory || name.startsWith('.') || name.substringAfterLast('.', "").lowercase() !in IMAGE_EXTENSIONS) continue
                        File(staging, name).outputStream().use { out ->
                            while (true) {
                                val read = zip.read(buffer)
                                if (read < 0) break
                                out.write(buffer, 0, read)
                                bytes += read
                                mutableState.value = State.Unpacking(done + counting.count, total)
                                if (cancelRequested) throw CancelledException()
                            }
                        }
                        files++
                    }
                }
                done += counting.count
            }
        } catch (e: CancelledException) {
            staging.deleteRecursively()
            return State.Failed(Reason.CANCELLED)
        } catch (e: IOException) {
            staging.deleteRecursively()
            return State.Failed(Reason.DAMAGED, e.message ?: "")
        }
        if (files == 0L) {
            staging.deleteRecursively()
            return State.Failed(Reason.NO_IMAGES)
        }
        return finish(context, staging, Pack(files, bytes), parts, total)
    }

    private class CancelledException : IOException()

    private class CountingInputStream(private val source: java.io.InputStream) : java.io.FilterInputStream(source) {
        var count = 0L
            private set

        override fun read(): Int = super.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }
        override fun skip(n: Long): Long = super.skip(n).also { count += it }
    }

    /** Room for [bytes] of images, counting the space the pack they replace frees. */
    private fun checkSpace(context: Context, bytes: Long): State? {
        val free = StatFs(context.filesDir.path).availableBytes + (installed(context)?.bytes ?: 0L)
        return if (free < bytes + SPARE_BYTES) State.Failed(Reason.NO_SPACE, "${bytes + SPARE_BYTES}/$free") else null
    }

    /** An empty folder to unpack into, after taking away the pack being replaced. */
    private fun prepareStaging(context: Context): File {
        delete(context)
        return File(hdDir(context), "textures.importing").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    private fun finish(context: Context, staging: File, pack: Pack, parts: List<Part>, partsBytes: Long): State {
        val target = packDir(context)
        target.deleteRecursively()
        if (!staging.renameTo(target)) {
            staging.deleteRecursively()
            return State.Failed(Reason.WRITE_FAILED)
        }
        val properties = Properties().apply {
            setProperty("files", pack.files.toString())
            setProperty("bytes", pack.bytes.toString())
        }
        infoFile(context).outputStream().use { properties.store(it, "HD texture pack") }
        return State.Done(pack, parts.map { it.uri }, partsBytes)
    }

    private fun failure(result: Int): State.Failed = State.Failed(
        when (result) {
            SevenZip.NOT_7Z -> Reason.NOT_AN_ARCHIVE
            SevenZip.MISSING_PARTS -> Reason.PART_MISSING
            SevenZip.UNSUPPORTED -> Reason.UNSUPPORTED
            SevenZip.READ_ERROR -> Reason.CANT_READ
            SevenZip.WRITE_ERROR -> Reason.WRITE_FAILED
            SevenZip.OUT_OF_MEMORY -> Reason.OUT_OF_MEMORY
            SevenZip.CANCELLED -> Reason.CANCELLED
            else -> Reason.DAMAGED
        }
    )

    private fun displayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getString(0)
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: ""
    }

    private fun size(context: Context, uri: Uri): Long {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getLong(0)
        }
        return 0L
    }

    /** "part2" before "part10". */
    private object NaturalOrder : Comparator<String> {
        override fun compare(a: String, b: String): Int {
            val pa = Regex("""\d+|\D+""").findAll(a.lowercase()).map { it.value }.toList()
            val pb = Regex("""\d+|\D+""").findAll(b.lowercase()).map { it.value }.toList()
            for (i in 0 until minOf(pa.size, pb.size)) {
                val x = pa[i]
                val y = pb[i]
                val c = if (x[0].isDigit() && y[0].isDigit()) {
                    x.toBigInteger().compareTo(y.toBigInteger()).takeIf { it != 0 } ?: x.length.compareTo(y.length)
                } else {
                    x.compareTo(y)
                }
                if (c != 0) return c
            }
            return pa.size.compareTo(pb.size)
        }
    }
}
