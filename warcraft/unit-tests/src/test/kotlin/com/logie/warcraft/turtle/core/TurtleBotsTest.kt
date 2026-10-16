package com.logie.warcraft.turtle.core

import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The bots' settings (TortoiseBots' aiplayerbot.conf) as the launcher writes them. */
class TurtleBotSettingsTest {
    @Test
    fun aPhonesShareOfWorldBots() {
        assertTrue(TurtleRealm.WORLD_BOTS_DEFAULT in TurtleRealm.WORLD_BOTS_CHOICES)
        val settings = TurtleRealm.botSettings(TurtleRealm.WORLD_BOTS_DEFAULT)
        assertEquals("1", settings["AiPlayerbot.Enabled"])
        assertEquals("40", settings["AiPlayerbot.MinRandomBots"])
        assertEquals("40", settings["AiPlayerbot.MaxRandomBots"])
        assertEquals("1", settings["AiPlayerbot.RandomBotAutologin"])
        assertEquals("0", settings["AiPlayerbot.Observability"])
    }

    @Test
    fun noneInTheWorldLeavesHiredBotsAndAlts() {
        val settings = TurtleRealm.botSettings(0)
        assertEquals("1", settings["AiPlayerbot.Enabled"])
        assertEquals("0", settings["AiPlayerbot.MaxRandomBots"])
        assertEquals("0", settings["AiPlayerbot.RandomBotAutologin"])
    }

    @Test
    fun neverMoreThanThePhonesMost() {
        assertEquals(TurtleRealm.WORLD_BOTS_CHOICES.max().toString(), TurtleRealm.botSettings(500)["AiPlayerbot.MaxRandomBots"])
        assertEquals("0", TurtleRealm.botSettings(-3)["AiPlayerbot.MaxRandomBots"])
    }

    /** Turtle's ctl.sh sets the same keys on its own (CI's realm test reads them), with the same default. */
    @Test
    fun ctlShSetsTheSameKeys() {
        val ctl = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "warcraft/turtle/server/ctl.sh") }.first { it.isFile }.readText()
        val keys = Regex("setkey \"\\\$conf\" (AiPlayerbot\\.[A-Za-z.]+)").findAll(ctl).map { it.groupValues[1] }.toSet()
        assertEquals(TurtleRealm.botSettings(1).keys, keys)
        assertTrue("WORLD_BOTS:-${TurtleRealm.WORLD_BOTS_DEFAULT}}" in ctl, "ctl.sh's default isn't the app's")
    }
}

/** The bots' control addon put into Turtle's copy of the client. */
class TurtleAddonsTest {
    private lateinit var base: File
    private lateinit var layout: TurtleLayout
    private lateinit var fs: SafeFs
    private lateinit var addons: File

    @BeforeTest
    fun setUp() {
        base = createTempDirectory("turtle-addons").toFile()
        layout = TurtleLayout(File(base, "files").apply { mkdirs() }, base)
        fs = SafeFs(layout.root, layout.protectedFolders)
        addons = File(layout.server, "addons/TortoiseBotsManager").apply { mkdirs() }
        File(addons, "TortoiseBotsManager.toc").writeText("## Interface: 11200\nCore.lua\n")
        File(addons, "Core.lua").writeText("-- v1\n")
        File(layout.client, "Interface/Addons").mkdirs() // as some copies of the client spell it
        File(layout.client, "WoW.exe").writeText("exe")
    }

    @AfterTest
    fun tearDown() {
        base.walkBottomUp().forEach { it.delete() }
    }

    private fun install() = TurtleAddons.install(addons.parentFile, layout.client, fs)

    @Test
    fun putsTheAddonInTheClientsAddOnsWhateverItsCase() {
        assertEquals(listOf("TortoiseBotsManager"), install())
        val target = File(layout.client, "Interface/Addons/TortoiseBotsManager")
        assertEquals("-- v1\n", File(target, "Core.lua").readText())
        assertTrue(File(target, "TortoiseBotsManager.toc").isFile)
        // Nothing left beside it, and no second AddOns folder.
        assertEquals(listOf("TortoiseBotsManager"), File(layout.client, "Interface/Addons").list()!!.toList())
        assertEquals(listOf("Addons"), File(layout.client, "Interface").list()!!.toList())
    }

    @Test
    fun anUnchangedAddonIsLeftAlone() {
        install()
        val target = File(layout.client, "Interface/Addons/TortoiseBotsManager")
        val stamp = File(target, "Core.lua").lastModified()
        assertEquals(emptyList(), install())
        assertEquals(stamp, File(target, "Core.lua").lastModified())
    }

    @Test
    fun aNewerAddonReplacesTheOldOneWhole() {
        install()
        val target = File(layout.client, "Interface/Addons/TortoiseBotsManager")
        File(target, "Stale.lua").writeText("old")
        File(addons, "Core.lua").writeText("-- v2\n")
        assertEquals(listOf("TortoiseBotsManager"), install())
        assertEquals("-- v2\n", File(target, "Core.lua").readText())
        assertFalse(File(target, "Stale.lua").exists())
        assertFalse(File(target.path + ".old").exists())
    }

    @Test
    fun makesAddOnsWhenTheClientHasNone() {
        File(layout.client, "Interface/Addons").delete()
        install()
        assertTrue(File(layout.client, "Interface/AddOns/TortoiseBotsManager/Core.lua").isFile)
    }

    @Test
    fun refusesALinkInAnAddon() {
        val outside = File(layout.files, "mysql/secret").apply { parentFile.mkdirs(); writeText("wrath") }
        Files.createSymbolicLink(File(addons, "Data.lua").toPath(), outside.toPath())
        assertFailsWith<java.io.IOException> { install() }
        assertEquals("wrath", outside.readText())
        assertFalse(File(layout.client, "Interface/Addons/TortoiseBotsManager").exists())
    }

    @Test
    fun aClientOutsideTurtlesFolderIsRefused() {
        val elsewhere = File(layout.files, "client").apply { File(this, "Interface/AddOns").mkdirs() }
        assertFailsWith<SafeFs.Refused> { TurtleAddons.install(addons.parentFile, elsewhere, fs) }
        assertEquals(listOf("AddOns"), File(elsewhere, "Interface").list()!!.toList())
        assertTrue(File(elsewhere, "Interface/AddOns").list()!!.isEmpty())
    }
}
