package com.logie.wifecraft.audio

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipInputStream

/** Why an import of sounds was refused; the message is for the player. */
class PartnerSoundException(message: String) : IOException(message)

/** What the folder holds: [count] files, [bytes], the [formats] present (lower-case extensions). */
data class PartnerSoundStatus(val count: Int, val bytes: Long, val formats: List<String>) {
    /** "N files, X MB" (a size under a tenth of a megabyte in KB). */
    fun summary(): String {
        val size = if (bytes >= 100_000L) String.format(Locale.ROOT, "%.1f MB", bytes / 1_048_576.0) else String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0)
        return "$count file${if (count == 1) "" else "s"}, $size"
    }
}

/**
 * The partner animations' sounds: a folder the user fills with audio files (ogg, wav, mp3, m4a, aac, flac), as single files or
 * in a zip, named after the sound names the animation mods' clips carry. The game's own EA audio (SNR/SNS) is NOT decoded;
 * these are the user's own files in formats the phone plays. [resolve] finds the files for a sound name: the exact name, the name
 * without its usual prefix, a numbered set (`name_01`, `name_02`: all returned, one to be chosen), a file named by the sound's
 * FNV hash (32 or 64 bit, of the lower-case name, hex, "0x" optional), then a file called `default`. A name with no file is not
 * an error: [resolve] is empty. Pure JVM; the zip import refuses paths leaving the zip and caps sizes and counts like
 * creator/ImportStore does; a zip that fails leaves the folder as it was. NOT run on a phone.
 */
class PartnerSounds(
    private val dir: File,
    private val maxEntry: Long = 64L shl 20,
    private val maxTotal: Long = 512L shl 20,
    private val maxEntries: Int = 20_000,
    private val maxFiles: Int = 5_000,
) {
    private var index: Map<String, List<File>>? = null

    /** What a zip or a file added: [added] kept, [skipped] left (not audio). */
    data class Added(val added: Int, val skipped: Int)

    @Synchronized
    private fun built(): Map<String, List<File>> {
        index?.let { return it }
        val map = LinkedHashMap<String, MutableList<File>>()
        for (f in listAudio()) map.getOrPut(SoundNames.normalise(f.name)) { ArrayList() }.add(f)
        index = map
        return map
    }

    private fun listAudio(): List<File> =
        (dir.listFiles() ?: emptyArray()).filter { it.isFile && !it.name.startsWith(".") && SoundNames.format(it.name) != null }.sortedBy { it.name }

    /** Reads the folder now (a play's first lookup otherwise does): call off the main thread to warm it. */
    fun warm() { built() }

    @Synchronized
    private fun changed() { index = null }

    /** The files for [eventName], best match first group only (see the class); empty when there is none. */
    fun resolve(eventName: String): List<File> {
        val idx = built()
        if (idx.isEmpty()) return emptyList()
        val names = SoundNames.candidates(eventName)
        if (names.isEmpty()) return emptyList()
        for (n in names) idx[n]?.let { return it }
        for (n in names) {
            val set = idx.entries.filter { (k, _) -> SoundNames.variantOf(k)?.first == n }.flatMap { it.value }
            if (set.isNotEmpty()) return set
        }
        val (h32, h64) = SoundNames.hashOf(eventName)
        for ((k, v) in idx) {
            val h = SoundNames.hashFileName(k) ?: continue
            if ((h.second == 32 && h.first == h32.toULong()) || (h.second == 64 && h.first == h64)) return v
        }
        idx[SoundNames.DEFAULT]?.let { return it }
        return idx.entries.filter { (k, _) -> SoundNames.variantOf(k)?.first == SoundNames.DEFAULT }.flatMap { it.value }
    }

    @Synchronized
    fun status(): PartnerSoundStatus {
        val files = listAudio()
        return PartnerSoundStatus(files.size, files.sumOf { it.length() }, files.mapNotNull { SoundNames.format(it.name) }.distinct().sorted())
    }

    /** Removes every file. */
    @Synchronized
    fun clear() {
        dir.listFiles()?.forEach { if (it.isFile) it.delete() }
        changed()
    }

    /** Takes in what the user picked: a zip (by its name) or one audio file. */
    @Synchronized
    fun import(displayName: String, input: InputStream): Added =
        if (displayName.lowercase(Locale.ROOT).endsWith(".zip")) addZip(input) else addFile(displayName, input)

    /** One audio file, kept under its normalised name. A name that isn't audio is refused. */
    @Synchronized
    fun addFile(displayName: String, input: InputStream): Added {
        val keep = keptName(displayName) ?: throw PartnerSoundException("\"$displayName\" isn't a sound file (ogg, wav, mp3, m4a, aac or flac).")
        dir.mkdirs()
        if (listAudio().size >= maxFiles && !File(dir, keep).exists()) throw PartnerSoundException("There are already $maxFiles sound files.")
        val tmp = File(dir, ".tmp-" + keep)
        try {
            copyCapped(input, tmp, maxEntry, 0L, maxTotal)
            place(tmp, File(dir, keep))
        } finally { tmp.delete() }
        changed()
        return Added(1, 0)
    }

    /**
     * A zip's audio files (folders flattened; a path leaving the zip, an absolute one or one with a drive is refused; an entry or
     * the whole over the caps is refused). All or nothing.
     */
    @Synchronized
    fun addZip(input: InputStream): Added {
        dir.mkdirs()
        val staged = ArrayList<Pair<File, String>>()
        var skipped = 0
        try {
            var total = 0L
            var entries = 0
            ZipInputStream(input).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    if (++entries > maxEntries) throw PartnerSoundException("This zip has too many files.")
                    val raw = e.name.replace('\\', '/')
                    if (raw.startsWith("/") || raw.contains(':') || raw.split('/').any { it == ".." }) throw PartnerSoundException("Bad path in the zip: ${e.name}")
                    if (e.isDirectory) continue
                    val keep = keptName(raw.substringAfterLast('/'))
                    if (keep == null) { skipped++; continue }
                    val tmp = File(dir, ".tmp-${staged.size}-$keep")
                    staged.add(tmp to keep)
                    total = copyCapped(z, tmp, maxEntry, total, maxTotal)
                }
            }
            if (staged.isEmpty()) throw PartnerSoundException("This zip has no sound files (ogg, wav, mp3, m4a, aac or flac) in it.")
            val names = staged.map { it.second }.toSet()
            val existing = listAudio().map { it.name }.toSet()
            if ((existing + names).size > maxFiles) throw PartnerSoundException("That would make more than $maxFiles sound files.")
            // a later entry of the same name wins, as in a zip extracted over itself
            for ((tmp, keep) in staged) place(tmp, File(dir, keep))
        } finally {
            staged.forEach { it.first.delete() }
            changed()
        }
        return Added(staged.map { it.second }.toSet().size, skipped)
    }

    /** The name a file is kept under: normalised base plus its lower-case extension; null when it isn't an audio file. */
    private fun keptName(fileName: String): String? {
        val base = fileName.replace('\\', '/').substringAfterLast('/')
        val ext = SoundNames.format(base) ?: return null
        val n = SoundNames.normalise(base)
        return if (n.isEmpty()) null else "$n.$ext"
    }

    private fun place(tmp: File, target: File) {
        if (!tmp.renameTo(target)) { target.delete(); if (!tmp.renameTo(target)) throw PartnerSoundException("${target.name} couldn't be written.") }
    }

    /** Copies [src] to [to] stopping at the caps; returns the running total. */
    private fun copyCapped(src: InputStream, to: File, maxOne: Long, total0: Long, maxAll: Long): Long {
        var total = total0
        var size = 0L
        to.outputStream().use { out ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = src.read(buf)
                if (n < 0) break
                size += n; total += n
                if (size > maxOne || total > maxAll) throw PartnerSoundException("This sound file or zip is too large.")
                out.write(buf, 0, n)
            }
        }
        return total
    }
}

/** What plays a partner sound: [SceneAudio] does. Kept apart so the sink below is plain JVM. */
interface PartnerSoundPlayer {
    /** Plays the sound for [name] if a file is there; false (and nothing else) when none is, or the sounds are off. */
    fun partnerSound(name: String, actorKey: String?, loop: Boolean, volume: Float): Boolean
    fun stopAll()
}

/**
 * What the simulation's partner-sound sink is wired to: the same two methods as simulation's `PartnerSoundSink`
 * (`play(name, actorKey, loop)` and `stopAll()`), which MainActivity adapts this to. [audio] is the scene's [SceneAudio].
 */
class SceneAudioPartnerSink(private val audio: PartnerSoundPlayer) {
    fun play(name: String, actorKey: String?, loop: Boolean = false) { audio.partnerSound(name, actorKey, loop, 1f) }
    fun stopAll() { audio.stopAll() }
}
