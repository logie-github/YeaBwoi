# Quest drops (mod-quest-drops)

The user: "if you are doing a collect quest like go and get me 10 bear asses then the bears will
always drop the item at 100% drop rate rather than you kill 50 bears to get 10 asses".

An item a quest asks for (a quest drop: the loot tables' `QuestRequired`) drops from every creature
that has it while the quest needs it. The core rolls each loot entry's chance; the module's
`OnItemRoll` (a `GlobalScript` hook) makes a quest drop's chance 100. Each player who needs the
item sees it in the corpse's loot, as before, and those who don't, don't; the number that drops
(the loot table's count) is unchanged.

Left as they are: quest drops in a loot group (one item of the group drops, by its chances), where
100 would keep the group's other items from ever dropping. In AzerothCore's base world 3042 quest
drops roll on their own chance under 100 and become 100; 24 are in groups.

Both realms (ChromieCraft and Wrath Remix). `QuestDrops.Enable` (`conf/mod_quest_drops.conf.dist`,
default 1) turns it off.

Tested by the realm test (`scripts/test-warcraft-server.sh`, `warcraft/server/quest_drops.steps`): a
character on Graverobbers (Rot Hide Graverobbers drop its Embalming Ichor at 40%) gets one from each
of five kills.
