package com.logie.warcrafts.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CdKeysTest {
    @Test
    fun `keys are found with the words before them as their label`() {
        val text = """
            Serial keys for the old installer:

            Reign of Chaos: ABCD2-EFGH3-JKLM4-NPQR5-TVWX6
            The Frozen Throne - QWER7-TYUP8-ASDF9-GHJK2-ZXCV3
        """.trimIndent()
        assertEquals(
            listOf(
                CdKeys.Found("Reign of Chaos", "ABCD2-EFGH3-JKLM4-NPQR5-TVWX6"),
                CdKeys.Found("The Frozen Throne", "QWER7-TYUP8-ASDF9-GHJK2-ZXCV3"),
            ),
            CdKeys.find(text),
        )
    }

    @Test
    fun `a 26-character key needs letters and digits, and words aren't keys`() {
        val found = CdKeys.find("ROC 4ab7cd8ef9gh2jk3lm4np5qr6s\nIncomprehensibilitiesxyzab is a word")
        assertEquals(listOf(CdKeys.Found("ROC", "4AB7CD8EF9GH2JK3LM4NP5QR6S")), found)
    }

    @Test
    fun `dates, versions and links are left alone, the same key once`() {
        val text = "v1.30.4.11274 built 2019-01-15\nhttps://ss64.com/locale.html\nKEY1-KEY2-KEY3\nagain KEY1-KEY2-KEY3"
        assertEquals(listOf(CdKeys.Found("", "KEY1-KEY2-KEY3")), CdKeys.find(text))
    }

    @Test
    fun `key files are told by their names`() {
        assertTrue(CdKeys.isKeyFile("_SERIAL_KEYS.txt"))
        assertTrue(CdKeys.isKeyFile("CD Key.txt"))
        assertTrue(CdKeys.isKeyFile("keys.txt"))
        assertFalse(CdKeys.isKeyFile("README.txt"))
        assertFalse(CdKeys.isKeyFile("CustomKeyInfo.txt.bak"))
    }

    @Test
    fun `pasted keys are taken line by line, with or without labels and dashes`() {
        val pasted = """
            RoC: ABCD-EFGH-1234-5678-WXYZ
            tft2k3j4h5g6f7d8s9a0q1w2e3

            not a key
            abcdefgh12345678
        """.trimIndent()
        assertEquals(
            listOf(
                CdKeys.Found("RoC", "ABCD-EFGH-1234-5678-WXYZ"),
                CdKeys.Found("", "TFT2K3J4H5G6F7D8S9A0Q1W2E3"),
                CdKeys.Found("", "ABCDEFGH12345678"),
            ),
            CdKeys.findPasted(pasted),
        )
    }

    @Test
    fun `the same key pasted twice is offered once, and nothing pasted is no key`() {
        assertEquals(1, CdKeys.findPasted("ABCDEFGH12345678\nabcdefgh12345678").size)
        assertTrue(CdKeys.findPasted("").isEmpty())
        assertTrue(CdKeys.findPasted("short1234\nwords only here").isEmpty())
    }
}
