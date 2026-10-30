-- mod-brill-home (ChromieCraft's world database alone: ctl.sh realm_sql). Made by tools/build_sql.py;
-- don't edit by hand. Written to be applied again: its own rows go first.

DELETE FROM `gameobject_template` WHERE `entry` BETWEEN 970100 AND 970109;
DELETE FROM `gameobject` WHERE `guid` BETWEEN 970100 AND 970149;
DELETE FROM `creature_template` WHERE `entry` BETWEEN 970110 AND 970119;
DELETE FROM `creature_template_model` WHERE `CreatureID` BETWEEN 970110 AND 970119;
DELETE FROM `creature` WHERE `guid` BETWEEN 970100 AND 970149;
DELETE FROM `quest_template` WHERE `ID` BETWEEN 970120 AND 970129;
DELETE FROM `quest_template_addon` WHERE `ID` BETWEEN 970120 AND 970129;
DELETE FROM `quest_offer_reward` WHERE `ID` BETWEEN 970120 AND 970129;
DELETE FROM `quest_request_items` WHERE `ID` BETWEEN 970120 AND 970129;
DELETE FROM `creature_queststarter` WHERE `quest` BETWEEN 970120 AND 970129;
DELETE FROM `creature_questender` WHERE `quest` BETWEEN 970120 AND 970129;
DELETE FROM `npc_text` WHERE `ID` BETWEEN 970140 AND 970149;

-- The house (the farmhouse Brill's own are), its furniture (each in its phase).
INSERT INTO `gameobject_template` (`entry`, `type`, `displayId`, `name`, `size`, `Data0`, `Data1`, `Data3`, `ScriptName`) VALUES
(970100, 5, 7432, 'Harrow Farmhouse', 1, 0, 0, 0, ''),
(970101, 2, 3471, 'Starter Bed', 1.0, 0, 0, 0, 'go_brill_home_bed'),
(970102, 5, 234, 'Kitchen Table', 1.0, 0, 0, 0, ''),
(970103, 7, 39, 'Chair', 1.0, 1, 1, 0, ''),
(970104, 5, 4, 'Wardrobe', 1.0, 0, 0, 0, ''),
(970105, 5, 188, 'Bookshelf', 1.0, 0, 0, 0, ''),
(970106, 5, 9405, 'Bearskin Rug', 1.0, 0, 0, 0, ''),
(970107, 5, 348, 'Pot-Bellied Stove', 1.0, 0, 0, 0, '');

INSERT INTO `gameobject` (`guid`, `id`, `map`, `zoneId`, `areaId`, `spawnMask`, `phaseMask`, `position_x`, `position_y`, `position_z`, `orientation`, `rotation0`, `rotation1`, `rotation2`, `rotation3`, `spawntimesecs`, `animprogress`, `state`, `Comment`) VALUES
(970100, 970100, 0, 85, 159, 1, 1, 2280.000, 485.000, 31.100, 5.1217, 0, 0, 0.548645, -0.836055, 0, 255, 1, 'mod-brill-home'),
(970101, 970101, 0, 85, 159, 1, 512, 2284.090, 483.613, 35.200, 5.1217, 0, 0, 0.548645, -0.836055, 0, 255, 1, 'mod-brill-home'),
(970102, 970102, 0, 85, 159, 1, 2048, 2280.018, 486.970, 35.200, 5.1217, 0, 0, 0.548645, -0.836055, 0, 255, 1, 'mod-brill-home'),
(970103, 970103, 0, 85, 159, 1, 2048, 2278.825, 486.452, 35.200, 0.4093, 0, 0, 0.203230, 0.979131, 0, 255, 1, 'mod-brill-home'),
(970104, 970103, 0, 85, 159, 1, 2048, 2281.210, 487.487, 35.200, 3.5509, 0, 0, 0.979131, -0.203230, 0, 255, 1, 'mod-brill-home'),
(970105, 970104, 0, 85, 159, 1, 4096, 2276.175, 488.791, 35.200, 5.1217, 0, 0, 0.548645, -0.836055, 0, 255, 1, 'mod-brill-home'),
(970106, 970105, 0, 85, 159, 1, 8192, 2284.758, 486.846, 35.200, 3.5509, 0, 0, 0.979131, -0.203230, 0, 255, 1, 'mod-brill-home'),
(970107, 970106, 0, 85, 159, 1, 16384, 2278.851, 483.629, 35.200, 5.1217, 0, 0, 0.548645, -0.836055, 0, 255, 1, 'mod-brill-home'),
(970108, 970107, 0, 85, 159, 1, 32768, 2282.154, 490.840, 35.200, 4.3363, 0, 0, 0.826826, -0.562457, 0, 255, 1, 'mod-brill-home');

-- Cedric Harrow (who leaves the house to the player), and the bed's credit (sleeping in it).
INSERT INTO `creature_template` (`entry`, `name`, `subname`, `gossip_menu_id`, `minlevel`, `maxlevel`, `faction`, `npcflag`, `speed_walk`, `speed_run`, `unit_class`, `unit_flags`, `type`, `flags_extra`, `ScriptName`) VALUES
(970110, 'Cedric Harrow', NULL, 0, 20, 20, 68, 2, 1, 1.14286, 1, 0, 6, 2, ''),
(970111, 'Slept in your new bed', NULL, 0, 1, 1, 35, 0, 1, 1, 1, 33554432, 10, 130, '');
INSERT INTO `creature_template_model` (`CreatureID`, `Idx`, `CreatureDisplayID`, `DisplayScale`, `Probability`) VALUES
(970110, 0, 3533, 1, 1),
(970111, 0, 11686, 1, 1);
INSERT INTO `creature` (`guid`, `id`, `map`, `zoneId`, `areaId`, `spawnMask`, `phaseMask`, `position_x`, `position_y`, `position_z`, `orientation`, `spawntimesecs`, `wander_distance`, `MovementType`, `Comment`) VALUES
(970100, 970110, 0, 85, 159, 1, 256, 2277.906, 483.546, 35.200, 3.3327, 120, 0, 0, 'mod-brill-home');

-- Jamie Nore (Brill's town hall) gives the quests and the house's upgrades (src/BrillHome.cpp).
UPDATE `creature_template` SET `npcflag` = `npcflag` | 3, `gossip_menu_id` = 970130, `ScriptName` = 'npc_brill_home_jamie' WHERE `entry` = 2310;

-- Their words.
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES
(970140, 'Brill\'s records are in order, the dead stay in their proper plots, and the living pay their taxes. Mostly.$B$BWhat can I do for you, $n?', 'Brill\'s records are in order, the dead stay in their proper plots, and the living pay their taxes. Mostly.$B$BWhat can I do for you, $n?', 1),
(970141, 'What would you like for the house? I\'ll have it carried over today.', 'What would you like for the house? I\'ll have it carried over today.', 1),
(970142, 'A wooden bed with a straw mattress and a quilt that\'s only been mended twice.', 'A wooden bed with a straw mattress and a quilt that\'s only been mended twice.', 1),
(970143, 'Mind the third board by the door. I never did get it to sit right.', 'Mind the third board by the door. I never did get it to sit right.', 1);

-- The quests: Cedric's house to Jamie; her welcome (done as it's taken); her bed.
INSERT INTO `quest_template` (`ID`, `QuestType`, `QuestLevel`, `MinLevel`, `QuestSortID`, `Flags`, `RewardXPDifficulty`, `RewardMoney`, `AllowableRaces`, `LogTitle`, `LogDescription`, `QuestDescription`, `AreaDescription`, `QuestCompletionLog`, `RequiredNpcOrGo1`, `RequiredNpcOrGoCount1`, `ObjectiveText1`) VALUES
(970120, 2, -1, 5, 159, 8, 2, 0, 0, 'The House by the Road', 'Speak with Jamie Nore in the Brill town hall.', 'You\'ve the look of someone who keeps their word, $n. Brill could use more of those.$B$BI built this house before the plague, for Marta and our two boys. When I rose again my feet carried me back here before my mind knew where it was going. I put the roof back on board by board. Thought it would help me remember who I was.$B$BIt didn\'t. It only reminds me who isn\'t here.$B$BThere\'s a ship leaving the Undercity for Vengeance Landing at the next dark of the moon, and I mean to be on it. Whatever the Lich King took from me, I\'ll take some of it back in Northrend.$B$BBrill took me in when the living would have burned me. I\'d like to leave it something better than an empty house. Let it go to someone the town can count on.$B$BJamie Nore at the town hall keeps the deeds. Tell her Cedric Harrow sent you, and that the house is yours.', '', 'Return to Jamie Nore in Brill.', 0, 0, ''),
(970121, 2, -1, 5, 159, 65544, 1, 0, 0, 'Welcome Home', 'Sign the deed to your house with Jamie Nore.', 'Then let\'s make it official. Sign here... and here. There.$B$BBrill isn\'t much, $n, and Tirisfal isn\'t kind. But a door that shuts and a roof that keeps the rain out are worth more than gold out here. Look after it, and it\'ll look after you.', '', '', 0, 0, ''),
(970122, 2, -1, 5, 159, 8, 2, 0, 0, 'Not on the Floor', 'Sleep in the bed in your new house, then return to Jamie Nore in Brill.', 'We can\'t have someone living in this town sleeping on the floor now, can we?$B$BThe innkeeper owed me a favor, and the inn had a spare bed taking up room in its cellar. I\'ve had it carried over to your house. Nothing grand: a wooden frame, a straw mattress, and a quilt that\'s only been mended twice.$B$BGo and try it out. Lie down, get a proper night\'s sleep, and then come back and tell me I was right.', '', 'Return to Jamie Nore in Brill.', 970111, 1, 'Slept in your new bed');
INSERT INTO `quest_template_addon` (`ID`, `PrevQuestID`) VALUES
(970120, 0), (970121, 970120), (970122, 970121);
INSERT INTO `quest_offer_reward` (`ID`, `Emote1`, `RewardText`) VALUES
(970120, 1, 'Cedric\'s house? He\'s giving it to you?$B$BHe stood in that doorway every night for three years, watching the road as if someone was coming home. I suppose someone finally is.$B$BWell. The deed\'s in order. Cedric always did keep his papers tidy, even dead.'), (970121, 2, 'Welcome home, neighbor.$B$BI walked past it this morning, mind you. Cedric took nearly everything with him; there isn\'t so much as a stool left. We\'ll fix that.'), (970122, 1, 'You look rested. Don\'t argue; you do.$B$BWhenever you\'d like to make the place more of a home, come and see me. I know a carpenter in the Undercity who owes me even more than the innkeeper did.');
INSERT INTO `quest_request_items` (`ID`, `EmoteOnComplete`, `EmoteOnIncomplete`, `CompletionText`) VALUES
(970122, 1, 1, 'Well? Have you tried it yet?');
INSERT INTO `creature_queststarter` (`id`, `quest`) VALUES
(970110, 970120), (2310, 970121), (2310, 970122);
INSERT INTO `creature_questender` (`id`, `quest`) VALUES
(2310, 970120), (2310, 970121), (2310, 970122);
