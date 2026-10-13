package com.logie.warcraft.client

import com.logie.warcraft.controls.InterfaceNote
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.lib.jse.JsePlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TouchAddonTest {
    /** The game's API as far as the add-on uses it: a 1024 x 768 screen (UI units), the spellbook. */
    private val game = """
        sent = {}
        function SendAddonMessage(prefix, text, kind, target)
            table.insert(sent, prefix .. "|" .. text .. "|" .. kind .. "|" .. target)
        end
        function UnitName(unit) return "Testhero" end
        function GetScreenWidth() return 1024 end
        function GetScreenHeight() return 768 end
        UIParent = { GetEffectiveScale = function(self) return 1 end }
        spellbook = { shown = false, left = 16, bottom = 152, width = 384, height = 512, scale = 1 }
        SpellBookFrame = {
            IsVisible = function(self) return spellbook.shown end,
            GetRect = function(self) return spellbook.left, spellbook.bottom, spellbook.width, spellbook.height end,
            GetHitRectInsets = function(self) return 0, 30, 0, 70 end,
            GetEffectiveScale = function(self) return spellbook.scale end,
        }
        frames = {}
        function CreateFrame(kind)
            local frame = { scripts = {}, events = {} }
            function frame:SetScript(name, fn) self.scripts[name] = fn end
            function frame:RegisterEvent(event) self.events[event] = true end
            table.insert(frames, frame)
            return frame
        end
    """.trimIndent()

    private val lua = JsePlatform.standardGlobals().apply {
        load(game).call()
        load(TouchAddon.files.getValue("WowTouch.lua")).call()
    }
    private val frame = (lua.get("frames") as LuaTable).get(1)

    private fun event(name: String) = frame.get("scripts").get("OnEvent").call(frame, LuaValue.valueOf(name))
    private fun update(seconds: Double) = frame.get("scripts").get("OnUpdate").call(frame, LuaValue.valueOf(seconds))
    private fun sent(): List<String> {
        val table = lua.get("sent") as LuaTable
        return (1..table.length()).map { table.get(it).tojstring() }
    }

    @Test
    fun itSaysWhereTheSpellbookIsWhenItChanges() {
        update(1.0) // Before the world: nothing.
        assertEquals(emptyList(), sent())
        event("PLAYER_ENTERING_WORLD")
        assertEquals(listOf("WowTouch|drag -|WHISPER|Testhero"), sent())
        update(1.0) // Nothing changed: nothing said.
        assertEquals(1, sent().size)

        lua.get("spellbook").set("shown", LuaValue.TRUE)
        update(1.0)
        assertEquals("WowTouch|drag 156 2891 3613 8646|WHISPER|Testhero", sent().last())

        // What the app makes of it: the spellbook from the top left, inside its edges.
        val note = sent().last().split('|')[1]
        val area = InterfaceNote.dragAreas(note).single()
        assertTrue(area.contains(0.1f, 0.5f))
        assertTrue(!area.contains(0.1f, 0.05f) && !area.contains(0.5f, 0.5f) && !area.contains(0.1f, 0.8f))

        // Bigger interface: the same place in fractions of the screen moves with it.
        lua.get("spellbook").set("scale", LuaValue.valueOf(1.25))
        update(1.0)
        assertEquals("WowTouch|drag 195 3613 4517 10807|WHISPER|Testhero", sent().last())

        lua.get("spellbook").set("shown", LuaValue.FALSE)
        update(1.0)
        assertEquals("WowTouch|drag -|WHISPER|Testhero", sent().last())
    }

    @Test
    fun theTocLoadsTheScriptForThisGame() {
        val toc = TouchAddon.files.getValue("WowTouch.toc")
        assertEquals(AddonLayout.INTERFACE, AddonLayout.tocFields(toc)["Interface"]?.trim())
        assertTrue(toc.lines().contains("WowTouch.lua"))
    }
}
