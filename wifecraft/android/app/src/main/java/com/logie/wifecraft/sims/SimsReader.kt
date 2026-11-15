package com.logie.wifecraft.sims

import java.io.File

/**
 * Reads Sims 4 packages: the animation clips a mod or pose pack holds, and the rigs in the game's own
 * packages (picked once, so clip bones can be named and placed). Pure Kotlin; nothing here touches Android.
 */
object SimsReader {
    /** One package's clips, the ones that decoded and the ones that did not (resource key and why). */
    class Scan(val clips: List<SimsClip>, val failures: List<Pair<String, String>>)

    /** Every clip in [pkg]. Throws [SimsFormatException] when the package or any clip in it cannot be read, or it holds no clip. */
    fun clips(pkg: ByteArray): List<SimsClip> {
        val s = scan(pkg)
        s.failures.firstOrNull()?.let { (key, why) -> throw SimsFormatException("Clip $key in this package cannot be read: $why") }
        if (s.clips.isEmpty()) throw SimsFormatException(noClips(Dbpf(pkg).entries))
        return s.clips
    }

    /** Like [clips], but one bad clip does not stop the others: it is listed in [Scan.failures]. Throws only when the package itself is unreadable. */
    fun scan(pkg: ByteArray): Scan {
        val dbpf = open(pkg)
        val clips = ArrayList<SimsClip>()
        val failures = ArrayList<Pair<String, String>>()
        for (e in dbpf.entries) {
            if (e.type != ClipDecoder.TYPE) continue
            try {
                clips += ClipDecoder.decode(dbpf.read(e), e.key)
            } catch (x: SimsFormatException) {
                failures += e.key to (x.message ?: "unreadable")
            } catch (x: RuntimeException) {
                failures += e.key to "unexpected data (${x.javaClass.simpleName}): the clip is damaged or in a layout this reader does not know"
            }
        }
        return Scan(clips, failures)
    }

    /** Every rig in [pkg]. Throws [SimsFormatException] when the package cannot be read or holds no readable rig. */
    fun rigs(pkg: ByteArray): List<SimsRig> {
        val dbpf = open(pkg)
        return collectRigs(dbpf.entries) { dbpf.read(it) }
    }

    /** Every rig in a package on disk, reading only the index and the rigs: for the game's own packages, too large to hold in memory. */
    fun rigs(file: File): List<SimsRig> = try {
        DbpfFile(file).use { f -> collectRigs(f.entries) { f.read(it) } }
    } catch (x: java.io.IOException) {
        throw SimsFormatException("The package ${file.name} cannot be read (${x.message}).")
    }

    private fun collectRigs(entries: List<Dbpf.Entry>, read: (Dbpf.Entry) -> ByteArray): List<SimsRig> {
        val rigEntries = entries.filter { it.type == SimsRig.TYPE }
        if (rigEntries.isEmpty()) {
            throw SimsFormatException("This package holds no Sims 4 rig (RIG 0x8EAF13DE). Pick one of the game's own packages from its Data/Client or Data/Simulation folder, such as ClientFullBuild0.package.")
        }
        val out = ArrayList<SimsRig>()
        var last: String? = null
        for (e in rigEntries) {
            try { out += SimsRig.parse(read(e)) } catch (x: SimsFormatException) { last = "${e.key}: ${x.message}" } catch (x: RuntimeException) { last = "${e.key}: unexpected data (${x.javaClass.simpleName})" }
        }
        if (out.isEmpty()) throw SimsFormatException("None of this package's ${rigEntries.size} rig(s) could be read (last: $last).")
        return out
    }

    private fun open(pkg: ByteArray): Dbpf {
        if (!Dbpf.isDbpf(pkg)) {
            val zip = pkg.size >= 4 && pkg[0] == 'P'.code.toByte() && pkg[1] == 'K'.code.toByte()
            throw SimsFormatException(
                if (zip) "This is a ZIP archive, not a Sims 4 package: import the archive itself so the packages inside are read."
                else "Not a Sims 4 package (no DBPF header). Pick a .package file from The Sims 4 or a mod."
            )
        }
        return Dbpf(pkg)
    }

    private fun noClips(entries: List<Dbpf.Entry>): String {
        val types = entries.groupingBy { "%08X".format(it.type) }.eachCount().entries.joinToString { "${it.value} x ${it.key}" }
        return "This package holds no Sims 4 animation clips (CLIP 0x6B20C4F3)" +
            (if (types.isEmpty()) "; it is empty." else "; it holds $types. It may be CAS, build or tuning content, not animations.")
    }
}
