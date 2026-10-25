package com.logie.warcrafts.library

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Wc3ScriptTest {
    /** Shaped like Blizzard's InstallerFileList.xml, cut down. */
    private val script = """
        <?xml version="1.0" encoding="UTF-8"?>
        <install>
        <if true_condition="Win32"><replace symbol="Launch" with="Warcraft III.exe" /></if>
        <if true_condition="German"><then><replace symbol="X" with="Y" /></then><else><replace symbol="X" with="Z" /></else></if>
        <disc with_title="{DiscTitle1}" with_file="{Tome1}" name="{DiscName1}">
        <archive origin="disc" input="#INPUT#" path="{Tome1}">
          <if true_condition="MacOS">
            <target location="user">
              <repack_into type="file"><repack from="MAC\War3.app" to="Warcraft III.app" size="1" /></repack_into>
            </target>
          </if>
          <if true_condition="Win32">
            <target location="user">
              <repack_into type="file" conflict="replace">
                <repack from="Common\War3.mpq" to="War3.mpq" size="10" />
                <repack from="Common\Maps\(2)BootyBay.w3m" to="Maps\(2)BootyBay.w3m" size="2" />
                <repack from="Common\Movies\HumanEd.mpq" to="movies\HumanEd.mpq" size="3" />
                <repack from="PC\Game.dll" to="Game.dll" size="4" />
              </repack_into>
            </target>
            <target location="user">
              <repack_into type="mpq" container="war3.mpq" options="disable_compression/1">
                <repack from="PC-100\Game.dll" to="Game.dll" size="4" />
              </repack_into>
            </target>
            <target location="uninstall">
              <repack_into conflict="replace" uninstall="defer" type="file">
                <repack input0="Files\Win32\Uninstall\Uninstall.exe" to="Uninstall.exe" size="5" />
              </repack_into>
            </target>
          </if>
          <target location="user">
            <repack_into type="mpq" container="war3.mpq">
              <encrypt object="cdkey26" into="font\font.gid" prompt="{CDKeyProductName}" product_id="14" />
              <encrypt object="user"    into="font\font.clh" />
              <repack from="Files\font\font.ccd" to="font\font.ccd" size="6" type="cdkey1encode" />
            </repack_into>
          </target>
        </archive>
        </disc>
        <if true_condition="Win32">
          <target destination="user">
            <registry_key where="machine" key_name="Warcraft III" value_name="InstallPath" target_path="" />
          </target>
        </if>
        </install>
    """.trimIndent()

    @Test
    fun windowsFilesAndArchiveAdditionsWithoutKeyOrUninstaller() {
        val plan = Wc3Script.plan(script)
        assertEquals(
            listOf(
                Wc3Script.Step("Common\\War3.mpq", "War3.mpq"),
                Wc3Script.Step("Common\\Maps\\(2)BootyBay.w3m", "Maps\\(2)BootyBay.w3m"),
                Wc3Script.Step("Common\\Movies\\HumanEd.mpq", "movies\\HumanEd.mpq"),
                Wc3Script.Step("PC\\Game.dll", "Game.dll"),
                // war3.mpq is the War3.mpq copied above.
                Wc3Script.Step("PC-100\\Game.dll", "Game.dll", archive = "War3.mpq"),
            ),
            plan,
        )
    }

    @Test
    fun spellingsOfWhatIsAlreadyInstalledWin() {
        // The Frozen Throne after Reign of Chaos: its "Movies" is Reign of Chaos's "movies".
        val plan = Wc3Script.plan(script, alreadyThere = listOf("movies", "WAR3.MPQ"))
        assertEquals("movies\\HumanEd.mpq", plan[2].to)
        assertEquals("WAR3.MPQ", plan[0].to)
        assertEquals("WAR3.MPQ", plan[4].archive)
    }

    @Test
    fun planTextIsTabSeparated() {
        val text = Wc3Script.planText(listOf(Wc3Script.Step("PC\\Game.dll", "Game.dll"), Wc3Script.Step("a", "b", "War3.mpq")))
        assertEquals("F\tPC\\Game.dll\tGame.dll\nM\tWar3.mpq\ta\tb\n", text)
    }

    /**
     * Blizzard's own scripts, when given: W3_SCRIPTS=<Reign of Chaos's>:<The Frozen Throne's>
     * (W3_PLANS=<folder> writes the two plans there, for trying wc3unpack on this machine).
     */
    @Test
    fun blizzardsScripts() {
        val paths = System.getenv("W3_SCRIPTS")?.split(':') ?: return
        val roc = Wc3Script.plan(File(paths[0]).readText())
        assertTrue(roc.any { it.from == "Common\\War3.mpq" && it.to == "War3.mpq" })
        assertTrue(roc.any { it.from == "PC\\Warcraft III.exe" && it.to == "Warcraft III.exe" })
        assertTrue(roc.filter { it.archive != null }.all { it.archive == "War3.mpq" })
        assertTrue(roc.none { it.from.startsWith("MAC") || it.from.startsWith("Files\\") })
        val tft = Wc3Script.plan(File(paths[1]).readText(), alreadyThere = roc.filter { it.archive == null }.map { it.to })
        assertTrue(tft.any { it.from == "PC\\Frozen Throne.exe" })
        assertTrue(tft.any { it.to == "War3x.mpq" && it.archive == null })
        assertTrue(tft.filter { it.archive != null }.all { it.archive == "War3x.mpq" })
        System.getenv("W3_PLANS")?.let { dir ->
            File(dir, "roc.plan").writeText(Wc3Script.planText(roc))
            File(dir, "tft.plan").writeText(Wc3Script.planText(tft))
        }
    }
}
