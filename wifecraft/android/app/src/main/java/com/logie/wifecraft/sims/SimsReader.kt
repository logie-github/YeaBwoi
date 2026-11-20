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
        visitClips(dbpf.entries, dbpf::read, { clips += it }, { key, why -> failures += key to why })
        return Scan(clips, failures)
    }

    /** Reads one resource at a time from disk, including the failure of any individual clip. */
    fun visitClips(pkg: DbpfFile, onClip: (SimsClip) -> Unit, onFailure: (String, String) -> Unit): Int =
        visitClips(pkg.entries, pkg::read, onClip, onFailure)

    private fun visitClips(
        entries: List<Dbpf.Entry>,
        read: (Dbpf.Entry) -> ByteArray,
        onClip: (SimsClip) -> Unit,
        onFailure: (String, String) -> Unit,
    ): Int {
        var found = 0
        for (entry in entries) {
            if (entry.type != ClipDecoder.TYPE) continue
            found++
            val clip = try {
                ClipDecoder.decode(read(entry), entry.key)
            } catch (e: SimsFormatException) {
                onFailure(entry.key, e.message ?: "Unreadable clip")
                continue
            } catch (e: RuntimeException) {
                onFailure(entry.key, "Unreadable clip data (${e.javaClass.simpleName})")
                continue
            }
            onClip(clip)
        }
        return found
    }

    /** An animation archive can contain rigs for props and animals alongside its human rigs. */
    fun humanoidRigs(pkg: DbpfFile): List<SimsRig> = humanoidRigResources(pkg).map { it.first }

    /** As [humanoidRigs], each with the RIG resource's own bytes (what a [SimsRig] is parsed from, to be kept). */
    fun humanoidRigResources(pkg: DbpfFile): List<Pair<SimsRig, ByteArray>> = pkg.entries.filter { it.type == SimsRig.TYPE }.mapNotNull { entry ->
        val bytes = runCatching { pkg.read(entry) }.getOrNull() ?: return@mapNotNull null
        val rig = runCatching { SimsRig.parse(bytes) }.getOrNull()
        rig?.takeIf { it.coverage(humanoidBones) == humanoidBones.size }?.let { it to bytes }
    }

    private val humanoidBones = listOf("b__ROOT_bind__", "b__Pelvis__", "b__Head__", "b__L_Thigh__", "b__R_Thigh__")
        .map(SimsHash::fnv32)

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
