package com.logie.warcraftrts.library

import com.logie.warcraftrts.library.GameLayout.Program
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
