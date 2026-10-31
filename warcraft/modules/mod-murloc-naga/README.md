# mod-murloc-naga: the Murlocs and the Naga

Two races of the app's ChromieCraft realm (not Wrath Remix's: "Only chromie needs new races"), both starting in
Darkshore at level 10 (the user: "Murloc to alliance and naga to horde", "start both of them in darkshore",
"Both start at level 10"):

| Race | Id | Side | Starts | Classes |
| --- | --- | --- | --- | --- |
| Murloc | 19 | Alliance | Their village on the island west of Auberdine | Warrior, Hunter, Rogue, Priest, Shaman, Mage |
| Naga | 20 | Horde | The renegades' camp on the island north of Auberdine | Warrior, Hunter, Rogue, Priest, Shaman, Mage, Warlock |

Races 19 and 20 were the game's Taunka and Northrend Skeleton, which only creatures use; in the game's tables they
move to 30 and 31 (the client's own rows of theirs, whatever their ids: their race, looks, hair shapes, facial hair
styles and voice rows), as the other race modules move theirs to 22 to 29.

## What they have

- **Models and looks**, from the game's own files (the Alternative HD Patch's when it's in: the user's look):
  - *Naga*: the game's naga character model (`Character\Naga_\`, men and women; the HD look has its own of both),
    its five skin colours a sex, and four more made from them (the colours of the game's creature naga: the naga
    lords' crimson, the sirens' gold and violet, and a grey for the men, silver for the women). The naga's one face
    is the game's. Added to that model as the realm makes the patch (`tools/naga_looks.py`): Blizzard's own naga
    headdresses and outfits (Battle for Azeroth's Nazjatar naga wear them; made for the naga's body, `_na_m` and
    `_na_f`):
    - **Headdress** (the hair style): none, and for men the myrmidon's crown and the warlord's helm, for women the
      sea witch's headdress (on the head's bone, where the newer naga's helm attachment is);
    - **Headdress color**: each headdress's colours (three or four, its item displays' textures);
    - **Features**: none, and each outfit in each of its colours (men: the myrmidon's harness in three, the warlord's
      plate in four; women: the sea witch's regalia in four), on the naga's own bones.
    The game's naga have no hair or facial hair of their own: their rows named textures the game hasn't, and are the
    headdresses' and the features' now.
  - *Murloc*: Blizzard's newer murloc (`creature/murloc2/murloc2.m2`, the murlocs of Cataclysm on) made a character's
    model: its fins cut from its body (the triangles its blue skin paints the fins' gold) and drawn in a colour of
    their own, its kin's parts (the murlocs built on the same skeleton: every bone of theirs at one of its bones'
    places) as geosets the creation screen picks (`tools/murloc_looks.py` says what each choice is):
    - **Skin color** (13): the body in every skin the game gives that model and its re-skinned kin (grey, blue,
      green, pale, orange, gold, fel, red, purple, slate, abyssal, bronze, ice);
    - **Eye Color** (the game's Face, 16): the eyes of each of those skins (silver, sky, jade, pearl, amber, gold,
      fel, blood, violet, lilac, abyssal, ember, frost) and three made from them (onyx, rose, sea green);
    - **Fins** (the hair style, 6): murloc2's crest as it is, short, long and swept back; the classic murloc's crest
      (the murlocs of 2004); the deep sea murloc's sail along its head and back;
    - **Fin Color** (the hair colour, 16): the fins of each skin (gold, crimson, plum, amethyst, rust, fel, olive,
      teal, abyssal, sand, frost) and five made from them (coral, sea green, azure, ebony, bone); the other models'
      fins recoloured to each;
    - **Features** (the facial hair style): none, the demon hunter murloc's horns, the deep sea murloc's lure, the
      demon hunter's blindfold, the shark-hooded murloc's outfit, the loincloth, and for women Maria's hair and
      dress (the Darkmoon Faire murloc in a dress).
    The murlocs have no hair: their hair choices are the fins' (the labels set by `MurlocNagaCreate.lua`). The files
    are the game's of today, fetched and made 3.3.5's as the server is packaged (`tools/murloc_model.py`, never in
    this repository); `tools/render_murloc.py` draws every choice to a contact sheet.
  - *How the choices are drawn*: the game draws a character's body with its composed texture (the skin colour's
    base, the face's two textures, armour), its hair with the hair row's texture, and shows one geoset of each group
    by the hair and facial hair styles. The murloc's body and eyes read the composed texture's face region (FaceUpper
    and FaceLower, 256 x 192 at the HD looks' scale): a face row (an eye colour, in each skin colour) fills it, the
    body's lower part in FaceLower and the eyes beside the body's upper part in FaceUpper. Its fins read the hair
    texture: a hair row (a fin style, in each fin colour) names the fins' colour. A fin style is a geoset of group 0
    (2 to 7); a feature one of group 1 (none 101, which the model hasn't: the game takes a facial style's geoset 0 as
    "leave the group as it is", and showed every feature at once). The eyes' own bones are the head's on the model
    (their animations draw the eyeballs back into the head as the murloc stands).
- **Armour** on a murloc: the game paints armour into a character's composed texture, in its regions for arms, chest
  and legs; the murloc's body and eyes read only the face region, which armour never paints, so a murloc stays a
  murloc in any armour. Helmets, shoulders and gear models need places on the model a creature hasn't: they don't
  show. Weapons in hand do.
- **Racials** (learned at creation from `playercreateinfo_spell_custom`):
  - Murloc: **Amphibious** (breathe underwater, swim 50% faster), **Mrrgglll!** (a war-gurgle: enemies within 8
    yards stunned for 2 sec, 2 min cooldown), **Slippery** (dodge +1%).
  - Naga: **Serpent's Grace** (breathe underwater, swim 50% faster), **Tail Sweep** (enemies within 8 yards stunned for
    2 sec, 2 min cooldown), **Scales of the Deep** (armour from items +5%).
- **Stats, standing, quests and items**: their kin's, the night elves' for the murlocs and the trolls' for the naga:
  every quest, item and condition open to the kin is open to them (the game's race masks, theirs OR'd in). The naga
  are also **neutral** to Darnassus, the Explorers' League (Ironforge) and the Gnomeregan Exiles (below).
- **Level 10, and their class's spells**: the world server makes a new character level 10 at its first login, at full
  health, and teaches it every spell its class's trainers teach up to its level; and again at every level after (they
  start far from their kin's trainers). A rank of a talent's spell once the talent is theirs.
- **Their homes**, seen by their own race alone: a phase of each race's in Darkshore and Ashenvale (`spell_area`: an
  aura with the world's phase and theirs), so nothing of the game's world changes for anyone else. The murlocs'
  village (huts on stilts from the Swamp of Sorrows, the murlocs' altar and great pearl) with its people, the naga raiders
  on the island to its south, the crawlers on its beaches, the shellfish, kelp, coral and giant clam around it, and
  what the murlocs steal in Auberdine; the renegades' camp (Outland's naga pagoda, banners, torches, crates), its
  people, and the Stormscale hunting them on the island to the north; the Darkspear scout at the Zoram Strand.
- **The intros**, seen once at a new character's first login: a flight over their home (each its own camera, written
  by `tools/cameras.py`), with the game's own sounds as narration: the murlocs' own voices for the murlocs (a village
  waking, a fry's dance, a warning, a war-gurgle, a cheer, 42 seconds); for the naga, Lady Vashj's (61 seconds, below).
- **Character creation**: twenty races, each side's ten in two columns (the allied races' and the Skyborne's layout),
  their icons (the game's murloc head and murloc oracle, Warlord Kalithresh and Lady Vashj), the Dwarves' scene for
  the murlocs and the Tauren's for the naga (framed for their height), their texts.
- **Voices**: at the UI's moments ("not enough mana"), the game's own creature sounds: the murlocs' gurgles, the naga's
  and the sirens' hiss. Their people gurgle (or hiss) as they're spoken to, and the murlocs cheer as a quest is handed in.
- **Add-ons**: Questie's race table gets them as their kin (the murlocs the night elves, the naga the trolls).

## The Murlocs' quests

Seventeen quests, three levels of them (10 to 13), every giver a murloc of the village, every word murloc. What a
quest wants is shown, not told: what the murloc does as it gurgles, in brackets ("(Blrrgl looks left. He looks right.
He leans so close you can smell the fish on his breath.)"). The last sends them to Auberdine's night elves, and on
into Darkshore's quests.

| Quest | Giver | What |
| --- | --- | --- |
| Mrrgl? | Elder Mrrglmrgl | Meet Grrmlgrrl the Tidehunter |
| Snap Snap | Grrmlgrrl | Kill 10 Pygmy Tide Crawlers |
| Scales in the Water | Grrmlgrrl | Kill 10 Stormscale Raiders on the island to the south |
| The Tidecaller | Grrmlgrrl | Kill Tidecaller Sssilith, their leader, for his trident |
| Mussel Memory | Murlorga | 8 Plump Mussels from the shallows |
| Kelp Wraps | Murlorga | 6 Fresh Kelp from the sea floor |
| Where's Sloshy? | Murlorga | Find the lost baby murloc under Auberdine's dock |
| Sloshy Goes Home | Sloshy | Take him home |
| Shinies! | Blrrgl the Snatcher | Steal 6 Elven Trinkets from Auberdine's crates |
| The Sweetest Bread | Blrrgl | Steal 5 Sweet Rolls from the baskets by Auberdine's inn |
| Gubber's Lures | Blrrgl | Steal Gubber Blump's box of lures from his dock |
| Glow of the Deep | Oracle Glubbub | 6 Glowing Coral from the sea floor |
| Eyes of the Thresher | Oracle Glubbub | 6 Thresher Eyes from Darkshore's threshers |
| The Oracle's Vision | Oracle Glubbub | Climb to the island's summit |
| A Gift from the Deep | Elder Mrrglmrgl | The Glimmering Pearl from the giant clam off the western shore |
| The Rite of the Pearl | Elder Mrrglmrgl | Place the pearl on the village's altar |
| Mrgl Mrgl, Land-Walker! | Elder Mrrglmrgl | Take the elder's scroll to Thundris Windweaver in Auberdine |

Rewards: the Tidehunter's Coral Spear, Murlorga's Kelp Wrap and the Elder's Pearl Charm.

## The Naga's way

The user's plan: a renegade naga, broken from the hostile naga, begins in Darkshore helping the night elves with their
own quests, then joins the Horde at Zoram'gar (levels 10 to 18 in Darkshore, 18 to 20 the way to the Horde).

| Level | Where | Quests |
| --- | --- | --- |
| 10 to 11 | The renegades' camp | **Blood in the Water** (Lady Szaria: 6 Stormscale Infiltrators on the island to the north), **Sealed Orders** (their orders, to Sentinel Glynda Nal'Shea in Auberdine: the night elves' grudging welcome) |
| 11 to 18 | Darkshore | The night elves', the Explorers' League's and the gnomes' quests there, as they are |
| 18 | The Grove of the Ancients, then the Zoram Strand | **The Tide Turns** (Onu: the Wrathtail stir in the south) |
| 18 to 19 | The Zoram Strand (Ashenvale) | **Strangers on the Shore** (Scout Zun'jin of the Darkspear: 4 Wrathtail Myrmidons and 4 Razortails), **An Unlikely Allegiance** (to Marukai at Zoram'gar) |
| 19 on | Zoram'gar, Ashenvale | **Naga at the Zoram Strand** (6442, Marukai's, the Horde's own), and the Horde's quests on from there |

Lady Vashj's words are the naga's intro: the game's own lines of hers (Serpentshrine Cavern's: the user's link is to
her lines, and the game's files have fourteen; the page itself couldn't be read here, it turns away programs), in an
order that tells the renegades' story: who the naga were and what they wanted ("Water... is life... We are the
Highborne, and the time has come at last for us to retake our rightful place in the world!"), the war on the surface
("I did not wish to lower myself by engaging your kind...", "Death to the outsiders!", "The time is now! Leave none
standing!", "Seek your mark!", "Victory to Lord Illidan!"), her fall ("Lord Illidan, I... I am... sorry."), and their own
way after it ("Be'lemer an-delei!"): 56 seconds of her, over a 61 second flight.

### How the core lets a Horde naga do the night elves' quests (AzerothCore's source, at the realm's commit)

Confirmed in the core's source and the world database it ships (`data/sql/base/db_world`, the commit in
`warcraft/server-version.env`):

- **The races**: the core reads the playable races from ChrRaces (`RaceMgr::LoadRaces`: every row without the
  not-playable flag; its `alliance` column the side), so new ids need no change to the core. The side is the race's
  faction template's team (`Player::TeamIdForRace`: ChrRaces' FactionID, the trolls' 116 for the naga: Horde).
- **Quests**: a quest is offered when the race is in its `AllowableRaces` mask (0: every race). Of Darkshore's 75
  quests (QuestSortID 148), all but eleven are 0: open to anyone their giver talks to. The eleven are the Alliance's
  mask (1101): the beached sea creatures' and turtles' (4722, 4723, 4725, 4727, 4728, 4730, 4731, 4732, 4733), The Red
  Crystal (4811) and As Water Cascades (4812); the naga's bit is OR'd into those.
- **Who talks to them**: whether a creature talks to (or attacks) a player is its reaction to the player
  (`Unit::GetReactionTo`, `Unit::GetFactionReactionTo`): for a creature whose faction has reputation, the player's own
  standing with that faction decides it (`ReputationMgr::GetRank`, no more than neutral while at war), not the sides.
  Every friendly creature of Darkshore is of such a faction (counted in the world database): Darnassus (the town, its
  sentinels, trainers, vendors, the quest givers: faction templates 79, 80, 124, 1076), Ironforge (the Explorers'
  League and Gubber Blump: 55) and the Gnomeregan Exiles (Wizbang Cranktoggle, Jenna Lemkenilli: 64, 875); and the
  escorts' (10, 231), friendly to all. A race's first standing with a faction is the first of the faction's four slots
  in Faction.dbc whose race mask has it (`ReputationMgr` from `FactionEntry`): the naga are put in the last, free slot of
  those three factions as neutral and not at war (and in no other slot of theirs), so those people talk to the naga and
  their guards leave them be, while the rest of the Horde stays hated. Their reaction to every other Horde character,
  and the Horde's and the Alliance's players' to the naga (faction templates, without reputation), is unchanged.
- **The quests that leave Darkshore for the Alliance's places** aren't offered to the naga (a condition: race, negated,
  on quest availability, source type 19): the Absent Minded Prospector's end in Ironforge (741, 942, 943), The Powers
  Below (968, Ironforge), the Tower of Althalaxx from the quest that goes to Delgren at Maestra's Post on (967, 970, 973,
  1140, 1167, 981, 1143), Journey to Astranaar (1133) and The Sleeper Has Awakened (5321, Ashenvale's Alliance camp).
- **The Horde's quests**: the trolls' race bit is in every Horde quest's mask (690, the Horde's), and every quest,
  item and condition with it gets the naga's: Naga at the Zoram Strand (6442, Marukai, 12719, Zoram'gar, quest level 19,
  min level 14, AllowableRaces 690) and everything after it, the Horde's flight masters (the core's team), vendors and
  trainers (Darkspear Trolls, Orgrimmar...: their standing is the trolls').
- **The transition's people** (checked in the world database): Onu (3616, the Grove of the Ancients, faction template
  124: Darnassus), Marukai (12719, faction 126: Darkspear Trolls), Sentinel Glynda Nal'Shea (2930), Thundris Windweaver
  (3649). The trigger is Onu: the southernmost of Darkshore's quest givers, at the Grove of the Ancients by Ashenvale's
  border, whose own quests are levels 17 to 20; The Tide Turns is offered there to a naga of level 17 and up, and
  Lady Szaria tells them to go to him.

What SQL alone can't do here, and what does it instead:

- **A race the game knows**: the client has its own ChrRaces, CharSections, CharBaseInfo... and can't make a race the
  server's tables alone have; the module's patch for the game (below) gives it the rows, the murloc's model and skins.
- **A Horde race some Alliance people talk to**: possible in SQL only through reputation (above). People of a faction
  without reputation (the Alliance's generic templates) react by the two sides' faction templates, the same for every
  race of a side; changing that for one race would need a player faction template of its own in the client too (and
  the core's `IfNormalReaction` hook changes the server's mind alone: the game would still show them hostile). None of
  Darkshore's friendly people are such, so none of that is needed.
- **Level 10 at creation, and their class's spells as they level**: the core's start level is one setting for every
  race (`StartPlayerLevel`); the module's script makes theirs 10 at the first login and teaches the trainers' spells.
- **Their homes seen by them alone**: a phase is an aura's; `spell_area` gives it by race and zone, in SQL, but the
  aura is a spell the client must know too (the patch's Spell rows).
- **The intros**: a flight is a camera the client plays (its file in the patch) and the world server follows (the
  same file in its data folder).

Assumed, not seen in the game (nothing here runs the 3.3.5 client):

- That the client draws a player of race 19 and 20 from the model its ChrRaces row names
  (`Character\<ClientFileString>\<Sex>\<ClientFileString><Sex>.m2` and the display ids: both match), and the
  murloc's composed texture as the naga's and the allied races' (512 x 512 skins, the face textures drawn over their
  region).
- That the client shows a reputation faction's people as the server's standing says (it gets the standing from the
  server: the same rule).
- That the client reads `Data/<locale>/patch-<locale>-T.MPQ` after `patch-S` and before `patch-U` (the other race
  modules' order, the Skyborne's `Game`).

## How it's made

- **`tools/build_data.py DBC_DIR WORLD_SQL_DIR MAPS_DIR`** reads the game's tables (the server's dbc folder), the core's
  world SQL and the server's maps (the ground's height, the water's: `tools/terrain.py`), and `tools/content.py` (the
  people, things, places and quests, every word of them), and writes `data/sql/chromiecraft/world/murloc_naga_world.sql`,
  `data/client/dbc/*.txt` (the rows to change in the game's tables), `data/client/looks.txt` (what textures and model the
  patch makes from the game's), `data/client/narration.txt` (the intros' sounds) and `data/client/Cameras/` (the intros'
  cameras, `tools/cameras.py`). Committed; run it again after changing it.
- As the world server starts, as the configuration is about to be read (`src/MurlocNaga.cpp`: first of the race modules'
  patches, the modules loaded in their folders' order), `src/MurlocNagaPatch.cpp` makes the game's
  `Data/<locale>/patch-<locale>-T.MPQ` from the client's own files: the tables with the rows changed, the naga's new
  colours, the icons, the screen's files, the cameras and the narrations (the game's WAVs mixed, 22050 Hz mono); and
  the murlocs' model and textures the server package brought (`data/files`, `murloc.files`: below).
- **`tools/murloc_model.py fetch DIR`** (run by `scripts/package-azerothcore.sh` as the server is packaged) fetches
  murloc2, its kin (`murloc2shark`, `murloc2maria`, `dhmurloc`, `murlocdeepsea`, the classic `murloc`), their skins
  and parts' textures from wago.tools by their file ids and writes the murlocs' files: murloc2 converted by Wrath
  Remix's converter (`mod-wrath-remix/tools/models.py`); its body's and eyes' texture coordinates moved into the
  composed texture's face region (the body's block 192 x 192, the eyes' 64 x 64); its fins cut from the body and, a
  copy a fin style, drawn with the hair texture (each spine lengthened or swept back from where it joins the body,
  or another model's fins); each part's triangles (the pieces of a batch's mesh by where they are, or a head's bones
  alone, or the bones murloc2 hasn't) added with its bones taken to murloc2's at the same places (a bone it hasn't,
  to its nearest parent it has), every section at most 64 bones; a track with one set of keys for every sequence
  given one a sequence (none for a sequence kept in its own .anim: its flag at byte 12). The face textures
  (`MurlocBody<skin>`, `MurlocEyes<eyes>_<skin>`) are palettized at the composed texture's scale; the fins'
  (`MurlocFins[Classic|Sail]<colour>`) 256 x 256. Its list, `murloc.files`, is written last; without it the patch
  says so and the murlocs aren't drawn.
- **`tools/naga_parts.py fetch DIR`** (run with it) fetches the naga's headdresses and outfits (`helm_*_na_m/_na_f`,
  `collections_*_na_m/_na_f`), their colours' textures (from their item displays) and the newer naga's skeleton, and
  writes each sex's parts (`Character/Naga_/<Sex>/Naga<Sex>Parts.dat`: each geometry's vertices with their bones'
  places, its triangles, the geosets and textures that draw it: an outfit's colours share its triangles, as the
  game reads a section's start in 16 bits) and textures, its list `naga.files` last. As the patch is made,
  `MergeParts` (`src/MurlocNagaPatch.cpp`) adds them to the client's own naga models (an HD look's, with one in):
  each vertex on the model's bone at its place (within 15 cm, or the parts aren't added), its triangles a section
  of each use's geoset, their textures its own (type 0) or the hair's (type 6, the headdress's colour).
  - **What the game draws into a character's composed texture** (the skin, faces, underwear) is made as the game's
    own character textures are: palettized (256 colours, every mip level) and at the scale of the skin it's drawn
    over (the HD looks' skins are 512: FaceLower 256 x 128, FaceUpper 256 x 64, underwear 256 x 128). The game's
    own naga faces and underwear are a quarter that size and compressed (never drawn by the game: the naga were
    never playable), and the murlocs' first faces were compressed: the character creation screen drew them green, and
    making the character broke the game's memory (crash report #110, at `CreateCharacter`). The game's naga rows
    also name hair, scalp and facial hair textures it hasn't (`Character\Naga_\Hair00_00.blp`, `ScalpLowerHair`,
    `ScalpUpperHair`, `FacialLowerHair`, `FacialUpperHair`): every texture the races' rows name that isn't in the game
    or the patch is left out of their look (the log says which). Read after the HD looks' patches (A, F, 6, E, Q, S:
  the Alternative HD Patch's SoundEntries; ChromieCraft's H, F, G, S, T) and before the Worgen and Goblins' patch-U, the
  allied races' patch-V, Wrath Remix's patch-Y and the Skyborne's patch-Z, which are made from the game with it in and
  carry its rows; it's made from the game without them (they come after it). Made again only when the client or the
  module change (the stamp in `patch-<locale>-T.MPQ.murlocnaga`); one that isn't the module's is left alone.
  `MurlocNaga.ClientPatch = 0` takes it out; on Wrath Remix (`MurlocNaga.Enable = 0`, set by ctl.sh) it's set aside.
  The world server reads the cameras from its own data folder's `Cameras` too (put there as the configuration is read), to
  follow the camera.
- The SQL is ChromieCraft's alone: in `data/sql/chromiecraft/world`, which ctl.sh (`realm_sql`) puts in ChromieCraft's
  world database's update list, last.

## Tests

- `tools/test_patch.cpp`: the patch made for a game folder and read back (the races, their looks and the creatures'
  races moved, the murloc's model reading the face region alone, its face textures, the naga's colours, the icons,
  the screen, the cameras, the narrations' lengths, every composed texture palettized at the skin's scale, every
  texture the races' rows name there; a second start keeping it). Run here on a stand-in of the
  Alternative HD Patch's client (its files out of its archive).
- `tools/test_screen.py`: `MurlocNagaCreate.lua` in Lua 5.1 with the Worgen and Goblins', the allied races' and the
  Skyborne's screen code after it: twenty buttons, their icons kept on their buttons, both sides' ten races in two
  columns, scenes, music, texts; Questie's table. CI runs it.
- `tools/test_data.py`: the committed data (the quests' givers, enders and items all there, every id in the module's
  ranges, the cameras as the world server reads them over the ground). CI runs it.
- CI's server test (`scripts/test-warcraft-server.sh`): the patch made before the other race modules', the races
  playable, the naga neutral to the three factions; a murloc warrior and a naga shaman start on their islands at level
  10 with their racials, language and class spells; then in the world (`warcraft/server/murloc_naga_*.steps`): the
  murloc's village seen and its first quests, Auberdine talking to it; the naga's first quest, Sentinel Glynda Nal'Shea
  offering it The Red Crystal and Thundris Windweaver his quests, Onu offering The Tide Turns at level 17 and the
  Darkspear scout taking it. On Wrath Remix, the patch set aside and no rows.
- Not tested: the game itself (the models drawn, the composed textures, the intros, the screen on a phone): nothing
  here runs the 3.3.5 client.
