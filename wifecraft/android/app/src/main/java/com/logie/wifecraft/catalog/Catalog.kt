package com.logie.wifecraft.catalog

import com.logie.wifecraft.creator.Tables

/** What a library entry is. */
enum class AssetKind {
    /** A world model (an M2 doodad, a weapon or shield): assets/catalog.tsv kind 0. */
    PROP,
    /** A building (a root WMO): kind 1. */
    BUILDING,
    /** A creature model, shown through one display (its textures): kind 2. */
    CREATURE_MODEL,
    /** One of the game's named creatures ("Archmage Khadgar"), read from its tables on the phone ([Catalog.creatures]). */
    CREATURE,
    /** One of the author's own people (the editor's cast, made in the character creator). */
    OWN_NPC,
}

/**
 * One thing the placement editor can put in a scene. [key] is unique across the library ("p:<file>", "b:<file>",
 * "m:<file>", "c:<creature id>", "n:<actor id>"). [fileId]: the model's file id (0 for an own NPC: its look is its
 * Appearance); [display]: a creature's CreatureDisplayInfo id (its textures, scale and sounds; 0 for props); [extra]: the
 * display's CreatureDisplayInfoExtra id, non-zero for an NPC drawn as a player race (a baked look and gear: see the
 * report); [size] 0 tiny .. 4 huge, -1 unknown; [scale] the display's CreatureModelScale.
 */
data class Asset(
    val key: String,
    val kind: AssetKind,
    val fileId: Int,
    val name: String,
    val category: String,
    val style: String = Catalog.ANY,
    val size: Int = -1,
    val alias: String = "",
    val display: Int = 0,
    val extra: Int = 0,
    val scale: Float = 1f,
)

/** A page of search results: [items] from [offset], of [total] matches. */
data class Page(val items: List<Asset>, val total: Int, val offset: Int)

/**
 * The placement editor's library: the props, buildings and creature models of assets/catalog.tsv (tools/catalog.py), the
 * game's named creatures from its tables ([creatures]) and the author's own people ([ownNpc]). Search by name words,
 * filter by category, style and kind, a page at a time. Pure JVM; nothing is downloaded to browse it (a model is
 * fetched by its file id once it is placed).
 */
class Catalog(val assets: List<Asset>) {
    private val byKey = assets.associateBy { it.key }
    private val names = assets.map { it.name.lowercase() }
    private val words: List<List<String>> = assets.map { a ->
        (WORD.findAll(a.name.lowercase()).map { it.value } + WORD.findAll(a.alias.lowercase()).map { it.value } +
            WORD.findAll(a.style.lowercase()).map { it.value } + WORD.findAll(a.category.lowercase()).map { it.value })
            .toList()
    }

    operator fun get(key: String): Asset? = byKey[key]

    /** The categories of the entries of [kinds] (all when null), in the order they first come, with how many each. */
    fun categories(kinds: Set<AssetKind>? = null): List<Pair<String, Int>> {
        val n = LinkedHashMap<String, Int>()
        for (a in assets) if (kinds == null || a.kind in kinds) n[a.category] = (n[a.category] ?: 0) + 1
        return n.entries.map { it.key to it.value }
    }

    fun styles(): List<String> = assets.mapTo(LinkedHashSet()) { it.style }.toList()

    /** This library with [more] added (an entry whose key is already here is replaced). */
    fun plus(more: List<Asset>): Catalog {
        val keys = more.mapTo(HashSet()) { it.key }
        return Catalog(assets.filter { it.key !in keys } + more)
    }

    /**
     * The entries matching [query] (each of its words begins a word of the name, alias, style or category, or, three
     * letters or more, is inside the name: "tree" finds "Duskwoodtree 05"), of [category], [style] and [kinds] where
     * given; best first (the name begins with the query, then whole-word matches, then shorter names), [limit] from [offset].
     */
    fun search(
        query: String = "",
        category: String? = null,
        style: String? = null,
        kinds: Set<AssetKind>? = null,
        offset: Int = 0,
        limit: Int = 50,
    ): Page {
        val q = query.trim().lowercase()
        val tokens = WORD.findAll(q).map { it.value }.toList()
        val hits = ArrayList<Pair<Int, Int>>()          // index, rank
        for (i in assets.indices) {
            val a = assets[i]
            if (category != null && a.category != category) continue
            if (style != null && a.style != style) continue
            if (kinds != null && a.kind !in kinds) continue
            if (tokens.isEmpty()) { hits += i to 3; continue }
            var rank = 1
            var ok = true
            for (t in tokens) {
                if (words[i].any { it.startsWith(t) }) continue
                if (t.length >= 3 && (names[i].contains(t) || a.alias.lowercase().contains(t))) { rank = 2; continue }
                ok = false
                break
            }
            if (!ok) continue
            if (names[i].startsWith(q) || a.alias.lowercase().startsWith(q)) rank = 0
            hits += i to rank
        }
        hits.sortWith(compareBy<Pair<Int, Int>>({ it.second }, { names[it.first].length }, { names[it.first] }, { assets[it.first].key }))
        val from = offset.coerceIn(0, hits.size)
        val to = (from + limit.coerceAtLeast(0)).coerceAtMost(hits.size)
        return Page(hits.subList(from, to).map { assets[it.first] }, hits.size, from)
    }

    /**
     * A location's starter pack: up to [perCategory] props of [style] in each of [categories] (the everyday things of a
     * place: furniture, light, containers, food), small to large before the rest, by name. The place's style is the
     * locations' data's (W25: `LocationAudio`'s sibling); "Any" gives the unstyled props.
     */
    fun starterPack(style: String, categories: List<String> = STARTER_CATEGORIES, perCategory: Int = 8): List<Asset> =
        categories.flatMap { c ->
            assets.asSequence()
                .filter { it.kind == AssetKind.PROP && it.category == c && it.style == style }
                .sortedWith(compareBy({ if (it.size in 1..3) 0 else 1 }, { it.name.lowercase() }, { it.fileId }))
                .take(perCategory).toList()
        }

    companion object {
        const val ANY = "Any"
        val SIZES = listOf("Tiny", "Small", "Medium", "Large", "Huge")
        val STARTER_CATEGORIES = listOf("Furniture", "Lighting", "Containers", "Food and drink", "Books and papers",
            "Banners and flags", "Nature")
        private val WORD = Regex("[\\p{L}\\p{N}']+")

        /** The entries of assets/catalog.tsv (tools/catalog.py's format: its header lines name the categories, styles, sizes). */
        fun parseTsv(text: String): List<Asset> {
            var cats = emptyList<String>()
            var styles = emptyList<String>()
            val out = ArrayList<Asset>(70000)
            for (line in text.lineSequence()) {
                if (line.isEmpty()) continue
                val f = line.split('\t')
                if (line.startsWith("#")) {
                    when (f[0]) { "#cats" -> cats = f.drop(1); "#styles" -> styles = f.drop(1) }
                    continue
                }
                if (f.size < 7) continue
                val id = f[0].toIntOrNull() ?: continue
                val kind = when (f[5]) { "0" -> AssetKind.PROP; "1" -> AssetKind.BUILDING; "2" -> AssetKind.CREATURE_MODEL; else -> continue }
                val prefix = when (kind) { AssetKind.PROP -> "p"; AssetKind.BUILDING -> "b"; else -> "m" }
                out += Asset("$prefix:$id", kind, id, f[1], cats.getOrElse(f[2].toInt()) { "Other" },
                    styles.getOrElse(f[3].toInt()) { ANY }, f[4].toInt(), f.getOrElse(7) { "" }, f[6].toInt())
            }
            return out
        }

        /** Names of the game's creatures that are no one to put in a scene: helpers, triggers, kill credits, vehicles. */
        val CREATURE_JUNK = Regex("bunny|trigger|stalker|credit|invisible|\\bdnd\\b|\\bdnt\\b|\\[|controller|\\bdummy\\b|" +
            "placeholder|\\btest\\b|\\bspell\\b|\\bvehicle\\b|\\bseat\\b|\\bpassenger\\b|\\(|\\bnyi\\b", RegexOption.IGNORE_CASE)

        /**
         * The game's named creatures from its tables (Creature: name, type, displays; CreatureDisplayInfo: model, scale,
         * extra; CreatureModelData: file; CreatureType: the category; CreatureFamily: a beast's family as its style):
         * each name once per display it is shown with (its first non-zero DisplayID), less [CREATURE_JUNK] and those
         * whose display has no model file.
         */
        fun creatures(t: Tables): List<Asset> {
            fun Map<String, String>.int(k: String) = this[k]?.toDoubleOrNull()?.toInt() ?: 0
            val types = t.rows("CreatureType").associate { it.int("ID") to it["Name_lang"].orEmpty() }
            val families = t.rows("CreatureFamily").associate { it.int("ID") to it["Name_lang"].orEmpty() }
            val files = t.rows("CreatureModelData").associate { it.int("ID") to it.int("FileDataID") }
            val displays = t.rows("CreatureDisplayInfo").associateBy { it.int("ID") }
            val seen = HashSet<String>()
            val out = ArrayList<Asset>()
            for (r in t.rows("Creature")) {
                val name = r["Name_lang"].orEmpty().trim()
                if (name.isEmpty() || CREATURE_JUNK.containsMatchIn(name)) continue
                val d = (0..3).map { r.int("DisplayID_$it") }.firstOrNull { it != 0 } ?: continue
                val di = displays[d] ?: continue
                val file = files[di.int("ModelID")] ?: 0
                if (file == 0 || !seen.add("$name|$d")) continue
                val scale = di["CreatureModelScale"]?.toFloatOrNull()?.takeIf { it > 0f } ?: 1f
                out += Asset("c:${r.int("ID")}", AssetKind.CREATURE, file, name, types[r.int("CreatureType")] ?: "Creatures",
                    families[r.int("CreatureFamily")]?.takeIf { it.isNotEmpty() } ?: ANY, -1, r["Title_lang"].orEmpty(), d,
                    di.int("ExtendedDisplayInfoID"), scale)
            }
            return out
        }

        /** One of the author's own people (the editor's Actor: id and name), in the category "My people". */
        fun ownNpc(actorId: String, name: String): Asset = Asset("n:$actorId", AssetKind.OWN_NPC, 0, name, MY_PEOPLE)

        const val MY_PEOPLE = "My people"
    }
}
