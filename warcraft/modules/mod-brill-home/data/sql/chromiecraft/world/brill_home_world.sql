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
(970100, 33, 7432, 'Harrow Farmhouse', 1, 0, 0, 0, ''),
(970101, 2, 3471, 'Starter Bed', 1.0, 0, 0, 0, 'go_brill_home_bed'),
(970102, 5, 234, 'Kitchen Table', 1.0, 0, 0, 0, ''),
(970103, 7, 39, 'Chair', 1.0, 1, 1, 0, ''),
(970104, 5, 4, 'Wardrobe', 1.0, 0, 0, 0, ''),
(970105, 5, 188, 'Bookshelf', 1.0, 0, 0, 0, ''),
(970106, 5, 9405, 'Bearskin Rug', 1.0, 0, 0, 0, ''),
(970107, 5, 348, 'Pot-Bellied Stove', 1.0, 0, 0, 0, '');
DELETE FROM `gameobject_template_addon` WHERE `entry` = 970100;
INSERT INTO `gameobject_template_addon` (`entry`, `faction`, `flags`) VALUES (970100, 0, 32);

INSERT INTO `gameobject` (`guid`, `id`, `map`, `zoneId`, `areaId`, `spawnMask`, `phaseMask`, `position_x`, `position_y`, `position_z`, `orientation`, `rotation0`, `rotation1`, `rotation2`, `rotation3`, `spawntimesecs`, `animprogress`, `state`, `Comment`) VALUES
(970100, 970100, 0, 85, 159, 1, 1, 2289.000, 352.000, 31.120, 3.3360, 0, 0, 0.995279, -0.097051, 0, 255, 1, 'mod-brill-home'),
(970101, 970101, 0, 85, 159, 1, 512, 2286.773, 348.300, 35.220, 3.3360, 0, 0, 0.995279, -0.097051, 0, 255, 1, 'mod-brill-home'),
(970102, 970102, 0, 85, 159, 1, 2048, 2290.921, 351.563, 35.220, 3.3360, 0, 0, 0.995279, -0.097051, 0, 255, 1, 'mod-brill-home'),
(970103, 970103, 0, 85, 159, 1, 2048, 2290.669, 352.838, 35.220, 4.9068, 0, 0, 0.635144, -0.772394, 0, 255, 1, 'mod-brill-home'),
(970104, 970103, 0, 85, 159, 1, 2048, 2291.172, 350.287, 35.220, 1.7652, 0, 0, 0.772394, 0.635144, 0, 255, 1, 'mod-brill-home'),
(970105, 970104, 0, 85, 159, 1, 4096, 2293.519, 354.928, 35.220, 3.3360, 0, 0, 0.995279, -0.097051, 0, 255, 1, 'mod-brill-home'),
(970106, 970105, 0, 85, 159, 1, 8192, 2289.789, 346.957, 35.220, 1.7652, 0, 0, 0.772394, 0.635144, 0, 255, 1, 'mod-brill-home'),
(970107, 970106, 0, 85, 159, 1, 16384, 2287.906, 353.415, 35.220, 3.3360, 0, 0, 0.995279, -0.097051, 0, 255, 1, 'mod-brill-home'),
(970108, 970107, 0, 85, 159, 1, 32768, 2294.246, 348.650, 35.220, 2.5506, 0, 0, 0.956658, 0.291214, 0, 255, 1, 'mod-brill-home');

-- Cedric Harrow (who leaves the house to the player), and the bed's credit (sleeping in it).
INSERT INTO `creature_template` (`entry`, `name`, `subname`, `gossip_menu_id`, `minlevel`, `maxlevel`, `faction`, `npcflag`, `speed_walk`, `speed_run`, `unit_class`, `unit_flags`, `type`, `flags_extra`, `ScriptName`) VALUES
(970110, 'Cedric Harrow', NULL, 0, 20, 20, 68, 2, 1, 1.14286, 1, 0, 6, 2, ''),
(970111, 'Slept in your new bed', NULL, 0, 1, 1, 35, 0, 1, 1, 1, 33554432, 10, 130, '');
INSERT INTO `creature_template_model` (`CreatureID`, `Idx`, `CreatureDisplayID`, `DisplayScale`, `Probability`) VALUES
(970110, 0, 3533, 1, 1),
(970111, 0, 11686, 1, 1);
INSERT INTO `creature` (`guid`, `id`, `map`, `zoneId`, `areaId`, `spawnMask`, `phaseMask`, `position_x`, `position_y`, `position_z`, `orientation`, `spawntimesecs`, `wander_distance`, `MovementType`, `Comment`) VALUES
(970100, 970110, 0, 85, 159, 1, 256, 2288.026, 354.356, 35.220, 1.5470, 120, 0, 0, 'mod-brill-home');

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

-- The mastiff: beside the bed once the player has slept in it (its phase), its quest, and the
-- template its pet is made from; the quest's two credits. Its coats are its model's (patch-W).
INSERT INTO `creature_template` (`entry`, `name`, `subname`, `gossip_menu_id`, `minlevel`, `maxlevel`, `faction`, `npcflag`, `speed_walk`, `speed_run`, `unit_class`, `unit_flags`, `type`, `type_flags`, `family`, `flags_extra`, `ScriptName`) VALUES
(970112, 'Mastiff', NULL, 0, 1, 1, 35, 3, 1, 1.14286, 1, 0, 1, 0, 1, 2, 'npc_brill_home_mastiff'),
(970115, 'Mastiff', NULL, 0, 1, 1, 35, 0, 1, 1.14286, 1, 0, 1, 1, 1, 0, ''),
(970116, 'Mastiff', NULL, 0, 1, 1, 35, 0, 1, 1.14286, 1, 0, 1, 0, 1, 0, 'npc_brill_home_companion'),
(970117, 'Stabled Companion', NULL, 0, 1, 1, 35, 0, 1, 1.14286, 1, 0, 1, 0, 1, 0, 'npc_brill_home_companion'),
(970113, 'Played fetch with the mastiff', NULL, 0, 1, 1, 35, 0, 1, 1, 1, 33554432, 10, 0, 0, 130, ''),
(970114, 'Scratched the mastiff behind the ears', NULL, 0, 1, 1, 35, 0, 1, 1, 1, 33554432, 10, 0, 0, 130, '');
INSERT INTO `creature_template_model` (`CreatureID`, `Idx`, `CreatureDisplayID`, `DisplayScale`, `Probability`) VALUES
(970112, 0, 39700, 1, 1),
(970112, 1, 39701, 1, 1),
(970112, 2, 39702, 1, 1),
(970112, 3, 39703, 1, 1),
(970115, 0, 39700, 1, 1),
(970115, 1, 39701, 1, 1),
(970115, 2, 39702, 1, 1),
(970115, 3, 39703, 1, 1),
(970116, 0, 39700, 1, 1),
(970116, 1, 39701, 1, 1),
(970116, 2, 39702, 1, 1),
(970116, 3, 39703, 1, 1),
(970117, 0, 39700, 1, 1),
(970117, 1, 39701, 1, 1),
(970117, 2, 39702, 1, 1),
(970117, 3, 39703, 1, 1),
(970113, 0, 11686, 1, 1),
(970114, 0, 11686, 1, 1);
INSERT INTO `creature` (`guid`, `id`, `map`, `zoneId`, `areaId`, `spawnMask`, `phaseMask`, `position_x`, `position_y`, `position_z`, `orientation`, `spawntimesecs`, `wander_distance`, `MovementType`, `Comment`) VALUES
(970101, 970112, 0, 85, 159, 1, 1024, 2288.527, 351.295, 35.220, 3.8596, 60, 0, 0, 'mod-brill-home');
DELETE FROM `creature_model_info` WHERE `DisplayID` BETWEEN 39700 AND 39703;
INSERT INTO `creature_model_info` (`DisplayID`, `BoundingRadius`, `CombatReach`, `Gender`, `DisplayID_Other_Gender`) VALUES
(39700, 0.7, 1.5, 2, 0),
(39701, 0.7, 1.5, 2, 0),
(39702, 0.7, 1.5, 2, 0),
(39703, 0.7, 1.5, 2, 0);
-- The server's copy of the game's rows for it (the client's are in patch-W: data/client/dbc).
DELETE FROM `creaturemodeldata_dbc` WHERE `ID` = 3970;
INSERT INTO `creaturemodeldata_dbc` (`ID`, `Flags`, `ModelName`, `SizeClass`, `ModelScale`, `BloodID`, `FootprintTextureID`, `FootprintTextureLength`, `FootprintTextureWidth`, `FootprintParticleScale`, `FoleyMaterialID`, `FootstepShakeSize`, `DeathThudShakeSize`, `SoundID`, `CollisionWidth`, `CollisionHeight`, `MountHeight`, `GeoBoxMinX`, `GeoBoxMinY`, `GeoBoxMinZ`, `GeoBoxMaxX`, `GeoBoxMaxY`, `GeoBoxMaxZ`, `WorldEffectScale`, `AttachedEffectScale`, `MissileCollisionRadius`, `MissileCollisionPush`, `MissileCollisionRaise`) VALUES
(3970, 4096, 'creature\\mastiff\\mastiff.mdx', 0, 1.0, 1, 7, 18.0, 12.0, 1.0, 0, 0, 0, 43, 0.6111111044883728, 2.031277894973755, 1.399109959602356, -1.3119957447052002, -0.42800551652908325, -0.029394155368208885, 1.1700317859649658, 0.4534095823764801, 1.9144073724746704, 1.0, 1.0, 0.0, 0.0, 0.0);
DELETE FROM `creaturedisplayinfo_dbc` WHERE `ID` BETWEEN 39700 AND 39703;
INSERT INTO `creaturedisplayinfo_dbc` (`ID`, `ModelID`, `SoundID`, `ExtendedDisplayInfoID`, `CreatureModelScale`, `CreatureModelAlpha`, `TextureVariation_1`, `TextureVariation_2`, `TextureVariation_3`, `PortraitTextureName`, `BloodLevel`, `BloodID`, `NPCSoundID`, `ParticleColorID`, `CreatureGeosetData`, `ObjectEffectPackageID`) VALUES
(39700, 3970, 0, 0, 0.7, 255, 'MastiffDog_Gray', '', '', '', 0, 0, 0, 0, 0, 0),
(39701, 3970, 0, 0, 0.7, 255, 'MastiffDog_Black', '', '', '', 0, 0, 0, 0, 0, 0),
(39702, 3970, 0, 0, 0.7, 255, 'MastiffDog_Red', '', '', '', 0, 0, 0, 0, 0, 0),
(39703, 3970, 0, 0, 0.7, 255, 'MastiffDog_Yellow', '', '', '', 0, 0, 0, 0, 0, 0);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES
(970144, '(The dog looks happy to see you.)', '(The dog looks happy to see you.)', 1),
(970145, '(The mastiff sits by the door, watching you. It seems to be waiting until you have room for it.)', '(The mastiff sits by the door, watching you. It seems to be waiting until you have room for it.)', 1);
INSERT INTO `quest_template` (`ID`, `QuestType`, `QuestLevel`, `MinLevel`, `QuestSortID`, `Flags`, `RewardXPDifficulty`, `RewardMoney`, `AllowableRaces`, `LogTitle`, `LogDescription`, `QuestDescription`, `AreaDescription`, `QuestCompletionLog`, `RequiredNpcOrGo1`, `RequiredNpcOrGoCount1`, `ObjectiveText1`, `RequiredNpcOrGo2`, `RequiredNpcOrGoCount2`, `ObjectiveText2`) VALUES
(970123, 2, -1, 5, 159, 8, 1, 0, 0, 'A Visitor in the Night', 'Throw the stick for the mastiff three times, then give it a good scratch behind the ears.', '(A big mastiff is sitting beside your bed, as if it has been waiting a while for you to wake up. A muddy stick lies between its front paws.)$B$B(It noses the stick toward you. It looks at the door. It looks back at you.)', '', 'Return to the mastiff.', 970113, 3, 'Played fetch', 970114, 1, 'Scratched behind the ears');
INSERT INTO `quest_template_addon` (`ID`, `PrevQuestID`) VALUES (970123, 0);
INSERT INTO `quest_offer_reward` (`ID`, `Emote1`, `RewardText`) VALUES (970123, 0, '(The mastiff turns three circles on the floor beside your bed and flops down with a long, contented sigh.)$B$B(When you step toward the door, it gets up and follows.)');
INSERT INTO `quest_request_items` (`ID`, `EmoteOnComplete`, `EmoteOnIncomplete`, `CompletionText`) VALUES (970123, 0, 0, '(The mastiff drops the stick at your feet again and waits, ears up.)');
INSERT INTO `creature_queststarter` (`id`, `quest`) VALUES (970112, 970123);
INSERT INTO `creature_questender` (`id`, `quest`) VALUES (970112, 970123);
