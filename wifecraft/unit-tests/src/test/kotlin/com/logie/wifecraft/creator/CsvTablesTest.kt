package com.logie.wifecraft.creator

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The lean table reader (CsvTables: mapped from the file, a row read when asked, a column indexed for rowsBy) reads every
 * row as the in-memory reader (CsvTables.parse of the file's text) does: the creator's fixtures and the hard cases.
 */
class CsvTablesTest {
    private val fixtures = File(javaClass.getResource("/creator/ChrModel.csv")!!.toURI()).parentFile

    /** Every row's fields (by entries and by get, a missing column included) as parse() reads them. */
    private fun assertSame(name: String, lean: Tables, text: String) {
        val old = CsvTables.parse(text)
        val new = lean.rows(name)
        assertEquals(old.size, new.size, "$name: rows")
        for (i in old.indices) {
            assertEquals(old[i], new[i], "$name row $i")
            assertEquals(old[i].entries.map { it.key to it.value }, new[i].entries.map { it.key to it.value }, "$name row $i entries")
            for (k in old[i].keys + "NoSuchColumn") assertEquals(old[i][k], new[i][k], "$name row $i $k")
        }
    }

    @Test fun theFixturesReadAsBefore() {
        val lean = CsvTables(fixtures)
        val names = fixtures.listFiles()!!.filter { it.name.endsWith(".csv") }.map { it.name.removeSuffix(".csv") }
        assertTrue(names.size >= 10)
        for (n in names) assertSame(n, lean, File(fixtures, "$n.csv").readText(Charsets.UTF_8))
    }

    @Test fun theHardCasesReadAsBefore() {
        val dir = Files.createTempDirectory("csv").toFile()
        val cases = mapOf(
            "Quoted" to "a,b,c\n\"x, y\",\"say \"\"hi\"\"\",\"two\nlines\"\r\n1,,\n",
            "Bom" to "﻿ID,Name_lang\r\n1,Élan\r\n2,\"Ünïcødé, ✓\"\r\n\r\n3,日本\r\n",
            "Short" to "A,B,C,D\n1,2\n3,4,5,6,7\n8,\n,\n",
            "NoEnd" to "ID,X\n1,\"a\"\"b\"\n2,last",
            "Trailing" to "ID,X,\n1,2,\n2,3,4\n",
            "Unclosed" to "ID,X\n1,\"open\n2,3\n",
            "Header" to "ID,X\n",
            "Empty" to "",
        )
        for ((n, text) in cases) File(dir, "$n.csv").writeText(text, Charsets.UTF_8)
        val lean = CsvTables(dir)
        for ((n, text) in cases) assertSame(n, lean, text)
        assertEquals(emptyList(), lean.rows("Missing"))
        assertEquals(emptyList(), lean.rowsBy("Missing", "ID", "1"))
        dir.deleteRecursively()
    }

    /** rowsBy is rows() filtered on the column's exact text, in the table's order, for every value the column has. */
    @Test fun rowsByIsAFilterOfRows() {
        val lean = CsvTables(fixtures)
        val plain = object : Tables { override fun rows(name: String) = lean.rows(name) }   // the interface's own rowsBy
        for (name in listOf("ChrCustomizationChoice", "ChrCustomizationElement", "TextureFileData", "CreatureDisplayInfo")) {
            val rows = lean.rows(name)
            assertTrue(rows.isNotEmpty(), name)
            for (column in rows[0].keys) {
                val values = rows.map { it[column]!! }.toSet() + "no such value" + ""
                for (v in values) assertEquals(plain.rowsBy(name, column, v), lean.rowsBy(name, column, v), "$name.$column = $v")
            }
            assertEquals(emptyList(), lean.rowsBy(name, "NoSuchColumn", "1"))
        }
        val id = lean.rows("ChrModel").last().int("ID")
        assertEquals(lean.rows("ChrModel").last(), lean.rowBy("ChrModel", "ID", id))
    }
}
