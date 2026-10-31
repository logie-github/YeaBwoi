# A home in Brill (mod-brill-home)

ChromieCraft's realm alone (ctl.sh: `BrillHome.Enable`, and its SQL in ChromieCraft's databases only).

The user: "Add a small house facing the road, the same style house that's at Brill 59.28, 52.29. Add an
NPC inside and a quest where he decides to leave the house to you... Let you be able to speak to Jamie
Nore at the Brill town hall and she will give you upgrade options such as adding furniture... her first
quest be to go try out the bed... Sleeping will fade to black while doing a yawn emote... a small rested
xp bonus. Like a quarter of a level's worth."

## The house

Brill's farmhouse (the game's own `Duskwood_Human_Farm.wmo`, the house at Brill 59.28, 52.29; display
7432) as a gameobject (type 33, a destructible building, as the game's own world buildings are spawned),
at (2289, 352) in Brill, map 59.3, 51.4 (the user's 59.35, 51.22, moved 5 yards west to clear the
field's fence), its door west toward the main road. The place was checked against the server's data
(`tools/build_sql.py` says how):

- the road from the town guards' patrol paths, which follow it;
- ground within 2 yards of level under the house (the terrain's heights);
- 18 yards from the nearest spawn (the seasonal camp: Midsummer's bonfire, Pilgrim's Bounty's turkeys)
  and 24 from the nearest tree;
- the doorway from the model's collision mesh (where its walls open, at 257.5 degrees of its own turn),
  turned to face the road; the floor (4.1 above the model's base) 0.2 above the highest ground under it.

Everyone sees the house. What's in it is each player's own, by phases: near the house (150 yards) the
module puts a player in the world's phase and the ones that are theirs; away from it, the world's alone.
A player under one of the game's own phasing spells is left as the spell has them.

| Phase | What | Whose |
| --- | --- | --- |
| 0x100 | Cedric Harrow | until his quest is turned in |
| 0x200 | the bed | from Jamie Nore's bed quest on |
| 0x800 | a kitchen table and two chairs (sitting works) | bought: 2 gold |
| 0x1000 | a wardrobe | bought: 1 gold 50 silver |
| 0x2000 | a bookshelf | bought: 1 gold |
| 0x4000 | a bearskin rug | bought: 50 silver |
| 0x8000 | a pot-bellied stove | bought: 3 gold |

## The quests

1. **The House by the Road** (Cedric Harrow, inside): a Forsaken who built the house in life for his
   family, rebuilt it after, and is leaving on the ship to Vengeance Landing; he leaves the house to
   someone the town can count on. To Jamie Nore.
2. **Welcome Home** (Jamie Nore): the deed signed; done as it's taken.
3. **Not on the Floor** (Jamie Nore): "We can't have someone living in this town sleeping on the floor
   now, can we?" The inn's spare bed carried over: sleep in it, then back to her.

After the deed, Jamie Nore's "I'd like to make my house more of a home." lists the furniture not
bought yet; each is paid for in gold and is in the house at once.

## The bed

Clicked: **Lie down to rest.** (onto the bed, lying along it, head at the headboard) or **Go to
sleep.**: a yawn, the screen fading to black and back (the app's add-on's fade), and asleep in the bed
until the player moves. Sleeping gives a quarter of the level's experience as rested experience (the
game's cap of a level and a half stays), at most once every six hours (`brill_home.last_rested`), and
the bed quest's credit.

Not checked on a phone: the house as a gameobject (the game's own houses are part of the map), how the
sleeping pose lies on the bed (assumed: lying back along the way the character faces), and the
furniture's turns (assumed: the models' fronts at their +x).

## The mastiff

The first time the player sleeps in the bed, they wake to a mastiff beside it (Cataclysm's Gilneas dog,
one of its four coats: gray, black, red, yellow). It doesn't talk; what it does is shown:
"(The dog looks happy to see you.)". Its quest, **A Visitor in the Night**: throw the stick for it three
times (it runs out the door after it and brings it back) and scratch it behind the ears. Turned in, the
dog is the player's pet, made as a hunter's tamed beast is, at their level, for any class:

- the game counts its keeper a hunter wherever it asks about pets (`OnPlayerIsClass`: the pet's kind,
  the stable master, Tame Beast, a pet's happiness and training), so it works as a hunter's pet does;
- they learn Call Pet, Dismiss Pet, Revive Pet, Feed Pet and Mend Pet (its ranks at a hunter's levels,
  learnt as they level), which go under General in their spellbook (not their class's);
- a player who already has a pet out or kept as their current one (a hunter's own, a warlock's demon)
  finds the dog waiting by the door: once there's room, "(Whistle for it to follow you.)".

Then any hunter trainer teaches them to tame beasts, as it does a hunter: their menu's "Teach me to
tame beasts, as you would a hunter." gives Tame Beast and Beast Lore (from level 10, a hunter's level
for it), under General too. Taming, the stable, a pet's food and talents are then a hunter's.

A hunter's dog is one more beside their own pet, never in its place (the user: "you can have your
current pets out but also the dog. To a limit of 2 for regular hunters and 3 out at a time for beast
masters"): a companion that follows them and fights what they fight (their target, or whatever is
fighting them), at their level; a beast master's (Bestial Wrath learnt) first stabled pet comes out
beside the two of them, in its own looks. Companions go while their hunter rides, flies or is dead, and
come back after; one left behind (more than 60 yards, another map) comes back to their side. They're
the game's guardians: the pet bar is their own pet's alone.

What it changes for classes with their own summons: a warlock, mage, shaman or death knight with the
dog counts as a hunter for pets too, so their own pets come back after a mount or a flight as a hunter's
do, and keep their spells' cooldowns when summoned.

The model: `tools/mastiff.py` fetches its files from wago.tools as the server is packaged
(`scripts/package-azerothcore.sh`; never in this repository) and makes them 3.3.5's with Wrath
Remix's converter (`mod-wrath-remix/tools/models.py`) and the starting areas' fixes
(`mod-worgen-goblin/tools/zones/package.py`'s `fix_models`). The world server puts it in the game as
`Data/patch-W.MPQ` (`src/BrillHomePatch.cpp`), with its CreatureModelData (3970, the wolf's sounds)
and its coats' CreatureDisplayInfo (39700 to 39703); made from the game with patches U and V in (they're
made without it), set aside on Wrath Remix's realm. Its own ids, not the starting areas' (3190 and
30210 to 30213, which go when the starting areas are off).

## Files

- `data/sql/chromiecraft/world/brill_home_world.sql`: made by `tools/build_sql.py`; don't edit it.
- `data/sql/chromiecraft/characters/brill_home_characters.sql`: `brill_home` (guid, furniture, last
  rested sleep).
- `src/BrillHome.cpp`: the phases, Jamie Nore's menu, the bed; `.brillhome state` and `.brillhome sleep`
  for game masters (CI's realm test: `warcraft/server/brill_home.steps`).

## Next

A 5th stable slot for hunters isn't made: the game's stable window has four, and the dog doesn't take
one (it lives outside the stable, out beside them), which is the hunter's one more pet. Jamie Nore may
have more quests later: `warcraft/PLAN.md`.
