package com.logie.wifecraft.creator

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/**
 * The game's tables the creator reads (wago.tools' CSV exports: `https://wago.tools/db2/<Name>/csv`), a table by name,
 * each row a map of the header's names to the row's text. A table that isn't there is empty.
 */
interface Tables {
    fun rows(name: String): List<Map<String, String>>

    /**
     * The rows of [name] whose [column] reads exactly [value], in the table's order (none when the table hasn't the
     * column). The same as filtering [rows]; [CsvTables] answers it from an index made on the first use of each column.
     */
    fun rowsBy(name: String, column: String, value: String): List<Map<String, String>> = rows(name).filter { it[column] == value }
}

/** [Tables.rowsBy] of a whole number (as the tables write it: `123`). */
fun Tables.rowsBy(name: String, column: String, value: Int): List<Map<String, String>> = rowsBy(name, column, value.toString())

/** The last row of [name] whose [column] is [value] (as `associateBy` keeps it), or null. */
fun Tables.rowBy(name: String, column: String, value: Int): Map<String, String>? = rowsBy(name, column, value.toString()).lastOrNull()

/**
 * [dir]/<Name>.csv; RFC 4180 quoting (quoted fields, doubled quotes, line breaks inside quotes). Lean on the phone's heap
 * (the creatures' tables are some 55 MB of text): a table is mapped from its file (the system's pages, not the heap) with
 * only where each record starts kept; a row is read from the file field by field when asked, nothing of it kept.
 * [rowsBy] indexes a column on its first use (its rows sorted by the field's hash: 4 bytes a row).
 */
class CsvTables(private val dir: File) : Tables {
    private val tables = HashMap<String, MappedTable>()

    @Synchronized
    private fun table(name: String): MappedTable? {
        tables[name]?.let { return it }
        val file = File(dir, "$name.csv")
        return if (file.isFile) MappedTable.open(file)?.also { tables[name] = it } else null
    }

    override fun rows(name: String): List<Map<String, String>> = table(name)?.rows ?: emptyList()

    override fun rowsBy(name: String, column: String, value: String): List<Map<String, String>> =
        table(name)?.rowsBy(column, value) ?: emptyList()

    companion object {
        /**
         * The rows of a CSV text whose first record is the header (a table in memory: the tests'; the files are read
         * mapped, as [CsvTables] does). A row keeps its place in [text] and reads a field when asked.
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

    /** A table mapped from its file: where each record starts (4 bytes a row); the header's names to their places. */
    private class MappedTable(private val buf: ByteBuffer, private val starts: IntArray, val index: Map<String, Int>) {
        val rows: List<Map<String, String>> = object : AbstractList<Map<String, String>>(), RandomAccess {
            override val size get() = starts.size
            override fun get(index: Int): Map<String, String> = starts[index].let { MappedRow(this@MappedTable, it, end(it)) }
        }
        /** Per column: the rows sorted by the field's hash, then by row (4 bytes a row). */
        private val columns = HashMap<Int, IntArray>()

        /** Where the record from [start] ends: its unquoted line break (taken off with a carriage return before it) or the file's end. */
        fun end(start: Int): Int {
            val n = buf.limit()
            var i = start
            var quoted = false
            while (i < n) {
                val ch = buf.get(i).toInt()
                if (ch == QUOTE) quoted = !quoted
                else if (ch == '\n'.code && !quoted) break
                i++
            }
            return if (i > start && buf.get(i - 1) == '\r'.code.toByte()) i - 1 else i
        }

        private fun hashOf(row: Int, col: Int): Int = starts[row].let { field(it, end(it), col).hashCode() }

        fun rowsBy(column: String, value: String): List<Map<String, String>> {
            val col = index[column] ?: return emptyList()
            val order = synchronized(columns) {
                columns.getOrPut(col) {
                    val k = LongArray(starts.size) { i -> (hashOf(i, col).toLong() shl 32) or i.toLong() }
                    k.sort()
                    IntArray(k.size) { (k[it] and 0xFFFFFFFFL).toInt() }
                }
            }
            val h = value.hashCode()
            var lo = 0
            var hi = order.size
            while (lo < hi) { val mid = (lo + hi) ushr 1; if (hashOf(order[mid], col) < h) lo = mid + 1 else hi = mid }
            val out = ArrayList<Map<String, String>>(1)
            while (lo < order.size) {
                val i = order[lo]
                val st = starts[i]
                val e = end(st)
                val f = field(st, e, col)
                if (f.hashCode() != h) break
                if (f == value) out += MappedRow(this, st, e)
                lo++
            }
            return out   // in the table's order: one hash's rows are sorted by row
        }

        fun byte(i: Int): Int = buf.get(i).toInt()

        /** Field [want] of the record [from] to [to] ("" past its last field), as [CsvTables.Row] reads it. */
        fun field(from: Int, to: Int, want: Int): String {
            var at = from
            var k = 0
            while (at <= to) {
                if (at == to && k == want) return ""
                if (k == want) return read(at, to).first
                k++
                at = skip(at, to)
            }
            return ""
        }

        /** The fields of a record, as [CsvTables.fields] reads them. */
        fun fields(from: Int, to: Int): List<String> {
            val out = ArrayList<String>()
            var i = from
            while (true) {
                val (value, next) = read(i, to)
                out += value
                if (next >= to) { if (next == to && to > from && byte(to - 1) == COMMA) out += ""; break }
                i = next
            }
            return out
        }

        /** Where the field at [at] ends: past its comma ([to] or more at the record's end). */
        private fun skip(at: Int, to: Int): Int {
            var i = at
            if (i < to && byte(i) == QUOTE) {
                i++
                while (i < to) {
                    if (byte(i) == QUOTE) { if (i + 1 < to && byte(i + 1) == QUOTE) { i += 2; continue }; i++; break }
                    i++
                }
            }
            while (i < to && byte(i) != COMMA) i++
            return if (i < to) i + 1 else to + 1
        }

        /** The field at [at] (quotes off, doubled quotes made one, UTF-8) and where the next starts. */
        private fun read(at: Int, to: Int): Pair<String, Int> {
            if (at < to && byte(at) == QUOTE) {
                var b = ByteArray(32)
                var n = 0
                var i = at + 1
                while (i < to) {
                    val ch = byte(i)
                    if (ch == QUOTE) {
                        if (i + 1 < to && byte(i + 1) == QUOTE) { if (n == b.size) b = b.copyOf(n * 2); b[n++] = QUOTE.toByte(); i += 2; continue }
                        i++
                        break
                    }
                    if (n == b.size) b = b.copyOf(n * 2)
                    b[n++] = ch.toByte(); i++
                }
                while (i < to && byte(i) != COMMA) i++
                return String(b, 0, n, Charsets.UTF_8) to (if (i < to) i + 1 else to + 1)
            }
            var i = at
            while (i < to && byte(i) != COMMA) i++
            return text(at, i) to (if (i < to) i + 1 else to + 1)
        }

        private fun text(from: Int, to: Int): String {
            if (to <= from) return ""
            val b = ByteArray(to - from)
            for (k in b.indices) b[k] = buf.get(from + k)
            return String(b, Charsets.UTF_8)
        }

        companion object {
            const val QUOTE = '"'.code
            const val COMMA = ','.code

            fun open(f: File): MappedTable {
                val buf: ByteBuffer = FileChannel.open(f.toPath(), StandardOpenOption.READ).use { ch ->
                    ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size())
                }
                val n = buf.limit()
                // each record's start and end (a line, or more where a quoted field holds line breaks), without its line break
                val starts = IntArrayList()
                var i = if (n >= 3 && buf.get(0) == 0xEF.toByte() && buf.get(1) == 0xBB.toByte() && buf.get(2) == 0xBF.toByte()) 3 else 0
                var start = i
                var quoted = false
                var header: IntArray? = null
                fun record(a: Int, b: Int) {
                    if (header == null) header = intArrayOf(a, b)
                    else if (b > a) starts.add(a)
                }
                while (i < n) {
                    val ch = buf.get(i).toInt()
                    if (ch == QUOTE) quoted = !quoted
                    else if (ch == '\n'.code && !quoted) {
                        record(start, if (i > start && buf.get(i - 1) == '\r'.code.toByte()) i - 1 else i)
                        start = i + 1
                    }
                    i++
                }
                if (start < n) record(start, if (buf.get(n - 1) == '\r'.code.toByte()) n - 1 else n)
                val h = header
                if (h == null) return MappedTable(buf, IntArray(0), emptyMap())
                val empty = MappedTable(buf, IntArray(0), emptyMap())
                val names = empty.fields(h[0], h[1])
                val index = HashMap<String, Int>(names.size * 2)
                names.forEachIndexed { k, name -> index.putIfAbsent(name.intern(), k) }
                return MappedTable(buf, starts.toArray(), index)
            }
        }
    }

    /** A row of a [MappedTable]: where it is in the file; a field is read when asked (a missing field reads as empty). */
    private class MappedRow(private val t: MappedTable, private val from: Int, private val to: Int) : AbstractMap<String, String>() {
        override val entries: Set<Map.Entry<String, String>>
            get() {
                val values = t.fields(from, to)
                return t.index.entries.mapTo(LinkedHashSet()) { (k, i) -> java.util.AbstractMap.SimpleImmutableEntry(k, values.getOrElse(i) { "" }) }
            }

        override fun get(key: String): String? {
            val want = t.index[key] ?: return null
            return t.field(from, to, want)
        }

        override fun containsKey(key: String) = t.index.containsKey(key)
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
