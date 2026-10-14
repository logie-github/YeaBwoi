-- Wrath Remix's people: the Infinite Dragonflight and the Keepers of Time who run the experiment,
-- the time-echoes and anomalies of the prologue, and the Bazaar's instructors. New entries in
-- Remix's own range (770001+), on models the 3.3.5a client has: Moratari is a blood elf woman in
-- dark plate with glowing runes (Lady Alistra's look), Eternus the Infinite Dragonflight's
-- Chrono-Lord Epoch, the Keepers their own Caverns of Time selves.

SET @MORATARI := 770001, @ETERNUS := 770002, @ANDORMU := 770003, @NOZARI := 770004,
    @ALURMI := 770005, @ZEPHYR := 770006, @SAAT := 770007, @CHRONOLOGIST := 770008,
    @MOMENTUS := 770009, @ECHO_LANDING := 770021, @ECHO_WRATHGATE := 770022,
    @ECHO_ICECROWN := 770023, @ANOMALY := 770031, @ANOMALY_CREDIT := 770032;

-- Until build 56 Remix's creatures were 7700001 and up, which made the core keep a table of
-- creature templates 7.7 million long (its fast lookups are indexed by entry): gone.
DELETE FROM `creature_template` WHERE `entry` BETWEEN 7700001 AND 7700199;
DELETE FROM `creature_template_model` WHERE `CreatureID` BETWEEN 7700001 AND 7700199;
DELETE FROM `creature_template_addon` WHERE `entry` BETWEEN 7700001 AND 7700199;
DELETE FROM `creature_equip_template` WHERE `CreatureID` BETWEEN 7700001 AND 7700199;
DELETE FROM `creature_default_trainer` WHERE `CreatureId` BETWEEN 7700001 AND 7700199;

DELETE FROM `creature_template` WHERE `entry` BETWEEN 770001 AND 770199;
INSERT INTO `creature_template` (`entry`, `name`, `subname`, `minlevel`, `maxlevel`, `exp`, `faction`, `npcflag`,
  `speed_walk`, `speed_run`, `rank`, `unit_class`, `unit_flags`, `unit_flags2`, `type`, `flags_extra`,
  `HealthModifier`, `DamageModifier`, `BaseAttackTime`, `RegenHealth`, `ScriptName`) VALUES
(@MORATARI,       'Moratari',       'Infinite Dragonflight',   80, 80, 2, 35, 3, 1, 1.14286, 0, 1, 0, 2048, 7, 2, 10, 1, 2000, 1, 'npc_remix_moratari'),
(@ETERNUS,        'Eternus',        'Infinite Dragonflight',   83, 83, 2, 35, 3, 1, 1.14286, 3, 2, 0, 2048, 2, 2, 25, 1, 2000, 1, 'npc_remix_eternus'),
(@ANDORMU,        'Andormu',        'Keepers of Time',         80, 80, 2, 35, 3, 1, 1.14286, 0, 1, 0, 2048, 2, 2, 10, 1, 2000, 1, 'npc_remix_keeper'),
(@NOZARI,         'Nozari',         'Keepers of Time',         80, 80, 2, 35, 3, 1, 1.14286, 0, 1, 0, 2048, 2, 2, 10, 1, 2000, 1, 'npc_remix_keeper'),
(@ALURMI,         'Alurmi',         'Bronze Quartermaster',    80, 80, 2, 35, 1, 1, 1.14286, 0, 1, 0, 2048, 7, 2, 10, 1, 2000, 1, 'npc_remix_alurmi'),
(@ZEPHYR,         'Zephyr',         'Keeper of the Ways',      80, 80, 2, 35, 1, 1, 1.14286, 0, 1, 0, 2048, 2, 2, 10, 1, 2000, 1, 'npc_remix_zephyr'),
(@SAAT,           'Sa''at',         'Timeline Safety',         80, 80, 2, 35, 1, 1, 1.14286, 0, 1, 0, 2048, 2, 2, 10, 1, 2000, 1, 'npc_remix_saat'),
(@CHRONOLOGIST,   'Chronologist',   'Wrath Remix',             80, 80, 2, 35, 1, 1, 1.14286, 0, 1, 0, 2048, 2, 2, 10, 1, 2000, 1, 'npc_remix_chronologist'),
(@MOMENTUS,       'Momentus',       'Infinite Power',          80, 80, 2, 35, 1, 1, 1.14286, 0, 2, 0, 2048, 2, 2, 10, 1, 2000, 1, 'npc_remix_momentus'),
(@ECHO_LANDING,   'Echo of the Landing',   'A frozen moment',  80, 80, 2, 35, 1, 1, 1.14286, 0, 1, 0, 2048, 7, 2, 1, 1, 2000, 1, 'npc_remix_echo'),
(@ECHO_WRATHGATE, 'Echo of the Wrathgate', 'A frozen moment',  80, 80, 2, 35, 1, 1, 1.14286, 0, 1, 0, 2048, 7, 2, 1, 1, 2000, 1, 'npc_remix_echo'),
(@ECHO_ICECROWN,  'Echo of Icecrown',      'A frozen moment',  80, 80, 2, 35, 1, 1, 1.14286, 0, 1, 0, 2048, 6, 2, 1, 1, 2000, 1, 'npc_remix_echo'),
(@ANOMALY,        'Temporal Anomaly', NULL,                    10, 10, 0, 14, 0, 1, 1.14286, 0, 1, 0, 2048, 2, 0, 0.8, 0.5, 2000, 1, 'npc_remix_anomaly'),
-- WRM-003's credit (never spawned): each anomaly's death gives it to the Timerunner it came for,
-- whoever struck the blow (a passing bot, Moratari's company).
(@ANOMALY_CREDIT, 'Temporal Anomaly', NULL,                    10, 10, 0, 14, 0, 1, 1.14286, 0, 1, 0, 2048, 2, 128, 1, 1, 2000, 1, '');

DELETE FROM `creature_template_model` WHERE `CreatureID` BETWEEN 770001 AND 770199;
INSERT INTO `creature_template_model` (`CreatureID`, `Idx`, `CreatureDisplayID`, `DisplayScale`, `Probability`, `VerifiedBuild`) VALUES
(@MORATARI, 0, 25458, 1, 1, 0),
(@ETERNUS, 0, 26580, 1, 1, 0),
(@ANDORMU, 0, 19267, 1, 1, 0),
(@NOZARI, 0, 19268, 1, 1, 0),
(@ALURMI, 0, 20384, 1, 1, 0),
(@ZEPHYR, 0, 19282, 1, 1, 0),
(@SAAT, 0, 19411, 1, 1, 0),
(@CHRONOLOGIST, 0, 19373, 1, 1, 0),
(@MOMENTUS, 0, 19063, 1, 1, 0),
(@ECHO_LANDING, 0, 23033, 1, 1, 0),
(@ECHO_WRATHGATE, 0, 24879, 1, 1, 0),
(@ECHO_ICECROWN, 0, 24191, 1, 1, 0),
(@ANOMALY, 0, 20515, 1, 1, 0),
(@ANOMALY_CREDIT, 0, 20515, 1, 1, 0);

DELETE FROM `creature_template_addon` WHERE `entry` BETWEEN 770001 AND 770199;
INSERT INTO `creature_template_addon` (`entry`, `path_id`, `mount`, `bytes1`, `bytes2`, `emote`, `visibilityDistanceType`, `auras`) VALUES
-- The echoes: half seen, with motes of time about them.
(@ECHO_LANDING, 0, 0, 0, 1, 0, 0, '44816 37119'),
(@ECHO_WRATHGATE, 0, 0, 0, 1, 0, 0, '44816 37119'),
(@ECHO_ICECROWN, 0, 0, 0, 1, 0, 0, '44816 37119');

DELETE FROM `creature_equip_template` WHERE `CreatureID` BETWEEN 770001 AND 770199;
INSERT INTO `creature_equip_template` (`CreatureID`, `ID`, `ItemID1`, `ItemID2`, `ItemID3`, `VerifiedBuild`) VALUES
(@MORATARI, 1, 39287, 0, 0, 0),
(@ETERNUS, 1, 38609, 0, 0, 0),
(@ZEPHYR, 1, 13861, 0, 0, 0);

-- What each of them says when spoken to (the module picks the text).
DELETE FROM `npc_text` WHERE `ID` BETWEEN 7702001 AND 7702199;
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES
(7702001, 'There you are. Welcome to the Caverns of Time, $n, or rather to a very small, very careful copy of a very large and careless war.$B$BI am Moratari. I study variables. You, I suspect, are about to become my favourite one.',
          'There you are. Welcome to the Caverns of Time, $n, or rather to a very small, very careful copy of a very large and careless war.$B$BI am Moratari. I study variables. You, I suspect, are about to become my favourite one.', 1),
(7702002, 'Hello again, Timerunner. History is holding up remarkably well, considering you are in it.$B$BWhat is history, if not a series of variables?',
          'Hello again, Timerunner. History is holding up remarkably well, considering you are in it.$B$BWhat is history, if not a series of variables?', 1),
(7702003, 'Nozdormu is very clear that we may not change what happened here. He is rather less clear about how long it has to take.$B$BIf the record ever becomes tangled, tell me, and I will return you to the last chapter that held together.',
          'Nozdormu is very clear that we may not change what happened here. He is rather less clear about how long it has to take.$B$BIf the record ever becomes tangled, tell me, and I will return you to the last chapter that held together.', 1),
(7702010, 'A champion is more than an obedient witness. Let us learn how much of the road truly matters, and how strong you can become on it, without the destination moving an inch.',
          'A champion is more than an obedient witness. Let us learn how much of the road truly matters, and how strong you can become on it, without the destination moving an inch.', 1),
(7702011, 'Two shores, one war. Your people landed on both. Choose where you will begin, and Moratari will meet you there.',
          'Two shores, one war. Your people landed on both. Choose where you will begin, and Moratari will meet you there.', 1),
(7702020, 'The experiment may shorten the road. It will not rewrite the destination. The Wrathgate must fall. The Frozen Throne must stand until its appointed hour.',
          'The experiment may shorten the road. It will not rewrite the destination. The Wrathgate must fall. The Frozen Throne must stand until its appointed hour.', 1),
(7702021, 'Every deviation is written here: every anomaly sealed, every errand the record forgave. The sum of them, so far, is a champion. That is acceptable.',
          'Every deviation is written here: every anomaly sealed, every errand the record forgave. The sum of them, so far, is a champion. That is acceptable.', 1),
(7702030, 'Bronze, Timerunner. Time leaves it everywhere you go: every errand finished, every foe felled. The Keepers trade in it, and so do I.',
          'Bronze, Timerunner. Time leaves it everywhere you go: every errand finished, every foe felled. The Keepers trade in it, and so do I.', 1),
(7702040, 'Every road in Northrend runs through this camp, if you know how to fold it. Where to?',
          'Every road in Northrend runs through this camp, if you know how to fold it. Where to?', 1),
(7702050, 'The dungeons of Northrend keep their own hours. Within the experiment they keep yours: each is waiting at the level of the chapter it belongs to.',
          'The dungeons of Northrend keep their own hours. Within the experiment they keep yours: each is waiting at the level of the chapter it belongs to.', 1),
(7702060, 'Seven chapters, ten levels to eighty, one ending that does not move. Here is where you stand in the record.',
          'Seven chapters, ten levels to eighty, one ending that does not move. Here is where you stand in the record.', 1),
(7702070, 'Infinite Power is what accumulates when a champion is allowed to keep everything they learn. The Keepers have not yet agreed to let me hand it out. They will.',
          'Infinite Power is what accumulates when a champion is allowed to keep everything they learn. The Keepers have not yet agreed to let me hand it out. They will.', 1),
(7702080, 'The fleets reach Northrend. Alliance banners at Valiance Keep, Horde banners at Warsong Hold, and in the ice beyond them, something that has been waiting a very long time.',
          'The fleets reach Northrend. Alliance banners at Valiance Keep, Horde banners at Warsong Hold, and in the ice beyond them, something that has been waiting a very long time.', 1),
(7702081, 'Angrathar. Bolvar Fordragon and Dranosh Saurfang lead the charge to the gate of Icecrown, and the plague falls on friend and foe alike. This moment cannot be moved.',
          'Angrathar. Bolvar Fordragon and Dranosh Saurfang lead the charge to the gate of Icecrown, and the plague falls on friend and foe alike. This moment cannot be moved.', 1),
(7702082, 'The Frozen Throne. The Lich King waits at the top of the world, and he is not afraid of anything. The record says he should have been.',
          'The Frozen Throne. The Lich King waits at the top of the world, and he is not afraid of anything. The record says he should have been.', 1);

-- The Bazaar's instructors: one for each class, every rank of every spell up to your level, as a
-- capital's trainer teaches them (their trainer lists are the capitals' own). Paladins have two,
-- for the Alliance's and the Horde's seals.
DELETE FROM `creature_template` WHERE `entry` BETWEEN 770101 AND 770112;
INSERT INTO `creature_template` (`entry`, `name`, `subname`, `minlevel`, `maxlevel`, `exp`, `faction`, `npcflag`,
  `speed_walk`, `speed_run`, `rank`, `unit_class`, `unit_flags`, `unit_flags2`, `type`, `flags_extra`,
  `HealthModifier`, `DamageModifier`, `BaseAttackTime`, `RegenHealth`, `ScriptName`) VALUES
(770101, 'Timeworn Instructor', 'Warrior Trainer',          80, 80, 2, 35, 48, 1, 1.14286, 0, 1, 0, 2048, 7, 2, 10, 1, 2000, 1, ''),
(770102, 'Timeworn Instructor', 'Paladin Trainer',          80, 80, 2, 35, 48, 1, 1.14286, 0, 2, 0, 2048, 7, 2, 10, 1, 2000, 1, ''),
(770103, 'Timeworn Instructor', 'Paladin Trainer (Horde)',  80, 80, 2, 35, 48, 1, 1.14286, 0, 2, 0, 2048, 7, 2, 10, 1, 2000, 1, ''),
(770104, 'Timeworn Instructor', 'Hunter Trainer',           80, 80, 2, 35, 48, 1, 1.14286, 0, 1, 0, 2048, 7, 2, 10, 1, 2000, 1, ''),
(770105, 'Timeworn Instructor', 'Rogue Trainer',            80, 80, 2, 35, 48, 1, 1.14286, 0, 1, 0, 2048, 7, 2, 10, 1, 2000, 1, ''),
(770106, 'Timeworn Instructor', 'Priest Trainer',           80, 80, 2, 35, 48, 1, 1.14286, 0, 2, 0, 2048, 7, 2, 10, 1, 2000, 1, ''),
(770107, 'Timeworn Instructor', 'Death Knight Trainer',     80, 80, 2, 35, 48, 1, 1.14286, 0, 1, 0, 2048, 7, 2, 10, 1, 2000, 1, ''),
(770108, 'Timeworn Instructor', 'Shaman Trainer',           80, 80, 2, 35, 48, 1, 1.14286, 0, 2, 0, 2048, 7, 2, 10, 1, 2000, 1, ''),
(770109, 'Timeworn Instructor', 'Mage Trainer',             80, 80, 2, 35, 48, 1, 1.14286, 0, 8, 0, 2048, 7, 2, 10, 1, 2000, 1, ''),
(770110, 'Timeworn Instructor', 'Warlock Trainer',          80, 80, 2, 35, 48, 1, 1.14286, 0, 8, 0, 2048, 7, 2, 10, 1, 2000, 1, ''),
(770111, 'Timeworn Instructor', 'Druid Trainer',            80, 80, 2, 35, 48, 1, 1.14286, 0, 2, 0, 2048, 7, 2, 10, 1, 2000, 1, '');

-- The Keepers' Steward of Time look, one for each.
INSERT INTO `creature_template_model` (`CreatureID`, `Idx`, `CreatureDisplayID`, `DisplayScale`, `Probability`, `VerifiedBuild`)
SELECT `entry`, 0, 19373, 1, 1, 0 FROM `creature_template` WHERE `entry` BETWEEN 770101 AND 770111;

DELETE FROM `creature_default_trainer` WHERE `CreatureId` BETWEEN 770101 AND 770112;
INSERT INTO `creature_default_trainer` (`CreatureId`, `TrainerId`) VALUES
(770101, 1), (770102, 3), (770103, 4), (770104, 7), (770105, 9), (770106, 11),
(770107, 13), (770108, 14), (770109, 16), (770110, 31), (770111, 33);
