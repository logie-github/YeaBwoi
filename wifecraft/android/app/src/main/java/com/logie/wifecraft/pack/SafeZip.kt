package com.logie.wifecraft.pack

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** The most a pack may unpack to: the entry count, one file, all files, and how far a file may swell over its packed size. */
data class ZipLimits(
    val maxEntries: Int = PackManifest.MAX_ENTRIES + 8,
    val maxEntryBytes: Long = PackManifest.MAX_ENTRY_BYTES,
    val maxTotalBytes: Long = PackManifest.MAX_TOTAL_BYTES,
    /** A stored size over this many times the packed size is a zip bomb ([ZipInputStream] knows the packed size only for some entries). */
    val maxRatio: Long = 400,
)

/** What [SafeZip.extract] did: the files written (zip name to file, with digest and size), and what it left alone and why. */
class ZipReport(val files: Map<String, Extracted>, val ignored: List<Pair<String, String>>)

class Extracted(val file: File, val sha256: String, val size: Long)

/**
 * Unpacks a pack's zip, streaming, into a folder, and nothing else. Never executes or interprets a file. Refused (the whole
 * pack: [PackException]): a name that would leave the folder (`..`, an absolute path, a drive letter, a backslash, a null:
 * zip-slip), a name that is a directory entry's own parent trick, a duplicate name, too many files, a file or a total over the
 * limits (counted as it is written, so a lying header cannot get past), a file that swells absurdly over its packed size.
 * Left alone and listed: directories, anything outside `pack/`, and any `.ts4script` (a script for the game: ignored whatever
 * the manifest says), `.py` and `.pyc`.
 */
object SafeZip {
    /** The first thing wrong with a zip entry's [name] as a path to write, or null when it is a plain relative path inside `pack/`. */
    fun unsafe(name: String): String? = when {
        name.isEmpty() -> "an empty name"
        name.contains('\u0000') -> "a null in the name"
        name.contains('\\') -> "a backslash in the name"
        name.startsWith("/") -> "an absolute path"
        Regex("^[A-Za-z]:").containsMatchIn(name) -> "a drive letter"
        name.split('/').any { it == ".." } -> "a .. part"
        name.length > 240 -> "a name over 240 characters"
        else -> null
    }

    fun isScript(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".ts4script") || n.endsWith(".py") || n.endsWith(".pyc") || n.endsWith(".pyo")
    }

    fun extract(input: InputStream, dest: File, limits: ZipLimits = ZipLimits(), only: ((String) -> Boolean)? = null): ZipReport {
        dest.mkdirs()
        val root = dest.canonicalFile
        val files = LinkedHashMap<String, Extracted>()
        val ignored = ArrayList<Pair<String, String>>()
        val seen = HashSet<String>()
        var total = 0L
        var count = 0
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                unsafe(name)?.let { throw PackException("The pack is refused: an entry of the zip has $it (\"${name.take(80)}\"). Nothing was unpacked from it.") }
                if (entry.isDirectory) continue
                if (!name.startsWith("pack/")) { ignored += name to "outside the pack folder"; continue }
                if (isScript(name)) { ignored += name to "a script: the app never runs or reads scripts"; continue }
                if (!seen.add(name.lowercase())) throw PackException("The pack is refused: the zip has \"${name.take(80)}\" twice.")
                if (only != null && !only(name)) { ignored += name to "not listed in the manifest"; continue }
                if (++count > limits.maxEntries) throw PackException("The pack is refused: the zip holds more than ${limits.maxEntries} files.")
                val target = File(root, name).canonicalFile
                if (!target.path.startsWith(root.path + File.separator)) throw PackException("The pack is refused: \"${name.take(80)}\" would be written outside its folder.")
                target.parentFile.mkdirs()
                val md = MessageDigest.getInstance("SHA-256")
                var written = 0L
                try {
                    target.outputStream().use { out ->
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            val n = zip.read(buf)
                            if (n < 0) break
                            written += n
                            total += n
                            if (written > limits.maxEntryBytes) throw PackException("The pack is refused: \"${name.take(80)}\" is larger than the ${limits.maxEntryBytes / 1048576} MB a file may be.")
                            if (total > limits.maxTotalBytes) throw PackException("The pack is refused: it unpacks to more than ${limits.maxTotalBytes / 1048576} MB.")
                            md.update(buf, 0, n)
                            out.write(buf, 0, n)
                        }
                    }
                } catch (e: IOException) {
                    target.delete()
                    throw PackException("The pack's zip is damaged at \"${name.take(80)}\" (${e.message?.take(80)}): copy the file again.")
                }
                val packed = entry.compressedSize
                if (packed > 0 && written > packed * limits.maxRatio && written > (1L shl 20)) {
                    target.delete()
                    throw PackException("The pack is refused: \"${name.take(80)}\" swells to $written bytes from $packed (a zip bomb).")
                }
                files[name] = Extracted(target, md.digest().joinToString("") { "%02x".format(it) }, written)
            }
        }
        return ZipReport(files, ignored)
    }
}
