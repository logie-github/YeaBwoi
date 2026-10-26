# Quest drops (mod-quest-drops)

The user: "if you are doing a collect quest like go and get me 10 bear asses then the bears will
always drop the item at 100% drop rate rather than you kill 50 bears to get 10 asses"; then: "At ones
that had ridiculously low drop rates, such as less than 25% chance push the drop rate to 75. For 10%
or less set it to 50" (for quest items, in place of the 100%; ChromieCraft's realm only).

An item a quest asks for (a quest drop: the loot tables' `QuestRequired`) drops, while the quest needs
it, 75% of the time where its chance was under 25%, 50% where it was 10% or less, and as it always did
from 25% up. The core rolls each loot entry's chance; the module's `OnItemRoll` (a `GlobalScript`
hook) changes a quest drop's chance before the roll. Each player who needs the item sees it in the
corpse's loot, as before, and those who don't, don't; the number that drops (the loot table's count)
is unchanged.

Left as they are: quest drops in a loot group (one item of the group drops, by its chances), where a
higher chance would take from the group's other items. In AzerothCore's base world, of the 5725 quest
drops, 24 are in groups.

ChromieCraft's realm alone: the app's `ctl.sh` sets `QuestDrops.Enable` (`conf/mod_quest_drops.conf.dist`)
to 1 for it and 0 for Wrath Remix's as each starts.

Game masters: `.questdrops chance <percent>` says what a quest drop of that chance rolls on the realm;
`.questdrops near <entry>` takes you beside the nearest living creature of an entry (300 yards at most).

Tested by the realm test (`scripts/test-warcraft-server.sh`, `warcraft/server/quest_drops.steps`): the
chances 40, 15, 10 and 5 roll 40, 75, 50 and 50; and a warlock on Piercing the Veil kills twenty
Rattlecage Skeletons, which drop its skull at 15%: nine skulls at least (at 75% that's missed about
once in a thousand tries, at 15% reached about once in a thousand).
