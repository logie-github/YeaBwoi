package com.logie.wifecraft.anim

import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files

/** An archive that cannot be read or breaks a safety limit; [message] is for the player. */
class ArchiveException(message: String) : Exception(message)

/** Safety limits for unpacking. The defaults suit a phone. */
class ArchiveLimits(
    val maxEntries: Int = 5000,
    val maxTotalBytes: Long = 1L shl 30,
    val maxEntryBytes: Long = 512L shl 20,
    /** An entry that inflates to more than this many times its packed size is a bomb (checked once [ratioMinBytes] are written). */
    val maxRatio: Long = 100,
    val ratioMinBytes: Long = 32L shl 20,
    val maxDepth: Int = 1,
)

/** What one archive held that matters: the unpacked files (a path inside the archive and the temp file) and what was refused. */
class ExtractResult(val files: List<Pair<String, File>>, val problems: List<String>)

/** Safe reading of ZIP and 7z archives into a private temp folder: no entry can land outside it (zip-slip, absolute
 *  paths, "..", drive letters, symlinks refused), and entry count, total size and the packed-to-unpacked ratio are
 *  limited. Only .package files and nested archives (one level) are unpacked; everything else is ignored. */
object ArchiveImport {
    enum class Kind { ZIP, SEVENZ, PACKAGE, OTHER }

    fun sniff(file: File): Kind {
        val h = ByteArray(8)
        val n = file.inputStream().use { it.read(h) }
        fun starts(vararg b: Int) = n >= b.size && b.indices.all { (h[it].toInt() and 0xFF) == b[it] }
        return when {
            starts(0x50, 0x4B, 0x03, 0x04) || starts(0x50, 0x4B, 0x05, 0x06) -> Kind.ZIP
            starts(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C) -> Kind.SEVENZ
            starts('D'.code, 'B'.code, 'P'.code, 'F'.code) -> Kind.PACKAGE
            else -> Kind.OTHER
        }
    }

    /** Copies a stream to [dest], refusing more than [maxBytes]. */
    fun spool(input: InputStream, dest: File, maxBytes: Long): File {
        dest.parentFile?.mkdirs()
        dest.outputStream().use { out ->
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > maxBytes) throw ArchiveException("The file is larger than ${maxBytes shr 20} MB, too big to import")
                out.write(buf, 0, n)
            }
        }
        return dest
    }

    /** The safe relative form of an entry name ("a/b/c.package"), or null when it is unsafe. */
    fun safeName(raw: String): String? {
        if (raw.isEmpty() || raw.indexOf('\u0000') >= 0) return null
        val n = raw.replace('\\', '/')
        if (n.startsWith("/") || Regex("^[A-Za-z]:").containsMatchIn(n)) return null
        val parts = n.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." }) return null
        return parts.joinToString("/")
    }

    private class Budget(val limits: ArchiveLimits) { var entries = 0; var bytes = 0L; var counter = 0 }

    /** Unpacks [archive] (of [kind] ZIP or SEVENZ) into [destDir]. Throws [ArchiveException] when it is not a readable archive
     *  or a limit is broken; an unsafe or unwanted single entry is only reported in [ExtractResult.problems]. */
    fun extract(archive: File, kind: Kind, destDir: File, limits: ArchiveLimits = ArchiveLimits()): ExtractResult {
        val files = ArrayList<Pair<String, File>>()
        val problems = ArrayList<String>()
        extractInto(archive, kind, destDir, "", 0, Budget(limits), files, problems)
        return ExtractResult(files, problems)
    }

    private fun wanted(name: String) = name.lowercase().let { it.endsWith(".package") || it.endsWith(".zip") || it.endsWith(".7z") }

    private fun extractInto(archive: File, kind: Kind, destDir: File, prefix: String, depth: Int, budget: Budget,
                            files: MutableList<Pair<String, File>>, problems: MutableList<String>) {
        destDir.mkdirs()
        val root = destDir.canonicalFile
        val nested = ArrayList<Pair<String, File>>()
        // Each wanted entry is written to a fresh numbered file, keeping its name, so two entries can never collide or escape.
        fun sink(name: String): File {
            val safe = name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._ ()+-]"), "_").ifEmpty { "file" }
            val out = File(File(root, (budget.counter++).toString()), safe)
            out.parentFile.mkdirs()
            check(out.canonicalFile.toPath().startsWith(root.toPath())) { "escape" }
            return out
        }
        fun admit(rawName: String, symlink: Boolean, dir: Boolean): String? {
            if (dir) return null
            if (++budget.entries > budget.limits.maxEntries) throw ArchiveException("The archive holds more than ${budget.limits.maxEntries} entries, refused")
            val safe = safeName(rawName)
            if (safe == null) { problems.add("Refused unsafe path \"$rawName\""); return null }
            if (symlink) { problems.add("Refused link \"$rawName\""); return null }
            if (!wanted(safe)) return null
            return safe
        }
        fun record(safe: String, out: File) {
            val shown = prefix + safe
            if (safe.lowercase().endsWith(".package")) files.add(shown to out)
            else if (depth >= budget.limits.maxDepth) { problems.add("Skipped \"$shown\": archives inside archives are only opened one level deep"); out.delete() }
            else nested.add(shown to out)
        }
        fun copy(src: InputStream, out: File, packed: Long) {
            out.outputStream().use { o -> copyLimited(src, o, packed, budget) }
        }
        try {
            when (kind) {
                Kind.ZIP -> ZipFile.builder().setFile(archive).get().use { zip ->
                    val en = zip.entries
                    while (en.hasMoreElements()) {
                        val e: ZipArchiveEntry = en.nextElement()
                        val safe = admit(e.name, e.isUnixSymlink, e.isDirectory) ?: continue
                        val out = sink(safe)
                        zip.getInputStream(e).use { copy(it, out, e.compressedSize) }
                        record(safe, out)
                    }
                }
                Kind.SEVENZ -> SevenZFile.builder().setFile(archive).get().use { sz ->
                    val ratioBase = archive.length()
                    while (true) {
                        val e = sz.nextEntry ?: break
                        val attrs = if (e.hasWindowsAttributes) e.windowsAttributes else 0
                        val symlink = (attrs and 0x400) != 0 || ((attrs and 0x8000) != 0 && ((attrs ushr 16) and 0xF000) == 0xA000)
                        val safe = admit(e.name ?: "", symlink || e.isAntiItem, e.isDirectory) ?: continue
                        val out = sink(safe)
                        // 7z packs solidly, so there is no packed size per entry: the archive's own size is the base for the ratio.
                        sz.getInputStream(e).use { copy(it, out, ratioBase) }
                        record(safe, out)
                    }
                }
                else -> throw ArchiveException("Not an archive")
            }
        } catch (e: ArchiveException) {
            throw e
        } catch (e: IOException) {
            throw ArchiveException("The archive is damaged or not a ${if (kind == Kind.ZIP) "ZIP" else "7z"} file (${e.message ?: e.javaClass.simpleName})")
        } catch (e: RuntimeException) {
            throw ArchiveException("The archive is damaged or unsupported (${e.message ?: e.javaClass.simpleName})")
        }
        for ((shown, f) in nested) {
            val k = sniff(f)
            if (k != Kind.ZIP && k != Kind.SEVENZ) { problems.add("Skipped \"$shown\": not a readable archive"); continue }
            try {
                extractInto(f, k, File(root, "n" + budget.counter++), "$shown/", depth + 1, budget, files, problems)
            } catch (e: ArchiveException) {
                problems.add("Could not open \"$shown\": ${e.message}")
            } finally { f.delete() }
        }
    }

    private fun copyLimited(src: InputStream, out: OutputStream, packed: Long, b: Budget) {
        val buf = ByteArray(64 * 1024)
        var written = 0L
        while (true) {
            val n = src.read(buf)
            if (n < 0) break
            written += n
            b.bytes += n
            if (written > b.limits.maxEntryBytes || b.bytes > b.limits.maxTotalBytes)
                throw ArchiveException("The archive unpacks to more than ${b.limits.maxTotalBytes shr 20} MB, refused (an archive bomb?)")
            if (written >= b.limits.ratioMinBytes && packed > 0 && written / packed > b.limits.maxRatio)
                throw ArchiveException("The archive unpacks to far more than its size, refused (an archive bomb?)")
            out.write(buf, 0, n)
        }
    }

    /** Deletes [dir] and everything in it (never following a link). */
    fun deleteTree(dir: File) {
        if (!Files.isSymbolicLink(dir.toPath()) && dir.isDirectory) dir.listFiles()?.forEach { deleteTree(it) }
        dir.delete()
    }
}
