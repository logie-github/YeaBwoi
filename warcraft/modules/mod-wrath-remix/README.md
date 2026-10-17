# mod-wrath-remix

Wrath Remix for AzerothCore: a Legion Remix-style replay of Wrath of the Lich King, on a realm of
its own. Built for the World of Warcraft Android app in this repository (its user manual is
`warcraft/README.md`, "Wrath Remix"); the design it follows is the user's guide,
`warcraft/REMIX-GUIDE.md`, and the story as the user asked for it after playing the first demo,
`STORY.md` (Eternus the antagonist, Sylvanas and Alleria from the future, the turn before the
Wrathgate): that file is the script, quest by quest.

It only runs where `WrathRemix.Enable = 1` **and** the world server's `RealmID` is
`WrathRemix.RealmID` (2). Everywhere else every hook returns at once. The app runs one server
program for both its realms, so the module is compiled into ChromieCraft's world server too, where
it's off and its tables don't exist.

## Layout

| Path | Contents |
| --- | --- |
| `conf/mod_wrath_remix.conf.dist` | The switch, the realm, the level bands, the Bronze rates (quests, kills, treasure, achievements), the solo scaling |
| `src/WrathRemix.cpp` | The realm gate, the tables, Timerunners (Bronze, chapter, starter kit), the hooks: first login, login, quest rewards (Bronze, Moratari's transitions, the Bazaar as home, death knights brought to the Bazaar), kills (Bronze), a Timerunner's training at login and each level (class trainer and class quest abilities, weapon skills at the level's maximum, free riding at 20/40/60/70/77, the starter kit once), creature levels by band and their spells scaled to match, the stat squish (banded creatures' health and damage at `WrathRemix.Squish.*` percent), the realm's bots sent from where characters are made (the Bazaar; the Caverns of Time until build 64) to their level's chapter (not one in a player's group), a Timerunner made before build 64 still in the prologue woken at the Bazaar; the `.remix` commands |
| `src/RemixNpcs.cpp` | Moratari, Eternus, Andormu and Nozari, the Bronze vendors (Alurmi, Horos, Domelius, Arturos, Aeonicus, Durus: their lists open as vendor windows), Zephyr (travel), Sa'at, the Chronologist (chapters), Momentus, the class instructors (the prologue's "speak with your instructor": your own class's only; their class's lesson and the trainer's list), the prologue's echoes of the war's dead and its anomalies (one at a time), Sylvanas and Alleria Windrunner, and the turn's scene (WRM-207) |
| `src/RemixScenes.cpp` | The story's scenes (`remix_scene`, `remix_scene_step`, written by `tools/scenes.py` from the script): as a quest is taken or turned in, its people are summoned where they stand, walk, emote, cast and speak, seen and heard by that Timerunner alone; one scene at a time, its credit given at the end; a scene a quest needs played again at its giver; Eternus's agents (`npc_remix_story_foe`: credit to everyone near, bots' tags or not) |
| `src/RemixLastHour.cpp` | WRM-721 The Last Hour: Eternus at Icecrown Citadel's doors, his words, his loyalists' waves, his spells, his retreat at a tenth of his health (he is never killed), and Moratari, Sylvanas and Alleria fighting beside the Timerunner; Moratari's way back to the doors |
| `src/RemixEnding.cpp` | The end: the Lich King's death (or the end of his cinematic) brings the Timerunner back to the Bazaar, with Eternus's letter and the return's scene; Sylvanas's, Alleria's, Moratari's, Andormu's and Nozari's conversations and goodbyes (`remix_conversation`, `remix_letter`); WRM-802's way home: the character saved, written as a player dump in `WOW_TRANSFERS_DIR` (ctl.sh: `files/run/transfers`), logged out and taken out of the Remix database; ChromieCraft's world server, as it starts, loads the dumps into its own characters in Stormwind or Orgrimmar |
| `src/RemixSolo.cpp` | Dungeons and raids for however many are in them (`WrathRemix.Solo.*`): damage scaled to the instance's share of a full group, and Icecrown Citadel's group-only mechanics done for a Timerunner nearly alone |
| `src/RemixFrozenHalls.cpp` | The Forge of Souls' Sylvanas (the Horde's way in): the game's briefing, then the moment she senses she is observed, and her murmurs about her sisters |
| `src/RemixHourglass.cpp` | Moratari's Hourglass (item 77002): given as a Timerunner wakes at the Bazaar (a death knight's as they come to it), and again if lost; used anywhere, to the Bazaar; used there, back to where they came to it from (kept by a hook on every teleport to the Bazaar, in `remix_character`) |
| `src/RemixIntro.cpp` | The opening (`WrathRemix.Intro`): a new Timerunner's battle at the Broken Front, in a phase of its own (`0024_intro.sql` its people), until time stops and Moratari brings them to the Bazaar; `.remix intro [skip]` |
| `src/RemixLessons.cpp` | The class lessons (WRM-030 to 039, `0025_lessons.sql`, `remix_lesson`): each class's instructor walks a Timerunner through three of its abilities, each its credit as it's cast as the lesson says (on one of the training yard's dummies, or within sight of one); the dummies' own script (`npc_remix_dummy`, the game's training dummy's) |
| `src/RemixGuide.cpp` | The guide (`WrathRemix.Guide`): a few times a minute, each Timerunner's next step (the quest's next objective at the nearest of its creatures or objects they can see, the one who takes it back, or the one with their next quest), as a flag on the map and minimap (`SMSG_GOSSIP_POI`) and a *Next:* line in chat as it changes; who can be seen follows the story's visibility conditions |
| `src/RemixBazaar.cpp` | The Bazaar's life: its people (every spawn with lines in `remix_bazaar_line`) talk now and then while a real player is near, to the air or to someone who answers, heard only by those who can see them |
| `src/PlayerSaves.cpp` | Each real player's character saved every `WrathRemix.SaveSeconds` (30), on either realm |
| `tools/bazaar.py`, `data/bazaar_ground.tsv` | The Bazaar's camp: its props, its people (the other Timerunners, the cook, porters, wardens, whelps, the dummies), where the story's own stand, what everyone does and says; heights from the floor's survey (`.remix ground`); writes `0023_bazaar.sql` |
| `tools/scenes.py` | The scenes as Python, from `STORY.md`'s script: who speaks, how long each line is given to be read, where people walk; writes `0014_scenes.sql` |
| `src/RemixReport.cpp` | Report Time Anomaly: the journal of each Timerunner's way (`remix_log`: logins, quests taken, objectives finished, quests turned in and dropped, creatures and quest objects spoken to, zones, levels, deaths, beside the transitions), and the report the app's add-on asks for from the game's menu or the toolbar's Help button ("WowRemix<tab>report <text>", an add-on whisper to the player itself; a long one in pieces, each but the last "report+ <text>", up to 2000 characters): where they are, the quest they're on, who gave it and who they've spoken to since, the rest of the quest log, the journal's last 300 steps; in Markdown (the title its first line) in the folder `WOW_REPORTS_DIR` names (ctl.sh: `files/run/reports`), for the app to send to GitHub as an issue |
| `src/ClientPatch.cpp` | The game's patch: as Wrath Remix starts, `Item.dbc`, `ItemExtendedCost.dbc` and `CurrencyTypes.dbc` (the server's, with the `*_dbc` tables' rows: Bronze, its currency row, its prices) in an MPQ in the app's client (`Data/patch-Y.MPQ`, beside `patch-Y.MPQ.wrath-remix`, which says it's Remix's), with `CreatureDisplayInfo.dbc` and the voices `remix_display_sound` gives some looks (`0026_voices.sql`: Moratari's, a blood elf of Silvermoon's instead of a death knight's); ChromieCraft's world server takes it out. `WOW_CLIENT_DIR` (ctl.sh) says where the client is |
| `data/sql/remix/world/` | The Remix world database's SQL (`wrath_remix_world_*.sql`): the module's tables, the NPCs and instructors, the prologue's quests (WRM-010 to 015) and the start at the Infinite Bazaar, Chapter I's guided path and Eternus's first kindness (WRM-017 to 019, in `0006_bridges`), the spawns, the level bands with the quests' and their rewards' levels (the quests shown at the Timerunner's own level, -1, their chapter's kept in `remix_quest_level` for the guide), Chapter I's transitions (WRM-006/016/026, WRM-121 to 124), the chapters and roads, the starter kits and class quest abilities (`0008_starter`), Bronze as a currency and the Bronze vendors (`0009_bronze`), the vendors' lists (`0010_vendors`, made by `tools/vendor_catalog.py`), Chapter II's story and transitions (`0011_chapter2`: WRM-201 to 208 and 211 to 218, the turn, and 221/222; where some quests take you as they're taken, `remix_departure`; who is seen where, visibility conditions), Chapter III's (`0012_chapter3`: WRM-301/311, 302, 303/313, 321), Chapter IV's (`0013_chapter4`: WRM-401 to 405, 421), the scenes (`0014_scenes`, made by `tools/scenes.py`), Chapter V's (`0015_chapter5`: WRM-501, 502, 521), Chapter VI's (`0016_chapter6`: WRM-601, 602, 621), Chapter VII's (`0017_chapter7`: WRM-701/711, 702, 721, 800), the end (`0018_ending`: WRM-801, 802, the conversations, Eternus's letter, Andormu and Nozari at the Bazaar), Eternus's small changes before the turn (`0019_eternus_kindnesses`: original quests' turn-in texts, from their originals in `remix_original_reward_text`, and his whispers, `remix_quest_note`), the Forge of Souls' Sylvanas (`0020_frozen_halls`), Moratari's Hourglass (`0021_hourglass`), the Bazaar's camp (`0023_bazaar`, made by `tools/bazaar.py`), the opening's people (`0024_intro`), the class lessons (`0025_lessons`: WRM-030 to 039), the voices (`0026_voices`). `0002_npcs` leaves the Bazaar's camp's creatures (770070 to 770099) and the opening's (770120 to 770149) to their own files, so each file can change on its own |
| `data/sql/remix/characters/` | The Remix characters database's: Timerunners (with the starter kit they've had, and where Moratari's Hourglass takes them back to) and their journal (`remix_log`: transitions, and from build 65 their way through the story, with the area of each line) |
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

Creatures 770001+ (the class lessons' credits 770067 to 770069, the Bazaar's camp 770070 to 770099 with its
training dummies 770087, instructors 770101+, the opening's 770120 to 770149, the Bronze vendors 770201+, their lists' hidden vendor
templates 770301+), quests 77nnn for the guide's WRM-nnn (77000 + nnn), NPC texts 7702nnn (the end's conversations 7703nnn),
spawns 7704nnn; Bronze is item 77001 (CurrencyTypes 342, bit 30), Moratari's Hourglass 77002, its prices ItemExtendedCost 7701
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
- `.remix sees ENTRY` (game masters): whether the story lets you see the nearest creature of that
  entry (its visibility conditions, as without game master mode), for CI's tests.
- `.remix hourglass` (game masters): where Moratari's Hourglass is and where it would take you back to.
- `.remix ground [RADIUS] [STEP]` (game masters): the floor's height in a grid around you (how
  `data/bazaar_ground.tsv` was surveyed).
- `.remix return` (game masters): the return to the Infinite Bazaar after the Lich King, as his
  ending does it (for when it doesn't, and for CI's tests).

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
   `0011_chapter2.sql` to `0013_chapter4.sql` for II to IV, `0015_chapter5.sql` to
   `0017_chapter7.sql` for V to VII, `0018_ending.sql` for the end; WRM-nnn is 77nnn): its `PrevQuestID` is the original quest it
   follows, a Moratari spawn stands by that quest's ender (`0004_spawns.sql`, in the ender's phase
   or one given), the original quest after the skipped block opens with either the transition or
   the block's own end (`conditions`, source type 19, with the block's `NextQuestID`s taken out),
   and `remix_bridge` says where it takes the Timerunner and which chapter it starts. Each file
   deletes and writes only its own rows (the server applies a file again when it changes).
3. A scene is written in `tools/scenes.py` (its quest, when it plays, its people and their lines)
   and `python3 tools/scenes.py` writes `0014_scenes.sql`; its people's creature templates are in
   `0002_npcs.sql`.
4. CI's server test plays the prologue (`warcraft/server/remix_prologue.steps`), buys from the
   Bronze vendors, plays the later chapters' transitions and scenes, the last hour, the return and
   the way home on each side
   (`warcraft/server/remix_campaign_alliance.steps`, `_horde.steps`: the original quests before
   them given with `.quest add`, `complete` and `reward`), checks the floor under every spawn
   (`warcraft/server/remix_check.py floors`) and the game's patch (`remix_check.py patch`, its own
   reading of the MPQ).
5. The vendors' lists: edit `tools/vendor_catalog.py` (its lists, the price ladder, the instances)
   and run it with `--mysql` (on the unchanged world database) and `--dbc` (the server's DBC
   folder); it rewrites `0010_vendors.sql` and `data/vendor_catalog.md`.
