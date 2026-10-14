# mod-wrath-remix

Wrath Remix for AzerothCore: a Legion Remix-style replay of Wrath of the Lich King, on a realm of
its own. Built for the World of Warcraft Android app in this repository (its user manual is
`warcraft/README.md`, "Wrath Remix"); the design it follows is the user's guide,
`warcraft/REMIX-GUIDE.md`.

It only runs where `WrathRemix.Enable = 1` **and** the world server's `RealmID` is
`WrathRemix.RealmID` (2). Everywhere else every hook returns at once. The app runs one server
program for both its realms, so the module is compiled into ChromieCraft's world server too, where
it's off and its tables don't exist.

## Layout

| Path | Contents |
| --- | --- |
| `conf/mod_wrath_remix.conf.dist` | The switch, the realm, the level bands, the Bronze rates |
| `src/WrathRemix.cpp` | The realm gate, the tables, Timerunners (Bronze, chapter, starter kit), the hooks: first login, login, quest rewards (Bronze, Moratari's transitions, the Bazaar as home, death knights brought to the Bazaar), kills (Bronze), a Timerunner's training at login and each level (class trainer and class quest abilities, weapon skills at the level's maximum, free riding at 20/40/60/70/77, the starter kit once), creature levels by band and their spells scaled to match, the stat squish (banded creatures' health and damage at `WrathRemix.Squish.*` percent), the realm's bots sent from the Caverns of Time to their level's chapter (not one in a player's group); the `.remix` commands |
| `src/RemixNpcs.cpp` | Moratari, Eternus, Andormu and Nozari, the Bronze vendors (Alurmi, Horos, Domelius, Arturos, Aeonicus, Durus: their lists open as vendor windows), Zephyr (travel), Sa'at, the Chronologist (chapters), Momentus, the prologue's time-echoes and its anomalies (one at a time) |
| `src/ClientPatch.cpp` | The game's patch: as Wrath Remix starts, `Item.dbc`, `ItemExtendedCost.dbc` and `CurrencyTypes.dbc` (the server's, with the `*_dbc` tables' rows: Bronze, its currency row, its prices) in an MPQ in the app's client (`Data/patch-Y.MPQ`, beside `patch-Y.MPQ.wrath-remix`, which says it's Remix's); ChromieCraft's world server takes it out. `WOW_CLIENT_DIR` (ctl.sh) says where the client is |
| `data/sql/remix/world/` | The Remix world database's SQL (`wrath_remix_world_*.sql`): the module's tables, the NPCs and instructors, the prologue's quests (WRM-001 to 007) and the start in the Caverns of Time, the spawns, the level bands with the quests' and their rewards' levels, Chapter I's transitions (WRM-006/016/026, WRM-121 to 124), the chapters and roads, the starter kits and class quest abilities (`0008_starter`), Bronze as a currency and the Bronze vendors (`0009_bronze`), the vendors' lists (`0010_vendors`, made by `tools/vendor_catalog.py`), Chapter II's transitions (`0011_chapter2`: WRM-201/211, 202/212, 221/222) |
| `data/sql/remix/characters/` | The Remix characters database's: Timerunners (with the starter kit they've had) and the log of purchases and transitions |
| `data/candidates.tsv` | The original quests the guide names for its campaign, by chapter |
| `data/campaign_manifest.csv`, `data/quest_audit.md` | Those quests resolved against the world database: id, side, levels, zone, chain, starter and ender |
| `tools/quest_audit.py` | Makes the two from `candidates.tsv` and a world database |
| `tools/vendor_catalog.py`, `data/vendor_catalog.md` | The Bronze vendors' lists from a world database (instance_encounters and the bosses' and chests' loot, the tier sets' item sets, and its own lists of Wrath's mounts, companions, toys and heirlooms), priced on a ladder of Bronze; writes `0010_vendors.sql` and the catalog |

The SQL is in `data/sql/remix/`, not `data/sql/db-*`, on purpose: AzerothCore applies a module's
`db-*` folders to every database of that kind, which would put Remix's world into ChromieCraft's.
The app's `ctl.sh` lists `remix/world` and `remix/characters` in the Remix databases'
`updates_include` table, so only those get them, as `CUSTOM` updates. The files are re-applied when
they change: each one is idempotent (data tables made again; changes to the game's own quests and
items worked out from the originals kept in `remix_original_*`).

## Ids

Creatures 770001+ (instructors 770101+, the Bronze vendors 770201+, their lists' hidden vendor
templates 770301+), quests 77nnn for the guide's WRM-nnn (77000 + nnn), NPC texts 7702nnn,
spawns 7704nnn; Bronze is item 77001 (CurrencyTypes 342, bit 30), its prices ItemExtendedCost 7701
to 7727 (7700 + the price's step on the ladder). Creature templates and quests are kept small on purpose: the core
looks them up in tables as long as the highest id (`_creatureTemplateStoreFast`,
`_questTemplatesFast`, and items the same way), so ids in the millions cost the world server
about 60 MB each. (They were 7700001+ and 7701nnn until build 56; the SQL takes the old rows out
and moves Timerunners' quest records over.)

## Commands

- `.remix`: whether Remix runs here; your Bronze, what you've earned, your chapter.
- `.remix bronze add N` (game masters): Bronze for the selected player or yourself.
- `.remix band` (game masters): the band of the zone you're in, the levels of the creatures
  around you, and their normal ones' health against their level's (the squish).
- `.remix guid ENTRY` (game masters): the nearest creature of that entry and its guid (the core
  gives creatures guids of their map's, so CI's test client asks for them).

## Bronze and the vendors

Bronze is a currency token (item 77001), stored where the emblems are. A ware's price is an
`ItemExtendedCost` asking for n Bronze: the server has those rows from `itemextendedcost_dbc`, and
the client from the patch `ClientPatch.cpp` writes (without it, a vendor window shows no price,
buying still works). Each list is a vendor template of its own, never spawned (`npc_vendor` rows
with those prices); a vendor's gossip names its lists (`remix_vendor_list`) and opens one with
`SendListInventory(guid, list)`, which the core remembers for the purchase. A list holds at most
255 wares (the core counts them in a byte) and shows a player at most 150 (the 3.3.5 window); the
core shows each player only their side's wares and the bind-on-pickup ones their class can use,
so the catalog splits a list only when one class would see more than 150. Bronze from before it
was an item (`remix_character.bronze`) is paid into the currency tab at the next login.

## Updating the campaign

1. Edit `data/candidates.tsv` (title, chapter) and run
   `python3 tools/quest_audit.py --mysql "<command that runs SQL on the world database>"`.
2. A transition is a quest in its chapter's file (`0006_bridges.sql` for Chapter I,
   `0011_chapter2.sql` for II; WRM-nnn is 77nnn): its `PrevQuestID` is the original quest it
   follows, a Moratari spawn stands by that quest's ender (`0004_spawns.sql`, in the ender's phase
   or one given), the original quest after the skipped block opens with either the transition or
   the block's own end (`conditions`, source type 19, with the block's `NextQuestID`s taken out),
   and `remix_bridge` says where it takes the Timerunner and which chapter it starts. Each file
   deletes and writes only its own rows (the server applies a file again when it changes).
3. CI's server test plays the prologue (`warcraft/server/remix_prologue.steps`), buys from the
   Bronze vendors, plays Chapter II's transitions on each side
   (`warcraft/server/remix_chapter2_alliance.steps`, `_horde.steps`: the original quests before
   them given with `.quest add`, `complete` and `reward`), checks the floor under every spawn
   (`warcraft/server/remix_check.py floors`) and the game's patch (`remix_check.py patch`, its own
   reading of the MPQ).
4. The vendors' lists: edit `tools/vendor_catalog.py` (its lists, the price ladder, the instances)
   and run it with `--mysql` (on the unchanged world database) and `--dbc` (the server's DBC
   folder); it rewrites `0010_vendors.sql` and `data/vendor_catalog.md`.
