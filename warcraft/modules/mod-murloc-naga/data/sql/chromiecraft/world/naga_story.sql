-- The naga's questline (mod-murloc-naga): made by tools/murloc/compile.py from tools/naga/ch*.py; edit those.
-- Safe to run again: every row of its ranges is taken out first.
DELETE FROM `creature_template` WHERE `entry` BETWEEN 990000 AND 991999 OR `entry` IN (975017, 975018, 975019);
DELETE FROM `creature_template_model` WHERE `CreatureID` BETWEEN 990000 AND 991999 OR `CreatureID` IN (975017, 975018, 975019);
DELETE FROM `creature_template_movement` WHERE `CreatureId` BETWEEN 990000 AND 991999 OR `CreatureId` IN (975017, 975018, 975019);
DELETE FROM `creature_text` WHERE `CreatureID` BETWEEN 990000 AND 991999 OR `CreatureID` IN (975017, 975018, 975019);
DELETE FROM `creature_addon` WHERE `guid` BETWEEN 9900000 AND 9929999;
DELETE FROM `creature` WHERE `guid` BETWEEN 9900000 AND 9929999;
DELETE FROM `smart_scripts` WHERE (`source_type` = 0 AND (`entryorguid` BETWEEN 990000 AND 991999 OR `entryorguid` IN (975017, 975018, 975019))) OR (`source_type` = 1 AND `entryorguid` BETWEEN 1990000 AND 1991999) OR (`source_type` = 9 AND `entryorguid` BETWEEN 990000000 AND 991999999) OR (`source_type` = 9 AND `entryorguid` BETWEEN 1990000000 AND 1991999999) OR (`source_type` = 9 AND `entryorguid` BETWEEN 975017000 AND 975017999) OR (`source_type` = 9 AND `entryorguid` BETWEEN 975018000 AND 975018999) OR (`source_type` = 9 AND `entryorguid` BETWEEN 975019000 AND 975019999);
DELETE FROM `creature_template_addon` WHERE `entry` BETWEEN 990000 AND 991999 OR `entry` IN (975017, 975018, 975019);
DELETE FROM `game_graveyard` WHERE `ID` BETWEEN 9900 AND 9939;
DELETE FROM `npc_text` WHERE `ID` BETWEEN 990000 AND 991999 OR `ID` IN (975017, 975018, 975019);
DELETE FROM `gossip_menu` WHERE `MenuID` BETWEEN 990000 AND 991999 OR `MenuID` IN (975017, 975018, 975019);
DELETE FROM `gossip_menu_option` WHERE `MenuID` BETWEEN 990000 AND 991999 OR `MenuID` IN (975017, 975018, 975019);
DELETE FROM `npc_vendor` WHERE `entry` BETWEEN 990000 AND 991999 OR `entry` IN (975017, 975018, 975019);
DELETE FROM `trainer_spell` WHERE `TrainerId` BETWEEN 990000 AND 990999;
DELETE FROM `trainer` WHERE `Id` BETWEEN 990000 AND 990999;
DELETE FROM `creature_default_trainer` WHERE `CreatureId` BETWEEN 990000 AND 991999 OR `CreatureId` IN (975017, 975018, 975019);
DELETE FROM `gameobject_template` WHERE `entry` BETWEEN 1990000 AND 1991999;
DELETE FROM `gameobject` WHERE `guid` BETWEEN 9930000 AND 9939999;
DELETE FROM `gameobject_loot_template` WHERE `Entry` BETWEEN 1990000 AND 1991999;
DELETE FROM `creature_loot_template` WHERE `Comment` = 'mod-murloc-naga: naga story';
DELETE FROM `item_template` WHERE `entry` BETWEEN 993000 AND 993799;
DELETE FROM `item_dbc` WHERE `ID` BETWEEN 993000 AND 993799;
DELETE FROM `quest_template` WHERE `ID` BETWEEN 992000 AND 992599;
DELETE FROM `quest_template_addon` WHERE `ID` BETWEEN 992000 AND 992599;
DELETE FROM `quest_offer_reward` WHERE `ID` BETWEEN 992000 AND 992599;
DELETE FROM `quest_request_items` WHERE `ID` BETWEEN 992000 AND 992599;
DELETE FROM `quest_details` WHERE `ID` BETWEEN 992000 AND 992599;
DELETE FROM `creature_queststarter` WHERE `quest` BETWEEN 992000 AND 992599;
DELETE FROM `creature_questender` WHERE `quest` BETWEEN 992000 AND 992599;
DELETE FROM `gameobject_queststarter` WHERE `quest` BETWEEN 992000 AND 992599;
DELETE FROM `gameobject_questender` WHERE `quest` BETWEEN 992000 AND 992599;
DELETE FROM `quest_poi` WHERE `QuestID` BETWEEN 992000 AND 992599;
DELETE FROM `quest_poi_points` WHERE `QuestID` BETWEEN 992000 AND 992599;
DELETE FROM `conditions` WHERE `Comment` = 'mod-murloc-naga: naga story';
DELETE FROM `spell_area` WHERE `spell` BETWEEN 994000 AND 994199;
DELETE FROM `creature_model_info` WHERE `DisplayID` BETWEEN 40200 AND 40299;
DELETE FROM `creaturedisplayinfo_dbc` WHERE `ID` BETWEEN 40200 AND 40299;
DELETE FROM `creaturedisplayinfoextra_dbc` WHERE `ID` BETWEEN 25000 AND 25099;
INSERT INTO `item_template` (`entry`, `name`, `Quality`, `bonding`, `stackable`, `maxcount`, `InventoryType`, `ItemLevel`, `RequiredLevel`, `Material`, `description`, `displayid`, `armor`, `dmg_min1`, `dmg_max1`, `delay`, `sheath`, `class`, `subclass`, `dmg_type1`, `MaxDurability`, `SellPrice`, `BuyPrice`, `block`, `ammo_type`, `RangedModRange`, `stat_type1`, `stat_value1`, `stat_type2`, `stat_value2`) VALUES
(993000, 'Coral-Tooth Knife', 2, 1, 1, 0, 13, 12, 7, 1, '', 20470, 0, 5.0, 10.0, 1500, 3, 2, 15, 0, 30, 183, 917, 0, 0, 0.0, 3, 2, 7, 1);
INSERT INTO `item_dbc` (`ID`, `ClassID`, `SubclassID`, `Sound_Override_Subclassid`, `Material`, `DisplayInfoID`, `InventoryType`, `SheatheType`) VALUES
(993000, 2, 15, -1, 1, 20470, 13, 3);
INSERT INTO `creature_template` (`entry`, `name`, `subname`, `gossip_menu_id`, `minlevel`, `maxlevel`, `faction`, `npcflag`, `speed_walk`, `speed_run`, `unit_class`, `unit_flags`, `type`, `type_flags`, `flags_extra`, `rank`, `HealthModifier`, `ManaModifier`, `ArmorModifier`, `DamageModifier`, `BaseAttackTime`, `RangeAttackTime`, `lootid`, `mingold`, `maxgold`, `AIName`, `ScriptName`, `detection_range`) VALUES
(975017, 'Lady Szaria', 'The Tide-Broken', 975017, 40, 40, 35, 3, 1.0, 1.14286, 1, 0, 7, 0, 0, 0, 1.0, 1.0, 1.0, 1.0, 2000, 2000, 0, 0, 0, 'SmartAI', '', 20.0),
(975018, 'Myrmidon Ssaveth', 'The Tide-Broken', 975018, 40, 40, 35, 3, 1.0, 1.14286, 1, 0, 7, 0, 0, 0, 1.0, 1.0, 1.0, 1.0, 2000, 2000, 0, 0, 0, 'SmartAI', '', 20.0),
(975019, 'Vyssa the Coral-Keeper', 'Innkeeper', 975019, 35, 35, 35, 65667, 1.0, 1.14286, 1, 0, 7, 0, 0, 0, 1.0, 1.0, 1.0, 1.0, 2000, 2000, 0, 0, 0, 'SmartAI', '', 20.0),
(990000, 'Isshara the Keeper', 'Keeper of Songs', 990000, 45, 45, 35, 3, 1.0, 1.14286, 1, 0, 7, 0, 0, 0, 1.0, 1.0, 1.0, 1.0, 2000, 2000, 0, 0, 0, 'SmartAI', '', 20.0),
(990001, 'Matron Szessh', 'Matron of the Crèche', 990001, 20, 20, 35, 3, 1.0, 1.14286, 1, 0, 7, 0, 0, 0, 1.0, 1.0, 1.0, 1.0, 2000, 2000, 0, 0, 0, 'SmartAI', '', 20.0),
(990002, 'Clawling', '', 0, 10, 11, 14, 0, 1.0, 1.14286, 1, 0, 1, 0, 0, 0, 1.0, 1.0, 1.0, 1.0, 2000, 2000, 0, 80, 220, '', '', 20.0),
(990003, 'Isshara', 'Handmaiden of the Queen', 0, 30, 30, 35, 0, 1.0, 1.14286, 1, 770, 7, 0, 0, 0, 1.0, 1.0, 1.0, 1.0, 2000, 2000, 0, 0, 0, '', '', 20.0),
(990004, 'Remembered Felguard', '', 0, 10, 11, 14, 0, 1.0, 1.14286, 1, 0, 3, 0, 0, 0, 1.0, 1.0, 1.0, 1.0, 2000, 2000, 0, 80, 220, '', '', 20.0),
(991600, 'Isshara the Keeper\'s question', '', 0, 1, 1, 35, 0, 1.0, 1.14286, 1, 33554944, 10, 0, 0, 0, 1.0, 1.0, 1.0, 1.0, 2000, 2000, 0, 0, 0, '', '', 20.0);
INSERT INTO `creature_template_model` (`CreatureID`, `Idx`, `CreatureDisplayID`, `DisplayScale`, `Probability`) VALUES
(975017, 0, 4980, 1.0, 1),
(975018, 0, 11269, 1.0, 1),
(975019, 0, 4973, 1.0, 1),
(990000, 0, 4981, 1.0, 1),
(990001, 0, 11268, 1.0, 1),
(990002, 0, 2600, 0.6957, 1),
(990003, 0, 18392, 1.0, 1),
(990004, 0, 14255, 1.1304, 1),
(991600, 0, 11686, 1.0, 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `lang0`, `Probability0`, `em0_0`, `em0_1`) VALUES
(975017, 'We were the Empress\'s once. Then Vashj\'s. Now we are no one\'s but our own, and I mean to keep it that way.', 'We were the Empress\'s once. Then Vashj\'s. Now we are no one\'s but our own, and I mean to keep it that way.', 0, 1, 0, 1),
(975018, 'I watch the water. Whatever comes, it comes from the water.', 'I watch the water. Whatever comes, it comes from the water.', 0, 1, 0, 1),
(975019, 'Coral, kelp, and a dry corner if you can find one. Sit. Eat something.', 'Coral, kelp, and a dry corner if you can find one. Sit. Eat something.', 0, 1, 0, 1),
(990000, 'Come closer, little one. I can\'t see you, but I can hear the sea in you.', 'Come closer, little one. I can\'t see you, but I can hear the sea in you.', 0, 1, 0, 1),
(990001, '(Stub.) The crèche has work for every tail.', '(Stub.) The crèche has work for every tail.', 0, 1, 0, 1),
(991601, '(Stub.) What did the water show you?', '(Stub.) What did the water show you?', 0, 1, 0, 1);
INSERT INTO `gossip_menu` (`MenuID`, `TextID`) VALUES
(975017, 975017),
(975018, 975018),
(975019, 975019),
(990000, 990000),
(990001, 990001),
(991601, 991601);
INSERT INTO `smart_scripts` (`entryorguid`, `source_type`, `id`, `link`, `event_type`, `event_phase_mask`, `event_chance`, `event_flags`, `event_param1`, `event_param2`, `event_param3`, `event_param4`, `event_param5`, `event_param6`, `action_type`, `action_param1`, `action_param2`, `action_param3`, `action_param4`, `action_param5`, `action_param6`, `target_type`, `target_param1`, `target_param2`, `target_param3`, `target_param4`, `target_x`, `target_y`, `target_z`, `target_o`, `comment`) VALUES
(975017, 0, 0, 0, 1, 0, 100, 0, 20000, 40000, 40000, 60000, 0, 0, 5, 1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 'mod-murloc-naga'),
(975018, 0, 0, 0, 1, 0, 100, 0, 27500, 55000, 55000, 82500, 0, 0, 5, 15, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 'mod-murloc-naga'),
(990000, 0, 0, 1, 1, 0, 100, 0, 22500, 45000, 45000, 67500, 0, 0, 5, 463, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 'mod-murloc-naga'),
(990000, 0, 1, 0, 61, 0, 100, 0, 0, 0, 0, 0, 0, 0, 4, 4024, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 'mod-murloc-naga'),
(990001, 0, 0, 0, 1, 0, 100, 0, 25000, 50000, 50000, 75000, 0, 0, 5, 51, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 'mod-murloc-naga'),
(990001000, 9, 0, 0, 0, 0, 100, 0, 0, 0, 0, 0, 0, 0, 5, 1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 'mod-murloc-naga'),
(990001000, 9, 1, 0, 0, 0, 100, 0, 0, 0, 0, 0, 0, 0, 5, 51, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 'mod-murloc-naga'),
(990001, 0, 1, 0, 19, 0, 100, 0, 992000, 0, 0, 0, 0, 0, 80, 990001000, 0, 1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 'mod-murloc-naga'),
(990001001, 9, 0, 0, 0, 0, 100, 0, 0, 0, 0, 0, 0, 0, 5, 1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 'mod-murloc-naga'),
(990001001, 9, 1, 0, 0, 0, 100, 0, 0, 0, 0, 0, 0, 0, 5, 15, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 'mod-murloc-naga'),
(990001, 0, 2, 0, 20, 0, 100, 0, 992000, 0, 0, 0, 0, 0, 80, 990001001, 0, 1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 'mod-murloc-naga'),
(990000000, 9, 0, 0, 0, 0, 100, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 7, 0, 0, 0, 0, 0, 0, 0, 0, 'mod-murloc-naga'),
(990000000, 9, 1, 0, 0, 0, 100, 0, 0, 0, 0, 0, 0, 0, 17, 468, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 'mod-murloc-naga'),
(990000000, 9, 2, 0, 0, 0, 100, 0, 0, 0, 0, 0, 0, 0, 4, 4024, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 'mod-murloc-naga'),
(990000000, 9, 3, 0, 0, 0, 100, 0, 2500, 2500, 0, 0, 0, 0, 75, 994200, 0, 0, 0, 0, 0, 7, 0, 0, 0, 0, 0, 0, 0, 0, 'mod-murloc-naga'),
(990000000, 9, 4, 0, 0, 0, 100, 0, 0, 0, 0, 0, 0, 0, 17, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 'mod-murloc-naga'),
(990000, 0, 2, 0, 19, 0, 100, 0, 992001, 0, 0, 0, 0, 0, 80, 990000000, 0, 1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 'mod-murloc-naga'),
(990000001, 9, 0, 0, 0, 0, 100, 0, 0, 0, 0, 0, 0, 0, 5, 1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 'mod-murloc-naga'),
(990000001, 9, 1, 0, 0, 0, 100, 0, 0, 0, 0, 0, 0, 0, 5, 463, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 'mod-murloc-naga'),
(990000, 0, 3, 0, 20, 0, 100, 0, 992001, 0, 0, 0, 0, 0, 80, 990000001, 0, 1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 'mod-murloc-naga'),
(990000, 0, 4, 0, 62, 0, 100, 0, 990000, 0, 0, 0, 0, 0, 98, 991601, 991601, 0, 0, 0, 0, 7, 0, 0, 0, 0, 0, 0, 0, 0, 'mod-murloc-naga'),
(990000002, 9, 0, 0, 0, 0, 100, 0, 0, 0, 0, 0, 0, 0, 1, 1, 0, 0, 0, 0, 0, 7, 0, 0, 0, 0, 0, 0, 0, 0, 'mod-murloc-naga'),
(990000, 0, 5, 6, 62, 0, 100, 0, 991601, 0, 0, 0, 0, 0, 80, 990000002, 0, 1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 'mod-murloc-naga'),
(990000, 0, 6, 0, 61, 0, 100, 0, 0, 0, 0, 0, 0, 0, 98, 991601, 991601, 0, 0, 0, 0, 7, 0, 0, 0, 0, 0, 0, 0, 0, 'mod-murloc-naga'),
(990000003, 9, 0, 0, 0, 0, 100, 0, 0, 0, 0, 0, 0, 0, 5, 463, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 'mod-murloc-naga'),
(990000, 0, 7, 8, 62, 0, 100, 0, 991601, 1, 0, 0, 0, 0, 72, 0, 0, 0, 0, 0, 0, 7, 0, 0, 0, 0, 0, 0, 0, 0, 'mod-murloc-naga'),
(990000, 0, 8, 9, 61, 0, 100, 0, 0, 0, 0, 0, 0, 0, 33, 991600, 0, 0, 0, 0, 0, 7, 0, 0, 0, 0, 0, 0, 0, 0, 'mod-murloc-naga'),
(990000, 0, 9, 0, 61, 0, 100, 0, 0, 0, 0, 0, 0, 0, 80, 990000003, 0, 1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 'mod-murloc-naga'),
(990000004, 9, 0, 0, 0, 0, 100, 0, 0, 0, 0, 0, 0, 0, 5, 15, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 'mod-murloc-naga'),
(990000, 0, 10, 11, 62, 0, 100, 0, 991601, 2, 0, 0, 0, 0, 80, 990000004, 0, 1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 'mod-murloc-naga'),
(990000, 0, 11, 0, 61, 0, 100, 0, 0, 0, 0, 0, 0, 0, 98, 991601, 991601, 0, 0, 0, 0, 7, 0, 0, 0, 0, 0, 0, 0, 0, 'mod-murloc-naga'),
(990000005, 9, 0, 0, 0, 0, 100, 0, 0, 0, 0, 0, 0, 0, 5, 1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 'mod-murloc-naga'),
(990000005, 9, 1, 0, 0, 0, 100, 0, 0, 0, 0, 0, 0, 0, 66, 0, 0, 0, 0, 0, 0, 7, 0, 0, 0, 0, 0, 0, 0, 0, 'mod-murloc-naga'),
(990000, 0, 12, 0, 19, 0, 100, 0, 992002, 0, 0, 0, 0, 0, 80, 990000005, 0, 1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 'mod-murloc-naga'),
(975019000, 9, 0, 0, 0, 0, 100, 0, 0, 0, 0, 0, 0, 0, 5, 1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 'mod-murloc-naga'),
(975019000, 9, 1, 0, 0, 0, 100, 0, 0, 0, 0, 0, 0, 0, 5, 467, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 'mod-murloc-naga'),
(975019, 0, 0, 0, 20, 0, 100, 0, 992002, 0, 0, 0, 0, 0, 80, 975019000, 0, 1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 'mod-murloc-naga'),
(990000, 0, 13, 14, 62, 0, 100, 0, 990000, 1, 0, 0, 0, 0, 72, 0, 0, 0, 0, 0, 0, 7, 0, 0, 0, 0, 0, 0, 0, 0, 'mod-murloc-naga'),
(990000, 0, 14, 0, 61, 0, 100, 0, 0, 0, 0, 0, 0, 0, 75, 994200, 0, 0, 0, 0, 0, 7, 0, 0, 0, 0, 0, 0, 0, 0, 'mod-murloc-naga');
INSERT INTO `creature_template_addon` (`entry`, `emote`) VALUES
(975018, 465),
(975019, 173),
(990003, 468);
INSERT INTO `npc_vendor` (`entry`, `slot`, `item`) VALUES
(975019, 0, 787),
(975019, 1, 4592),
(975019, 2, 4593),
(975019, 3, 4594),
(975019, 4, 8957),
(975019, 5, 21552),
(975019, 6, 159),
(975019, 7, 1179),
(975019, 8, 1205),
(975019, 9, 1708),
(975019, 10, 1645),
(975019, 11, 8766);
INSERT INTO `gossip_menu_option` (`MenuID`, `OptionID`, `OptionIcon`, `OptionText`, `OptionType`, `OptionNpcFlag`) VALUES
(975019, 0, 5, 'Make this place your home.', 8, 65536),
(975019, 1, 1, 'Let me see what you have.', 3, 128),
(990000, 0, 0, '(Stub.) Ask me what I saw.', 1, 1),
(991601, 0, 0, '(Stub.) The Empress, saving us.', 1, 1),
(991601, 1, 0, '(Stub.) Fire, and the Well, and the sea coming in.', 1, 1),
(991601, 2, 0, '(Stub.) Nothing. Only the sea.', 1, 1),
(990000, 1, 0, '(Stub.) Sing it again.', 1, 1);
INSERT INTO `creature` (`guid`, `id`, `map`, `zoneId`, `areaId`, `spawnMask`, `phaseMask`, `position_x`, `position_y`, `position_z`, `orientation`, `spawntimesecs`, `wander_distance`, `MovementType`, `Comment`) VALUES
(9900000, 990001, 1, 0, 0, 1, 2147485696, 2218.0, -6462.0, 6.82, 2.2, 120, 0.0, 0, 'mod-murloc-naga: Matron Szessh'),
(9900001, 990002, 1, 0, 0, 1, 2147485696, 2190.0, -6420.0, 2.58, 0.0, 30, 4.0, 1, 'mod-murloc-naga: Clawling'),
(9900002, 990002, 1, 0, 0, 1, 2147485696, 2181.0, -6431.0, 6.51, 1.37, 30, 4.0, 1, 'mod-murloc-naga: Clawling'),
(9900003, 990002, 1, 0, 0, 1, 2147485696, 2186.0, -6406.0, 0.23, 2.74, 30, 4.0, 1, 'mod-murloc-naga: Clawling'),
(9900004, 990002, 1, 0, 0, 1, 2147485696, 2176.0, -6416.0, 3.15, 4.11, 30, 4.0, 1, 'mod-murloc-naga: Clawling'),
(9900005, 990003, 1, 0, 0, 1, 2147483650, 2432.0, -6816.0, 117.24, 4.0, 120, 0.0, 0, 'mod-murloc-naga: Isshara'),
(9900006, 990004, 1, 0, 0, 1, 2147483650, 2412.0, -6846.0, 115.78, 0.0, 20, 0.0, 0, 'mod-murloc-naga: Remembered Felguard'),
(9900007, 990004, 1, 0, 0, 1, 2147483650, 2446.0, -6849.0, 106.0, 1.37, 20, 0.0, 0, 'mod-murloc-naga: Remembered Felguard'),
(9900008, 990004, 1, 0, 0, 1, 2147483650, 2422.0, -6862.0, 109.19, 2.74, 20, 0.0, 0, 'mod-murloc-naga: Remembered Felguard'),
(9900009, 990000, 1, 0, 0, 1, 2147485696, 2166.0, -6457.0, -1.18, 3.0, 120, 0.0, 0, 'mod-murloc-naga: Isshara the Keeper'),
(9900010, 975019, 1, 0, 0, 1, 2147485696, 2200.0, -6430.0, 2.06, 4.5, 120, 0.0, 0, 'mod-murloc-naga: Vyssa the Coral-Keeper'),
(9900011, 6491, 1, 0, 0, 1, 2147485696, 2215.0, -6440.0, 1.51, 0.0, 300, 0.0, 0, 'mod-murloc-naga: the tribe\'s spirit healer');
INSERT INTO `gameobject_template` (`entry`, `type`, `displayId`, `name`, `size`, `AIName`, `Data0`, `Data1`, `Data2`, `Data3`, `Data4`, `Data5`, `Data6`, `Data7`, `Data8`, `Data9`, `Data10`, `Data11`, `Data12`, `Data13`, `Data14`, `Data15`, `Data16`, `Data17`, `Data18`, `Data19`, `Data20`, `Data21`, `Data22`, `Data23`) VALUES
(1990000, 5, 10400, 'The Empress\'s Banner', 1.0, '', 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
INSERT INTO `gameobject` (`guid`, `id`, `map`, `zoneId`, `areaId`, `spawnMask`, `phaseMask`, `position_x`, `position_y`, `position_z`, `orientation`, `rotation0`, `rotation1`, `rotation2`, `rotation3`, `spawntimesecs`, `animprogress`, `state`, `Comment`) VALUES
(9930000, 1990000, 1, 0, 0, 1, 2147485696, 2226.0, -6471.0, 13.87, 2.2, 0, 0, 0.891207, 0.453596, 0, 100, 1, 'mod-murloc-naga: The Empress\'s Banner');
INSERT INTO `quest_template` (`ID`, `QuestType`, `QuestLevel`, `MinLevel`, `QuestSortID`, `Flags`, `RewardXPDifficulty`, `RewardMoney`, `AllowableRaces`, `LogTitle`, `LogDescription`, `QuestDescription`, `AreaDescription`, `QuestCompletionLog`, `StartItem`, `TimeAllowed`, `RequiredNpcOrGo1`, `RequiredNpcOrGoCount1`, `RewardItem1`, `RewardAmount1`, `ObjectiveText1`) VALUES
(992000, 2, 10, 8, 16, 8, 7, 750, 524288, 'Clawlings in the Egg Beds (stub)', 'Kill 3 Clawlings on the beach of Rethress Sanctum.', '(Stub: chapter 1\'s writer replaces this quest.) The clawlings are in the egg beds again. Go and see to it.', '', 'Return to Matron Szessh.', 0, 0, 990002, 3, 993000, 1, ''),
(992001, 2, 11, 9, 16, 8, 7, 825, 524288, 'The Deep Pool (stub)', 'In Isshara\'s memory, slay 2 Remembered Felguards.', '(Stub: chapter 1\'s writer replaces this quest.) Touch the water while I sing, little one. You will be there.', '', 'Return to Isshara the Keeper.', 0, 0, 990004, 2, 0, 0, ''),
(992002, 2, 11, 9, 16, 8, 7, 825, 524288, 'What the Water Showed (stub)', 'Answer Isshara\'s question, then speak with Vyssa the Coral-Keeper.', '(Stub: chapter 1\'s writer replaces this quest.) Tell me what you saw. Then go and eat something: Vyssa will feed you.', '', 'Return to Vyssa the Coral-Keeper.', 0, 0, 991600, 1, 0, 0, 'Isshara\'s question answered'),
(992599, 2, 1, 1, 0, 0, 0, 0, 524288, '(a quest not written yet)', '', '', '', '', 0, 0, 0, 0, 0, 0, '');
INSERT INTO `quest_template_addon` (`ID`, `PrevQuestID`, `SpecialFlags`, `ProvidedItemCount`, `ExclusiveGroup`, `AllowableClasses`) VALUES
(992000, 0, 0, 0, 0, 0),
(992001, 992000, 0, 0, 0, 0),
(992002, 992001, 0, 0, 0, 0);
INSERT INTO `quest_offer_reward` (`ID`, `Emote1`, `RewardText`) VALUES
(992000, 1, '(Stub.) Good. The eggs are safe for another night.'),
(992001, 1, '(Stub.) Now you have seen it. Now you know.'),
(992002, 1, '(Stub.) Isshara sent you? Then sit. Eat.');
INSERT INTO `quest_request_items` (`ID`, `EmoteOnComplete`, `EmoteOnIncomplete`, `CompletionText`) VALUES
(992000, 1, 6, '(Stub.) The beds are still crawling.'),
(992001, 1, 6, '(Stub.) The song isn\'t over.'),
(992002, 1, 6, '(Stub.) Well?');
INSERT INTO `quest_details` (`ID`, `Emote1`) VALUES
(992000, 1),
(992001, 1),
(992002, 1);
INSERT INTO `creature_queststarter` (`id`, `quest`) VALUES
(990001, 992000),
(990000, 992001),
(990000, 992002);
INSERT INTO `creature_questender` (`id`, `quest`) VALUES
(990001, 992000),
(990000, 992001),
(975019, 992002);
INSERT INTO `creature_text` (`CreatureID`, `GroupID`, `ID`, `Text`, `Type`, `Language`, `Probability`, `Emote`, `Duration`, `Sound`, `BroadcastTextId`, `TextRange`, `comment`) VALUES
(990000, 0, 0, '(Stub.) Listen.', 12, 0, 100, 1, 0, 0, 0, 0, 'mod-murloc-naga'),
(990000, 1, 0, '(Stub.) No. Listen again.', 12, 0, 100, 1, 0, 0, 0, 0, 'mod-murloc-naga');
INSERT INTO `conditions` (`SourceTypeOrReferenceId`, `SourceGroup`, `SourceEntry`, `SourceId`, `ElseGroup`, `ConditionTypeOrReference`, `ConditionTarget`, `ConditionValue1`, `ConditionValue2`, `ConditionValue3`, `NegativeCondition`, `ErrorType`, `ErrorTextId`, `ScriptName`, `Comment`) VALUES
(15, 990000, 0, 0, 0, 9, 0, 992002, 0, 0, 0, 0, 0, '', 'mod-murloc-naga: naga story'),
(15, 990000, 0, 0, 0, 28, 0, 992002, 0, 0, 1, 0, 0, '', 'mod-murloc-naga: naga story'),
(15, 990000, 1, 0, 0, 9, 0, 992001, 0, 0, 0, 0, 0, '', 'mod-murloc-naga: naga story'),
(15, 990000, 1, 0, 0, 28, 0, 992001, 0, 0, 1, 0, 0, '', 'mod-murloc-naga: naga story');
INSERT INTO `creature_addon` (`guid`, `emote`) VALUES
(9900010, 173);
INSERT INTO `game_graveyard` (`ID`, `Map`, `x`, `y`, `z`, `Comment`) VALUES
(9900, 1, 2215.0, -6440.0, 2.01, 'mod-murloc-naga: the crèche');
INSERT INTO `spell_area` (`spell`, `area`, `quest_start`, `quest_end`, `aura_spell`, `racemask`, `gender`, `autocast`, `quest_start_status`, `quest_end_status`) VALUES
(994000, 16, 0, 992599, -994002, 524288, 2, 1, 0, 63),
(994001, 16, 992599, 0, -994002, 524288, 2, 1, 64, 0),
(994001, 1237, 0, 0, -994002, 524288, 2, 1, 0, 0);
DELETE FROM `gameobjectdisplayinfo_dbc` WHERE `ID` BETWEEN 10400 AND 10599;
INSERT INTO `gameobjectdisplayinfo_dbc` (`ID`, `ModelName`, `Sound_1`, `Sound_2`, `Sound_3`, `Sound_4`, `Sound_5`, `Sound_6`, `Sound_7`, `Sound_8`, `Sound_9`, `Sound_10`, `GeoBoxMinX`, `GeoBoxMinY`, `GeoBoxMinZ`, `GeoBoxMaxX`, `GeoBoxMaxY`, `GeoBoxMaxZ`, `ObjectEffectPackageID`) VALUES
(10400, 'World\\EXPANSION01\\DOODADS\\GENERIC\\NAGA\\Banner\\NA_Banner01.mdx', 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -1, -1, 0, 1, 1, 3, 0);
-- Spells the story's quests teach (the module, src/MurlocNaga.cpp, teaches them as each is handed in).
CREATE TABLE IF NOT EXISTS `naga_story_learn` (`quest` INT UNSIGNED NOT NULL, `spell` INT UNSIGNED NOT NULL, PRIMARY KEY (`quest`, `spell`));
DELETE FROM `naga_story_learn`;
-- The story's graveyards, as the module (src/MurlocNaga.cpp) chooses them for the naga whose story isn't done.
CREATE TABLE IF NOT EXISTS `naga_story_graveyards` (`id` INT UNSIGNED NOT NULL PRIMARY KEY, `map` INT UNSIGNED NOT NULL, `x` FLOAT NOT NULL, `y` FLOAT NOT NULL, `z` FLOAT NOT NULL, `from_quest` INT UNSIGNED NOT NULL, `until_quest` INT UNSIGNED NOT NULL);
DELETE FROM `naga_story_graveyards`;
INSERT INTO `naga_story_graveyards` (`id`, `map`, `x`, `y`, `z`, `from_quest`, `until_quest`) VALUES (9900, 1, 2215.0, -6440.0, 2.01, 0, 0);
-- What one carries puts an aura on (the module, every second): an item in the bags, its aura.
CREATE TABLE IF NOT EXISTS `naga_story_carry` (`item` INT UNSIGNED NOT NULL, `aura` INT UNSIGNED NOT NULL, PRIMARY KEY (`item`, `aura`));
DELETE FROM `naga_story_carry`;
-- The memories (the module: going in and out, death and logout inside, leaving their ground): each its aura, its
-- phase bit, where one arrives, its ground (a centre and a radius), the pool one wakes at, its quests (the
-- current one's objectives start again after a death inside) and those whose completion wakes one.
CREATE TABLE IF NOT EXISTS `naga_story_memories` (`spell` INT UNSIGNED NOT NULL PRIMARY KEY, `bit` INT UNSIGNED NOT NULL, `map` INT UNSIGNED NOT NULL, `x` FLOAT NOT NULL, `y` FLOAT NOT NULL, `z` FLOAT NOT NULL, `o` FLOAT NOT NULL, `cx` FLOAT NOT NULL, `cy` FLOAT NOT NULL, `radius` FLOAT NOT NULL, `pool_map` INT UNSIGNED NOT NULL, `px` FLOAT NOT NULL, `py` FLOAT NOT NULL, `pz` FLOAT NOT NULL, `po` FLOAT NOT NULL, `quests` VARCHAR(255) NOT NULL, `wakes` VARCHAR(255) NOT NULL);
DELETE FROM `naga_story_memories`;
INSERT INTO `naga_story_memories` (`spell`, `bit`, `map`, `x`, `y`, `z`, `o`, `cx`, `cy`, `radius`, `pool_map`, `px`, `py`, `pz`, `po`, `quests`, `wakes`) VALUES
(994200, 2, 1, 2441.0, -6806.0, 115.61, 3.9, 2425.0, -6831.0, 120, 1, 2176.0, -6451.0, 1.6, 3.1, '992001', '992001');
-- What the module (src/MurlocNaga.cpp) reads of the story: its quests' range, and the last quest, which opens
-- the other quests to the naga (and the memories' marker and fade spells).
CREATE TABLE IF NOT EXISTS `naga_story` (`name` VARCHAR(32) NOT NULL PRIMARY KEY, `value` INT UNSIGNED NOT NULL);
REPLACE INTO `naga_story` (`name`, `value`) VALUES ('fade_spell', 994003), ('final_quest', 992599), ('first_quest', 992000), ('last_quest', 992599), ('marker_spell', 994002);
