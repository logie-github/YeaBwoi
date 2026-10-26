package com.logie.warcrafts.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClassicPlusTest {
    @Test
    fun `files are taken from inside the Classic+ folder`() {
        assertEquals("Units/Human/Footman/Footman.mdx", ClassicPlus.inMod("kenshin_lab_v1.0/Classic+/Units/Human/Footman/Footman.mdx"))
        assertEquals("textures/footman.dds", ClassicPlus.inMod("kenshin_lab_v1.0\\classic+\\textures\\footman.dds"))
        assertNull(ClassicPlus.inMod("kenshin_lab_v1.0/AllowLocalFiles.reg"))
        assertNull(ClassicPlus.inMod("kenshin_lab_v1.0/Classic+"))
        assertNull(ClassicPlus.inMod("Classic+/../War3.mpq"))
    }

    @Test
    fun `the download is found on the mod's page`() {
        val page = """<a href="https://drive.google.com/file/d/1vndUBXEFwlMNBUWhUUphSoCr3WhEPE2h/view">Download</a>"""
        assertEquals("1vndUBXEFwlMNBUWhUUphSoCr3WhEPE2h", ClassicPlus.driveId(page))
        assertNull(ClassicPlus.driveId("<html>no link</html>"))
    }

    @Test
    fun `Google Drive's warning page gives the download's address`() {
        val page = """<form id="download-form" action="https://drive.usercontent.google.com/download" method="get">""" +
            """<input type="submit" id="uc-download-link" value="Download anyway"/>""" +
            """<input type="hidden" name="id" value="ABC"><input type="hidden" name="export" value="download">""" +
            """<input type="hidden" name="confirm" value="t"><input type="hidden" name="uuid" value="u-1"></form>"""
        assertEquals("https://drive.usercontent.google.com/download?id=ABC&export=download&confirm=t&uuid=u-1", ClassicPlus.confirmUrl(page))
        assertNull(ClassicPlus.confirmUrl("<html>the file itself</html>"))
    }
}
