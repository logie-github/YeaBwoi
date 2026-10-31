-- Brill: A Place Among the Forsaken (mod-brill-home; ChromieCraft's world database alone: ctl.sh
-- realm_sql). Made by tools/build_town.py from tools/town/*.py; don't edit by hand. Written to be
-- applied again: its own rows go first.

CREATE TABLE IF NOT EXISTS `brill_town_phase` (`bit` INT UNSIGNED NOT NULL, `ord` INT UNSIGNED NOT NULL, `cond` VARCHAR(255) NOT NULL DEFAULT '', PRIMARY KEY (`bit`, `ord`)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS `brill_town_gossip` (`npc` INT UNSIGNED NOT NULL, `ord` INT UNSIGNED NOT NULL, `kind` VARCHAR(8) NOT NULL, `cond` VARCHAR(255) NOT NULL DEFAULT '', `icon` TINYINT UNSIGNED NOT NULL DEFAULT 0, `text` TEXT, `text_id` INT UNSIGNED NOT NULL DEFAULT 0, `action` VARCHAR(16) NOT NULL DEFAULT '', `quest` INT UNSIGNED NOT NULL DEFAULT 0, `credit` INT UNSIGNED NOT NULL DEFAULT 0, `marker` INT UNSIGNED NOT NULL DEFAULT 0, `emote` INT UNSIGNED NOT NULL DEFAULT 0, `reply` TEXT, `arg` INT UNSIGNED NOT NULL DEFAULT 0, PRIMARY KEY (`npc`, `ord`)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS `brill_town_stage` (`type` TINYINT UNSIGNED NOT NULL, `value` INT NOT NULL, `ord` INT UNSIGNED NOT NULL, `cond` VARCHAR(255) NOT NULL DEFAULT '', PRIMARY KEY (`type`, `value`, `ord`)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS `brill_town_invis` (`kind` CHAR(1) NOT NULL, `guid` INT UNSIGNED NOT NULL, `type` TINYINT UNSIGNED NOT NULL, `value` INT NOT NULL, PRIMARY KEY (`kind`, `guid`, `type`)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
DELETE FROM `brill_town_stage`;
DELETE FROM `brill_town_invis`;
CREATE TABLE IF NOT EXISTS `brill_town_use` (`go` INT UNSIGNED NOT NULL, `ord` INT UNSIGNED NOT NULL, `cond` VARCHAR(255) NOT NULL DEFAULT '', `quest` INT UNSIGNED NOT NULL DEFAULT 0, `credit` INT UNSIGNED NOT NULL DEFAULT 0, `text_id` INT UNSIGNED NOT NULL DEFAULT 0, `marker` INT UNSIGNED NOT NULL DEFAULT 0, PRIMARY KEY (`go`, `ord`)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
DELETE FROM `brill_town_phase`;
DELETE FROM `brill_town_gossip`;
DELETE FROM `brill_town_use`;
DELETE FROM `creature_template` WHERE `entry` BETWEEN 970200 AND 971699;
DELETE FROM `creature_template_model` WHERE `CreatureID` BETWEEN 970200 AND 971699;
DELETE FROM `creature` WHERE `guid` BETWEEN 970200 AND 971699;
DELETE FROM `gameobject_template` WHERE `entry` BETWEEN 970200 AND 971699;
DELETE FROM `gameobject` WHERE `guid` BETWEEN 970200 AND 971699;
DELETE FROM `npc_text` WHERE `ID` BETWEEN 970200 AND 971699;
DELETE FROM `quest_template` WHERE `ID` BETWEEN 970200 AND 971699;
DELETE FROM `quest_template_addon` WHERE `ID` BETWEEN 970200 AND 971699;
DELETE FROM `quest_offer_reward` WHERE `ID` BETWEEN 970200 AND 971699;
DELETE FROM `quest_request_items` WHERE `ID` BETWEEN 970200 AND 971699;
DELETE FROM `creature_queststarter` WHERE `quest` BETWEEN 970200 AND 971699;
DELETE FROM `creature_questender` WHERE `quest` BETWEEN 970200 AND 971699;
DELETE FROM `smart_scripts` WHERE `entryorguid` BETWEEN 970200 AND 971699 AND `source_type` = 0;
DELETE FROM `gameobject_template_addon` WHERE `entry` BETWEEN 970200 AND 971699;
DELETE FROM `npc_vendor` WHERE `entry` BETWEEN 970200 AND 971699;
DELETE FROM `game_event_creature` WHERE `guid` BETWEEN 970200 AND 971699;
DELETE FROM `game_event_gameobject` WHERE `guid` BETWEEN 970200 AND 971699;
DELETE FROM `creature_default_trainer` WHERE `CreatureId` BETWEEN 970200 AND 971699;
DELETE FROM `conditions` WHERE `SourceTypeOrReferenceId` = 19 AND `SourceEntry` BETWEEN 970200 AND 971699;
DELETE FROM `conditions` WHERE `SourceTypeOrReferenceId` = 23 AND `SourceGroup` BETWEEN 970200 AND 971699;
DELETE FROM `game_event` WHERE `eventEntry` IN (230, 231, 232, 233);
INSERT INTO `game_event` (`eventEntry`, `start_time`, `end_time`, `occurence`, `length`, `holiday`, `description`, `world_event`, `announce`) VALUES
(230, '2020-01-01 06:00:00', '2037-12-31 00:00:00', 1440, 240, 0, 'Brill: morning', 0, 0),
(231, '2020-01-01 10:00:00', '2037-12-31 00:00:00', 1440, 480, 0, 'Brill: day', 0, 0),
(232, '2020-01-01 18:00:00', '2037-12-31 00:00:00', 1440, 240, 0, 'Brill: evening', 0, 0),
(233, '2020-01-01 22:00:00', '2037-12-31 00:00:00', 1440, 480, 0, 'Brill: night', 0, 0);

-- The New House
INSERT INTO `creature_template` (`entry`, `name`, `subname`, `gossip_menu_id`, `minlevel`, `maxlevel`, `faction`, `npcflag`, `speed_walk`, `speed_run`, `unit_class`, `unit_flags`, `type`, `rank`, `HealthModifier`, `DamageModifier`, `mingold`, `maxgold`, `flags_extra`, `AIName`, `ScriptName`) VALUES
(970200, 'Oswin Thatcher', 'Carpenter', 0, 25, 25, 68, 3, 1, 1.14286, 1, 0, 6, 0, 1, 1, 0, 0, 2, '', ''),
(970201, 'Cellar Rat', NULL, 0, 5, 6, 14, 0, 1, 1.14286, 1, 0, 1, 0, 0.7, 0.7, 0, 0, 0, '', ''),
(970202, 'Innkeeper Renee', NULL, 0, 20, 20, 68, 1, 1, 1.14286, 1, 0, 6, 0, 1, 1, 0, 0, 2, '', ''),
(970203, 'Apothecary Johaan', NULL, 0, 20, 20, 68, 1, 1, 1.14286, 1, 0, 6, 0, 1, 1, 0, 0, 2, '', ''),
(970204, 'Hamlin Atkins', NULL, 0, 20, 20, 68, 1, 1, 1.14286, 1, 0, 6, 0, 1, 1, 0, 0, 2, '', ''),
(970205, 'Deathguard Dillinger', NULL, 0, 20, 20, 68, 1, 1, 1.14286, 1, 0, 6, 0, 1, 1, 0, 0, 2, '', ''),
(970206, 'Broken furniture inspected', NULL, 0, 1, 1, 35, 0, 1, 1, 1, 33554432, 10, 0, 1, 1, 0, 0, 130, '', ''),
(970207, 'Rotten floorboards inspected', NULL, 0, 1, 1, 35, 0, 1, 1, 1, 33554432, 10, 0, 1, 1, 0, 0, 130, '', ''),
(970208, 'Leaking roof inspected', NULL, 0, 1, 1, 35, 0, 1, 1, 1, 33554432, 10, 0, 1, 1, 0, 0, 130, '', ''),
(970209, 'Cellar hatch inspected', NULL, 0, 1, 1, 35, 0, 1, 1, 1, 33554432, 10, 0, 1, 1, 0, 0, 130, '', ''),
(970210, 'Salvageable lumber', NULL, 0, 1, 1, 35, 0, 1, 1, 1, 33554432, 10, 0, 1, 1, 0, 0, 130, '', ''),
(970211, 'Bucket of nails', NULL, 0, 1, 1, 35, 0, 1, 1, 1, 33554432, 10, 0, 1, 1, 0, 0, 130, '', ''),
(970212, 'Cedric\'s letters read', NULL, 0, 1, 1, 35, 0, 1, 1, 1, 33554432, 10, 0, 1, 1, 0, 0, 130, '', ''),
(970213, 'The portrait found', NULL, 0, 1, 1, 35, 0, 1, 1, 1, 33554432, 10, 0, 1, 1, 0, 0, 130, '', ''),
(970214, 'The chest opened', NULL, 0, 1, 1, 35, 0, 1, 1, 1, 33554432, 10, 0, 1, 1, 0, 0, 130, '', ''),
(970215, 'The bricked-up wall examined', NULL, 0, 1, 1, 35, 0, 1, 1, 1, 33554432, 10, 0, 1, 1, 0, 0, 130, '', ''),
(970216, 'Candles and lamp oil', NULL, 0, 1, 1, 35, 0, 1, 1, 1, 33554432, 10, 0, 1, 1, 0, 0, 130, '', ''),
(970217, 'Linens', NULL, 0, 1, 1, 35, 0, 1, 1, 1, 33554432, 10, 0, 1, 1, 0, 0, 130, '', ''),
(970218, 'Clean straw', NULL, 0, 1, 1, 35, 0, 1, 1, 1, 33554432, 10, 0, 1, 1, 0, 0, 130, '', ''),
(970219, 'Furnishings chosen', NULL, 0, 1, 1, 35, 0, 1, 1, 1, 33554432, 10, 0, 1, 1, 0, 0, 130, '', ''),
(970220, 'Neighbors invited', NULL, 0, 1, 1, 35, 0, 1, 1, 1, 33554432, 10, 0, 1, 1, 0, 0, 130, '', '');
INSERT INTO `creature_template_model` (`CreatureID`, `Idx`, `CreatureDisplayID`, `DisplayScale`, `Probability`) VALUES
(970200, 0, 1638, 1, 1),
(970201, 0, 1141, 1.3, 1),
(970201, 1, 1418, 1.3, 1),
(970201, 2, 2176, 1.3, 1),
(970202, 0, 8190, 1, 1),
(970203, 0, 1565, 1, 1),
(970204, 0, 1635, 1, 1),
(970205, 0, 2855, 1, 1),
(970206, 0, 11686, 1, 1),
(970207, 0, 11686, 1, 1),
(970208, 0, 11686, 1, 1),
(970209, 0, 11686, 1, 1),
(970210, 0, 11686, 1, 1),
(970211, 0, 11686, 1, 1),
(970212, 0, 11686, 1, 1),
(970213, 0, 11686, 1, 1),
(970214, 0, 11686, 1, 1),
(970215, 0, 11686, 1, 1),
(970216, 0, 11686, 1, 1),
(970217, 0, 11686, 1, 1),
(970218, 0, 11686, 1, 1),
(970219, 0, 11686, 1, 1),
(970220, 0, 11686, 1, 1);
INSERT INTO `creature` (`guid`, `id`, `map`, `zoneId`, `areaId`, `spawnMask`, `phaseMask`, `position_x`, `position_y`, `position_z`, `orientation`, `spawntimesecs`, `wander_distance`, `MovementType`, `Comment`) VALUES
(970200, 970200, 0, 85, 0, 1, 1, 2284.412, 369.636, 34.200, 3.3598, 60, 0, 0, 'mod-brill-home town'),
(970201, 970201, 0, 85, 0, 1, 524288, 2275.821, 367.731, 35.800, 1.9635, 30, 2, 1, 'mod-brill-home town'),
(970202, 970201, 0, 85, 0, 1, 524288, 2270.962, 367.473, 35.800, 5.2797, 30, 2, 1, 'mod-brill-home town'),
(970203, 970201, 0, 85, 0, 1, 524288, 2273.958, 372.439, 35.800, 3.1853, 30, 2, 1, 'mod-brill-home town'),
(970204, 970201, 0, 85, 0, 1, 524288, 2273.890, 366.278, 35.800, 0.7418, 30, 2, 1, 'mod-brill-home town'),
(970205, 970201, 0, 85, 0, 1, 524288, 2271.983, 371.182, 35.800, 4.2325, 30, 2, 1, 'mod-brill-home town'),
(970206, 970201, 0, 85, 0, 1, 524288, 2277.536, 370.160, 35.800, 2.5744, 30, 2, 1, 'mod-brill-home town'),
(970207, 970202, 0, 85, 0, 1, 2097152, 2272.957, 367.711, 35.800, 0.7418, 60, 0, 0, 'mod-brill-home town'),
(970208, 970203, 0, 85, 0, 1, 2097152, 2273.502, 371.724, 35.800, 4.4070, 60, 0, 0, 'mod-brill-home town'),
(970209, 970204, 0, 85, 0, 1, 2097152, 2272.395, 370.249, 35.800, 1.2654, 60, 0, 0, 'mod-brill-home town'),
(970210, 970205, 0, 85, 0, 1, 2097152, 2277.601, 370.789, 35.800, 3.3598, 60, 0, 0, 'mod-brill-home town');
INSERT INTO `gameobject_template` (`entry`, `type`, `displayId`, `name`, `size`, `Data0`, `Data1`, `ScriptName`) VALUES
(970200, 2, 7525, 'Broken Furniture', 1, 0, 0, 'go_brill_town'),
(970201, 2, 20, 'Rotten Floorboards', 0.6, 0, 0, 'go_brill_town'),
(970202, 2, 239, 'Leaking Roof', 1, 0, 0, 'go_brill_town'),
(970203, 2, 8413, 'Cellar Hatch', 0.35, 0, 0, 'go_brill_town'),
(970204, 2, 220, 'Bundle of Letters', 1, 0, 0, 'go_brill_town'),
(970205, 2, 6530, 'Faded Portrait', 0.4, 0, 0, 'go_brill_town'),
(970206, 2, 1, 'Rusted Chest', 1, 0, 0, 'go_brill_town'),
(970207, 5, 6537, 'Lantern', 1, 0, 0, ''),
(970208, 5, 240, 'Candelabra', 1, 0, 0, ''),
(970209, 5, 5743, 'Storage Chest', 1, 0, 0, ''),
(970210, 5, 5911, 'Straw Dog Bed', 1.6, 0, 0, ''),
(970211, 2, 1108, 'Salvageable Lumber', 1, 0, 0, 'go_brill_town'),
(970212, 2, 2891, 'Bucket of Nails', 1, 0, 0, 'go_brill_town');
INSERT INTO `gameobject` (`guid`, `id`, `map`, `zoneId`, `areaId`, `spawnMask`, `phaseMask`, `position_x`, `position_y`, `position_z`, `orientation`, `rotation0`, `rotation1`, `rotation2`, `rotation3`, `spawntimesecs`, `animprogress`, `state`, `Comment`) VALUES
(970200, 970200, 0, 85, 0, 1, 262144, 2272.980, 368.535, 35.800, 2.3126, 0, 0, 0.915319, 0.402730, 0, 255, 1, 'mod-brill-home town'),
(970201, 970201, 0, 85, 0, 1, 262144, 2271.612, 366.388, 35.800, 1.7890, 0, 0, 0.779896, 0.625909, 0, 255, 1, 'mod-brill-home town'),
(970202, 970202, 0, 85, 0, 1, 262144, 2275.368, 372.547, 35.800, 1.7890, 0, 0, 0.779896, 0.625909, 0, 255, 1, 'mod-brill-home town'),
(970203, 970203, 0, 85, 0, 1, 1, 2275.495, 366.429, 35.800, 1.7890, 0, 0, 0.779896, 0.625909, 0, 255, 1, 'mod-brill-home town'),
(970204, 970204, 0, 85, 0, 1, 1, 2269.834, 368.862, 35.800, 1.7890, 0, 0, 0.779896, 0.625909, 0, 255, 1, 'mod-brill-home town'),
(970205, 970205, 0, 85, 0, 1, 1, 2274.197, 374.131, 36.700, 4.9306, 0, 0, 0.625909, -0.779896, 0, 255, 1, 'mod-brill-home town'),
(970206, 970206, 0, 85, 0, 1, 1, 2278.315, 366.645, 35.800, 3.3598, 0, 0, 0.994054, -0.108886, 0, 255, 1, 'mod-brill-home town'),
(970207, 970207, 0, 85, 0, 1, 1048576, 2271.225, 373.677, 35.800, 1.7890, 0, 0, 0.779896, 0.625909, 0, 255, 1, 'mod-brill-home town'),
(970208, 970207, 0, 85, 0, 1, 1048576, 2272.587, 364.760, 35.800, 1.7890, 0, 0, 0.779896, 0.625909, 0, 255, 1, 'mod-brill-home town'),
(970209, 970208, 0, 85, 0, 1, 1048576, 2273.609, 368.470, 36.800, 1.7890, 0, 0, 0.779896, 0.625909, 0, 255, 1, 'mod-brill-home town'),
(970210, 970209, 0, 85, 0, 1, 1048576, 2272.483, 373.546, 35.800, 4.9306, 0, 0, 0.625909, -0.779896, 0, 255, 1, 'mod-brill-home town'),
(970211, 970210, 0, 85, 0, 1, 1048576, 2270.159, 366.475, 35.800, 1.7890, 0, 0, 0.779896, 0.625909, 0, 255, 1, 'mod-brill-home town'),
(970212, 970211, 0, 85, 0, 1, 1, 2828.000, 1049.000, 110.900, 1.0000, 0, 0, 0.479426, 0.877583, 0, 255, 1, 'mod-brill-home town'),
(970213, 970211, 0, 85, 0, 1, 1, 2750.000, 780.000, 113.200, 2.0000, 0, 0, 0.841471, 0.540302, 0, 255, 1, 'mod-brill-home town'),
(970214, 970211, 0, 85, 0, 1, 1, 2258.000, 1308.000, 33.400, 0.5000, 0, 0, 0.247404, 0.968912, 0, 255, 1, 'mod-brill-home town'),
(970215, 970211, 0, 85, 0, 1, 1, 2446.000, 488.000, 44.300, 4.0000, 0, 0, 0.909297, -0.416147, 0, 255, 1, 'mod-brill-home town'),
(970216, 970211, 0, 85, 0, 1, 1, 2060.000, 560.000, 39.000, 3.0000, 0, 0, 0.997495, 0.070737, 0, 255, 1, 'mod-brill-home town'),
(970217, 970212, 0, 85, 0, 1, 1, 2268.000, 1306.000, 33.400, 0.0000, 0, 0, 0.000000, 1.000000, 0, 255, 1, 'mod-brill-home town');
INSERT INTO `brill_town_use` (`go`, `ord`, `cond`, `quest`, `credit`, `text_id`, `marker`) VALUES
(970200, 0, 'I970200', 970200, 970206, 970200, 0),
(970200, 1, '', 0, 0, 970201, 0),
(970201, 0, 'I970200', 970200, 970207, 970202, 0),
(970201, 1, '', 0, 0, 970203, 0),
(970202, 0, 'I970200', 970200, 970208, 970204, 0),
(970202, 1, '', 0, 0, 970205, 0),
(970203, 0, 'I970200', 970200, 970209, 970206, 0),
(970203, 1, 'I970203', 970203, 970215, 970207, 0),
(970203, 2, 'R970203', 0, 0, 970208, 0),
(970203, 3, '', 0, 0, 970209, 0),
(970204, 0, 'I970202', 970202, 970212, 970210, 0),
(970204, 1, '', 0, 0, 970211, 0),
(970205, 0, 'I970202', 970202, 970213, 970212, 0),
(970205, 1, '', 0, 0, 970213, 0),
(970206, 0, 'I970202', 970202, 970214, 970214, 0),
(970206, 1, '', 0, 0, 970215, 0),
(970211, 0, 'I970201', 970201, 970210, 970216, 0),
(970211, 1, '', 0, 0, 970217, 0),
(970212, 0, 'I970201', 970201, 970211, 970218, 0),
(970212, 1, '', 0, 0, 970219, 0);
INSERT INTO `brill_town_gossip` (`npc`, `ord`, `kind`, `cond`, `icon`, `text`, `text_id`, `action`, `quest`, `credit`, `marker`, `emote`, `reply`, `arg`) VALUES
(970200, 0, 'greet', '!O', 0, NULL, 970220, '', 0, 0, 0, 0, NULL, 0),
(970200, 1, 'greet', 'I970204 F80', 0, NULL, 970221, '', 0, 0, 0, 0, NULL, 0),
(970200, 2, 'greet', 'R970205 F80', 0, NULL, 970222, '', 0, 0, 0, 0, NULL, 0),
(970200, 3, 'greet', 'R970205', 0, NULL, 970223, '', 0, 0, 0, 0, NULL, 0),
(970200, 4, 'greet', 'R970203', 0, NULL, 970224, '', 0, 0, 0, 0, NULL, 0),
(970200, 5, 'greet', '', 0, NULL, 970225, '', 0, 0, 0, 0, NULL, 0),
(970200, 6, 'option', 'R970205 O', 1, 'I\'d like more furniture for the house.', 0, 'upgrades', 0, 0, 0, 0, NULL, 0),
(970200, 7, 'option', 'I970204 !U0', 0, 'A kitchen table and two chairs.', 0, 'furnish', 970204, 970219, 0, 0, 'A table. Good. Every house argues better over a table.', 0),
(970200, 8, 'option', 'I970204 !U1', 0, 'A wardrobe.', 0, 'furnish', 970204, 970219, 0, 0, 'A wardrobe. Cedar, so the moths stay out. The moths here are the size of bats.', 1),
(970200, 9, 'option', 'I970204 !U2', 0, 'A bookshelf.', 0, 'furnish', 970204, 970219, 0, 0, 'A bookshelf. A reader, then. Brill could use one.', 2),
(970200, 10, 'option', 'I970204 !U3', 0, 'A bearskin rug.', 0, 'furnish', 970204, 970219, 0, 0, 'A rug. I\'ll ask the trappers. The bear won\'t mind.', 3),
(970200, 11, 'option', 'I970204 !U4', 0, 'A pot-bellied stove.', 0, 'furnish', 970204, 970219, 0, 0, 'A stove. Proper heat. Your living guests will thank you, and your dead ones will pretend to.', 4),
(970200, 12, 'option', 'I970204', 0, 'It\'s furnished enough. Keep the wood for something else.', 0, 'credit', 970204, 970219, 0, 0, 'Suit yourself. I\'ll put it toward the shelves.', 0),
(2118, 13, 'option', 'I970204', 0, 'Oswin Thatcher sent me for candles and lamp oil.', 0, 'credit', 970204, 970216, 0, 0, 'For Cedric\'s old place? About time someone lit a lamp in it. Here. Tell Oswin they\'re on Jamie\'s account. She\'ll love that.', 0),
(2134, 14, 'option', 'I970204', 0, 'I need linens for my house. Not the gray ones.', 0, 'credit', 970204, 970217, 0, 0, 'Not the gray ones! Oswin\'s been talking. Fine. The cream, then. They were my mother\'s pattern. Don\'t bleed on them.', 0),
(10055, 15, 'option', 'I970204', 0, 'Could I have some clean straw? For my dog\'s bed.', 0, 'credit', 970204, 970218, 0, 0, 'Clean straw, for a living dog. Do you know how long it\'s been since anyone in Brill asked me for something for a living animal? Take two armfuls.', 0),
(5688, 16, 'option', 'I970205', 0, 'Come to a housewarming at my place.', 0, 'credit', 970205, 970220, 970206, 4, 'A party? In Brill? I\'ll bring the cider. Nobody can taste it, but everybody remembers the smell.', 0),
(1518, 17, 'option', 'I970205', 0, 'Would you come to a housewarming at my house, Apothecary?', 0, 'credit', 970205, 970220, 970207, 0, 'A social obligation. How quaint. ...Yes, very well. I\'ll bring something. Something non-lethal, I suppose.', 0),
(3547, 18, 'option', 'I970205', 0, 'You\'re invited to my housewarming, neighbor.', 0, 'credit', 970205, 970220, 970208, 4, 'Neighbor! Nobody\'s called me that in years. I\'ll bring mushrooms. The good ones. The ones that don\'t talk back.', 0),
(1496, 19, 'option', 'I970205', 0, 'There\'s a housewarming at my house. You should come.', 0, 'credit', 970205, 970220, 970209, 0, 'I\'m on watch. ...I\'ll watch from your doorway, then. Same thing, with walls.', 0),
(970202, 20, 'greet', 'F80', 0, NULL, 970226, '', 0, 0, 0, 0, NULL, 0),
(970202, 21, 'greet', '', 0, NULL, 970227, '', 0, 0, 0, 0, NULL, 0),
(970203, 22, 'greet', '', 0, NULL, 970228, '', 0, 0, 0, 0, NULL, 0),
(970204, 23, 'greet', 'F2', 0, NULL, 970229, '', 0, 0, 0, 0, NULL, 0),
(970204, 24, 'greet', '', 0, NULL, 970230, '', 0, 0, 0, 0, NULL, 0),
(970205, 25, 'greet', '', 0, NULL, 970231, '', 0, 0, 0, 0, NULL, 0);
UPDATE `creature_template` SET `npcflag` = `npcflag` | 1 WHERE `entry` IN (1496, 1518, 2118, 2134, 3547, 5688, 10055);
INSERT INTO `brill_town_phase` (`bit`, `ord`, `cond`) VALUES
(262144, 0, 'O !R970201'),
(524288, 0, 'A970203'),
(1048576, 0, 'R970204'),
(2097152, 0, 'C970205'),
(2097152, 1, 'R970205 D4');
INSERT INTO `quest_template` (`ID`, `QuestType`, `QuestLevel`, `MinLevel`, `QuestSortID`, `Flags`, `RewardXPDifficulty`, `RewardMoney`, `AllowableRaces`, `LogTitle`, `LogDescription`, `QuestDescription`, `AreaDescription`, `QuestCompletionLog`, `RequiredNpcOrGo1`, `RequiredNpcOrGoCount1`, `ObjectiveText1`, `RequiredNpcOrGo2`, `RequiredNpcOrGoCount2`, `ObjectiveText2`, `RequiredNpcOrGo3`, `RequiredNpcOrGoCount3`, `ObjectiveText3`, `RequiredNpcOrGo4`, `RequiredNpcOrGoCount4`, `ObjectiveText4`, `RewardItem1`, `RewardAmount1`, `RewardItem2`, `RewardAmount2`, `RewardItem3`, `RewardAmount3`, `RewardItem4`, `RewardAmount4`) VALUES
(970200, 2, -1, 5, 159, 0, 2, 1500, 0, 'The Place Is Yours', 'Inspect the house with Oswin Thatcher: the broken furniture, the rotten floorboards, the leaking roof and the cellar hatch.', 'You\'ll be the new owner, then. Oswin Thatcher, carpenter, late of the Undercity\'s Rogues\' Quarter, and before that of a town that doesn\'t exist anymore. Jamie Nore sent for me. She said it was a favor. Jamie\'s favors are the kind you pay for twice.$B$BI\'ve had a look from the doorway. I\'ve seen worse. Mostly in the Plaguelands.$B$BCedric kept the roof on, I\'ll give him that, but the dead don\'t notice damp and they don\'t notice rot. Before I lift a hammer, I want you to see it with your own eyes: the broken furniture, the floorboards by the hearth, wherever the rain\'s getting in, and that hatch down to the cellar. A house is like a body, $n. You find out what\'s wrong with it before you start cutting.', '', 'Return to Oswin Thatcher outside your house in Brill.', 970206, 1, 'Broken furniture inspected', 970207, 1, 'Rotten floorboards inspected', 970208, 1, 'Leaking roof inspected', 970209, 1, 'Cellar hatch inspected', 0, 0, 0, 0, 0, 0, 0, 0),
(970201, 2, -1, 5, 159, 0, 4, 3500, 0, 'Timber for the Dead', 'Salvage 5 piles of usable lumber from abandoned farms around Tirisfal and a bucket of nails from Solliden Farmstead, then return to Oswin Thatcher.', 'I\'d buy new timber, but the nearest honest lumber is in Silverpine, and the worgen have opinions about who cuts it. Besides, Tirisfal is full of houses nobody needs anymore.$B$BThe farms out past Brill were built by people who knew their trade. Agamand Mills, the Solliden place, the old steadings east and west of town: there\'s oak in those walls older than either of us. Find the piles that haven\'t rotted through and bring back what you can carry. And nails. The Soliddens kept a bucket of them in the barn; nobody\'s had a use for them since.$B$BMind what still lives out there. Or doesn\'t live, but walks about anyway.', '', 'Return to Oswin Thatcher outside your house in Brill.', 970210, 5, 'Salvageable lumber', 970211, 1, 'Bucket of nails', 0, 0, '', 0, 0, '', 0, 0, 0, 0, 0, 0, 0, 0),
(970202, 2, -1, 5, 159, 0, 3, 2500, 0, 'Things Left Behind', 'Look through what Cedric Harrow left in the house: the bundle of letters, the portrait and the locked chest. Then speak with Oswin Thatcher.', 'Pulling up the floor turned up more than rot. Cedric left things behind: a bundle of letters under the loose board, a portrait in the rafters, and a chest with a lock rusted shut. I don\'t go through other people\'s things. It\'s bad luck, and I\'ve had enough of that.$B$BBut it\'s your house now. If there\'s anything in there worth keeping, or worth sending after him to Northrend, you\'re the one who ought to decide.', '', 'Return to Oswin Thatcher outside your house in Brill.', 970212, 1, 'Cedric\'s letters read', 970213, 1, 'The portrait found', 970214, 1, 'The chest opened', 0, 0, '', 0, 0, 0, 0, 0, 0, 0, 0),
(970203, 2, -1, 5, 159, 0, 4, 3000, 0, 'Beneath the Floorboards', 'Kill 6 Cellar Rats in your house, then look through the cellar hatch at what\'s behind them.', 'Something\'s been chewing at the joists from below. I thought it was the house settling until it squeaked at me.$B$BRats. Big ones. Cellar rats in Tirisfal grow fat on whatever the plague left in the ground, and they\'ve been nesting under your floor since before Cedric came back. Clear them out, and then have a proper look down that hatch. I\'ve a feeling they came from somewhere further down than the cellar. Rats don\'t dig that deep without a reason.', '', 'Return to Oswin Thatcher outside your house in Brill.', 970201, 6, '', 970215, 1, 'The bricked-up wall examined', 0, 0, '', 0, 0, '', 0, 0, 0, 0, 0, 0, 0, 0),
(970204, 2, -1, 5, 159, 0, 4, 4000, 0, 'A Home Worth Keeping', 'Get candles and lamp oil from Abigail Shiel, linens from Mrs. Winters and clean straw from Morganus the stable master, then choose two furnishings with Oswin Thatcher.', 'Roof, door, floor, rats. That\'s the house done. A home is the rest of it.$B$BYou\'ll want light: Abigail Shiel sells candles and lamp oil by the market. Linens for the bed: Mrs. Winters, and don\'t let her talk you into the gray ones. And straw, clean straw, from Morganus at the stable, so that dog of yours has somewhere to sleep that isn\'t your bed.$B$BThen come and tell me how you want the place set out. I\'ll build you two pieces of whatever you like, on Jamie\'s account. She owes me more than she\'ll admit.', '', 'Return to Oswin Thatcher outside your house in Brill.', 970216, 1, 'Candles and lamp oil', 970217, 1, 'Linens', 970218, 1, 'Clean straw', 970219, 2, 'Furnishings chosen', 858, 3, 0, 0, 0, 0, 0, 0),
(970205, 2, -1, 5, 159, 0, 5, 5000, 0, 'A Housewarming for the Dead', 'Invite Innkeeper Renee, Apothecary Johaan, Hamlin Atkins and Deathguard Dillinger to your house, then speak with Oswin Thatcher there.', 'In Lordaeron, when a house was finished, you filled it with people the first night, so it would know what it was for. We did it in my town. We did it the night before the soldiers came to burn it.$B$BI\'d like to do it once more.$B$BInvite your neighbors. Renee at the inn, she\'ll bring something to drink whether anyone can taste it or not. Johaan, if he can be pried away from his vials. Hamlin, the mushroom farmer next door. And Dillinger, so the Deathguard know which house to watch.$B$BThey\'ll come. People in Brill don\'t get asked to things. It\'ll be the first invitation some of them have had since they died.', '', 'Speak with Oswin Thatcher outside your house in Brill.', 970220, 4, 'Neighbors invited', 0, 0, '', 0, 0, '', 0, 0, '', 929, 3, 0, 0, 0, 0, 0, 0),
(970206, 2, 1, 1, -1, 0, 0, 0, 0, '[invited_renee]', '', '', '', '', 0, 0, '', 0, 0, '', 0, 0, '', 0, 0, '', 0, 0, 0, 0, 0, 0, 0, 0),
(970207, 2, 1, 1, -1, 0, 0, 0, 0, '[invited_johaan]', '', '', '', '', 0, 0, '', 0, 0, '', 0, 0, '', 0, 0, '', 0, 0, 0, 0, 0, 0, 0, 0),
(970208, 2, 1, 1, -1, 0, 0, 0, 0, '[invited_hamlin]', '', '', '', '', 0, 0, '', 0, 0, '', 0, 0, '', 0, 0, '', 0, 0, 0, 0, 0, 0, 0, 0),
(970209, 2, 1, 1, -1, 0, 0, 0, 0, '[invited_dillinger]', '', '', '', '', 0, 0, '', 0, 0, '', 0, 0, '', 0, 0, '', 0, 0, 0, 0, 0, 0, 0, 0);
INSERT INTO `quest_template_addon` (`ID`, `PrevQuestID`, `SpecialFlags`) VALUES (970200, 970121, 0), (970201, 970200, 0), (970202, 970201, 0), (970203, 970202, 0), (970204, 970203, 0), (970205, 970204, 0);
INSERT INTO `quest_offer_reward` (`ID`, `Emote1`, `RewardText`) VALUES
(970200, 1, 'So. A cellar that groans at night, a roof like a sieve, and furniture fit for kindling. I\'ve rebuilt houses for worse owners in worse towns.$B$BThe roof first, then the door, then the floor. Everything else can wait. Nothing in Tirisfal is in a hurry, least of all the people in it.'),
(970201, 1, 'Good oak. Dry, straight, and only a little bloodstained.$B$B(Over the next hours Oswin works without stopping. The broken furniture goes out the door as kindling, the rotten boards come up, the gap in the roof closes, and the door finally shuts the way a door should.)$B$BThere. It\'s a house again, $n. Not a home, not yet. But the rain stays outside, which is a start.'),
(970202, 1, 'Elinor, two boys, and a dog called Bramble. And the last letter dated the week the grain came in from Andorhal.$B$B(Oswin is quiet a while.)$B$BI\'ll send the ring to Vengeance Landing with the next ship. Cedric should have it. The toy soldiers... leave them on the shelf. Some things belong to a house more than to a person.'),
(970203, 1, 'A wall of brick at the back of the cellar, and a mark on it you don\'t like the look of. Show me.$B$B(Oswin studies your description for a long moment.)$B$BI\'ve seen that sign before. In Stratholme, on the doors of the houses the Cult of the Damned used. Somebody bricked that up before the plague came to Brill, or just after.$B$BI\'ll mortar over it again. Whatever\'s behind it has waited this long. If you want to know more, I\'d ask someone who keeps the dead\'s records. I\'d rather not know at all.'),
(970204, 1, '(Oswin hangs the last lantern and steps back to the doorway.)$B$BThere. Lamps that don\'t smoke, linen that doesn\'t smell of grave dirt, a bed of straw by the stove for the dog, and a chest for whatever you drag home from your travels.$B$BIt looks like somebody lives here, $n. That\'s all a house ever wants.'),
(970205, 1, '(The house is full: Renee pouring something brown into cups, Johaan explaining a recipe to Hamlin, who is explaining mushrooms back at him, Dillinger standing by the door out of habit.)$B$BListen to that. Six years I\'ve been building rooms for people to be lonely in.$B$BI\'ll stay on in Brill a while, I think. Jamie keeps finding me work. If you want anything else done to the place, you know where to find me. Right here, usually, admiring my own roof.');
INSERT INTO `quest_request_items` (`ID`, `EmoteOnComplete`, `EmoteOnIncomplete`, `CompletionText`) VALUES
(970200, 1, 1, 'Seen it all yet? Don\'t step on the boards by the hearth. Or do, and save me the trouble of pulling them up.'),
(970201, 1, 1, 'Timber? Nails? The roof isn\'t going to stop leaking because we\'ve been patient with it.'),
(970202, 1, 1, 'Found anything? You\'ve got the look of someone reading something they shouldn\'t.'),
(970203, 1, 1, 'Still squeaking. I can hear them from out here.'),
(970204, 1, 1, 'Light, linen, straw, and your say in the rest. I\'m not building it all on my own guesswork.'),
(970205, 1, 1, 'Have you asked them? Don\'t worry if they say no. They won\'t say no.');
INSERT INTO `creature_queststarter` (`id`, `quest`) VALUES (970200, 970200), (970200, 970201), (970200, 970202), (970200, 970203), (970200, 970204), (970200, 970205);
INSERT INTO `creature_questender` (`id`, `quest`) VALUES (970200, 970200), (970200, 970201), (970200, 970202), (970200, 970203), (970200, 970204), (970200, 970205);

-- The Gravekeeper's Ledger
INSERT INTO `creature_template` (`entry`, `name`, `subname`, `gossip_menu_id`, `minlevel`, `maxlevel`, `faction`, `npcflag`, `speed_walk`, `speed_run`, `unit_class`, `unit_flags`, `type`, `rank`, `HealthModifier`, `DamageModifier`, `mingold`, `maxgold`, `flags_extra`, `AIName`, `ScriptName`) VALUES
(970300, 'Mortimer Weller', 'Gravekeeper', 0, 22, 22, 68, 3, 1, 1.14286, 1, 0, 6, 0, 1, 1, 0, 0, 2, '', ''),
(970301, 'Mercy Hollin', NULL, 0, 12, 12, 68, 3, 1, 1.14286, 1, 512, 6, 0, 1, 1, 0, 0, 2, '', ''),
(970302, 'Gideon Hale', NULL, 0, 12, 12, 68, 1, 1, 1.14286, 1, 512, 6, 0, 1, 1, 0, 0, 2, '', ''),
(970303, 'Edith Fairweather', NULL, 0, 10, 10, 68, 1, 1, 1.14286, 1, 512, 6, 0, 1, 1, 0, 0, 2, '', ''),
(970304, 'Walter Shaw', NULL, 0, 13, 13, 68, 1, 1, 1.14286, 1, 512, 6, 0, 1, 1, 0, 0, 2, '', ''),
(970305, 'Mercy Hollin', NULL, 0, 12, 12, 68, 1, 1, 1.14286, 1, 0, 6, 0, 1, 1, 0, 0, 2, '', ''),
(970306, 'Gideon Hale', 'Stonemason', 0, 12, 12, 68, 1, 1, 1.14286, 1, 0, 6, 0, 1, 1, 0, 0, 2, '', ''),
(970307, 'Edith Fairweather', 'Weaver', 0, 10, 10, 68, 1, 1, 1.14286, 1, 0, 6, 0, 1, 1, 0, 0, 2, '', ''),
(970308, 'Walter Shaw', NULL, 0, 13, 13, 68, 1, 1, 1.14286, 1, 0, 6, 0, 1, 1, 0, 0, 2, '', ''),
(970309, 'Burial ledgers sorted', NULL, 0, 1, 1, 35, 0, 1, 1, 1, 33554432, 10, 0, 1, 1, 0, 0, 130, '', ''),
(970310, 'Neglected graves inspected', NULL, 0, 1, 1, 35, 0, 1, 1, 1, 33554432, 10, 0, 1, 1, 0, 0, 130, '', ''),
(970311, 'Disturbed graves searched', NULL, 0, 1, 1, 35, 0, 1, 1, 1, 33554432, 10, 0, 1, 1, 0, 0, 130, '', ''),
(970312, 'Trail of grave dirt followed', NULL, 0, 1, 1, 35, 0, 1, 1, 1, 33554432, 10, 0, 1, 1, 0, 0, 130, '', ''),
(970313, 'Whoever hides in the mausoleum spoken with', NULL, 0, 1, 1, 35, 0, 1, 1, 1, 33554432, 10, 0, 1, 1, 0, 0, 130, '', ''),
(970314, 'Belongings recovered', NULL, 0, 1, 1, 35, 0, 1, 1, 1, 33554432, 10, 0, 1, 1, 0, 0, 130, '', ''),
(970315, 'Brill\'s question settled', NULL, 0, 1, 1, 35, 0, 1, 1, 1, 33554432, 10, 0, 1, 1, 0, 0, 130, '', ''),
(970316, 'Names entered on Brill\'s rolls', NULL, 0, 1, 1, 35, 0, 1, 1, 1, 33554432, 10, 0, 1, 1, 0, 0, 130, '', ''),
(970317, 'The last bell rung', NULL, 0, 1, 1, 35, 0, 1, 1, 1, 33554432, 10, 0, 1, 1, 0, 0, 130, '', '');
INSERT INTO `creature_template_model` (`CreatureID`, `Idx`, `CreatureDisplayID`, `DisplayScale`, `Probability`) VALUES
(970300, 0, 2650, 1, 1),
(970301, 0, 2660, 1, 1),
(970302, 0, 2649, 1, 1),
(970303, 0, 2664, 1, 1),
(970304, 0, 2654, 1, 1),
(970305, 0, 2660, 1, 1),
(970306, 0, 2649, 1, 1),
(970307, 0, 2664, 1, 1),
(970308, 0, 2654, 1, 1),
(970309, 0, 11686, 1, 1),
(970310, 0, 11686, 1, 1),
(970311, 0, 11686, 1, 1),
(970312, 0, 11686, 1, 1),
(970313, 0, 11686, 1, 1),
(970314, 0, 11686, 1, 1),
(970315, 0, 11686, 1, 1),
(970316, 0, 11686, 1, 1),
(970317, 0, 11686, 1, 1);
INSERT INTO `creature` (`guid`, `id`, `map`, `zoneId`, `areaId`, `spawnMask`, `phaseMask`, `position_x`, `position_y`, `position_z`, `orientation`, `spawntimesecs`, `wander_distance`, `MovementType`, `Comment`) VALUES
(970300, 970300, 0, 85, 0, 1, 1, 2362.000, 342.000, 38.030, 3.0000, 60, 0, 0, 'mod-brill-home town'),
(970301, 970301, 0, 85, 0, 1, 1, 2486.000, 259.000, 47.280, 2.8000, 60, 0, 0, 'mod-brill-home town'),
(970302, 970302, 0, 85, 0, 1, 1, 2489.000, 265.000, 46.440, 3.4000, 60, 0, 0, 'mod-brill-home town'),
(970303, 970303, 0, 85, 0, 1, 1, 2484.000, 253.000, 45.920, 2.4000, 60, 0, 0, 'mod-brill-home town'),
(970304, 970304, 0, 85, 0, 1, 1, 2491.000, 251.000, 45.380, 2.2000, 60, 0, 0, 'mod-brill-home town'),
(970305, 970305, 0, 85, 0, 1, 4194304, 2352.000, 336.000, 38.080, 0.4000, 60, 0, 0, 'mod-brill-home town'),
(970306, 970306, 0, 85, 0, 1, 4194304, 2290.000, 318.000, 35.310, 1.6000, 60, 0, 0, 'mod-brill-home town'),
(970307, 970307, 0, 85, 0, 1, 4194304, 2262.000, 279.000, 34.790, 0.8000, 60, 0, 0, 'mod-brill-home town'),
(970308, 970308, 0, 85, 0, 1, 4194304, 2296.000, 393.000, 34.070, 0.2000, 60, 0, 0, 'mod-brill-home town');
INSERT INTO `gameobject_template` (`entry`, `type`, `displayId`, `name`, `size`, `Data0`, `Data1`, `ScriptName`) VALUES
(970300, 2, 193, 'Neglected Grave', 1, 0, 0, 'go_brill_town'),
(970301, 2, 499, 'Neglected Grave', 1, 0, 0, 'go_brill_town'),
(970302, 2, 2452, 'Neglected Grave', 1, 0, 0, 'go_brill_town'),
(970303, 2, 19, 'Neglected Grave', 1, 0, 0, 'go_brill_town'),
(970304, 2, 399, 'Neglected Grave', 1, 0, 0, 'go_brill_town'),
(970305, 2, 498, 'Disturbed Grave', 1, 0, 0, 'go_brill_town'),
(970306, 2, 49, 'Disturbed Grave', 1, 0, 0, 'go_brill_town'),
(970307, 2, 498, 'Disturbed Grave', 1, 0, 0, 'go_brill_town'),
(970308, 2, 49, 'Trail of Grave Dirt', 0.5, 0, 0, 'go_brill_town'),
(970309, 5, 1187, 'Lyle Family Mausoleum', 1, 0, 0, ''),
(970310, 2, 317, 'Mason\'s Tool Roll', 1, 0, 0, 'go_brill_town'),
(970311, 2, 468, 'Sewing Basket', 1, 0, 0, 'go_brill_town'),
(970312, 2, 318, 'Tin Letter Box', 1, 0, 0, 'go_brill_town');
INSERT INTO `gameobject` (`guid`, `id`, `map`, `zoneId`, `areaId`, `spawnMask`, `phaseMask`, `position_x`, `position_y`, `position_z`, `orientation`, `rotation0`, `rotation1`, `rotation2`, `rotation3`, `spawntimesecs`, `animprogress`, `state`, `Comment`) VALUES
(970300, 970300, 0, 85, 0, 1, 1, 2352.000, 322.000, 36.420, 1.2000, 0, 0, 0.564642, 0.825336, 0, 255, 1, 'mod-brill-home town'),
(970301, 970301, 0, 85, 0, 1, 1, 2348.000, 360.000, 36.720, 0.4000, 0, 0, 0.198669, 0.980067, 0, 255, 1, 'mod-brill-home town'),
(970302, 970302, 0, 85, 0, 1, 1, 2366.000, 368.000, 37.790, 4.9000, 0, 0, 0.637765, -0.770231, 0, 255, 1, 'mod-brill-home town'),
(970303, 970303, 0, 85, 0, 1, 1, 2396.000, 362.000, 37.010, 3.6000, 0, 0, 0.973848, -0.227202, 0, 255, 1, 'mod-brill-home town'),
(970304, 970304, 0, 85, 0, 1, 1, 2400.000, 325.000, 38.090, 3.1000, 0, 0, 0.999784, 0.020795, 0, 255, 1, 'mod-brill-home town'),
(970305, 970305, 0, 85, 0, 1, 1, 2372.000, 374.000, 37.340, 1.0000, 0, 0, 0.479426, 0.877583, 0, 255, 1, 'mod-brill-home town'),
(970306, 970306, 0, 85, 0, 1, 1, 2356.000, 376.000, 35.910, 2.2000, 0, 0, 0.891207, 0.453596, 0, 255, 1, 'mod-brill-home town'),
(970307, 970307, 0, 85, 0, 1, 1, 2340.000, 368.000, 34.650, 5.5000, 0, 0, 0.381661, -0.924302, 0, 255, 1, 'mod-brill-home town'),
(970308, 970308, 0, 85, 0, 1, 1, 2425.000, 300.000, 36.170, 5.8000, 0, 0, 0.239249, -0.970958, 0, 255, 1, 'mod-brill-home town'),
(970309, 970308, 0, 85, 0, 1, 1, 2458.000, 280.000, 31.440, 5.9000, 0, 0, 0.190423, -0.981702, 0, 255, 1, 'mod-brill-home town'),
(970310, 970308, 0, 85, 0, 1, 1, 2472.000, 268.000, 45.380, 5.9000, 0, 0, 0.190423, -0.981702, 0, 255, 1, 'mod-brill-home town'),
(970311, 970309, 0, 85, 0, 1, 1, 2497.000, 257.000, 46.090, 2.8000, 0, 0, 0.985450, 0.169967, 0, 255, 1, 'mod-brill-home town'),
(970312, 970310, 0, 85, 0, 1, 1, 2041.500, 153.000, 34.300, 1.4000, 0, 0, 0.644218, 0.764842, 0, 255, 1, 'mod-brill-home town'),
(970313, 970311, 0, 85, 0, 1, 1, 2483.500, 24.500, 26.900, 0.6000, 0, 0, 0.295520, 0.955336, 0, 255, 1, 'mod-brill-home town'),
(970314, 970312, 0, 85, 0, 1, 1, 2903.000, 387.000, 30.020, 4.0000, 0, 0, 0.909297, -0.416147, 0, 255, 1, 'mod-brill-home town');
INSERT INTO `brill_town_use` (`go`, `ord`, `cond`, `quest`, `credit`, `text_id`, `marker`) VALUES
(970300, 0, 'I970300', 970300, 970310, 970300, 0),
(970300, 1, '', 0, 0, 970301, 0),
(970301, 0, 'I970300', 970300, 970310, 970302, 0),
(970301, 1, '', 0, 0, 970303, 0),
(970302, 0, 'I970300', 970300, 970310, 970304, 0),
(970302, 1, '', 0, 0, 970305, 0),
(970303, 0, 'I970300', 970300, 970310, 970306, 0),
(970303, 1, '', 0, 0, 970307, 0),
(970304, 0, 'I970300', 970300, 970310, 970308, 0),
(970304, 1, '', 0, 0, 970309, 0),
(970305, 0, 'I970301', 970301, 970311, 970310, 0),
(970305, 1, '', 0, 0, 970311, 0),
(970306, 0, 'I970301', 970301, 970311, 970312, 0),
(970306, 1, '', 0, 0, 970313, 0),
(970307, 0, 'I970301', 970301, 970311, 970314, 0),
(970307, 1, '', 0, 0, 970315, 0),
(970308, 0, 'I970302', 970302, 970312, 970316, 0),
(970308, 1, '', 0, 0, 970317, 0),
(970310, 0, 'I970303', 970303, 970314, 970318, 0),
(970310, 1, '', 0, 0, 970319, 0),
(970311, 0, 'I970303', 970303, 970314, 970320, 0),
(970311, 1, '', 0, 0, 970321, 0),
(970312, 0, 'I970303', 970303, 970314, 970322, 0),
(970312, 1, '', 0, 0, 970323, 0);
INSERT INTO `brill_town_gossip` (`npc`, `ord`, `kind`, `cond`, `icon`, `text`, `text_id`, `action`, `quest`, `credit`, `marker`, `emote`, `reply`, `arg`) VALUES
(970300, 1000, 'greet', 'R970904', 0, NULL, 970324, '', 0, 0, 0, 0, NULL, 0),
(970300, 1001, 'greet', 'C970904', 0, NULL, 970325, '', 0, 0, 0, 0, NULL, 0),
(970300, 1002, 'greet', 'T970900', 0, NULL, 970326, '', 0, 0, 0, 0, NULL, 0),
(970300, 1003, 'greet', 'R970304 R970305', 0, NULL, 970327, '', 0, 0, 0, 0, NULL, 0),
(970300, 1004, 'greet', 'R970304 R970306', 0, NULL, 970328, '', 0, 0, 0, 0, NULL, 0),
(970300, 1005, 'greet', 'R970203 !R970304', 0, NULL, 970329, '', 0, 0, 0, 0, NULL, 0),
(970300, 1006, 'greet', 'A970304', 0, NULL, 970330, '', 0, 0, 0, 0, NULL, 0),
(970300, 1007, 'greet', 'T970302 !R970304', 0, NULL, 970331, '', 0, 0, 0, 0, NULL, 0),
(970300, 1008, 'greet', 'A970301', 0, NULL, 970332, '', 0, 0, 0, 0, NULL, 0),
(970300, 1009, 'greet', 'A970300', 0, NULL, 970333, '', 0, 0, 0, 0, NULL, 0),
(970300, 1010, 'greet', '', 0, NULL, 970334, '', 0, 0, 0, 0, NULL, 0),
(970300, 1011, 'option', 'I970300', 0, '(Help Mortimer sort the burial ledgers.)', 0, 'credit', 970300, 970309, 0, 1, 'Plague year on the left, after on the right. Births in the green book, there were so few. Thank you, $n.$BHere\'s the odd thing: Hollin. Hale. Fairweather. Shaw. Written twice. Go and see their stones for me, along the wall, and the Lyle stone at the end.', 0),
(970300, 1012, 'option', 'I970304', 0, '(Ring the bell over the lychgate.)', 0, 'credit', 970304, 970317, 0, 2, '(The bell sounds once, flat and cracked, out over the town. On the cemetery wall, four figures stand very still until it fades.)', 0),
(970300, 1013, 'option', 'R970203', 0, 'Oswin Thatcher said to ask you about a mark in my cellar.', 0, 'say', 0, 0, 0, 0, 'A crowned eye, on brick? I know of it, though not from my books. Brill had a benefactor in the plague year, a Master Croft, who paid for many kindnesses and some walls. Ask the Deathstalker by the mausoleum. She\'s been waiting for someone to.', 0),
(970301, 1014, 'option', 'I970302', 0, 'I\'m not here to hurt you. Mortimer Weller sent me.', 0, 'credit', 970302, 970313, 0, 6, 'Weller. The clerk. ...Put your hands where I can see them, and come up slowly.', 0),
(970301, 1015, 'option', 'I970303', 0, 'Walk into Brill by daylight, under your own names. Let them see you.', 0, 'choice', 970303, 970315, 970305, 273, 'By daylight. Walter will hate it. ...Good. Let them look; we\'ll look back. I delivered half the Deathguard\'s grandmothers.', 0),
(970301, 1016, 'option', 'I970303', 0, 'Come in quietly, at night, with the gravekeeper\'s word ahead of you.', 0, 'choice', 970303, 970315, 970306, 273, 'Quietly, then. Edith will be glad. Not everyone wants to be a curiosity, $n. Some of us only want to be left to sew.', 0),
(1499, 1017, 'option', 'I970304', 0, 'Gravekeeper Weller asks that four names be entered on Brill\'s rolls.', 0, 'credit', 970304, 970316, 0, 1, 'Four more risen, and the Deathguard didn\'t find them first? Unusual. ...Hollin, Hale, Fairweather, Shaw. Entered, under the Dark Lady\'s protection. Tell them they owe tithe from the next harvest, the same as everyone. Freedom has its paperwork.', 0),
(970301, 1018, 'greet', 'R970304', 0, NULL, 970335, '', 0, 0, 0, 0, NULL, 0),
(970301, 1019, 'greet', 'T970302', 0, NULL, 970336, '', 0, 0, 0, 0, NULL, 0),
(970301, 1020, 'greet', '', 0, NULL, 970337, '', 0, 0, 0, 0, NULL, 0),
(970302, 1021, 'greet', 'R970304', 0, NULL, 970338, '', 0, 0, 0, 0, NULL, 0),
(970302, 1022, 'greet', '', 0, NULL, 970339, '', 0, 0, 0, 0, NULL, 0),
(970303, 1023, 'greet', 'R970304', 0, NULL, 970340, '', 0, 0, 0, 0, NULL, 0),
(970303, 1024, 'greet', '', 0, NULL, 970341, '', 0, 0, 0, 0, NULL, 0),
(970304, 1025, 'greet', 'R970304', 0, NULL, 970342, '', 0, 0, 0, 0, NULL, 0),
(970304, 1026, 'greet', '', 0, NULL, 970343, '', 0, 0, 0, 0, NULL, 0),
(970305, 1027, 'greet', 'R970305 D3', 0, NULL, 970344, '', 0, 0, 0, 0, NULL, 0),
(970305, 1028, 'greet', 'R970305', 0, NULL, 970345, '', 0, 0, 0, 0, NULL, 0),
(970305, 1029, 'greet', '', 0, NULL, 970346, '', 0, 0, 0, 0, NULL, 0),
(970306, 1030, 'greet', 'R970904', 0, NULL, 970347, '', 0, 0, 0, 0, NULL, 0),
(970306, 1031, 'greet', 'R970305', 0, NULL, 970348, '', 0, 0, 0, 0, NULL, 0),
(970306, 1032, 'greet', '', 0, NULL, 970349, '', 0, 0, 0, 0, NULL, 0),
(970307, 1033, 'greet', 'R970306', 0, NULL, 970350, '', 0, 0, 0, 0, NULL, 0),
(970307, 1034, 'greet', '', 0, NULL, 970351, '', 0, 0, 0, 0, NULL, 0),
(970308, 1035, 'greet', 'R970305', 0, NULL, 970352, '', 0, 0, 0, 0, NULL, 0),
(970308, 1036, 'greet', '', 0, NULL, 970353, '', 0, 0, 0, 0, NULL, 0);
UPDATE `creature_template` SET `npcflag` = `npcflag` | 1 WHERE `entry` IN (1499);
INSERT INTO `brill_town_phase` (`bit`, `ord`, `cond`) VALUES
(4194304, 0, 'R970304');
INSERT INTO `quest_template` (`ID`, `QuestType`, `QuestLevel`, `MinLevel`, `QuestSortID`, `Flags`, `RewardXPDifficulty`, `RewardMoney`, `AllowableRaces`, `LogTitle`, `LogDescription`, `QuestDescription`, `AreaDescription`, `QuestCompletionLog`, `RequiredNpcOrGo1`, `RequiredNpcOrGoCount1`, `ObjectiveText1`, `RequiredNpcOrGo2`, `RequiredNpcOrGoCount2`, `ObjectiveText2`, `RequiredNpcOrGo3`, `RequiredNpcOrGoCount3`, `ObjectiveText3`, `RequiredNpcOrGo4`, `RequiredNpcOrGoCount4`, `ObjectiveText4`, `RewardItem1`, `RewardAmount1`, `RewardItem2`, `RewardAmount2`, `RewardItem3`, `RewardAmount3`, `RewardItem4`, `RewardAmount4`) VALUES
(970300, 2, -1, 5, 159, 0, 3, 2000, 0, 'Names Among the Stones', 'Help Mortimer Weller sort Brill\'s burial ledgers, then inspect five neglected graves in the cemetery.', 'You there. You\'ve the look of someone who reads. Forgive me: I don\'t get many visitors who still do.$B$BI was the parish clerk here, before. I wrote every burial of the plague year in this ledger, until my hand shook too hard to hold the pen, and then somebody wrote mine. The Dark Lady\'s people gave me the cemetery back when I rose. A clerk to the last, it seems.$B$BGraves are coming up empty, $n. Not torn open, the way the Scourge leave them. Just... empty. And when I went to the ledgers to see whose, I found names written twice. My own hand, and then another\'s.$B$BHelp me put the books in order, and then look at five of the oldest graves along the wall. Tell me what the stones say. I\'d go myself, but I\'d rather not be alone with this until I know what it is.', '', 'Return to Gravekeeper Mortimer Weller in Brill\'s cemetery.', 970309, 1, 'Burial ledgers sorted', 970310, 5, 'Neglected graves inspected', 0, 0, '', 0, 0, '', 118, 2, 0, 0, 0, 0, 0, 0),
(970301, 2, -1, 5, 159, 0, 3, 2500, 0, 'Fresh Earth', 'Search three recently disturbed graves in Brill\'s cemetery for evidence of who has been taking the dead.', 'The Deathguard tell me it\'s the Rot Hide gnolls. They dig for bones the way dogs do, and they\'ve done it before. Executor Zygand says to put a guard on the gate and stop fretting.$B$BBut I\'ve seen what gnolls leave behind, $n: half the coffin in splinters and the other half chewed. These graves were opened neatly. Somebody filled the holes back in after. Gnolls don\'t tidy.$B$BThree of the double-named graves were disturbed within the week, along the north side, by the old yew. Look them over properly. Whatever was left behind, I want to know about it before the Deathguard decide what it means for us.', '', 'Return to Gravekeeper Mortimer Weller in Brill\'s cemetery.', 970311, 3, 'Disturbed graves searched', 0, 0, '', 0, 0, '', 0, 0, '', 858, 2, 0, 0, 0, 0, 0, 0),
(970302, 2, -1, 5, 159, 0, 4, 2500, 0, 'Those Who Were Forgotten', 'Follow the trail of grave dirt east of Brill\'s cemetery to wherever it leads, and speak with whoever you find.', 'There\'s a furrow in the grass past the east wall, where something heavy was dragged, or someone who couldn\'t yet walk was helped along. It goes up toward the wooded hill, where the old Lyle family mausoleum stands. The Lyles all died in the first month of the plague. No one has opened that door since.$B$BI could tell the Deathguard. They\'d go up there with torches, and whoever is inside would be put down a second time, or a third.$B$BI\'d rather you went. You don\'t wear a tabard, and you\'ve the patience to talk before you swing. If they are what I think they are, they\'ll be more afraid of you than you need be of them. Mostly.', '', 'Speak with Mercy Hollin at the Lyle mausoleum east of Brill.', 970312, 3, 'Trail of grave dirt followed', 970313, 1, 'Whoever hides in the mausoleum spoken with', 0, 0, '', 0, 0, '', 0, 0, 0, 0, 0, 0, 0, 0),
(970303, 2, -1, 5, 159, 0, 4, 3000, 0, 'A Name of One\'s Own', 'Recover Gideon\'s tools from the old smithy west of Brill, Edith\'s basket from her cottage south of town and Walter\'s letters from the gnolls\' farmstead north-east of it, and help Mercy decide how they\'ll face Brill.', 'I woke in the dark with earth in my mouth, and I knew my own name. That was the strange part. For years I\'d only known his voice in my head, and then it was gone, and there I was. Mercy. Just Mercy.$B$BI dug myself out. Then I heard knocking from the next grave, so I took Mortimer\'s spade from his shed and dug again. Four times, so far. I\'ll keep listening.$B$BThe others want things back, before they\'ll believe they\'re people. Gideon wants his tools from the old smithy by the west road. Edith wants her sewing basket from her cottage south of town, the one by the duskbat woods. Walter\'s letters are at his farmstead north-east of here, where the Rot Hide gnolls have their camp now.$B$BAnd when you come back, help me talk sense into them about Brill. We can\'t live in a tomb forever. Though Walter would try.', '', 'Return to Mercy Hollin at the Lyle mausoleum east of Brill.', 970314, 3, 'Belongings recovered', 970315, 1, 'Brill\'s question settled', 0, 0, '', 0, 0, '', 858, 2, 0, 0, 0, 0, 0, 0),
(970304, 2, -1, 5, 159, 0, 5, 4000, 0, 'The Last Bell', 'Ask Magistrate Sevren in Brill\'s town hall to enter the four names on Brill\'s rolls, then ring the cemetery bell with Gravekeeper Mortimer Weller.', 'There\'s a custom in Brill. There was, anyway. When someone was laid in the ground, the clerk rang the bell over the lychgate, once, so the whole parish knew a life was finished. In the plague year it rang so often they stopped ringing it at all.$B$BMortimer told me, through you, that he\'d like to ring it once more for the four of us. The other way round. Not a life finished; a death.$B$BBut a bell\'s only a bell. The Magistrate keeps the rolls of who lives in Brill, and who the Deathguard are to leave alone. Our names need to be on them, $n, or we\'re only corpses with opinions. Go to Sevren in the town hall, and then to Mortimer. We\'ll be watching from the wall.', '', 'Return to Gravekeeper Mortimer Weller in Brill\'s cemetery.', 970316, 1, 'Names entered on Brill\'s rolls', 970317, 1, 'The last bell rung', 0, 0, '', 0, 0, '', 1930, 1, 0, 0, 0, 0, 0, 0),
(970305, 2, 1, 1, -1, 0, 0, 0, 0, '[openly]', '', '', '', '', 0, 0, '', 0, 0, '', 0, 0, '', 0, 0, '', 0, 0, 0, 0, 0, 0, 0, 0),
(970306, 2, 1, 1, -1, 0, 0, 0, 0, '[quietly]', '', '', '', '', 0, 0, '', 0, 0, '', 0, 0, '', 0, 0, '', 0, 0, 0, 0, 0, 0, 0, 0);
INSERT INTO `quest_template_addon` (`ID`, `PrevQuestID`, `SpecialFlags`) VALUES (970300, 0, 0), (970301, 970300, 0), (970302, 970301, 0), (970303, 970302, 0), (970304, 970303, 0);
INSERT INTO `quest_offer_reward` (`ID`, `Emote1`, `RewardText`) VALUES
(970300, 1, 'Hollin, Hale, Fairweather, Shaw. Every one of them twice.$B$BThe first entry\'s mine: buried in the plague year. The second is in a Deathguard\'s square hand, two years on. \'Remains found walking. Put down. Returned to plot.\'$B$BSo they were Scourge, once, after they died. The Deathguard cut them down and gave them back to the earth. I\'d have called that the end of the matter. It seems the earth didn\'t agree.'),
(970301, 1, 'My own spade. I wondered where that went.$B$BAnd this lid: scored on the inside, as if someone woke and clawed at it. But the nails were drawn from the outside, one by one, by somebody who knew what they were doing.$B$BNo one is stealing the dead, $n. Someone is letting them out.'),
(970302, 1, '(The woman lowers the spade, but doesn\'t let go of it.)$B$BMortimer sent you? Mortimer Weller, the clerk? He wrote my name in that book of his. Twice, I expect.$B$BI\'m Mercy Hollin. I was the midwife here, when Brill had children. Behind me are Gideon, Edith and Walter. We\'re not Scourge, whatever you\'ve been told. We were, once. We remember it. That\'s why we\'re hiding.'),
(970303, 1, 'Look at Gideon, turning that chisel over like he\'d never seen one. And Edith hasn\'t let go of her basket since you put it in her hands.$B$BWalter read his letters twice and then went outside to stand in the rain. Leave him be. He\'ll come back.$B$BYou\'ve given them something I couldn\'t, $n: proof they were somebody before, and not only something.'),
(970304, 1, 'Hollin. Hale. Fairweather. Shaw. Written a third time, and in the right column at last.$B$B(Gideon Hale clears his throat behind you.)$B$B"Gravekeeper. There\'s something you should know, since we\'re being honest. In the plague year a gentleman named Croft paid me to brick up the old way down beneath this cemetery, and two others like it about the town. To keep the sickness out of the refuge, he said. The people sheltering below went quiet soon after. I\'ve wondered what I sealed in, ever since I could wonder again."$B$B(Mortimer looks at you for a long moment.)$B$BWrite that down somewhere, $n. Not in my ledger. I\'ve enough to keep in it.');
INSERT INTO `quest_request_items` (`ID`, `EmoteOnComplete`, `EmoteOnIncomplete`, `CompletionText`) VALUES
(970300, 1, 1, 'Have you seen to the ledgers? And the stones along the wall?'),
(970301, 1, 1, 'Anything? Even a footprint would be more than the Deathguard have found.'),
(970303, 1, 1, 'Walter keeps asking if you\'ve found his letters. He pretends he isn\'t asking.'),
(970304, 1, 1, 'Have the names been entered? Has it rung?');
INSERT INTO `creature_queststarter` (`id`, `quest`) VALUES (970300, 970300), (970300, 970301), (970300, 970302), (970301, 970303), (970301, 970304);
INSERT INTO `creature_questender` (`id`, `quest`) VALUES (970300, 970300), (970300, 970301), (970301, 970302), (970301, 970303), (970300, 970304);

-- The Apothecary's Apprentice
INSERT INTO `creature_template` (`entry`, `name`, `subname`, `gossip_menu_id`, `minlevel`, `maxlevel`, `faction`, `npcflag`, `speed_walk`, `speed_run`, `unit_class`, `unit_flags`, `type`, `rank`, `HealthModifier`, `DamageModifier`, `mingold`, `maxgold`, `flags_extra`, `AIName`, `ScriptName`) VALUES
(970400, 'Wendell Crane', 'Apprentice Apothecary', 0, 15, 15, 68, 3, 1, 1.14286, 1, 0, 6, 0, 1, 1, 0, 0, 2, '', ''),
(970401, 'Frenzied Specimen', NULL, 0, 10, 10, 14, 0, 1, 1.14286, 1, 0, 1, 0, 2.2, 1.3, 40, 40, 0, 'SmartAI', ''),
(970402, 'Storage shed investigated', NULL, 0, 1, 1, 35, 0, 1, 1, 1, 33554432, 10, 0, 1, 1, 0, 0, 130, '', ''),
(970403, 'Stolen reagents recovered', NULL, 0, 1, 1, 35, 0, 1, 1, 1, 33554432, 10, 0, 1, 1, 0, 0, 130, '', ''),
(970404, 'Suspicious delivery examined', NULL, 0, 1, 1, 35, 0, 1, 1, 1, 33554432, 10, 0, 1, 1, 0, 0, 130, '', ''),
(970405, 'Caged darkhounds examined', NULL, 0, 1, 1, 35, 0, 1, 1, 1, 33554432, 10, 0, 1, 1, 0, 0, 130, '', ''),
(970406, 'Judgment given', NULL, 0, 1, 1, 35, 0, 1, 1, 1, 33554432, 10, 0, 1, 1, 0, 0, 130, '', '');
INSERT INTO `creature_template_model` (`CreatureID`, `Idx`, `CreatureDisplayID`, `DisplayScale`, `Probability`) VALUES
(970400, 0, 4111, 1, 1),
(970401, 0, 9020, 1.25, 1),
(970402, 0, 11686, 1, 1),
(970403, 0, 11686, 1, 1),
(970404, 0, 11686, 1, 1),
(970405, 0, 11686, 1, 1),
(970406, 0, 11686, 1, 1);
INSERT INTO `creature` (`guid`, `id`, `map`, `zoneId`, `areaId`, `spawnMask`, `phaseMask`, `position_x`, `position_y`, `position_z`, `orientation`, `spawntimesecs`, `wander_distance`, `MovementType`, `Comment`) VALUES
(970400, 970400, 0, 85, 0, 1, 1, 2249.500, 343.500, 35.200, 3.1400, 60, 0, 0, 'mod-brill-home town'),
(970401, 970401, 0, 85, 0, 1, 1, 2606.000, 186.000, 33.300, 2.5000, 60, 4, 1, 'mod-brill-home town');
UPDATE `creature_template` SET `AIName` = 'SmartAI' WHERE `entry` IN (970401);
INSERT INTO `smart_scripts` (`entryorguid`, `source_type`, `id`, `link`, `event_type`, `event_phase_mask`, `event_chance`, `event_flags`, `event_param1`, `event_param2`, `event_param3`, `event_param4`, `event_param5`, `event_param6`, `action_type`, `action_param1`, `action_param2`, `action_param3`, `action_param4`, `action_param5`, `action_param6`, `target_type`, `target_param1`, `target_param2`, `target_param3`, `target_param4`, `target_x`, `target_y`, `target_z`, `target_o`, `comment`) VALUES
(970401, 0, 0, 0, 0, 0, 100, 0, 3000, 5000, 12000, 16000, 0, 0, 11, 3427, 0, 0, 0, 0, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0, 'Frenzied Specimen casts 3427');
INSERT INTO `gameobject_template` (`entry`, `type`, `displayId`, `name`, `size`, `Data0`, `Data1`, `ScriptName`) VALUES
(970400, 2, 662, 'Locked Storage Shed', 1, 0, 0, 'go_brill_town'),
(970401, 5, 7485, 'Washbasin', 1, 0, 0, ''),
(970402, 5, 4073, 'Dirty Glassware', 1, 0, 0, ''),
(970403, 2, 335, 'Pilfered Reagent Crate', 1, 0, 0, 'go_brill_town'),
(970404, 2, 6961, 'Abandoned Handcart', 1, 0, 0, 'go_brill_town'),
(970405, 2, 4151, 'Wendell\'s Cage', 0.7, 0, 0, 'go_brill_town'),
(970406, 2, 4151, 'Wendell\'s Cage', 0.7, 0, 0, 'go_brill_town'),
(970407, 2, 4151, 'Wendell\'s Cage', 0.7, 0, 0, 'go_brill_town'),
(970408, 5, 4391, 'Crane\'s Apparatus', 1, 0, 0, ''),
(970409, 5, 7448, 'Bubbling Retort', 0.45, 0, 0, ''),
(970410, 5, 254, 'Johaan\'s Ledger', 1, 0, 0, ''),
(970411, 5, 324, 'Charred Remains', 1, 0, 0, ''),
(970412, 5, 239, 'Scrubbing Bucket', 1, 0, 0, '');
INSERT INTO `gameobject` (`guid`, `id`, `map`, `zoneId`, `areaId`, `spawnMask`, `phaseMask`, `position_x`, `position_y`, `position_z`, `orientation`, `rotation0`, `rotation1`, `rotation2`, `rotation3`, `spawntimesecs`, `animprogress`, `state`, `Comment`) VALUES
(970400, 970400, 0, 85, 0, 1, 1, 2250.500, 355.000, 35.200, 5.6200, 0, 0, 0.325549, -0.945525, 0, 255, 1, 'mod-brill-home town'),
(970401, 970401, 0, 85, 0, 1, 1, 2247.600, 344.600, 35.200, 1.6000, 0, 0, 0.717356, 0.696707, 0, 255, 1, 'mod-brill-home town'),
(970402, 970402, 0, 85, 0, 1, 1, 2247.400, 342.400, 35.200, 4.0000, 0, 0, 0.909297, -0.416147, 0, 255, 1, 'mod-brill-home town'),
(970403, 970403, 0, 85, 0, 1, 1, 2286.000, 1298.000, 32.200, 1.0000, 0, 0, 0.479426, 0.877583, 0, 255, 1, 'mod-brill-home town'),
(970404, 970403, 0, 85, 0, 1, 1, 2294.000, 1322.000, 31.500, 2.2000, 0, 0, 0.891207, 0.453596, 0, 255, 1, 'mod-brill-home town'),
(970405, 970403, 0, 85, 0, 1, 1, 2276.000, 1334.000, 33.500, 4.1000, 0, 0, 0.887362, -0.461073, 0, 255, 1, 'mod-brill-home town'),
(970406, 970403, 0, 85, 0, 1, 1, 2300.000, 1305.000, 31.400, 0.4000, 0, 0, 0.198669, 0.980067, 0, 255, 1, 'mod-brill-home town'),
(970407, 970404, 0, 85, 0, 1, 1, 2280.000, 1288.000, 31.500, 0.8000, 0, 0, 0.389418, 0.921061, 0, 255, 1, 'mod-brill-home town'),
(970408, 970405, 0, 85, 0, 1, 1, 2450.000, 200.000, 37.500, 1.0000, 0, 0, 0.479426, 0.877583, 0, 255, 1, 'mod-brill-home town'),
(970409, 970406, 0, 85, 0, 1, 1, 2522.000, 252.000, 47.000, 3.0000, 0, 0, 0.997495, 0.070737, 0, 255, 1, 'mod-brill-home town'),
(970410, 970407, 0, 85, 0, 1, 1, 2602.000, 182.000, 34.000, 5.0000, 0, 0, 0.598472, -0.801144, 0, 255, 1, 'mod-brill-home town'),
(970411, 970408, 0, 85, 0, 1, 8388608, 2246.600, 340.400, 35.200, 3.3000, 0, 0, 0.996865, -0.079121, 0, 255, 1, 'mod-brill-home town'),
(970412, 970409, 0, 85, 0, 1, 8388608, 2245.800, 346.000, 35.200, 0.0000, 0, 0, 0.000000, 1.000000, 0, 255, 1, 'mod-brill-home town'),
(970413, 970410, 0, 85, 0, 1, 8388608, 2251.200, 341.000, 35.200, 2.0000, 0, 0, 0.841471, 0.540302, 0, 255, 1, 'mod-brill-home town'),
(970414, 970411, 0, 85, 0, 1, 16777216, 2252.600, 352.000, 35.200, 0.6000, 0, 0, 0.295520, 0.955336, 0, 255, 1, 'mod-brill-home town'),
(970415, 970412, 0, 85, 0, 1, 16777216, 2248.700, 345.000, 35.200, 0.0000, 0, 0, 0.000000, 1.000000, 0, 255, 1, 'mod-brill-home town');
INSERT INTO `brill_town_use` (`go`, `ord`, `cond`, `quest`, `credit`, `text_id`, `marker`) VALUES
(970400, 0, 'I970400', 970400, 970402, 970400, 0),
(970400, 1, 'R970407', 0, 0, 970401, 0),
(970400, 2, 'R970406', 0, 0, 970402, 0),
(970400, 3, 'R970405', 0, 0, 970403, 0),
(970400, 4, '', 0, 0, 970404, 0),
(970403, 0, 'I970401', 970401, 970403, 970405, 0),
(970403, 1, '', 0, 0, 970406, 0),
(970404, 0, 'I970401', 970401, 970404, 970407, 0),
(970404, 1, '', 0, 0, 970408, 0),
(970405, 0, 'I970403', 970403, 970405, 970409, 0),
(970405, 1, '', 0, 0, 970410, 0),
(970406, 0, 'I970403', 970403, 970405, 970411, 0),
(970406, 1, '', 0, 0, 970412, 0),
(970407, 0, 'I970403', 970403, 970405, 970413, 0),
(970407, 1, '', 0, 0, 970414, 0);
INSERT INTO `brill_town_gossip` (`npc`, `ord`, `kind`, `cond`, `icon`, `text`, `text_id`, `action`, `quest`, `credit`, `marker`, `emote`, `reply`, `arg`) VALUES
(1518, 2000, 'option', 'I970404', 0, '(Hand him the notes.) It belongs to the Society. Take it.', 0, 'choice', 970404, 970406, 970405, 0, 'Sensible. Faranell will be delighted; he so rarely is. Crane will be credited, in a footnote, in a file no one reads. That is more than most of us are given.', 0),
(1518, 2001, 'option', 'I970404', 0, 'Let him go on with it, Apothecary. Under your eye.', 0, 'choice', 970404, 970406, 970406, 0, 'Under my eye. You do realize it is the only one I have left that still sits properly in its socket? ...Very well. A bench in the yard, my reagents, my ledger, my rules. If a single hound bites anyone, it is both of you I report.', 0),
(1518, 2002, 'option', 'I970404', 0, '(Burn the notes in the still behind the shed.) No one should have this.', 0, 'choice', 970404, 970406, 970407, 0, '(Johaan watches the smoke rise from behind the laboratory and says nothing for a long while.)$B$BPerhaps. I would have liked to read it first. That is the trouble with alchemists, $n. We always want to read everything first.', 0),
(1518, 2003, 'greet', 'R970405', 0, NULL, 970415, '', 0, 0, 0, 0, NULL, 0),
(1518, 2004, 'greet', 'R970406', 0, NULL, 970416, '', 0, 0, 0, 0, NULL, 0),
(1518, 2005, 'greet', 'R970407', 0, NULL, 970417, '', 0, 0, 0, 0, NULL, 0),
(1518, 2006, 'greet', 'I970401', 0, NULL, 970418, '', 0, 0, 0, 0, NULL, 0),
(970400, 2007, 'greet', 'R970405 R970804', 0, NULL, 970419, '', 0, 0, 0, 0, NULL, 0),
(970400, 2008, 'greet', 'R970406 R970804', 0, NULL, 970420, '', 0, 0, 0, 0, NULL, 0),
(970400, 2009, 'greet', 'R970405', 0, NULL, 970421, '', 0, 0, 0, 0, NULL, 0),
(970400, 2010, 'greet', 'R970406', 0, NULL, 970422, '', 0, 0, 0, 0, NULL, 0),
(970400, 2011, 'greet', 'R970407', 0, NULL, 970423, '', 0, 0, 0, 0, NULL, 0),
(970400, 2012, 'greet', 'A970404', 0, NULL, 970424, '', 0, 0, 0, 0, NULL, 0),
(970400, 2013, 'greet', 'R970402', 0, NULL, 970425, '', 0, 0, 0, 0, NULL, 0),
(970400, 2014, 'greet', 'A970400', 0, NULL, 970426, '', 0, 0, 0, 0, NULL, 0),
(970400, 2015, 'greet', '', 0, NULL, 970427, '', 0, 0, 0, 0, NULL, 0),
(2132, 2016, 'option', 'R970407', 0, '(Ask her about Wendell Crane.)', 0, 'say', 0, 0, 0, 0, 'You burned it. Good. Some things shouldn\'t be kept, $n. Only the things that matter. Only the things that are yours.', 0),
(2132, 2017, 'option', 'R970406', 0, '(Ask her about Wendell Crane.)', 0, 'say', 0, 0, 0, 0, 'Wendell brought me a jar of the Stay to look at. Fine work. Careful. I\'d never let anyone put it in me, of course. I\'d rather keep my rot. It\'s mine.', 0),
(2132, 2018, 'option', 'R970405', 0, '(Ask her about Wendell Crane.)', 0, 'say', 0, 0, 0, 0, 'The Society has his notes now. They\'ll make something of it, and none of us will be allowed to have it. That\'s what the Society is for.', 0),
(2132, 2019, 'option', 'R970402 !R970404', 0, '(Ask her about Wendell Crane.)', 0, 'say', 0, 0, 0, 0, 'So it\'s Wendell. Poor thing. He asked me once whether lavender oil could cover the smell of a bad hand. I told him nothing covers it. You just stop letting people close enough to notice.', 0),
(2132, 2020, 'option', 'A970400', 0, '(Ask her about Johaan\'s missing ingredients.)', 0, 'say', 0, 0, 0, 0, 'Johaan\'s been counting his jars twice a day. He thinks I don\'t notice him counting mine as well. I don\'t steal, $n. I\'ve never needed to.', 0),
(2132, 2021, 'option', 'A970401', 0, '(Ask her about Johaan\'s missing ingredients.)', 0, 'say', 0, 0, 0, 0, 'Johaan\'s been counting his jars twice a day. He thinks I don\'t notice him counting mine as well. I don\'t steal, $n. I\'ve never needed to.', 0);
UPDATE `creature_template` SET `npcflag` = `npcflag` | 1 WHERE `entry` IN (1518, 2132);
INSERT INTO `brill_town_phase` (`bit`, `ord`, `cond`) VALUES
(8388608, 0, 'R970406'),
(16777216, 0, 'R970407');
INSERT INTO `quest_template` (`ID`, `QuestType`, `QuestLevel`, `MinLevel`, `QuestSortID`, `Flags`, `RewardXPDifficulty`, `RewardMoney`, `AllowableRaces`, `LogTitle`, `LogDescription`, `QuestDescription`, `AreaDescription`, `QuestCompletionLog`, `RequiredNpcOrGo1`, `RequiredNpcOrGoCount1`, `ObjectiveText1`, `RequiredNpcOrGo2`, `RequiredNpcOrGoCount2`, `ObjectiveText2`, `RequiredNpcOrGo3`, `RequiredNpcOrGoCount3`, `ObjectiveText3`, `RequiredNpcOrGo4`, `RequiredNpcOrGoCount4`, `ObjectiveText4`, `RewardItem1`, `RewardAmount1`, `RewardItem2`, `RewardAmount2`, `RewardItem3`, `RewardAmount3`, `RewardItem4`, `RewardAmount4`) VALUES
(970400, 2, -1, 5, 159, 0, 3, 1500, 0, 'An Unpleasant Odor', 'Investigate the locked storage shed behind Apothecary Johaan\'s laboratory in Brill, then return to Johaan.', 'You there. You have a nose, I trust? Most of Brill has stopped using theirs, which is a mercy, given the town\'s chief exports.$B$BThings have been going missing from my stores. Embalming salts. Grave moss. Three flasks of distilled bile I was saving for a particularly ungrateful Scarlet. I keep inventories, $n. I may be the only person left in Tirisfal who keeps inventories.$B$BAnd for a week now there has been a smell from the storage shed behind the laboratory. Not our usual smell. Something sweet beneath it, like lilies left too long on a coffin. I do not keep lilies.$B$BThe shed is locked, and I did not lock it. Go and look. Through the boards, if you must. Tell me what is in there before I am obliged to go and see for myself.', '', 'Return to Apothecary Johaan in Brill.', 970402, 1, 'Storage shed investigated', 0, 0, '', 0, 0, '', 0, 0, '', 0, 0, 0, 0, 0, 0, 0, 0),
(970401, 2, -1, 5, 159, 0, 4, 3000, 0, 'Missing Reagents', 'Recover 4 crates of stolen reagents and examine the suspicious delivery at Solliden Farmstead, north of Brill, then return to Apothecary Johaan.', 'Solliden Farmstead lies north of here, past the mills. Old Solliden still walks his fields, though he no longer remembers what they are for, and the hands who work them are less pleasant company than he is. A quiet place to leave a crate, if one did not wish to be asked about it.$B$BWhatever was carted out there is mine, and the Society\'s, which is to say the Dark Lady\'s. Bring it back. Every crate you can find.$B$BAnd look for the delivery itself: a cart, a sack, a bill of lading, anything with a hand on it. Thieves are seldom clever, $n, but they are always careless about paper. I want to know who has been signing for my salts.', '', 'Return to Apothecary Johaan in Brill.', 970403, 4, 'Stolen reagents recovered', 970404, 1, 'Suspicious delivery examined', 0, 0, '', 0, 0, '', 858, 2, 0, 0, 0, 0, 0, 0),
(970402, 2, -1, 5, 159, 0, 2, 1500, 0, 'The Apprentice\'s Secret', 'Speak with Wendell Crane in the yard behind Apothecary Johaan\'s laboratory in Brill.', 'You have returned my salts, and I am grateful, in my fashion. Now I need one thing more, and I need it from someone who is not me.$B$BWendell works in the yard behind the laboratory, washing whatever I dirty. Go and speak with him. Show him those slips. I want to know what he has been brewing in my shed, and for whom. If he is selling to the Scarlets, or the Cult, or, the Dark Lady forbid, to the trolls, I will deal with it myself.$B$BIf it is something else... then I want to know what it is before I decide what it is worth.$B$BHe will talk to you. He has not talked to me in three years, except to say \'yes, Apothecary.\'', '', 'Speak with Wendell Crane behind the laboratory in Brill.', 0, 0, '', 0, 0, '', 0, 0, '', 0, 0, '', 0, 0, 0, 0, 0, 0, 0, 0),
(970403, 2, -1, 5, 159, 0, 4, 3500, 0, 'A Question of Progress', 'Examine Wendell Crane\'s three caged darkhounds in the hills north-east of Brill and deal with whatever has become of the third, then return to Wendell.', 'Mostly. That\'s the word that keeps me up, and I don\'t even sleep.$B$BIn a jar the Stay is perfect: no rot, no softening, not in six weeks. But a jar isn\'t a body. I needed something living and rotting at once, and Tirisfal has no shortage of those.$B$BThe darkhounds north-east of town have the wasting sickness: fur coming out in handfuls, sores that never close. I baited three of them with dosed meat and caged them in the hills on the way to the lake. I haven\'t dared go back. Johaan watches the road, and I\'m a coward besides.$B$BLook at them for me. All three. Tell me what the Stay has done to them. Whatever it is.', '', 'Return to Wendell Crane behind the laboratory in Brill.', 970405, 3, 'Caged darkhounds examined', 970401, 1, '', 0, 0, '', 0, 0, '', 2455, 2, 858, 2, 0, 0, 0, 0),
(970404, 2, -1, 5, 159, 0, 5, 4500, 0, 'The Apothecary\'s Judgment', 'Take Wendell Crane\'s notes on the Stay to Apothecary Johaan and decide what becomes of them.', 'I\'ve written it all out. The recipe, the doses, the hounds. Every mistake.$B$B(He presses a bound sheaf of notes into your hands. The ink on the last page is still wet.)$B$BTake it to Johaan. I can\'t. If I stand in front of him I\'ll only say \'yes, Apothecary\' and let him decide, the way I always have.$B$BYou\'ve seen what it does, the good and the bad. You decide what he hears. Give it to him, and the Society will have it, and Faranell\'s people in the Undercity will do with it whatever they do. Ask him to let me go on, under his eye, slowly, properly. Or burn it, and I\'ll go back to washing glass, and we\'ll all rot at the usual rate.$B$BWhatever you choose, I\'ll abide by it. I\'m tired of being the only one who knows.', '', 'Return to Apothecary Johaan in Brill.', 970406, 1, 'Judgment given', 0, 0, '', 0, 0, '', 0, 0, '', 2458, 2, 2459, 1, 0, 0, 0, 0),
(970405, 2, 1, 1, -1, 0, 0, 0, 0, '[surrendered]', '', '', '', '', 0, 0, '', 0, 0, '', 0, 0, '', 0, 0, '', 0, 0, 0, 0, 0, 0, 0, 0),
(970406, 2, 1, 1, -1, 0, 0, 0, 0, '[supervised]', '', '', '', '', 0, 0, '', 0, 0, '', 0, 0, '', 0, 0, '', 0, 0, 0, 0, 0, 0, 0, 0),
(970407, 2, 1, 1, -1, 0, 0, 0, 0, '[destroyed]', '', '', '', '', 0, 0, '', 0, 0, '', 0, 0, '', 0, 0, '', 0, 0, 0, 0, 0, 0, 0, 0);
INSERT INTO `quest_template_addon` (`ID`, `PrevQuestID`, `SpecialFlags`) VALUES (970400, 0, 0), (970401, 970400, 0), (970402, 970401, 0), (970403, 970402, 0), (970404, 970403, 0);
INSERT INTO `quest_offer_reward` (`ID`, `Emote1`, `RewardText`) VALUES
(970400, 1, 'A still. In my shed. With my salts in it.$B$B(Johaan pinches the bridge of his nose. A flake of skin comes away; he flicks it aside.)$B$BAnd crates bound for Solliden Farmstead. Someone is carting my reagents out of Brill and brewing with whatever is left behind my back. One of my apprentices, then. I have two, and I trust neither of them, which is the proper relationship.$B$BWell. At least they label their crates.'),
(970401, 1, 'Crane.$B$B(Johaan is silent for a long moment.)$B$BWendell Crane has been my apprentice for three years. He scrubs my glassware. He sweeps my floor. He has never once spoken out of turn, which I took for respect and ought to have taken for concealment.$B$BI won\'t question him. If I question him, I shall have to report whatever he says to the Society, and the Society does not believe in second chances. Or, in my experience, in first ones.'),
(970402, 1, '(Wendell looks at the slips, then at his hands. Two fingers of the left are gone at the knuckle; the rest are bound in gauze.)$B$BSo he knows. Of course he knows. He counts everything.$B$BI\'m not selling it. I\'m not selling anything. I\'m trying to stop this.$B$B(He holds up the hand.)$B$BI washed the dead for burial in Andorhal, before the grain came. I know what happens to a body, $n. I know it to the day. We\'re no different; we\'re only slower. A finger a year. An ear. One morning you wake and your jaw won\'t close. The Society\'s salts keep the worst of it off. I\'m making something that keeps us whole.$B$BI call it the Stay. And it works. Mostly.'),
(970403, 1, 'One healed. One set like a tanned hide. And one... I heard it, you know. Last night, from the yard. I told myself it was the wind.$B$B(Wendell writes it all down, carefully, with his three good fingers.)$B$BThe flesh holds. That much is true. But the stiffening, the madness... If the Stay does that to a hound\'s mind, what would it do to ours? Our minds are all that keep us from being the Scourge, $n. Will, and nothing else.$B$BI don\'t know whether I\'ve made a cure or a cage.'),
(970404, 1, 'It is settled, then. Whatever we have done, we have done it with our eyes open, which is more than the Lich King ever allowed any of us.$B$B(Johaan opens his ledger and writes a single line. He blots it with great care.)$B$BCrane will hear it from me. You have earned a rest from my apprentices\' troubles, and I have earned a rest from my own. Take these. They were brewed properly, by someone who keeps inventories.');
INSERT INTO `quest_request_items` (`ID`, `EmoteOnComplete`, `EmoteOnIncomplete`, `CompletionText`) VALUES
(970400, 1, 1, 'Well? I can smell it from here. Which, I assure you, is saying something.'),
(970401, 1, 1, 'My salts, $n. And the name. I will settle for the salts if I must.'),
(970403, 1, 1, 'Did it work? Don\'t tell me it didn\'t work. Tell me, but gently.'),
(970404, 1, 1, 'You have something of Crane\'s. I can tell by the way you hold it. Like a dead bird.');
INSERT INTO `creature_queststarter` (`id`, `quest`) VALUES (1518, 970400), (1518, 970401), (1518, 970402), (970400, 970403), (970400, 970404);
INSERT INTO `creature_questender` (`id`, `quest`) VALUES (1518, 970400), (1518, 970401), (970400, 970402), (970400, 970403), (1518, 970404);
UPDATE `creature_template` SET `npcflag` = `npcflag` | 3 WHERE `entry` IN (1518);

-- Whispers of the Scarlet
INSERT INTO `creature_template` (`entry`, `name`, `subname`, `gossip_menu_id`, `minlevel`, `maxlevel`, `faction`, `npcflag`, `speed_walk`, `speed_run`, `unit_class`, `unit_flags`, `type`, `rank`, `HealthModifier`, `DamageModifier`, `mingold`, `maxgold`, `flags_extra`, `AIName`, `ScriptName`) VALUES
(970500, 'Lowell Marsh', 'Lamplighter', 0, 10, 10, 68, 3, 1, 1.14286, 1, 0, 6, 0, 1, 1, 0, 0, 2, '', ''),
(970501, 'Scarlet Courier', NULL, 0, 9, 10, 67, 0, 1, 1.14286, 1, 0, 7, 0, 1, 1, 40, 40, 0, '', ''),
(970502, 'Charred letter recovered', NULL, 0, 1, 1, 35, 0, 1, 1, 1, 33554432, 10, 0, 1, 1, 0, 0, 130, '', ''),
(970503, 'Innkeeper Renee questioned', NULL, 0, 1, 1, 35, 0, 1, 1, 1, 33554432, 10, 0, 1, 1, 0, 0, 130, '', ''),
(970504, 'Mrs. Winters questioned', NULL, 0, 1, 1, 35, 0, 1, 1, 1, 33554432, 10, 0, 1, 1, 0, 0, 130, '', ''),
(970505, 'Abigail Shiel questioned', NULL, 0, 1, 1, 35, 0, 1, 1, 1, 33554432, 10, 0, 1, 1, 0, 0, 130, '', ''),
(970506, 'Signs of the informant found', NULL, 0, 1, 1, 35, 0, 1, 1, 1, 33554432, 10, 0, 1, 1, 0, 0, 130, '', ''),
(970507, 'Street lamp searched', NULL, 0, 1, 1, 35, 0, 1, 1, 1, 33554432, 10, 0, 1, 1, 0, 0, 130, '', ''),
(970508, 'Captured Scarlet Zealot questioned', NULL, 0, 1, 1, 35, 0, 1, 1, 1, 33554432, 10, 0, 1, 1, 0, 0, 130, '', ''),
(970509, 'Lowell Marsh\'s fate decided', NULL, 0, 1, 1, 35, 0, 1, 1, 1, 33554432, 10, 0, 1, 1, 0, 0, 130, '', '');
INSERT INTO `creature_template_model` (`CreatureID`, `Idx`, `CreatureDisplayID`, `DisplayScale`, `Probability`) VALUES
(970500, 0, 2611, 1, 1),
(970501, 0, 2467, 1, 1),
(970501, 1, 2478, 1, 1),
(970502, 0, 11686, 1, 1),
(970503, 0, 11686, 1, 1),
(970504, 0, 11686, 1, 1),
(970505, 0, 11686, 1, 1),
(970506, 0, 11686, 1, 1),
(970507, 0, 11686, 1, 1),
(970508, 0, 11686, 1, 1),
(970509, 0, 11686, 1, 1);
INSERT INTO `creature` (`guid`, `id`, `map`, `zoneId`, `areaId`, `spawnMask`, `phaseMask`, `position_x`, `position_y`, `position_z`, `orientation`, `spawntimesecs`, `wander_distance`, `MovementType`, `Comment`) VALUES
(970500, 970500, 0, 85, 0, 1, 33554432, 2236.000, 286.000, 35.170, 0.3000, 60, 0, 0, 'mod-brill-home town'),
(970501, 970501, 0, 85, 0, 1, 1, 2480.000, 150.000, 29.020, 1.0000, 120, 4, 1, 'mod-brill-home town'),
(970502, 970501, 0, 85, 0, 1, 1, 2495.000, 165.000, 29.030, 3.5000, 120, 4, 1, 'mod-brill-home town'),
(970503, 970501, 0, 85, 0, 1, 1, 2505.000, 150.000, 28.210, 2.2000, 120, 4, 1, 'mod-brill-home town'),
(970504, 970501, 0, 85, 0, 1, 1, 2475.000, 170.000, 29.920, 5.0000, 120, 4, 1, 'mod-brill-home town');
INSERT INTO `gameobject_template` (`entry`, `type`, `displayId`, `name`, `size`, `Data0`, `Data1`, `ScriptName`) VALUES
(970500, 2, 201, 'Smouldering Campfire', 1, 0, 0, 'go_brill_town'),
(970501, 2, 498, 'Loose Cobbles', 0.6, 0, 0, 'go_brill_town'),
(970502, 2, 275, 'Chalk-Marked Crate', 0.8, 0, 0, 'go_brill_town'),
(970503, 2, 731, 'Brill Street Lamp', 0.9, 0, 0, 'go_brill_town');
INSERT INTO `gameobject` (`guid`, `id`, `map`, `zoneId`, `areaId`, `spawnMask`, `phaseMask`, `position_x`, `position_y`, `position_z`, `orientation`, `rotation0`, `rotation1`, `rotation2`, `rotation3`, `spawntimesecs`, `animprogress`, `state`, `Comment`) VALUES
(970500, 970500, 0, 85, 0, 1, 1, 2485.000, 158.000, 29.070, 0.0000, 0, 0, 0.000000, 1.000000, 0, 255, 1, 'mod-brill-home town'),
(970501, 970500, 0, 85, 0, 1, 1, 2500.000, 175.000, 30.110, 1.2000, 0, 0, 0.564642, 0.825336, 0, 255, 1, 'mod-brill-home town'),
(970502, 970500, 0, 85, 0, 1, 1, 2470.000, 162.000, 30.090, 2.4000, 0, 0, 0.932039, 0.362358, 0, 255, 1, 'mod-brill-home town'),
(970503, 970501, 0, 85, 0, 1, 1, 2231.500, 283.500, 35.170, 0.0000, 0, 0, 0.000000, 1.000000, 0, 255, 1, 'mod-brill-home town'),
(970504, 970502, 0, 85, 0, 1, 1, 2281.000, 257.000, 34.470, 1.9000, 0, 0, 0.813416, 0.581683, 0, 255, 1, 'mod-brill-home town'),
(970505, 970503, 0, 85, 0, 1, 1, 2244.000, 300.000, 35.190, 0.5000, 0, 0, 0.247404, 0.968912, 0, 255, 1, 'mod-brill-home town'),
(970506, 970503, 0, 85, 0, 1, 1, 2276.000, 272.000, 35.180, 2.0000, 0, 0, 0.841471, 0.540302, 0, 255, 1, 'mod-brill-home town'),
(970507, 970503, 0, 85, 0, 1, 1, 2266.000, 312.000, 34.150, 4.0000, 0, 0, 0.909297, -0.416147, 0, 255, 1, 'mod-brill-home town');
INSERT INTO `brill_town_use` (`go`, `ord`, `cond`, `quest`, `credit`, `text_id`, `marker`) VALUES
(970500, 0, 'I970500', 970500, 970502, 970500, 0),
(970500, 1, '', 0, 0, 970501, 0),
(970501, 0, 'I970501', 970501, 970506, 970502, 0),
(970501, 1, '', 0, 0, 970503, 0),
(970502, 0, 'I970501', 970501, 970506, 970504, 0),
(970502, 1, 'R970501', 0, 0, 970505, 0),
(970502, 2, '', 0, 0, 970506, 0),
(970503, 0, 'I970502', 970502, 970507, 970507, 0),
(970503, 1, 'R970507', 0, 0, 970508, 0),
(970503, 2, '', 0, 0, 970509, 0);
INSERT INTO `brill_town_gossip` (`npc`, `ord`, `kind`, `cond`, `icon`, `text`, `text_id`, `action`, `quest`, `credit`, `marker`, `emote`, `reply`, `arg`) VALUES
(970500, 3000, 'greet', 'R970506 D4', 0, NULL, 970510, '', 0, 0, 0, 0, NULL, 0),
(970500, 3001, 'greet', 'R970506', 0, NULL, 970511, '', 0, 0, 0, 0, NULL, 0),
(970500, 3002, 'greet', 'A970504', 0, NULL, 970512, '', 0, 0, 0, 0, NULL, 0),
(970500, 3003, 'greet', 'T970502', 0, NULL, 970513, '', 0, 0, 0, 0, NULL, 0),
(970500, 3004, 'greet', '', 0, NULL, 970514, '', 0, 0, 0, 0, NULL, 0),
(970500, 3005, 'option', 'I970504', 0, 'Magistrate Sevren will hear all of it. You\'ll answer to him.', 0, 'choice', 970504, 970509, 970505, 2, '(Lowell nods, as if he had expected nothing else.) Yes. That\'s fair. Will you walk with me to the town hall? I\'d rather not go with the Deathguard\'s hands on my arms. They were my friends, you see.$B$B(He sets his lamp-pole against the well, very carefully, as though someone else might need it.)', 0),
(970500, 3006, 'option', 'I970504', 0, 'Burn Anselm\'s letters and stay. Be what you are, and be it here.', 0, 'choice', 970504, 970509, 970506, 18, 'Stay. As this. (He looks at his hands for a long time.) Brother Anselm called me a lamb lost in the dark. You\'re the first to say the dark\'s a place a person can live in.$B$BI\'ll burn them. Tonight. All of them. And if he writes again, I\'ll burn that too. If you see me wavering, $n, you have my leave to tell Sevren everything.', 0),
(970500, 3007, 'option', 'I970504', 0, 'Take the west road tonight. Don\'t come back to Brill.', 0, 'choice', 970504, 970509, 970507, 2, 'You\'d... (His voice catches.) I won\'t go to Anselm. I swear it. I\'ll go somewhere the Crusade has never heard of and neither has the Dark Lady, if there\'s such a place left.$B$B(He takes nothing but his coat.) Light the north road lamp for me, would you? Someone ought to. Goodbye, $n.', 0),
(5688, 3008, 'option', 'I970501', 0, 'Who in Brill keeps odd hours, Renee?', 0, 'credit', 970501, 970503, 0, 0, 'Odd hours? Love, everyone here\'s dead; there are no other kind. (She lowers her voice.) But since you ask: the only one who comes in after the second bell is old Lowell, the lamplighter. Takes a seat by the back door, never drinks, leaves when the watch changes. I always thought he was lonely.', 0),
(2134, 3009, 'option', 'I970501', 0, 'Has anyone been asking about the Deathguard\'s rounds?', 0, 'credit', 970501, 970504, 0, 0, 'Asking? No one needs to ask. Every child in the old Brill could tell you when the watch changed by when the lamps were lit. (She frowns.) Strange thing, though. Lowell Marsh bought a whole quire of paper from me last month. Lamplighters don\'t write much.', 0),
(2118, 3010, 'option', 'I970501', 0, 'Seen anyone by the well after dark?', 0, 'credit', 970501, 970505, 0, 0, 'The well? Only the lamplighter, and he\'s always there; he fills his oil pot from the cistern beside it. (She pauses.) Though he did take an awfully long time over it last week. Down on his knees. I took it for prayer. You\'d be surprised how many of us still do.', 0),
(1931, 3011, 'option', 'I970502', 0, '(Show him the charred letters.)', 0, 'credit', 970502, 970508, 0, 0, '(The zealot glances at the letters and his cracked lips twitch into a smile.) Ah. Our little lamb\'s handwriting. He\'ll be made whole, you know, when the Light comes to cleanse this place. Brother Anselm promised him. (He catches himself, too late, and turns his face to the wall.)', 0),
(1931, 3012, 'greet', 'R970505', 0, NULL, 970515, '', 0, 0, 0, 0, NULL, 0),
(1499, 3013, 'greet', 'R970505', 0, NULL, 970516, '', 0, 0, 0, 0, NULL, 0),
(1499, 3014, 'greet', 'R970507', 0, NULL, 970517, '', 0, 0, 0, 0, NULL, 0),
(1499, 3015, 'greet', 'R970506', 0, NULL, 970518, '', 0, 0, 0, 0, NULL, 0),
(5688, 3016, 'greet', 'R970507 D3', 0, NULL, 970519, '', 0, 0, 0, 0, NULL, 0),
(1652, 3017, 'greet', 'R970505', 0, NULL, 970520, '', 0, 0, 0, 0, NULL, 0),
(1652, 3018, 'greet', 'R970507', 0, NULL, 970521, '', 0, 0, 0, 0, NULL, 0),
(1746, 3019, 'greet', 'R970506', 0, NULL, 970522, '', 0, 0, 0, 0, NULL, 0),
(1746, 3020, 'greet', 'R970505', 0, NULL, 970523, '', 0, 0, 0, 0, NULL, 0),
(1745, 3021, 'greet', 'R970507', 0, NULL, 970524, '', 0, 0, 0, 0, NULL, 0),
(1745, 3022, 'greet', 'R970504', 0, NULL, 970525, '', 0, 0, 0, 0, NULL, 0),
(2209, 3023, 'greet', 'R970505', 0, NULL, 970526, '', 0, 0, 0, 0, NULL, 0),
(2210, 3024, 'greet', 'R970504', 0, NULL, 970527, '', 0, 0, 0, 0, NULL, 0);
UPDATE `creature_template` SET `npcflag` = `npcflag` | 1 WHERE `entry` IN (1499, 1652, 1745, 1746, 1931, 2118, 2134, 2209, 2210, 5688);
INSERT INTO `brill_town_phase` (`bit`, `ord`, `cond`) VALUES
(33554432, 0, '!R970505 !R970507');
INSERT INTO `quest_template` (`ID`, `QuestType`, `QuestLevel`, `MinLevel`, `QuestSortID`, `Flags`, `RewardXPDifficulty`, `RewardMoney`, `AllowableRaces`, `LogTitle`, `LogDescription`, `QuestDescription`, `AreaDescription`, `QuestCompletionLog`, `RequiredNpcOrGo1`, `RequiredNpcOrGoCount1`, `ObjectiveText1`, `RequiredNpcOrGo2`, `RequiredNpcOrGoCount2`, `ObjectiveText2`, `RequiredNpcOrGo3`, `RequiredNpcOrGoCount3`, `ObjectiveText3`, `RequiredNpcOrGo4`, `RequiredNpcOrGoCount4`, `ObjectiveText4`, `RewardItem1`, `RewardAmount1`, `RewardItem2`, `RewardAmount2`, `RewardItem3`, `RewardAmount3`, `RewardItem4`, `RewardAmount4`) VALUES
(970500, 2, -1, 5, 159, 0, 4, 1800, 0, 'Letters in the Ashes', 'Kill 4 Scarlet Couriers in the hollow northeast of Brill and recover 3 charred letters from their campfires, then return to Magistrate Sevren in Brill.', 'Three nights ago the Crusade ambushed a supply cart on the east road. They knew which road, they knew which hour, and they knew the escort would be two Deathguards short because of the changing of the watch. That is not luck, $n. Luck doesn\'t keep a schedule.$B$BSomeone is telling them. I don\'t yet know who, and I\'d rather not hang the wrong neck; the town has few enough left.$B$BMy scouts have seen Scarlet couriers in the hollow northeast of town, past the cemetery road. When they\'re cornered they throw their letters on the fire. Zealots: they\'d rather burn the evidence than trust Light to protect it. Kill them before they finish the job and pull what you can from the ashes. Half a letter is still a letter.', '', 'Return to Magistrate Sevren in Brill.', 970501, 4, '', 970502, 3, 'Charred letter recovered', 0, 0, '', 0, 0, '', 858, 2, 0, 0, 0, 0, 0, 0),
(970501, 2, -1, 5, 159, 0, 4, 2000, 0, 'Eyes Among Us', 'Question Innkeeper Renee, Mrs. Winters and Abigail Shiel, and look for signs of the informant by the market well and behind the tavern. Then return to Magistrate Sevren.', 'If I start asking questions myself, the whole town will know by supper that the Magistrate is frightened. They\'d be right, but there\'s no reason to tell them.$B$BYou\'re new enough that people still talk to you. Go and listen. Innkeeper Renee hears everything said over a drink; Mrs. Winters and Abigail Shiel keep the market and see who comes and goes. Ask them who keeps odd hours, who has been asking after the watch.$B$BAnd look around while you\'re at it. Letters have to pass from hand to hand somewhere. The market well and the back of the tavern are both quiet after dark, and both on a patrol\'s blind side. If I were selling my town, that is where I\'d do it.', '', 'Return to Magistrate Sevren in Brill.', 970503, 1, 'Innkeeper Renee questioned', 970504, 1, 'Mrs. Winters questioned', 970505, 1, 'Abigail Shiel questioned', 970506, 2, 'Signs of the informant found', 0, 0, 0, 0, 0, 0, 0, 0),
(970502, 2, -1, 5, 159, 0, 4, 2000, 0, 'The Scarlet Informant', 'Search the three street lamps in Brill\'s square for hidden marks, and question the Captured Scarlet Zealot in the tavern\'s cellar. Then return to Magistrate Sevren.', 'Everything you found keeps time with the lamps. The letter names the hour the lamps are lit. The chalk and the well are on the lamplighter\'s round. Every word of it points at Lowell Marsh, and I\'ve known Lowell since before the Dark Lady gave us back our wills. He lit the lamps in this town when it had living people in it.$B$BSo I want proof, not a pattern. Look at the lamps in the square, the post by the leatherworker, the one by the stables and the one across from the Executor. A man who marks a meeting place marks it where his hands already are.$B$BThen go down to the cellar under the Gallows\' End. We have a zealot down there who thinks his silence is a prayer. Show him the letters. Even a zealot\'s face says something.', '', 'Return to Magistrate Sevren in Brill.', 970507, 3, 'Street lamp searched', 970508, 1, 'Captured Scarlet Zealot questioned', 0, 0, '', 0, 0, '', 2455, 2, 0, 0, 0, 0, 0, 0),
(970503, 2, -1, 5, 159, 0, 2, 1000, 0, 'The Promise of Life', 'Confront Lowell Marsh, the lamplighter, by the well in Brill\'s market.', 'Go to him, $n. Not with guards; he\'d only lie to guards, and he\'d lie badly, and someone would lose patience. Go yourself. He\'s by the well most evenings, cleaning his glass.$B$BI want to know why. Forty years he served this town, and the Crusade burns people like him by the cartload. Whatever they offered him, it must have been worth more to him than his neighbors.$B$BFind out what it was. Then we\'ll decide what Brill does about it. Or what you do, if I can\'t bring myself to.', '', 'Speak with Lowell Marsh in Brill.', 0, 0, '', 0, 0, '', 0, 0, '', 0, 0, '', 0, 0, 0, 0, 0, 0, 0, 0),
(970504, 2, -1, 5, 159, 0, 5, 3500, 0, 'A Betrayer\'s Fate', 'Decide Lowell Marsh\'s fate: expose him to Magistrate Sevren, persuade him to abandon his arrangement, or help him escape Brill. Then return to Magistrate Sevren.', 'Two of our Deathguards died on that east road. I know their names. Morgan and Pell. I lit the lamp they walked under on the way out of town.$B$BI didn\'t think... no. I did think. I just thought about the sun more.$B$BBrother Anselm writes that it\'s nearly ready, the rite. A few more letters. I don\'t even know anymore if I believe him. A man who wants you to betray your town for a miracle would say the miracle was nearly ready, wouldn\'t he?$B$B(He laughs, a dry sound.)$B$BSevren sent you, so it\'s yours to say. Hand me to him, and I\'ll go quietly. Tell me to burn Anselm\'s letters, and I\'ll try, though I don\'t know if I can. Or look the other way while I take the west road tonight, and I\'ll never trouble Brill again. Whatever you choose, $n, I won\'t blame you for it. I\'ve used up all my blaming on myself.', '', 'Return to Magistrate Sevren in Brill.', 970509, 1, 'Lowell Marsh\'s fate decided', 0, 0, '', 0, 0, '', 0, 0, '', 4496, 1, 0, 0, 0, 0, 0, 0),
(970505, 2, 1, 1, -1, 0, 0, 0, 0, '[exposed]', '', '', '', '', 0, 0, '', 0, 0, '', 0, 0, '', 0, 0, '', 0, 0, 0, 0, 0, 0, 0, 0),
(970506, 2, 1, 1, -1, 0, 0, 0, 0, '[spared]', '', '', '', '', 0, 0, '', 0, 0, '', 0, 0, '', 0, 0, '', 0, 0, 0, 0, 0, 0, 0, 0),
(970507, 2, 1, 1, -1, 0, 0, 0, 0, '[escaped]', '', '', '', '', 0, 0, '', 0, 0, '', 0, 0, '', 0, 0, '', 0, 0, 0, 0, 0, 0, 0, 0);
INSERT INTO `quest_template_addon` (`ID`, `PrevQuestID`, `SpecialFlags`) VALUES (970500, 0, 0), (970501, 970500, 0), (970502, 970501, 0), (970503, 970502, 0), (970504, 970503, 0);
INSERT INTO `quest_offer_reward` (`ID`, `Emote1`, `RewardText`) VALUES
(970500, 1, '(Sevren lays the blackened pages on his desk and smooths them flat with a careful finger.)$B$B"...the eastern escort changes at the second bell, and the north road stands empty until the lamps are lit..." They know when the lamps are lit. Of course they do.$B$BThis wasn\'t written by a Crusader, $n. Look at the hand: it shakes. Whoever wrote this has been dead a long time and still remembers his letters. One of ours.'),
(970501, 1, 'A chalk mark behind the tavern and a hollow under the well\'s stones, and three good women who all mention, without being asked, the man who\'s always about when the patrols change.$B$B(Sevren closes his eyes.)$B$BNo. Don\'t say the name yet. I want to be certain before I let myself think it.'),
(970502, 1, 'Three tallies scratched under the lamp-glass, one for each warning sent, and a zealot who laughed and called him "our lamb."$B$B(Sevren is quiet for a long moment.)$B$BLowell Marsh. A lamplighter. He walks every street with the night watch; they talk to him about their hours because he\'s harmless. He has been harmless for forty years.$B$BI could have the Deathguard drag him in tonight. I find I don\'t want to. Not yet.'),
(970503, 1, '(Lowell Marsh sets down his lamp-pole very slowly. For a moment he looks as if he means to run; then his shoulders drop.)$B$BSo the Magistrate knows. Good. I\'m tired of carrying it.$B$BHis name is Brother Anselm. He found me on the north road last spring, alone with my ladder, and he didn\'t burn me. He talked to me. He said the Light can call a soul back into living flesh, that it\'s been done, that the Scourge\'s curse is only a sickness and sicknesses are cured. He said he\'d pray over me himself. He said I\'d feel the sun again.$B$BAll I had to do was tell him when the lamps were lit.$B$B(He looks down at his grey hands.)$B$BDo you know what it is to remember warmth, $g sir:madam;? I\'d have told him anything.'),
(970504, 1, 'It\'s done, then.$B$B(Sevren doesn\'t ask for details, and seems careful not to look as if he wants them.)$B$BThe Crusade will find our patrols harder to read now, whatever became of the man who read them. I\'ll change the watch hours tonight regardless; I\'d be a fool not to.$B$BYou did a hard, quiet thing for this town, $n. Those are the ones nobody thanks you for, so let me be the one who does. Thank you.');
INSERT INTO `quest_request_items` (`ID`, `EmoteOnComplete`, `EmoteOnIncomplete`, `CompletionText`) VALUES
(970500, 1, 1, 'Anything from the hollow? Even a scrap with a date on it would do.'),
(970501, 1, 1, 'Well? I\'ve been staring at that letter so long I could forge it.'),
(970502, 1, 1, 'The lamps, $n, and the zealot. Then we\'ll know.'),
(970504, 1, 1, '(Lowell waits, turning his lamp-pole in his hands.)');
INSERT INTO `creature_queststarter` (`id`, `quest`) VALUES (1499, 970500), (1499, 970501), (1499, 970502), (1499, 970503), (970500, 970504);
INSERT INTO `creature_questender` (`id`, `quest`) VALUES (1499, 970500), (1499, 970501), (1499, 970502), (970500, 970503), (1499, 970504);
UPDATE `creature_template` SET `npcflag` = `npcflag` | 3 WHERE `entry` IN (1499);

-- The Last Customer
INSERT INTO `npc_vendor` (`entry`, `slot`, `item`, `maxcount`, `incrtime`, `ExtendedCost`) VALUES (970600, 0, 755, 0, 0, 0), (970600, 1, 17027, 0, 0, 0), (970600, 2, 5042, 0, 0, 0), (970600, 3, 5048, 0, 0, 0), (970600, 4, 3419, 0, 0, 0), (970600, 5, 3420, 0, 0, 0), (970600, 6, 5363, 0, 0, 0), (970600, 7, 18233, 0, 0, 0), (970600, 8, 5374, 0, 0, 0), (970600, 9, 5115, 0, 0, 0), (970600, 10, 5376, 0, 0, 0), (970600, 11, 3300, 0, 0, 0), (970600, 12, 5373, 0, 0, 0), (970600, 13, 5433, 0, 0, 0), (970600, 14, 11940, 0, 0, 0), (970600, 15, 6297, 0, 0, 0);
INSERT INTO `creature_template` (`entry`, `name`, `subname`, `gossip_menu_id`, `minlevel`, `maxlevel`, `faction`, `npcflag`, `speed_walk`, `speed_run`, `unit_class`, `unit_flags`, `type`, `rank`, `HealthModifier`, `DamageModifier`, `mingold`, `maxgold`, `flags_extra`, `AIName`, `ScriptName`) VALUES
(970600, 'Ambrose Wicke', 'Candles and Sundries', 0, 22, 22, 68, 131, 1, 1.14286, 1, 0, 6, 0, 1, 1, 0, 0, 2, '', ''),
(970601, 'Agnes Balnir', 'Laundress', 0, 18, 18, 68, 3, 1, 1.14286, 1, 0, 6, 0, 1, 1, 0, 0, 2, '', ''),
(970602, 'Dusty corners cleaned', NULL, 0, 1, 1, 35, 0, 1, 1, 1, 33554432, 10, 0, 1, 1, 0, 0, 130, '', ''),
(970603, 'Order ledger found', NULL, 0, 1, 1, 35, 0, 1, 1, 1, 33554432, 10, 0, 1, 1, 0, 0, 130, '', ''),
(970604, 'The ledger\'s last entry read', NULL, 0, 1, 1, 35, 0, 1, 1, 1, 33554432, 10, 0, 1, 1, 0, 0, 130, '', ''),
(970605, 'The wrapped order found', NULL, 0, 1, 1, 35, 0, 1, 1, 1, 33554432, 10, 0, 1, 1, 0, 0, 130, '', ''),
(970606, 'Magistrate Sevren asked about the Balnirs', NULL, 0, 1, 1, 35, 0, 1, 1, 1, 33554432, 10, 0, 1, 1, 0, 0, 130, '', ''),
(970607, 'Signs of the Balnirs\' fate found', NULL, 0, 1, 1, 35, 0, 1, 1, 1, 33554432, 10, 0, 1, 1, 0, 0, 130, '', ''),
(970608, 'The candlesticks settled', NULL, 0, 1, 1, 35, 0, 1, 1, 1, 33554432, 10, 0, 1, 1, 0, 0, 130, '', '');
INSERT INTO `creature_template_model` (`CreatureID`, `Idx`, `CreatureDisplayID`, `DisplayScale`, `Probability`) VALUES
(970600, 0, 2622, 1, 1),
(970601, 0, 2671, 1, 1),
(970602, 0, 11686, 1, 1),
(970603, 0, 11686, 1, 1),
(970604, 0, 11686, 1, 1),
(970605, 0, 11686, 1, 1),
(970606, 0, 11686, 1, 1),
(970607, 0, 11686, 1, 1),
(970608, 0, 11686, 1, 1);
INSERT INTO `creature` (`guid`, `id`, `map`, `zoneId`, `areaId`, `spawnMask`, `phaseMask`, `position_x`, `position_y`, `position_z`, `orientation`, `spawntimesecs`, `wander_distance`, `MovementType`, `Comment`) VALUES
(970600, 970600, 0, 85, 0, 1, 1, 2345.300, 277.900, 34.750, 2.7000, 60, 0, 0, 'mod-brill-home town'),
(970601, 970601, 0, 85, 0, 1, 1, 547.000, 1588.000, 130.120, 0.9000, 60, 0, 0, 'mod-brill-home town');
INSERT INTO `gameobject_template` (`entry`, `type`, `displayId`, `name`, `size`, `Data0`, `Data1`, `ScriptName`) VALUES
(970600, 5, 7872, 'Wicke\'s Stall', 1, 0, 0, ''),
(970601, 5, 7732, 'Shop Counter', 1, 0, 0, ''),
(970602, 2, 159, 'Moth-Eaten Rug', 1, 0, 0, 'go_brill_town'),
(970603, 2, 113, 'Dust-Choked Crate', 1, 0, 0, 'go_brill_town'),
(970604, 2, 187, 'Cobwebbed Shelf', 1, 0, 0, 'go_brill_town'),
(970605, 2, 164, 'Green-Bound Ledger', 1, 0, 0, 'go_brill_town'),
(970606, 2, 4191, 'Paper-Wrapped Parcel', 1, 0, 0, 'go_brill_town'),
(970607, 2, 2491, 'Weathered Notice', 1, 0, 0, 'go_brill_town'),
(970608, 2, 470, 'Scorched Family Book', 1, 0, 0, 'go_brill_town'),
(970609, 2, 2452, 'Balnir Family Stone', 1, 0, 0, 'go_brill_town'),
(970610, 5, 7710, 'Wicke\'s Candles', 1, 0, 0, ''),
(970611, 5, 7711, 'Wicke\'s Candles', 1, 0, 0, ''),
(970612, 5, 6406, 'Skull Candle', 1, 0, 0, ''),
(970613, 5, 5993, 'Basket of Sundries', 1, 0, 0, ''),
(970614, 5, 8966, 'Basket of Ribbons', 1, 0, 0, ''),
(970615, 5, 52, 'Urn, Slightly Used', 1, 0, 0, ''),
(970616, 5, 7600, 'Wedding Basket', 1, 0, 0, ''),
(970617, 5, 7495, 'Vase of Black Roses', 1, 0, 0, ''),
(970618, 5, 107, 'Second-Hand Books', 1, 0, 0, '');
INSERT INTO `gameobject` (`guid`, `id`, `map`, `zoneId`, `areaId`, `spawnMask`, `phaseMask`, `position_x`, `position_y`, `position_z`, `orientation`, `rotation0`, `rotation1`, `rotation2`, `rotation3`, `spawntimesecs`, `animprogress`, `state`, `Comment`) VALUES
(970600, 970600, 0, 85, 0, 1, 1, 2345.000, 278.000, 34.720, 2.7000, 0, 0, 0.975723, 0.219007, 0, 255, 1, 'mod-brill-home town'),
(970601, 970601, 0, 85, 0, 1, 1, 2343.900, 278.500, 34.600, 2.7000, 0, 0, 0.975723, 0.219007, 0, 255, 1, 'mod-brill-home town'),
(970602, 970602, 0, 85, 0, 1, 1, 2342.100, 279.400, 34.430, 2.7000, 0, 0, 0.975723, 0.219007, 0, 255, 1, 'mod-brill-home town'),
(970603, 970603, 0, 85, 0, 1, 1, 2345.800, 280.900, 34.610, 1.2000, 0, 0, 0.564642, 0.825336, 0, 255, 1, 'mod-brill-home town'),
(970604, 970604, 0, 85, 0, 1, 1, 2347.900, 279.500, 34.910, 2.7000, 0, 0, 0.975723, 0.219007, 0, 255, 1, 'mod-brill-home town'),
(970605, 970605, 0, 85, 0, 1, 1, 2343.100, 277.200, 34.570, 2.7000, 0, 0, 0.975723, 0.219007, 0, 255, 1, 'mod-brill-home town'),
(970606, 970606, 0, 85, 0, 1, 1, 2345.600, 275.100, 34.920, 2.7000, 0, 0, 0.975723, 0.219007, 0, 255, 1, 'mod-brill-home town'),
(970607, 970607, 0, 85, 0, 1, 1, 1998.000, -455.000, 34.530, 1.6000, 0, 0, 0.717356, 0.696707, 0, 255, 1, 'mod-brill-home town'),
(970608, 970608, 0, 85, 0, 1, 1, 1948.000, -442.000, 35.450, 0.3000, 0, 0, 0.149438, 0.988771, 0, 255, 1, 'mod-brill-home town'),
(970609, 970609, 0, 85, 0, 1, 1, 2041.000, -517.000, 43.660, 4.7000, 0, 0, 0.711473, -0.702713, 0, 255, 1, 'mod-brill-home town'),
(970610, 970610, 0, 85, 0, 1, 67108864, 2341.600, 276.500, 34.450, 2.7000, 0, 0, 0.975723, 0.219007, 0, 255, 1, 'mod-brill-home town'),
(970611, 970611, 0, 85, 0, 1, 67108864, 2341.200, 276.100, 34.460, 2.2000, 0, 0, 0.891207, 0.453596, 0, 255, 1, 'mod-brill-home town'),
(970612, 970612, 0, 85, 0, 1, 67108864, 2344.200, 281.000, 34.510, 2.7000, 0, 0, 0.975723, 0.219007, 0, 255, 1, 'mod-brill-home town'),
(970613, 970613, 0, 85, 0, 1, 67108864, 2343.500, 281.800, 34.440, 1.9000, 0, 0, 0.813416, 0.581683, 0, 255, 1, 'mod-brill-home town'),
(970614, 970614, 0, 85, 0, 1, 67108864, 2344.500, 282.200, 34.470, 3.0000, 0, 0, 0.997495, 0.070737, 0, 255, 1, 'mod-brill-home town'),
(970615, 970615, 0, 85, 0, 1, 67108864, 2340.500, 276.400, 34.470, 2.7000, 0, 0, 0.975723, 0.219007, 0, 255, 1, 'mod-brill-home town'),
(970616, 970616, 0, 85, 0, 1, 67108864, 2342.200, 281.300, 34.390, 2.4000, 0, 0, 0.932039, 0.362358, 0, 255, 1, 'mod-brill-home town'),
(970617, 970617, 0, 85, 0, 1, 67108864, 2340.500, 278.300, 34.430, 2.7000, 0, 0, 0.975723, 0.219007, 0, 255, 1, 'mod-brill-home town'),
(970618, 970618, 0, 85, 0, 1, 67108864, 2347.400, 280.600, 34.770, 1.0000, 0, 0, 0.479426, 0.877583, 0, 255, 1, 'mod-brill-home town');
INSERT INTO `brill_town_use` (`go`, `ord`, `cond`, `quest`, `credit`, `text_id`, `marker`) VALUES
(970602, 0, 'I970600', 970600, 970602, 970600, 0),
(970602, 1, '', 0, 0, 970601, 0),
(970603, 0, 'I970600', 970600, 970602, 970602, 0),
(970603, 1, '', 0, 0, 970603, 0),
(970604, 0, 'I970600', 970600, 970602, 970604, 0),
(970604, 1, '', 0, 0, 970605, 0),
(970605, 0, 'I970600', 970600, 970603, 970606, 0),
(970605, 1, 'R970604', 0, 0, 970607, 0),
(970605, 2, '', 0, 0, 970608, 0),
(970606, 0, 'I970601', 970601, 970605, 970609, 0),
(970606, 1, 'R970605', 0, 0, 970610, 0),
(970606, 2, 'R970606', 0, 0, 970611, 0),
(970606, 3, '', 0, 0, 970612, 0),
(970607, 0, 'I970602', 970602, 970607, 970613, 0),
(970607, 1, '', 0, 0, 970614, 0),
(970608, 0, 'I970602', 970602, 970607, 970615, 0),
(970608, 1, '', 0, 0, 970616, 0),
(970609, 0, 'I970602', 970602, 970607, 970617, 0),
(970609, 1, '', 0, 0, 970618, 0);
INSERT INTO `brill_town_gossip` (`npc`, `ord`, `kind`, `cond`, `icon`, `text`, `text_id`, `action`, `quest`, `credit`, `marker`, `emote`, `reply`, `arg`) VALUES
(970600, 4000, 'greet', 'R970604 R970605', 0, NULL, 970619, '', 0, 0, 0, 0, NULL, 0),
(970600, 4001, 'greet', 'R970604 R970606', 0, NULL, 970620, '', 0, 0, 0, 0, NULL, 0),
(970600, 4002, 'greet', 'R970604', 0, NULL, 970621, '', 0, 0, 0, 0, NULL, 0),
(970600, 4003, 'greet', 'A970604', 0, NULL, 970622, '', 0, 0, 0, 0, NULL, 0),
(970600, 4004, 'greet', 'T970603', 0, NULL, 970623, '', 0, 0, 0, 0, NULL, 0),
(970600, 4005, 'greet', 'T970602', 0, NULL, 970624, '', 0, 0, 0, 0, NULL, 0),
(970600, 4006, 'greet', 'T970600', 0, NULL, 970625, '', 0, 0, 0, 0, NULL, 0),
(970600, 4007, 'greet', '', 0, NULL, 970626, '', 0, 0, 0, 0, NULL, 0),
(970600, 4008, 'option', 'I970601', 0, 'Read the ledger\'s last entry with him.', 0, 'credit', 970601, 970604, 0, 1, '"Balnir, T., of the Balnir Farmstead. One pair candlesticks, silvered, vine pattern, engraved. For his wedding. Paid in full, in coin and two seasons\' beeswax." And the date, $n. Read me the date. ...Eleven years? No. No, that\'s the ink. It runs.', 0),
(970600, 4009, 'option', 'I970604', 0, 'Light them, and keep them in your window. For the wedding.', 0, 'choice', 970604, 970608, 970605, 2, 'In the window. Yes. Yes, that\'s proper. Folk will ask who they\'re for, and I\'ll say: a customer. Paid in full. Not for sale.', 0),
(970600, 4010, 'option', 'I970604', 0, 'Sell them to the next couple who\'ll have them. Someone should be married by them.', 0, 'choice', 970604, 970608, 970606, 273, 'Sell them. To a wedding. Hah! In Brill! I may be waiting another eleven years, $n. But I\'ll wait with the shutters open, this time.', 0),
(970600, 4011, 'option', 'R970604', 1, 'Let me see what you\'re selling.', 0, 'vendor', 0, 0, 0, 0, NULL, 0),
(970600, 4012, 'option', 'R970604 O', 0, 'Anything for the house by the road?', 0, 'say', 0, 0, 0, 1, 'The old Harrow place? Candles, $n. A house is only a house until someone lights a candle in it. After that it\'s a home, and after that, in my experience, it\'s a fire risk. I sell buckets too.', 0),
(970600, 4013, 'option', 'R970604 R970205', 0, 'I had a housewarming, you know.', 0, 'say', 0, 0, 0, 6, 'And nobody told me? I\'d have brought a candle. Two! Well. Next time someone in Brill dies and gets up again, we\'ll call it a housewarming and I\'ll bring three.', 0),
(1499, 4014, 'option', 'I970601', 0, 'Do the town\'s books say what became of the Balnir family?', 0, 'credit', 970601, 970606, 0, 1, 'Balnir. The farm south-east, past the crossroads. The Crown quarantined it the first autumn of the plague: the grain. My predecessor\'s hand. No departures recorded, $n, and nobody living\'s been there since. If Wicke\'s sending you out there, tell him the Deathguard won\'t fetch you back.', 0),
(970601, 4015, 'greet', 'R970604', 0, NULL, 970627, '', 0, 0, 0, 0, NULL, 0),
(970601, 4016, 'greet', 'R970603', 0, NULL, 970628, '', 0, 0, 0, 0, NULL, 0),
(970601, 4017, 'greet', '', 0, NULL, 970629, '', 0, 0, 0, 0, NULL, 0),
(970601, 4018, 'option', 'R970603', 0, 'Tell me about the wedding that never happened.', 0, 'say', 0, 0, 0, 1, 'Harvest, the twelfth. Marian had a dress her mother wore, let out twice. Father had bought a pig. I was to come home from Silverpine the week before. I didn\'t: the roads were closed. I had a letter from Tobias saying the grain had come in plentiful, and they\'d have bread enough for half of Brill. That was the last letter. I\'ve read it so often I don\'t need it any more.', 0);
UPDATE `creature_template` SET `npcflag` = `npcflag` | 1 WHERE `entry` IN (1499);
INSERT INTO `brill_town_phase` (`bit`, `ord`, `cond`) VALUES
(67108864, 0, 'R970604');
INSERT INTO `quest_template` (`ID`, `QuestType`, `QuestLevel`, `MinLevel`, `QuestSortID`, `Flags`, `RewardXPDifficulty`, `RewardMoney`, `AllowableRaces`, `LogTitle`, `LogDescription`, `QuestDescription`, `AreaDescription`, `QuestCompletionLog`, `RequiredNpcOrGo1`, `RequiredNpcOrGoCount1`, `ObjectiveText1`, `RequiredNpcOrGo2`, `RequiredNpcOrGoCount2`, `ObjectiveText2`, `RequiredNpcOrGo3`, `RequiredNpcOrGoCount3`, `ObjectiveText3`, `RequiredNpcOrGo4`, `RequiredNpcOrGoCount4`, `ObjectiveText4`, `RewardItem1`, `RewardAmount1`, `RewardItem2`, `RewardAmount2`, `RewardItem3`, `RewardAmount3`, `RewardItem4`, `RewardAmount4`) VALUES
(970600, 2, -1, 5, 159, 0, 2, 600, 0, 'Business as Usual', 'Clean the dust from Ambrose Wicke\'s stall behind Brill\'s town hall and find his old order ledger.', 'Closed. Can\'t you read the sign? Oh. The sign fell down. Years ago, I expect.$B$BAmbrose Wicke, candles, lamps and sundries, established in Brill before most of Brill was buried. I don\'t sell, $g lad:lass;. Not until an order is collected. A customer paid in full, and a shop that sells his goods out from under him is a shop that cheats. My father said that, and his father before him, and they were both a great deal warmer than I am now.$B$BBut the dust. The dust I will admit to. I woke behind this counter after the plague and I have been standing at it since, and the moths have had the run of the place. Shake out the rug, clear the crate and the shelf, and look under the counter for my order ledger. Green binding. It has his name in it. I\'d look myself, but I\'m keeping shop.', '', 'Return to Ambrose Wicke in Brill.', 970602, 3, 'Dusty corners cleaned', 970603, 1, 'Order ledger found', 0, 0, '', 0, 0, '', 0, 0, 0, 0, 0, 0, 0, 0),
(970601, 2, -1, 5, 159, 0, 3, 800, 0, 'An Outstanding Order', 'Read the ledger\'s last entry with Ambrose Wicke, find the order wrapped at the back of his stall, and ask Magistrate Sevren in Brill\'s town hall about the customer\'s family.', 'Now that you\'ve found it, you may as well read it. My eyes aren\'t what they were, chiefly because one of them isn\'t anywhere.$B$BThe last entry. Read it with me. He\'ll be here any day: he said so when he paid. Then look at the back of the stall, behind the shelf. His order is wrapped in brown paper and string, the way it\'s been waiting.$B$BAnd ask the Magistrate about the family. Sevren keeps the town\'s books now, such as they are. If the young man\'s moved house, the Magistrate will know where. People move. I did. Into the ground and out again, but it counts.', '', 'Return to Ambrose Wicke in Brill.', 970604, 1, 'The ledger\'s last entry read', 970605, 1, 'The wrapped order found', 970606, 1, 'Magistrate Sevren asked about the Balnirs', 0, 0, '', 0, 0, 0, 0, 0, 0, 0, 0),
(970602, 2, -1, 9, 159, 0, 4, 1500, 0, 'No Forwarding Address', 'Search the Balnir Farmstead, south-east of Brill, for what became of the Balnir family, then return to Ambrose Wicke.', 'The Balnir Farmstead. South and east of here, past the crossroads, where the road bends toward the old Crusade tower. A good farm, once: Tobias\'s father grew snapdragons for the market, of all things, and brought me the wax from his bees.$B$BGo and see. There will be something. A notice on the gate, a family book in the house, the stones in the family plot; farmers write things down, and they bury their people where they can see them from the kitchen.$B$BMind the farm. The Magistrate says nothing living goes there, and from his face I gather nothing dead goes there gladly either. Find out where Tobias went, $n. I don\'t ask you to bring him. Only to tell me where to send word.', '', 'Return to Ambrose Wicke in Brill.', 970607, 3, 'Signs of the Balnirs\' fate found', 0, 0, '', 0, 0, '', 0, 0, '', 858, 2, 0, 0, 0, 0, 0, 0),
(970603, 2, -1, 9, 159, 0, 3, 1000, 0, 'The Gift Never Given', 'Find Agnes Balnir at the Sepulcher in Silverpine Forest.', 'Silverpine. A great many of us who woke there went no further than the Sepulcher: the Dark Lady\'s people keep it, and it\'s the nearest thing to a town those woods have left. If Agnes Balnir is anywhere, she\'s there, or under it.$B$BGo and find her. Don\'t tell her about the candlesticks straight off. Tell her Ambrose Wicke of Brill is holding an order in her brother\'s name, paid in full, and wishes to know what she would like done with it. That\'s the proper form.$B$BAnd $n... if she doesn\'t remember him, don\'t press her. Some of us came back with the whole of our lives. Some came back with a name and a smell of smoke.', '', 'Return to Agnes Balnir at the Sepulcher.', 0, 0, '', 0, 0, '', 0, 0, '', 0, 0, '', 0, 0, 0, 0, 0, 0, 0, 0),
(970604, 2, -1, 9, 159, 0, 4, 2500, 0, 'Closing the Ledger', 'Take Agnes Balnir\'s letter to Ambrose Wicke in Brill and decide what becomes of the candlesticks.', 'I\'ve written to him. It\'s short. I was never one for letters; Tobias wrote them for both of us.$B$BI can\'t say what\'s to be done with them, $n. If I say keep them, I\'m keeping a wedding that never came. If I say sell them, I\'m selling my brother\'s last good idea. So I\'ve told old Wicke the order is yours to settle. You found the farm. You saw the grave. You choose.$B$BTell him one more thing for me, would you? Tell him Tobias isn\'t coming. Not because he doesn\'t care. Whatever climbed out of that grave wasn\'t my brother, and it didn\'t come to me, or to Marian, and it won\'t come to a candle shop. Tell him to open the shutters.', '', 'Return to Ambrose Wicke in Brill.', 970608, 1, 'The candlesticks settled', 0, 0, '', 0, 0, '', 0, 0, '', 7338, 1, 0, 0, 0, 0, 0, 0),
(970605, 2, 1, 1, -1, 0, 0, 0, 0, '[lit]', '', '', '', '', 0, 0, '', 0, 0, '', 0, 0, '', 0, 0, '', 0, 0, 0, 0, 0, 0, 0, 0),
(970606, 2, 1, 1, -1, 0, 0, 0, 0, '[sold]', '', '', '', '', 0, 0, '', 0, 0, '', 0, 0, '', 0, 0, '', 0, 0, 0, 0, 0, 0, 0, 0);
INSERT INTO `quest_template_addon` (`ID`, `PrevQuestID`, `SpecialFlags`) VALUES (970600, 0, 0), (970601, 970600, 0), (970602, 970601, 0), (970603, 970602, 0), (970604, 970603, 0);
INSERT INTO `quest_offer_reward` (`ID`, `Emote1`, `RewardText`) VALUES
(970600, 1, 'There it is. The binding\'s gone grey, but then so have I.$B$BAnd the stall looks very nearly like a place someone might buy something. Not that they may. Take this for your trouble: it\'s payment, not a sale, and I\'ll thank you not to confuse the two.'),
(970601, 1, 'Quarantined. By the Crown.$B$BI remember the soldiers on the road. I remember thinking it was a great deal of fuss over grain.$B$BAnd the date. Eleven years. I\'d have sworn it was last spring. You lose the count, standing still; the days don\'t come in and buy anything, so you don\'t notice them. Still. A farm doesn\'t move, $n. Someone there will know where he went.'),
(970602, 1, 'Marian first, then Tobias, then the old man. And the grave open.$B$B...$B$BI sold Osric Balnir his wife\'s funeral candles, you know. Plain ones. He wouldn\'t take the good ones on credit, though I offered.$B$BBut the girl. Agnes. Her name isn\'t crossed out. In service in Silverpine, the book says. If she lived, or if she did what I did and didn\'t stay dead, she is the order\'s next of kin.'),
(970603, 1, 'Ambrose Wicke? Old Wicke, with the candles? He\'s still... well. Aren\'t we all, still.$B$BTobias\'s candlesticks. He showed me the drawing. Silver, with a vine going up them, and their names on the base and the date. Harvest, the twelfth. Four years they waited for the levies to come down so the wedding could be paid for, and when they finally set the day it was the week the grain came.$B$BI remember it. I remember all of it. That\'s the trouble with me.'),
(970604, 1, '"The order is settled. Signed, A. Balnir, next of kin."$B$BWell. That\'s that, then.$B$BEleven years I\'ve kept this stall shut for a young man who was in the ground before I was. And I\'d have kept it shut eleven more, $n, because while the order was open I was still a shopkeeper with a customer coming, and not just a dead man standing behind a counter.$B$BHelp me with the shutters. We\'re open. Take this from stock, on the house: it\'s been waiting for someone too.');
INSERT INTO `quest_request_items` (`ID`, `EmoteOnComplete`, `EmoteOnIncomplete`, `CompletionText`) VALUES
(970600, 1, 1, 'The ledger, $n. Green binding. Under the counter. I\'d know it by the smell if I still had one.'),
(970601, 1, 1, 'Has the Magistrate said where he\'s gone? People don\'t just stop coming for their orders.'),
(970602, 1, 1, 'Well? Is there a forwarding address? Even the dead leave forwarding addresses. I left one. It\'s here.'),
(970604, 1, 1, 'You\'ve given him the letter? What did he say?');
INSERT INTO `creature_queststarter` (`id`, `quest`) VALUES (970600, 970600), (970600, 970601), (970600, 970602), (970600, 970603), (970601, 970604);
INSERT INTO `creature_questender` (`id`, `quest`) VALUES (970600, 970600), (970600, 970601), (970600, 970602), (970601, 970603), (970600, 970604);

-- A Deathguard's Duty
INSERT INTO `creature_template` (`entry`, `name`, `subname`, `gossip_menu_id`, `minlevel`, `maxlevel`, `faction`, `npcflag`, `speed_walk`, `speed_run`, `unit_class`, `unit_flags`, `type`, `rank`, `HealthModifier`, `DamageModifier`, `mingold`, `maxgold`, `flags_extra`, `AIName`, `ScriptName`) VALUES
(970700, 'Deathguard Harlan', NULL, 0, 12, 12, 68, 3, 1, 1.14286, 1, 0, 6, 0, 1, 1, 0, 0, 2, '', ''),
(970701, 'Deathguard Harlan', NULL, 0, 12, 12, 68, 1, 1, 1.14286, 1, 0, 6, 0, 1, 1, 0, 0, 2, '', ''),
(970702, 'Deathguard Harlan', NULL, 0, 12, 12, 68, 1, 1, 1.14286, 1, 0, 6, 0, 1, 1, 0, 0, 2, '', ''),
(970703, 'Maren Ashby', NULL, 0, 5, 5, 35, 1, 1, 1.14286, 1, 0, 7, 0, 1, 1, 0, 0, 2, '', ''),
(970704, 'Lise Ashby', NULL, 0, 1, 1, 35, 1, 1, 1.14286, 1, 0, 7, 0, 1, 1, 0, 0, 2, '', ''),
(970705, 'Roadside Ghoul', NULL, 0, 9, 10, 21, 0, 1, 1.14286, 1, 0, 6, 0, 1, 1, 0, 0, 0, '', ''),
(970706, 'Gnawbone', NULL, 0, 11, 11, 21, 0, 1, 1.14286, 1, 0, 6, 0, 2.5, 1.3, 120, 120, 0, '', ''),
(970707, 'Supplies delivered to Deathguard Abraham', NULL, 0, 1, 1, 35, 0, 1, 1, 1, 33554432, 10, 0, 1, 1, 0, 0, 130, '', ''),
(970708, 'Supplies delivered to Deathguard Terrence', NULL, 0, 1, 1, 35, 0, 1, 1, 1, 33554432, 10, 0, 1, 1, 0, 0, 130, '', ''),
(970709, 'Supplies delivered to Deathguard Linnea', NULL, 0, 1, 1, 35, 0, 1, 1, 1, 33554432, 10, 0, 1, 1, 0, 0, 130, '', ''),
(970710, 'Patrol brazier stoked', NULL, 0, 1, 1, 35, 0, 1, 1, 1, 33554432, 10, 0, 1, 1, 0, 0, 130, '', ''),
(970711, 'Wrecked cart searched', NULL, 0, 1, 1, 35, 0, 1, 1, 1, 33554432, 10, 0, 1, 1, 0, 0, 130, '', ''),
(970712, 'Deathguard Lawrence asked about Harlan', NULL, 0, 1, 1, 35, 0, 1, 1, 1, 33554432, 10, 0, 1, 1, 0, 0, 130, '', ''),
(970713, 'Deathguard Bartholomew asked about Harlan', NULL, 0, 1, 1, 35, 0, 1, 1, 1, 33554432, 10, 0, 1, 1, 0, 0, 130, '', ''),
(970714, 'Harlan\'s tracks followed', NULL, 0, 1, 1, 35, 0, 1, 1, 1, 33554432, 10, 0, 1, 1, 0, 0, 130, '', ''),
(970715, 'Spoke with Maren Ashby', NULL, 0, 1, 1, 35, 0, 1, 1, 1, 33554432, 10, 0, 1, 1, 0, 0, 130, '', ''),
(970716, 'Spoke with Lise Ashby', NULL, 0, 1, 1, 35, 0, 1, 1, 1, 33554432, 10, 0, 1, 1, 0, 0, 130, '', ''),
(970717, 'Firewood at the gate found', NULL, 0, 1, 1, 35, 0, 1, 1, 1, 33554432, 10, 0, 1, 1, 0, 0, 130, '', ''),
(970718, 'Deathguard Harlan\'s choice made', NULL, 0, 1, 1, 35, 0, 1, 1, 1, 33554432, 10, 0, 1, 1, 0, 0, 130, '', '');
INSERT INTO `creature_template_model` (`CreatureID`, `Idx`, `CreatureDisplayID`, `DisplayScale`, `Probability`) VALUES
(970700, 0, 2862, 1, 1),
(970701, 0, 2862, 1, 1),
(970702, 0, 2862, 1, 1),
(970703, 0, 18618, 1, 1),
(970704, 0, 252, 1, 1),
(970705, 0, 829, 1, 1),
(970706, 0, 1202, 1.25, 1),
(970707, 0, 11686, 1, 1),
(970708, 0, 11686, 1, 1),
(970709, 0, 11686, 1, 1),
(970710, 0, 11686, 1, 1),
(970711, 0, 11686, 1, 1),
(970712, 0, 11686, 1, 1),
(970713, 0, 11686, 1, 1),
(970714, 0, 11686, 1, 1),
(970715, 0, 11686, 1, 1),
(970716, 0, 11686, 1, 1),
(970717, 0, 11686, 1, 1),
(970718, 0, 11686, 1, 1);
INSERT INTO `creature` (`guid`, `id`, `map`, `zoneId`, `areaId`, `spawnMask`, `phaseMask`, `position_x`, `position_y`, `position_z`, `orientation`, `spawntimesecs`, `wander_distance`, `MovementType`, `Comment`) VALUES
(970700, 970700, 0, 85, 0, 1, 1, 2285.000, 1235.000, 38.670, 1.8000, 60, 0, 0, 'mod-brill-home town'),
(970701, 970701, 0, 85, 0, 1, 134217728, 2262.000, 428.000, 35.390, 0.0000, 60, 0, 0, 'mod-brill-home town'),
(970702, 970702, 0, 85, 0, 1, 268435456, 2345.000, 385.000, 34.260, 4.7100, 60, 0, 0, 'mod-brill-home town'),
(970703, 970703, 0, 85, 0, 1, 1, 2262.000, 1292.000, 31.900, 4.6000, 60, 0, 0, 'mod-brill-home town'),
(970704, 970704, 0, 85, 0, 1, 1, 2266.000, 1288.000, 31.420, 3.0000, 60, 3, 1, 'mod-brill-home town'),
(970705, 970705, 0, 85, 0, 1, 1, 2262.000, 512.000, 34.500, 1.0000, 120, 5, 1, 'mod-brill-home town'),
(970706, 970705, 0, 85, 0, 1, 1, 2276.000, 522.000, 35.420, 3.0000, 120, 5, 1, 'mod-brill-home town'),
(970707, 970705, 0, 85, 0, 1, 1, 2258.000, 535.000, 33.810, 4.5000, 120, 5, 1, 'mod-brill-home town'),
(970708, 970705, 0, 85, 0, 1, 1, 2270.000, 505.000, 34.840, 0.5000, 120, 5, 1, 'mod-brill-home town'),
(970709, 970706, 0, 85, 0, 1, 1, 2268.000, 552.000, 33.770, 3.6000, 180, 0, 0, 'mod-brill-home town');
INSERT INTO `gameobject_template` (`entry`, `type`, `displayId`, `name`, `size`, `Data0`, `Data1`, `ScriptName`) VALUES
(970700, 2, 197, 'Patrol Brazier', 0.8, 0, 0, 'go_brill_town'),
(970701, 2, 6961, 'Wrecked Cart', 0.9, 0, 0, 'go_brill_town'),
(970702, 2, 498, 'Bootprints', 0.5, 0, 0, 'go_brill_town'),
(970703, 2, 1248, 'Bundle of Firewood', 0.6, 0, 0, 'go_brill_town');
INSERT INTO `gameobject` (`guid`, `id`, `map`, `zoneId`, `areaId`, `spawnMask`, `phaseMask`, `position_x`, `position_y`, `position_z`, `orientation`, `rotation0`, `rotation1`, `rotation2`, `rotation3`, `spawntimesecs`, `animprogress`, `state`, `Comment`) VALUES
(970700, 970700, 0, 85, 0, 1, 1, 2262.000, 470.000, 33.880, 0.0000, 0, 0, 0.000000, 1.000000, 0, 255, 1, 'mod-brill-home town'),
(970701, 970700, 0, 85, 0, 1, 1, 2168.000, 203.000, 42.890, 0.0000, 0, 0, 0.000000, 1.000000, 0, 255, 1, 'mod-brill-home town'),
(970702, 970700, 0, 85, 0, 1, 1, 2445.000, 288.000, 28.120, 0.0000, 0, 0, 0.000000, 1.000000, 0, 255, 1, 'mod-brill-home town'),
(970703, 970701, 0, 85, 0, 1, 1, 2255.000, 490.000, 33.950, 1.3000, 0, 0, 0.605186, 0.796084, 0, 255, 1, 'mod-brill-home town'),
(970704, 970701, 0, 85, 0, 1, 1, 2274.000, 545.000, 33.780, 4.0000, 0, 0, 0.909297, -0.416147, 0, 255, 1, 'mod-brill-home town'),
(970705, 970701, 0, 85, 0, 1, 1, 2252.000, 565.000, 33.450, 2.2000, 0, 0, 0.891207, 0.453596, 0, 255, 1, 'mod-brill-home town'),
(970706, 970702, 0, 85, 0, 1, 1, 2208.000, 690.000, 34.900, 0.0000, 0, 0, 0.000000, 1.000000, 0, 255, 1, 'mod-brill-home town'),
(970707, 970702, 0, 85, 0, 1, 1, 2230.000, 900.000, 48.000, 0.0000, 0, 0, 0.000000, 1.000000, 0, 255, 1, 'mod-brill-home town'),
(970708, 970702, 0, 85, 0, 1, 1, 2250.000, 1150.000, 34.130, 0.0000, 0, 0, 0.000000, 1.000000, 0, 255, 1, 'mod-brill-home town'),
(970709, 970703, 0, 85, 0, 1, 1, 2257.000, 1287.000, 32.110, 1.0000, 0, 0, 0.479426, 0.877583, 0, 255, 1, 'mod-brill-home town');
INSERT INTO `brill_town_use` (`go`, `ord`, `cond`, `quest`, `credit`, `text_id`, `marker`) VALUES
(970700, 0, 'I970700', 970700, 970710, 970700, 0),
(970700, 1, '', 0, 0, 970701, 0),
(970701, 0, 'I970701', 970701, 970711, 970702, 0),
(970701, 1, '', 0, 0, 970703, 0),
(970702, 0, 'I970702', 970702, 970714, 970704, 0),
(970702, 1, '', 0, 0, 970705, 0),
(970703, 0, 'I970703', 970703, 970717, 970706, 0),
(970703, 1, 'R970706', 0, 0, 970707, 0),
(970703, 2, '', 0, 0, 970708, 0);
INSERT INTO `brill_town_gossip` (`npc`, `ord`, `kind`, `cond`, `icon`, `text`, `text_id`, `action`, `quest`, `credit`, `marker`, `emote`, `reply`, `arg`) VALUES
(1496, 5000, 'greet', 'R970704 R970705 D2', 0, NULL, 970709, '', 0, 0, 0, 0, NULL, 0),
(1496, 5001, 'greet', 'R970704 R970706 D2', 0, NULL, 970710, '', 0, 0, 0, 0, NULL, 0),
(1496, 5002, 'greet', 'R970704 D3', 0, NULL, 970711, '', 0, 0, 0, 0, NULL, 0),
(1496, 5003, 'greet', 'A970700 F2', 0, NULL, 970712, '', 0, 0, 0, 0, NULL, 0),
(1496, 5004, 'greet', 'T970702 !R970704', 0, NULL, 970713, '', 0, 0, 0, 0, NULL, 0),
(1496, 5005, 'greet', 'R970505 !T970700', 0, NULL, 970714, '', 0, 0, 0, 0, NULL, 0),
(1496, 5006, 'option', 'F2 A970701', 0, '(Let your mastiff sniff the cart\'s scent from your gloves.)', 0, 'say', 0, 0, 0, 0, 'Ha. It\'s got the stink of ghoul in its nose now; it\'ll growl before you see them. Keep it close on the north road.', 0),
(1735, 5007, 'option', 'I970700', 0, 'Dillinger sent supplies for your post.', 0, 'credit', 970700, 970707, 0, 0, 'Oil! And a whetstone. (Abraham turns them over in his hands.) Thank Dillinger for me. And tell him the north road\'s been bad at night. Whoever\'s meant to be patrolling between here and Brill, isn\'t.', 0),
(1738, 5008, 'option', 'I970700', 0, 'Supplies from Dillinger, Terrence.', 0, 'credit', 970700, 970708, 0, 0, '(Terrence takes the pack without breaking stride, checks it, and grunts.)', 0),
(1495, 5009, 'option', 'I970700', 0, 'Dillinger sends whetstones and oil.', 0, 'credit', 970700, 970709, 0, 0, 'Whetstones. I have been asking for whetstones since before the Dark Lady was a lady. (She tests one with a thumb.) Tell Dillinger he\'s forgiven. Mostly.', 0),
(1743, 5010, 'option', 'I970702', 0, 'What do you know about Harlan\'s absences?', 0, 'credit', 970702, 970712, 0, 0, 'Harlan? Steady as a gravestone. Never talks about before. (Lawrence hesitates.) Only once, the night the farmers at Solliden lit a harvest fire you could see from here. He stared at it until dawn. Said nothing. Next week, he started going missing.', 0),
(1742, 5011, 'option', 'I970702', 0, 'Where does Harlan go when he leaves his post?', 0, 'credit', 970702, 970713, 0, 0, 'West. Always west, over the river. (Bartholomew lowers his voice.) He takes firewood with him. Splits it himself behind the stables. What does a dead man want with firewood, I ask you?', 0),
(970700, 5012, 'greet', 'R970705', 0, NULL, 970715, '', 0, 0, 0, 0, NULL, 0),
(970700, 5013, 'greet', 'R970706', 0, NULL, 970716, '', 0, 0, 0, 0, NULL, 0),
(970700, 5014, 'greet', 'A970703 F2', 0, NULL, 970717, '', 0, 0, 0, 0, NULL, 0),
(970700, 5015, 'greet', 'T970702', 0, NULL, 970718, '', 0, 0, 0, 0, NULL, 0),
(970700, 5016, 'greet', '', 0, NULL, 970719, '', 0, 0, 0, 0, NULL, 0),
(970700, 5017, 'option', 'I970704', 0, 'Your post is waiting, Harlan. Brill needs you on that road.', 0, 'choice', 970704, 970718, 970705, 273, 'The road it is. (Harlan squares his shoulders.) It runs this way, at least. I\'ll keep it clean for them, and once a week I\'ll come and stand in the trees. Tell Dillinger I\'ll be at my post before the bell.', 0),
(970700, 5018, 'option', 'I970704', 0, 'Ask Dillinger for another post. Let them go, Harlan.', 0, 'choice', 970704, 970718, 970706, 2, 'Let them go. (He says it as if testing the weight of it.) They let me go seven years ago. It\'s only fair. (Harlan takes a last look at the window.) Tell Dillinger I\'ll take the cemetery. The dead don\'t need firewood.', 0),
(970703, 5019, 'greet', 'R970704 R970706', 0, NULL, 970720, '', 0, 0, 0, 0, NULL, 0),
(970703, 5020, 'greet', 'R970704 R970705', 0, NULL, 970721, '', 0, 0, 0, 0, NULL, 0),
(970703, 5021, 'greet', '', 0, NULL, 970722, '', 0, 0, 0, 0, NULL, 0),
(970703, 5022, 'option', 'I970703', 0, 'I\'m only passing. You keep a fine garden.', 0, 'credit', 970703, 970715, 0, 0, '(She studies you a long moment, then lets her hand fall.) It was my husband\'s garden. He died in the plague; everyone did, nearly. Lise and I stayed. People in Brill used to be people, you know. (Her voice softens.) Someone leaves wood at the gate. Every week. I never see who. Good evening.', 0),
(970704, 5023, 'greet', '', 0, NULL, 970723, '', 0, 0, 0, 0, NULL, 0),
(970704, 5024, 'option', 'I970703', 0, '(Ask the girl her name.)', 0, 'credit', 970703, 970716, 0, 0, 'Lise. Lise Ashby. (She tilts her head.) My papa was a farmhand here. I don\'t remember him much. He used to carry me on his shoulders to see the lamps in Brill. Mama says he\'d want me to be brave. Are you brave?', 0),
(970704, 5025, 'option', 'F2 A970703', 0, '(Let her pat your mastiff.)', 0, 'say', 0, 0, 0, 4, '(Lise squeals and throws her arms around the mastiff\'s neck.) We had a dog! His name was Pudding! He was bigger than this one. Well. I was smaller.', 0),
(970701, 5026, 'greet', 'D3', 0, NULL, 970724, '', 0, 0, 0, 0, NULL, 0),
(970701, 5027, 'greet', '', 0, NULL, 970725, '', 0, 0, 0, 0, NULL, 0),
(970702, 5028, 'greet', 'D3', 0, NULL, 970726, '', 0, 0, 0, 0, NULL, 0),
(970702, 5029, 'greet', '', 0, NULL, 970727, '', 0, 0, 0, 0, NULL, 0);
UPDATE `creature_template` SET `npcflag` = `npcflag` | 1 WHERE `entry` IN (1495, 1496, 1735, 1738, 1742, 1743);
INSERT INTO `brill_town_phase` (`bit`, `ord`, `cond`) VALUES
(134217728, 0, 'R970705'),
(268435456, 0, 'R970706');
INSERT INTO `quest_template` (`ID`, `QuestType`, `QuestLevel`, `MinLevel`, `QuestSortID`, `Flags`, `RewardXPDifficulty`, `RewardMoney`, `AllowableRaces`, `LogTitle`, `LogDescription`, `QuestDescription`, `AreaDescription`, `QuestCompletionLog`, `RequiredNpcOrGo1`, `RequiredNpcOrGoCount1`, `ObjectiveText1`, `RequiredNpcOrGo2`, `RequiredNpcOrGoCount2`, `ObjectiveText2`, `RequiredNpcOrGo3`, `RequiredNpcOrGoCount3`, `ObjectiveText3`, `RequiredNpcOrGo4`, `RequiredNpcOrGoCount4`, `ObjectiveText4`, `RewardItem1`, `RewardAmount1`, `RewardItem2`, `RewardAmount2`, `RewardItem3`, `RewardAmount3`, `RewardItem4`, `RewardAmount4`) VALUES
(970700, 2, -1, 5, 159, 0, 4, 1800, 0, 'The Night Watch', 'Deliver supplies to Deathguard Abraham on the north road, Deathguard Terrence on the Undercity road and Deathguard Linnea at the crossroads, and stoke the 3 patrol braziers on Brill\'s roads. Then return to Deathguard Dillinger.', 'You there. You\'ve got legs and you\'re not wearing a tabard, which makes you more use to me tonight than half the watch.$B$BBrill\'s Deathguard is stretched thinner than a ghoul\'s patience. The Executor sends the best of us to the Sepulcher and the Bulwark, the Crusade takes the unlucky ones, and the ones left are standing double watches. We don\'t tire, they say. Whoever says that hasn\'t stood eighteen hours on the same road.$B$BTake these packs to our posts outside town: Abraham at the river on the north road, Terrence on the Undercity road, Linnea at the crossroads. Oil, bandages, whetstones, a few letters. And while you\'re out there, stoke the patrol braziers on each road. A dark brazier tells the Scourge nobody\'s watching.', '', 'Return to Deathguard Dillinger on Brill\'s north road.', 970707, 1, 'Supplies delivered to Deathguard Abraham', 970708, 1, 'Supplies delivered to Deathguard Terrence', 970709, 1, 'Supplies delivered to Deathguard Linnea', 970710, 3, 'Patrol brazier stoked', 858, 2, 0, 0, 0, 0, 0, 0),
(970701, 2, -1, 5, 159, 0, 4, 2500, 0, 'Trouble on the Road', 'Search the 3 wrecked carts on the north road between Brill and the river, and kill 4 Roadside Ghouls and Gnawbone. Then return to Deathguard Dillinger.', 'Abraham sent word back with you, did you notice? No, of course not; you were busy being thanked.$B$BTravelers on the north road have been set upon between here and the river. A tinker from Deathknell, two apothecaries\' runners, a cart of Agamand grain. Something drags them off the road at night and leaves the carts. Scourge stragglers, by the sound of it: ghouls, and one big enough that the survivors gave it a name.$B$BThat stretch is meant to be watched. It\'s Harlan\'s patrol. (Dillinger\'s jaw tightens.) It was Harlan\'s patrol. Go and look at the carts, find out what we\'re dealing with, and put it down.', '', 'Return to Deathguard Dillinger on Brill\'s north road.', 970711, 3, 'Wrecked cart searched', 970705, 4, '', 970706, 1, '', 0, 0, '', 2455, 2, 0, 0, 0, 0, 0, 0),
(970702, 2, -1, 5, 159, 0, 4, 2000, 0, 'An Empty Post', 'Ask Deathguard Lawrence and Deathguard Bartholomew about Harlan, follow his tracks west from the river, and find Deathguard Harlan.', 'Deserting a post is a hanging matter in the Deathguard. I don\'t want to hang Harlan. He\'s a good guard; he was a good farmhand before that, by all accounts, and he never once complained about either. But the Executor will notice soon, and when he does it won\'t be my decision anymore.$B$BLawrence and Bartholomew stood watch beside him longest. Ask them what they\'ve seen. Then go and find him before anyone else does. Last time he went missing someone saw tracks heading west from the river, toward the old farms.$B$BBring him back if you can. If you can\'t, at least tell me why.', '', 'Find Deathguard Harlan.', 970712, 1, 'Deathguard Lawrence asked about Harlan', 970713, 1, 'Deathguard Bartholomew asked about Harlan', 970714, 3, 'Harlan\'s tracks followed', 0, 0, '', 0, 0, 0, 0, 0, 0, 0, 0),
(970703, 2, -1, 5, 159, 0, 4, 1500, 0, 'The House Beyond the Trees', 'Go down to the cottage at Solliden Farmstead for Deathguard Harlan: speak with Maren and Lise Ashby, and see what he has been leaving at their gate. Then return to Harlan on the hill.', 'I come here every night I can steal. I stand in these trees and I watch the lamp in the window until it goes out. I tell myself it\'s to keep the ghouls away from them. Mostly it is.$B$BI can\'t go down there. Look at me. Maren would scream, and she\'d be right to; Lise would remember me like this instead of how I was. The farmers down there would put a pitchfork through me, and I\'d let them.$B$BBut you could go. You\'re a stranger. You could pass the gate and say good evening and look at them properly, which I haven\'t done in seven years. Tell me if Lise is well. Tell me if Maren still smiles at people. And see if... see if the wood\'s still there, by the gate. I\'d like to know if they use it.', '', 'Return to Deathguard Harlan on the hill above Solliden Farmstead.', 970715, 1, 'Spoke with Maren Ashby', 970716, 1, 'Spoke with Lise Ashby', 970717, 1, 'Firewood at the gate found', 0, 0, '', 858, 1, 0, 0, 0, 0, 0, 0),
(970704, 2, -1, 5, 159, 0, 5, 3500, 0, 'Duty and Memory', 'Help Deathguard Harlan decide: return to his post on the north road, or ask for a post away from his old home. Then return to Deathguard Dillinger.', 'There are two ways this goes. I\'ve had seven years to think them through, and I still can\'t pick one on my own.$B$BI go back to my post on the north road. I stand my watch, every hour of it. It\'s the road that runs toward this place; I\'d keep the ghouls off it and off them, and on my free days... I\'d come here, and stand in the trees, and go home again before the bell. Once a week. No more.$B$BOr I ask Dillinger for a post on the other side of Brill. The cemetery, say. Facing east, my back to all this. I\'d never see the lamp in that window again. Maybe that\'s what lets a man rest.$B$BYou\'ve seen them now. You\'ve seen me. Tell me which, $n. I\'ll do it.', '', 'Return to Deathguard Dillinger on Brill\'s north road.', 970718, 1, 'Deathguard Harlan\'s choice made', 0, 0, '', 0, 0, '', 0, 0, '', 858, 3, 0, 0, 0, 0, 0, 0),
(970705, 2, 1, 1, -1, 0, 0, 0, 0, '[returned]', '', '', '', '', 0, 0, '', 0, 0, '', 0, 0, '', 0, 0, '', 0, 0, 0, 0, 0, 0, 0, 0),
(970706, 2, 1, 1, -1, 0, 0, 0, 0, '[reassigned]', '', '', '', '', 0, 0, '', 0, 0, '', 0, 0, '', 0, 0, '', 0, 0, 0, 0, 0, 0, 0, 0);
INSERT INTO `quest_template_addon` (`ID`, `PrevQuestID`, `SpecialFlags`) VALUES (970700, 0, 0), (970701, 970700, 0), (970702, 970701, 0), (970703, 970702, 0), (970704, 970703, 0);
INSERT INTO `quest_offer_reward` (`ID`, `Emote1`, `RewardText`) VALUES
(970700, 1, 'Abraham says thank you. Terrence says nothing, which for Terrence is thank you. Linnea says she\'s been asking for whetstones since the Third War.$B$BThat\'s one night covered. Brill has a lot of nights, $n.'),
(970701, 1, 'Gnawbone. Fitting. The survivors always have a way with names.$B$BThe north road\'s clear for now. Which brings me to the thing I didn\'t want to say: that road was only open to the ghouls because there was no one on it. Harlan\'s missed four patrols this month. He comes back before the bell, says nothing, stands his post like nothing happened. Then he\'s gone again.'),
(970702, 1, '(The Deathguard on the hill doesn\'t turn when you come up behind him. He\'s looking down at the farmstead below, at a cottage with smoke rising from its chimney.)$B$BI know who sent you. Dillinger\'s a decent sort; he\'d send someone quiet before he sent a noose. Tell him I\'m not deserting. I\'m only... standing a different watch.$B$B(He nods toward the cottage.)$B$BI was a farmhand at Solliden, before. That\'s my house. My wife\'s in it. My daughter. They lived, $n. The plague came through and they lived, and I didn\'t, and then I came back. Like this.'),
(970703, 1, 'She found the wood. She thinks it\'s a neighbor.$B$B(Harlan is quiet a long time.)$B$BLise has my mother\'s chin. She was four when I died; I didn\'t know what she\'d look like. Now I do. That\'s more than most of us get, $n. More than I had any right to.$B$B(He finally looks away from the cottage.)$B$BI can\'t keep doing this. Dillinger\'s covered for me as long as he can, and the road paid for it. Something has to change.'),
(970704, 1, 'Harlan told me himself. Walked up to me and reported, as if he\'d been gone an hour.$B$B(Dillinger shakes his head slowly.)$B$BSolliden. His wife and his girl, alive, an hour\'s walk from his post, and he never said a word. Seven years. I\'ve known men go to pieces over less, $n, living or dead. He\'ll do, now. You saw to that.$B$BThe Executor will hear he was on special duty for me. That\'s not even a lie, the way I count it. Thank you. Brill\'s watch is whole again.');
INSERT INTO `quest_request_items` (`ID`, `EmoteOnComplete`, `EmoteOnIncomplete`, `CompletionText`) VALUES
(970700, 1, 1, 'Packs delivered? Braziers lit? The night won\'t wait for you, $n, and neither will the Scourge.'),
(970701, 1, 1, 'The road, $n. Every night it stays dangerous, someone in Brill goes without bread or oil or news.'),
(970702, 1, 1, '(Deathguard Harlan does not turn around.) I know Dillinger sent you. Give me a moment.'),
(970703, 1, 1, 'Did you see them? (He keeps his eyes on the cottage.) Tell me slowly.'),
(970704, 1, 1, '(Harlan waits, his eyes on the cottage\'s lamp.)');
INSERT INTO `creature_queststarter` (`id`, `quest`) VALUES (1496, 970700), (1496, 970701), (1496, 970702), (970700, 970703), (970700, 970704);
INSERT INTO `creature_questender` (`id`, `quest`) VALUES (1496, 970700), (1496, 970701), (970700, 970702), (970700, 970703), (1496, 970704);
UPDATE `creature_template` SET `npcflag` = `npcflag` | 3 WHERE `entry` IN (1496);

-- Something in the Well
INSERT INTO `creature_template` (`entry`, `name`, `subname`, `gossip_menu_id`, `minlevel`, `maxlevel`, `faction`, `npcflag`, `speed_walk`, `speed_run`, `unit_class`, `unit_flags`, `type`, `rank`, `HealthModifier`, `DamageModifier`, `mingold`, `maxgold`, `flags_extra`, `AIName`, `ScriptName`) VALUES
(970800, 'Maudie Fenwick', 'Laundress', 0, 10, 10, 68, 3, 1, 1.14286, 1, 0, 6, 0, 1, 1, 0, 0, 2, '', ''),
(970801, 'Culvert Broodmother', NULL, 0, 12, 12, 14, 0, 1, 1.14286, 1, 0, 1, 0, 3.0, 1.3, 300, 300, 0, 'SmartAI', ''),
(970802, 'Broodmother Larva', NULL, 0, 8, 9, 14, 0, 1, 1.14286, 1, 0, 1, 0, 0.6, 0.6, 0, 0, 0, '', ''),
(970803, 'Water samples drawn', NULL, 0, 1, 1, 35, 0, 1, 1, 1, 33554432, 10, 0, 1, 1, 0, 0, 130, '', ''),
(970804, 'Gray bloom examined', NULL, 0, 1, 1, 35, 0, 1, 1, 1, 33554432, 10, 0, 1, 1, 0, 0, 130, '', ''),
(970805, 'The culvert under the bridge found', NULL, 0, 1, 1, 35, 0, 1, 1, 1, 33554432, 10, 0, 1, 1, 0, 0, 130, '', ''),
(970806, 'Egg sacs burst', NULL, 0, 1, 1, 35, 0, 1, 1, 1, 33554432, 10, 0, 1, 1, 0, 0, 130, '', ''),
(970807, 'The nest searched', NULL, 0, 1, 1, 35, 0, 1, 1, 1, 33554432, 10, 0, 1, 1, 0, 0, 130, '', ''),
(970808, 'Brill\'s well cleansed', NULL, 0, 1, 1, 35, 0, 1, 1, 1, 33554432, 10, 0, 1, 1, 0, 0, 130, '', '');
INSERT INTO `creature_template_model` (`CreatureID`, `Idx`, `CreatureDisplayID`, `DisplayScale`, `Probability`) VALUES
(970800, 0, 4004, 1, 1),
(970801, 0, 12336, 0.8, 1),
(970802, 0, 15436, 1, 1),
(970803, 0, 11686, 1, 1),
(970804, 0, 11686, 1, 1),
(970805, 0, 11686, 1, 1),
(970806, 0, 11686, 1, 1),
(970807, 0, 11686, 1, 1),
(970808, 0, 11686, 1, 1);
INSERT INTO `creature` (`guid`, `id`, `map`, `zoneId`, `areaId`, `spawnMask`, `phaseMask`, `position_x`, `position_y`, `position_z`, `orientation`, `spawntimesecs`, `wander_distance`, `MovementType`, `Comment`) VALUES
(970800, 970800, 0, 85, 0, 1, 1, 2292.600, 314.600, 35.500, 3.9300, 60, 0, 0, 'mod-brill-home town'),
(970801, 970801, 0, 85, 0, 1, 1, 2212.000, 634.000, 24.800, 2.4000, 90, 0, 0, 'mod-brill-home town'),
(970802, 970802, 0, 85, 0, 1, 1, 2224.000, 636.000, 24.800, 3.0000, 60, 3, 1, 'mod-brill-home town'),
(970803, 970802, 0, 85, 0, 1, 1, 2212.000, 624.000, 24.800, 1.0000, 60, 3, 1, 'mod-brill-home town'),
(970804, 970802, 0, 85, 0, 1, 1, 2205.000, 636.000, 24.800, 5.0000, 60, 3, 1, 'mod-brill-home town');
UPDATE `creature_template` SET `AIName` = 'SmartAI' WHERE `entry` IN (970801);
INSERT INTO `smart_scripts` (`entryorguid`, `source_type`, `id`, `link`, `event_type`, `event_phase_mask`, `event_chance`, `event_flags`, `event_param1`, `event_param2`, `event_param3`, `event_param4`, `event_param5`, `event_param6`, `action_type`, `action_param1`, `action_param2`, `action_param3`, `action_param4`, `action_param5`, `action_param6`, `target_type`, `target_param1`, `target_param2`, `target_param3`, `target_param4`, `target_x`, `target_y`, `target_z`, `target_o`, `comment`) VALUES
(970801, 0, 0, 0, 0, 0, 100, 0, 3000, 5000, 10000, 14000, 0, 0, 11, 744, 0, 0, 0, 0, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0, 'Culvert Broodmother casts 744'),
(970801, 0, 1, 0, 0, 0, 100, 0, 6000, 8000, 15000, 18000, 0, 0, 11, 3427, 0, 0, 0, 0, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0, 'Culvert Broodmother casts 3427');
INSERT INTO `gameobject_template` (`entry`, `type`, `displayId`, `name`, `size`, `Data0`, `Data1`, `ScriptName`) VALUES
(970800, 2, 8503, 'Brill Well', 1, 0, 0, 'go_brill_town'),
(970801, 2, 239, 'Maudie\'s Old Bucket', 1, 0, 0, 'go_brill_town'),
(970802, 2, 389, 'Gray Bloom', 1, 0, 0, 'go_brill_town'),
(970803, 2, 134, 'Collapsed Culvert', 0.6, 0, 0, 'go_brill_town'),
(970804, 2, 364, 'Broodmother Egg Sac', 1, 0, 0, 'go_brill_town'),
(970805, 2, 9, 'Split Cask', 1, 0, 0, 'go_brill_town'),
(970806, 5, 7485, 'Maudie\'s Washtubs', 1, 0, 0, ''),
(970807, 5, 7484, 'Bucket of Clean Water', 1, 0, 0, ''),
(970808, 5, 468, 'Laundry Basket', 1, 0, 0, '');
INSERT INTO `gameobject` (`guid`, `id`, `map`, `zoneId`, `areaId`, `spawnMask`, `phaseMask`, `position_x`, `position_y`, `position_z`, `orientation`, `rotation0`, `rotation1`, `rotation2`, `rotation3`, `spawntimesecs`, `animprogress`, `state`, `Comment`) VALUES
(970800, 970800, 0, 85, 0, 1, 1, 2290.000, 312.000, 35.300, 0.0000, 0, 0, 0.000000, 1.000000, 0, 255, 1, 'mod-brill-home town'),
(970801, 970801, 0, 85, 0, 1, 1, 2386.000, 95.000, 21.600, 1.0000, 0, 0, 0.479426, 0.877583, 0, 255, 1, 'mod-brill-home town'),
(970802, 970801, 0, 85, 0, 1, 1, 2418.000, 103.000, 21.500, 2.0000, 0, 0, 0.841471, 0.540302, 0, 255, 1, 'mod-brill-home town'),
(970803, 970801, 0, 85, 0, 1, 1, 2436.000, 106.000, 21.900, 4.0000, 0, 0, 0.909297, -0.416147, 0, 255, 1, 'mod-brill-home town'),
(970804, 970802, 0, 85, 0, 1, 1, 2262.000, 420.000, 35.000, 0.0000, 0, 0, 0.000000, 1.000000, 0, 255, 1, 'mod-brill-home town'),
(970805, 970802, 0, 85, 0, 1, 1, 2255.000, 480.000, 33.900, 1.5000, 0, 0, 0.681639, 0.731689, 0, 255, 1, 'mod-brill-home town'),
(970806, 970802, 0, 85, 0, 1, 1, 2250.000, 560.000, 33.600, 3.0000, 0, 0, 0.997495, 0.070737, 0, 255, 1, 'mod-brill-home town'),
(970807, 970803, 0, 85, 0, 1, 1, 2228.000, 640.000, 24.750, 1.6000, 0, 0, 0.717356, 0.696707, 0, 255, 1, 'mod-brill-home town'),
(970808, 970804, 0, 85, 0, 1, 1, 2208.000, 630.000, 24.750, 0.0000, 0, 0, 0.000000, 1.000000, 0, 255, 1, 'mod-brill-home town'),
(970809, 970804, 0, 85, 0, 1, 1, 2216.000, 641.000, 24.750, 1.0000, 0, 0, 0.479426, 0.877583, 0, 255, 1, 'mod-brill-home town'),
(970810, 970804, 0, 85, 0, 1, 1, 2203.000, 641.000, 24.800, 2.0000, 0, 0, 0.841471, 0.540302, 0, 255, 1, 'mod-brill-home town'),
(970811, 970804, 0, 85, 0, 1, 1, 2219.000, 631.000, 24.750, 3.0000, 0, 0, 0.997495, 0.070737, 0, 255, 1, 'mod-brill-home town'),
(970812, 970804, 0, 85, 0, 1, 1, 2210.000, 638.000, 24.750, 4.0000, 0, 0, 0.909297, -0.416147, 0, 255, 1, 'mod-brill-home town'),
(970813, 970805, 0, 85, 0, 1, 1, 2210.000, 644.000, 24.750, 0.7000, 0, 0, 0.342898, 0.939373, 0, 255, 1, 'mod-brill-home town'),
(970814, 970806, 0, 85, 0, 1, 536870912, 2294.200, 312.200, 35.500, 2.0000, 0, 0, 0.841471, 0.540302, 0, 255, 1, 'mod-brill-home town'),
(970815, 970807, 0, 85, 0, 1, 536870912, 2291.800, 309.600, 35.300, 0.5000, 0, 0, 0.247404, 0.968912, 0, 255, 1, 'mod-brill-home town'),
(970816, 970808, 0, 85, 0, 1, 536870912, 2295.000, 315.600, 35.800, 4.0000, 0, 0, 0.909297, -0.416147, 0, 255, 1, 'mod-brill-home town');
INSERT INTO `brill_town_use` (`go`, `ord`, `cond`, `quest`, `credit`, `text_id`, `marker`) VALUES
(970800, 0, 'I970800', 970800, 970803, 970800, 0),
(970800, 1, 'I970804', 970804, 970808, 970801, 0),
(970800, 2, 'R970804', 0, 0, 970802, 0),
(970800, 3, '', 0, 0, 970803, 0),
(970801, 0, 'I970800', 970800, 970803, 970804, 0),
(970801, 1, '', 0, 0, 970805, 0),
(970802, 0, 'I970802', 970802, 970804, 970806, 0),
(970802, 1, 'R970803', 0, 0, 970807, 0),
(970802, 2, '', 0, 0, 970808, 0),
(970803, 0, 'I970802', 970802, 970805, 970809, 0),
(970803, 1, '', 0, 0, 970810, 0),
(970804, 0, 'I970803', 970803, 970806, 970811, 0),
(970804, 1, '', 0, 0, 970812, 0),
(970805, 0, 'I970804', 970804, 970807, 970813, 0),
(970805, 1, '', 0, 0, 970814, 0);
INSERT INTO `brill_town_gossip` (`npc`, `ord`, `kind`, `cond`, `icon`, `text`, `text_id`, `action`, `quest`, `credit`, `marker`, `emote`, `reply`, `arg`) VALUES
(970800, 6000, 'greet', 'R970804 R970406', 0, NULL, 970815, '', 0, 0, 0, 0, NULL, 0),
(970800, 6001, 'greet', 'R970804 R970407', 0, NULL, 970816, '', 0, 0, 0, 0, NULL, 0),
(970800, 6002, 'greet', 'R970804 R970405', 0, NULL, 970817, '', 0, 0, 0, 0, NULL, 0),
(970800, 6003, 'greet', 'R970804', 0, NULL, 970818, '', 0, 0, 0, 0, NULL, 0),
(970800, 6004, 'greet', 'A970804', 0, NULL, 970819, '', 0, 0, 0, 0, NULL, 0),
(970800, 6005, 'greet', 'T970801 !R970804', 0, NULL, 970820, '', 0, 0, 0, 0, NULL, 0),
(970800, 6006, 'greet', '', 0, NULL, 970821, '', 0, 0, 0, 0, NULL, 0),
(10665, 6007, 'option', 'A970804 T970402', 0, 'Could that cask have been Wendell Crane\'s?', 0, 'say', 0, 0, 0, 0, 'Crane\'s? No. Batch nine is older than his apprenticeship; the Society stopped numbering batches that way the year we took Brill back. I know what\'s been said about him. Whatever else he is, he\'s careful. More careful than whoever did this.', 0),
(10665, 6008, 'greet', 'R970804 T970402', 0, NULL, 970822, '', 0, 0, 0, 0, NULL, 0),
(10665, 6009, 'greet', 'R970804', 0, NULL, 970823, '', 0, 0, 0, 0, NULL, 0),
(10665, 6010, 'greet', 'A970803', 0, NULL, 970824, '', 0, 0, 0, 0, NULL, 0),
(2132, 6011, 'option', 'R970804', 0, '(Ask her about Maudie\'s well.)', 0, 'say', 0, 0, 0, 0, 'Maudie\'s well is clean again. I\'ve started washing my glassware in it. I can tell the difference. I\'m not sure I like that I can tell.', 0);
UPDATE `creature_template` SET `npcflag` = `npcflag` | 1 WHERE `entry` IN (2132, 10665);
INSERT INTO `brill_town_phase` (`bit`, `ord`, `cond`) VALUES
(536870912, 0, 'R970804');
INSERT INTO `quest_template` (`ID`, `QuestType`, `QuestLevel`, `MinLevel`, `QuestSortID`, `Flags`, `RewardXPDifficulty`, `RewardMoney`, `AllowableRaces`, `LogTitle`, `LogDescription`, `QuestDescription`, `AreaDescription`, `QuestCompletionLog`, `RequiredNpcOrGo1`, `RequiredNpcOrGoCount1`, `ObjectiveText1`, `RequiredNpcOrGo2`, `RequiredNpcOrGoCount2`, `ObjectiveText2`, `RequiredNpcOrGo3`, `RequiredNpcOrGoCount3`, `ObjectiveText3`, `RequiredNpcOrGo4`, `RequiredNpcOrGoCount4`, `ObjectiveText4`, `RewardItem1`, `RewardAmount1`, `RewardItem2`, `RewardAmount2`, `RewardItem3`, `RewardAmount3`, `RewardItem4`, `RewardAmount4`) VALUES
(970800, 2, -1, 5, 159, 0, 3, 1500, 0, 'Water Unfit for the Dead', 'Draw a sample from Brill\'s well and three from the stream north-east of town, at Maudie Fenwick\'s old buckets, then return to Maudie in Brill.', 'You\'ll want to stand back from that well, dear. Further. Further than that.$B$BMaudie Fenwick. I do the washing for the inn: sheets, tabards, whatever the Deathguard bleed on, and the bedding of every orc and troll who stops in Brill on the way to somewhere better. The living sweat, you know. Terribly. Somebody has to wash it out.$B$BFor a fortnight the water\'s come up stinking. Not the honest stink of a town full of corpses; I\'m used to that, I am that. This is sour and green, and it clings to the cloth. Renee\'s had complaints.$B$BFill these jars for me. One from the well, and three from the stream north-east of town, where I drew water before the well was dug. My old buckets are still on the bank. If the stream\'s gone bad as well, then it isn\'t just my well, is it?', '', 'Return to Maudie Fenwick at the well in Brill.', 970803, 4, 'Water samples drawn', 0, 0, '', 0, 0, '', 0, 0, '', 0, 0, 0, 0, 0, 0, 0, 0),
(970801, 2, -1, 5, 159, 0, 2, 1000, 0, 'A Peculiar Growth', 'Bring Maudie Fenwick\'s water samples to Junior Apothecary Holland in Brill.', 'I\'m a washerwoman, not an alchemist. I know when water\'s wrong; I couldn\'t tell you why.$B$BTake the jars to Junior Apothecary Holland, at the north end of town. Not Johaan. Johaan would want to keep them. Holland\'s young, by our reckoning, and still curious, and he doesn\'t charge for curiosity.$B$BTell him Maudie sent you. He\'ll pretend he doesn\'t know who I am. He sends me his robes every week and pretends they wash themselves.', '', 'Speak with Junior Apothecary Holland in Brill.', 0, 0, '', 0, 0, '', 0, 0, '', 0, 0, '', 0, 0, 0, 0, 0, 0, 0, 0),
(970802, 2, -1, 5, 159, 0, 4, 2500, 0, 'The Source of the Rot', 'Follow the fouled runoff west of Brill: examine 3 patches of gray bloom and find where the old drainage channel runs under the covered bridge, then return to Junior Apothecary Holland.', 'Water runs downhill, and so does everything in it. Brill\'s ground water drains west, under the hill, toward the channel the Lordaeron engineers cut to keep the fields from flooding. It\'s been dry since the plague. Nobody tends the sluices now.$B$BFollow the runoff. Where the larvae are thick, the ground will show it: a gray bloom, like mold on bread, wherever the water seeps out. Find three patches, then find where it all comes from. My guess is the channel itself, where it passes under the covered bridge on the Deathknell road.$B$BDon\'t touch the bloom with bare skin. Or with skin of any kind, really.', '', 'Return to Junior Apothecary Holland in Brill.', 970804, 3, 'Gray bloom examined', 970805, 1, 'The culvert under the bridge found', 0, 0, '', 0, 0, '', 0, 0, 0, 0, 0, 0, 0, 0),
(970803, 2, -1, 5, 159, 0, 5, 4000, 0, 'A Nest Below', 'Kill the Culvert Broodmother under the covered bridge west of Brill and burst 4 of its egg sacs, then return to Junior Apothecary Holland.', 'I\'d send for the Deathguard, but Executor Zygand would want it in writing, and then he\'d want to know why the Society didn\'t notice sooner, and then I\'d be writing for a month.$B$BYou go. Get under that bridge and kill whatever is laying those eggs. Burst the sacs too, every one you find. If even a few hatch, the whole thing starts over in a season.$B$BI\'ve no idea what it will be. A broodmother of some kind. Something the plague made, or something the plague made worse. Either way, it\'s grown fat on our water, and I would like it to stop.', '', 'Return to Junior Apothecary Holland in Brill.', 970801, 1, '', 970806, 4, 'Egg sacs burst', 0, 0, '', 0, 0, '', 858, 3, 0, 0, 0, 0, 0, 0),
(970804, 2, -1, 5, 159, 0, 5, 5000, 0, 'Clear Waters, Dark Memories', 'Search the broodmother\'s nest under the covered bridge, cleanse Brill\'s well with Holland\'s quicklime, then speak with Maudie Fenwick in Brill.', 'A broodmother that size doesn\'t grow on runoff alone, not in a few years. Something fed it. Something rich.$B$BGo back to the nest and search it, properly, now that nothing in it is trying to eat you. If anything was dumped there, a cask, a crate, anything with a label, I want to know what was in it.$B$BAnd take this.$B$B(He hands you a heavy sack of gray powder.)$B$BQuicklime and bitterroot ash. Tip it down Brill\'s well; it\'ll kill whatever larvae are left and won\'t harm anyone who\'s already dead. The living may want to boil their water for a week. Then tell Maudie she can have her well back.', '', 'Return to Maudie Fenwick at the well in Brill.', 970807, 1, 'The nest searched', 970808, 1, 'Brill\'s well cleansed', 0, 0, '', 0, 0, '', 2459, 1, 858, 2, 0, 0, 0, 0);
INSERT INTO `quest_template_addon` (`ID`, `PrevQuestID`, `SpecialFlags`) VALUES (970800, 0, 0), (970801, 970800, 0), (970802, 970801, 0), (970803, 970802, 0), (970804, 970803, 0);
INSERT INTO `quest_offer_reward` (`ID`, `Emote1`, `RewardText`) VALUES
(970800, 1, 'Look at that. Look at it moving.$B$BI\'ve washed blood, bile and the inside of a ghoul\'s mouth out of good linen and never once felt queasy. But that... that turns what\'s left of my stomach.$B$BIt\'s in the stream too. So it isn\'t my well. It\'s under all of us.'),
(970801, 1, 'Maudie sent you? I don\'t know any Maudie.$B$B(Holland uncorks the first jar, sniffs, and stops pretending.)$B$BOh, that\'s lovely. Look here, under the glass. These threads aren\'t weed. They\'re larvae: a parasite, a fluke of some kind, feeding on whatever\'s dissolved in the water. On us, in a manner of speaking. The runoff from a town of the dead is very rich.$B$BThey don\'t breed in open water. Something is laying them. Somewhere close, and dark, and wet.'),
(970802, 1, 'Under the bridge. Of course. Dark and damp, and nobody\'s walked that channel in years but the Rotting Dead, and they\'re not much for reporting things.$B$BWarm, you said. And the smell of eggs.$B$B(Holland goes rather pale, which takes some doing.)$B$BThat\'s a nest, $n. And whatever\'s laying in it will be considerably larger than a thread.'),
(970803, 1, 'Dead. And the sacs. Good. Good.$B$B(He sets down a jar he has been holding very tightly.)$B$BI\'ve watched the samples all afternoon. The threads have stopped moving. Not slowly: all at once, the moment it died, as if something had been pulling every one of their strings.$B$BThere\'s still what\'s left in the water. And something about how fast it grew is bothering me.'),
(970804, 1, 'It\'s clear. Look, $n, it\'s clear! I can see the bottom of the bucket!$B$B(She draws another, just to see it again.)$B$BAnd the cask, you say. Society work. Somebody up the road poured their mistake into the ground and let it seep under all of us, and never once came to ask why the water tasted wrong.$B$BWell. I won\'t say anything. Who would I say it to? But I\'ll be washing the Society\'s robes in this water from now on, and I\'ll know. Every time.');
INSERT INTO `quest_request_items` (`ID`, `EmoteOnComplete`, `EmoteOnIncomplete`, `CompletionText`) VALUES
(970800, 1, 1, 'Four jars. And don\'t drink from any of them, whatever you are.'),
(970802, 1, 1, 'Anything? The jars on my bench have started to cloud. That\'s new.'),
(970803, 1, 1, 'Is it dead? And the eggs? Both matter. The second rather more.'),
(970804, 1, 1, 'Is it done? Can I fill my tubs?');
INSERT INTO `creature_queststarter` (`id`, `quest`) VALUES (970800, 970800), (970800, 970801), (10665, 970802), (10665, 970803), (10665, 970804);
INSERT INTO `creature_questender` (`id`, `quest`) VALUES (970800, 970800), (10665, 970801), (10665, 970802), (10665, 970803), (970800, 970804);
UPDATE `creature_template` SET `npcflag` = `npcflag` | 3 WHERE `entry` IN (10665);

-- Beneath the Gallows
INSERT INTO `creature_template` (`entry`, `name`, `subname`, `gossip_menu_id`, `minlevel`, `maxlevel`, `faction`, `npcflag`, `speed_walk`, `speed_run`, `unit_class`, `unit_flags`, `type`, `rank`, `HealthModifier`, `DamageModifier`, `mingold`, `maxgold`, `flags_extra`, `AIName`, `ScriptName`) VALUES
(970900, 'Isolde Ashcroft', 'Deathstalker', 0, 30, 30, 68, 3, 1, 1.14286, 1, 0, 6, 0, 1, 1, 0, 0, 2, '', ''),
(970901, 'Isolde Ashcroft', 'Deathstalker', 0, 30, 30, 68, 3, 1, 1.14286, 1, 512, 6, 0, 1, 1, 0, 0, 2, '', ''),
(970902, 'Bound Sentinel', NULL, 0, 10, 11, 14, 0, 1, 1.14286, 1, 0, 6, 0, 1.0, 1.0, 0, 0, 0, 'SmartAI', ''),
(970903, 'Aldric Croft', 'Cult of the Damned', 0, 13, 13, 14, 0, 1, 1.14286, 8, 0, 6, 0, 3.0, 1.3, 200, 200, 0, 'SmartAI', ''),
(970904, 'The Cult\'s marks found', NULL, 0, 1, 1, 35, 0, 1, 1, 1, 33554432, 10, 0, 1, 1, 0, 0, 130, '', ''),
(970905, 'Refugees\' journals recovered', NULL, 0, 1, 1, 35, 0, 1, 1, 1, 33554432, 10, 0, 1, 1, 0, 0, 130, '', ''),
(970906, 'The warded door listened at', NULL, 0, 1, 1, 35, 0, 1, 1, 1, 33554432, 10, 0, 1, 1, 0, 0, 130, '', ''),
(970907, 'Croft\'s letters read', NULL, 0, 1, 1, 35, 0, 1, 1, 1, 33554432, 10, 0, 1, 1, 0, 0, 130, '', ''),
(970908, 'Croft\'s Roll of the Raised', NULL, 0, 1, 1, 35, 0, 1, 1, 1, 33554432, 10, 0, 1, 1, 0, 0, 130, '', '');
INSERT INTO `creature_template_model` (`CreatureID`, `Idx`, `CreatureDisplayID`, `DisplayScale`, `Probability`) VALUES
(970900, 0, 1679, 1, 1),
(970901, 0, 1679, 1, 1),
(970902, 0, 200, 1, 1),
(970902, 1, 535, 1, 1),
(970903, 0, 10427, 1, 1),
(970904, 0, 11686, 1, 1),
(970905, 0, 11686, 1, 1),
(970906, 0, 11686, 1, 1),
(970907, 0, 11686, 1, 1),
(970908, 0, 11686, 1, 1);
INSERT INTO `creature` (`guid`, `id`, `map`, `zoneId`, `areaId`, `spawnMask`, `phaseMask`, `position_x`, `position_y`, `position_z`, `orientation`, `spawntimesecs`, `wander_distance`, `MovementType`, `Comment`) VALUES
(970900, 970900, 0, 85, 0, 1, 1073741824, 2378.000, 335.000, 38.510, 0.1000, 60, 0, 0, 'mod-brill-home town'),
(970901, 970901, 0, 85, 0, 1, 1, 2965.000, 645.000, 100.000, 0.2700, 60, 0, 0, 'mod-brill-home town'),
(970902, 970902, 0, 85, 0, 1, 1, 3040.000, 671.500, 81.200, 0.0000, 120, 0, 0, 'mod-brill-home town'),
(970903, 970902, 0, 85, 0, 1, 1, 3047.500, 671.000, 81.100, 3.1000, 120, 0, 0, 'mod-brill-home town'),
(970904, 970902, 0, 85, 0, 1, 1, 3030.000, 655.000, 75.400, 0.0000, 120, 0, 0, 'mod-brill-home town'),
(970905, 970902, 0, 85, 0, 1, 1, 3040.000, 655.000, 75.400, 3.1000, 120, 0, 0, 'mod-brill-home town'),
(970906, 970902, 0, 85, 0, 1, 1, 3052.000, 655.500, 75.400, 3.1000, 120, 0, 0, 'mod-brill-home town'),
(970907, 970902, 0, 85, 0, 1, 1, 3028.000, 688.500, 66.400, 0.0000, 120, 0, 0, 'mod-brill-home town'),
(970908, 970902, 0, 85, 0, 1, 1, 3040.000, 689.000, 66.600, 3.1000, 120, 0, 0, 'mod-brill-home town'),
(970909, 970903, 0, 85, 0, 1, 1, 3060.500, 694.000, 66.500, 3.6000, 300, 0, 0, 'mod-brill-home town');
UPDATE `creature_template` SET `AIName` = 'SmartAI' WHERE `entry` IN (970902, 970903);
INSERT INTO `smart_scripts` (`entryorguid`, `source_type`, `id`, `link`, `event_type`, `event_phase_mask`, `event_chance`, `event_flags`, `event_param1`, `event_param2`, `event_param3`, `event_param4`, `event_param5`, `event_param6`, `action_type`, `action_param1`, `action_param2`, `action_param3`, `action_param4`, `action_param5`, `action_param6`, `target_type`, `target_param1`, `target_param2`, `target_param3`, `target_param4`, `target_x`, `target_y`, `target_z`, `target_o`, `comment`) VALUES
(970902, 0, 0, 0, 0, 0, 100, 0, 3000, 5000, 8000, 11000, 0, 0, 11, 11976, 0, 0, 0, 0, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0, 'Bound Sentinel casts 11976'),
(970903, 0, 0, 0, 0, 0, 100, 0, 1000, 2000, 4000, 6000, 0, 0, 11, 20791, 0, 0, 0, 0, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0, 'Aldric Croft casts 20791'),
(970903, 0, 1, 0, 0, 0, 100, 0, 3000, 5000, 16000, 20000, 0, 0, 11, 980, 0, 0, 0, 0, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0, 'Aldric Croft casts 980'),
(970903, 0, 2, 0, 0, 0, 100, 0, 9000, 12000, 20000, 25000, 0, 0, 11, 689, 0, 0, 0, 0, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0, 'Aldric Croft casts 689');
INSERT INTO `gameobject_template` (`entry`, `type`, `displayId`, `name`, `size`, `Data0`, `Data1`, `ScriptName`) VALUES
(970900, 2, 465, 'Crowned Eye Sigil', 0.6, 0, 0, 'go_brill_town'),
(970901, 2, 302, 'Mausoleum Foundation Stone', 0.8, 0, 0, 'go_brill_town'),
(970902, 2, 456, 'Sunken Earth', 0.7, 0, 0, 'go_brill_town'),
(970903, 2, 105, 'Water-Stained Journal', 1, 0, 0, 'go_brill_town'),
(970904, 2, 447, 'Baker\'s Daybook', 1, 0, 0, 'go_brill_town'),
(970905, 2, 184, 'Child\'s Copybook', 0.8, 0, 0, 'go_brill_town'),
(970906, 2, 316, 'Bone-Warded Door', 1, 0, 0, 'go_brill_town'),
(970907, 2, 164, 'Unsent Letters', 1, 0, 0, 'go_brill_town'),
(970908, 2, 163, 'Roll of the Raised', 1, 0, 0, 'go_brill_town'),
(970909, 5, 6406, 'Croft\'s Skull-Candle, Snuffed', 1, 0, 0, '');
INSERT INTO `gameobject` (`guid`, `id`, `map`, `zoneId`, `areaId`, `spawnMask`, `phaseMask`, `position_x`, `position_y`, `position_z`, `orientation`, `rotation0`, `rotation1`, `rotation2`, `rotation3`, `spawntimesecs`, `animprogress`, `state`, `Comment`) VALUES
(970900, 970900, 0, 85, 0, 1, 1073741824, 2293.500, 240.000, 27.300, 1.6000, 0, 0, 0.717356, 0.696707, 0, 255, 1, 'mod-brill-home town'),
(970901, 970901, 0, 85, 0, 1, 1073741824, 2378.000, 326.000, 38.730, 1.6000, 0, 0, 0.717356, 0.696707, 0, 255, 1, 'mod-brill-home town'),
(970902, 970902, 0, 85, 0, 1, 1073741824, 2405.000, 318.000, 38.260, 1.6000, 0, 0, 0.717356, 0.696707, 0, 255, 1, 'mod-brill-home town'),
(970903, 970903, 0, 85, 0, 1, 1, 3045.000, 674.000, 81.200, 2.0000, 0, 0, 0.841471, 0.540302, 0, 255, 1, 'mod-brill-home town'),
(970904, 970904, 0, 85, 0, 1, 1, 3035.000, 657.000, 75.400, 4.0000, 0, 0, 0.909297, -0.416147, 0, 255, 1, 'mod-brill-home town'),
(970905, 970905, 0, 85, 0, 1, 1, 3021.500, 691.000, 66.500, 5.0000, 0, 0, 0.598472, -0.801144, 0, 255, 1, 'mod-brill-home town'),
(970906, 970906, 0, 85, 0, 1, 1, 3050.000, 690.000, 66.600, 0.0000, 0, 0, 0.000000, 1.000000, 0, 255, 1, 'mod-brill-home town'),
(970907, 970907, 0, 85, 0, 1, 1, 3056.000, 693.000, 66.500, 3.0000, 0, 0, 0.997495, 0.070737, 0, 255, 1, 'mod-brill-home town'),
(970908, 970908, 0, 85, 0, 1, 1, 3062.500, 697.000, 66.500, 3.6000, 0, 0, 0.973848, -0.227202, 0, 255, 1, 'mod-brill-home town'),
(970909, 970909, 0, 85, 0, 1, 2147483648, 2274.219, 374.033, 35.800, 4.9306, 0, 0, 0.625909, -0.779896, 0, 255, 1, 'mod-brill-home town');
INSERT INTO `brill_town_use` (`go`, `ord`, `cond`, `quest`, `credit`, `text_id`, `marker`) VALUES
(970900, 0, 'I970900', 970900, 970904, 970900, 0),
(970900, 1, '', 0, 0, 970901, 0),
(970901, 0, 'I970900', 970900, 970904, 970902, 0),
(970901, 1, '', 0, 0, 970903, 0),
(970902, 0, 'I970900', 970900, 970904, 970904, 0),
(970902, 1, '', 0, 0, 970905, 0),
(970903, 0, 'I970901', 970901, 970905, 970906, 0),
(970903, 1, '', 0, 0, 970907, 0),
(970904, 0, 'I970901', 970901, 970905, 970908, 0),
(970904, 1, '', 0, 0, 970909, 0),
(970905, 0, 'I970901', 970901, 970905, 970910, 0),
(970905, 1, '', 0, 0, 970911, 0),
(970906, 0, 'I970903', 970903, 970906, 970912, 0),
(970906, 1, 'T970903', 0, 0, 970913, 0),
(970906, 2, '', 0, 0, 970914, 0),
(970907, 0, 'I970903', 970903, 970907, 970915, 0),
(970907, 1, '', 0, 0, 970916, 0),
(970908, 0, 'I970904', 970904, 970908, 970917, 0),
(970908, 1, '', 0, 0, 970918, 0);
INSERT INTO `brill_town_gossip` (`npc`, `ord`, `kind`, `cond`, `icon`, `text`, `text_id`, `action`, `quest`, `credit`, `marker`, `emote`, `reply`, `arg`) VALUES
(970900, 7000, 'greet', 'R970904', 0, NULL, 970919, '', 0, 0, 0, 0, NULL, 0),
(970900, 7001, 'greet', 'T970901', 0, NULL, 970920, '', 0, 0, 0, 0, NULL, 0),
(970900, 7002, 'greet', '', 0, NULL, 970921, '', 0, 0, 0, 0, NULL, 0),
(970901, 7003, 'greet', 'R970904', 0, NULL, 970922, '', 0, 0, 0, 0, NULL, 0),
(970901, 7004, 'greet', 'T970901', 0, NULL, 970923, '', 0, 0, 0, 0, NULL, 0),
(970901, 7005, 'greet', '', 0, NULL, 970924, '', 0, 0, 0, 0, NULL, 0),
(970306, 7006, 'option', 'T970900 !R970904', 0, 'Isolde Ashcroft found your mark on the mausoleum stone.', 0, 'say', 0, 0, 0, 0, 'T.C. Aye. I was proud of that wall, God help me. Croft paid in good silver and said the people below would thank me. Go carefully down there, $n. I laid those bricks to keep something in.', 0);
INSERT INTO `brill_town_phase` (`bit`, `ord`, `cond`) VALUES
(1073741824, 0, 'R970203'),
(1073741824, 1, 'R970304'),
(2147483648, 0, 'R970904 O');
INSERT INTO `quest_template` (`ID`, `QuestType`, `QuestLevel`, `MinLevel`, `QuestSortID`, `Flags`, `RewardXPDifficulty`, `RewardMoney`, `AllowableRaces`, `LogTitle`, `LogDescription`, `QuestDescription`, `AreaDescription`, `QuestCompletionLog`, `RequiredNpcOrGo1`, `RequiredNpcOrGoCount1`, `ObjectiveText1`, `RequiredNpcOrGo2`, `RequiredNpcOrGoCount2`, `ObjectiveText2`, `RequiredNpcOrGo3`, `RequiredNpcOrGoCount3`, `ObjectiveText3`, `RequiredNpcOrGo4`, `RequiredNpcOrGoCount4`, `ObjectiveText4`, `RewardItem1`, `RewardAmount1`, `RewardItem2`, `RewardAmount2`, `RewardItem3`, `RewardAmount3`, `RewardItem4`, `RewardAmount4`) VALUES
(970900, 2, -1, 10, 159, 0, 4, 3000, 0, 'The Sealed Passage', 'Find the Cult\'s marks beneath Brill: in the cellar under the inn, on the mausoleum\'s foundation stone and where the ground has sunk at the cemetery\'s east edge.', 'Don\'t turn round. Admire the mausoleum. It\'s very fine.$B$BEither you\'ve seen a mark on a bricked-up wall you shouldn\'t have, or a dead mason has been talking where a gravekeeper could hear. Either way you\'ve found the edge of something I\'ve been following since Stratholme, and I\'d rather have you with me than blundering about alone.$B$BBefore the plague there were tunnels under this town: old cellars and a smugglers\' way that ran north under the hills. In the plague year someone walled every way into them and marked each wall. A crowned eye. The Cult of the Damned used it on the houses they meant to come back for.$B$BFind me the marks. There\'s one in the cellar under the inn, where the Deathguard keep their prisoners; one on this mausoleum\'s foundation stone; and one where the earth has fallen in past the east wall. Tell me which way they point.', '', 'Return to Deathstalker Isolde Ashcroft by the mausoleum in Brill\'s cemetery.', 970904, 3, 'The Cult\'s marks found', 0, 0, '', 0, 0, '', 0, 0, '', 0, 0, 0, 0, 0, 0, 0, 0),
(970901, 2, -1, 10, 159, 0, 4, 3500, 0, 'Echoes of Lordaeron', 'Recover three journals left by Brill\'s plague refugees in the Agamand Family Crypt, north of Brill, then find Deathstalker Isolde Ashcroft outside the crypt.', 'The Agamand Family Crypt is north of here, past the mills. The Agamands themselves don\'t rest easy, and they won\'t thank you for visiting. Go anyway.$B$BIf Croft\'s refuge opened into their vaults, the people who sheltered there will have left something. People write, when they\'re frightened and waiting. They write to whoever might find it.$B$BBring me what they wrote. I\'ll meet you outside the crypt. I have a few things to arrange first: there are people in the Undercity who\'d rather this stayed buried, and I\'d like them busy elsewhere.', '', 'Find Deathstalker Isolde Ashcroft outside the Agamand Family Crypt.', 970905, 3, 'Refugees\' journals recovered', 0, 0, '', 0, 0, '', 0, 0, '', 858, 2, 0, 0, 0, 0, 0, 0),
(970902, 2, -1, 10, 159, 0, 5, 4000, 0, 'The Unquiet Dead', 'Destroy 6 Bound Sentinels in the Agamand Family Crypt.', 'You\'ll have seen them down there: the ones in rags, standing their posts in the dark, who don\'t moan the way the Agamands do. They don\'t make any sound at all.$B$BThose are Croft\'s refugees. Hester Lyle and her children, the baker, the boy who wrote. He raised them and bound them to guard his door, and they\'ve stood there for years without a single thought of their own. Not like us. Nothing woke in them. Nothing is going to.$B$BI\'ve been free six years and I still don\'t know how to feel about doing this. Do it anyway. Put them down, $n, gently if you can. It\'s the only kindness left to give them.', '', 'Return to Deathstalker Isolde Ashcroft outside the Agamand Family Crypt.', 970902, 6, '', 0, 0, '', 0, 0, '', 0, 0, '', 0, 0, 0, 0, 0, 0, 0, 0),
(970903, 2, -1, 10, 159, 0, 5, 4000, 0, 'A Master Without a Kingdom', 'Find the warded door deep in the Agamand Family Crypt and hear out whoever is behind it, and read the letters left beside it.', 'He\'s still down there. Croft. Twenty years and a war later.$B$BI could take you straight to him and we could be done. But I want to know what we\'re dealing with: whether he\'s mad, or waiting for something, or someone. A Cult necromancer who\'s sat in the dark this long has either forgotten the world or is very sure it will come back to him.$B$BGo to the bottom of the crypt. There\'ll be a door he\'s warded. Talk to it. Listen. And look about you: a man like that keeps records. He can\'t help himself.', '', 'Return to Deathstalker Isolde Ashcroft outside the Agamand Family Crypt.', 970906, 1, 'The warded door listened at', 970907, 1, 'Croft\'s letters read', 0, 0, '', 0, 0, '', 2455, 2, 0, 0, 0, 0, 0, 0),
(970904, 2, -1, 10, 159, 0, 6, 6000, 0, 'No Masters but Ourselves', 'Slay Aldric Croft in the depths of the Agamand Family Crypt and take his Roll of the Raised to Gravekeeper Mortimer Weller in Brill.', 'His ward is weaker for the sentinels being gone. It\'ll break if you put your shoulder to it, and then it\'s you and him.$B$BHe\'s a necromancer of the old Cult: shadow, curses, the slow drain. He\'s had twenty years to prepare and no one to practise on, so he\'ll be sharp in his spells and soft everywhere else. Close quickly.$B$BWhen he\'s down, find his register. Every name he raised, he\'ll have written down: it\'s how they thought, the Cult, like stewards counting sheep. Those names belong to Brill. Take them to the gravekeeper. I\'ll stay and see the crypt sealed properly this time. My way.', '', 'Bring Croft\'s Roll of the Raised to Gravekeeper Mortimer Weller in Brill\'s cemetery.', 970903, 1, '', 970908, 1, 'Croft\'s Roll of the Raised', 0, 0, '', 0, 0, '', 929, 2, 14123, 1, 0, 0, 0, 0);
INSERT INTO `quest_template_addon` (`ID`, `PrevQuestID`, `SpecialFlags`) VALUES (970900, 0, 0), (970901, 970900, 0), (970902, 970901, 0), (970903, 970902, 0), (970904, 970903, 0);
INSERT INTO `quest_offer_reward` (`ID`, `Emote1`, `RewardText`) VALUES
(970900, 1, 'North. All three of them, north, toward Agamand Mills.$B$BThe Agamands had vaults under their family crypt, deep and dry. If the tunnels ran there, the people of Brill who went below to hide from the plague went there too. And the man who walled them in knew it.$B$BHis name was Aldric Croft. Brill remembers him fondly. He paid for the well, the bell rope, the orphans\' bread. He was also a necromancer of the Cult, and he was never found.'),
(970901, 1, '(Isolde reads by the last of the light, slowly, the way you\'d read a list of the dead. Which it is.)$B$B\'Master Croft says the air below is clean.\' \'Master Croft brought bread again.\' And then, near the end, in the boy\'s hand: \'Mother says the doors are bricked. Master Croft says it is for our own good. Mother has stopped answering.\'$B$BHe didn\'t hide them from the plague, $n. He kept them for it.'),
(970902, 1, 'Thank you. I mean that, though I won\'t say it again.$B$BWith his sentinels gone the crypt is quieter. Quiet enough that I could hear something further down, while you worked. A man\'s voice, reading aloud. Very patient. Very pleased with itself.'),
(970903, 1, 'Letters to the Lich King. Years of them, and not one answer.$B$BNo, he isn\'t mad. He\'s worse. He\'s loyal. He thinks the Master\'s silence is a test, and that our freedom is a fever that will pass. He\'d put every Forsaken in Brill back in the ground and call it tidying.$B$BWe are no one\'s servants now, $n. Not his, not his master\'s. I\'d like him to understand that before the end.'),
(970904, 1, 'A Roll of the Raised. In a fine hand. He even kept the dates.$B$BThirty-one names. Lyle, Hester, and four children. Pike, the baker. Merriweather. ...I wrote some of these into my ledger as \'remains not recovered.\' I suppose I can finish the entries now.$B$B(He sets the roll beside his ledger, carefully, as if it might wake.)$B$BIsolde sent word. She\'s had something carried to your house, if you keep one here: Croft\'s skull-candle, snuffed. She says every home ought to have one thing in it to remind you who isn\'t master there anymore. Thank you, $n. For Brill.');
INSERT INTO `quest_request_items` (`ID`, `EmoteOnComplete`, `EmoteOnIncomplete`, `CompletionText`) VALUES
(970900, 1, 1, 'Three marks. Don\'t touch them more than you must.'),
(970901, 1, 1, 'Did they write? They always write.'),
(970902, 1, 1, 'It doesn\'t get easier. It shouldn\'t.'),
(970903, 1, 1, 'Well? Is he mad?'),
(970904, 1, 1, '(Mortimer looks at your hands, and then at your face.) You\'ve been to the bottom of it, haven\'t you?');
INSERT INTO `creature_queststarter` (`id`, `quest`) VALUES (970900, 970900), (970900, 970901), (970901, 970902), (970901, 970903), (970901, 970904);
INSERT INTO `creature_questender` (`id`, `quest`) VALUES (970900, 970900), (970901, 970901), (970901, 970902), (970901, 970903), (970300, 970904);

-- Brill Endures (waiting for its projects)

-- The words.
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970200, '(A table with two legs, a chair with none, and a dresser the woodworm got to first. Kindling, most of it.)', '(A table with two legs, a chair with none, and a dresser the woodworm got to first. Kindling, most of it.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970201, '(Furniture, once. Kindling, now.)', '(Furniture, once. Kindling, now.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970202, '(The boards by the hearth give like wet bread under your foot. Something below them scuttles away.)', '(The boards by the hearth give like wet bread under your foot. Something below them scuttles away.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970203, '(Soft, black, and best not stood on.)', '(Soft, black, and best not stood on.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970204, '(A bucket half full of brown water. Above it, a hand\'s width of gray Tirisfal sky shows between the rafters. A drop lands on your shoulder as you look.)', '(A bucket half full of brown water. Above it, a hand\'s width of gray Tirisfal sky shows between the rafters. A drop lands on your shoulder as you look.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970205, '(Drip. Drip. Drip.)', '(Drip. Drip. Drip.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970206, '(The hatch lifts on a breath of cold, wet air. Steps go down into the dark. From somewhere below comes a slow, patient gnawing.)', '(The hatch lifts on a breath of cold, wet air. Steps go down into the dark. From somewhere below comes a slow, patient gnawing.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970207, '(At the back of the cellar, behind the rats\' nest, the earth wall gives way to brick: newer brick, laid in a hurry. Scratched into the mortar is a sign you half recognize, a circle bound in thorned lines, the kind painted on the doors of the Cult of the Damned. The air coming through the gaps is colder than the cellar\'s.)', '(At the back of the cellar, behind the rats\' nest, the earth wall gives way to brick: newer brick, laid in a hurry. Scratched into the mortar is a sign you half recognize, a circle bound in thorned lines, the kind painted on the doors of the Cult of the Damned. The air coming through the gaps is colder than the cellar\'s.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970208, '(Steps down into the cellar. At the back, the bricked-up wall and its mark.)', '(Steps down into the cellar. At the back, the bricked-up wall and its mark.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970209, '(Steps down into a damp cellar.)', '(Steps down into a damp cellar.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970210, '(Letters tied with twine, all to Cedric, all from Elinor. The early ones are about the harvest and the boys and a dog called Bramble. The last is short:$B$B\'They say the grain from Andorhal is bad. Owen is coughing. Come home quickly. I don\'t like how quiet the road is.\'$B$BShe never wrote again.)', '(Letters tied with twine, all to Cedric, all from Elinor. The early ones are about the harvest and the boys and a dog called Bramble. The last is short:$B$B\'They say the grain from Andorhal is bad. Owen is coughing. Come home quickly. I don\'t like how quiet the road is.\'$B$BShe never wrote again.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970211, '(Elinor\'s letters to Cedric, tied with twine.)', '(Elinor\'s letters to Cedric, tied with twine.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970212, '(A painted family in a farmhouse doorway: a broad, smiling man you\'d never guess was Cedric, a woman with flour on her apron, two boys, one missing a front tooth, and a shaggy gray dog sitting on everyone\'s feet. The doorway is this one.)', '(A painted family in a farmhouse doorway: a broad, smiling man you\'d never guess was Cedric, a woman with flour on her apron, two boys, one missing a front tooth, and a shaggy gray dog sitting on everyone\'s feet. The doorway is this one.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970213, '(The Harrows, in this doorway, before.)', '(The Harrows, in this doorway, before.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970214, '(The lock is rusted through; it gives under your hands. Inside: a woman\'s ring wrapped in a handkerchief, a row of carved wooden soldiers, and a child\'s drawing of a dog, labelled BRAMBLE in large uneven letters.)', '(The lock is rusted through; it gives under your hands. Inside: a woman\'s ring wrapped in a handkerchief, a row of carved wooden soldiers, and a child\'s drawing of a dog, labelled BRAMBLE in large uneven letters.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970215, '(The boys\' wooden soldiers, standing guard.)', '(The boys\' wooden soldiers, standing guard.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970216, '(Under the rotten top layer, the oak is still sound. You take as much as you can carry.)', '(Under the rotten top layer, the oak is still sound. You take as much as you can carry.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970217, '(Old timber from a house nobody lives in.)', '(Old timber from a house nobody lives in.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970218, '(Nails by the hundred, rusty but straight. Someone sorted them by size, once.)', '(Nails by the hundred, rusty but straight. Someone sorted them by size, once.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970219, '(A bucket of rusty nails.)', '(A bucket of rusty nails.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970220, 'Oswin Thatcher, carpenter. I\'ve been sent to fix a house for somebody who hasn\'t got it yet. Jamie Nore\'s idea of planning.', 'Oswin Thatcher, carpenter. I\'ve been sent to fix a house for somebody who hasn\'t got it yet. Jamie Nore\'s idea of planning.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970221, 'Two cups by the stove now, and somebody\'s alchemy things on the windowsill. Are some of these furnishings for her, then? I\'ll make the chair arms wider. She looks like the sort who sits and watches.', 'Two cups by the stove now, and somebody\'s alchemy things on the windowsill. Are some of these furnishings for her, then? I\'ll make the chair arms wider. She looks like the sort who sits and watches.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970222, 'The alchemist swept my sawdust out of the door this morning before I\'d finished making it. Tidy. Very tidy. She asked me which boards creak. I told her. I\'m still not sure I should have.', 'The alchemist swept my sawdust out of the door this morning before I\'d finished making it. Tidy. Very tidy. She asked me which boards creak. I told her. I\'m still not sure I should have.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`, `text1_0`, `text1_1`, `Probability1`, `text2_0`, `text2_1`, `Probability2`) VALUES (970223, 'Still standing. The roof, I mean. And me, more or less.', 'Still standing. The roof, I mean. And me, more or less.', 0.333, 'Jamie\'s got me fixing the town hall\'s shutters next. Then the inn\'s stairs. Brill was falling down for years and nobody noticed, because nobody here expects anything to stay up.', 'Jamie\'s got me fixing the town hall\'s shutters next. Then the inn\'s stairs. Brill was falling down for years and nobody noticed, because nobody here expects anything to stay up.', 0.333, 'If you want anything else built, $n, ask. I\'ve grown fond of your house. Don\'t tell it.', 'If you want anything else built, $n, ask. I\'ve grown fond of your house. Don\'t tell it.', 0.333);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970224, 'I mortared the gap twice. Some nights I\'d swear I hear it breathing through the brick. Probably the wind.', 'I mortared the gap twice. Some nights I\'d swear I hear it breathing through the brick. Probably the wind.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970225, 'Oswin Thatcher, carpenter. Mind the boards. Mind the roof. Mind everything, really; this house has been left to itself a long time.', 'Oswin Thatcher, carpenter. Mind the boards. Mind the roof. Mind everything, really; this house has been left to itself a long time.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970226, 'So the alchemist lives here now. She poured my cider back into the jug and gave me tea instead. I didn\'t argue. Nobody argues with Carolai.', 'So the alchemist lives here now. She poured my cider back into the jug and gave me tea instead. I didn\'t argue. Nobody argues with Carolai.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`, `text1_0`, `text1_1`, `Probability1`, `text2_0`, `text2_1`, `Probability2`) VALUES (970227, 'I brought cider. Can\'t taste it. Neither can half of us. We drink it anyway; it\'s the ceremony that counts.', 'I brought cider. Can\'t taste it. Neither can half of us. We drink it anyway; it\'s the ceremony that counts.', 0.333, 'My inn\'s never been this quiet, and your house has never been this loud. Seems fair.', 'My inn\'s never been this quiet, and your house has never been this loud. Seems fair.', 0.333, 'When I was alive I used to bake for every new house on the lane. I\'ve forgotten how. I remember the smell, though.', 'When I was alive I used to bake for every new house on the lane. I\'ve forgotten how. I remember the smell, though.', 0.333);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`, `text1_0`, `text1_1`, `Probability1`, `text2_0`, `text2_1`, `Probability2`) VALUES (970228, 'I\'ve brought you a tincture for the damp. Rub it on the walls, not yourself. Probably not yourself.', 'I\'ve brought you a tincture for the damp. Rub it on the walls, not yourself. Probably not yourself.', 0.333, 'A very reasonable house. Solid. Uncontaminated. I\'m almost disappointed.', 'A very reasonable house. Solid. Uncontaminated. I\'m almost disappointed.', 0.333, 'Your carpenter has opinions about rot. I have more. We\'ve been discussing it for an hour. It\'s been delightful.', 'Your carpenter has opinions about rot. I have more. We\'ve been discussing it for an hour. It\'s been delightful.', 0.333);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970229, 'That dog of yours keeps sniffing my mushrooms. Smart dog. He knows the good ones.', 'That dog of yours keeps sniffing my mushrooms. Smart dog. He knows the good ones.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`, `text1_0`, `text1_1`, `Probability1`, `text2_0`, `text2_1`, `Probability2`) VALUES (970230, 'Mushrooms! I brought mushrooms. A housewarming gift. Plant them in the cellar; they\'ll love it down there.', 'Mushrooms! I brought mushrooms. A housewarming gift. Plant them in the cellar; they\'ll love it down there.', 0.333, 'We\'re neighbors now. If you hear me singing to the crops at night, that\'s normal. It helps them grow.', 'We\'re neighbors now. If you hear me singing to the crops at night, that\'s normal. It helps them grow.', 0.333, 'Lovely house. Smells of fresh oak. Mine smells of mushrooms. Everything I own smells of mushrooms.', 'Lovely house. Smells of fresh oak. Mine smells of mushrooms. Everything I own smells of mushrooms.', 0.333);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`, `text1_0`, `text1_1`, `Probability1`, `text2_0`, `text2_1`, `Probability2`) VALUES (970231, 'I\'m not on duty. I\'m standing by the door because that\'s where I stand.', 'I\'m not on duty. I\'m standing by the door because that\'s where I stand.', 0.333, 'The Deathguard know which house this is now. Nothing comes up that road without us seeing it.', 'The Deathguard know which house this is now. Nothing comes up that road without us seeing it.', 0.333, 'It\'s good, this. People in a room together who aren\'t afraid of each other. Don\'t tell Zygand I said so.', 'It\'s good, this. People in a room together who aren\'t afraid of each other. Don\'t tell Zygand I said so.', 0.333);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970300, '(Under the moss: MERCY HOLLIN, MIDWIFE OF THIS PARISH. SHE BROUGHT US IN. Someone has scratched a second date beneath the first, more recently, and then scratched it out.)', '(Under the moss: MERCY HOLLIN, MIDWIFE OF THIS PARISH. SHE BROUGHT US IN. Someone has scratched a second date beneath the first, more recently, and then scratched it out.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970301, '(A weathered stone, its name half lost to moss: HOLLIN.)', '(A weathered stone, its name half lost to moss: HOLLIN.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970302, '(A fine stone, better cut than its neighbors: TOBIAS CRANE, MASON. The lettering is his own style; he must have cut it for himself, in case. The earth in front of it is soft.)', '(A fine stone, better cut than its neighbors: TOBIAS CRANE, MASON. The lettering is his own style; he must have cut it for himself, in case. The earth in front of it is soft.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970303, '(A well-cut stone: TOBIAS CRANE, MASON.)', '(A well-cut stone: TOBIAS CRANE, MASON.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970304, '(AGNES FAIRWEATHER, BELOVED WIFE. Beside it, a smaller stone with no name at all, only a year. The plague year.)', '(AGNES FAIRWEATHER, BELOVED WIFE. Beside it, a smaller stone with no name at all, only a year. The plague year.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970305, '(AGNES FAIRWEATHER, BELOVED WIFE.)', '(AGNES FAIRWEATHER, BELOVED WIFE.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970306, '(WALTER SHAW, BRILL MILITIA. HE STOOD. Below, in a cruder hand cut later with a knife: AND STOOD AGAIN. Someone in the Deathguard had a sense of humor.)', '(WALTER SHAW, BRILL MILITIA. HE STOOD. Below, in a cruder hand cut later with a knife: AND STOOD AGAIN. Someone in the Deathguard had a sense of humor.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970307, '(WALTER SHAW, BRILL MILITIA. HE STOOD.)', '(WALTER SHAW, BRILL MILITIA. HE STOOD.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970308, '(HESTER LYLE AND HER CHILDREN. The stone is old and the grave undisturbed; but the Lyles\' names are in the ledger only once, and the note beside them reads \'remains not recovered. Family vault?\')', '(HESTER LYLE AND HER CHILDREN. The stone is old and the grave undisturbed; but the Lyles\' names are in the ledger only once, and the note beside them reads \'remains not recovered. Family vault?\')', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970309, '(HESTER LYLE AND HER CHILDREN.)', '(HESTER LYLE AND HER CHILDREN.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970310, '(The earth was put back with care, and patted flat. Half buried at the head of the grave, a spade with a broken haft: burned into the handle, the cemetery\'s own mark.)', '(The earth was put back with care, and patted flat. Half buried at the head of the grave, a spade with a broken haft: burned into the handle, the cemetery\'s own mark.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970311, '(Freshly turned earth, patted flat.)', '(Freshly turned earth, patted flat.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970312, '(A coffin lid lies under a thin layer of soil. Its underside is gouged with long scratches. Its nails were drawn from outside, neatly, and lie in a little pile beside it, as if someone meant to put them back.)', '(A coffin lid lies under a thin layer of soil. Its underside is gouged with long scratches. Its nails were drawn from outside, neatly, and lie in a little pile beside it, as if someone meant to put them back.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970313, '(Freshly turned earth.)', '(Freshly turned earth.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970314, '(Strips of an old shroud, torn into bandages and knotted, as you\'d wrap someone\'s hands. No gnoll tracks. Two sets of bare footprints, one dragging, heading east.)', '(Strips of an old shroud, torn into bandages and knotted, as you\'d wrap someone\'s hands. No gnoll tracks. Two sets of bare footprints, one dragging, heading east.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970315, '(Freshly turned earth.)', '(Freshly turned earth.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970316, '(Clods of cemetery clay, darker than the earth here, scattered where someone stopped to rest. The furrow goes on, uphill, toward the trees.)', '(Clods of cemetery clay, darker than the earth here, scattered where someone stopped to rest. The furrow goes on, uphill, toward the trees.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970317, '(A scatter of dark clay in the grass.)', '(A scatter of dark clay in the grass.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970318, '(Under a fallen beam by the cold forge, a rolled leather case: chisels, a mallet, a mason\'s square. T.C. burned into the leather. The steel is rusted, but it will clean.)', '(Under a fallen beam by the cold forge, a rolled leather case: chisels, a mallet, a mason\'s square. T.C. burned into the leather. The steel is rusted, but it will clean.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970319, '(A rotting leather tool roll, under a fallen beam.)', '(A rotting leather tool roll, under a fallen beam.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970320, '(A small basket, the willow gone grey. Inside: a bone spindle whorl, a needle case, and a child\'s bonnet, finished but for the ribbon.)', '(A small basket, the willow gone grey. Inside: a bone spindle whorl, a needle case, and a child\'s bonnet, finished but for the ribbon.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970321, '(An old sewing basket, the willow gone grey.)', '(An old sewing basket, the willow gone grey.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970322, '(A dented tin box the gnolls didn\'t think worth chewing. Inside, letters tied with string, every one beginning \'Dear Da\' in a careful round hand. The last is dated the week the plague came to Brill.)', '(A dented tin box the gnolls didn\'t think worth chewing. Inside, letters tied with string, every one beginning \'Dear Da\' in a careful round hand. The last is dated the week the plague came to Brill.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970323, '(A dented tin box, rusted shut.)', '(A dented tin box, rusted shut.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970324, 'Thirty-one names, $n. Thirty-one people Croft kept in the dark and wrote down like stock. They\'re in my ledger now, in the right column. I ring the bell at dusk for one of them each night. It\'ll take a month. I find I don\'t mind the work.', 'Thirty-one names, $n. Thirty-one people Croft kept in the dark and wrote down like stock. They\'re in my ledger now, in the right column. I ring the bell at dusk for one of them each night. It\'ll take a month. I find I don\'t mind the work.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970325, 'Isolde\'s message said you\'d bring me something from under the hill. I\'ve cleared a page for it. I hope a page is enough.', 'Isolde\'s message said you\'d bring me something from under the hill. I\'ve cleared a page for it. I hope a page is enough.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970326, 'You\'ve been below. I can smell it on you: old candle-fat and older stone. Whatever\'s down there, $n, mind you come back up the same as you went. Some don\'t.', 'You\'ve been below. I can smell it on you: old candle-fat and older stone. Whatever\'s down there, $n, mind you come back up the same as you went. Some don\'t.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970327, 'Mercy walked through the market at noon yesterday and bought a loaf she can\'t eat, just to be seen paying for it. Half of Brill stared. The other half asked if she still delivers. She does, it seems: she\'s been at the Apothecary\'s door asking after foals and calves.$B$BThe ledger balances, $n. First time in years.', 'Mercy walked through the market at noon yesterday and bought a loaf she can\'t eat, just to be seen paying for it. Half of Brill stared. The other half asked if she still delivers. She does, it seems: she\'s been at the Apothecary\'s door asking after foals and calves.$B$BThe ledger balances, $n. First time in years.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970328, 'They came in by night, as we agreed, and the Deathguard had their orders. No torches. A week on, and the baker\'s boy still doesn\'t know Edith was ever under my lawn. That\'s how she wants it.$B$BThe ledger balances, $n. First time in years.', 'They came in by night, as we agreed, and the Deathguard had their orders. No torches. A week on, and the baker\'s boy still doesn\'t know Edith was ever under my lawn. That\'s how she wants it.$B$BThe ledger balances, $n. First time in years.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970329, 'A carpenter came by asking whether the parish books mention a mark like a crowned eye. They don\'t. But a Deathstalker asked me the very same thing a month ago, and she\'s been standing by the mausoleum ever since, pretending to admire it.', 'A carpenter came by asking whether the parish books mention a mark like a crowned eye. They don\'t. But a Deathstalker asked me the very same thing a month ago, and she\'s been standing by the mausoleum ever since, pretending to admire it.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970330, 'The bell\'s rope is new. I bought it myself. Have you been to the Magistrate?', 'The bell\'s rope is new. I bought it myself. Have you been to the Magistrate?', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970331, 'I\'ve kept the Deathguard away from the east hill. Told them it\'s adders. Don\'t make a liar of me.', 'I\'ve kept the Deathguard away from the east hill. Told them it\'s adders. Don\'t make a liar of me.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970332, 'North side, by the yew. Mind the soft ground; it isn\'t always empty.', 'North side, by the yew. Mind the soft ground; it isn\'t always empty.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970333, 'The ledgers are in a terrible state. I kept them dry for twenty years and then I died and nobody else did. Shall we?', 'The ledgers are in a terrible state. I kept them dry for twenty years and then I died and nobody else did. Shall we?', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`, `text1_0`, `text1_1`, `Probability1`, `text2_0`, `text2_1`, `Probability2`) VALUES (970334, 'Mind the newer stones. The ground hasn\'t settled. Neither, some days, have the residents.', 'Mind the newer stones. The ground hasn\'t settled. Neither, some days, have the residents.', 0.333, 'Every name in this ground was someone\'s. I keep them straight. It\'s the least I can do, and in this town the least is a great deal.', 'Every name in this ground was someone\'s. I keep them straight. It\'s the least I can do, and in this town the least is a great deal.', 0.333, 'You\'re welcome to walk here. Only don\'t stand on anyone. They notice, these days.', 'You\'re welcome to walk here. Only don\'t stand on anyone. They notice, these days.', 0.333);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970335, 'I come back up here some nights. It was the first place I was myself. Brill is louder than I remember.', 'I come back up here some nights. It was the first place I was myself. Brill is louder than I remember.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970336, 'Keep your voice low. Walter startles.', 'Keep your voice low. Walter startles.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970337, '(A grey-faced woman blocks the mausoleum door with a spade held like a pike.)$B$BThis is a tomb. There\'s nothing for you here. Go on.', '(A grey-faced woman blocks the mausoleum door with a spade held like a pike.)$B$BThis is a tomb. There\'s nothing for you here. Go on.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970338, 'Mercy\'s up here again, is she? I came to fix the hinge. Somebody should. It kept us.', 'Mercy\'s up here again, is she? I came to fix the hinge. Somebody should. It kept us.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970339, 'I cut half the stones down in that cemetery, you know. Mine too. Didn\'t expect to read it from the wrong side.', 'I cut half the stones down in that cemetery, you know. Mine too. Didn\'t expect to read it from the wrong side.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970340, 'It\'s quiet up here. I come to sew when the town gets to be too much.', 'It\'s quiet up here. I come to sew when the town gets to be too much.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970341, 'Please don\'t look at my face. I know what\'s left of it. I\'d... rather you looked at my hands. They still know how to work.', 'Please don\'t look at my face. I know what\'s left of it. I\'d... rather you looked at my hands. They still know how to work.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970342, 'Don\'t tell the others I come up here. I like the view. From up here Brill looks like it did.', 'Don\'t tell the others I come up here. I like the view. From up here Brill looks like it did.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970343, 'I remember the Deathguard\'s blade going in. I remember being grateful. Then I woke up again. Do you understand? I don\'t want to be grateful twice.', 'I remember the Deathguard\'s blade going in. I remember being grateful. Then I woke up again. Do you understand? I don\'t want to be grateful twice.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970344, 'Junior Apothecary Holland asked me to look at a sow that\'s farrowing. I said I\'ve never midwifed a pig. He said nor has anyone in Brill, these days. So I\'m going.', 'Junior Apothecary Holland asked me to look at a sow that\'s farrowing. I said I\'ve never midwifed a pig. He said nor has anyone in Brill, these days. So I\'m going.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970345, '$n! Come and be seen with me. The more people I\'m seen talking to, the fewer think I\'m here to eat them.', '$n! Come and be seen with me. The more people I\'m seen talking to, the fewer think I\'m here to eat them.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970346, 'I help Mortimer with the graves, nights. I listen for knocking. There hasn\'t been any since. I keep listening.', 'I help Mortimer with the graves, nights. I listen for knocking. There hasn\'t been any since. I keep listening.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970347, 'Isolde told me what was behind my wall. I\'ll be cutting thirty-one stones this winter, $n. I\'ll do them properly. It\'s what I owe.', 'Isolde told me what was behind my wall. I\'ll be cutting thirty-one stones this winter, $n. I\'ll do them properly. It\'s what I owe.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970348, 'Three commissions already. Forsaken want their names on something solid. I understand the feeling.', 'Three commissions already. Forsaken want their names on something solid. I understand the feeling.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970349, 'I mend what the Deathguard break. Steps, walls, the odd gravestone. Nobody asks where I learned. Suits me.', 'I mend what the Deathguard break. Steps, walls, the odd gravestone. Nobody asks where I learned. Suits me.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970350, 'Nobody knows. They think I came down from the Undercity for the quiet. I let them. I\'m making shrouds for Mortimer, good ones. Somebody ought to be buried properly.', 'Nobody knows. They think I came down from the Undercity for the quiet. I let them. I\'m making shrouds for Mortimer, good ones. Somebody ought to be buried properly.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970351, 'A girl from the inn stared at me for a whole afternoon, and then asked me to teach her to spin. I almost ran. I didn\'t.', 'A girl from the inn stared at me for a whole afternoon, and then asked me to teach her to spin. I almost ran. I didn\'t.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970352, 'Dillinger offered me a tabard. Me, in the Deathguard. After what they did. ...I\'m thinking about it. Somebody ought to remember what it\'s like on the other end.', 'Dillinger offered me a tabard. Me, in the Deathguard. After what they did. ...I\'m thinking about it. Somebody ought to remember what it\'s like on the other end.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970353, 'I stand the north road at night. Nobody asked me to. Old habit. Brill militia: he stood.', 'I stand the north road at night. Nobody asked me to. Old habit. Brill militia: he stood.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970400, '(The padlock is new, and the hasp has been oiled. Through a gap between the boards you make out a small copper still, a row of stoppered jars, and a slate covered in chalk figures. The smell is embalming salts, and under it something sweet and cloying, like lilies left too long on a coffin.$B$BBeside the door, empty crates are stacked, a carter\'s tag still tied to one: \'Solliden. Leave by the trough. No questions.\')', '(The padlock is new, and the hasp has been oiled. Through a gap between the boards you make out a small copper still, a row of stoppered jars, and a slate covered in chalk figures. The smell is embalming salts, and under it something sweet and cloying, like lilies left too long on a coffin.$B$BBeside the door, empty crates are stacked, a carter\'s tag still tied to one: \'Solliden. Leave by the trough. No questions.\')', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970401, '(The shed is empty and scrubbed. The boards nearest the door are scorched black, and the still is gone.)', '(The shed is empty and scrubbed. The boards nearest the door are scorched black, and the still is gone.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970402, '(The padlock is gone. Inside, the still has been cleaned and labeled in two hands: one small and careful, one sharp and impatient. A ledger hangs from a nail, every dose initialed twice.)', '(The padlock is gone. Inside, the still has been cleaned and labeled in two hands: one small and careful, one sharp and impatient. A ledger hangs from a nail, every dose initialed twice.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970403, '(The shed is bare. The still, the jars and the slate have been crated and carted to the Undercity under the Royal Apothecary Society\'s seal. Only the smell of lilies remains.)', '(The shed is bare. The still, the jars and the slate have been crated and carted to the Undercity under the Royal Apothecary Society\'s seal. Only the smell of lilies remains.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970404, '(A storage shed, padlocked. A faint smell seeps between the boards: embalming salts, and something sweet.)', '(A storage shed, padlocked. A faint smell seeps between the boards: embalming salts, and something sweet.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970405, '(A crate stamped with the Royal Apothecary Society\'s seal, its lid pried off and nailed back down. Inside are embalming salts, a jar of grave moss, and flasks of bile packed in straw, every one with Johaan\'s inventory mark. You take it.)', '(A crate stamped with the Royal Apothecary Society\'s seal, its lid pried off and nailed back down. Inside are embalming salts, a jar of grave moss, and flasks of bile packed in straw, every one with Johaan\'s inventory mark. You take it.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970406, '(An empty crate with the Royal Apothecary Society\'s seal.)', '(An empty crate with the Royal Apothecary Society\'s seal.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970407, '(A handcart under an oilcloth, one wheel sunk in the mud. In it are two crates, already empty, and a bundle of delivery slips tied with string. Every slip is for the same order: salts, moss, bile, and a quantity of lily oil, \'for the smell.\' Each is signed in a small, careful hand, the letters leaning as if the writer\'s fingers did not quite close:$B$BW. Crane.)', '(A handcart under an oilcloth, one wheel sunk in the mud. In it are two crates, already empty, and a bundle of delivery slips tied with string. Every slip is for the same order: salts, moss, bile, and a quantity of lily oil, \'for the smell.\' Each is signed in a small, careful hand, the letters leaning as if the writer\'s fingers did not quite close:$B$BW. Crane.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970408, '(A handcart, sunk in the mud. Nobody has come back for it.)', '(A handcart, sunk in the mud. Nobody has come back for it.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970409, '(The darkhound in this cage lifts its head as you come near. Where the wasting sickness ought to have eaten its flank, the skin has closed over, pale and smooth as candle wax. Its fur has stopped falling. It whines and pushes its muzzle through the bars, looking for more of the meat.$B$BIt is the healthiest diseased animal you have ever seen.)', '(The darkhound in this cage lifts its head as you come near. Where the wasting sickness ought to have eaten its flank, the skin has closed over, pale and smooth as candle wax. Its fur has stopped falling. It whines and pushes its muzzle through the bars, looking for more of the meat.$B$BIt is the healthiest diseased animal you have ever seen.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970410, '(A cage, empty now. Someone has left a bowl of clean water in it.)', '(A cage, empty now. Someone has left a bowl of clean water in it.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970411, '(This darkhound is alive, but it barely moves. Its sores are gone, and so is any give in its flesh: the muscles of its legs have set as hard as cured leather, and its joints creak when it tries to rise. It manages a single step, then lies down again, panting, its eyes following you.$B$BPreserved, you think. Like meat in salt.)', '(This darkhound is alive, but it barely moves. Its sores are gone, and so is any give in its flesh: the muscles of its legs have set as hard as cured leather, and its joints creak when it tries to rise. It manages a single step, then lies down again, panting, its eyes following you.$B$BPreserved, you think. Like meat in salt.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970412, '(A cage, empty now.)', '(A cage, empty now.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970413, '(The third cage has been torn open from the inside. The bars are bent outward and smeared with dark, waxy blood. Tracks lead off into the brush. Close by, something snarls, too loud and too long, as if it has forgotten how to stop.)', '(The third cage has been torn open from the inside. The bars are bent outward and smeared with dark, waxy blood. Tracks lead off into the brush. Close by, something snarls, too loud and too long, as if it has forgotten how to stop.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970414, '(A cage with its bars bent outward.)', '(A cage with its bars bent outward.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`, `text1_0`, `text1_1`, `Probability1`, `text2_0`, `text2_1`, `Probability2`) VALUES (970415, 'Faranell wrote back. Three pages of questions and no thanks, and Crane\'s name misspelled twice. Still, the Society has it now, and the Society forgets nothing it can use.', 'Faranell wrote back. Three pages of questions and no thanks, and Crane\'s name misspelled twice. Still, the Society has it now, and the Society forgets nothing it can use.', 0.333, 'Crane says \'yes, Apothecary\' again. I had grown used to the alternative. I find I do not care for the old way as much.', 'Crane says \'yes, Apothecary\' again. I had grown used to the alternative. I find I do not care for the old way as much.', 0.333, 'A courier from the Undercity asked whether I had any other apprentices with ideas. I told him no. It was very nearly true.', 'A courier from the Undercity asked whether I had any other apprentices with ideas. I told him no. It was very nearly true.', 0.333);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`, `text1_0`, `text1_1`, `Probability1`, `text2_0`, `text2_1`, `Probability2`) VALUES (970416, 'Crane is at his bench before I am at mine. I have caught him humming. It is deeply unsettling.', 'Crane is at his bench before I am at mine. I have caught him humming. It is deeply unsettling.', 0.333, 'I check his ledger every night. He checks mine. It is like having a conscience, only one that washes glassware.', 'I check his ledger every night. He checks mine. It is like having a conscience, only one that washes glassware.', 0.333, 'The healed hound is still alive. I have named it Exhibit A. Crane calls it something else, and I have chosen not to hear what.', 'The healed hound is still alive. I have named it Exhibit A. Crane calls it something else, and I have chosen not to hear what.', 0.333);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`, `text1_0`, `text1_1`, `Probability1`, `text2_0`, `text2_1`, `Probability2`) VALUES (970417, 'There is still a black patch behind the shed. I haven\'t had it raked. A reminder is cheaper than a sermon.', 'There is still a black patch behind the shed. I haven\'t had it raked. A reminder is cheaper than a sermon.', 0.333, 'Crane scrubs the floors now as well as the glass. I did not ask him to. I have not asked him to stop.', 'Crane scrubs the floors now as well as the glass. I did not ask him to. I have not asked him to stop.', 0.333, 'Some nights I think of what was in those notes. Then I think of the third hound. Then I go back to work.', 'Some nights I think of what was in those notes. Then I think of the third hound. Then I go back to work.', 0.333);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970418, 'Solliden, $n. My salts, and a name. My inventories do not lie. People do. Go and find out which of them.', 'Solliden, $n. My salts, and a name. My inventories do not lie. People do. Go and find out which of them.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970419, 'Holland says the cask under the bridge was Society work, from before my time. I checked the batch numbers anyway. Twice. I wanted to be sure it wasn\'t mine. I suppose nothing is mine now.', 'Holland says the cask under the bridge was Society work, from before my time. I checked the batch numbers anyway. Twice. I wanted to be sure it wasn\'t mine. I suppose nothing is mine now.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970420, 'Johaan has me boiling every drop I use, since the well. He says if we\'re going to keep bodies whole we\'ll start by not poisoning them. I\'ve written that on the wall.', 'Johaan has me boiling every drop I use, since the well. He says if we\'re going to keep bodies whole we\'ll start by not poisoning them. I\'ve written that on the wall.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`, `text1_0`, `text1_1`, `Probability1`, `text2_0`, `text2_1`, `Probability2`) VALUES (970421, 'The Society sent a courier for the notes. He read the first page on the doorstep and laughed. I don\'t know what that means. I\'ve decided not to find out.', 'The Society sent a courier for the notes. He read the first page on the doorstep and laughed. I don\'t know what that means. I\'ve decided not to find out.', 0.333, 'Yes, Apothecary. No, Apothecary. I\'ve had a great deal of practice.', 'Yes, Apothecary. No, Apothecary. I\'ve had a great deal of practice.', 0.333, 'Maybe they\'ll make something of it. Maybe in ten years some Deathguard will keep his fingers and never know my name. That would be all right. I think that would be all right.', 'Maybe they\'ll make something of it. Maybe in ten years some Deathguard will keep his fingers and never know my name. That would be all right. I think that would be all right.', 0.333);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`, `text1_0`, `text1_1`, `Probability1`, `text2_0`, `text2_1`, `Probability2`) VALUES (970422, 'Johaan checks every dose twice. He says it\'s to keep me honest. I think he\'s interested. Don\'t tell him I said so.', 'Johaan checks every dose twice. He says it\'s to keep me honest. I think he\'s interested. Don\'t tell him I said so.', 0.333, 'Small doses, slow work, everything written down. It\'ll take years. We have years. It\'s the one thing we have plenty of.', 'Small doses, slow work, everything written down. It\'ll take years. We have years. It\'s the one thing we have plenty of.', 0.333, 'The hound\'s flank is still closed. Six weeks now. I go out and look at it every morning, like it\'s the sunrise.', 'The hound\'s flank is still closed. Six weeks now. I go out and look at it every morning, like it\'s the sunrise.', 0.333);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`, `text1_0`, `text1_1`, `Probability1`, `text2_0`, `text2_1`, `Probability2`) VALUES (970423, 'I scrub the floors now as well as the glass. Johaan says it\'s so I\'ll know what\'s under my feet. I think it\'s so I won\'t have time to think.', 'I scrub the floors now as well as the glass. Johaan says it\'s so I\'ll know what\'s under my feet. I think it\'s so I won\'t have time to think.', 0.333, 'It was right to burn it. I tell myself so every morning, when I count my fingers.', 'It was right to burn it. I tell myself so every morning, when I count my fingers.', 0.333, 'The healed hound got out of its cage, they tell me. Somewhere in the hills there\'s one darkhound that won\'t rot. Good luck to it.', 'The healed hound got out of its cage, they tell me. Somewhere in the hills there\'s one darkhound that won\'t rot. Good luck to it.', 0.333);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970424, 'You have the notes. Don\'t read me what you\'ve decided. I\'ll find out from his face.', 'You have the notes. Don\'t read me what you\'ve decided. I\'ll find out from his face.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970425, 'You\'ve seen the hand. You might as well see the rest.', 'You\'ve seen the hand. You might as well see the rest.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970426, 'The shed? It\'s... storage. For storage things. I wouldn\'t go near it, the smell\'s awful. You\'ll want to stay upwind. Far upwind.', 'The shed? It\'s... storage. For storage things. I wouldn\'t go near it, the smell\'s awful. You\'ll want to stay upwind. Far upwind.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`, `text1_0`, `text1_1`, `Probability1`, `text2_0`, `text2_1`, `Probability2`) VALUES (970427, 'Apprentice Crane. If you\'re looking for the Apothecary, he\'s inside, being disappointed in something.', 'Apprentice Crane. If you\'re looking for the Apothecary, he\'s inside, being disappointed in something.', 0.333, 'Mind the basin; it\'s lye. It\'ll take the skin off. Not that I\'d notice for a day or two.', 'Mind the basin; it\'s lye. It\'ll take the skin off. Not that I\'d notice for a day or two.', 0.333, 'Three years I\'ve washed his glass. I could tell you every crack in every retort in Brill. Nobody asks.', 'Three years I\'ve washed his glass. I could tell you every crack in every retort in Brill. Nobody asks.', 0.333);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970500, '(You scatter the coals with your boot and pull out a curl of half-burnt paper. Most of it is ash, but the lines along the bottom survive: "...the north road stands empty until the lamps are lit. The Light keep you, Brother. I count the days.")', '(You scatter the coals with your boot and pull out a curl of half-burnt paper. Most of it is ash, but the lines along the bottom survive: "...the north road stands empty until the lamps are lit. The Light keep you, Brother. I count the days.")', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970501, '(Ash and the stink of burnt vellum. Someone sat here a long time, waiting to be sure every page had caught.)', '(Ash and the stink of burnt vellum. Someone sat here a long time, waiting to be sure every page had caught.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970502, '(Two cobbles at the foot of the well lift out too easily. Beneath them is a dry hollow, lined with oilcloth, just large enough for a folded letter. It\'s empty now, but it smells faintly of lamp oil.)', '(Two cobbles at the foot of the well lift out too easily. Beneath them is a dry hollow, lined with oilcloth, just large enough for a folded letter. It\'s empty now, but it smells faintly of lamp oil.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970503, '(Loose cobbles at the foot of the well. Someone has pressed them back very neatly.)', '(Loose cobbles at the foot of the well. Someone has pressed them back very neatly.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970504, '(Behind the Gallows\' End, out of a patrol\'s sight, a crate bears a small chalk mark: a circle with a line through it, the sign Scarlet scouts use to say "message waiting." The chalk is fresh. It was drawn by someone tall enough to reach the top of the stack without climbing.)', '(Behind the Gallows\' End, out of a patrol\'s sight, a crate bears a small chalk mark: a circle with a line through it, the sign Scarlet scouts use to say "message waiting." The chalk is fresh. It was drawn by someone tall enough to reach the top of the stack without climbing.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970505, '(Someone has scrubbed the chalk mark from the crate. Badly.)', '(Someone has scrubbed the chalk mark from the crate. Badly.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970506, '(An old crate behind the tavern, damp and smelling of cheap ale.)', '(An old crate behind the tavern, damp and smelling of cheap ale.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970507, '(You lift the lamp\'s glass. Scratched into the brass beneath it, where only someone who cleans it would ever look, is a small tally mark, and beside it a date. The scratches are neat and deliberate, as if the hand that made them wanted to remember each one.)', '(You lift the lamp\'s glass. Scratched into the brass beneath it, where only someone who cleans it would ever look, is a small tally mark, and beside it a date. The scratches are neat and deliberate, as if the hand that made them wanted to remember each one.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970508, '(The lamp is unlit. Brill has not yet found a new lamplighter.)', '(The lamp is unlit. Brill has not yet found a new lamplighter.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970509, '(A street lamp, its glass polished clean. Someone takes good care of these.)', '(A street lamp, its glass polished clean. Someone takes good care of these.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`, `text1_0`, `text1_1`, `Probability1`, `text2_0`, `text2_1`, `Probability2`) VALUES (970510, 'I went to Apothecary Johaan yesterday and asked him straight out: can it be undone? He laughed at me, then he stopped laughing and gave me a very long answer that meant no. It helped, oddly. A door shut properly is easier than one left ajar.', 'I went to Apothecary Johaan yesterday and asked him straight out: can it be undone? He laughed at me, then he stopped laughing and gave me a very long answer that meant no. It helped, oddly. A door shut properly is easier than one left ajar.', 0.333, 'Deathguard Cyrus asked after my knees today. My knees! I\'ve no feeling in them since the plague. But he asked. I think that\'s what Anselm was really selling me, $n. Someone who\'d ask.', 'Deathguard Cyrus asked after my knees today. My knees! I\'ve no feeling in them since the plague. But he asked. I think that\'s what Anselm was really selling me, $n. Someone who\'d ask.', 0.333, 'A letter came from Brother Anselm. I put it in the lamp on the north road and lit it with the rest. Best light that lamp\'s ever given.', 'A letter came from Brother Anselm. I put it in the lamp on the north road and lit it with the rest. Best light that lamp\'s ever given.', 0.333);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970511, 'Evening, $n. The lamps are lit on time and no one knows when but the watch. (He polishes a lamp-glass, and doesn\'t quite meet your eyes.) I\'m trying. That\'s all I can say. I\'m trying.', 'Evening, $n. The lamps are lit on time and no one knows when but the watch. (He polishes a lamp-glass, and doesn\'t quite meet your eyes.) I\'m trying. That\'s all I can say. I\'m trying.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970512, '(Lowell\'s hands are shaking around the lamp-pole.) Have you decided? I\'d rather know.', '(Lowell\'s hands are shaking around the lamp-pole.) Have you decided? I\'d rather know.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970513, 'Evening. (Lowell\'s eyes flick to you and away again.) The lamps won\'t light themselves, $g sir:madam;.', 'Evening. (Lowell\'s eyes flick to you and away again.) The lamps won\'t light themselves, $g sir:madam;.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`, `text1_0`, `text1_1`, `Probability1`, `text2_0`, `text2_1`, `Probability2`) VALUES (970514, 'Evening, $g sir:madam;. Mind the ladder. I\'ve a dozen lamps to light before the watch changes, and the north road ones are always last.', 'Evening, $g sir:madam;. Mind the ladder. I\'ve a dozen lamps to light before the watch changes, and the north road ones are always last.', 0.333, 'Forty years I\'ve lit these lamps. Kings, plague, and now the Dark Lady, and the lamps never cared which. There\'s a comfort in that, isn\'t there?', 'Forty years I\'ve lit these lamps. Kings, plague, and now the Dark Lady, and the lamps never cared which. There\'s a comfort in that, isn\'t there?', 0.333, 'The Deathguard like a bit of company on the night round. They tell me everything. Who\'s tired, who\'s sore, who\'s late. I just listen. Someone ought to.', 'The Deathguard like a bit of company on the night round. They tell me everything. Who\'s tired, who\'s sore, who\'s late. I just listen. Someone ought to.', 0.333);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970515, '(The zealot is praying under his breath. When he sees you, he stops.) Anselm will hear what you did to his lamb. The Light keeps an account, abomination.', '(The zealot is praying under his breath. When he sees you, he stops.) Anselm will hear what you did to his lamb. The Light keeps an account, abomination.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970516, 'The Royal Apothecary Society took Lowell Marsh to the Undercity this morning. I asked what would become of him and was told it was a matter for the Dark Lady\'s agents. I have decided to be satisfied with that answer. Brill\'s patrols are its own again, $n. That is what matters. That is what I keep telling myself.', 'The Royal Apothecary Society took Lowell Marsh to the Undercity this morning. I asked what would become of him and was told it was a matter for the Dark Lady\'s agents. I have decided to be satisfied with that answer. Brill\'s patrols are its own again, $n. That is what matters. That is what I keep telling myself.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970517, 'Lowell Marsh is gone. He left his ladder against the inn wall and walked off in the night, and the north road lamp was burning when the watch found it, though no one remembers lighting it. (Sevren studies you a moment.) I don\'t suppose you\'d know anything about that. No. Of course you wouldn\'t.', 'Lowell Marsh is gone. He left his ladder against the inn wall and walked off in the night, and the north road lamp was burning when the watch found it, though no one remembers lighting it. (Sevren studies you a moment.) I don\'t suppose you\'d know anything about that. No. Of course you wouldn\'t.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970518, 'The letters have stopped. Whoever it was lost his nerve, or his priest. (Sevren glances out of the window, toward the lamplighter working in the square.) I have decided not to wonder why the lamps have been lit so very carefully lately. A magistrate must choose what to wonder about.', 'The letters have stopped. Whoever it was lost his nerve, or his priest. (Sevren glances out of the window, toward the lamplighter working in the square.) I have decided not to wonder why the lamps have been lit so very carefully lately. A magistrate must choose what to wonder about.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`, `text1_0`, `text1_1`, `Probability1`, `text2_0`, `text2_1`, `Probability2`) VALUES (970519, 'A letter came for you, love. No name on the outside, just yours. (It reads:) "The ferry at the Sepulcher takes anyone who pays. I mend nets now on the Silverpine coast. The fishermen don\'t ask what I am; the fog makes us all the same color. I think of the north road lamp. W."', 'A letter came for you, love. No name on the outside, just yours. (It reads:) "The ferry at the Sepulcher takes anyone who pays. I mend nets now on the Silverpine coast. The fishermen don\'t ask what I am; the fog makes us all the same color. I think of the north road lamp. W."', 0.333, 'Another letter for you. (It reads:) "I\'ve heard nothing from Brother Anselm and I\'ve stopped listening for him. A worgen came down to the shore last night and we looked at each other a long while and then it went away. There\'s a lesson in that, I think. Not a comfortable one. W."', 'Another letter for you. (It reads:) "I\'ve heard nothing from Brother Anselm and I\'ve stopped listening for him. A worgen came down to the shore last night and we looked at each other a long while and then it went away. There\'s a lesson in that, I think. Not a comfortable one. W."', 0.333, 'One more of these came for you. Same shaky hand. (It reads:) "A Forsaken captain is taking volunteers for Northrend. They say no one there asks where you came from, only whether you\'ll stand. I\'m going to try standing. Light a lamp for me, if Brill\'s found anyone to light them. W."', 'One more of these came for you. Same shaky hand. (It reads:) "A Forsaken captain is taking volunteers for Northrend. They say no one there asks where you came from, only whether you\'ll stand. I\'m going to try standing. Light a lamp for me, if Brill\'s found anyone to light them. W."', 0.333);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970520, 'You\'re the one who found the lamplighter out. (Burgess straightens.) Two of mine died on that east road. Pell was a friend. If you ever need a blade at your back in Brill, $n, you\'ll have mine.', 'You\'re the one who found the lamplighter out. (Burgess straightens.) Two of mine died on that east road. Pell was a friend. If you ever need a blade at your back in Brill, $n, you\'ll have mine.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970521, 'Lamplighter vanished the same night you were seen talking with him by the well. (Burgess\'s eyes narrow.) I\'m sure that\'s nothing, $g sir:madam;. Move along.', 'Lamplighter vanished the same night you were seen talking with him by the well. (Burgess\'s eyes narrow.) I\'m sure that\'s nothing, $g sir:madam;. Move along.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970522, 'Lowell\'s been walking the night round with me again. Asks after my knees. (Cyrus snorts.) My knees! But it\'s company. Odd: he never asks about the watch hours anymore. Used to be all he talked about.', 'Lowell\'s been walking the night round with me again. Asks after my knees. (Cyrus snorts.) My knees! But it\'s company. Odd: he never asks about the watch hours anymore. Used to be all he talked about.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970523, 'Lowell Marsh. I told him about my hours myself, every night for a year. (Cyrus stares at nothing.) I thought he was listening because he cared.', 'Lowell Marsh. I told him about my hours myself, every night for a year. (Cyrus stares at nothing.) I thought he was listening because he cared.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970524, 'Nobody\'s lit the lamps since Marsh left. The Executor says he\'ll find someone. Meanwhile we walk in the dark. Funny how you don\'t notice a man until he\'s gone.', 'Nobody\'s lit the lamps since Marsh left. The Executor says he\'ll find someone. Meanwhile we walk in the dark. Funny how you don\'t notice a man until he\'s gone.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970525, 'Patrol hours changed overnight on the Magistrate\'s word, and the Crusade hasn\'t touched a cart since. Someone did good work, $n. I hear it was you.', 'Patrol hours changed overnight on the Magistrate\'s word, and the Crusade hasn\'t touched a cart since. Someone did good work, $n. I hear it was you.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970526, 'We kept the lamplighter down here one night, before the apothecaries came for him. He sat by the zealots\' cell and didn\'t say a word to them. They prayed for him the whole night. Worst sound I ever heard.', 'We kept the lamplighter down here one night, before the apothecaries came for him. He sat by the zealots\' cell and didn\'t say a word to them. They prayed for him the whole night. Worst sound I ever heard.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970527, 'The zealot\'s stopped gloating since you came down here with those letters. Whatever you did up there, keep doing it.', 'The zealot\'s stopped gloating since you came down here with those letters. Whatever you did up there, keep doing it.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970600, '(You lift the rug and shake it. A great grey cloud comes off it, and a moth the size of a thumb, which looks at you with what you\'d swear is resentment and flies off toward the town hall.)', '(You lift the rug and shake it. A great grey cloud comes off it, and a moth the size of a thumb, which looks at you with what you\'d swear is resentment and flies off toward the town hall.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970601, '(A rug of good Lordaeron wool, eaten to lace at the edges. Someone has written WIPE YOUR FEET on a card beside it, in a careful old hand.)', '(A rug of good Lordaeron wool, eaten to lace at the edges. Someone has written WIPE YOUR FEET on a card beside it, in a careful old hand.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970602, '(You clear the dust and cobwebs from the crate. Inside, packed in straw: tallow candles, every one of them stamped WICKE, every one of them eleven years old and perfectly fine. Wax keeps.)', '(You clear the dust and cobwebs from the crate. Inside, packed in straw: tallow candles, every one of them stamped WICKE, every one of them eleven years old and perfectly fine. Wax keeps.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970603, '(Candles packed in straw, stamped WICKE. Wax keeps. So, apparently, does Ambrose.)', '(Candles packed in straw, stamped WICKE. Wax keeps. So, apparently, does Ambrose.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970604, '(You brush down the shelf. Under the cobwebs: a row of little brass bells, a box of hairpins, three pocket watches that all stopped at different hours, and a jar of buttons nobody came back for.)', '(You brush down the shelf. Under the cobwebs: a row of little brass bells, a box of hairpins, three pocket watches that all stopped at different hours, and a jar of buttons nobody came back for.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970605, '(Bells, hairpins, stopped pocket watches, buttons. A shopkeeper\'s shelf, kept as if the customers were only late.)', '(Bells, hairpins, stopped pocket watches, buttons. A shopkeeper\'s shelf, kept as if the customers were only late.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970606, '(Under the counter, wedged behind a box of tapers, a ledger bound in green cloth gone grey. Its pages are columns of names, wares and pennies in a neat, cramped hand. The last page has a ribbon in it.)', '(Under the counter, wedged behind a box of tapers, a ledger bound in green cloth gone grey. Its pages are columns of names, wares and pennies in a neat, cramped hand. The last page has a ribbon in it.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970607, '(Ambrose\'s order ledger. The last entry is underlined twice, and beside it someone has added, much later and in the same hand: SETTLED.)', '(Ambrose\'s order ledger. The last entry is underlined twice, and beside it someone has added, much later and in the same hand: SETTLED.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970608, '(Ambrose\'s order ledger, open at a page with a ribbon in it.)', '(Ambrose\'s order ledger, open at a page with a ribbon in it.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970609, '(Brown paper, gone soft as cloth, and string tied in a shopkeeper\'s knot. Through a tear: two silvered candlesticks with a vine climbing each one. Engraved round the base: TOBIAS AND MARIAN. HARVEST, THE TWELFTH. The date is eleven years gone.)', '(Brown paper, gone soft as cloth, and string tied in a shopkeeper\'s knot. Through a tear: two silvered candlesticks with a vine climbing each one. Engraved round the base: TOBIAS AND MARIAN. HARVEST, THE TWELFTH. The date is eleven years gone.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970610, '(The paper\'s gone. The candlesticks are in the window now, lit, their names turned out toward the road.)', '(The paper\'s gone. The candlesticks are in the window now, lit, their names turned out toward the road.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970611, '(The parcel\'s been rewrapped in fresh paper, and a new label says: FOR A WEDDING. ANY WEDDING. ASK.)', '(The parcel\'s been rewrapped in fresh paper, and a new label says: FOR A WEDDING. ANY WEDDING. ASK.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970612, '(A parcel in old brown paper. A label in a careful hand: T. BALNIR. PAID. TO BE COLLECTED.)', '(A parcel in old brown paper. A label in a careful hand: T. BALNIR. PAID. TO BE COLLECTED.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970613, '(A notice nailed to the old wagon, the ink run but legible: BY ORDER OF THE CROWN OF LORDAERON. THIS HOLDING IS UNDER QUARANTINE. NO PERSON SHALL ENTER OR LEAVE. GRAIN FROM THIS HOLDING IS TO BE BURNED. Below, in pencil, someone has written: we did burn it. it didn\'t help.)', '(A notice nailed to the old wagon, the ink run but legible: BY ORDER OF THE CROWN OF LORDAERON. THIS HOLDING IS UNDER QUARANTINE. NO PERSON SHALL ENTER OR LEAVE. GRAIN FROM THIS HOLDING IS TO BE BURNED. Below, in pencil, someone has written: we did burn it. it didn\'t help.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970614, '(A quarantine notice from the Crown of Lordaeron, years too late to matter.)', '(A quarantine notice from the Crown of Lordaeron, years too late to matter.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970615, '(A family book, its cover burnt through at one corner. Inside, the Balnirs: Osric and Ada, and their children Tobias and Agnes. Below Tobias someone has written \'to wed Marian Hale\', and the date has been scratched out and written again three times. Then the deaths, all one autumn, in Osric\'s shaking hand: Marian. Tobias. Ada. The last line is in another hand: Osric. Agnes\'s name alone is not crossed out. Beside it: in service, Silverpine.)', '(A family book, its cover burnt through at one corner. Inside, the Balnirs: Osric and Ada, and their children Tobias and Agnes. Below Tobias someone has written \'to wed Marian Hale\', and the date has been scratched out and written again three times. Then the deaths, all one autumn, in Osric\'s shaking hand: Marian. Tobias. Ada. The last line is in another hand: Osric. Agnes\'s name alone is not crossed out. Beside it: in service, Silverpine.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970616, '(The Balnirs\' family book. Every name in it is crossed out but one.)', '(The Balnirs\' family book. Every name in it is crossed out but one.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970617, '(The family plot, up the slope behind the barn where the kitchen window could see it. The stone reads: TOBIAS BALNIR, AND MARIAN, WHO WOULD HAVE BEEN BALNIR. The grave beside it is open, and the earth is thrown outward. Whatever left it, nobody dug it up.)', '(The family plot, up the slope behind the barn where the kitchen window could see it. The stone reads: TOBIAS BALNIR, AND MARIAN, WHO WOULD HAVE BEEN BALNIR. The grave beside it is open, and the earth is thrown outward. Whatever left it, nobody dug it up.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970618, '(Tobias Balnir, and Marian, who would have been Balnir. The grave beside the stone is still open.)', '(Tobias Balnir, and Marian, who would have been Balnir. The grave beside the stone is still open.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`, `text1_0`, `text1_1`, `Probability1`, `text2_0`, `text2_1`, `Probability2`, `text3_0`, `text3_1`, `Probability3`) VALUES (970619, 'Customer! A living one, or near enough. Mind the candlesticks in the window: they\'re not for sale. Everything else is, and most of it is older than you are.', 'Customer! A living one, or near enough. Mind the candlesticks in the window: they\'re not for sale. Everything else is, and most of it is older than you are.', 0.250, 'Welcome, welcome. Business is excellent. Nobody in Brill needs light to see by, so they buy candles for the smell. You\'d be amazed what people want to smell like, round here.', 'Welcome, welcome. Business is excellent. Nobody in Brill needs light to see by, so they buy candles for the smell. You\'d be amazed what people want to smell like, round here.', 0.250, 'Two candles in the window, burning for a wedding. Folk ask who they\'re for. I tell them a customer. The best kind: paid in full and never complains.', 'Two candles in the window, burning for a wedding. Folk ask who they\'re for. I tell them a customer. The best kind: paid in full and never complains.', 0.250, 'Ah, $n. Come in. I\'ve a pocket watch that stopped the hour its owner did. Very reasonable. It\'s right twice a day, which is more than most of my customers manage.', 'Ah, $n. Come in. I\'ve a pocket watch that stopped the hour its owner did. Very reasonable. It\'s right twice a day, which is more than most of my customers manage.', 0.250);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`, `text1_0`, `text1_1`, `Probability1`, `text2_0`, `text2_1`, `Probability2`, `text3_0`, `text3_1`, `Probability3`) VALUES (970620, 'Customer! The candlesticks are for sale, if you\'re marrying. Nobody\'s asked yet. Forsaken courtship is slow, I\'m told. Something about the hands coming off.', 'Customer! The candlesticks are for sale, if you\'re marrying. Nobody\'s asked yet. Forsaken courtship is slow, I\'m told. Something about the hands coming off.', 0.250, 'Welcome, welcome. Wrapping paper, $n? Red ribbon or blue. I\'ve a great deal of practice wrapping things that never get collected.', 'Welcome, welcome. Wrapping paper, $n? Red ribbon or blue. I\'ve a great deal of practice wrapping things that never get collected.', 0.250, 'Sold three candles to a Deathguard this morning. He didn\'t say what for. In Brill, you learn not to ask what the candles are for.', 'Sold three candles to a Deathguard this morning. He didn\'t say what for. In Brill, you learn not to ask what the candles are for.', 0.250, 'Ah, $n. I\'ve a mirror in stock. Broken, I\'m afraid. Most of my customers prefer it that way.', 'Ah, $n. I\'ve a mirror in stock. Broken, I\'m afraid. Most of my customers prefer it that way.', 0.250);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`, `text1_0`, `text1_1`, `Probability1`) VALUES (970621, 'Open for business, $n. Candles, curios, and a very good price on handkerchiefs. Funerals and weddings both: I don\'t ask which.', 'Open for business, $n. Candles, curios, and a very good price on handkerchiefs. Funerals and weddings both: I don\'t ask which.', 0.500, 'Customer! Come in, come in, before you die. Though if you do, I\'ll still be here.', 'Customer! Come in, come in, before you die. Though if you do, I\'ll still be here.', 0.500);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970622, 'That\'s Agnes Balnir\'s hand on that letter. I\'d know it anywhere: she used to sign for her father\'s wax. Well? Give it here. No. Wait. Tell me first. Then give it here.', 'That\'s Agnes Balnir\'s hand on that letter. I\'d know it anywhere: she used to sign for her father\'s wax. Well? Give it here. No. Wait. Tell me first. Then give it here.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970623, 'Silverpine. I\'ve not been out of Brill since I woke up. I\'d lose the custom. Not that I\'ve any. Go on, $n: the Sepulcher. Tell her it\'s paid in full.', 'Silverpine. I\'ve not been out of Brill since I woke up. I\'d lose the custom. Not that I\'ve any. Go on, $n: the Sepulcher. Tell her it\'s paid in full.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970624, 'The Balnir Farmstead, $n. South and east. When you come back, try not to look at me like that. I can see your face from here. Well. With the one eye.', 'The Balnir Farmstead, $n. South and east. When you come back, try not to look at me like that. I can see your face from here. Well. With the one eye.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970625, 'Mind the counter. It\'s held up a good deal of business in its time, and lately it\'s been holding up me.', 'Mind the counter. It\'s held up a good deal of business in its time, and lately it\'s been holding up me.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970626, 'Closed. We\'re closed. There\'s an order outstanding, and until it\'s collected, nothing in this stall is for sale. No, not even that. Especially not that.', 'Closed. We\'re closed. There\'s an order outstanding, and until it\'s collected, nothing in this stall is for sale. No, not even that. Especially not that.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970627, 'Did he open the shutters? Good. Good. Tobias always said old Wicke would have sold candles to the sun if it stood still long enough.', 'Did he open the shutters? Good. Good. Tobias always said old Wicke would have sold candles to the sun if it stood still long enough.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970628, 'Shrouds don\'t wash themselves, $n. Well. Some of them try. Those I report to the Deathguard.', 'Shrouds don\'t wash themselves, $n. Well. Some of them try. Those I report to the Deathguard.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970629, 'Laundry? Leave it there, and careful what you hand me: half of what comes in here used to belong to someone, and they may want it back.', 'Laundry? Leave it there, and careful what you hand me: half of what comes in here used to belong to someone, and they may want it back.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970700, '(You rake the dead coals and feed in fresh pitch-wood from the Deathguard\'s pack. The brazier catches with a sullen roar, throwing light a long way down the road.)', '(You rake the dead coals and feed in fresh pitch-wood from the Deathguard\'s pack. The brazier catches with a sullen roar, throwing light a long way down the road.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970701, '(A Deathguard patrol brazier, burning low.)', '(A Deathguard patrol brazier, burning low.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970702, '(The cart lies on its side, a wheel snapped. Its load is scattered: grain, a tinker\'s pots, an apothecary\'s satchel slit open. Deep gouges in the wood, the claw-marks of ghouls, and the drag marks lead off the road toward the river. Whoever drove it didn\'t get far.)', '(The cart lies on its side, a wheel snapped. Its load is scattered: grain, a tinker\'s pots, an apothecary\'s satchel slit open. Deep gouges in the wood, the claw-marks of ghouls, and the drag marks lead off the road toward the river. Whoever drove it didn\'t get far.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970703, '(A wrecked cart, picked clean.)', '(A wrecked cart, picked clean.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970704, '(Heavy bootprints, the hobnailed kind the Deathguard wear, pressed deep into the mud. They come and go along the same path many times over, always heading west toward Solliden Farmstead, never straying from the way.)', '(Heavy bootprints, the hobnailed kind the Deathguard wear, pressed deep into the mud. They come and go along the same path many times over, always heading west toward Solliden Farmstead, never straying from the way.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970705, '(Old bootprints in the mud, heading west.)', '(Old bootprints in the mud, heading west.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970706, '(A neat bundle of split oak by the cottage gate, tied with a strip of grey cloth: the same grey as a Deathguard\'s tabard. Older bundles have been stacked against the wall, carefully, by someone else. It\'s been used all winter.)', '(A neat bundle of split oak by the cottage gate, tied with a strip of grey cloth: the same grey as a Deathguard\'s tabard. Older bundles have been stacked against the wall, carefully, by someone else. It\'s been used all winter.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970707, '(The last bundle of firewood, half gone. No one has left a new one.)', '(The last bundle of firewood, half gone. No one has left a new one.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970708, '(Split oak, tied with a strip of grey cloth.)', '(Split oak, tied with a strip of grey cloth.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`, `text1_0`, `text1_1`, `Probability1`) VALUES (970709, 'Harlan stood his whole watch last night and the night before. Didn\'t miss a bell. (Dillinger almost smiles.) Brill owes you for that, $n. So do I.', 'Harlan stood his whole watch last night and the night before. Didn\'t miss a bell. (Dillinger almost smiles.) Brill owes you for that, $n. So do I.', 0.500, 'Quiet on the north road. Harlan sees to it. Took his rest day at Solliden, came back before the bell, same as he promised. You\'re welcome at my fire any night you like, $n.', 'Quiet on the north road. Harlan sees to it. Took his rest day at Solliden, came back before the bell, same as he promised. You\'re welcome at my fire any night you like, $n.', 0.500);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`, `text1_0`, `text1_1`, `Probability1`) VALUES (970710, 'Harlan\'s taken to the cemetery watch like he was born to it. Well. Died to it. He doesn\'t look west anymore. Brill\'s in your debt, $n.', 'Harlan\'s taken to the cemetery watch like he was born to it. Well. Died to it. He doesn\'t look west anymore. Brill\'s in your debt, $n.', 0.500, 'New lad on the north road now; keen, careless. I miss Harlan\'s patience out there, but he\'s whole at the cemetery, and that\'s what counts. Good to see you, $n.', 'New lad on the north road now; keen, careless. I miss Harlan\'s patience out there, but he\'s whole at the cemetery, and that\'s what counts. Good to see you, $n.', 0.500);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970711, '$n. The watch knows your name now. Not many living or dead can say that in Brill.', '$n. The watch knows your name now. Not many living or dead can say that in Brill.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970712, 'That mastiff yours? Good. Brill bred mastiffs once, for the Lordaeron militia. Take it on your rounds; ghouls don\'t like a dog that doesn\'t flinch.', 'That mastiff yours? Good. Brill bred mastiffs once, for the Lordaeron militia. Take it on your rounds; ghouls don\'t like a dog that doesn\'t flinch.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970713, 'Any word of Harlan? (Dillinger glances toward the north road.) I\'ll stand his watch until you\'re back.', 'Any word of Harlan? (Dillinger glances toward the north road.) I\'ll stand his watch until you\'re back.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970714, 'You\'re the one who found Sevren\'s leak. Two of ours died on that east road. (Dillinger nods.) If you\'ve a mind to help the watch, I\'ve work.', 'You\'re the one who found Sevren\'s leak. Two of ours died on that east road. (Dillinger nods.) If you\'ve a mind to help the watch, I\'ve work.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970715, 'My rest day. (Harlan doesn\'t look away from the cottage.) I stand here till the lamp goes out, then I walk back and take the dawn watch. Once a week. It\'s enough. It has to be.', 'My rest day. (Harlan doesn\'t look away from the cottage.) I stand here till the lamp goes out, then I walk back and take the dawn watch. Once a week. It\'s enough. It has to be.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970716, 'Last time. I told myself that, coming up the hill. (Harlan turns away from the cottage.) I\'ll be at the cemetery tomorrow, facing east. Don\'t tell me if the lamp\'s still lit.', 'Last time. I told myself that, coming up the hill. (Harlan turns away from the cottage.) I\'ll be at the cemetery tomorrow, facing east. Don\'t tell me if the lamp\'s still lit.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970717, 'That\'s a Brill mastiff. (Something in Harlan\'s face softens.) We had one. Lise named it Pudding. Leave yours here with me while you go down; the farmers will take it for a stray and loose the hounds.', 'That\'s a Brill mastiff. (Something in Harlan\'s face softens.) We had one. Lise named it Pudding. Leave yours here with me while you go down; the farmers will take it for a stray and loose the hounds.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970718, '(Harlan watches the cottage below. Smoke rises from its chimney.)', '(Harlan watches the cottage below. Smoke rises from its chimney.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970719, 'Move along. I\'m on patrol. (The Deathguard keeps his eyes on the farmstead below, and doesn\'t seem to be patrolling anything.)', 'Move along. I\'m on patrol. (The Deathguard keeps his eyes on the farmstead below, and doesn\'t seem to be patrolling anything.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970720, 'No firewood at the gate this month. (Maren pulls her shawl tighter.) Silly. I\'d started thinking of it as a friend. Some kind neighbor I never saw.', 'No firewood at the gate this month. (Maren pulls her shawl tighter.) Silly. I\'d started thinking of it as a friend. Some kind neighbor I never saw.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970721, 'Wood at the gate again this morning. Every seventh day, now, regular as a bell. (She smiles, a little puzzled.) Whoever it is, I hope they\'re warm.', 'Wood at the gate again this morning. Every seventh day, now, regular as a bell. (She smiles, a little puzzled.) Whoever it is, I hope they\'re warm.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970722, '(The woman by the cottage stiffens when she sees you, one hand on the doorframe.) We\'ve nothing worth taking, stranger. The farmers are just across the yard.', '(The woman by the cottage stiffens when she sees you, one hand on the doorframe.) We\'ve nothing worth taking, stranger. The farmers are just across the yard.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970723, '(A girl of eleven or so peers at you from behind the fence.) Mama says not to talk to strangers from Brill.', '(A girl of eleven or so peers at you from behind the fence.) Mama says not to talk to strangers from Brill.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970724, 'Rest day tomorrow. (Harlan\'s eyes go west, just for a moment, then back to the road.) Quiet watch. Exactly how I like it.', 'Rest day tomorrow. (Harlan\'s eyes go west, just for a moment, then back to the road.) Quiet watch. Exactly how I like it.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970725, 'North road\'s clear, $n. Not a ghoul between here and the river. (He nods once.) Thanks to you, I\'m standing where I ought to be.', 'North road\'s clear, $n. Not a ghoul between here and the river. (He nods once.) Thanks to you, I\'m standing where I ought to be.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970726, 'The dead in this cemetery don\'t want anything from me. (Harlan leans on his halberd.) There\'s a peace in that. Most days.', 'The dead in this cemetery don\'t want anything from me. (Harlan leans on his halberd.) There\'s a peace in that. Most days.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970727, 'Cemetery watch, $n. Facing east, toward the Plaguelands and whatever comes out of them. (He doesn\'t look back over his shoulder. It seems to take some effort.)', 'Cemetery watch, $n. Facing east, toward the Plaguelands and whatever comes out of them. (He doesn\'t look back over his shoulder. It seems to take some effort.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970800, '(You lower the bucket and draw it back up. The water is cloudy and faintly green, and pale threads drift in it, turning slowly, as if they were looking for a way out of the jar.)', '(You lower the bucket and draw it back up. The water is cloudy and faintly green, and pale threads drift in it, turning slowly, as if they were looking for a way out of the jar.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970801, '(You tip Holland\'s powder into the well. It hisses as it strikes the water, and a gray steam rises, smelling of lime and burnt root. When it clears, the water far below is dark and still, and nothing in it moves.)', '(You tip Holland\'s powder into the well. It hisses as it strikes the water, and a gray steam rises, smelling of lime and burnt root. When it clears, the water far below is dark and still, and nothing in it moves.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970802, '(You draw up a bucket. The water is cold and clear, and tastes of stone and very faintly of lime. Somewhere behind you, Maudie hums as she wrings out a sheet.)', '(You draw up a bucket. The water is cold and clear, and tastes of stone and very faintly of lime. Somewhere behind you, Maudie hums as she wrings out a sheet.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970803, '(The water far below is cloudy and smells sour and green, even by Brill\'s standards. Best not.)', '(The water far below is cloudy and smells sour and green, even by Brill\'s standards. Best not.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970804, '(Maudie\'s old bucket sits on the bank, half sunk in the reeds. You fill a jar from the stream. The water here is clearer, but held up to the light the same pale threads are in it: fewer, drifting.)', '(Maudie\'s old bucket sits on the bank, half sunk in the reeds. You fill a jar from the stream. The water here is clearer, but held up to the light the same pale threads are in it: fewer, drifting.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970805, '(An old wooden bucket, half sunk in the reeds.)', '(An old wooden bucket, half sunk in the reeds.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970806, '(A crust of pale gray fungus spreads where the water seeps out of the hillside. Beneath it the soil is slick and squirming with the same pale threads, thicker here, all of them leaning west, downhill.)', '(A crust of pale gray fungus spreads where the water seeps out of the hillside. Beneath it the soil is slick and squirming with the same pale threads, thicker here, all of them leaning west, downhill.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970807, '(Gray fungus, withered and dry now. Nothing moves in it.)', '(Gray fungus, withered and dry now. Nothing moves in it.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970808, '(A crust of pale gray fungus. It glistens.)', '(A crust of pale gray fungus. It glistens.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970809, '(Under the covered bridge, the dry channel narrows to a stone culvert half choked with rubble. A grate once closed it; it hangs from one hinge now. The air from the dark beneath the bridge is warm and wet and smells of eggs. Something moves in there, heavily, then is still.)', '(Under the covered bridge, the dry channel narrows to a stone culvert half choked with rubble. A grate once closed it; it hangs from one hinge now. The air from the dark beneath the bridge is warm and wet and smells of eggs. Something moves in there, heavily, then is still.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970810, '(A stone culvert, half choked with rubble, beneath the covered bridge.)', '(A stone culvert, half choked with rubble, beneath the covered bridge.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970811, '(You split the sac open. Pale threads spill across the stones and writhe in the air, then go still.)', '(You split the sac open. Pale threads spill across the stones and writhe in the air, then go still.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970812, '(A slick, pale sac, burst and empty.)', '(A slick, pale sac, burst and empty.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970813, '(Half buried in the muck of the nest lies a split cask, its staves black with slime. The brand on the lid is the Royal Apothecary Society\'s. Beneath it is a stenciled label, mostly legible:$B$B\'GROWTH TINCTURE, BATCH 9. FOR FUNGAL CULTURE. FAILED: DO NOT USE. DISPOSE OF BY DILUTION.\'$B$BSomeone diluted it, all right. They rolled it into the drainage channel and let the rain do the rest.)', '(Half buried in the muck of the nest lies a split cask, its staves black with slime. The brand on the lid is the Royal Apothecary Society\'s. Beneath it is a stenciled label, mostly legible:$B$B\'GROWTH TINCTURE, BATCH 9. FOR FUNGAL CULTURE. FAILED: DO NOT USE. DISPOSE OF BY DILUTION.\'$B$BSomeone diluted it, all right. They rolled it into the drainage channel and let the rain do the rest.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970814, '(A split cask with the Royal Apothecary Society\'s brand.)', '(A split cask with the Royal Apothecary Society\'s brand.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970815, 'That Crane lad comes by every morning for a bucket now. Says the Apothecary insists on clean water for his work. Nice manners. Short a few fingers. Aren\'t we all.', 'That Crane lad comes by every morning for a bucket now. Says the Apothecary insists on clean water for his work. Nice manners. Short a few fingers. Aren\'t we all.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970816, 'Johaan\'s apprentice scrubs the laboratory floors with my water now. He asked me once whether I thought he\'d done right. I told him I wash other people\'s stains for a living, and I don\'t ask how they got there.', 'Johaan\'s apprentice scrubs the laboratory floors with my water now. He asked me once whether I thought he\'d done right. I told him I wash other people\'s stains for a living, and I don\'t ask how they got there.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970817, 'Society couriers through the square twice this week, carrying crates up from Johaan\'s. They didn\'t stop for water. I didn\'t offer.', 'Society couriers through the square twice this week, carrying crates up from Johaan\'s. They didn\'t stop for water. I didn\'t offer.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`, `text1_0`, `text1_1`, `Probability1`, `text2_0`, `text2_1`, `Probability2`) VALUES (970818, 'Clean water, clean linen, and Renee\'s paying me again. A body can\'t ask for more. Well, a living one could. I\'ve stopped asking.', 'Clean water, clean linen, and Renee\'s paying me again. A body can\'t ask for more. Well, a living one could. I\'ve stopped asking.', 0.333, 'Draw yourself a bucket, if you like. It\'s the best water in Tirisfal now. That isn\'t saying much, but I\'ll say it.', 'Draw yourself a bucket, if you like. It\'s the best water in Tirisfal now. That isn\'t saying much, but I\'ll say it.', 0.333, 'Hear that? The windlass. I used to hate the sound. Now I sing along. Badly. I was always bad.', 'Hear that? The windlass. I used to hate the sound. Now I sing along. Badly. I was always bad.', 0.333);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970819, 'Holland\'s powder? Go on, then. Tip it in. I\'ll hold my breath. Habit.', 'Holland\'s powder? Go on, then. Tip it in. I\'ll hold my breath. Habit.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970820, 'I\'ve been washing in rainwater from the barrels. Do you know how long it takes to fill a tub from a barrel? Neither did I. Now I know.', 'I\'ve been washing in rainwater from the barrels. Do you know how long it takes to fill a tub from a barrel? Neither did I. Now I know.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`, `text1_0`, `text1_1`, `Probability1`) VALUES (970821, 'Don\'t drink from that well. Don\'t even look into it too long. It looks back.', 'Don\'t drink from that well. Don\'t even look into it too long. It looks back.', 0.500, 'Maudie Fenwick, laundress. If it\'s stained, I\'ve washed it. If it\'s cursed, I\'ve washed it twice.', 'Maudie Fenwick, laundress. If it\'s stained, I\'ve washed it. If it\'s cursed, I\'ve washed it twice.', 0.500);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970822, 'I asked about batch nine in the Undercity. Nobody remembered it. Everybody remembered to tell me not to ask again. Crane says that\'s how the Society says thank you.', 'I asked about batch nine in the Undercity. Nobody remembered it. Everybody remembered to tell me not to ask again. Crane says that\'s how the Society says thank you.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`, `text1_0`, `text1_1`, `Probability1`) VALUES (970823, 'The samples from the well are clean. I\'ve kept one of the old ones in a sealed jar on my shelf. For science. And to remind me to look at the water now and then.', 'The samples from the well are clean. I\'ve kept one of the old ones in a sealed jar on my shelf. For science. And to remind me to look at the water now and then.', 0.500, 'Maudie says the robes come back whiter now. I\'ve told her robes aren\'t meant to be white. She ignored me.', 'Maudie says the robes come back whiter now. I\'ve told her robes aren\'t meant to be white. She ignored me.', 0.500);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970824, 'Under the covered bridge, west on the Deathknell road. Burst the sacs. All of them, if you can.', 'Under the covered bridge, west on the Deathknell road. Burst the sacs. All of them, if you can.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970900, '(Scratched into the cellar floor where the straw has been swept aside: an eye under a crown, its pupil a long stroke pointing north. The prisoners in the cells don\'t look at it.)', '(Scratched into the cellar floor where the straw has been swept aside: an eye under a crown, its pupil a long stroke pointing north. The prisoners in the cells don\'t look at it.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970901, '(An eye under a crown, scratched into the floor.)', '(An eye under a crown, scratched into the floor.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970902, '(The foundation stone has been re-mortared; behind it, a hollow. Cut into the stone\'s hidden face: the crowned eye, and under it the words CLEAN AIR BELOW. A mason\'s mark beside it: T.C.)', '(The foundation stone has been re-mortared; behind it, a hollow. Cut into the stone\'s hidden face: the crowned eye, and under it the words CLEAN AIR BELOW. A mason\'s mark beside it: T.C.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970903, '(A foundation stone, newer mortar around its edge.)', '(A foundation stone, newer mortar around its edge.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970904, '(The ground has fallen into a long trench running north. At the bottom, in the clay, the top of a brick arch, and on its keystone the crowned eye. Cold air breathes up between the bricks.)', '(The ground has fallen into a long trench running north. At the bottom, in the clay, the top of a brick arch, and on its keystone the crowned eye. Cold air breathes up between the bricks.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970905, '(A long trench where the ground has fallen in.)', '(A long trench where the ground has fallen in.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970906, '(HESTER LYLE, HER BOOK. \'Third day below. Master Croft says the air here is clean and the plague cannot follow. The children are frightened of the Agamand vaults but I tell them the dead are only sleeping. God forgive me, I believed it.\')', '(HESTER LYLE, HER BOOK. \'Third day below. Master Croft says the air here is clean and the plague cannot follow. The children are frightened of the Agamand vaults but I tell them the dead are only sleeping. God forgive me, I believed it.\')', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970907, '(A water-stained journal, its pages stuck together.)', '(A water-stained journal, its pages stuck together.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970908, '(Accounts in a baker\'s hand, flour and salt, and then: \'Croft brought bread again. Good white bread. Where does he get the flour, with the mills shut? Lyle\'s youngest has the cough. Croft says it is only the damp. Croft says a great deal.\')', '(Accounts in a baker\'s hand, flour and salt, and then: \'Croft brought bread again. Good white bread. Where does he get the flour, with the mills shut? Lyle\'s youngest has the cough. Croft says it is only the damp. Croft says a great deal.\')', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970909, '(A baker\'s daybook, its accounts unfinished.)', '(A baker\'s daybook, its accounts unfinished.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970910, '(Letters practised over and over in a careful round hand, and then, near the end: \'Mother says the doors are bricked. Master Croft says it is for our own good. Mother has stopped answering. I am going to be brave like Da.\')', '(Letters practised over and over in a careful round hand, and then, near the end: \'Mother says the doors are bricked. Master Croft says it is for our own good. Mother has stopped answering. I am going to be brave like Da.\')', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970911, '(A child\'s copybook, the letters practised over and over.)', '(A child\'s copybook, the letters practised over and over.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970912, '(A voice beyond the ward, mild and amused.)$B$B\'A visitor. How long it\'s been. You\'ll be one of the Banshee\'s strays, I suppose. Freed, you call it. A dog off its lead is not free; it is lost. The Master is silent because the Master is patient. When He speaks again, every one of you will remember whose you are, and I will be here to welcome you home.\'', '(A voice beyond the ward, mild and amused.)$B$B\'A visitor. How long it\'s been. You\'ll be one of the Banshee\'s strays, I suppose. Freed, you call it. A dog off its lead is not free; it is lost. The Master is silent because the Master is patient. When He speaks again, every one of you will remember whose you are, and I will be here to welcome you home.\'', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970913, '(The ward hums. Beyond it, someone is humming a hymn, slightly flat.)', '(The ward hums. Beyond it, someone is humming a hymn, slightly flat.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970914, '(Bones set in a pattern across a doorway. They are cold to the touch.)', '(Bones set in a pattern across a doorway. They are cold to the touch.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970915, '(A stack of letters, each addressed TO MY MASTER, IN NORTHREND. \'Brill is tended. The flock is counted.\' \'I await your instruction.\' \'The Banshee\'s rabble walk my town as if it were theirs.\' \'Master, I have been patient.\' The most recent is only one line, written many times: \'Answer me.\')', '(A stack of letters, each addressed TO MY MASTER, IN NORTHREND. \'Brill is tended. The flock is counted.\' \'I await your instruction.\' \'The Banshee\'s rabble walk my town as if it were theirs.\' \'Master, I have been patient.\' The most recent is only one line, written many times: \'Answer me.\')', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970916, '(A stack of letters, sealed and never sent.)', '(A stack of letters, sealed and never sent.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970917, '(A long roll of vellum in a fine, careful hand: thirty-one names, each with the date of its raising and a note of its use. SENTINEL. SENTINEL. LABOURER. SENTINEL. You roll it up and take it.)', '(A long roll of vellum in a fine, careful hand: thirty-one names, each with the date of its raising and a note of its use. SENTINEL. SENTINEL. LABOURER. SENTINEL. You roll it up and take it.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970918, '(A roll of vellum, names in a fine and careful hand.)', '(A roll of vellum, names in a fine and careful hand.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970919, 'The crypt\'s walled again, the right way this time: no marks, no gifts, no Croft. I\'ll be moving on soon. Before I go: well done. I don\'t say it often.', 'The crypt\'s walled again, the right way this time: no marks, no gifts, no Croft. I\'ll be moving on soon. Before I go: well done. I don\'t say it often.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970920, 'Why are you looking for me here? I said I\'d meet you at the crypt. North, past the mills.', 'Why are you looking for me here? I said I\'d meet you at the crypt. North, past the mills.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970921, '(A woman in a dark hood studies the mausoleum\'s stonework with great interest. She doesn\'t look at you.)$B$BKeep walking as if we\'re strangers. We are, for now.', '(A woman in a dark hood studies the mausoleum\'s stonework with great interest. She doesn\'t look at you.)$B$BKeep walking as if we\'re strangers. We are, for now.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970922, 'Sealed. My way. Go home, $n. Light a candle. Not his.', 'Sealed. My way. Go home, $n. Light a candle. Not his.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970923, 'Mind the Agamands on the stair. They\'re only angry; they don\'t know any better.', 'Mind the Agamands on the stair. They\'re only angry; they don\'t know any better.', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970924, 'This crypt belongs to the Agamands. I\'m only resting. You\'ll want to keep walking.', 'This crypt belongs to the Agamands. I\'m only resting. You\'ll want to keep walking.', 1);
-- (14 of the town's 21 phases used)
