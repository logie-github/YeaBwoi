// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.client

/**
 * The app's own add-on, put into the client's `Interface/AddOns` before each start (and left out
 * of the add-on manager's list): it tells the touch controls where the spellbook is while it's
 * open, so a drag that starts on it drags a spell, as the mouse does on a PC.
 *
 * An add-on can't reach the app directly, so it says it to the realm on the phone: an add-on
 * message (prefix `WowTouch`) whispered to the player itself, which the realm keeps for the app
 * instead of passing on (patches/azerothcore/0010, `files/run/interface`). It says it again only
 * when it changes: "drag <left> <bottom> <right> <top>" in ten-thousandths of the screen from its
 * bottom left, inside the window's own edges, or "drag -".
 */
object TouchAddon {
    const val FOLDER = "WowTouch"
    const val PREFIX = "WowTouch"

    val files: Map<String, String> = mapOf(
        "$FOLDER.toc" to """
            ## Interface: ${AddonLayout.INTERFACE}
            ## Title: WowTouch
            ## Notes: The World of Warcraft app's touch controls: where the spellbook is, for dragging its spells.
            ## Author: World of Warcraft app
            ## Version: 1
            $FOLDER.lua
        """.trimIndent() + "\n",
        "$FOLDER.lua" to """
            -- The World of Warcraft app's touch controls (installed by the app at each start). Says
            -- where the spellbook is while it's open, so a drag that starts on it drags a spell, as
            -- the mouse does on a PC: in an add-on message to the player itself, which the realm on
            -- the phone keeps for the app.

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
            watcher:RegisterEvent("PLAYER_ENTERING_WORLD")
            watcher:RegisterEvent("PLAYER_LEAVING_WORLD")
            watcher:SetScript("OnEvent", function(self, event)
                inWorld = event == "PLAYER_ENTERING_WORLD"
                said = nil
                tell()
            end)
        """.trimIndent() + "\n",
    )
}
