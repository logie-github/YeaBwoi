# A home in Brill (mod-brill-home)

ChromieCraft's realm alone (ctl.sh: `BrillHome.Enable`, and its SQL in ChromieCraft's databases only).

The user: "Add a small house facing the road, the same style house that's at Brill 59.28, 52.29. Add an
NPC inside and a quest where he decides to leave the house to you... Let you be able to speak to Jamie
Nore at the Brill town hall and she will give you upgrade options such as adding furniture... her first
quest be to go try out the bed... Sleeping will fade to black while doing a yawn emote... a small rested
xp bonus. Like a quarter of a level's worth."

## The house

Brill's farmhouse (the game's own `Duskwood_Human_Farm.wmo`, the house at Brill 59.28, 52.29; display
7432) as a gameobject, at (2280, 485) on the east side of the road north out of Brill, its door toward
the road. The place was chosen from the server's data (`tools/build_sql.py` says how):

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

## Files

- `data/sql/chromiecraft/world/brill_home_world.sql`: made by `tools/build_sql.py`; don't edit it.
- `data/sql/chromiecraft/characters/brill_home_characters.sql`: `brill_home` (guid, furniture, last
  rested sleep).
- `src/BrillHome.cpp`: the phases, Jamie Nore's menu, the bed; `.brillhome state` and `.brillhome sleep`
  for game masters (CI's realm test: `warcraft/server/brill_home.steps`).

## Next

The mastiff that's there when the player first wakes (Cataclysm's, backported), its quest, and the dog
as a hunter's pet for any class: `warcraft/PLAN.md`.
