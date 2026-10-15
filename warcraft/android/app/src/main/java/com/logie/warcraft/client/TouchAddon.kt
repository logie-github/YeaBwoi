// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.client

/**
 * The app's own add-on, put into the client's `Interface/AddOns` before each start (and left out
 * of the add-on manager's list): it tells the touch controls where the spellbook is while it's
 * open, so a drag that starts on it drags a spell, as the mouse does on a PC.
 *
 * An add-on can't reach the app directly, so it says it to the realm on the phone: an add-on
 * message (prefix `WowTouch`) whispered to the player itself, which the realm keeps for the app
 * instead of passing on (patches/azerothcore/0009, `files/run/interface`). It says it again only
 * when it changes: "drag <left> <bottom> <right> <top>" in ten-thousandths of the screen from its
 * bottom left, inside the window's own edges, or "drag -".
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
            ## Notes: The World of Warcraft app's touch controls: where the spellbook is, for dragging its spells; Report Time Anomaly on Wrath Remix.
            ## Author: World of Warcraft app
            ## Version: 2
            $FOLDER.lua
        """.trimIndent() + "\n",
        "$FOLDER.lua" to """
            -- The World of Warcraft app's touch controls (installed by the app at each start). Says
            -- where the spellbook is while it's open, so a drag that starts on it drags a spell, as
            -- the mouse does on a PC: in an add-on message to the player itself, which the realm on
            -- the phone keeps for the app. On Wrath Remix, Report Time Anomaly in the game's menu.

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

            local function tell()
                if not inWorld then return end
                local note = "drag " .. place(SpellBookFrame)
                if note ~= said then
                    said = note
                    SendAddonMessage("$PREFIX", note, "WHISPER", UnitName("player"))
                end
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
            watcher:SetScript("OnEvent", function(self, event)
                inWorld = event == "PLAYER_ENTERING_WORLD"
                said = nil
                tell()
                if inWorld and GetRealmName() == "$REMIX_REALM" then addReportButton() end
            end)
        """.trimIndent() + "\n",
    )
}
