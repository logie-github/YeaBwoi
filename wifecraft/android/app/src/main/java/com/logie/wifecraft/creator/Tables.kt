package com.logie.wifecraft.creator

import java.io.File

/**
 * The game's tables the creator reads (wago.tools' CSV exports: `https://wago.tools/db2/<Name>/csv`), a table by name,
 * each row a map of the header's names to the row's text. A table that isn't there is empty.
 */
interface Tables {
    fun rows(name: String): List<Map<String, String>>
}

/** [dir]/<Name>.csv, read once and kept; RFC 4180 quoting (quoted fields, doubled quotes, line breaks inside quotes). */
class CsvTables(private val dir: File) : Tables {
    private val cache = HashMap<String, List<Map<String, String>>>()

    @Synchronized
    override fun rows(name: String): List<Map<String, String>> = cache.getOrPut(name) {
        val f = File(dir, "$name.csv")
        if (!f.isFile) emptyList() else parse(f.readText(Charsets.UTF_8))
    }

    companion object {
        /**
         * The rows of a CSV text whose first record is the header. A row keeps its place in [text] and reads a field
         * when asked (the game's big tables, CreatureDisplayInfo's hundred thousand rows, cost their text and no more).
         */
        fun parse(text: String): List<Map<String, String>> {
            val ranges = records(text)
            if (ranges.isEmpty()) return emptyList()
            val header = fields(text, ranges[0], ranges[1])
            val index = HashMap<String, Int>(header.size * 2)
            header.forEachIndexed { i, h -> index.putIfAbsent(h, i) }
            val out = ArrayList<Map<String, String>>(ranges.size / 2)
            var k = 2
            while (k < ranges.size) {
                val a = ranges[k]; val b = ranges[k + 1]
                if (b > a) out += Row(text, a, b, index)
                k += 2
            }
            return out
        }

        /** Each record's start and end in [text] (a line, or more where a quoted field holds line breaks), the ends
         *  without their line break. */
        internal fun records(text: String): IntArray {
            val out = IntArrayList()
            var i = if (text.startsWith("\uFEFF")) 1 else 0
            var start = i
            var quoted = false
            val n = text.length
            while (i < n) {
                val ch = text[i]
                if (ch == '"') quoted = !quoted
                else if (ch == '\n' && !quoted) {
                    out.add(start); out.add(if (i > start && text[i - 1] == '\r') i - 1 else i)
                    start = i + 1
                }
                i++
            }
            if (start < n) { out.add(start); out.add(if (text[n - 1] == '\r') n - 1 else n) }
            return out.toArray()
        }

        /** The fields of the record [from] to [to]: quotes taken off, doubled quotes made one. */
        internal fun fields(text: String, from: Int, to: Int): List<String> {
            val out = ArrayList<String>()
            var i = from
            while (true) {
                val (value, next) = field(text, i, to)
                out += value
                if (next >= to) { if (next == to && to > from && text[to - 1] == ',') out += ""; break }
                i = next
            }
            return out
        }

        /** The field starting at [at] and where the next starts (past its comma; [to] or more at the record's end). */
        internal fun field(text: String, at: Int, to: Int): Pair<String, Int> {
            if (at < to && text[at] == '"') {
                val sb = StringBuilder()
                var i = at + 1
                while (i < to) {
                    val ch = text[i]
                    if (ch == '"') {
                        if (i + 1 < to && text[i + 1] == '"') { sb.append('"'); i += 2; continue }
                        i++
                        break
                    }
                    sb.append(ch); i++
                }
                while (i < to && text[i] != ',') i++
                return sb.toString() to (if (i < to) i + 1 else to + 1)
            }
            var i = at
            while (i < to && text[i] != ',') i++
            return text.substring(at, i) to (if (i < to) i + 1 else to + 1)
        }
    }

    /** A row: its place in the table's text; a field is read when asked (a missing field reads as empty). */
    private class Row(private val text: String, private val from: Int, private val to: Int, private val index: Map<String, Int>) : AbstractMap<String, String>() {
        override val entries: Set<Map.Entry<String, String>>
            get() {
                val values = fields(text, from, to)
                return index.entries.mapTo(LinkedHashSet()) { (k, i) -> java.util.AbstractMap.SimpleImmutableEntry(k, values.getOrElse(i) { "" }) }
            }

        override fun get(key: String): String? {
            val want = index[key] ?: return null
            var at = from
            var k = 0
            while (at <= to) {
                if (at == to && k == want) return ""
                val (value, next) = field(text, at, to)
                if (k == want) return value
                k++
                at = next
            }
            return ""
        }

        override fun containsKey(key: String) = index.containsKey(key)
    }

    private class IntArrayList {
        private var a = IntArray(1024)
        private var n = 0
        fun add(v: Int) { if (n == a.size) a = a.copyOf(n * 2); a[n++] = v }
        fun toArray() = a.copyOf(n)
    }
}

/** A column as a whole number (empty, missing or a float's whole part as 0 / its value). */
internal fun Map<String, String>.int(key: String): Int {
    val v = this[key] ?: return 0
    if (v.isEmpty()) return 0
    return v.toIntOrNull() ?: v.toLongOrNull()?.toInt() ?: v.toDoubleOrNull()?.toInt() ?: 0
}

internal fun Map<String, String>.float(key: String, default: Float = 0f): Float = this[key]?.toFloatOrNull() ?: default
