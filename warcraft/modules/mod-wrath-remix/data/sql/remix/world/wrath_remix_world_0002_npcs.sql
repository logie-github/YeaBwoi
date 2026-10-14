-- Wrath Remix's people (STORY.md): Eternus of the Infinite Dragonflight, who brings Timerunners to
-- Northrend to change the Lich King's war, his lieutenant Moratari, the Keepers of Time, the
-- prologue's echoes of the war's dead and its anomalies, and the Bazaar's instructors. New entries
-- in Remix's own range (770001+), on models the 3.3.5a client has: Moratari is a blood elf woman in
-- dark plate with glowing runes (Lady Alistra's look), Eternus the Infinite Dragonflight's
-- Chrono-Lord Epoch, the Keepers their own Caverns of Time selves, the echoes the people they were.

SET @MORATARI := 770001, @ETERNUS := 770002, @ANDORMU := 770003, @NOZARI := 770004,
    @ALURMI := 770005, @ZEPHYR := 770006, @SAAT := 770007, @CHRONOLOGIST := 770008,
    @MOMENTUS := 770009, @ECHO_FOOTMAN := 770021, @ECHO_SAURFANG := 770022,
    @ECHO_BOLVAR := 770023, @ANOMALY := 770031, @ANOMALY_CREDIT := 770032, @INSTRUCTOR_CREDIT := 770033;

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
(@ALURMI,         'Alurmi',         'Bronze Quartermaster',    80, 80, 2, 35, 129, 1, 1.14286, 0, 1, 0, 2048, 7, 2, 10, 1, 2000, 1, 'npc_remix_alurmi'),
(@ZEPHYR,         'Zephyr',         'Keeper of the Ways',      80, 80, 2, 35, 1, 1, 1.14286, 0, 1, 0, 2048, 2, 2, 10, 1, 2000, 1, 'npc_remix_zephyr'),
(@SAAT,           'Sa''at',         'Timeline Safety',         80, 80, 2, 35, 1, 1, 1.14286, 0, 1, 0, 2048, 2, 2, 10, 1, 2000, 1, 'npc_remix_saat'),
(@CHRONOLOGIST,   'Chronologist',   'Wrath Remix',             80, 80, 2, 35, 1, 1, 1.14286, 0, 1, 0, 2048, 2, 2, 10, 1, 2000, 1, 'npc_remix_chronologist'),
(@MOMENTUS,       'Momentus',       'Infinite Power',          80, 80, 2, 35, 1, 1, 1.14286, 0, 2, 0, 2048, 2, 2, 10, 1, 2000, 1, 'npc_remix_momentus'),
-- The war's dead as Eternus shows them, frozen at the moment they fell.
(@ECHO_FOOTMAN,   'Echo of a Fallen Footman', 'Valiance Keep, the first day', 80, 80, 2, 35, 1, 1, 1.14286, 0, 1, 0, 2048, 7, 2, 1, 1, 2000, 1, 'npc_remix_echo'),
(@ECHO_SAURFANG,  'Echo of Dranosh Saurfang', 'Angrathar, the Wrathgate',     80, 80, 2, 35, 1, 1, 1.14286, 0, 1, 0, 2048, 7, 2, 1, 1, 2000, 1, 'npc_remix_echo'),
(@ECHO_BOLVAR,    'Echo of Bolvar Fordragon', 'Angrathar, the Wrathgate',     80, 80, 2, 35, 1, 1, 1.14286, 0, 1, 0, 2048, 7, 2, 1, 1, 2000, 1, 'npc_remix_echo'),
(@ANOMALY,        'Temporal Anomaly', NULL,                    10, 10, 0, 14, 0, 1, 1.14286, 0, 1, 0, 2048, 2, 0, 0.8, 0.5, 2000, 1, 'npc_remix_anomaly'),
-- WRM-013's credit (never spawned): each anomaly's death gives it to the Timerunner it came for,
-- whoever struck the blow (a passing bot, Moratari's company).
(@ANOMALY_CREDIT, 'Temporal Anomaly', NULL,                    10, 10, 0, 14, 0, 1, 1.14286, 0, 1, 0, 2048, 2, 128, 1, 1, 2000, 1, ''),
-- WRM-012's (never spawned): given by any of the Bazaar's instructors as they're spoken to.
(@INSTRUCTOR_CREDIT, 'Your class''s instructor', NULL,          80, 80, 0, 35, 0, 1, 1.14286, 0, 1, 0, 2048, 7, 128, 1, 1, 2000, 1, '');

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
(@ECHO_FOOTMAN, 0, 23031, 1, 1, 0),    -- a Valiance Keep Footman
(@ECHO_SAURFANG, 0, 23034, 1, 1, 0),   -- Saurfang the Younger
(@ECHO_BOLVAR, 0, 24879, 1, 1, 0),     -- Highlord Bolvar Fordragon at the Wrathgate
(@ANOMALY, 0, 20515, 1, 1, 0),
(@ANOMALY_CREDIT, 0, 20515, 1, 1, 0),
(@INSTRUCTOR_CREDIT, 0, 19373, 1, 1, 0);

DELETE FROM `creature_template_addon` WHERE `entry` BETWEEN 770001 AND 770199;
INSERT INTO `creature_template_addon` (`entry`, `path_id`, `mount`, `bytes1`, `bytes2`, `emote`, `visibilityDistanceType`, `auras`) VALUES
-- The echoes: half seen, with motes of time about them.
(@ECHO_FOOTMAN, 0, 0, 0, 1, 0, 0, '44816 37119'),
(@ECHO_SAURFANG, 0, 0, 0, 1, 0, 0, '44816 37119'),
(@ECHO_BOLVAR, 0, 0, 0, 1, 0, 0, '44816 37119');

DELETE FROM `creature_equip_template` WHERE `CreatureID` BETWEEN 770001 AND 770199;
INSERT INTO `creature_equip_template` (`CreatureID`, `ID`, `ItemID1`, `ItemID2`, `ItemID3`, `VerifiedBuild`) VALUES
(@MORATARI, 1, 39287, 0, 0, 0),
(@ETERNUS, 1, 38609, 0, 0, 0),
(@ZEPHYR, 1, 13861, 0, 0, 0);

-- What each of them says when spoken to (the module picks the text).
DELETE FROM `npc_text` WHERE `ID` BETWEEN 7702001 AND 7702199;
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES
-- Moratari: before the first quest is done, as you wake; after; on where you are in history.
(7702001, 'Easy. Breathe. You were somewhere else a moment ago, $n: your own time, years from now. You are in Northrend now, at the start of the war against the Lich King, and you feel weaker because you are. Time takes its toll on travellers.$B$BI am Moratari. Eternus brought you here. He will want to tell you why himself.',
          'Easy. Breathe. You were somewhere else a moment ago, $n: your own time, years from now. You are in Northrend now, at the start of the war against the Lich King, and you feel weaker because you are. Time takes its toll on travellers.$B$BI am Moratari. Eternus brought you here. He will want to tell you why himself.', 1),
(7702002, 'Hello again, Timerunner. The war is still where we left it. That is either reassuring or the problem, depending on whom you ask.',
          'Hello again, Timerunner. The war is still where we left it. That is either reassuring or the problem, depending on whom you ask.', 1),
(7702003, 'Every Timerunner I have sent into this war, I can find again. If you lose your way, I will return you to the start of the chapter you are in.',
          'Every Timerunner I have sent into this war, I can find again. If you lose your way, I will return you to the start of the chapter you are in.', 1),
-- Eternus.
(7702010, 'I have watched this war more times than I can count, champion. Every time, the same graves. I did not bring you here to watch it with me.',
          'I have watched this war more times than I can count, champion. Every time, the same graves. I did not bring you here to watch it with me.', 1),
(7702011, 'The bronze will tell you history is sacred. The bronze let Stratholme burn, and called it duty. I would rather save someone.',
          'The bronze will tell you history is sacred. The bronze let Stratholme burn, and called it duty. I would rather save someone.', 1),
-- The Keepers of Time (with Sylvanas and Alleria, after the turn).
(7702020, 'The Infinite were always going to try this. We were always going to need someone who remembers how it ends.',
          'The Infinite were always going to try this. We were always going to need someone who remembers how it ends.', 1),
(7702021, 'I keep the record of every change the Infinite have tried, and every one you have stopped. It is a long record. Yours is a good part of it.',
          'I keep the record of every change the Infinite have tried, and every one you have stopped. It is a long record. Yours is a good part of it.', 1),
-- The Bazaar's services.
(7702030, 'Bronze, Timerunner. Time leaves it everywhere you go: every errand finished, every foe felled. I trade in it, and I do not ask whose side you are on.',
          'Bronze, Timerunner. Time leaves it everywhere you go: every errand finished, every foe felled. I trade in it, and I do not ask whose side you are on.', 1),
(7702040, 'Every road in Northrend runs through this camp, if you know how to fold it. Where to?',
          'Every road in Northrend runs through this camp, if you know how to fold it. Where to?', 1),
(7702050, 'The dungeons of Northrend keep their own hours. Here they keep yours: each is waiting at the level of the chapter it belongs to.',
          'The dungeons of Northrend keep their own hours. Here they keep yours: each is waiting at the level of the chapter it belongs to.', 1),
(7702060, 'Seven chapters, ten levels to eighty, and the Lich King at the end of them. Here is where you stand.',
          'Seven chapters, ten levels to eighty, and the Lich King at the end of them. Here is where you stand.', 1),
(7702070, 'Infinite Power is what a champion keeps when time gives back what it took. Not yet, Timerunner. Soon.',
          'Infinite Power is what a champion keeps when time gives back what it took. Not yet, Timerunner. Soon.', 1),
-- The echoes (WRM-011): the dead Eternus means to save.
(7702080, 'The boats came in under the arrows and the cold. I made it off the beach. I did not make it off the second day. When I stood up again, I was fighting for them.',
          'The boats came in under the arrows and the cold. I made it off the beach. I did not make it off the second day. When I stood up again, I was fighting for them.', 1),
(7702081, 'For the Horde... We had him. The gate was open and he came down to meet us, and then the sky went green. Tell my father I did not run.',
          'For the Horde... We had him. The gate was open and he came down to meet us, and then the sky went green. Tell my father I did not run.', 1),
(7702082, 'The plague, then the fire. Then the cold, and a voice telling me that someone has to hold it. It is so very cold.',
          'The plague, then the fire. Then the cold, and a voice telling me that someone has to hold it. It is so very cold.', 1);

-- The Bazaar's instructors: one for each class, every rank of every spell up to your level, as a
-- capital's trainer teaches them (their trainer lists are the capitals' own). Paladins have two,
-- for the Alliance's and the Horde's seals.
DELETE FROM `creature_template` WHERE `entry` BETWEEN 770101 AND 770112;
INSERT INTO `creature_template` (`entry`, `name`, `subname`, `minlevel`, `maxlevel`, `exp`, `faction`, `npcflag`,
  `speed_walk`, `speed_run`, `rank`, `unit_class`, `unit_flags`, `unit_flags2`, `type`, `flags_extra`,
  `HealthModifier`, `DamageModifier`, `BaseAttackTime`, `RegenHealth`, `ScriptName`) VALUES
(770101, 'Timeworn Instructor', 'Warrior Trainer',          80, 80, 2, 35, 49, 1, 1.14286, 0, 1, 0, 2048, 7, 2, 10, 1, 2000, 1, 'npc_remix_instructor'),
(770102, 'Timeworn Instructor', 'Paladin Trainer',          80, 80, 2, 35, 49, 1, 1.14286, 0, 2, 0, 2048, 7, 2, 10, 1, 2000, 1, 'npc_remix_instructor'),
(770103, 'Timeworn Instructor', 'Paladin Trainer (Horde)',  80, 80, 2, 35, 49, 1, 1.14286, 0, 2, 0, 2048, 7, 2, 10, 1, 2000, 1, 'npc_remix_instructor'),
(770104, 'Timeworn Instructor', 'Hunter Trainer',           80, 80, 2, 35, 49, 1, 1.14286, 0, 1, 0, 2048, 7, 2, 10, 1, 2000, 1, 'npc_remix_instructor'),
(770105, 'Timeworn Instructor', 'Rogue Trainer',            80, 80, 2, 35, 49, 1, 1.14286, 0, 1, 0, 2048, 7, 2, 10, 1, 2000, 1, 'npc_remix_instructor'),
(770106, 'Timeworn Instructor', 'Priest Trainer',           80, 80, 2, 35, 49, 1, 1.14286, 0, 2, 0, 2048, 7, 2, 10, 1, 2000, 1, 'npc_remix_instructor'),
(770107, 'Timeworn Instructor', 'Death Knight Trainer',     80, 80, 2, 35, 49, 1, 1.14286, 0, 1, 0, 2048, 7, 2, 10, 1, 2000, 1, 'npc_remix_instructor'),
(770108, 'Timeworn Instructor', 'Shaman Trainer',           80, 80, 2, 35, 49, 1, 1.14286, 0, 2, 0, 2048, 7, 2, 10, 1, 2000, 1, 'npc_remix_instructor'),
(770109, 'Timeworn Instructor', 'Mage Trainer',             80, 80, 2, 35, 49, 1, 1.14286, 0, 8, 0, 2048, 7, 2, 10, 1, 2000, 1, 'npc_remix_instructor'),
(770110, 'Timeworn Instructor', 'Warlock Trainer',          80, 80, 2, 35, 49, 1, 1.14286, 0, 8, 0, 2048, 7, 2, 10, 1, 2000, 1, 'npc_remix_instructor'),
(770111, 'Timeworn Instructor', 'Druid Trainer',            80, 80, 2, 35, 49, 1, 1.14286, 0, 2, 0, 2048, 7, 2, 10, 1, 2000, 1, 'npc_remix_instructor');

-- The Keepers' Steward of Time look, one for each.
INSERT INTO `creature_template_model` (`CreatureID`, `Idx`, `CreatureDisplayID`, `DisplayScale`, `Probability`, `VerifiedBuild`)
SELECT `entry`, 0, 19373, 1, 1, 0 FROM `creature_template` WHERE `entry` BETWEEN 770101 AND 770111;

DELETE FROM `creature_default_trainer` WHERE `CreatureId` BETWEEN 770101 AND 770112;
INSERT INTO `creature_default_trainer` (`CreatureId`, `TrainerId`) VALUES
(770101, 1), (770102, 3), (770103, 4), (770104, 7), (770105, 9), (770106, 11),
(770107, 13), (770108, 14), (770109, 16), (770110, 31), (770111, 33);
