package com.logie.wifecraft.anim

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** What [AnimLibrary.addResult] did: stored [anim] (maybe with a new name), or found the same source already
 *  there ([duplicate], [anim] is the existing one and nothing was written). [message] says why, for the player. */
class AddResult(val anim: Anim, val duplicate: Boolean, val message: String, val updated: Boolean = false)

/** What [AnimLibrary.addPairResult] did, as [AddResult] for a partner animation. */
class PairAddResult(val pair: PairAnim, val duplicate: Boolean, val message: String, val updated: Boolean = false)

/**
 * The animation library on disk. Each animation's tracks are a clip file, dir/clips/<id>.wclip ([ClipCodec]: quantized,
 * keyframe-reduced, under 2 % of the old JSON); a partner animation's two are dir/clips/pair_<id>_a.wclip and _b.wclip.
 * Everything else is in one index, dir/index.json, which is all the library reads when it opens:
 *
 * ```
 * {"v": 2, "format": "wifecraft-anim-index",
 *  "anims": [ AnimJson without tracks ("tracks": []) + "store": {"file", "tracks", "bytes", "revision", "inspect", "legacy"?}
 *             + "requires"? (the classifier's result, RequirementJson) ],
 *  "pairs": [ PairJson with its clips' metadata only + "storeA", "storeB" (as "store") + "requires"? ]}
 * ```
 *
 * "file" is relative to the library folder; "revision" is [ClipCodec.REVISION] of the encoder that wrote it; "inspect" is
 * ClipInspection's verdict (so listing and sorting need no tracks); "legacy" marks a clip still in its old JSON file
 * (<id>.json, AnimJson; pair_<id>.json, PairJson), which keeps loading and is converted the first time its tracks are read
 * or by [compact]. Names, categories and switches are edited in the index alone; tracks are read only when played or
 * previewed (StoredTracks, [ClipCache]). The index is rebuilt from the clip files' own headers when it is lost (a clip
 * written and the index not, after a crash, is found the same way). Every write is a temp file then a rename. Instances on
 * the same folder share their state and a lock. A damaged file is skipped.
 */
class AnimLibrary(private val dir: File, private val freeSpace: (File) -> Long = { it.usableSpace }) {
    class IndexEntry(val id: String, val name: String, val category: String)

    private class State {
        val lock = Any()
        var anims: LinkedHashMap<String, Anim>? = null
        var pairs: LinkedHashMap<String, PairAnim>? = null
        var requirements: RequirementCache? = null
        var damaged: List<String> = emptyList()
        var damagedPairs: List<String> = emptyList()
        /** Lower-case names in use, and source to id: the add path's checks without a pass over thousands of entries. */
        val names = HashSet<String>()
        val bySource = HashMap<String, String>()
        @Volatile var batch = 0
        var dirty = false
    }

    private val state: State = synchronized(states) { states.getOrPut(dir.absoluteFile.normalize().path) { State() } }
    private val clips = File(dir, CLIPS)

    /**
     * What each animation and partner animation needs to be played on (anim/Requirements: a bed, a seat, a top, the floor),
     * kept beside the library in dir/requires.cache and classified when missing or outdated; the live scene reads it through
     * [RequirementStore] (this library's cache from the moment it is opened). The index keeps the importer's own result too.
     */
    val requirements: RequirementCache = synchronized(state.lock) {
        state.requirements ?: RequirementCache(dir).also { state.requirements = it }
    }

    init { RequirementStore.cache = requirements }

    /** Names of animation files that could not be read at the last load. */
    val damaged: List<String> get() = synchronized(state.lock) { state.damaged }

    /** Names of partner-animation files that could not be read at the last load. */
    val damagedPairs: List<String> get() = synchronized(state.lock) { state.damagedPairs }

    // ---------------------------------------------------------------- loading

    private fun anims(): LinkedHashMap<String, Anim> { ensure(); return state.anims!! }
    private fun pairMap(): LinkedHashMap<String, PairAnim> { ensure(); return state.pairs!! }

    private fun ensure() {
        if (state.anims != null && state.pairs != null) return
        dir.mkdirs()
        val anims = LinkedHashMap<String, Anim>()
        val pairs = LinkedHashMap<String, PairAnim>()
        val bad = ArrayList<String>()
        val badPairs = ArrayList<String>()
        val index = readIndex()
        var dirty = index == null
        if (index != null) {
            index.optJSONArray("anims")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val a = runCatching { entryAnim(arr.getJSONObject(i)) }.getOrNull()
                    if (a == null || a.id in anims || !stored(a).file.isFile) { dirty = true; continue }
                    anims[a.id] = a
                }
            }
            index.optJSONArray("pairs")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val p = runCatching { entryPair(arr.getJSONObject(i)) }.getOrNull()
                    if (p == null || p.id in pairs || !stored(p.a).file.isFile || !stored(p.b).file.isFile) { dirty = true; continue }
                    pairs[p.id] = p
                }
            }
        }
        // the old JSON files the index does not know yet: read once for their metadata, listed as legacy until converted
        for (f in (dir.listFiles() ?: emptyArray()).sortedBy { it.name }) {
            if (!f.isFile || !f.name.endsWith(".json") || f.name == INDEX) continue
            if (f.name.startsWith(PAIR_PREFIX)) {
                if (f.name.removePrefix(PAIR_PREFIX).removeSuffix(".json") in pairs) continue
                try {
                    val p = PairJson.parse(f.readText())
                    if (pairFile(p.id) == f.name) { pairs[p.id] = legacyPair(p, f); dirty = true } else badPairs.add(f.name)
                } catch (e: Exception) { badPairs.add(f.name) }
            } else {
                if (f.name.removeSuffix(".json") in anims) continue
                try {
                    val a = AnimJson.parse(f.readText())
                    if (a.id + ".json" == f.name) { anims[a.id] = legacyAnim(a, f); dirty = true } else bad.add(f.name)
                } catch (e: Exception) { bad.add(f.name) }
            }
        }
        // clip files the index does not name (it was lost, or a crash came between a clip and the index): their headers
        val known = HashSet<String>()
        for (a in anims.values) known += stored(a).file.absolutePath
        for (p in pairs.values) { known += stored(p.a).file.absolutePath; known += stored(p.b).file.absolutePath }
        val parts = HashMap<String, HashMap<String, Pair<JSONObject, StoredTracks>>>()
        for (f in (clips.listFiles() ?: emptyArray()).sortedBy { it.name }) {
            if (!f.isFile || !f.name.endsWith(ClipCodec.EXTENSION) || f.absolutePath in known) continue
            try {
                val h = ClipCodec.readHeader(f)
                val tracks = StoredTracks(f, h.trackCount, ClipStore.inspectionOf(h.meta.optJSONObject("inspect")), h.bytes, h.revision)
                val pair = h.meta.optJSONObject("pair")
                if (pair != null) {
                    parts.getOrPut(pair.getString("id")) { HashMap() }[h.meta.optString("part")] = pair to tracks
                } else {
                    val a = AnimJson.fromJson(h.meta.getJSONObject("anim"))
                    if (a.id + ClipCodec.EXTENSION == f.name && a.id !in anims) { anims[a.id] = a.copy(tracks = tracks); dirty = true }
                    else bad.add(CLIPS + "/" + f.name)
                }
            } catch (e: Exception) { bad.add(CLIPS + "/" + f.name) }
        }
        for ((id, two) in parts) {
            val a = two["a"]; val b = two["b"]
            if (a == null || b == null || id in pairs) { badPairs.add("$CLIPS/$PAIR_PREFIX$id"); continue }
            try {
                val p = PairJson.fromJson(a.first)
                pairs[p.id] = p.copy(a = p.a.copy(tracks = a.second), b = p.b.copy(tracks = b.second)); dirty = true
            } catch (e: Exception) { badPairs.add("$CLIPS/$PAIR_PREFIX$id") }
        }
        state.anims = anims
        state.pairs = pairs
        state.damaged = bad
        state.damagedPairs = badPairs
        state.names.clear(); state.bySource.clear()
        for (a in anims.values) { state.names += a.name.lowercase(); if (a.source.isNotBlank()) state.bySource.putIfAbsent(a.source, a.id) }
        if (dirty) writeIndex()
    }

    private fun readIndex(): JSONObject? = try {
        val f = File(dir, INDEX)
        if (!f.isFile) null else JSONObject(f.readText()).takeIf { it.optInt("v", 0) == INDEX_VERSION }
    } catch (e: Exception) { null }

    private fun stored(a: Anim): StoredTracks = a.tracks as StoredTracks

    private fun storeOf(o: JSONObject, legacyHook: Boolean): StoredTracks {
        val rel = o.getString("file")
        require(!rel.contains("..") && !rel.startsWith("/")) { "A clip path leaves the library" }
        val t = StoredTracks(File(dir, rel), o.optInt("tracks", 0), ClipStore.inspectionOf(o.optJSONObject("inspect")),
            o.optLong("bytes", 0L), o.optInt("revision", 0), o.optString("legacy").ifEmpty { null })
        if (legacyHook && t.legacy != null) t.onLegacyLoad = ::migrateLoaded
        return t
    }

    private fun storeJson(t: StoredTracks): JSONObject = JSONObject()
        .put("file", t.file.relativeTo(dir).path.replace(File.separatorChar, '/')).put("tracks", t.count).put("bytes", t.bytes)
        .put("revision", t.revision).also { o ->
            ClipStore.inspectionJson(t.inspection)?.let { o.put("inspect", it) }
            t.legacy?.let { o.put("legacy", it) }
        }

    private fun entryAnim(o: JSONObject): Anim {
        val a = AnimJson.fromJson(o)
        if (a.source.isNotBlank()) RequirementJson.fromJson(o.optJSONObject(RequirementJson.FIELD))?.let { RequirementStore.seed(RequirementStore.keyOf(a), it) }
        return a.copy(tracks = storeOf(o.getJSONObject("store"), true))
    }

    private fun entryPair(o: JSONObject): PairAnim {
        val p = PairJson.fromJson(o)
        if (p.source.isNotBlank()) RequirementJson.fromJson(o.optJSONObject(RequirementJson.FIELD))?.let { RequirementStore.seed(RequirementStore.keyOf(p), it) }
        return p.copy(a = p.a.copy(tracks = storeOf(o.getJSONObject("storeA"), true)), b = p.b.copy(tracks = storeOf(o.getJSONObject("storeB"), true)))
    }

    private fun entryJson(a: Anim): JSONObject {
        val o = ClipStore.meta(a).put("store", storeJson(stored(a)))
        if (a.source.isNotBlank()) RequirementStore.seeded(a)?.let { o.put(RequirementJson.FIELD, rounded(RequirementJson.toJson(it))) }
        return o
    }

    private fun pairEntryJson(p: PairAnim): JSONObject {
        val o = ClipStore.pairMeta(p).put("storeA", storeJson(stored(p.a))).put("storeB", storeJson(stored(p.b)))
        if (p.source.isNotBlank()) RequirementStore.seeded(p)?.let { o.put(RequirementJson.FIELD, rounded(RequirementJson.toJson(it))) }
        return o
    }

    /** The classifier's numbers to 4 decimals (a tenth of a millimetre, a hundredth of a degree): the index stays small. */
    private fun rounded(v: Any?): Any? = when (v) {
        is JSONObject -> v.also { o -> for (k in o.keys().asSequence().toList()) o.put(k, rounded(o.get(k))) }
        is JSONArray -> v.also { a -> for (i in 0 until a.length()) a.put(i, rounded(a.get(i))) }
        is Double -> if (v.isFinite()) Math.round(v * 10000.0) / 10000.0 else v
        is Float -> if (v.isFinite()) Math.round(v * 10000.0) / 10000.0 else v
        else -> v
    }

    private fun legacyAnim(a: Anim, f: File): Anim {
        val t = StoredTracks(f, a.tracks.size, ClipStore.inspect(a.tracks), f.length(), 0, "anim")
        t.onLegacyLoad = ::migrateLoaded
        return a.copy(tracks = t)
    }

    private fun legacyPair(p: PairAnim, f: File): PairAnim {
        fun part(x: Anim, which: String) = StoredTracks(f, x.tracks.size, ClipStore.inspect(x.tracks), f.length() / 2, 0, which)
            .also { it.onLegacyLoad = ::migrateLoaded }
        return p.copy(a = p.a.copy(tracks = part(p.a, "a")), b = p.b.copy(tracks = part(p.b, "b")))
    }

    /** Writes the index (deferred to the end of a [batch]). */
    private fun writeIndex() {
        if (state.batch > 0) { state.dirty = true; return }
        state.dirty = false
        val anims = JSONArray()
        for (a in sorted(state.anims?.values.orEmpty())) anims.put(entryJson(a))
        val pairs = JSONArray()
        for (p in sortedPairs(state.pairs?.values.orEmpty())) pairs.put(pairEntryJson(p))
        atomic(File(dir, INDEX), JSONObject().put("v", INDEX_VERSION).put("format", "wifecraft-anim-index")
            .put("anims", anims).put("pairs", pairs).toString())
    }

    /**
     * Runs [block] (adds, renames...) with the index written once at its end, not after every change (a pack's thousands of
     * animations saved at once). The other calls stay usable meanwhile; a crash inside leaves clip files the next load finds.
     */
    fun <T> batch(block: () -> T): T {
        synchronized(state.lock) { state.batch++ }
        try {
            return block()
        } finally {
            synchronized(state.lock) {
                state.batch--
                if (state.batch == 0 && state.dirty) writeIndex()
            }
        }
    }

    // ---------------------------------------------------------------- single animations

    private fun sorted(m: Collection<Anim>) =
        m.sortedWith(compareBy<Anim, String>(String.CASE_INSENSITIVE_ORDER) { it.category }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.id })

    /** Every animation, light (its tracks are read when played: StoredTracks), by category then name. */
    fun list(): List<Anim> = synchronized(state.lock) { sorted(anims().values) }

    fun get(id: String): Anim? = synchronized(state.lock) { anims()[id] }

    /** The light listing: id, name, category. */
    fun index(): List<IndexEntry> = synchronized(state.lock) { sorted(anims().values).map { IndexEntry(it.id, it.name, it.category) } }

    /** Adds [a]: the same source (file hash and clip) already stored is skipped; a name already used gets " (2)",
     *  " (3)"...; an id that is unusable or taken is replaced. Returns the stored animation. */
    fun add(a: Anim): Anim = addResult(a).anim

    /**
     * How many stored animations and partner animations an older conversion made ([Retargeter.CONVERSION_REVISION]; files
     * written before the revision was stamped read as 0): importing their pack again replaces them ([addResult],
     * [addPairResult]) with their names, categories and settings kept.
     */
    fun stale(): Int = synchronized(state.lock) {
        anims().values.count(::isStale) + pairMap().values.count { isStale(it.a) || isStale(it.b) }
    }

    fun addResult(a: Anim): AddResult = synchronized(state.lock) {
        val map = anims()
        if (a.source.isNotBlank()) {
            val same = state.bySource[a.source]?.let { map[it] }
            if (same != null && a.conversionRevision > same.conversionRevision) {
                val updated = store(a.copy(id = same.id, name = same.name, category = same.category, importedAt = same.importedAt), same)
                map[same.id] = updated
                writeIndex()
                return AddResult(updated, false, "updated ${same.name}", updated = true)
            }
            if (same != null) return AddResult(same, true, "\"${a.name.trim()}\" is already in the library as \"${same.name}\" (same file and clip), skipped")
        }
        val id = if (ID.matches(a.id) && a.id !in map && !a.id.startsWith(PAIR_PREFIX)) a.id else newId(map)
        val wanted = a.name.trim().ifEmpty { "Animation" }
        val name = uniqueName(state.names, wanted)
        val saved = store(a.copy(id = id, name = name, category = normCategory(map, a.category)), null)
        map[id] = saved
        state.names += name.lowercase()
        if (saved.source.isNotBlank()) state.bySource.putIfAbsent(saved.source, id)
        writeIndex()
        val msg = if (name != wanted) "\"$wanted\" was taken, saved as \"$name\"" else "saved as \"$name\""
        AddResult(saved, false, msg)
    }

    /** [a]'s clip written as dir/clips/<id>.wclip: a stored clip's track block copied as it is (a staged one moved), else encoded. */
    private fun store(a: Anim, replacing: Anim?): Anim {
        val target = File(clips, a.id + ClipCodec.EXTENSION)
        val t = clip(a, target) { insp -> ClipStore.header(a, insp) }
        replacing?.let { dropOld(stored(it), target) }
        return a.copy(tracks = t)
    }

    private fun clip(a: Anim, target: File, header: (ClipInspection?) -> JSONObject): StoredTracks {
        val tracks = a.tracks
        if (tracks is StoredTracks && tracks.legacy == null) {
            val size = ClipCodec.rewrite(tracks.file, target, header(tracks.inspection))
            if (tracks.staged && tracks.file.absoluteFile != target.absoluteFile) tracks.file.delete()
            ClipCache.forget(target)
            return StoredTracks(target, tracks.count, tracks.inspection, size, tracks.revision)
        }
        val plain = if (tracks is StoredTracks) tracks.load() else tracks
        return ClipStore.store(target, ClipStore.prepare(plain) { header(it) })
    }

    /** A replaced clip's old file, when it was not the new one's place (an old JSON file). */
    private fun dropOld(old: StoredTracks, target: File) {
        if (old.file.absoluteFile != target.absoluteFile) { old.file.delete(); ClipCache.forget(old.file) }
    }

    fun rename(id: String, name: String) = synchronized(state.lock) {
        val map = anims()
        val a = map[id] ?: throw NoSuchElementException("No animation $id")
        require(name.isNotBlank()) { "A name cannot be empty" }
        state.names -= a.name.lowercase()
        val updated = a.copy(name = uniqueName(state.names, name.trim()))
        map[id] = updated; state.names += updated.name.lowercase(); writeIndex()
    }

    fun setCategory(id: String, category: String) = synchronized(state.lock) {
        val map = anims()
        val a = map[id] ?: throw NoSuchElementException("No animation $id")
        map[id] = a.copy(category = normCategory(map, category, id)); writeIndex()
    }

    fun delete(id: String) = synchronized(state.lock) {
        val map = anims()
        val a = map.remove(id) ?: return@synchronized
        state.names -= a.name.lowercase()
        if (state.bySource[a.source] == id) state.bySource.remove(a.source)
        val f = stored(a).file
        f.delete(); ClipCache.forget(f)
        writeIndex()
        requirements.forget(id)
    }

    /** Every category in use, alphabetical, no repeats. */
    fun categories(): List<String> = synchronized(state.lock) {
        anims().values.map { it.category }.distinctBy { it.lowercase() }.sortedWith(String.CASE_INSENSITIVE_ORDER)
    }

    /** Animations whose name or category contains [query], ignoring case; a blank query finds all. */
    fun find(query: String): List<Anim> = synchronized(state.lock) {
        val q = query.trim().lowercase()
        sorted(anims().values.filter { q.isEmpty() || it.name.lowercase().contains(q) || it.category.lowercase().contains(q) })
    }

    // ---------------------------------------------------------------- partner animations

    private fun sortedPairs(m: Collection<PairAnim>) =
        m.sortedWith(compareBy<PairAnim, String>(String.CASE_INSENSITIVE_ORDER) { it.category }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.id })

    fun pairs(): List<PairAnim> = synchronized(state.lock) { sortedPairs(pairMap().values) }

    fun getPair(id: String): PairAnim? = synchronized(state.lock) { pairMap()[id] }

    /** Adds [p] with the singles' policy: the same two clips already stored is skipped (the stored one returned);
     *  a name already used by a partner animation gets " (2)", " (3)"...; an unusable or taken id is replaced. */
    fun addPair(p: PairAnim): PairAnim = addPairResult(p).pair

    fun addPairResult(p: PairAnim): PairAddResult = synchronized(state.lock) {
        val map = pairMap()
        if (p.source.isNotBlank()) {
            val same = map.values.firstOrNull { it.source == p.source }
            // the same two clips converted by a newer conversion: the stored one is replaced, its name, category and switch kept
            if (same != null && p.a.conversionRevision > same.a.conversionRevision) {
                val updated = storePair(p.copy(id = same.id, name = same.name, category = same.category, enabled = same.enabled, importedAt = same.importedAt), same)
                map[same.id] = updated
                writeIndex()
                return PairAddResult(updated, false, "updated ${same.name}", updated = true)
            }
            if (same != null) return PairAddResult(same, true, "\"${p.name.trim()}\" is already in the library as \"${same.name}\" (the same two clips), skipped")
        }
        val id = if (ID.matches(p.id) && p.id !in map) p.id else newPairId(map)
        val wanted = p.name.trim().ifEmpty { "Partner animation" }
        val name = uniqueName(map.values.mapTo(HashSet()) { it.name.lowercase() }, wanted)
        val saved = storePair(p.copy(id = id, name = name, category = normPairCategory(map, p.category)), null)
        map[id] = saved
        writeIndex()
        val msg = if (name != wanted) "\"$wanted\" was taken, saved as \"$name\"" else "saved as \"$name\""
        PairAddResult(saved, false, msg)
    }

    private fun storePair(p: PairAnim, replacing: PairAnim?): PairAnim {
        val fa = File(clips, "$PAIR_PREFIX${p.id}_a${ClipCodec.EXTENSION}")
        val fb = File(clips, "$PAIR_PREFIX${p.id}_b${ClipCodec.EXTENSION}")
        val a = clip(p.a, fa) { ClipStore.header(p.a, it, p, "a") }
        val b = clip(p.b, fb) { ClipStore.header(p.b, it, p, "b") }
        replacing?.let { old -> dropOld(stored(old.a), fa); if (stored(old.b).file != stored(old.a).file) dropOld(stored(old.b), fb) }
        return p.copy(a = p.a.copy(tracks = a), b = p.b.copy(tracks = b))
    }

    fun setPairEnabled(id: String, on: Boolean) = updatePair(id) { it.copy(enabled = on) }

    fun renamePair(id: String, name: String) = synchronized(state.lock) {
        require(name.isNotBlank()) { "A name cannot be empty" }
        val map = pairMap()
        val used = map.values.filter { it.id != id }.mapTo(HashSet()) { it.name.lowercase() }
        updatePair(id) { it.copy(name = uniqueName(used, name.trim())) }
    }

    fun setPairCategory(id: String, category: String) = synchronized(state.lock) {
        val map = pairMap()
        updatePair(id) { it.copy(category = normPairCategory(map, category, id)) }
    }

    fun deletePair(id: String) = synchronized(state.lock) {
        val map = pairMap()
        val p = map.remove(id) ?: return@synchronized
        for (f in setOf(stored(p.a).file, stored(p.b).file)) { f.delete(); ClipCache.forget(f) }
        writeIndex()
        requirements.forget("pair:$id")
    }

    /** Every partner-animation category in use, alphabetical, no repeats. */
    fun pairCategories(): List<String> = synchronized(state.lock) {
        pairMap().values.map { it.category }.distinctBy { it.lowercase() }.sortedWith(String.CASE_INSENSITIVE_ORDER)
    }

    private fun updatePair(id: String, change: (PairAnim) -> PairAnim) = synchronized(state.lock) {
        val map = pairMap()
        val p = map[id] ?: throw NoSuchElementException("No partner animation $id")
        map[id] = change(p); writeIndex()
    }

    // ---------------------------------------------------------------- the old format, the import's staging, space

    /** How many animations and partner animations are still in the old JSON files or an older clip encoding ([compact]). */
    fun legacyCount(): Int = synchronized(state.lock) {
        anims().values.count { old(stored(it)) } + pairMap().values.count { old(stored(it.a)) || old(stored(it.b)) }
    }

    /**
     * Compact library: converts every animation still in an old JSON file (or an older clip encoding) to a clip file,
     * one at a time (the library stays usable), [onProgress] (done, total) after each. Returns how many were converted.
     */
    fun compact(onProgress: (Int, Int) -> Unit = { _, _ -> }): Int {
        val todo = synchronized(state.lock) {
            anims().values.filter { old(stored(it)) }.map { it.id } + pairMap().values.filter { old(stored(it.a)) || old(stored(it.b)) }.map { PAIR_PREFIX + it.id }
        }
        var done = 0
        batch {
            for ((i, key) in todo.withIndex()) {
                val ok = synchronized(state.lock) {
                    runCatching { if (key.startsWith(PAIR_PREFIX)) migratePair(key.removePrefix(PAIR_PREFIX)) else migrateAnim(key, null) }.getOrDefault(false)
                }
                if (ok) done++
                onProgress(i + 1, todo.size)
            }
        }
        return done
    }

    private fun old(t: StoredTracks) = t.legacy != null || t.revision < ClipCodec.REVISION

    /** A legacy clip's tracks were just read (StoredTracks.load): it is converted now, its next read is the new file. */
    private fun migrateLoaded(t: StoredTracks, tracks: List<BoneTrack>) {
        synchronized(state.lock) {
            val anim = state.anims?.values?.firstOrNull { it.tracks === t }
            if (anim != null) migrateAnim(anim.id, tracks)
            else state.pairs?.values?.firstOrNull { it.a.tracks === t || it.b.tracks === t }?.let { migratePair(it.id) }
        }
    }

    private fun migrateAnim(id: String, given: List<BoneTrack>?): Boolean {
        val a = anims()[id] ?: return false
        val t = stored(a)
        if (!old(t)) return false
        val plain = given ?: if (t.legacy != null) AnimJson.parse(t.file.readText()).tracks else ClipCodec.readTracks(t.file)
        val target = File(clips, id + ClipCodec.EXTENSION)
        val prepared = ClipStore.prepare(plain) { ClipStore.header(a, it) }
        ClipCodec.writeAtomic(target, prepared.bytes)
        upgrade(t, target, prepared)
        writeIndex()
        return true
    }

    private fun migratePair(id: String): Boolean {
        val p = pairMap()[id] ?: return false
        val ta = stored(p.a); val tb = stored(p.b)
        if (!old(ta) && !old(tb)) return false
        val legacy = if (ta.legacy != null || tb.legacy != null) PairJson.parse(ta.file.readText()) else null
        val fa = File(clips, "$PAIR_PREFIX${id}_a${ClipCodec.EXTENSION}")
        val fb = File(clips, "$PAIR_PREFIX${id}_b${ClipCodec.EXTENSION}")
        val pa = ClipStore.prepare(legacy?.a?.tracks ?: ClipCodec.readTracks(ta.file)) { ClipStore.header(p.a, it, p, "a") }
        val pb = ClipStore.prepare(legacy?.b?.tracks ?: ClipCodec.readTracks(tb.file)) { ClipStore.header(p.b, it, p, "b") }
        ClipCodec.writeAtomic(fa, pa.bytes); ClipCodec.writeAtomic(fb, pb.bytes)
        upgrade(ta, fa, pa); upgrade(tb, fb, pb)
        writeIndex()
        return true
    }

    /** [t] now reads [target] (the same object: every Anim holding it follows); an old JSON file it read is removed. */
    private fun upgrade(t: StoredTracks, target: File, p: ClipStore.Prepared) {
        val oldFile = t.file
        val wasLegacy = t.legacy != null
        t.file = target; t.count = p.count; t.bytes = p.bytes.size.toLong(); t.inspection = p.inspection
        t.revision = ClipCodec.REVISION; t.legacy = null; t.onLegacyLoad = null
        ClipCache.forget(target)
        if (wasLegacy && oldFile.absoluteFile != target.absoluteFile) { oldFile.delete(); ClipCache.forget(oldFile) }
    }

    /** The bytes the library's clip files take (the index's own count). */
    fun storedBytes(): Long = synchronized(state.lock) {
        anims().values.sumOf { stored(it).bytes } + pairMap().values.sumOf { stored(it.a).bytes + stored(it.b).bytes }
    }

    /** Free space where the library lives. */
    fun freeBytes(): Long { dir.mkdirs(); return freeSpace(dir) }

    /**
     * A new import's staging folder (dir/.staging/<time>): its clips are written there as they convert and moved in when
     * saved. Any older staging folder (an import left in its review, or cut short) is removed first: one import at a time.
     */
    fun newStaging(): ImportStaging {
        val root = File(dir, STAGING)
        ArchiveImport.deleteTree(root)
        return ImportStaging(File(root, System.currentTimeMillis().toString()), ::freeBytes)
    }

    // ---------------------------------------------------------------- helpers

    private fun newPairId(map: Map<String, PairAnim>): String {
        while (true) { val id = UUID.randomUUID().toString().replace("-", "").take(16); if (id !in map) return id }
    }

    private fun normPairCategory(map: Map<String, PairAnim>, category: String, except: String? = null): String {
        val c = category.trim().ifEmpty { PairAnim.CATEGORY }
        return map.values.firstOrNull { it.id != except && it.category.equals(c, ignoreCase = true) }?.category ?: c
    }

    private fun newId(map: Map<String, Anim>): String {
        while (true) { val id = UUID.randomUUID().toString().replace("-", "").take(16); if (id !in map) return id }
    }

    private fun normCategory(map: Map<String, Anim>, category: String, except: String? = null): String {
        val c = category.trim().ifEmpty { DEFAULT_CATEGORY }
        // An existing category in another spelling of letter case keeps its first spelling.
        return map.values.firstOrNull { it.id != except && it.category.equals(c, ignoreCase = true) }?.category ?: c
    }

    /** [wanted], or with " (2)", " (3)"... when [used] (lower case) has it. */
    private fun uniqueName(used: Set<String>, wanted: String): String {
        if (wanted.lowercase() !in used) return wanted
        var n = 2
        while ("$wanted ($n)".lowercase() in used) n++
        return "$wanted ($n)"
    }

    private fun atomic(target: File, text: String) {
        dir.mkdirs()
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeText(text)
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        const val INDEX = "index.json"
        const val INDEX_VERSION = 2
        /** The clip files' folder. */
        const val CLIPS = "clips"
        /** Import staging folders (one at a time). */
        const val STAGING = ".staging"
        /** Partner animations' files: pair_<id>.json (old), clips/pair_<id>_a.wclip and _b.wclip. */
        const val PAIR_PREFIX = "pair_"
        private fun pairFile(id: String) = "$PAIR_PREFIX$id.json"
        /** Converted from a Sims clip by an older conversion than this app's (a converted clip always has a source). */
        fun isStale(a: Anim) = a.source.isNotBlank() && a.conversionRevision < Retargeter.CONVERSION_REVISION
        const val DEFAULT_CATEGORY = "Imported"
        private val ID = Regex("[A-Za-z0-9_-]{1,64}")
        private val states = HashMap<String, State>()

        /** Drops the shared in-memory state, so the next instance reads the folder afresh (used by tests, and after the folder is replaced). */
        fun forgetCaches() { synchronized(states) { states.clear() }; ClipCache.clear() }
    }
}
