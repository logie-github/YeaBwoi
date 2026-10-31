# The murlocs' questline: how a chapter is written

A chapter is a Python file, `tools/murloc/chNN_<name>.py`, holding plain data (no logic) that
`tools/murloc/compile.py` turns into the world database's rows (quests, people, objects, items,
scripts, gossip, map markers) and checks. Write every text in plain English: the compiler runs it
through Blizzard's Nerglish translator (`tools/nerglish.py`), so the player reads none of it. The
quest log's one-line goal (`goal`) stays English. Alliance and Horde people speak their own language
(`language`), which the game garbles for a murloc.

So every scene must work with the words gone: what people DO is the story. Use beats (below):
emotes, sounds, moving, handing things over, spell visuals, music, summoned creatures. Bracketed
stage directions are not shown to anyone; don't write them.

## The file

```python
CHAPTER = dict(key="ch1", title="The Pens", levels=(10, 15), map=1, zones=[331], hub="pens",
               start="first_step", start_facing=1.5,      # (the first chapter's: where a new murloc stands)
               alone_until="ch1_breakout")                # (its zones show the murlocs' phase alone, no world,
                                                          #  until that quest's reward)

PLACES = {                      # named points: (x, y) on the ground, or (x, y, z) exactly
    "pens": (4213.0, 1012.5),
    "overseer_rock": (4190.2, 990.1, 12.3),
}

NPCS = [
    dict(key="elder", name="Elder Mrrglmrgl", subname="Tribe Elder", look="oracle", level=12,
         side="tribe", role="quest", at="pens", facing=4.7,
         idle=[("state", "self", "sit"), ("every", 20000, [("emote", "self", "talk")])]),
    dict(key="overseer", name="Wrathtail Overseer", look="naga_male", level=(11, 12), side="naga",
         role="enemy", at=["overseer_rock", (4200, 1000), (4205, 1010)], respawn=120),
    dict(key="trainer_warrior", name="Grrbl", subname="Warrior Trainer", look="warrior", level=30,
         side="tribe", role="trainer:warrior", at=(4220, 1015), facing=1.2),
]
```

- `look`: a key of `LOOKS` in `tools/murloc/common.py`, or a CreatureDisplayInfo id. The tribe's
  murlocs are `murloc_<skin>` (grey, blue, green, pale, orange, gold, fel, red, purple, slate, abyssal,
  bronze, ice): the race's own murloc with emotes (talk, point, cheer, bow, wave, cry, laugh, beg,
  applaud, kneel, rude, kiss, flex, eat, question, exclamation, yes, no, sit, sleep, work, dance all
  play). The game's other murloc looks (`oracle`, `tidehunter`...) play no emote but attacks, spell
  casts, stun, cower and swimming: use them for enemies and crowds, never for someone who acts. Naga
  looks play talk, work, roar, battle roar and casts; people of the other races play everything.
- `level`: a number or (min, max).
- `side`: `tribe` (friendly to murlocs, can't be attacked), `naga` (hostile), `hostile` (hostile to
  murlocs, an enemy), `neutral` (yellow), or a FactionTemplate id; a sympathizer in a town is
  `faction:<Faction.dbc id>` (its standing decides: the town's own).
- `role`: `quest`, `vendor`, `trainer:<class>` (warrior, hunter, rogue, priest, shaman, mage),
  `profession:<skill>` (first_aid, cooking, fishing, skinning, mining, herbalism, leatherworking,
  tailoring, blacksmithing, alchemy, engineering, enchanting, jewelcrafting, inscription),
  `innkeeper`, `repair`, `banker`, `stable`, `auctioneer`, `travel`, `ambient`, `enemy`, `guard`; several
  joined by `+` (`"trainer:rogue+banker"`). A vendor sells `sells=[item entries]` (default: food).
  `travel`: `travel=[("<place>", "<quest key>" | None)]`, a gossip line each (teleports there; shown
  once the quest's done).
- `watch=(yards, [beats], cooldown ms)`: a player coming that near (in sight) starts the beats ("player"
  is them): a Lasher's hiss, point and lash; a slave hiding. Default cooldown 15000.
- `plural`: how an emote objective names them ("pen slaves": "/wave at pen slaves 0/5").
- `at`: a place, a point, or a list of them (one creature at each).
- `language`: `nerglish` (the default for murlocs: translated), or `common`, `orcish`, `darnassian`,
  `dwarvish`, `gnomish`, `taurahe`, `troll`, `gutterspeak`, `thalassian`, `draenei` (English as
  written; the game garbles it for murlocs), or `naga` (naga speak Common in the game; English,
  garbled likewise).
- `idle`: beats it repeats when nobody's doing anything with it (see `every`).
- `phase`: when it's there: `always` (the default), `("from", "<quest>")` (from that quest's
  reward on), `("until", "<quest>")` (until that quest's reward), `("during", "<quest>")` (while it's
  in the log), `("between", "<quest>", "<quest>")` (from the first's reward until the second's).

```python
OBJECTS = [
    dict(key="kelp", model="kelp", name="Kelp Bed", at=["kelp1", (4230, 990)], kind="loot",
         loot=[("kelp", 1, 2)], quest="ch1_kelp"),          # a loot object (only on the quest)
    dict(key="cage", model="cage", name="Slave Cage", at="pens", kind="use", quest="ch1_free",
         credit="ch1_free"),                                # used once for a quest's credit
    dict(key="hut", model="murloc_hut", name="Hut", at=(4210, 1020), kind="doodad", scale=1.1),
]
```
`model`: a key of `MODELS` in common.py or a game model path ("World\\...\\X.mdx"). Kinds: `loot`,
`use` (clicked once for a quest's credit; `beats=[...]` a scene it starts: the object's own beats are
sounds, summons, objects and auras, anyone else's (an NPC key: the nearest of them) anything),
`doodad`, `chair`, `mailbox`. `phase` as NPCs'.

```python
ITEMS = [
    dict(key="kelp", name="Slimy Kelp", icon="kelp", stack=20),                   # a quest item
    dict(key="spear", name="Pen-Breaker Spear", icon="spear", kind="weapon:spear", quality=2, level=14,
         stats=[("str", 3), ("sta", 2)]),                                          # a reward
]
```
An item with `aura="<aura key>"` puts that aura on the player when used (a disguise in a barrel);
`consumed=True` uses it up. Kinds: quest items (no `kind`), `weapon:<dagger|sword|axe|mace|spear|staff|fist|bow|gun|wand>`,
`armor:<cloth|leather|mail|plate>:<head|shoulder|chest|waist|legs|feet|wrist|hands|back>`,
`ring`, `neck`, `trinket`, `shield`, `offhand`, `food`, `drink`, `bag`. Stats: str, agi, sta, int,
spi, ap, sp (spell power), hit, crit, dodge, def, mp5, armor.

## Quests

```python
QUESTS = [
    dict(key="ch1_kelp", title="Kelp for the Cauldron", giver="cook", ender="cook", level=10,
         prev="ch1_wake",                       # the quest before (or a list: all of them)
         goal="Gather 6 Slimy Kelp from the shallows north of the pens.",
         items=[("kelp", 6)],                   # and/or kill / explore / interact / use / escort
         text="The pot is empty again. The naga take the fish and leave us the bones...",
         progress="Still nothing for the pot?",
         reward="Kelp! Now the little ones will eat tonight.",
         accept=[...beats...], complete=[...beats...],
         poi=["kelp_beds"],                     # where the map marks it
         tone="boring", path=None,              # path: "hero", "menace" or None
         rep=[(69, 150)],                       # Faction.dbc id, standing (negative for a menace)
         rewards=["spear"], choice=["ring_a", "cloak_b"], money=150),
]
```

Objectives (up to four kinds, four entries each):
- `kill=[("<npc key>" | <creature entry>, count)]`
- `items=[("<item key>" | <item entry>, count)]` and where they come from: `drops={"kelp":
  [("overseer", 40), (3711, 25)]}` (chance %, on the quest only), or loot objects (OBJECTS).
- `explore="<place>"` (with `radius`, default 15 yards): reach the place.
- `interact=[("<npc key>", "What you do, in English: a gossip line shown as Nerglish")]`: talk to them.
- `use=[("<item key>", "<npc key>" | "<place>", count)]`: use a quest item on a creature or at a place.
- `escort=("<npc key>", ["p1", "p2", ...])`: they follow you; reach the last place with them.
- `emotes=[("<npc key>", "wave", count, [beats])]`: the player's own emote at them (/wave, /bow,
  /dance, /roar, /cheer, /hug, /salute, /cry...: common.py TEXT_EMOTES), each answering with the beats
  (their own: "self"); one credit per emote, a few seconds apart per creature.

The giver gives a `source` item if the quest needs one (`source="cage_key"`).

More: `time=180` (seconds to finish it), `classes=["warrior"]` (only them), `repeatable=True` (a
chore), `details=["talk", "point"]` (the giver's emotes as it's offered), `reward_emotes=["cheer"]`
(the ender's as it's handed in), `plain_reward=True` (the reward text as written: the story's last
quest alone, the first words a murloc can read; finishing it teaches the murloc Common), `exclusive=N` (quests sharing N: taking one closes the others).

`level` is the quest's level; `min` defaults to level - 2. `xp` (1-8) defaults to 7; the last of a
chapter's chains and set pieces take 8.

## Beats

A beat is a tuple. `who` is `"self"` (the giver or ender), an NPC key (the nearest one of them), or
`"player"`.

| Beat | What happens |
| --- | --- |
| `("say", who, "English text", "emote"?, sound?)` | speaks (Nerglish, or its language); optional emote and sound |
| `("yell", who, "text", ...)` / `("whisper", ...)` | the same, louder / to the player alone |
| `("emote", who, "point")` | plays an emote once (names: common.py EMOTES, or an Emotes.dbc id) |
| `("state", who, "dance" \| "sit" \| "kneel" \| "sleep" \| "work" \| "none")` | keeps doing it |
| `("sound", who, <SoundEntries id or name>)` | plays a sound there |
| `("music", <SoundEntries id or name>)` | plays music to the player |
| `("visual", who, <SpellVisual or spell id or name>)` | a spell's look, nothing else |
| `("cast", who, <spell id or name>, target)` | casts it (target "player", "self" or an NPC key) |
| `("move", who, "<place>", "walk" \| "run")` | goes there |
| `("face", who, "player" \| "<place>")` | turns |
| `("jump", who, "<place>")` | jumps there |
| `("give", "<item key>", count)` | hands the player something |
| `("take", "<item key>", count)` | takes it back |
| `("summon", "<npc key>", "<place>", seconds)` | it appears for a while (with its own idle) |
| `("despawn", who)` | it goes |
| `("credit", "<quest key>")` | completes an `event` objective (`event="text"` in the quest) |
| `("teleport", "player", "<place>", facing?)` | moves the player there (a vision, a crossing) |
| `("aura", who, "<aura key>")` / `("unaura", who, "<aura key>")` | puts an aura (AURAS) on them / takes it off |
| `("object", "<object key>", "<place>", seconds)` | an object appears there for a while |
| `("follow", who)` | they follow the player |
| `("wait", ms)` | pause before the next beat |
| `("every", ms, [beats])` | (idle only) repeats beats on that period |

Order matters; each beat follows the last at once unless a `wait` comes between. Keep sequences
under a minute.

## Auras, more spawns, graveyards

```python
AURAS = [                       # spells of the story's own, shown on the bar with their name and text
    dict(key="brand", name="Salt-Scar", kind="mark", text="Branded by the naga.", icon=1126),
    dict(key="barrel", name="Barrel Disguise", kind="disguise", look=4553, seconds=300),  # another look
    dict(key="heavy", name="Heavy Coral", kind="slow", percent=40, seconds=60),
    dict(key="lashed", name="Lashed", kind="stun", seconds=2),
]   # kinds: mark, disguise (look: a CreatureDisplayInfo id or LOOKS key), slow, speed (percent), stun, root;
    # seconds: 0 lasts until taken off; icon: a spell id whose icon it shows; visual: a SpellVisual id

SPAWNS = [                      # more of someone already made: the hub's people at a later hub, or the
                                # game's creatures (an entry) in the murlocs' phase
    dict(npc="ch1:murlorga", at="hub_pot", phase=("from", "ch3_arrival"), facing=2.0),
    dict(npc=3711, at=[(4100, 950), (4110, 960)], wander=8, respawn=60),
    dict(object="ch0:mailbox", at="hub_post", phase=("from", "ch3_arrival")),   # an object likewise
]

GRAVEYARDS = [                  # where a murloc wakes as a ghost (the nearest on its map), with a spirit healer
    dict(at="hub_shore", name="the pens", phase=("until", "ch1_breakout")),
]
```

## Finding places

Positions come from the world: the research's gazetteer (`scratchpad/murloc/gazetteer.md`) and
`tools/murloc/where.py`, which says what's at a spot (land, shallows or deep water; the game's creatures
and objects nearest) and finds the game's creatures by name:

    WOW_SERVER_MAPS=... WOW_WORLD_SQL=... python3 tools/murloc/where.py 1 4213 1012
    WOW_SERVER_MAPS=... WOW_WORLD_SQL=... python3 tools/murloc/where.py find "Wrathtail" 1

Someone standing needs land (or a building's floor: give z, as a creature of the game's stands there);
an (x, y) is put on the ground, which under water is the sea floor (fine for murlocs and naga).

## Sounds, visuals

`common.py` names the usual sounds (`murloc_aggro`, `naga_hiss`, `splash`, `tavern`...); any
SoundEntries id works, and the research's `effects.md` lists them by kind, with ~140 spells NPCs can
cast for their look alone (bubbles, sparkles, frost, smoke, summons...) by id.

## Guards and towns

Towns are the game's. A murloc not in good standing is attacked by guards within a few yards, and
warned first in their own language (the module does it). A chapter only says which towns it sends
the player into (`TOWNS = ["Astranaar"]`), so the compiler can check standing quests.

## Names

Keys are `chN_<word>` for quests, plain words for the rest; unique within the chapter (the compiler
prefixes them with the chapter). Refer to another chapter's thing as `"ch2:<key>"`.
