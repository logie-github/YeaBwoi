-- Bronze as a currency, and the Bazaar's Bronze vendors, after Legion Remix's (the guide's roster:
-- Horos's mounts and rare collections, Domelius's companions and curiosities, Arturos's dungeon
-- gear, Aeonicus's 10-player raids, Durus's 25-player and heroic raids; Alurmi keeps the
-- heirlooms). Their wares are lists of their own, priced in Bronze
-- (wrath_remix_world_0010_vendors.sql, made by tools/vendor_catalog.py).
--
-- Bronze is an item, 77001, in the currency tab like the emblems (a currency token: CurrencyTypes
-- row 342, under Wrath of the Lich King), and every price is an ItemExtendedCost of n Bronze. The
-- client has to know those rows to show them: the module writes them into a patch for the game
-- (Data/patch-Y.MPQ) as the realm starts. The server's side of them is here, in the *_dbc tables
-- the core reads over its DBC files (the Remix world database only).

SET @BRONZE := 77001;

-- The item: Emblem of Frost's, renamed, with the bronze dragonflight's head for an icon.
DELETE FROM `item_template` WHERE `entry` = @BRONZE;
DROP TEMPORARY TABLE IF EXISTS `remix_bronze_item`;
CREATE TEMPORARY TABLE `remix_bronze_item` SELECT * FROM `item_template` WHERE `entry` = 49426;
UPDATE `remix_bronze_item` SET `entry` = @BRONZE, `name` = 'Bronze', `displayid` = 43636, `Quality` = 6, `ItemLevel` = 1,
  `description` = 'Time, minted. Earned for every quest and foe in Wrath Remix, spent at the Infinite Bazaar.';
INSERT INTO `item_template` SELECT * FROM `remix_bronze_item`;
DROP TEMPORARY TABLE `remix_bronze_item`;

DELETE FROM `item_dbc` WHERE `ID` = @BRONZE;
INSERT INTO `item_dbc` (`ID`, `ClassID`, `SubclassID`, `Sound_Override_Subclassid`, `Material`, `DisplayInfoID`, `InventoryType`, `SheatheType`) VALUES
(@BRONZE, 10, 0, -1, -1, 43636, 0, 0);

DELETE FROM `currencytypes_dbc` WHERE `ID` = 342 OR `ItemID` = @BRONZE;
INSERT INTO `currencytypes_dbc` (`ID`, `ItemID`, `CategoryID`, `BitIndex`) VALUES
(342, @BRONZE, 21, 30);

-- Which vendor offers which list (wrath_remix_world_0010_vendors.sql fills it). side: 0 both,
-- 1 Alliance, 2 Horde.
CREATE TABLE IF NOT EXISTS `remix_vendor_list` (
  `list` INT UNSIGNED NOT NULL,
  `vendor` INT UNSIGNED NOT NULL,
  `sort` SMALLINT UNSIGNED NOT NULL DEFAULT 0,
  `side` TINYINT UNSIGNED NOT NULL DEFAULT 0,
  `label` VARCHAR(128) NOT NULL,
  PRIMARY KEY (`list`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- The vendors: Infinite Dragonflight, as the guide's roster has them.
-- (770201 and up: wrath_remix_world_0002_npcs.sql clears 770001 to 770199 when it runs again.)
SET @HOROS := 770201, @DOMELIUS := 770202, @ARTUROS := 770203, @AEONICUS := 770204, @DURUS := 770205;
DELETE FROM `creature_template` WHERE `entry` BETWEEN 770201 AND 770299;
INSERT INTO `creature_template` (`entry`, `name`, `subname`, `minlevel`, `maxlevel`, `exp`, `faction`, `npcflag`,
  `speed_walk`, `speed_run`, `rank`, `unit_class`, `unit_flags`, `unit_flags2`, `type`, `flags_extra`,
  `HealthModifier`, `DamageModifier`, `BaseAttackTime`, `RegenHealth`, `ScriptName`) VALUES
(@HOROS,    'Horos',    'Rare Collections',          80, 80, 2, 35, 129, 1, 1.14286, 0, 1, 0, 2048, 2, 2, 10, 1, 2000, 1, 'npc_remix_bronze_vendor'),
(@DOMELIUS, 'Domelius', 'Companions and Curiosities', 80, 80, 2, 35, 129, 1, 1.14286, 0, 1, 0, 2048, 2, 2, 10, 1, 2000, 1, 'npc_remix_bronze_vendor'),
(@ARTUROS,  'Arturos',  'Dungeon Wares',             80, 80, 2, 35, 129, 1, 1.14286, 0, 1, 0, 2048, 2, 2, 10, 1, 2000, 1, 'npc_remix_bronze_vendor'),
(@AEONICUS, 'Aeonicus', 'Raid Wares (10 Player)',    80, 80, 2, 35, 129, 1, 1.14286, 0, 1, 0, 2048, 2, 2, 10, 1, 2000, 1, 'npc_remix_bronze_vendor'),
(@DURUS,    'Durus',    'Raid Wares (25 Player)',    80, 80, 2, 35, 129, 1, 1.14286, 0, 1, 0, 2048, 2, 2, 10, 1, 2000, 1, 'npc_remix_bronze_vendor');

DELETE FROM `creature_template_model` WHERE `CreatureID` BETWEEN 770201 AND 770299;
INSERT INTO `creature_template_model` (`CreatureID`, `Idx`, `CreatureDisplayID`, `DisplayScale`, `Probability`, `VerifiedBuild`) VALUES
-- (dragons all, in the mortal guises the Infinite wear among mortals)
(@HOROS, 0, 21445, 1, 1, 0),     -- an elven lord (Tydormu's look)
(@DOMELIUS, 0, 21508, 1, 1, 0),  -- an elven lady (Indormi's)
(@ARTUROS, 0, 1504, 1, 1, 0),    -- a human swordsman (Ander Germaine's)
(@AEONICUS, 0, 20222, 1, 1, 0),  -- an elven sorceress (Soridormi's)
(@DURUS, 0, 19549, 1, 1, 0);     -- a human captain (Captain Sanders's)

-- What each says (the module picks it; 7702201 and up, outside 0002's texts).
DELETE FROM `npc_text` WHERE `ID` BETWEEN 7702201 AND 7702299;
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES
(7702201, 'Every drake that ever flew over Northrend, Timerunner, and a few that only flew in one version of it. Bronze, please. Time is not free, but it is negotiable.',
          'Every drake that ever flew over Northrend, Timerunner, and a few that only flew in one version of it. Bronze, please. Time is not free, but it is negotiable.', 1),
(7702202, 'Companions, keepsakes, toys. History remembers the battles; I remember the little things people carried into them.',
          'Companions, keepsakes, toys. History remembers the battles; I remember the little things people carried into them.', 1),
(7702203, 'What the heroes of Northrend took from its dungeons, without the dungeons. Choose a hall, and I will show you what it gave.',
          'What the heroes of Northrend took from its dungeons, without the dungeons. Choose a hall, and I will show you what it gave.', 1),
(7702204, 'Ten champions against the Scourge, again and again. Their spoils are here: the sets the record remembers, and every blade and bauble.',
          'Ten champions against the Scourge, again and again. Their spoils are here: the sets the record remembers, and every blade and bauble.', 1),
(7702205, 'Twenty-five champions, and the heroic hours that broke them. The finest the war ever handed out, and two weapons it handed out only once.',
          'Twenty-five champions, and the heroic hours that broke them. The finest the war ever handed out, and two weapons it handed out only once.', 1);

-- The Bazaar's market lane, along its west side (Horos, Domelius, Arturos, Aeonicus, Durus after
-- Alurmi), each behind the counter of a stall facing the lane (the stalls are
-- wrath_remix_world_0023_bazaar.sql's; tools/bazaar.py keeps these spots). Spawns 7704201 to
-- 7704205 (only those: the chapters' files have the rest of 7704200 to 7704399):
-- wrath_remix_world_0004_spawns.sql has 7704001 to 7704199.
DELETE FROM `creature` WHERE `guid` BETWEEN 7704201 AND 7704205;
INSERT INTO `creature` (`guid`, `id`, `map`, `spawnMask`, `phaseMask`, `equipment_id`, `position_x`, `position_y`, `position_z`, `orientation`, `spawntimesecs`, `wander_distance`, `MovementType`, `Comment`) VALUES
(7704201, @HOROS,    571, 1, 1, 0, 5677.0, 1046.5, 174.6, 4.71, 60, 0, 0, 'Wrath Remix: Horos, Bazaar'),
(7704202, @DOMELIUS, 571, 1, 1, 0, 5668.0, 1046.5, 174.6, 4.71, 60, 0, 0, 'Wrath Remix: Domelius, Bazaar'),
(7704203, @ARTUROS,  571, 1, 1, 0, 5659.0, 1046.0, 174.6, 4.71, 60, 0, 0, 'Wrath Remix: Arturos, Bazaar'),
(7704204, @AEONICUS, 571, 1, 1, 0, 5650.0, 1046.5, 174.6, 4.71, 60, 0, 0, 'Wrath Remix: Aeonicus, Bazaar'),
(7704205, @DURUS,    571, 1, 1, 0, 5641.5, 1046.5, 174.6, 4.71, 60, 0, 0, 'Wrath Remix: Durus, Bazaar');
