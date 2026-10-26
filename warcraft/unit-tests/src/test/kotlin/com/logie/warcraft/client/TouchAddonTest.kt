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
            function frame:SetText(text)
                self.text = text
                if self.scripts.OnTextChanged then self.scripts.OnTextChanged(self) end
            end
            function frame:GetText() return self.text end
            function frame:SetHeight(height) self.height = height end
            function frame:GetHeight() return self.height end
            function frame:ClearAllPoints() self.points = {} end
            function frame:SetPoint(...) self.points = { ... } end
            function frame:GetPoint() return (table.unpack or unpack)(self.points) end
            function frame:GetName() return self.name end
            function frame:Show() self.shown = true; if self.scripts.OnShow then self.scripts.OnShow(self) end end
            function frame:Hide() self.shown = false end
            function frame:IsShown() return self.shown end
            function frame:IsVisible() return self.shown end
            function frame:GetScript(script) return self.scripts[script] end
            -- (what only a window's looks need: nothing here)
            for _, method in ipairs({ "SetWidth", "SetFrameStrata", "SetToplevel", "EnableMouse", "SetBackdrop",
                    "SetBackdropColor", "SetMultiLine", "SetAutoFocus", "SetMaxLetters", "SetFontObject",
                    "SetScrollChild", "SetJustifyH", "SetAllPoints", "SetTexture", "SetTextColor" }) do
                frame[method] = function() end
            end
            function frame:SetAlpha(alpha) self.alpha = alpha end
            function frame:CreateTexture() return CreateFrame("Texture") end
            function frame:SetFocus() self.focused = true end
            function frame:ClearFocus() self.focused = false end
            function frame:CreateFontString() return CreateFrame("FontString") end
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
        UISpecialFrames = {}
        -- The game's scrolling text box (FrameXML's UIPanelTemplates.lua), as far as it reads the box:
        -- where its cursor is, as the text changes.
        function ScrollingEdit_OnTextChanged(self, scrollFrame)
            self.handleCursorChange = true
            ScrollingEdit_OnUpdate(self, 0, scrollFrame)
        end
        function ScrollingEdit_OnCursorChanged(self, x, y, w, h)
            self.cursorOffset = y
            self.cursorHeight = h
            self.handleCursorChange = true
        end
        function ScrollingEdit_OnUpdate(self, elapsed, scrollFrame)
            if self.handleCursorChange then
                local cursorOffset = -self.cursorOffset
                self.scrolledTo = cursorOffset - self.cursorHeight
                self.handleCursorChange = false
            end
        end
        -- The toolbar's Help button, as the game makes it.
        helped = 0
        HelpMicroButton = CreateFrame("Button", "HelpMicroButton")
        HelpMicroButton:SetScript("OnClick", function() helped = helped + 1 end)
        HelpMicroButton.tooltipText = "Help Request"
        -- The talents' window's buttons (the game's own add-on): a click spends a point.
        now = 100
        function GetTime() return now end
        modifier = false
        function IsModifierKeyDown() return modifier end
        learned, shownTooltips = {}, 0
        function talentButton(id)
            local button = CreateFrame("Button", "PlayerTalentFrameTalent" .. id)
            button:SetScript("OnClick", function(self, mouseButton) table.insert(learned, id .. " " .. mouseButton) end)
            button:SetScript("OnEnter", function(self) shownTooltips = shownTooltips + 1 end)
            return button
        end
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
        function ClearCursor() table.insert(picked, "clear"); cursor = nil end
        -- Bags and the cursor: an item picked up stays in its place (locked) while the cursor
        -- holds it; the game's question before destroying it, which closes when the cursor holds
        -- no item.
        function hooksecurefunc(name, hook)
            local original = _G[name]
            _G[name] = function(...)
                local results = { original(...) }
                hook(...)
                return (table.unpack or unpack)(results)
            end
        end
        bags = { [0] = { [1] = "|Hitem:2589|h[Linen Cloth]|h", [2] = "|Hitem:6948|h[Hearthstone]|h" } }
        equipped = { [5] = "|Hitem:49|h[Footpad's Vest]|h" }
        cursor = nil
        destroyed = {}
        function GetContainerItemLink(bag, slot) return bags[bag] and bags[bag][slot] end
        function GetInventoryItemLink(unit, slot) return equipped[slot] end
        function PickupContainerItem(bag, slot) cursor = { bag = bag, slot = slot, link = bags[bag][slot] } end
        function PickupInventoryItem(slot) cursor = { slot = slot, link = equipped[slot] } end
        function CursorHasItem() return cursor ~= nil end
        function GetCursorInfo() if cursor then return "item", 0, cursor.link end end
        function DeleteCursorItem()
            if not cursor then return end
            table.insert(destroyed, cursor.link)
            if cursor.bag then bags[cursor.bag][cursor.slot] = nil else equipped[cursor.slot] = nil end
            cursor = nil
        end
        hidden = {}
        function StaticPopup_Hide(which) table.insert(hidden, which) end
        StaticPopupDialogs = {
            DELETE_ITEM = {
                OnAccept = function(self) DeleteCursorItem() end,
                OnCancel = function(self) ClearCursor() end,
                OnUpdate = function(self) if not CursorHasItem() then StaticPopup_Hide("DELETE_ITEM") end end,
            },
            DELETE_GOOD_ITEM = {
                OnAccept = function(self) DeleteCursorItem() end,
                OnCancel = function(self) ClearCursor() end,
                OnUpdate = function(self) if not CursorHasItem() then StaticPopup_Hide("DELETE_GOOD_ITEM") end end,
                EditBoxOnEnterPressed = function(self) DeleteCursorItem() end,
            },
        }
    """.trimIndent()

    private val lua = JsePlatform.standardGlobals().apply {
        load(game).call()
        load(TouchAddon.files.getValue("WowTouch.lua")).call()
    }
    /** The add-on's own frame: the first it makes, after the stand-in's. */
    private val frame = (lua.get("frames") as LuaTable).let { frames ->
        (1..frames.length()).map { frames.get(it) }.first { it.get("name").isnil() && !it.get("scripts").get("OnUpdate").isnil() }
    }

    private fun event(name: String, arg: String? = null) = frame.get("scripts").get("OnEvent")
        .call(frame, LuaValue.valueOf(name), arg?.let { LuaValue.valueOf(it) } ?: LuaValue.NIL)
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

        // Tapped: the menu closes, the window asks what went wrong, the box empty and ready; what's
        // written goes to the realm on one line.
        button.get("scripts").get("OnClick").call(button)
        assertTrue(!menu.get("shown").toboolean())
        val window = lua.get("WowTouchReportFrame")
        assertTrue(window.get("shown").toboolean())
        val box = lua.get("WowTouchReportBox")
        assertEquals("", box.get("text").tojstring())
        assertTrue(box.get("focused").toboolean())
        box.set("text", LuaValue.valueOf("Moratari\nvanished"))
        val send = lua.get("WowTouchReportSend")
        send.get("scripts").get("OnClick").call(send)
        assertEquals("WowRemix|report Moratari vanished|WHISPER|Testhero", sent().last())
        assertTrue(!window.get("shown").toboolean())

        // Nothing written: the report still goes.
        button.get("scripts").get("OnClick").call(button)
        send.get("scripts").get("OnClick").call(send)
        assertEquals("WowRemix|report |WHISPER|Testhero", sent().last())

        // Cancelled: nothing goes.
        val before = sent().size
        button.get("scripts").get("OnClick").call(button)
        val cancel = lua.get("WowTouchReportCancel")
        cancel.get("scripts").get("OnClick").call(cancel)
        assertEquals(before, sent().size)
        assertTrue(!window.get("shown").toboolean())
    }

    @Test
    fun theReportBoxScrollsBeforeItsCursorHasMoved() {
        lua.set("realm", LuaValue.valueOf("Wrath Remix"))
        event("PLAYER_ENTERING_WORLD")
        val button = lua.get("GameMenuButtonWowTouchReport")
        // The window's first showing empties the box: its text changes before its cursor has moved.
        button.get("scripts").get("OnClick").call(button)
        val box = lua.get("WowTouchReportBox")
        assertEquals(0, box.get("scrolledTo").toint())
        // Written in: the cursor moves, then the text changes.
        box.get("scripts").get("OnCursorChanged").invoke(LuaValue.varargsOf(arrayOf(box, LuaValue.valueOf(0), LuaValue.valueOf(-28), LuaValue.valueOf(2), LuaValue.valueOf(14))))
        box.get("scripts").get("OnTextChanged").call(box)
        assertEquals(14, box.get("scrolledTo").toint())
    }

    @Test
    fun aLongReportGoesInPieces() {
        lua.set("realm", LuaValue.valueOf("Wrath Remix"))
        event("PLAYER_ENTERING_WORLD")
        val button = lua.get("GameMenuButtonWowTouchReport")
        button.get("scripts").get("OnClick").call(button)
        // 450 letters, one of them two bytes long where a 200-byte piece would end.
        val text = "a".repeat(199) + "é" + "b".repeat(250)
        lua.get("WowTouchReportBox").set("text", LuaValue.valueOf(text.toByteArray(Charsets.UTF_8)))
        val send = lua.get("WowTouchReportSend")
        send.get("scripts").get("OnClick").call(send)
        val pieces = sent().takeLast(3)
        assertEquals("WowRemix|report+ " + "a".repeat(199) + "|WHISPER|Testhero", pieces[0])
        assertTrue(pieces[1].startsWith("WowRemix|report+ é" + "b"))
        assertTrue(pieces[2].startsWith("WowRemix|report b"))
        val words = pieces.joinToString("") { it.split('|')[1].substringAfter(' ') }
        assertEquals(text, words)
    }

    @Test
    fun theHelpButtonReportsOnWrathRemixOnly() {
        event("PLAYER_ENTERING_WORLD")
        val help = lua.get("HelpMicroButton")
        help.get("scripts").get("OnClick").call(help)
        assertEquals(1, lua.get("helped").toint(), "ChromieCraft's Help button isn't the game's")
        assertEquals("Help Request", help.get("tooltipText").tojstring())

        lua.set("realm", LuaValue.valueOf("Wrath Remix"))
        event("PLAYER_LEAVING_WORLD")
        event("PLAYER_ENTERING_WORLD")
        help.get("scripts").get("OnClick").call(help)
        assertEquals(1, lua.get("helped").toint())
        assertEquals("Report Time Anomaly", help.get("tooltipText").tojstring())
        assertTrue(lua.get("WowTouchReportFrame").get("shown").toboolean())
    }

    @Test
    fun aTalentsFirstTapShowsItsTooltipAndTheSecondSpendsThePoint() {
        event("PLAYER_ENTERING_WORLD")
        // The talents' window loads when it's first opened.
        lua.load("talentButton(1); talentButton(2)").call()
        event("ADDON_LOADED", "Blizzard_TalentUI")
        val first = lua.get("PlayerTalentFrameTalent1")
        val second = lua.get("PlayerTalentFrameTalent2")
        fun click(button: LuaValue, mouseButton: String = "LeftButton") =
            button.get("scripts").get("OnClick").call(button, LuaValue.valueOf(mouseButton))
        fun learned(): List<String> = (lua.get("learned") as LuaTable).let { t -> (1..t.length()).map { t.get(it).tojstring() } }

        click(first)
        assertEquals(emptyList(), learned())
        assertEquals(1, lua.get("shownTooltips").toint())
        // Another talent: its tooltip, nothing spent.
        click(second)
        assertEquals(emptyList(), learned())
        // The first again, then again at once: spent the second time.
        click(first)
        lua.set("now", LuaValue.valueOf(100.4))
        click(first)
        assertEquals(listOf("1 LeftButton"), learned())
        // Long after: a first tap again.
        lua.set("now", LuaValue.valueOf(110))
        click(first)
        assertEquals(listOf("1 LeftButton"), learned())
        // A right click, or a click with a modifier (a link in chat), is the game's as it was.
        click(second, "RightButton")
        lua.set("modifier", LuaValue.TRUE)
        click(second)
        assertEquals(listOf("1 LeftButton", "2 RightButton", "2 LeftButton"), learned())
        // A second loading of the world wraps nothing twice.
        lua.set("modifier", LuaValue.FALSE)
        event("PLAYER_ENTERING_WORLD")
        lua.set("now", LuaValue.valueOf(120))
        click(first)
        lua.set("now", LuaValue.valueOf(120.3))
        click(first)
        assertEquals(4, learned().size)
    }

    @Test
    fun theMountsAndCompanionsDragToo() {
        lua.load("""
            PetPaperDollFrameCompanionFrame = window(16, 152, 330, 420)
            PetPaperDollFrameCompanionFrame.GetHitRectInsets = function(self) return 0, 0, 0, 0 end
        """.trimIndent()).call()
        event("PLAYER_ENTERING_WORLD")
        val alone = sent().last().split('|')[1]
        assertEquals("drag 156 1979 3379 7448 hold -", alone)
        // With the spellbook open as well: both.
        lua.get("spellbook").set("shown", LuaValue.TRUE)
        update(1.0)
        val both = sent().last().split('|')[1]
        assertEquals(2, InterfaceNote.dragAreas(both).size)
        // Closed: none.
        lua.load("PetPaperDollFrameCompanionFrame.shown = false; spellbook.shown = false").call()
        update(1.0)
        assertEquals("WowTouch|drag - hold -|WHISPER|Testhero", sent().last())
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
    fun theQuestionBeforeDestroyingAnItemStaysUntilItsAnswered() {
        fun destroyed(): List<String> = (lua.get("destroyed") as LuaTable).let { t -> (1..t.length()).map { t.get(it).tojstring() } }
        val popups = lua.get("StaticPopupDialogs")
        fun popup(which: String) = popups.get(which)
        // A tap picks the cloth up and puts it down in the world: the question; the app's right
        // click after it lets go of the cloth, which no longer closes the question.
        lua.load("PickupContainerItem(0, 1)").call()
        event("DELETE_ITEM_CONFIRM", "Linen Cloth")
        lua.load("ClearCursor()").call()
        popup("DELETE_ITEM").get("OnUpdate").let { if (!it.isnil()) it.call(popup("DELETE_ITEM")) }
        assertEquals(0, (lua.get("hidden") as LuaTable).length())
        // Yes: picked up again and destroyed.
        popup("DELETE_ITEM").get("OnAccept").call(popup("DELETE_ITEM"))
        assertEquals(listOf("|Hitem:2589|h[Linen Cloth]|h"), destroyed())
        assertTrue(lua.get("bags").get(0).get(1).isnil())

        // An equipped item, the question that asks for DELETE typed, answered with Enter.
        lua.load("PickupInventoryItem(5)").call()
        event("DELETE_ITEM_CONFIRM", "Footpad's Vest")
        lua.load("ClearCursor()").call()
        popup("DELETE_GOOD_ITEM").get("EditBoxOnEnterPressed").call(popup("DELETE_GOOD_ITEM"))
        assertEquals("|Hitem:49|h[Footpad's Vest]|h", destroyed().last())

        // Another item on the cursor by the time of Yes: it stays.
        lua.load("PickupContainerItem(0, 2)").call()
        event("DELETE_ITEM_CONFIRM", "Hearthstone")
        lua.load("ClearCursor(); cursor = { link = 'other' }").call()
        popup("DELETE_ITEM").get("OnAccept").call(popup("DELETE_ITEM"))
        assertEquals(2, destroyed().size)
        assertEquals("|Hitem:6948|h[Hearthstone]|h", lua.get("bags").get(0).get(2).tojstring())
    }

    @Test
    fun theRealmsFadeDarkensTheScreenWithItsWordsForAsLongAsItSays() {
        fun addonMessage(prefix: String, text: String, sender: String) = frame.get("scripts").get("OnEvent").invoke(
            arrayOf(frame, LuaValue.valueOf("CHAT_MSG_ADDON"), LuaValue.valueOf(prefix), LuaValue.valueOf(text),
                LuaValue.valueOf("WHISPER"), LuaValue.valueOf(sender)))
        event("PLAYER_ENTERING_WORLD")
        // Someone else's, or another add-on's: nothing.
        addonMessage("WowRemix", "fade 6000 Not from the realm.", "Otherhero")
        addonMessage("SomethingElse", "fade 6000 Not ours.", "Testhero")
        assertTrue(lua.get("WowTouchFadeFrame").isnil())

        addonMessage("WowRemix", "fade 6000 The lamp burns low.", "Testhero")
        val fade = lua.get("WowTouchFadeFrame")
        assertTrue(fade.get("shown").toboolean())
        assertEquals("The lamp burns low.", fade.get("words").get("text").tojstring())
        assertEquals(0.0, fade.get("alpha").todouble())
        val tick = fade.get("scripts").get("OnUpdate")
        tick.call(fade, LuaValue.valueOf(0.6))
        assertEquals(0.5, fade.get("alpha").todouble(), 0.001)  // coming up
        tick.call(fade, LuaValue.valueOf(2.0))
        assertEquals(1.0, fade.get("alpha").todouble(), 0.001)  // dark
        tick.call(fade, LuaValue.valueOf(2.65))
        assertEquals(0.5, fade.get("alpha").todouble(), 0.001)  // going, 0.75 s from the end
        assertTrue(fade.get("shown").toboolean())
        tick.call(fade, LuaValue.valueOf(1.0))
        assertTrue(!fade.get("shown").toboolean(), "gone after six seconds")

        // (one shorter than its coming and going is given the time for both)
        addonMessage("WowRemix", "fade 500", "Testhero")
        assertEquals("", fade.get("words").get("text").tojstring())
        assertEquals(2.7, fade.get("lasts").todouble(), 0.001)
    }

    @Test
    fun theOpeningsCurtainIsDarkAtOnceUntilTheRealmLiftsIt() {
        fun addonMessage(text: String) = frame.get("scripts").get("OnEvent").invoke(
            arrayOf(frame, LuaValue.valueOf("CHAT_MSG_ADDON"), LuaValue.valueOf("WowRemix"), LuaValue.valueOf(text), LuaValue.NIL, LuaValue.valueOf("Testhero")))
        addonMessage("curtain 30000 The Gilded Rose, Stormwind | Some months after the fall of the Lich King")
        val curtain = lua.get("WowTouchFadeFrame")
        assertTrue(curtain.get("shown").toboolean())
        assertEquals(1.0, curtain.get("alpha").todouble(), "dark at once")
        assertEquals("The Gilded Rose, Stormwind\nSome months after the fall of the Lich King", curtain.get("words").get("text").tojstring())
        val tick = curtain.get("scripts").get("OnUpdate")
        tick.call(curtain, LuaValue.valueOf(10.0))
        assertEquals(1.0, curtain.get("alpha").todouble(), 0.001, "still dark ten seconds on")
        addonMessage("lift 2000")
        tick.call(curtain, LuaValue.valueOf(1.0))
        assertEquals(0.5, curtain.get("alpha").todouble(), 0.001, "half gone a second into the lift")
        tick.call(curtain, LuaValue.valueOf(1.1))
        assertTrue(!curtain.get("shown").toboolean(), "gone after the lift")
        // Never lifted: it goes by itself when its time is up.
        addonMessage("curtain 5000")
        tick.call(curtain, LuaValue.valueOf(2.5))
        assertEquals(1.0, curtain.get("alpha").todouble(), 0.001)
        tick.call(curtain, LuaValue.valueOf(1.25))
        assertEquals(0.5, curtain.get("alpha").todouble(), 0.001, "fading out over its last two and a half seconds")
        tick.call(curtain, LuaValue.valueOf(1.5))
        assertTrue(!curtain.get("shown").toboolean())
        // A lift with nothing up does nothing.
        addonMessage("lift 1000")
        assertTrue(!curtain.get("shown").toboolean())
    }

    @Test
    fun theAddonTellsTheRealmOnceTheFramesHaveSettled() {
        fun settled() = sent().filter { it.startsWith("WowRemix|settled") }
        fun addonMessage(text: String) = frame.get("scripts").get("OnEvent").invoke(
            arrayOf(frame, LuaValue.valueOf("CHAT_MSG_ADDON"), LuaValue.valueOf("WowRemix"), LuaValue.valueOf(text), LuaValue.NIL, LuaValue.valueOf("Testhero")))
        // On ChromieCraft nothing is said, however smooth the frames.
        event("PLAYER_ENTERING_WORLD")
        repeat(60) { update(0.05) }
        assertEquals(0, settled().size)

        lua.set("realm", LuaValue.valueOf("Wrath Remix"))
        event("PLAYER_ENTERING_WORLD")
        // Choppy: a long frame every half second keeps it waiting.
        repeat(6) { update(0.5); repeat(10) { update(0.05) } }
        assertEquals(0, settled().size, "no word while frames hitch")
        // Smooth for a second and a half (two seconds in at least): said once.
        repeat(30) { update(0.05) }
        assertEquals(1, settled().size, "said once the frames run smoothly: ${sent()}")
        val took = settled()[0].substringAfter("settled ").substringBefore("|").toInt()
        assertTrue(took in 6900..7100, "with the time it took (${settled()[0]})")
        repeat(100) { update(0.05) }
        assertEquals(1, settled().size, "and not again")
        // The realm's curtain starts the watch again.
        addonMessage("curtain 30000 The Broken Front, Icecrown")
        repeat(41) { update(0.05) }
        assertEquals(2, settled().size, "said again after a curtain, once two smooth seconds are in")
        assertTrue(settled()[1].startsWith("WowRemix|settled 20"), settled()[1])
        // Leaving the world stops the watch; entering it starts another.
        addonMessage("curtain 30000")
        event("PLAYER_LEAVING_WORLD")
        repeat(100) { update(0.05) }
        assertEquals(2, settled().size)
        event("PLAYER_ENTERING_WORLD")
        repeat(41) { update(0.05) }
        assertEquals(3, settled().size)
    }

    /** A frame of an add-on made for a PC (TomTom's arrow, Immersion's parts), placed as it places it. */
    private fun pcFrame(name: String, vararg point: Any): LuaValue {
        lua.load("""
            local frame = CreateFrame("Frame", "$name")
            frame.userPlaced = false
            function frame:IsUserPlaced() return self.userPlaced end
            function frame:SetClampedToScreen(clamped) self.clamped = clamped end
            function frame:SetClampRectInsets(left, right, top, bottom) self.insets = { left, right, top, bottom } end
            function frame:GetEffectiveScale() return self.scale or 1 end
        """.trimIndent()).call()
        val frame = lua.get(name)
        frame.get("SetPoint").invoke(LuaValue.varargsOf(arrayOf(frame) + point.map {
            if (it is Int) LuaValue.valueOf(it) else if (it is LuaValue) it else LuaValue.valueOf(it.toString())
        }))
        return frame
    }

    private fun points(frame: LuaValue): List<String> =
        (1..frame.get("points").length()).map { frame.get("points").get(it).let { p -> if (p.istable()) "table" else p.tojstring() } }

    @Test
    fun tomTomsArrowGoesToTheTopOfTheScreenInTheMiddle() {
        val arrow = pcFrame("TomTomCrazyArrow", "CENTER", 0, 0)
        event("PLAYER_ENTERING_WORLD")
        assertEquals(listOf("TOP", "table", "TOP", "0", "-36"), points(arrow))
        assertEquals(lua.get("UIParent"), arrow.get("points").get(2))
        assertTrue(arrow.get("clamped").toboolean(), "kept on the screen")
        // Once: a later loading screen leaves it where it is (the player may have dragged it since).
        arrow.get("SetPoint").invoke(LuaValue.varargsOf(arrayOf(arrow, LuaValue.valueOf("LEFT"), LuaValue.valueOf(10), LuaValue.valueOf(20))))
        event("PLAYER_LEAVING_WORLD")
        event("PLAYER_ENTERING_WORLD")
        assertEquals(listOf("LEFT", "10", "20"), points(arrow))
    }

    @Test
    fun tomTomsArrowStaysWhereThePlayerDraggedIt() {
        val arrow = pcFrame("TomTomCrazyArrow", "TOPLEFT", 300, -200)
        arrow.set("userPlaced", LuaValue.TRUE)
        event("PLAYER_ENTERING_WORLD")
        assertEquals(listOf("TOPLEFT", "300", "-200"), points(arrow))
    }

    @Test
    fun immersionsChoicesAndTalkingBoxStayOnTheScreen() {
        lua.load("ImmersionSetup = { titleoffset = 500 }").call()
        val titles = pcFrame("ImmersionTitleButtons", "CENTER", "UIParent", "CENTER", 500, 0)
        val talkBox = pcFrame("ImmersionTalkBox", "BOTTOM", 0, 150)
        lua.load("ImmersionFrame = { TitleButtons = ImmersionTitleButtons, TalkBox = ImmersionTalkBox }").call()
        event("ADDON_LOADED", "Immersion")
        for (part in listOf(titles, talkBox)) {
            assertTrue(part.get("clamped").toboolean(), "kept on the screen")
            // 16 from its edges (the choices are wider than their list)
            assertEquals(listOf(-16, 16, 16, -16), (1..4).map { part.get("insets").get(it).toint() })
        }
        // The choices, 500 right of the middle, come in to 16 from the edge of a screen 1024 wide
        // (512 - 155 - 16), and Immersion keeps that place.
        assertEquals(listOf("CENTER", "UIParent", "CENTER", "341", "0"), points(titles))
        assertEquals(341, lua.get("ImmersionSetup").get("titleoffset").toint())
    }

    @Test
    fun immersionsChoicesComeInEachTimeTheyShow() {
        lua.load("ImmersionSetup = { titleoffset = -100 }").call()
        val titles = pcFrame("ImmersionTitleButtons", "CENTER", "UIParent", "CENTER", -100, 20)
        val talkBox = pcFrame("ImmersionTalkBox", "BOTTOM", 0, 150)
        lua.load("ImmersionFrame = { TitleButtons = ImmersionTitleButtons, TalkBox = ImmersionTalkBox }").call()
        event("PLAYER_ENTERING_WORLD")
        // Already on the screen: left where it is.
        assertEquals(listOf("CENTER", "UIParent", "CENTER", "-100", "20"), points(titles))
        // Bigger (Immersion's scale), dragged to the left edge: the next time they show, in again.
        titles.set("scale", LuaValue.valueOf(2))
        titles.get("SetPoint").invoke(LuaValue.varargsOf(arrayOf(titles, LuaValue.valueOf("CENTER"),
            lua.get("UIParent"), LuaValue.valueOf("CENTER"), LuaValue.valueOf(-300), LuaValue.valueOf(20))))
        titles.get("hooks").get(1).call(titles)
        // 1024 / 2 / 2 - 155 - 16
        assertEquals(listOf("CENTER", "table", "CENTER", "-85", "20"), points(titles))
        assertEquals(-85, lua.get("ImmersionSetup").get("titleoffset").toint())
    }

    @Test
    fun theTocLoadsTheScriptForThisGame() {
        val toc = TouchAddon.files.getValue("WowTouch.toc")
        assertEquals(AddonLayout.INTERFACE, AddonLayout.tocFields(toc)["Interface"]?.trim())
        assertTrue(toc.lines().contains("WowTouch.lua"))
        // After TomTom and Immersion, whose frames it fits to the screen.
        assertEquals("TomTom, Immersion", AddonLayout.tocFields(toc)["OptionalDeps"]?.trim())
    }
}
