-- Brill: A Place Among the Forsaken (mod-brill-home; ChromieCraft's world database alone: ctl.sh
-- realm_sql). Made by tools/build_town.py from tools/town/*.py; don't edit by hand. Written to be
-- applied again: its own rows go first.

CREATE TABLE IF NOT EXISTS `brill_town_phase` (`bit` INT UNSIGNED NOT NULL, `ord` INT UNSIGNED NOT NULL, `cond` VARCHAR(255) NOT NULL DEFAULT '', PRIMARY KEY (`bit`, `ord`)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS `brill_town_gossip` (`npc` INT UNSIGNED NOT NULL, `ord` INT UNSIGNED NOT NULL, `kind` VARCHAR(8) NOT NULL, `cond` VARCHAR(255) NOT NULL DEFAULT '', `icon` TINYINT UNSIGNED NOT NULL DEFAULT 0, `text` TEXT, `text_id` INT UNSIGNED NOT NULL DEFAULT 0, `action` VARCHAR(16) NOT NULL DEFAULT '', `quest` INT UNSIGNED NOT NULL DEFAULT 0, `credit` INT UNSIGNED NOT NULL DEFAULT 0, `marker` INT UNSIGNED NOT NULL DEFAULT 0, `emote` INT UNSIGNED NOT NULL DEFAULT 0, `reply` TEXT, `arg` INT UNSIGNED NOT NULL DEFAULT 0, PRIMARY KEY (`npc`, `ord`)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS `brill_town_use` (`go` INT UNSIGNED NOT NULL, `ord` INT UNSIGNED NOT NULL, `cond` VARCHAR(255) NOT NULL DEFAULT '', `quest` INT UNSIGNED NOT NULL DEFAULT 0, `credit` INT UNSIGNED NOT NULL DEFAULT 0, `text_id` INT UNSIGNED NOT NULL DEFAULT 0, `marker` INT UNSIGNED NOT NULL DEFAULT 0, PRIMARY KEY (`go`, `ord`)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
DELETE FROM `brill_town_phase`;
DELETE FROM `brill_town_gossip`;
DELETE FROM `brill_town_use`;
DELETE FROM `creature_template` WHERE `entry` BETWEEN 970200 AND 970999;
DELETE FROM `creature_template_model` WHERE `CreatureID` BETWEEN 970200 AND 970999;
DELETE FROM `creature` WHERE `guid` BETWEEN 970200 AND 970999;
DELETE FROM `gameobject_template` WHERE `entry` BETWEEN 970200 AND 970999;
DELETE FROM `gameobject` WHERE `guid` BETWEEN 970200 AND 970999;
DELETE FROM `npc_text` WHERE `ID` BETWEEN 970200 AND 970999;
DELETE FROM `quest_template` WHERE `ID` BETWEEN 970200 AND 970999;
DELETE FROM `quest_template_addon` WHERE `ID` BETWEEN 970200 AND 970999;
DELETE FROM `quest_offer_reward` WHERE `ID` BETWEEN 970200 AND 970999;
DELETE FROM `quest_request_items` WHERE `ID` BETWEEN 970200 AND 970999;
DELETE FROM `creature_queststarter` WHERE `quest` BETWEEN 970200 AND 970999;
DELETE FROM `creature_questender` WHERE `quest` BETWEEN 970200 AND 970999;
DELETE FROM `smart_scripts` WHERE `entryorguid` BETWEEN 970200 AND 970999 AND `source_type` = 0;

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
(970200, 970200, 0, 85, 0, 1, 1, 2290.012, 362.391, 34.220, 4.9068, 60, 0, 0, 'mod-brill-home town'),
(970201, 970201, 0, 85, 0, 1, 524288, 2291.712, 353.757, 35.220, 3.5105, 30, 2, 1, 'mod-brill-home town'),
(970202, 970201, 0, 85, 0, 1, 524288, 2291.854, 348.893, 35.220, 0.5435, 30, 2, 1, 'mod-brill-home town'),
(970203, 970201, 0, 85, 0, 1, 524288, 2286.960, 352.006, 35.220, 4.7323, 30, 2, 1, 'mod-brill-home town'),
(970204, 970201, 0, 85, 0, 1, 524288, 2293.118, 351.792, 35.220, 2.2888, 30, 2, 1, 'mod-brill-home town'),
(970205, 970201, 0, 85, 0, 1, 524288, 2288.170, 350.002, 35.220, 5.7795, 30, 2, 1, 'mod-brill-home town'),
(970206, 970201, 0, 85, 0, 1, 524288, 2289.324, 355.529, 35.220, 4.1214, 30, 2, 1, 'mod-brill-home town'),
(970207, 970202, 0, 85, 0, 1, 2097152, 2291.664, 350.894, 35.220, 2.2888, 60, 0, 0, 'mod-brill-home town'),
(970208, 970203, 0, 85, 0, 1, 2097152, 2287.665, 351.533, 35.220, 5.9540, 60, 0, 0, 'mod-brill-home town'),
(970209, 970204, 0, 85, 0, 1, 2097152, 2289.113, 350.392, 35.220, 2.8124, 60, 0, 0, 'mod-brill-home town'),
(970210, 970205, 0, 85, 0, 1, 2097152, 2288.697, 355.609, 35.220, 4.9068, 60, 0, 0, 'mod-brill-home town');
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
(970200, 970200, 0, 85, 0, 1, 262144, 2290.840, 350.935, 35.220, 3.8596, 0, 0, 0.936248, -0.351341, 0, 255, 1, 'mod-brill-home town'),
(970201, 970201, 0, 85, 0, 1, 262144, 2292.954, 349.517, 35.220, 3.3360, 0, 0, 0.995279, -0.097051, 0, 255, 1, 'mod-brill-home town'),
(970202, 970202, 0, 85, 0, 1, 262144, 2286.886, 353.418, 35.220, 3.3360, 0, 0, 0.995279, -0.097051, 0, 255, 1, 'mod-brill-home town'),
(970203, 970203, 0, 85, 0, 1, 1, 2293.005, 353.400, 35.220, 3.3360, 0, 0, 0.995279, -0.097051, 0, 255, 1, 'mod-brill-home town'),
(970204, 970204, 0, 85, 0, 1, 1, 2290.439, 347.799, 35.220, 3.3360, 0, 0, 0.995279, -0.097051, 0, 255, 1, 'mod-brill-home town'),
(970205, 970205, 0, 85, 0, 1, 1, 2285.275, 352.286, 36.120, 0.1944, 0, 0, 0.097051, 0.995279, 0, 255, 1, 'mod-brill-home town'),
(970206, 970206, 0, 85, 0, 1, 1, 2292.857, 356.225, 35.220, 4.9068, 0, 0, 0.635144, -0.772394, 0, 255, 1, 'mod-brill-home town'),
(970207, 970207, 0, 85, 0, 1, 1048576, 2285.658, 349.304, 35.220, 3.3360, 0, 0, 0.995279, -0.097051, 0, 255, 1, 'mod-brill-home town'),
(970208, 970207, 0, 85, 0, 1, 1048576, 2294.604, 350.454, 35.220, 3.3360, 0, 0, 0.995279, -0.097051, 0, 255, 1, 'mod-brill-home town'),
(970209, 970208, 0, 85, 0, 1, 1048576, 2290.921, 351.563, 36.220, 3.3360, 0, 0, 0.995279, -0.097051, 0, 255, 1, 'mod-brill-home town'),
(970210, 970209, 0, 85, 0, 1, 1048576, 2285.819, 350.558, 35.220, 0.1944, 0, 0, 0.097051, 0.995279, 0, 255, 1, 'mod-brill-home town'),
(970211, 970210, 0, 85, 0, 1, 1048576, 2292.832, 348.066, 35.220, 3.3360, 0, 0, 0.995279, -0.097051, 0, 255, 1, 'mod-brill-home town'),
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
INSERT INTO `quest_template_addon` (`ID`, `PrevQuestID`) VALUES (970200, 970121), (970201, 970200), (970202, 970201), (970203, 970202), (970204, 970203), (970205, 970204);
INSERT INTO `quest_offer_reward` (`ID`, `Emote1`, `RewardText`) VALUES
(970200, 1, 'So. A cellar that groans at night, a roof like a sieve, and furniture fit for kindling. I\'ve rebuilt houses for worse owners in worse towns.$B$BThe roof first, then the door, then the floor. Everything else can wait. Nothing in Tirisfal is in a hurry, least of all the people in it.'),
(970201, 1, 'Good oak. Dry, straight, and only a little bloodstained.$B$B(Over the next hours Oswin works without stopping. The broken furniture goes out the door as kindling, the rotten boards come up, the gap in the roof closes, and the door finally shuts the way a door should.)$B$BThere. It\'s a house again, $n. Not a home, not yet. But the rain stays outside, which is a start.'),
(970202, 1, 'Marta, two boys, and a dog called Bramble. And the last letter dated the week the grain came in from Andorhal.$B$B(Oswin is quiet a while.)$B$BI\'ll send the ring to Vengeance Landing with the next ship. Cedric should have it. The toy soldiers... leave them on the shelf. Some things belong to a house more than to a person.'),
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

-- The Last Customer
DELETE FROM `npc_vendor` WHERE `entry` = 970600;
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
(970610, 970610, 0, 85, 0, 1, 4194304, 2341.600, 276.500, 34.450, 2.7000, 0, 0, 0.975723, 0.219007, 0, 255, 1, 'mod-brill-home town'),
(970611, 970611, 0, 85, 0, 1, 4194304, 2341.200, 276.100, 34.460, 2.2000, 0, 0, 0.891207, 0.453596, 0, 255, 1, 'mod-brill-home town'),
(970612, 970612, 0, 85, 0, 1, 4194304, 2344.200, 281.000, 34.510, 2.7000, 0, 0, 0.975723, 0.219007, 0, 255, 1, 'mod-brill-home town'),
(970613, 970613, 0, 85, 0, 1, 4194304, 2343.500, 281.800, 34.440, 1.9000, 0, 0, 0.813416, 0.581683, 0, 255, 1, 'mod-brill-home town'),
(970614, 970614, 0, 85, 0, 1, 4194304, 2344.500, 282.200, 34.470, 3.0000, 0, 0, 0.997495, 0.070737, 0, 255, 1, 'mod-brill-home town'),
(970615, 970615, 0, 85, 0, 1, 4194304, 2340.500, 276.400, 34.470, 2.7000, 0, 0, 0.975723, 0.219007, 0, 255, 1, 'mod-brill-home town'),
(970616, 970616, 0, 85, 0, 1, 4194304, 2342.200, 281.300, 34.390, 2.4000, 0, 0, 0.932039, 0.362358, 0, 255, 1, 'mod-brill-home town'),
(970617, 970617, 0, 85, 0, 1, 4194304, 2340.500, 278.300, 34.430, 2.7000, 0, 0, 0.975723, 0.219007, 0, 255, 1, 'mod-brill-home town'),
(970618, 970618, 0, 85, 0, 1, 4194304, 2347.400, 280.600, 34.770, 1.0000, 0, 0, 0.479426, 0.877583, 0, 255, 1, 'mod-brill-home town');
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
(4194304, 0, 'R970604');
INSERT INTO `quest_template` (`ID`, `QuestType`, `QuestLevel`, `MinLevel`, `QuestSortID`, `Flags`, `RewardXPDifficulty`, `RewardMoney`, `AllowableRaces`, `LogTitle`, `LogDescription`, `QuestDescription`, `AreaDescription`, `QuestCompletionLog`, `RequiredNpcOrGo1`, `RequiredNpcOrGoCount1`, `ObjectiveText1`, `RequiredNpcOrGo2`, `RequiredNpcOrGoCount2`, `ObjectiveText2`, `RequiredNpcOrGo3`, `RequiredNpcOrGoCount3`, `ObjectiveText3`, `RequiredNpcOrGo4`, `RequiredNpcOrGoCount4`, `ObjectiveText4`, `RewardItem1`, `RewardAmount1`, `RewardItem2`, `RewardAmount2`, `RewardItem3`, `RewardAmount3`, `RewardItem4`, `RewardAmount4`) VALUES
(970600, 2, -1, 5, 159, 0, 2, 600, 0, 'Business as Usual', 'Clean the dust from Ambrose Wicke\'s stall behind Brill\'s town hall and find his old order ledger.', 'Closed. Can\'t you read the sign? Oh. The sign fell down. Years ago, I expect.$B$BAmbrose Wicke, candles, lamps and sundries, established in Brill before most of Brill was buried. I don\'t sell, $g lad:lass;. Not until an order is collected. A customer paid in full, and a shop that sells his goods out from under him is a shop that cheats. My father said that, and his father before him, and they were both a great deal warmer than I am now.$B$BBut the dust. The dust I will admit to. I woke behind this counter after the plague and I have been standing at it since, and the moths have had the run of the place. Shake out the rug, clear the crate and the shelf, and look under the counter for my order ledger. Green binding. It has his name in it. I\'d look myself, but I\'m keeping shop.', '', 'Return to Ambrose Wicke in Brill.', 970602, 3, 'Dusty corners cleaned', 970603, 1, 'Order ledger found', 0, 0, '', 0, 0, '', 0, 0, 0, 0, 0, 0, 0, 0),
(970601, 2, -1, 5, 159, 0, 3, 800, 0, 'An Outstanding Order', 'Read the ledger\'s last entry with Ambrose Wicke, find the order wrapped at the back of his stall, and ask Magistrate Sevren in Brill\'s town hall about the customer\'s family.', 'Now that you\'ve found it, you may as well read it. My eyes aren\'t what they were, chiefly because one of them isn\'t anywhere.$B$BThe last entry. Read it with me. He\'ll be here any day: he said so when he paid. Then look at the back of the stall, behind the shelf. His order is wrapped in brown paper and string, the way it\'s been waiting.$B$BAnd ask the Magistrate about the family. Sevren keeps the town\'s books now, such as they are. If the young man\'s moved house, the Magistrate will know where. People move. I did. Into the ground and out again, but it counts.', '', 'Return to Ambrose Wicke in Brill.', 970604, 1, 'The ledger\'s last entry read', 970605, 1, 'The wrapped order found', 970606, 1, 'Magistrate Sevren asked about the Balnirs', 0, 0, '', 0, 0, 0, 0, 0, 0, 0, 0),
(970602, 2, -1, 9, 159, 0, 4, 1500, 0, 'No Forwarding Address', 'Search the Balnir Farmstead, south-east of Brill, for what became of the Balnir family, then return to Ambrose Wicke.', 'The Balnir Farmstead. South and east of here, past the crossroads, where the road bends toward the old Crusade tower. A good farm, once: Tobias\'s father grew snapdragons for the market, of all things, and brought me the wax from his bees.$B$BGo and see. There will be something. A notice on the gate, a family book in the house, the stones in the family plot; farmers write things down, and they bury their people where they can see them from the kitchen.$B$BMind the farm. The Magistrate says nothing living goes there, and from his face I gather nothing dead goes there gladly either. Find out where Tobias went, $n. I don\'t ask you to bring him. Only to tell me where to send word.', '', 'Return to Ambrose Wicke in Brill.', 970607, 3, 'Signs of the Balnirs\' fate found', 0, 0, '', 0, 0, '', 0, 0, '', 858, 2, 0, 0, 0, 0, 0, 0),
(970603, 2, -1, 9, 159, 0, 3, 1000, 0, 'The Gift Never Given', 'Find Agnes Balnir at the Sepulcher in Silverpine Forest.', 'Silverpine. A great many of us who woke there went no further than the Sepulcher: the Dark Lady\'s people keep it, and it\'s the nearest thing to a town those woods have left. If Agnes Balnir is anywhere, she\'s there, or under it.$B$BGo and find her. Don\'t tell her about the candlesticks straight off. Tell her Ambrose Wicke of Brill is holding an order in her brother\'s name, paid in full, and wishes to know what she would like done with it. That\'s the proper form.$B$BAnd $n... if she doesn\'t remember him, don\'t press her. Some of us came back with the whole of our lives. Some came back with a name and a smell of smoke.', '', 'Return to Agnes Balnir at the Sepulcher.', 0, 0, '', 0, 0, '', 0, 0, '', 0, 0, '', 0, 0, 0, 0, 0, 0, 0, 0),
(970604, 2, -1, 9, 159, 0, 4, 2500, 0, 'Closing the Ledger', 'Take Agnes Balnir\'s letter to Ambrose Wicke in Brill and decide what becomes of the candlesticks.', 'I\'ve written to him. It\'s short. I was never one for letters; Tobias wrote them for both of us.$B$BI can\'t say what\'s to be done with them, $n. If I say keep them, I\'m keeping a wedding that never came. If I say sell them, I\'m selling my brother\'s last good idea. So I\'ve told old Wicke the order is yours to settle. You found the farm. You saw the grave. You choose.$B$BTell him one more thing for me, would you? Tell him Tobias isn\'t coming. Not because he doesn\'t care. Whatever climbed out of that grave wasn\'t my brother, and it didn\'t come to me, or to Marian, and it won\'t come to a candle shop. Tell him to open the shutters.', '', 'Return to Ambrose Wicke in Brill.', 970608, 1, 'The candlesticks settled', 0, 0, '', 0, 0, '', 0, 0, '', 7338, 1, 0, 0, 0, 0, 0, 0),
(970605, 2, 1, 1, -1, 0, 0, 0, 0, '[lit]', '', '', '', '', 0, 0, '', 0, 0, '', 0, 0, '', 0, 0, '', 0, 0, 0, 0, 0, 0, 0, 0),
(970606, 2, 1, 1, -1, 0, 0, 0, 0, '[sold]', '', '', '', '', 0, 0, '', 0, 0, '', 0, 0, '', 0, 0, '', 0, 0, 0, 0, 0, 0, 0, 0);
INSERT INTO `quest_template_addon` (`ID`, `PrevQuestID`) VALUES (970600, 0), (970601, 970600), (970602, 970601), (970603, 970602), (970604, 970603);
INSERT INTO `quest_offer_reward` (`ID`, `Emote1`, `RewardText`) VALUES
(970600, 1, 'There it is. The binding\'s gone grey, but then so have I.$B$BAnd the stall looks very nearly like a place someone might buy something. Not that they may. Take this for your trouble: it\'s payment, not a sale, and I\'ll thank you not to confuse the two.'),
(970601, 1, 'Quarantined. By the Crown.$B$BI remember the soldiers on the road. I remember thinking it was a great deal of fuss over grain.$B$BAnd the date. Eleven years. I\'d have sworn it was last spring. You lose the count, standing still; the days don\'t come in and buy anything, so you don\'t notice them. Still. A farm doesn\'t move, $n. Someone there will know where he went.'),
(970602, 1, 'Marian first, then Tobias, then the old man. And the grave open.$B$B...$B$BI sold Wendel Balnir his wife\'s funeral candles, you know. Plain ones. He wouldn\'t take the good ones on credit, though I offered.$B$BBut the girl. Agnes. Her name isn\'t crossed out. In service in Silverpine, the book says. If she lived, or if she did what I did and didn\'t stay dead, she is the order\'s next of kin.'),
(970603, 1, 'Ambrose Wicke? Old Wicke, with the candles? He\'s still... well. Aren\'t we all, still.$B$BTobias\'s candlesticks. He showed me the drawing. Silver, with a vine going up them, and their names on the base and the date. Harvest, the twelfth. Four years they waited for the levies to come down so the wedding could be paid for, and when they finally set the day it was the week the grain came.$B$BI remember it. I remember all of it. That\'s the trouble with me.'),
(970604, 1, '"The order is settled. Signed, A. Balnir, next of kin."$B$BWell. That\'s that, then.$B$BEleven years I\'ve kept this stall shut for a young man who was in the ground before I was. And I\'d have kept it shut eleven more, $n, because while the order was open I was still a shopkeeper with a customer coming, and not just a dead man standing behind a counter.$B$BHelp me with the shutters. We\'re open. Take this from stock, on the house: it\'s been waiting for someone too.');
INSERT INTO `quest_request_items` (`ID`, `EmoteOnComplete`, `EmoteOnIncomplete`, `CompletionText`) VALUES
(970600, 1, 1, 'The ledger, $n. Green binding. Under the counter. I\'d know it by the smell if I still had one.'),
(970601, 1, 1, 'Has the Magistrate said where he\'s gone? People don\'t just stop coming for their orders.'),
(970602, 1, 1, 'Well? Is there a forwarding address? Even the dead leave forwarding addresses. I left one. It\'s here.'),
(970604, 1, 1, 'You\'ve given him the letter? What did he say?');
INSERT INTO `creature_queststarter` (`id`, `quest`) VALUES (970600, 970600), (970600, 970601), (970600, 970602), (970600, 970603), (970601, 970604);
INSERT INTO `creature_questender` (`id`, `quest`) VALUES (970600, 970600), (970600, 970601), (970600, 970602), (970601, 970603), (970600, 970604);

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
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970210, '(Letters tied with twine, all to Cedric, all from Marta. The early ones are about the harvest and the boys and a dog called Bramble. The last is short:$B$B\'They say the grain from Andorhal is bad. Tobias is coughing. Come home quickly. I don\'t like how quiet the road is.\'$B$BShe never wrote again.)', '(Letters tied with twine, all to Cedric, all from Marta. The early ones are about the harvest and the boys and a dog called Bramble. The last is short:$B$B\'They say the grain from Andorhal is bad. Tobias is coughing. Come home quickly. I don\'t like how quiet the road is.\'$B$BShe never wrote again.)', 1);
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970211, '(Marta\'s letters to Cedric, tied with twine.)', '(Marta\'s letters to Cedric, tied with twine.)', 1);
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
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES (970615, '(A family book, its cover burnt through at one corner. Inside, the Balnirs: Wendel and Ada, and their children Tobias and Agnes. Below Tobias someone has written \'to wed Marian Hale\', and the date has been scratched out and written again three times. Then the deaths, all one autumn, in Wendel\'s shaking hand: Marian. Tobias. Ada. The last line is in another hand: Wendel. Agnes\'s name alone is not crossed out. Beside it: in service, Silverpine.)', '(A family book, its cover burnt through at one corner. Inside, the Balnirs: Wendel and Ada, and their children Tobias and Agnes. Below Tobias someone has written \'to wed Marian Hale\', and the date has been scratched out and written again three times. Then the deaths, all one autumn, in Wendel\'s shaking hand: Marian. Tobias. Ada. The last line is in another hand: Wendel. Agnes\'s name alone is not crossed out. Beside it: in service, Silverpine.)', 1);
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
-- (5 of the town's 14 phases used)
