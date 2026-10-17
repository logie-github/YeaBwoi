-- The opening (src/RemixIntro.cpp): a new Timerunner's first moments are at the Broken Front in
-- Icecrown, at the war's worst hour, before Moratari pulls them out to the Bazaar. Its people are
-- the game's own, under entries of their own (770120 to 770149) so nothing of theirs (their
-- scripts, their loot, their vehicles) comes with them: the Scourge (ghouls, an abomination, crypt
-- fiends, a deathbringer, a frost wyrm, the val'kyr) and the Lich King with Frostmourne; Highlord
-- Tirion Fordring with the Ashbringer, the Argent Crusade, the Skybreaker's marines and the
-- Kor'kron; and the unseen ones the effects and the voices come from. All of them are summoned for
-- the one Timerunner, in a phase of their own; the script sets their sides, levels and health.

DROP TEMPORARY TABLE IF EXISTS `remix_intro_cast`;
CREATE TEMPORARY TABLE `remix_intro_cast` (
  `entry` INT UNSIGNED NOT NULL,
  `source` INT UNSIGNED NOT NULL,
  `name` VARCHAR(100) NOT NULL,
  `subname` VARCHAR(100) NULL,
  `unit_flags` INT UNSIGNED NOT NULL,
  `flags_extra` INT UNSIGNED NOT NULL,
  `faction` SMALLINT UNSIGNED NOT NULL,
  PRIMARY KEY (`entry`)
);
-- unit_flags: 0x100 the player can't attack them, 0x200 nor creatures, 0x2 nobody (the Lich King,
-- who is not there to be fought; the unseen ones). flags_extra 0x80: unseen (a trigger). Sides:
-- the Scourge 2068, and against it 250, which every player, Alliance or Horde, sees as a friend.
INSERT INTO `remix_intro_cast` (`entry`, `source`, `name`, `subname`, `unit_flags`, `flags_extra`, `faction`) VALUES
(770120, 26125, 'Risen Ghoul', NULL, 0, 0, 2068),
(770121, 30597, 'Spiked Ghoul', NULL, 0, 0, 2068),
(770122, 28843, 'Bloated Abomination', NULL, 0, 0, 2068),
(770123, 27734, 'Crypt Fiend', NULL, 0, 0, 2068),
(770124, 36892, 'Ymirjar Deathbringer', NULL, 0, 0, 2068),
(770125, 28670, 'Frostbrood Vanquisher', NULL, 770, 0, 2068),
(770126, 28534, 'Val''kyr Battle-maiden', NULL, 770, 0, 2068),
(770127, 36597, 'The Lich King', NULL, 770, 0, 2068),
(770128, 38995, 'Highlord Tirion Fordring', 'The Argent Crusade', 256, 0, 250),
(770129, 38493, 'Argent Crusader', NULL, 256, 0, 250),
(770130, 28029, 'Argent Crusader', NULL, 256, 0, 250),
(770131, 30352, 'Skybreaker Marine', NULL, 256, 0, 250),
(770132, 30755, 'Kor''kron Reaver', NULL, 256, 0, 250),
(770133, 21252, 'The Argent Crusade', NULL, 770, 128, 250),
(770134, 21252, 'The Scourge', NULL, 770, 128, 2068),
(770135, 21252, 'Eternus', NULL, 770, 128, 35),
(770136, 21252, 'The Lich King', NULL, 770, 128, 35);

DELETE FROM `creature_template` WHERE `entry` BETWEEN 770120 AND 770149;
DELETE FROM `creature_template_model` WHERE `CreatureID` BETWEEN 770120 AND 770149;
DELETE FROM `creature_equip_template` WHERE `CreatureID` BETWEEN 770120 AND 770149;

DROP TEMPORARY TABLE IF EXISTS `remix_intro_template`;
CREATE TEMPORARY TABLE `remix_intro_template` SELECT c.`entry` AS `cast_entry`, t.* FROM `remix_intro_cast` c JOIN `creature_template` t ON t.`entry` = c.`source`;
UPDATE `remix_intro_template` r JOIN `remix_intro_cast` c ON c.`entry` = r.`cast_entry`
SET r.`entry` = c.`entry`, r.`name` = c.`name`, r.`subname` = c.`subname`, r.`unit_flags` = c.`unit_flags`,
    r.`flags_extra` = c.`flags_extra`, r.`faction` = c.`faction`, r.`difficulty_entry_1` = 0, r.`difficulty_entry_2` = 0, r.`difficulty_entry_3` = 0,
    r.`KillCredit1` = 0, r.`KillCredit2` = 0, r.`gossip_menu_id` = 0, r.`minlevel` = 10, r.`maxlevel` = 10, r.`npcflag` = 0,
    r.`lootid` = 0, r.`pickpocketloot` = 0, r.`skinloot` = 0, r.`mingold` = 0, r.`maxgold` = 0, r.`VehicleId` = 0,
    r.`MovementType` = 0, r.`AIName` = '', r.`ScriptName` = 'npc_remix_intro', r.`CreatureImmunitiesId` = 0,
    r.`HealthModifier` = 1, r.`DamageModifier` = 1, r.`RegenHealth` = 1, r.`exp` = 2;
ALTER TABLE `remix_intro_template` DROP COLUMN `cast_entry`;
INSERT INTO `creature_template` SELECT * FROM `remix_intro_template`;
DROP TEMPORARY TABLE `remix_intro_template`;

INSERT INTO `creature_template_model` (`CreatureID`, `Idx`, `CreatureDisplayID`, `DisplayScale`, `Probability`, `VerifiedBuild`)
SELECT c.`entry`, m.`Idx`, m.`CreatureDisplayID`, m.`DisplayScale`, m.`Probability`, 0
FROM `remix_intro_cast` c JOIN `creature_template_model` m ON m.`CreatureID` = c.`source`;

-- Their arms: the Ashbringer, Frostmourne, the crusaders', the marines' and the Kor'kron's.
INSERT INTO `creature_equip_template` (`CreatureID`, `ID`, `ItemID1`, `ItemID2`, `ItemID3`, `VerifiedBuild`)
SELECT c.`entry`, e.`ID`, e.`ItemID1`, e.`ItemID2`, e.`ItemID3`, 0
FROM `remix_intro_cast` c JOIN `creature_equip_template` e ON e.`CreatureID` = c.`source` AND e.`ID` = 1;

DROP TEMPORARY TABLE `remix_intro_cast`;
