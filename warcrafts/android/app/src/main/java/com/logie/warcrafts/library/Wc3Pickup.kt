// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.warcrafts.library

/**
 * Warcraft III's heroes pick up an item by walking over it (asked for by the player, for whom a tap
 * on an item didn't pick it up). The game has no such setting, so the app adds it to the game's
 * own script library, `Scripts\Blizzard.j`, which every map's script calls at its start
 * (`InitBlizzard`): melee, custom and campaign maps alike.
 *
 * With the registry's `Allow Local Files` on, 1.27 reads a `Blizzard.j` at the top of the game's
 * folder in place of its archives' (it asks for `F:\blizzard.j`, not `F:\Scripts\blizzard.j`: seen
 * under Wine). The app takes the player's own from `War3Patch.mpq` (Blizzard's file stays out of
 * this repository), adds [GLOBALS], [FUNCTIONS] and a call at the end of `InitBlizzard`, and writes
 * it there ([com.logie.warcrafts.Wc3PickupSetup]).
 *
 * What the addition does, ten times a second, for each hero of each player at the controls (alive,
 * not hidden, not an illusion, not paused, with an inventory, no cinematic playing): an item on the
 * ground within 50 game units of the hero's edge (its collision circle) is given to it (`UnitAddItem`: the game's own "picks up
 * an item" event fires, which campaign triggers use; a powerup is used, as when picked up), when
 * there is a free slot or the item is a powerup. A powerup already used lies there a while, dead:
 * it's left. An item a hero drops stays where it is until that hero has walked away from it.
 * Tried in 1.27 under Wine, Reign of Chaos and The Frozen Throne: walking over, standing on, a full
 * inventory, a tome, a dropped item, a cinematic.
 */
object Wc3Pickup {
    /** Where the game reads it: the top of its folder. */
    const val FILE = "Blizzard.j"
    /** The game's own, in its patch archive. */
    const val ARCHIVE = "War3Patch.mpq"
    const val IN_ARCHIVE = "Scripts\\Blizzard.j"
    /** The addition's version: a new one rewrites the file. */
    const val VERSION = 1
    private const val MARK = "// com.logie.warcrafts: walk-over pickup"

    /** The first line of the app's file: the addition's version and where the game's own came from. */
    fun header(source: String) = "$MARK $VERSION, from $source"

    /** What [header] says of an archive: its name, size and time, so a new one (a patch) is noticed. */
    fun source(name: String, length: Long, modified: Long) = "$name $length $modified"

    /** Whether a `Blizzard.j`'s [firstLine] is the app's (any version). */
    fun isOurs(firstLine: String) = firstLine.trimEnd('\r').startsWith(MARK)

    /**
     * The game's `Blizzard.j` ([blizzard], read byte for byte as ISO-8859-1) with the addition, its
     * first line [header], in its own line endings; null when it isn't laid out as expected (one
     * globals block, then `InitBlizzard`) or already has the addition.
     */
    fun patch(blizzard: String, source: String): String? {
        if (blizzard.contains("LogiePickupInit")) return null
        val nl = if (blizzard.contains("\r\n")) "\r\n" else "\n"
        val globalsEnd = Regex("(?m)^endglobals").find(blizzard)?.range?.first ?: return null
        val init = Regex("(?m)^function InitBlizzard takes nothing returns nothing").find(blizzard)?.range?.first ?: return null
        if (init < globalsEnd) return null
        val initEnd = Regex("(?m)^endfunction").find(blizzard, init)?.range?.first ?: return null
        fun lines(text: String) = text.trimEnd('\n').replace("\n", nl) + nl
        return header(source) + nl +
            blizzard.substring(0, globalsEnd) + lines(GLOBALS) +
            blizzard.substring(globalsEnd, init) + lines(FUNCTIONS) + nl +
            blizzard.substring(init, initEnd) + lines(CALL) +
            blizzard.substring(initEnd)
    }

    /** The addition's globals, put at the end of the globals block. */
    val GLOBALS = """
    // The Android app's walk-over pickup (com.logie.warcrafts)
    constant real      LOGIE_PICKUP_RANGE          = 50.00
    constant real      LOGIE_PICKUP_AWAY           = 150.00
    timer              logiePickupTimer            = null
    group              logiePickupGroup            = null
    rect               logiePickupRect             = null
    unit               logiePickupHero             = null
    item array         logiePickupLeft
    unit array         logiePickupLeftBy
    integer            logiePickupLeftCount        = 0
""".trimStart('\n')

    /** The addition's functions, put just before `InitBlizzard`. */
    val FUNCTIONS = """
//===========================================================================
// The Android app's walk-over pickup (com.logie.warcrafts): a hero of a
// player at the controls picks up an item on the ground by walking over it,
// as if it had been clicked. An item a hero dropped is left there until that
// hero has walked away from it.
//===========================================================================
function LogiePickupLeftAt takes item it returns integer
    local integer i = 0
    loop
        exitwhen i >= logiePickupLeftCount
        if logiePickupLeft[i] == it then
            return i
        endif
        set i = i + 1
    endloop
    return -1
endfunction

function LogiePickupForget takes integer i returns nothing
    set logiePickupLeftCount = logiePickupLeftCount - 1
    set logiePickupLeft[i] = logiePickupLeft[logiePickupLeftCount]
    set logiePickupLeftBy[i] = logiePickupLeftBy[logiePickupLeftCount]
    set logiePickupLeft[logiePickupLeftCount] = null
    set logiePickupLeftBy[logiePickupLeftCount] = null
endfunction

function LogiePickupHasRoom takes unit u, item it returns boolean
    local integer slot = 0
    if IsItemPowerup(it) then
        return true
    endif
    loop
        exitwhen slot >= UnitInventorySize(u)
        if UnitItemInSlot(u, slot) == null then
            return true
        endif
        set slot = slot + 1
    endloop
    return false
endfunction

function LogiePickupItem takes nothing returns nothing
    local item it = GetEnumItem()
    local unit u = logiePickupHero
    local integer left
    // (A powerup used up lies there a while, dead: not one to pick up.)
    if IsItemVisible(it) and not IsItemOwned(it) and not (IsItemPowerup(it) and GetWidgetLife(it) <= 0) and IsUnitInRangeXY(u, GetItemX(it), GetItemY(it), LOGIE_PICKUP_RANGE) then
        set left = LogiePickupLeftAt(it)
        if (left < 0 or logiePickupLeftBy[left] != u) and LogiePickupHasRoom(u, it) then
            call UnitAddItem(u, it)
        endif
    endif
    set it = null
    set u = null
endfunction

function LogiePickupTick takes nothing returns nothing
    local integer p = 0
    local integer i
    local player who
    local unit u
    local item it
    if bj_cineModeAlreadyIn then
        return
    endif
    // A dropped item is the dropping hero's again once it has walked away (or it's gone or carried).
    set i = logiePickupLeftCount - 1
    loop
        exitwhen i < 0
        set it = logiePickupLeft[i]
        if GetItemTypeId(it) == 0 or IsItemOwned(it) or not IsUnitInRangeXY(logiePickupLeftBy[i], GetItemX(it), GetItemY(it), LOGIE_PICKUP_AWAY) then
            call LogiePickupForget(i)
        endif
        set i = i - 1
    endloop
    loop
        exitwhen p >= bj_MAX_PLAYERS
        set who = Player(p)
        if GetPlayerController(who) == MAP_CONTROL_USER and GetPlayerSlotState(who) == PLAYER_SLOT_STATE_PLAYING then
            call GroupEnumUnitsOfPlayer(logiePickupGroup, who, null)
            loop
                set u = FirstOfGroup(logiePickupGroup)
                exitwhen u == null
                call GroupRemoveUnit(logiePickupGroup, u)
                if IsUnitType(u, UNIT_TYPE_HERO) and not IsUnitType(u, UNIT_TYPE_DEAD) and not IsUnitHidden(u) and not IsUnitIllusion(u) and not IsUnitPaused(u) and UnitInventorySize(u) > 0 then
                    set logiePickupHero = u
                    call SetRect(logiePickupRect, GetUnitX(u) - 200, GetUnitY(u) - 200, GetUnitX(u) + 200, GetUnitY(u) + 200)
                    call EnumItemsInRect(logiePickupRect, null, function LogiePickupItem)
                endif
            endloop
        endif
        set p = p + 1
    endloop
    set logiePickupHero = null
    set who = null
    set it = null
endfunction

function LogiePickupDropped takes nothing returns nothing
    if IsUnitType(GetTriggerUnit(), UNIT_TYPE_HERO) and logiePickupLeftCount < 8000 then
        set logiePickupLeft[logiePickupLeftCount] = GetManipulatedItem()
        set logiePickupLeftBy[logiePickupLeftCount] = GetTriggerUnit()
        set logiePickupLeftCount = logiePickupLeftCount + 1
    endif
endfunction

function LogiePickupInit takes nothing returns nothing
    local trigger t = CreateTrigger()
    local integer p = 0
    set logiePickupGroup = CreateGroup()
    set logiePickupRect = Rect(0, 0, 0, 0)
    loop
        exitwhen p >= bj_MAX_PLAYERS
        call TriggerRegisterPlayerUnitEvent(t, Player(p), EVENT_PLAYER_UNIT_DROP_ITEM, null)
        set p = p + 1
    endloop
    call TriggerAddAction(t, function LogiePickupDropped)
    set logiePickupTimer = CreateTimer()
    call TimerStart(logiePickupTimer, 0.10, true, function LogiePickupTick)
    set t = null
endfunction
""".trimStart('\n')

    /** Put at the end of `InitBlizzard`. */
    const val CALL = "    call LogiePickupInit()"
}
