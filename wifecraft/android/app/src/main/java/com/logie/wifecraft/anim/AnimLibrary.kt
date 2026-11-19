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
class PairAddResult(val pair: PairAnim, val duplicate: Boolean, val message: String)

/** The animation library on disk: dir/<id>.json per animation (see [AnimJson]) and dir/index.json (id, name,
 *  category) for fast listing; partner animations in dir/pair_<id>.json (see [PairJson]), with their own names,
 *  categories and the same duplicate policy. Every write is a temp file then a rename. Instances on the same
 *  folder share their state and a lock, so the app's threads cannot trip over each other. A damaged file is
 *  skipped. A library written before partner animations existed loads unchanged (it has no pair files). */
class AnimLibrary(private val dir: File) {
    class IndexEntry(val id: String, val name: String, val category: String)

    private class State { val lock = Any(); var anims: LinkedHashMap<String, Anim>? = null; var pairs: LinkedHashMap<String, PairAnim>? = null }

    private val state: State = synchronized(states) { states.getOrPut(dir.absoluteFile.normalize().path) { State() } }

    /** Names of animation files that could not be read at the last load. */
    @Volatile var damaged: List<String> = emptyList()
        private set

    private fun load(): LinkedHashMap<String, Anim> {
        state.anims?.let { return it }
        dir.mkdirs()
        val map = LinkedHashMap<String, Anim>()
        val bad = ArrayList<String>()
        val files = (dir.listFiles { f -> isAnimFile(f) } ?: emptyArray()).sortedBy { it.name }
        for (f in files) {
            try {
                val a = AnimJson.parse(f.readText())
                if (a.id + ".json" == f.name) map[a.id] = a else bad.add(f.name)
            } catch (e: Exception) { bad.add(f.name) }
        }
        damaged = bad
        state.anims = map
        return map
    }

    private fun sorted(m: Collection<Anim>) =
        m.sortedWith(compareBy<Anim, String>(String.CASE_INSENSITIVE_ORDER) { it.category }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.id })

    fun list(): List<Anim> = synchronized(state.lock) { sorted(load().values) }

    fun get(id: String): Anim? = synchronized(state.lock) { load()[id] }

    /** The light listing (no tracks) from index.json when it is intact, else rebuilt from the animations. */
    fun index(): List<IndexEntry> = synchronized(state.lock) {
        val loaded = state.anims
        if (loaded == null) {
            val read = readIndex()
            val count = dir.listFiles { f -> isAnimFile(f) }?.size ?: 0
            if (read != null && read.size == count) return read
        }
        sorted(load().values).map { IndexEntry(it.id, it.name, it.category) }
    }

    private fun readIndex(): List<IndexEntry>? = try {
        val f = File(dir, INDEX)
        if (!f.isFile) null else {
            val arr = JSONObject(f.readText()).getJSONArray("anims")
            List(arr.length()) { val o = arr.getJSONObject(it); IndexEntry(o.getString("id"), o.getString("name"), o.getString("category")) }
        }
    } catch (e: Exception) { null }

    /** Adds [a]: the same source (file hash and clip) already stored is skipped; a name already used gets " (2)",
     *  " (3)"...; an id that is unusable or taken is replaced. Returns the stored animation. */
    fun add(a: Anim): Anim = addResult(a).anim

    fun addResult(a: Anim): AddResult = synchronized(state.lock) {
        val map = load()
        if (a.source.isNotBlank()) {
            val same = map.values.firstOrNull { it.source == a.source }
            if (same != null && a.conversionRevision > same.conversionRevision) {
                val updated = a.copy(id = same.id, name = same.name, category = same.category, importedAt = same.importedAt)
                write(updated)
                map[same.id] = updated
                return AddResult(updated, false, "updated ${same.name}", updated = true)
            }
            if (same != null) return AddResult(same, true, "\"${a.name.trim()}\" is already in the library as \"${same.name}\" (same file and clip), skipped")
        }
        val id = if (ID.matches(a.id) && a.id !in map && !a.id.startsWith(PAIR_PREFIX)) a.id else newId(map)
        val wanted = a.name.trim().ifEmpty { "Animation" }
        val name = uniqueName(map, wanted, null)
        val stored = a.copy(id = id, name = name, category = normCategory(map, a.category))
        write(stored)
        map[id] = stored
        writeIndex(map)
        val msg = if (name != wanted) "\"$wanted\" was taken, saved as \"$name\"" else "saved as \"$name\""
        AddResult(stored, false, msg)
    }

    fun rename(id: String, name: String) = synchronized(state.lock) {
        val map = load()
        val a = map[id] ?: throw NoSuchElementException("No animation $id")
        require(name.isNotBlank()) { "A name cannot be empty" }
        val updated = a.copy(name = uniqueName(map, name.trim(), id))
        write(updated); map[id] = updated; writeIndex(map)
    }

    fun setCategory(id: String, category: String) = synchronized(state.lock) {
        val map = load()
        val a = map[id] ?: throw NoSuchElementException("No animation $id")
        val updated = a.copy(category = normCategory(map, category, id))
        write(updated); map[id] = updated; writeIndex(map)
    }

    fun delete(id: String) = synchronized(state.lock) {
        val map = load()
        if (map.remove(id) == null) return@synchronized
        File(dir, "$id.json").delete()
        writeIndex(map)
    }

    /** Every category in use, alphabetical, no repeats. */
    fun categories(): List<String> = synchronized(state.lock) {
        load().values.map { it.category }.distinctBy { it.lowercase() }.sortedWith(String.CASE_INSENSITIVE_ORDER)
    }

    /** Animations whose name or category contains [query], ignoring case; a blank query finds all. */
    fun find(query: String): List<Anim> = synchronized(state.lock) {
        val q = query.trim().lowercase()
        sorted(load().values.filter { q.isEmpty() || it.name.lowercase().contains(q) || it.category.lowercase().contains(q) })
    }

    // ---------------------------------------------------------------- partner animations

    private fun loadPairs(): LinkedHashMap<String, PairAnim> {
        state.pairs?.let { return it }
        dir.mkdirs()
        val map = LinkedHashMap<String, PairAnim>()
        val bad = ArrayList<String>()
        val files = (dir.listFiles { f -> f.isFile && f.name.startsWith(PAIR_PREFIX) && f.name.endsWith(".json") } ?: emptyArray()).sortedBy { it.name }
        for (f in files) {
            try {
                val p = PairJson.parse(f.readText())
                if (pairFile(p.id) == f.name) map[p.id] = p else bad.add(f.name)
            } catch (e: Exception) { bad.add(f.name) }
        }
        damagedPairs = bad
        state.pairs = map
        return map
    }

    /** Names of partner-animation files that could not be read at the last load. */
    @Volatile var damagedPairs: List<String> = emptyList()
        private set

    private fun sortedPairs(m: Collection<PairAnim>) =
        m.sortedWith(compareBy<PairAnim, String>(String.CASE_INSENSITIVE_ORDER) { it.category }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.id })

    fun pairs(): List<PairAnim> = synchronized(state.lock) { sortedPairs(loadPairs().values) }

    fun getPair(id: String): PairAnim? = synchronized(state.lock) { loadPairs()[id] }

    /** Adds [p] with the singles' policy: the same two clips already stored is skipped (the stored one returned);
     *  a name already used by a partner animation gets " (2)", " (3)"...; an unusable or taken id is replaced. */
    fun addPair(p: PairAnim): PairAnim = addPairResult(p).pair

    fun addPairResult(p: PairAnim): PairAddResult = synchronized(state.lock) {
        val map = loadPairs()
        if (p.source.isNotBlank()) {
            val same = map.values.firstOrNull { it.source == p.source }
            if (same != null) return PairAddResult(same, true, "\"${p.name.trim()}\" is already in the library as \"${same.name}\" (the same two clips), skipped")
        }
        val id = if (ID.matches(p.id) && p.id !in map) p.id else newPairId(map)
        val wanted = p.name.trim().ifEmpty { "Partner animation" }
        val name = uniquePairName(map, wanted, null)
        val stored = p.copy(id = id, name = name, category = normPairCategory(map, p.category))
        writePair(stored)
        map[id] = stored
        val msg = if (name != wanted) "\"$wanted\" was taken, saved as \"$name\"" else "saved as \"$name\""
        PairAddResult(stored, false, msg)
    }

    fun setPairEnabled(id: String, on: Boolean) = updatePair(id) { it.copy(enabled = on) }

    fun renamePair(id: String, name: String) = synchronized(state.lock) {
        require(name.isNotBlank()) { "A name cannot be empty" }
        val map = loadPairs()
        updatePair(id) { it.copy(name = uniquePairName(map, name.trim(), id)) }
    }

    fun setPairCategory(id: String, category: String) = synchronized(state.lock) {
        val map = loadPairs()
        updatePair(id) { it.copy(category = normPairCategory(map, category, id)) }
    }

    fun deletePair(id: String) = synchronized(state.lock) {
        val map = loadPairs()
        if (map.remove(id) == null) return@synchronized
        File(dir, pairFile(id)).delete()
    }

    /** Every partner-animation category in use, alphabetical, no repeats. */
    fun pairCategories(): List<String> = synchronized(state.lock) {
        loadPairs().values.map { it.category }.distinctBy { it.lowercase() }.sortedWith(String.CASE_INSENSITIVE_ORDER)
    }

    private fun updatePair(id: String, change: (PairAnim) -> PairAnim) = synchronized(state.lock) {
        val map = loadPairs()
        val p = map[id] ?: throw NoSuchElementException("No partner animation $id")
        val updated = change(p)
        writePair(updated); map[id] = updated
    }

    private fun newPairId(map: Map<String, PairAnim>): String {
        while (true) { val id = UUID.randomUUID().toString().replace("-", "").take(16); if (id !in map) return id }
    }

    private fun normPairCategory(map: Map<String, PairAnim>, category: String, except: String? = null): String {
        val c = category.trim().ifEmpty { PairAnim.CATEGORY }
        return map.values.firstOrNull { it.id != except && it.category.equals(c, ignoreCase = true) }?.category ?: c
    }

    private fun uniquePairName(map: Map<String, PairAnim>, wanted: String, except: String?): String {
        val used = map.values.filter { it.id != except }.map { it.name.lowercase() }.toHashSet()
        if (wanted.lowercase() !in used) return wanted
        var n = 2
        while ("$wanted ($n)".lowercase() in used) n++
        return "$wanted ($n)"
    }

    private fun writePair(p: PairAnim) = atomic(File(dir, pairFile(p.id)), PairJson.toJson(p).toString())

    private fun newId(map: Map<String, Anim>): String {
        while (true) { val id = UUID.randomUUID().toString().replace("-", "").take(16); if (id !in map) return id }
    }

    private fun normCategory(map: Map<String, Anim>, category: String, except: String? = null): String {
        val c = category.trim().ifEmpty { DEFAULT_CATEGORY }
        // An existing category in another spelling of letter case keeps its first spelling.
        return map.values.firstOrNull { it.id != except && it.category.equals(c, ignoreCase = true) }?.category ?: c
    }

    private fun uniqueName(map: Map<String, Anim>, wanted: String, except: String?): String {
        val used = map.values.filter { it.id != except }.map { it.name.lowercase() }.toHashSet()
        if (wanted.lowercase() !in used) return wanted
        var n = 2
        while ("$wanted ($n)".lowercase() in used) n++
        return "$wanted ($n)"
    }

    private fun write(a: Anim) = atomic(File(dir, "${a.id}.json"), AnimJson.toJson(a).toString())

    private fun writeIndex(map: Map<String, Anim>) {
        val arr = JSONArray()
        for (a in sorted(map.values)) arr.put(JSONObject().put("id", a.id).put("name", a.name).put("category", a.category))
        atomic(File(dir, INDEX), JSONObject().put("v", 1).put("anims", arr).toString())
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
        /** Partner animations' files: pair_<id>.json. */
        const val PAIR_PREFIX = "pair_"
        private fun pairFile(id: String) = "$PAIR_PREFIX$id.json"
        private fun isAnimFile(f: File) = f.isFile && f.name.endsWith(".json") && f.name != INDEX && !f.name.startsWith(PAIR_PREFIX)
        const val DEFAULT_CATEGORY = "Imported"
        private val ID = Regex("[A-Za-z0-9_-]{1,64}")
        private val states = HashMap<String, State>()

        /** Drops the shared in-memory state, so the next instance reads the folder afresh (used by tests, and after the folder is replaced). */
        fun forgetCaches() = synchronized(states) { states.clear() }
    }
}
