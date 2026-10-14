-- Where Wrath Remix's people stand: the prologue in the Caverns of Time, the Infinite Bazaar on the
-- ground of Crystalsong Forest by the Violet Stand (below Dalaran, with the Kirin Tor's teleporter
-- up to the city), and Moratari at each landing and at each of her transitions. Spawn guids in
-- Remix's range (7704000+). Moratari beside an original quest giver is placed from that NPC's own
-- spawn, a step to its side, so she stands where it stands. CI's server test checks the floor under
-- every one of them.

SET @MORATARI := 770001, @ETERNUS := 770002, @ANDORMU := 770003, @NOZARI := 770004,
    @ALURMI := 770005, @ZEPHYR := 770006, @SAAT := 770007, @CHRONOLOGIST := 770008,
    @MOMENTUS := 770009, @ECHO_LANDING := 770021, @ECHO_WRATHGATE := 770022, @ECHO_ICECROWN := 770023;

DELETE FROM `creature` WHERE `guid` BETWEEN 7704001 AND 7704199;

-- The prologue, around the Caverns' flight landing (where new Timerunners appear, facing north).
SET @IX := -8360.7, @IY := -4327.7, @IZ := -208.3;
INSERT INTO `creature` (`guid`, `id`, `map`, `spawnMask`, `phaseMask`, `equipment_id`, `position_x`, `position_y`, `position_z`, `orientation`, `spawntimesecs`, `wander_distance`, `MovementType`, `Comment`) VALUES
(7704001, @MORATARI,       1, 1, 1, 1, @IX,       @IY + 4.5, @IZ, 4.71, 60, 0, 0, 'Wrath Remix: Moratari, prologue'),
(7704002, @ETERNUS,        1, 1, 1, 1, @IX - 6,   @IY + 7,   @IZ, 5.50, 60, 0, 0, 'Wrath Remix: Eternus, prologue'),
(7704003, @ANDORMU,        1, 1, 1, 0, @IX + 6,   @IY + 7,   @IZ, 3.93, 60, 0, 0, 'Wrath Remix: Andormu, prologue'),
(7704004, @ECHO_LANDING,   1, 1, 1, 0, @IX - 9,   @IY + 14,  @IZ, 5.10, 60, 0, 0, 'Wrath Remix: Echo of the Landing'),
(7704005, @ECHO_WRATHGATE, 1, 1, 1, 0, @IX,       @IY + 17,  @IZ, 4.71, 60, 0, 0, 'Wrath Remix: Echo of the Wrathgate'),
(7704006, @ECHO_ICECROWN,  1, 1, 1, 0, @IX + 9,   @IY + 14,  @IZ, 4.30, 60, 0, 0, 'Wrath Remix: Echo of Icecrown');

-- The Infinite Bazaar, on the flat ground west of the Violet Stand.
SET @HX := 5700, @HY := 1015, @HZ := 174.6;
INSERT INTO `creature` (`guid`, `id`, `map`, `spawnMask`, `phaseMask`, `equipment_id`, `position_x`, `position_y`, `position_z`, `orientation`, `spawntimesecs`, `wander_distance`, `MovementType`, `Comment`) VALUES
(7704011, @ETERNUS,      571, 1, 1, 1, @HX,      @HY + 8,  @HZ, 4.71, 60, 0, 0, 'Wrath Remix: Eternus, Bazaar'),
(7704012, @MORATARI,     571, 1, 1, 1, @HX - 5,  @HY + 6,  @HZ, 5.30, 60, 0, 0, 'Wrath Remix: Moratari, Bazaar'),
(7704013, @ANDORMU,      571, 1, 1, 0, @HX + 5,  @HY + 6,  @HZ, 4.10, 60, 0, 0, 'Wrath Remix: Andormu, Bazaar'),
(7704014, @NOZARI,       571, 1, 1, 0, @HX + 8,  @HY + 1,  @HZ, 3.60, 60, 0, 0, 'Wrath Remix: Nozari, Bazaar'),
(7704015, @ALURMI,       571, 1, 1, 0, @HX - 8,  @HY + 1,  @HZ, 6.00, 60, 0, 0, 'Wrath Remix: Alurmi, Bazaar'),
(7704016, @ZEPHYR,       571, 1, 1, 1, @HX - 7,  @HY - 5,  @HZ, 0.60, 60, 0, 0, 'Wrath Remix: Zephyr, Bazaar'),
(7704017, @SAAT,         571, 1, 1, 0, @HX + 7,  @HY - 5,  @HZ, 2.50, 60, 0, 0, 'Wrath Remix: Sa''at, Bazaar'),
(7704018, @CHRONOLOGIST, 571, 1, 1, 0, @HX - 2,  @HY - 8,  @HZ, 1.40, 60, 0, 0, 'Wrath Remix: Chronologist, Bazaar'),
(7704019, @MOMENTUS,     571, 1, 1, 0, @HX + 2,  @HY - 8,  @HZ, 1.80, 60, 0, 0, 'Wrath Remix: Momentus, Bazaar');

-- The instructors, in a row along the Bazaar's east side.
INSERT INTO `creature` (`guid`, `id`, `map`, `spawnMask`, `phaseMask`, `equipment_id`, `position_x`, `position_y`, `position_z`, `orientation`, `spawntimesecs`, `wander_distance`, `MovementType`, `Comment`)
SELECT 7704020 + (`entry` - 770100), `entry`, 571, 1, 1, 0, @HX + 14, @HY - 11 + (`entry` - 770101) * 2.2, @HZ, 3.14, 60, 0, 0,
  CONCAT('Wrath Remix: ', `subname`, ', Bazaar')
FROM `creature_template` WHERE `entry` BETWEEN 770101 AND 770111;

-- Moratari beside original quest givers: at each landing (where WRM-005 ends), at her
-- transitions (wrath_remix_world_0006_bridges.sql, and the later chapters') and at each coast's
-- last quest (WRM-121). A step to the giver's left, facing the same way, in the giver's phases
-- (or those given: Thrall's after the Battle for the Undercity is in its phase, 64, and the
-- Timerunner is back in the world's, 1, once it's turned in).
INSERT INTO `creature` (`guid`, `id`, `map`, `spawnMask`, `phaseMask`, `equipment_id`, `position_x`, `position_y`, `position_z`, `orientation`, `spawntimesecs`, `wander_distance`, `MovementType`, `Comment`)
SELECT 7704050 + n, @MORATARI, c.`map`, 1, IF(b.phase, b.phase, c.`phaseMask`), 1,
  c.`position_x` + 2.5 * COS(c.`orientation` + PI() / 2), c.`position_y` + 2.5 * SIN(c.`orientation` + PI() / 2),
  c.`position_z`, c.`orientation`, 60, 0, 0, CONCAT('Wrath Remix: Moratari, ', what)
FROM `creature` c JOIN (
  SELECT 1 AS n, 116919 AS beside, 'Valiance Keep landing (Recruitment Officer Blythe)' AS what, 0 AS phase UNION ALL
  SELECT 2, 125539, 'Warsong Hold landing (Warsong Recruitment Officer)', 0 UNION ALL
  SELECT 3, 133903, 'Valgarde landing (Macalroy)', 0 UNION ALL
  SELECT 4, 101906, 'Vengeance Landing (Apothecary Lysander)', 0 UNION ALL
  SELECT 5, 99763,  'WRM-006, Alliance Borean Tundra (William Allerton)', 0 UNION ALL
  SELECT 6, 107800, 'WRM-016, Horde Borean Tundra (Mobu)', 0 UNION ALL
  SELECT 7, 203320, 'WRM-026 and WRM-121, Westguard Keep (Captain Adams)', 0 UNION ALL
  SELECT 8, 101136, 'WRM-121, Alliance Borean Tundra (Thassarian)', 0 UNION ALL
  SELECT 9, 125447, 'WRM-121, Horde Borean Tundra (Garrosh Hellscream)', 0 UNION ALL
  SELECT 10, 113677, 'WRM-121, Horde Howling Fjord ("Hacksaw" Jenny)', 0 UNION ALL
  -- Chapter II (wrath_remix_world_0011_chapter2.sql)
  SELECT 11, 133653, 'WRM-201, Alliance Dragonblight (Cavalier Durkon)', 0 UNION ALL
  SELECT 12, 108123, 'WRM-211, Horde Dragonblight (Doctor Sintar Malefious)', 0 UNION ALL
  SELECT 13, 132193, 'WRM-202 and WRM-212, the Wrathgate (Alexstrasza)', 0 UNION ALL
  SELECT 14, 10495,  'WRM-221, Stormwind Keep (King Varian Wrynn)', 0 UNION ALL
  SELECT 15, 3109785, 'WRM-222, the throne room of Lordaeron (Thrall)', 65
) b ON c.`guid` = b.beside;

-- The Azure Dragonflight's raiders at the Violet Stand would kill a new Timerunner on arrival:
-- not in Remix's world.
DELETE FROM `creature` WHERE `id` = 31402 AND `guid` IN (72221, 13424, 13423);
