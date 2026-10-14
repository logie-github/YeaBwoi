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
| `src/WrathRemix.cpp` | The realm gate, the tables, Timerunners (Bronze, chapter, starter kit), the hooks: first login, login, quest rewards (Bronze, Moratari's transitions, the Bazaar as home, death knights brought to the Bazaar), kills (Bronze), a Timerunner's training at login and each level (class trainer and class quest abilities, weapon skills at the level's maximum, free riding at 20/40/60/70/77, the starter kit once), creature levels by band and their spells scaled to match; the `.remix` commands |
| `src/RemixNpcs.cpp` | Moratari, Eternus, Andormu and Nozari, Alurmi (the Bronze shop), Zephyr (travel), Sa'at, the Chronologist (chapters), Momentus, the prologue's time-echoes and its anomalies (one at a time) |
| `data/sql/remix/world/` | The Remix world database's SQL (`wrath_remix_world_*.sql`): the module's tables, the NPCs and instructors, the prologue's quests (WRM-001 to 007) and the start in the Caverns of Time, the spawns, the level bands with the quests' and their rewards' levels, Chapter I's transitions (WRM-006/016/026, WRM-121 to 124), the chapters, roads and shop, the starter kits and class quest abilities (`0008_starter`) |
| `data/sql/remix/characters/` | The Remix characters database's: Timerunners (with the starter kit they've had) and the log of purchases and transitions |
| `data/candidates.tsv` | The original quests the guide names for its campaign, by chapter |
| `data/campaign_manifest.csv`, `data/quest_audit.md` | Those quests resolved against the world database: id, side, levels, zone, chain, starter and ender |
| `tools/quest_audit.py` | Makes the two from `candidates.tsv` and a world database |

The SQL is in `data/sql/remix/`, not `data/sql/db-*`, on purpose: AzerothCore applies a module's
`db-*` folders to every database of that kind, which would put Remix's world into ChromieCraft's.
The app's `ctl.sh` lists `remix/world` and `remix/characters` in the Remix databases'
`updates_include` table, so only those get them, as `CUSTOM` updates. The files are re-applied when
they change: each one is idempotent (data tables made again; changes to the game's own quests and
items worked out from the originals kept in `remix_original_*`).

## Ids

Creatures 770001+ (instructors 770101+), quests 77nnn for the guide's WRM-nnn (77000 + nnn), NPC
texts 7702nnn, spawns 7704nnn. Creature templates and quests are kept small on purpose: the core
looks them up in tables as long as the highest id (`_creatureTemplateStoreFast`,
`_questTemplatesFast`, and items the same way), so ids in the millions cost the world server
about 60 MB each. (They were 7700001+ and 7701nnn until build 56; the SQL takes the old rows out
and moves Timerunners' quest records over.)

## Commands

- `.remix`: whether Remix runs here; your Bronze, what you've earned, your chapter.
- `.remix bronze add N` (game masters): Bronze for the selected player or yourself.
- `.remix band` (game masters): the band of the zone you're in and the levels of the creatures
  around you.
- `.remix guid ENTRY` (game masters): the nearest creature of that entry and its guid (the core
  gives creatures guids of their map's, so CI's test client asks for them).

## Updating the campaign

1. Edit `data/candidates.tsv` (title, chapter) and run
   `python3 tools/quest_audit.py --mysql "<command that runs SQL on the world database>"`.
2. A transition is a quest in `wrath_remix_world_0006_bridges.sql` (the next free 7701nnn): its
   `PrevQuestID` is the original quest it follows, a Moratari spawn stands by that quest's ender
   (`0004_spawns.sql`), the original quest after the skipped block opens with either the
   transition or the block's own end (`conditions`, source type 19), and `remix_bridge` says where
   it takes the Timerunner and which chapter it starts.
3. CI's server test plays the prologue (`warcraft/server/remix_prologue.steps`) and checks the
   floor under every spawn (`warcraft/server/remix_check.py floors`).
