// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.client

/**
 * The app's own add-on, put into the client's `Interface/AddOns` before each start (and left out
 * of the add-on manager's list): it tells the touch controls where the spellbook is while it's
 * open, so a drag that starts on it drags a spell, as the mouse does on a PC; and where the
 * game's windows of items, talents and abilities are (the bags, the bank, the character pane, the
 * talents, the action bars), where a hold is a right click.
 *
 * An add-on can't reach the app directly, so it says it to the realm on the phone: an add-on
 * message (prefix `WowTouch`) whispered to the player itself, which the realm keeps for the app
 * instead of passing on (patches/azerothcore/0009, `files/run/interface`). It says it again only
 * when it changes: "drag <left> <bottom> <right> <top> hold <left> <bottom> <right> <top> ..." in
 * ten-thousandths of the screen from its bottom left (the spellbook inside its own edges; each
 * group of windows as one area: the bags, the bank, the character pane, the talents, the bars at
 * the bottom, the bars on the right), "-" for none.
 *
 * A right click on an action bar's button (a hold, from the app) takes its action off the bar, as
 * dragging it away does on a PC, and does nothing else.
 *
 * On the Wrath Remix realm it also puts **Report Time Anomaly** in the game's menu, under Macros:
 * it asks what went wrong (the player may write nothing) and asks the realm for a report, in an
 * add-on message to the player itself (prefix `WowRemix`, "report <what they wrote>"). The realm
 * writes the report (mod-wrath-remix, RemixReport.cpp) and the app sends it to GitHub
 * ([com.logie.warcraft.AnomalyReports]).
 */
object TouchAddon {
    const val FOLDER = "WowTouch"
    const val PREFIX = "WowTouch"
    const val REMIX_PREFIX = "WowRemix"
    /** The realm whose menu has Report Time Anomaly, as the game names it ([com.logie.warcraft.Realms]). */
    const val REMIX_REALM = "Wrath Remix"

    val files: Map<String, String> = mapOf(
        "$FOLDER.toc" to """
            ## Interface: ${AddonLayout.INTERFACE}
            ## Title: WowTouch
            ## Notes: The World of Warcraft app's touch controls: where the spellbook is, for dragging its spells; holds as right clicks on items, talents and abilities; Report Time Anomaly on Wrath Remix.
            ## Author: World of Warcraft app
            ## Version: 3
            $FOLDER.lua
        """.trimIndent() + "\n",
        "$FOLDER.lua" to """
            -- The World of Warcraft app's touch controls (installed by the app at each start). Says
            -- where the spellbook is while it's open, so a drag that starts on it drags a spell, as
            -- the mouse does on a PC, and where the windows of items, talents and abilities are,
            -- where a hold is a right click: in an add-on message to the player itself, which the
            -- realm on the phone keeps for the app. A right click on an action bar's button takes
            -- it off the bar. On Wrath Remix, Report Time Anomaly in the game's menu.

            local said
            local inWorld = false

            -- A share of the screen, in ten-thousandths.
            local function share(value)
                return math.floor(value * 10000 + 0.5)
            end

            -- A window's place inside its edges, in ten-thousandths of the screen from its bottom left.
            local function place(frame)
                if not frame or not frame:IsVisible() then return "-" end
                local left, bottom, width, height = frame:GetRect()
                if not left then return "-" end
                local insetLeft, insetRight, insetTop, insetBottom = frame:GetHitRectInsets()
                local scale = frame:GetEffectiveScale()
                local screenWidth = GetScreenWidth() * UIParent:GetEffectiveScale()
                local screenHeight = GetScreenHeight() * UIParent:GetEffectiveScale()
                return string.format("%d %d %d %d",
                    share((left + insetLeft) * scale / screenWidth),
                    share((bottom + insetBottom) * scale / screenHeight),
                    share((left + width - insetRight) * scale / screenWidth),
                    share((bottom + height - insetTop) * scale / screenHeight))
            end

            -- Some frames, nil ones too: { n = how many, ... }.
            local function frames(...)
                return { n = select("#", ...), ... }
            end

            -- Shown windows' places together, as one area: "<left> <bottom> <right> <top>", or nil.
            local function around(list)
                local left, bottom, right, top
                local screenWidth = GetScreenWidth() * UIParent:GetEffectiveScale()
                local screenHeight = GetScreenHeight() * UIParent:GetEffectiveScale()
                for i = 1, list.n do
                    local frame = list[i]
                    if frame and frame:IsVisible() then
                        local l, b, w, h = frame:GetRect()
                        if l then
                            local scale = frame:GetEffectiveScale()
                            l, b, w, h = l * scale / screenWidth, b * scale / screenHeight, w * scale / screenWidth, h * scale / screenHeight
                            left = math.min(left or l, l)
                            bottom = math.min(bottom or b, b)
                            right = math.max(right or l + w, l + w)
                            top = math.max(top or b + h, b + h)
                        end
                    end
                end
                if not left then return nil end
                return string.format("%d %d %d %d", share(left), share(bottom), share(right), share(top))
            end

            local function bags()
                local list = { n = NUM_CONTAINER_FRAMES or 13 }
                for i = 1, list.n do list[i] = _G["ContainerFrame" .. i] end
                return list
            end

            -- Where a hold is a right click: the bags, the bank, the character pane, the talents,
            -- the action bars at the bottom and on the right.
            local function holds()
                local areas = {}
                for _, list in ipairs({
                    bags(),
                    frames(BankFrame),
                    frames(CharacterFrame),
                    frames(PlayerTalentFrame),
                    frames(MainMenuBar, MultiBarBottomLeft, MultiBarBottomRight, ShapeshiftBarFrame, PetActionBarFrame),
                    frames(MultiBarRight, MultiBarLeft),
                }) do
                    local area = around(list)
                    if area then table.insert(areas, area) end
                end
                return #areas > 0 and table.concat(areas, " ") or "-"
            end

            local function tell()
                if not inWorld then return end
                local note = "drag " .. place(SpellBookFrame) .. " hold " .. holds()
                if note ~= said then
                    said = note
                    SendAddonMessage("$PREFIX", note, "WHISPER", UnitName("player"))
                end
            end

            -- A right click on an action bar's button (a hold, from the app) takes its action off the
            -- bar and does nothing else: its right click has no action of its own ("type2"), and
            -- after it the action is picked up and let go of. Set out of combat, as the game asks.
            local barButtons = { "ActionButton", "BonusActionButton", "MultiBarBottomLeftButton",
                "MultiBarBottomRightButton", "MultiBarRightButton", "MultiBarLeftButton" }
            local barsReady = false

            local function takeOff(button, mouseButton)
                if mouseButton ~= "RightButton" then return end
                local slot = button.action or (ActionButton_GetPagedID and ActionButton_GetPagedID(button))
                if slot and HasAction(slot) then
                    PickupAction(slot)
                    ClearCursor()
                end
            end

            local function readyBars()
                if barsReady or InCombatLockdown() then return end
                for _, name in ipairs(barButtons) do
                    for i = 1, 12 do
                        local button = _G[name .. i]
                        if button then
                            button:SetAttribute("type2", "none")
                            button:HookScript("PostClick", takeOff)
                        end
                    end
                end
                barsReady = true
            end

            local watcher = CreateFrame("Frame")
            local wait = 0
            watcher:SetScript("OnUpdate", function(self, elapsed)
                wait = wait - elapsed
                if wait > 0 then return end
                wait = 0.1
                tell()
            end)
            -- On the Wrath Remix realm: Report Time Anomaly in the game's menu, under Macros. It asks
            -- what went wrong, then asks the realm for a report of the Timerunner's way so far, which
            -- the app sends to GitHub.
            local reportButton
            local menuHeight

            local function report(text)
                text = string.gsub(text or "", "[%c]", " ")
                SendAddonMessage("$REMIX_PREFIX", "report " .. string.sub(text, 1, 200), "WHISPER", UnitName("player"))
            end

            StaticPopupDialogs["WOWTOUCH_TIME_ANOMALY"] = {
                text = "Report a time anomaly?\n\nThe realm writes down where you are, the quest you're on, who you've spoken to and the way you came, and the app sends it to the realm's makers on GitHub.\n\nWhat went wrong? You can leave it empty. (Tap the box, then three fingers for the keyboard.)",
                button1 = "Report",
                button2 = CANCEL or "Cancel",
                hasEditBox = 1,
                maxLetters = 200,
                timeout = 0,
                whileDead = 1,
                hideOnEscape = 1,
                OnShow = function(self)
                    local box = _G[self:GetName() .. "EditBox"]
                    box:SetText("")
                    box:SetFocus()
                end,
                OnAccept = function(self)
                    report(_G[self:GetName() .. "EditBox"]:GetText())
                end,
                EditBoxOnEnterPressed = function(self)
                    report(self:GetText())
                    self:GetParent():Hide()
                end,
                EditBoxOnEscapePressed = function(self)
                    self:GetParent():Hide()
                end,
            }

            -- The button, in its own group between Macros and Log Out: in Log Out's place, with Log Out
            -- under it, and the menu taller by as much.
            local function addReportButton()
                if reportButton or not GameMenuFrame or not GameMenuButtonLogout then return end
                reportButton = CreateFrame("Button", "GameMenuButtonWowTouchReport", GameMenuFrame, "GameMenuButtonTemplate")
                reportButton:SetText("Report Time Anomaly")
                reportButton:SetScript("OnClick", function()
                    PlaySound("igMainMenuOption")
                    HideUIPanel(GameMenuFrame)
                    StaticPopup_Show("WOWTOUCH_TIME_ANOMALY")
                end)
                local point, anchor, relativePoint, x, y = GameMenuButtonLogout:GetPoint()
                reportButton:ClearAllPoints()
                reportButton:SetPoint(point, anchor, relativePoint, x, y)
                GameMenuButtonLogout:ClearAllPoints()
                GameMenuButtonLogout:SetPoint(point, reportButton, relativePoint, x, y)
                menuHeight = GameMenuFrame:GetHeight() + reportButton:GetHeight() - y
                GameMenuFrame:SetHeight(menuHeight)
                -- (the game sizes its menu as it opens on some clients: it stays tall enough)
                GameMenuFrame:HookScript("OnShow", function(menu)
                    if menu:GetHeight() < menuHeight then menu:SetHeight(menuHeight) end
                end)
            end

            watcher:RegisterEvent("PLAYER_ENTERING_WORLD")
            watcher:RegisterEvent("PLAYER_LEAVING_WORLD")
            watcher:RegisterEvent("PLAYER_REGEN_ENABLED")
            watcher:SetScript("OnEvent", function(self, event)
                if event == "PLAYER_REGEN_ENABLED" then
                    readyBars()
                    return
                end
                inWorld = event == "PLAYER_ENTERING_WORLD"
                said = nil
                tell()
                if inWorld then readyBars() end
                if inWorld and GetRealmName() == "$REMIX_REALM" then addReportButton() end
            end)
        """.trimIndent() + "\n",
    )
}
