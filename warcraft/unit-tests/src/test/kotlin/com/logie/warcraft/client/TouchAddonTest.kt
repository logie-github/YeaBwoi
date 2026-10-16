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
        realm = "ChromieCraft"
        function GetRealmName() return realm end
        frames = {}
        -- A frame: its scripts and events, its text, its height and where it's anchored.
        function CreateFrame(kind, name, parent, template)
            local frame = { scripts = {}, events = {}, hooks = {}, points = {}, height = 21, shown = true, name = name }
            function frame:SetScript(script, fn) self.scripts[script] = fn end
            function frame:HookScript(script, fn) table.insert(self.hooks, fn) end
            function frame:RegisterEvent(event) self.events[event] = true end
            function frame:SetText(text) self.text = text end
            function frame:GetText() return self.text end
            function frame:SetHeight(height) self.height = height end
            function frame:GetHeight() return self.height end
            function frame:ClearAllPoints() self.points = {} end
            function frame:SetPoint(...) self.points = { ... } end
            function frame:GetPoint() return (table.unpack or unpack)(self.points) end
            function frame:GetName() return self.name end
            function frame:Show() self.shown = true end
            function frame:Hide() self.shown = false end
            table.insert(frames, frame)
            if name then _G[name] = frame end
            return frame
        end
        -- The game's menu (its Macros and Log Out buttons) and its popups.
        GameMenuFrame = CreateFrame("Frame", "GameMenuFrame")
        GameMenuFrame.height = 292
        GameMenuButtonMacros = CreateFrame("Button", "GameMenuButtonMacros", GameMenuFrame)
        GameMenuButtonLogout = CreateFrame("Button", "GameMenuButtonLogout", GameMenuFrame)
        GameMenuButtonLogout:SetPoint("TOP", GameMenuButtonMacros, "BOTTOM", 0, -16)
        function HideUIPanel(frame) frame:Hide() end
        function PlaySound(sound) end
        StaticPopupDialogs = {}
        popup = CreateFrame("Frame", "StaticPopup1")
        popupBox = CreateFrame("EditBox", "StaticPopup1EditBox", popup)
        function popupBox:SetFocus() end
        function popupBox:GetParent() return popup end
        -- Windows and action buttons, as plain tables: a window's place, a button's action and
        -- what the add-on sets and hooks on it.
        function window(left, bottom, width, height)
            return {
                shown = true,
                IsVisible = function(self) return self.shown end,
                GetRect = function(self) return left, bottom, width, height end,
                GetEffectiveScale = function(self) return 1 end,
            }
        end
        function actionButton(slot)
            return {
                action = slot, attributes = {}, hooks = {},
                SetAttribute = function(self, name, value) self.attributes[name] = value end,
                HookScript = function(self, script, fn) self.hooks[script] = fn end,
            }
        end
        inCombat = false
        function InCombatLockdown() return inCombat end
        actions = { [1] = true, [13] = false }
        picked = {}
        function HasAction(slot) return actions[slot] == true end
        function PickupAction(slot) table.insert(picked, slot) end
        function ClearCursor() table.insert(picked, "clear") end
        function StaticPopup_Show(which)
            popup.which = which
            popup:Show()
            StaticPopupDialogs[which].OnShow(popup)
        end
    """.trimIndent()

    private val lua = JsePlatform.standardGlobals().apply {
        load(game).call()
        load(TouchAddon.files.getValue("WowTouch.lua")).call()
    }
    /** The add-on's own frame: the first it makes, after the stand-in's. */
    private val frame = (lua.get("frames") as LuaTable).let { frames ->
        (1..frames.length()).map { frames.get(it) }.first { it.get("name").isnil() && !it.get("scripts").get("OnUpdate").isnil() }
    }

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
        assertEquals(listOf("WowTouch|drag - hold -|WHISPER|Testhero"), sent())
        update(1.0) // Nothing changed: nothing said.
        assertEquals(1, sent().size)

        lua.get("spellbook").set("shown", LuaValue.TRUE)
        update(1.0)
        assertEquals("WowTouch|drag 156 2891 3613 8646 hold -|WHISPER|Testhero", sent().last())

        // What the app makes of it: the spellbook from the top left, inside its edges.
        val note = sent().last().split('|')[1]
        val area = InterfaceNote.dragAreas(note).single()
        assertTrue(area.contains(0.1f, 0.5f))
        assertTrue(!area.contains(0.1f, 0.05f) && !area.contains(0.5f, 0.5f) && !area.contains(0.1f, 0.8f))

        // Bigger interface: the same place in fractions of the screen moves with it.
        lua.get("spellbook").set("scale", LuaValue.valueOf(1.25))
        update(1.0)
        assertEquals("WowTouch|drag 195 3613 4517 10807 hold -|WHISPER|Testhero", sent().last())

        lua.get("spellbook").set("shown", LuaValue.FALSE)
        update(1.0)
        assertEquals("WowTouch|drag - hold -|WHISPER|Testhero", sent().last())
    }

    @Test
    fun theMenuHasReportTimeAnomalyOnlyOnWrathRemix() {
        event("PLAYER_ENTERING_WORLD")
        assertTrue(lua.get("GameMenuButtonWowTouchReport").isnil(), "a button on ChromieCraft")
        assertEquals(292, lua.get("GameMenuFrame").get("height").toint())
    }

    @Test
    fun reportTimeAnomalyAsksTheRealmForAReport() {
        lua.set("realm", LuaValue.valueOf("Wrath Remix"))
        event("PLAYER_ENTERING_WORLD")
        val button = lua.get("GameMenuButtonWowTouchReport")
        assertEquals("Report Time Anomaly", button.get("text").tojstring())
        // In Log Out's place under Macros, Log Out under it, the menu taller by as much.
        val menu = lua.get("GameMenuFrame")
        val logout = lua.get("GameMenuButtonLogout")
        assertEquals(lua.get("GameMenuButtonMacros"), button.get("points").get(2))
        assertEquals(button, logout.get("points").get(2))
        assertEquals(292 + 21 + 16, menu.get("height").toint())
        // A second entering of the world (a loading screen): the same button, the same menu.
        event("PLAYER_LEAVING_WORLD")
        event("PLAYER_ENTERING_WORLD")
        assertEquals(292 + 21 + 16, menu.get("height").toint())

        // Tapped: the menu closes, the popup asks what went wrong; what's written goes to the
        // realm on one line.
        button.get("scripts").get("OnClick").call(button)
        assertTrue(!menu.get("shown").toboolean())
        assertEquals("WOWTOUCH_TIME_ANOMALY", lua.get("popup").get("which").tojstring())
        val box = lua.get("popupBox")
        box.set("text", LuaValue.valueOf("Moratari\nvanished"))
        val dialog = lua.get("StaticPopupDialogs").get("WOWTOUCH_TIME_ANOMALY")
        dialog.get("OnAccept").call(lua.get("popup"))
        assertEquals("WowRemix|report Moratari vanished|WHISPER|Testhero", sent().last())

        // Nothing written: the report still goes.
        lua.get("StaticPopup_Show").call(LuaValue.valueOf("WOWTOUCH_TIME_ANOMALY"))
        assertEquals("", box.get("text").tojstring())
        dialog.get("EditBoxOnEnterPressed").call(box)
        assertEquals("WowRemix|report |WHISPER|Testhero", sent().last())
        assertTrue(!lua.get("popup").get("shown").toboolean())
    }

    @Test
    fun itSaysWhereAHoldIsARightClick() {
        // An open bag on the right, the main action bar along the bottom, a bar on the right edge.
        lua.load("""
            ContainerFrame1 = window(800, 100, 200, 300)
            ContainerFrame2 = window(800, 420, 200, 100)
            MainMenuBar = window(0, 0, 1024, 53)
            MultiBarRight = window(980, 200, 44, 400)
        """.trimIndent()).call()
        event("PLAYER_ENTERING_WORLD")
        val note = sent().last().split('|')[1]
        assertEquals("drag - hold 7813 1302 9766 6771 0 0 10000 690 9570 2604 10000 7813", note)
        val holds = InterfaceNote.holdAreas(note)
        assertTrue(holds[0].contains(0.85f, 0.5f) && holds[1].contains(0.5f, 0.97f))

        // The bag closes: said again.
        lua.load("ContainerFrame1.shown = false; ContainerFrame2.shown = false").call()
        update(1.0)
        assertEquals("WowTouch|drag - hold 0 0 10000 690 9570 2604 10000 7813|WHISPER|Testhero", sent().last())
    }

    @Test
    fun aRightClickOnAnActionBarsButtonTakesItOffTheBar() {
        lua.load("ActionButton1 = actionButton(1); MultiBarRightButton1 = actionButton(13); inCombat = true").call()
        // (in combat, nothing is set: once it's over)
        event("PLAYER_ENTERING_WORLD")
        val button = lua.get("ActionButton1")
        assertTrue(button.get("attributes").get("type2").isnil())
        lua.set("inCombat", LuaValue.FALSE)
        event("PLAYER_REGEN_ENABLED")
        assertEquals("none", button.get("attributes").get("type2").tojstring())

        val postClick = button.get("hooks").get("PostClick")
        postClick.call(button, LuaValue.valueOf("LeftButton"))
        postClick.call(button, LuaValue.valueOf("RightButton"))
        // An empty button: nothing to take.
        val empty = lua.get("MultiBarRightButton1")
        empty.get("hooks").get("PostClick").call(empty, LuaValue.valueOf("RightButton"))
        val picked = lua.get("picked") as LuaTable
        assertEquals(listOf("1", "clear"), (1..picked.length()).map { picked.get(it).tojstring() })
    }

    @Test
    fun theTocLoadsTheScriptForThisGame() {
        val toc = TouchAddon.files.getValue("WowTouch.toc")
        assertEquals(AddonLayout.INTERFACE, AddonLayout.tocFields(toc)["Interface"]?.trim())
        assertTrue(toc.lines().contains("WowTouch.lua"))
    }
}
