# This repository

Android apps that each play one game on an emulator, like a native game app: gesture controls
instead of on-screen buttons, a title screen styled from the user's art, straight into the game,
self-updating from this repository's GitHub releases.

| Folder | App | Package | Built on |
| --- | --- | --- | --- |
| `games/xd`, `games/colosseum` (+ `patches/`, `overlay/`) | Dolphin XD, Dolphin Colosseum | `com.logie.dolphinxd`, `com.logie.dolphincollosseum` | Dolphin, patched |
| `v3gold/` | V3 Gold (Persona 4 Golden) | `com.logie.v3gold` | Vita3K, patched |
| `azuredreams/` | Azure Dreams | `com.logie.azure` | SwanStation core + LibretroDroid |
| `parasiteeve/` | Parasite Eve | `com.logie.pe` | Beetle PSX HW core + LibretroDroid |
| `warcraft/` | World of Warcraft 3.3.5a: ChromieCraft and Wrath Remix realms, the game on Winlator | `com.logie.warcraft` | AzerothCore/ChromieCraft + playerbots (musl arm64), `mod-wrath-remix`, Winlator |
| `warcrafts/` | Warcraft (on the phone): Warcraft I and II (GOG) native, Warcraft III RoC/TFT 1.30, offline | `com.logie.warcrafts` | Stratagus + War1gus/Wargus (arm64), innoextract for GOG's installers; Winlator (the WoW app's commit) for WC3 |
| `wifecraft/` | Wifecraft: a Warcraft dating sim (visual novel), a playable demo | `com.logie.wifecraft` | Its own renderer for the game's models and world (OpenGL ES), the game's files fetched from wago.tools on the phone |

- **How the apps are made, tested and released:** `docs/single-game-apps.md`. Read it before
  building or changing an app.
- **The app being built:** `warcraft/PLAN.md` (the user's requirements, decisions, open questions,
  stages and where each stands). The user's art for it is in `warcraft/branding/`.
- **Wrath Remix** (a Legion Remix-style realm): the app's second realm, built from the user's guide
  (`warcraft/REMIX-GUIDE.md`) as `warcraft/modules/mod-wrath-remix` (its README). It must never
  change the ChromieCraft realm: its SQL goes into the Remix databases only, its module is off on
  realm 1. Where it stands and what's next: `warcraft/PLAN.md`.
- **The Skyborne** (WoW: Forever's race, both realms): `warcraft/modules/mod-skyborne` (its README);
  `tools/build_data.py` makes its data, committed.
- **The allied races** (void elves, nightborne, Lightforged Draenei, Zandalari Trolls: Blizzard's
  own models, ChromieCraft's realm only: the user, "Only chromie needs new races"), with WoW: Forever's new race and class
  pairs (undead paladins, human hunters, dwarf shamans, gnome priests, orc mages, troll warlocks, each also trained at
  another class's trainers) and Forever's racials for the eight races of 1.x: `warcraft/modules/mod-allied-races` (its README); `tools/races.py`, `tools/build_data.py` and
  `tools/quests.py` make its data, committed; `tools/package.py` fetches and converts the game's
  files as the server is packaged (never committed).
- **The Worgen and Goblins** (mod-worgoblin, built in, ChromieCraft's realm only: the user, "Use the
  popular worgen goblin module"): `warcraft/modules/mod-worgen-goblin` (its README) puts it in on top
  of the others' changes; `tools/build_data.py` makes its data, committed; `tools/package.py` makes
  its files for the game as the server is packaged (never committed). Next: Gilneas's and Kezan's
  starting areas from Cataclysm, joined to Wrath's world after them (`warcraft/PLAN.md`).
- **A home in Brill** (ChromieCraft's realm only: the house by Brill's road, Cedric Harrow's and Jamie Nore's quests,
  the bed, the furniture, the cellar, Corn the zombified pug and pets for every class, Carolai Anise's five quests: a yandere romance or a friendship, and
  as lovers her moving in; and Brill: A Place Among the Forsaken, the town's optional questlines and its six Community Projects:
  `tools/town/*.py`, made by `tools/build_town.py`, run by `src/BrillTown.cpp`): `warcraft/modules/mod-brill-home` (its README);
  `tools/build_sql.py` makes its SQL, committed.
- **The Murlocs and the Naga** (ChromieCraft's realm only: races 19, Alliance, and 20, Horde, both at level 10; the
  murlocs' own story from 10 to 55 in Nerglish, from the Wrathtail's slave pens at the Zoram Strand, ten chapters as data in
  `tools/murloc/ch*.py` (`SCHEMA.md`, `compile.py`), no one else's quests until its last; the naga, for now, from Darkshore
  through its night elf quests to the Horde at Zoram'gar, their own story in Azshara to be written):
  `warcraft/modules/mod-murloc-naga` (its README); `tools/build_data.py` makes its data from `tools/content.py` and the
  murlocs' chapters, committed; its patch (`Data/<locale>/patch-<locale>-T.MPQ`) is made at the realm's start from the
  client's own files (never committed).
- **Ralia's mod and Alternate Female Options** (both realms: the launcher's Game options switches, one at a time;
  the user's own zips, picked on the phone, never committed): `warcraft/modules/mod-ralia` (its README); `tools/build_data.py` makes `data/client/sources.txt`, committed.
  Its patch is `Data/<locale>/patch-<locale>-Z.MPQ`, made from the client at the realm's start.
- **No Gear** (was Body, was Base Model; both realms: the launcher's Game options switch, at realm startup): `warcraft/modules/mod-base-model` (its
  README): female character creation previews without starter gear while `files/base-model/enabled` is there. In-game gear is never hidden.
- **WoW: Forever's quests** (ChromieCraft's realm only): `warcraft/modules/mod-forever-quests` (its
  README); `tools/forever.py` makes its SQL from QuestieDB's Forever data and foreverchanges.pro's
  quest pages (the exact text), committed.
- Each app's `README.md` is its user manual; keep it current with every change.

## Rules

- Every change is committed and pushed; CI (`.github/workflows/build-<app>.yml`) builds it and
  publishes a release. Confirm the run and the release before reporting to the user.
- Build locally before pushing; run the app's unit tests. Local Gradle builds use
  `--init-script scripts/maven-mirror.init.gradle` (Maven Central rate-limits these machines).
- Never commit build outputs (`build/`, `.cxx/`, `<app>/android/core/`) or any game files.
- Tell the user what was tested and what wasn't (nothing here runs on a real phone).
- **Patch notes with every change** (the user: "Start including patch notes for future updates ... just do
  it moving forward"). For the World of Warcraft app, each change adds one file to `warcraft/notes/`
  (`YYYY-MM-DD-what.md`, a short Markdown list in plain words for the player: `warcraft/notes/README.md`);
  CI puts the files added since the previous release into the new release, and the app's update dialog
  shows every release's notes since the one installed. Never edit or remove a released note.
- **Merge your own work without asking** (the user: "I always want all changes to do this without me
  having to okay the merge as long as it doesn't overwrite other sessions"). Many sessions work at once,
  each on its own branch. When a change is done and pushed, open a pull request to the default branch
  and merge it yourself, as long as it overwrites nothing of other sessions':
  1. Fetch the default branch and merge it into your branch (never rebase or force-push over others' work).
  2. Look at what the default branch changed since your branch left it, and at the files you both
     changed. Where a conflict or a shared file is plainly two separate additions, keep both. Where
     it isn't (the same lines changed differently, or one change undoes the other's), stop and ask the user.
  3. Check the pull request's diff against the default branch is your change alone (nothing of
     others' reverted or missing), build and run the app's tests again, and push.
  4. Fetch the default branch once more just before merging; if it moved, go back to 1. Then merge
     (a merge commit, not a squash or rebase), and confirm the default branch's CI run and release.

## World of Warcraft content: work as a senior designer at Blizzard

The user: "For world of warcraft, put into your instructions to take on the role of a senior
designer at blizzard. You are an expert on quest design, world building, lore." For every quest,
NPC, object, zone change or line of text in the WoW modules (the Brill home and town, the races'
stories, Wrath Remix, anything placed in the world), work as Blizzard's own senior quest and world
designers would, to the standard of the game's own zones. Most of the fun of the game is finding an
immersive world; the user can tell at a glance what was added, and anything that reads as added
has failed.

- **Show, don't tell.** Scenery is never clickable and never explains itself: no gossip text on a
  lamp, a cart, a brazier, a mushroom, a sign or a book. The world says what it is by what it is
  and where it stands. An object is interactive only while a quest needs it (a quest object that
  sparkles and can be used only while that quest is in the log, as the game's own are); its words,
  if any, are the clue the quest is about, in a line or two, never a description of the object.
- **Research before placing.** Use the art the place already uses: the models the zone's own
  buildings and doodads are made of (Brill and Tirisfal are Lordaeron's human villages, Forsaken
  since: Lordaeron lamp posts, braziers, carts and fences, Duskwood-style buildings; never another
  race's or continent's look, such as a tauren lamp post or a dwarven brazier). Check every model's
  file path, style and size before using it.
- **Place with care.** Every object stands where someone in the world would have put it: lamps
  along a road, a cart beside a barn, a fire where people gather, debris where something
  happened. Check each spot against the ground's height, the roads, the buildings and doodads
  already there (the server's maps and vmaps), and the view from where players walk. Nothing
  floats, sinks, stands in a road or in a wall, or sits in an empty field for no reason. Fewer
  objects, placed well, beat many placed anywhere.
- **Write as the game writes.** Quest text in the voice of the game's own quests and of the
  character speaking, in the lore of the time and place; short, specific, never explaining what
  the player can see. Every quest has a reason in the world, a place it happens and a payoff.
- **Fit the lore.** Nothing contradicts the setting of the realm's expansion and the zone's story;
  new people, places and history are written as if Blizzard had made them.

## OctoWoW (was Turtle WoW)

The World of Warcraft app's second game: OctoWoW's client (1.18.1, build 7272, downloaded from
OctoWoW's own server and checked against its torrent: `turtle/core/ClientTorrent`, `ClientSync`)
on its own Tortoise-WoW realm, kept completely apart from the 3.3.5a game. It was Turtle WoW until
October 2026 (the user: "replace turtle wow with octo wow"); OctoWoW's server isn't published, so
the realm stays Tortoise-WoW. The code, folders and realm keep Turtle's names:
`warcraft/TURTLE.md` (the brief), `warcraft/turtle/` (its realm), `com.logie.warcraft.turtle` (its
code, every file operation through `turtle/core/SafeFs`). Its look is octowow.st's.

## Legion 7.3.5 (LegionCore)

The World of Warcraft app's Legion game is LegionCore 7.3.5 (The Legion Preservation Project,
`fed58f7`) on the phone, set up like ChromieCraft (native arm64 realm, the client on Winlator), at
the user's request ("use legioncore ... I don't need the spp ... copy how ChromieCraft works").
The code keeps the Felsong names (`com.logie.warcraft.felsong`, `files/felsong`,
`warcraft/felsong/`, `felsong-server-<hash>` releases from the `felsong-server` CI job);
`warcraft/FELSONG.md` is the brief. It takes the client and server data the Single Player
Project download left in `files/spp-legion` (`FelsongSpp`, Kind.SPP). The SPP launcher is out of
the picker; its code (`spp/`, `local/`: the headless Windows realm host, issue #53, `SPP.md`)
remains for The War Within's Draconic realm, which shares it. The game launches with Box64
0.4.4-legion (`scripts/build-box64.sh`, `warcraft/patches/box64/`). Legion phone gameplay is
unverified: do not claim it works without a phone test.

## Warcraft

A separate app (`warcrafts/`, `com.logie.warcrafts`): Warcraft I and II from the user's GOG files (the offline installer itself, unpacked by innoextract),
native on Android (Stratagus with War1gus/Wargus, `scripts/build-stratagus.sh`; the user ruled out
DOSBox and wants them Android-native), and Warcraft III (Reign of Chaos and The Frozen Throne, 1.30)
on Winlator, offline (Blizzard's names blocked in the runtime's hosts file), with the touch gestures
the user chose. The brief: `warcrafts/PLAN.md`; the manual: `warcrafts/README.md`. Its Winlator
comes from `scripts/prepare-winlator-app.sh warcrafts` (the WoW app keeps its own script).

## Wifecraft

A separate app (`wifecraft/`, `com.logie.wifecraft`): a dating sim laid out like a visual novel
(a speaker from the waist up, a text box with a name tab, choices), with the game's newer models
in the plain WoW look, their emotes as expressions, and the live 3D world behind them (the demo:
Broken Isles Dalaran; Alleria Windrunner and an original night elf huntress, Nyrelle Moonquill;
affection and five endings). The user's art for it is in `wifecraft/branding/`. Its menus are WoW's own current interface (talking-head box, gossip window,
red buttons, reputation bars as affection, achievement toast as an ending); all the game's files,
interface included, are fetched from wago.tools on the phone, never committed, built in or fetched
by CI. The brief, the screens and the stages:
`wifecraft/PLAN.md` (the mockups: https://claude.ai/artifact/B2i9Zi7R6hmyCJ8pNTjNVn); the manual:
`wifecraft/README.md`. The demo (A Day in Dalaran: cutscenes, five endings) is built and
unit-tested; `wifecraft/engine-test/` renders scenes on the desktop. Never tested on a phone.
