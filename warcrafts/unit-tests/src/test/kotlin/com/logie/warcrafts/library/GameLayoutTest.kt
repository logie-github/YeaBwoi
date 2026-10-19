package com.logie.warcrafts.library

import com.logie.warcrafts.library.GameLayout.Program
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GameLayoutTest {
    private val repack = "Warcraft III 1.30 (16by9 ratio) (Multi-Language) by Mashiro"

    @Test
    fun `the 1_30 repack's zip is taken from inside its folder`() {
        val paths = listOf(
            "$repack/Warcraft III.exe",
            "$repack/World Editor.exe",
            "$repack/DO NOT CLICK THIS - Warcraft III Launcher.exe",
            "$repack/Data/data/data.000",
            "$repack/Maps/ENGLISH v1.30 (default)/(2)BootyBay.w3m",
            "$repack/cef/SceneCefBrowser.exe",
        )
        assertEquals(repack, GameLayout.findRoot(paths, Install.WC3.mainNames))
    }

    @Test
    fun `a folder picked as the game itself has its root at the top`() {
        val paths = listOf("Warcraft III.exe", "Data/indices/x.idx", "JassHelper/jasshelper.exe")
        assertEquals("", GameLayout.findRoot(paths, Install.WC3.mainNames))
    }

    @Test
    fun `an unknown game is taken from the single folder everything is in`() {
        val paths = listOf("Warcraft II Remastered/x86_64/Game.exe", "Warcraft II Remastered/Data/a.casc")
        assertEquals("Warcraft II Remastered", GameLayout.findRoot(paths, Install.WC2.mainNames))
    }

    @Test
    fun `no program at all is no game`() {
        assertNull(GameLayout.findRoot(listOf("Maps/a.w3m", "readme.txt"), Install.WC3.mainNames))
    }

    @Test
    fun `Warcraft III's own program wins over its launcher, editor and helpers`() {
        val programs = listOf(
            Program("DO NOT CLICK THIS - Warcraft III Launcher.exe", 3_833_840),
            Program("World Editor.exe", 12_685_800),
            Program("Warcraft III.exe", 22_370_792),
            Program("BlizzardError.exe", 898_536),
            Program("cef/SceneCefBrowser.exe", 2_567_656),
        )
        assertEquals("Warcraft III.exe", GameLayout.pickProgram(programs, Install.WC3.mainNames)?.path)
    }

    @Test
    fun `an unknown remastered program is the biggest one that isn't a helper`() {
        val programs = listOf(
            Program("Warcraft II Remastered Launcher.exe", 4_000_000),
            Program("x86_64/Warcraft II Game.exe", 90_000_000),
            Program("x86_64/BlizzardError.exe", 900_000),
            Program("x86_64/CrashMailer.exe", 1_000_000),
            Program("Battle.net Agent.exe", 5_000_000),
        )
        assertEquals("x86_64/Warcraft II Game.exe", GameLayout.pickProgram(programs, Install.WC2.mainNames)?.path)
    }

    @Test
    fun `paths that would leave the game's folder are refused`() {
        assertTrue(GameLayout.isSafe("Data/data/data.000"))
        assertFalse(GameLayout.isSafe("../evil.exe"))
        assertFalse(GameLayout.isSafe("/etc/hosts"))
        assertFalse(GameLayout.isSafe("Data/../../x"))
        assertFalse(GameLayout.isSafe("a\\b"))
        assertFalse(GameLayout.isSafe(""))
    }
}

class GogLayoutTest {
    @Test
    fun `GOG Warcraft I's folder is the one with WAR_EXE and DATA`() {
        val paths = listOf("Warcraft Orcs & Humans/WAR.EXE", "Warcraft Orcs & Humans/DATA/DATA.WAR", "Warcraft Orcs & Humans/DOS4GW.EXE")
        assertEquals("Warcraft Orcs & Humans", GameLayout.findRoot(paths, Install.WC1.mainNames))
    }

    @Test
    fun `GOG Warcraft II's folder is the one with the program, the tomes and Install_mpq`() {
        val paths = listOf("Warcraft II BNE/Warcraft II BNE.exe", "Warcraft II BNE/Support/TOMES/TOME.1", "Warcraft II BNE/Install.mpq")
        assertEquals("Warcraft II BNE", GameLayout.findRoot(paths, Install.WC2.mainNames))
    }

    @Test
    fun `Warcraft II's data alone, without a program, is still found`() {
        val paths = listOf("War2/Support/TOMES/TOME.1", "War2/Install.mpq")
        assertEquals("War2", GameLayout.findRoot(paths, Install.WC2.mainNames))
    }
}

class GogInstallerTest {
    private val wc2 = "setup_warcraft_ii_2.02_v4_(28456)"
    private val wc1 = "setup_warcraft_orcs__humans_1.2_(28330)"

    @Test
    fun `GOG's installer is its setup program and the bin parts named after it`() {
        val paths = listOf("$wc2.exe", "$wc2-1.bin", "$wc2-2.bin", "notes.txt")
        assertEquals(GameLayout.Installer("$wc2.exe", listOf("$wc2-1.bin", "$wc2-2.bin")), GameLayout.findInstaller(paths, Install.WC2.installerHint))
    }

    @Test
    fun `an installer of one exe has no parts`() {
        assertEquals(GameLayout.Installer("$wc1.exe", emptyList()), GameLayout.findInstaller(listOf("$wc1.exe"), Install.WC1.installerHint))
    }

    @Test
    fun `with both games' installers in a folder, each game takes its own`() {
        val paths = listOf("GOG/$wc1.exe", "GOG/$wc2.exe", "GOG/$wc2-1.bin")
        assertEquals(GameLayout.Installer("GOG/$wc1.exe", emptyList()), GameLayout.findInstaller(paths, Install.WC1.installerHint))
        assertEquals(GameLayout.Installer("GOG/$wc2.exe", listOf("GOG/$wc2-1.bin")), GameLayout.findInstaller(paths, Install.WC2.installerHint))
    }

    @Test
    fun `renamed parts are still taken when none is named after the setup program`() {
        val paths = listOf("SETUP.EXE", "part1.bin")
        assertEquals(GameLayout.Installer("SETUP.EXE", listOf("part1.bin")), GameLayout.findInstaller(paths, Install.WC1.installerHint))
    }

    @Test
    fun `no setup program is no installer`() {
        assertNull(GameLayout.findInstaller(listOf("WAR.EXE", "DATA/DATA.WAR"), Install.WC1.installerHint))
    }

    @Test
    fun `an installed game is told apart from its installer`() {
        assertTrue(GameLayout.hasMain(listOf("Warcraft/WAR.EXE", "Warcraft/DATA/DATA.WAR"), Install.WC1.mainNames))
        assertFalse(GameLayout.hasMain(listOf("$wc1.exe"), Install.WC1.mainNames))
        assertTrue(GameLayout.hasMain(listOf("app/Support/TOMES/TOME.1"), Install.WC2.mainNames))
    }

    @Test
    fun `innoextract's unpacked app folder is the game's folder`() {
        val paths = listOf("app/WAR.EXE", "app/DATA/DATA.WAR", "app/DOSBOX/DOSBox.exe", "commonappdata/x.ini")
        assertEquals("app", GameLayout.findRoot(paths, Install.WC1.mainNames))
    }

    @Test
    fun `English is picked from the installer's languages`() {
        assertEquals("english", GameLayout.englishLanguage(listOf("german Deutsch", "english English", "french Français")))
        assertEquals("en-US", GameLayout.englishLanguage(listOf("de-DE", "en-US")))
        assertNull(GameLayout.englishLanguage(listOf("german Deutsch")))
        assertNull(GameLayout.englishLanguage(emptyList()))
    }
}
