# Brill: A Place Among the Forsaken: the questlines

Each file here is one of Brill's optional questlines (`LINE`, a dict), made into the game's rows and the
module's tables by `tools/build_town.py` (run it after any change; its output,
`data/sql/chromiecraft/world/brill_town_world.sql`, is committed). `src/BrillTown.cpp` runs them.

The lines stand alone: none needs another to be started, any can be ignored for good, and none waits on
the player (its people keep their routines). A line may *acknowledge* another (a greeting or an option
whose condition names the other line's quest or mark), never require it, except where the user's design
says so (Beneath the Gallows opens from a discovery in the house's or the gravekeeper's line).

## A LINE

```python
LINE = {
    "title": "The Gravekeeper's Ledger",
    "quests": [...], "markers": [...], "npcs": [...], "credits": [...], "objects": [...],
    "gossip": [...], "phases": {...},
}
```

Names (an `id`) are the line's own; another line's are `"<line>.<id>"` (lines: house, grave, apprentice,
scarlet, customer, deathguard, well, gallows), the house's own quests `home.house`, `home.welcome`,
`home.bed`, `home.dog` and Carolai's `carolai.tonic` ... `carolai.lily`. A creature of the game's own is
its entry (an int). Ids are given out by the generator (a hundred of each kind for each line).

### quests

```python
{"id": "names", "title": "Names Among the Stones", "giver": "gravekeeper", "ender": "gravekeeper",
 "prev": None,                       # the quest before it in the line (its own name, or another's)
 "log": "...",                       # the log's one-line objective (LogDescription)
 "offer": "...",                     # what the giver says (QuestDescription): $n name, $B line break,
                                     # $g male:female; as quest text has them
 "progress": "...",                  # said while it isn't done (optional)
 "done": "...",                      # said as it's turned in
 "return": "Return to Mortimer Grave in Brill.",   # the log's line once done
 "objectives": [("kill", 1548, 8, ""),             # a creature of the game's (entry) or the line's (name)
                ("credit", "graves_credit", 5, "Neglected graves inspected")],   # a credit (below)
 "items": [(929, 3)],                # rewards: the game's items (optional; the same for every choice)
 "money": 2500, "xp": 3,             # copper; RewardXPDifficulty 1-8 (the game scales by level)
 "auto_complete": False,             # done as it's taken (a talk-only step)
}
```

Up to four objectives. A quest with none is turned in at its ender (a "speak with" step).

### markers

`["exposed", "spared", "escaped"]`: a choice's mark, a quest never given or seen, set as rewarded when the
choice is made (an option's or an object's `marker`). Conditions ask `"M:exposed"` (as `R:`).

### npcs

```python
{"id": "gravekeeper", "name": "Mortimer Grave", "subname": "Gravekeeper",
 "display": 1601,                    # a CreatureDisplayID of the game's (or a list: one at random)
 "level": 20, "faction": 68,         # 68: the Undercity's (friendly to the Horde)
 "flags": ["gossip", "quest"],       # and "vendor" (with "vendor": [item entries])
 "spawns": [[0, x, y, z, o]],        # the world (map 0), or ["house", lx, ly, dz, deg] in the house
 "phase": "newcomers",               # optional: only for players in that phase (below); in Brill only
 "wander": 0,                        # yards it wanders (0: stands)
 # a hostile one:
 "hostile": True, "faction": 14, "level": (9, 10), "rank": 1, "health": 3, "damage": 1.5, "gold": 50,
 "unit_class": 8, "type": 6,         # 6 undead, 7 humanoid, 1 beast...
 "spells": [(20791, (2000, 4000), (6000, 9000))],   # spell, first cast (ms, min-max), again (min-max)
}
```

A friendly creature with gossip rows (below) shows its own menu (its quests) and the line's options.

### credits

`{"id": "graves_credit", "name": "Neglected graves inspected"}`: an invisible creature a quest's
`("credit", ...)` objective counts; given by an option or an object.

### objects

```python
{"id": "grave", "name": "Neglected Grave", "display": 1171, "scale": 1,
 "spawns": [[0, x, y, z, o], ...], "phase": None,
 "use": [                            # clicked: the first row whose cond holds and has something to do
     {"cond": ["I:names"], "quest": "names", "credit": "graves_credit",
      "text": "(The name on the stone is ...)"},       # words shown (a gossip window)
     {"text": "(A weathered stone.)"},                 # otherwise
 ]}
```

An object with `use` is clickable (each spawn gives a credit once to a player); without, it's scenery
(type 5). `"type": 7` makes a chair. A spawn gives each quest's credit once (the same object may count
for two quests).

### gossip

```python
{"npc": "gravekeeper", "kind": "greet", "cond": ["R:last_bell"], "text": "..."}   # its greeting
{"npc": "gravekeeper", "kind": "greet", "texts": ["...", "...", "..."]}           # one at random
{"npc": "gravekeeper", "kind": "option", "cond": ["I:names"], "text": "(Read the ledger.)",
 "action": "credit", "quest": "names", "credit": "ledger_credit", "reply": "...", "emote": 1}
{"npc": 1499, "kind": "option", "cond": ["I:fate"], "text": "He should answer to the Magistrate.",
 "action": "choice", "quest": "fate", "credit": "fate_credit", "marker": "exposed", "reply": "..."}
{"npc": "shopkeeper", "kind": "option", "cond": ["R:closing"], "text": "Let me see your wares.", "action": "vendor"}
```

Greetings: the first whose cond holds (else the creature's own). Options: shown when their cond holds
(and, for `credit` and `choice`, while the quest is in the log, not done and that credit's still short;
and never once their `marker` is set). `say` only replies; `vendor` opens its wares; `upgrades` Jamie
Nore's furniture list. `furnish` (with `"arg"`: a furniture's index, 0 table and chairs, 1 wardrobe, 2 bookshelf, 3 rug,
4 stove) puts that piece in the player's house free, and counts as `credit`. `reply` is whispered (its $n, $B, $g as quest text). `emote`: an emote id (1 talk,
2 bow, 3 wave, 4 cheer, 5 exclamation, 6 question, 11 laugh, 18 cry, 20 beg, 23 flex, 273 yes, 274 no).

Don't give rows to Jamie Nore (2310) or Carolai Anise (2132): their menus are `src/BrillHome.cpp`'s.

### phases

```python
"phases": {"newcomers": [["R:last_bell"]],                  # rows: any row whose every cond holds
           "unrest": [["T:names", "!R:last_bell"], ["OWN"]]}
```

Fourteen phases for all the lines together (the house's own take the rest): use them only for what must
look different to one player and not another (someone who has moved in, a shop's goods out). A phased
creature or object must be in Brill (within 140 yards of 2275, 330), where the module puts players in
their phases; anything elsewhere is everyone's (phase 1).

### Conditions

`R:q` rewarded, `!R:q` not, `T:q` taken (ever), `A:q` in the log, `I:q` in the log and not done, `C:q`
done and not turned in, `M:mark` a mark set, `OWN` the house theirs, `DOG` the mastiff theirs, `LOVERS`,
`FRIENDS` (Carolai), `CAROLAI_HOME` (she lives in the house), `UPG:n` the house has furniture n (its
index, as `furnish`), `DAY:n` one day in n; `!` before any, not.
