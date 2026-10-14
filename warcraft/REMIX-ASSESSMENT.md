# Wrath Remix: assessment

The assessment written before it was built, against this app as of 1.0.51 (wow-build-51). Since
then the user sent their design (`REMIX-GUIDE.md`) and asked for it as a realm separate from
ChromieCraft: it's built as the app's second realm with `modules/mod-wrath-remix` (README, "Wrath
Remix"; PLAN.md). The realm separation below is how it was done; where the guide differs (level 10
with Northrend banded to the chapters, not 68), the guide was followed.

## The idea

Blizzard's Legion Remix, within Wrath of the Lich King, as its own realm next to the normal one:
a streamlined Northrend story campaign, fast leveling, Dalaran as a hub (campaign, teleports,
Bronze vendors, power upgrades, raid unlocks), Bronze earned everywhere and spent on appearances,
mounts and rare items, "Infinite Power" that grows until the player steamrolls content, and an
endgame through Naxxramas, Ulduar, Trial of the Crusader and Icecrown Citadel. Proposed as a
module, `mod-wrath-remix`.

## What this installation is

One realm on the phone, in the app: MariaDB (`acore_auth`, `acore_characters`, `acore_world`,
`acore_playerbots`), one auth server and one world server (port 8085), started by
`server/ctl.sh`. ChromieCraft's AzerothCore with the playerbots fork's core merged in, arm64 musl.
Built-in modules: playerbots, progression-system, zone-difficulty, chromie-xp, SmartStone,
hoard-collector, npc-talent-template, and whatever the user adds in Modules. The player is the
user, with bots.

## Verdict

Feasible, with changes: Timerunners start at 68 (not 1), one realm runs at a time, Bronze and its
shops are server-side, and the realm separation comes first, before any gameplay.

## A second realm, the first untouched

- AzerothCore runs several realms on one auth database: realm 2 gets its own configs
  (`files/etc-remix`: `RealmID = 2`, port 8086) and its own databases (`acore_world_remix`,
  `acore_characters_remix`, `acore_playerbots_remix`), and a second `realmlist` row.
- One world server at a time: two beside Wine and the game would likely run the phone out of
  memory. The launcher picks the realm, starts that world server and sets the realm in
  `Config.wtf` (the game still goes straight to character select).
- One server program for both: the Remix module does nothing unless the Remix config turns it on.
- AzerothCore applies a compiled module's SQL to every database it updates. Remix SQL therefore
  goes in its own folder, listed in the Remix databases' `updates_include` table only, so the
  normal databases never get it. The user's picked modules are in both realms (one program).
- Why a separate world database at all: mod-progression-system opens content by changing the
  world database bracket by bracket; Remix needs all of Wrath open while the normal realm stays
  gated. Cost: another copy of the world database (to measure) and its first import.

## Shared login, separate characters

The realm model gives it: accounts and passwords shared; characters, bags, bank, mail, auction
house, guilds, raid lockouts and achievements per realm; GM levels per realm. Playerbots' random
bot accounts (`rndbot…`) are in the shared auth database: both realms would use the same accounts
with their own characters (expected to work; to test first). The client's WDB cache is very
likely shared between realms: Remix uses new IDs, never renamed existing ones, and the app can
clear the cache when the realm changes.

## Server-side or not

| Feature | Server-side | How |
| --- | --- | --- |
| Separate realm, shared login | Yes | Configs, realmlist, databases |
| Timerunner at 68, starter kit, Northrend start | Yes | First-login hook; npc-talent-template gear sets |
| Fast leveling | Yes | Remix XP rates, chapter rewards |
| Campaign chapters, tracking, teleports, rewards | Yes | Module plus a chapter table; Blizzard's quests unchanged |
| Dalaran hub | Yes | Existing models (Chromie, Infinite Dragonflight from Culling of Stratholme) |
| Bronze earned and spent | Yes, as a wallet | Kept on the server; shown in the hub's menus and chat |
| Bronze in the Currency tab | No | Needs the client's CurrencyTypes.dbc |
| Bronze prices in the vendor window | No | ItemExtendedCost.dbc is the client's |
| Infinite Power effects | Yes | Damage and healing hooks (as autobalance-type modules use), stat bonuses |
| Infinite Power as a buff | Partly | An existing buff's icon; a new spell needs client patches |
| Raid appearances | Yes, with `mod-transmog` | Works with the stock client |
| Mounts, pets, raid gear for Bronze | Yes | Through hub menus |
| Leveling 1 to 80 in Northrend | Not practically | No level scaling in 3.3.5a |
| Custom achievements | No | Server-side milestones shown at the hub |

## Conflicts with what's installed

- mod-progression-system: Remix's world database set to the last bracket (the reason for its own
  world database).
- mod-zone-difficulty: changes damage and healing in some dungeons and raids through the same
  hooks Infinite Power uses; off on Remix, or counted in.
- mod-playerbots: random bots at 68 to 80 in Northrend without Remix power; the player's party bots
  need the power too; 10 to 25 bots in a raid are heavy for a phone.
- mod-chromie-xp: Chromie's XP changes; Remix's rates should win (off on Remix, or its NPC reused).
- SmartStone and npc-talent-template: useful (a Remix menu item; level-80 gear sets).
- The app's own core patches (selection, cutscene, spellbook): work in both.
- Core updates can rename the hooks a module uses; CI catches it at compile time.

## Architecture

- The realm side, in the app and `ctl.sh` (not the module): the second configs and databases,
  the realm choice in the launcher, one world server at a time, the first import, backups of both,
  CI for both realms.
- One module, `mod-wrath-remix`, in five parts sharing one character record (the Timerunner
  flag) and one config:
  - Timerunner: a new Remix character's first login sets level 68, starter gear and bags, the
    faction's way into Northrend, Dalaran reachable.
  - Campaign: data-driven (chapter, faction, quests, start point, rewards); follows quest
    completion; the hub offers the next chapter and its teleport. Blizzard's quest chains stay as
    they are.
  - Bronze: a wallet in the Remix characters database, an earnings table (quest, elite, dungeon
    boss, raid boss, chapter, daily), purchases through hub menus, each logged.
  - Infinite Power: a level with upgrade lines (damage, healing, health, speed, utility), bought or
    earned; applied through hooks and stat bonuses on top of class and talents; shared with the
    player's party bots (a setting); shown as an existing buff with stacks.
  - Hub and endgame: Dalaran gossip NPCs over those tables; raid unlocks, boss rewards, challenge
    rules.
- One module rather than several: the parts share the flag, the wallet and the config, and
  AzerothCore modules have no clean way to call each other. Tuning lives in tables (a realm
  restart, not a rebuild). `mod-transmog` as it is, for appearances.

## Biggest risks

1. Leveling from 1 in Northrend: no scaling in 3.3.5a (fixed creature levels, quest rewards at
   68+, few abilities at low level). Avoided by starting at 68, where Wrath's story begins.
2. Quest chains and phasing: the Wrathgate, the Battle for Undercity, Icecrown's phases and the
   Argent chains depend on scripts of varying quality in AzerothCore. Skipping ahead (quests marked
   done in the database) can leave the world in the wrong phase: skips only as tested lists per
   chapter. Alliance and Horde chains differ for many chapters.
3. Raids with bots on a phone: Infinite Power should make raids doable alone or with a few bots.
4. Balance: power multipliers compound; caps per line.
5. Storage and the first start: a second world database and its import.
6. Core updates breaking hooks (CI catches them).
7. Testing: CI can check both realms start and keep characters apart, and the starter kit, Bronze
   and hub menus with the test character. Whether each chapter plays through needs the user on the
   phone.

## Design changes recommended

- Start at 68, perhaps 68 or 80 to choose.
- Chapters guide through Blizzard's own quests, with teleports and rewards, instead of rebuilding
  them; skips only where tested.
- Bronze as a wallet with menu shops: nothing on the client to patch.
- Power that matters alone: party bots share it; raids aimed at the player and a few bots.
- Everything tuned in tables (earnings, prices, power lines, chapters).
- No season end at first; later, perhaps, "graduating" a Timerunner into the normal realm, the
  only feature that would touch it.
- If the normal realm is meant to reach Wrath too, Remix is best as the quick story replay that
  the bracket-by-bracket realm isn't.

## Phases (each a release: CI tests, then the user on the phone)

| Phase | What | Size | Playable result |
| --- | --- | --- | --- |
| 0 | The second realm: configs, databases, launcher choice, one server at a time, all brackets open; CI for both realms and separate characters | Medium | A fresh "Wrath Remix" realm with Wrath open; the normal realm unchanged |
| 1 | Timerunners: 68, starter kit, Northrend start, Remix XP; Dalaran hub with Chromie (teleports, information) | Small to medium | Fast leveling from a hub |
| 2 | Bronze: wallet, earnings, shop menus (mounts, pets, gear); `mod-transmog` | Medium | Earning and spending |
| 3 | Campaign: chapter table and tracking; Arrival and Dragonblight/Wrathgate for both factions first, then Zul'Drak, the Storm Peaks, Icecrown | Large (mostly testing) | The story path, with rewards |
| 4 | Infinite Power: lines, hooks, party bots, the buff, caps | Medium | Growing power |
| 5 | Endgame: raid and boss rewards, unlocks, challenges, raid balance for the player and a few bots | Medium | Naxxramas to Icecrown Citadel at 80 |
| 6 | Polish: themed NPCs and announcements, milestones, the cache cleared on a realm change, optional graduation | Small to medium | The Remix feel |

Phase 0 first and alone: it's the only phase with any risk to the normal realm, and once the
separation is proven, everything after it touches Remix only.
