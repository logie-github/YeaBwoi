-- Undead paladins (mod-allied-races) train at the priest trainers: the core gives them a paladin
-- trainer's lessons there (patches/azerothcore/0011), and this gives them the priest trainers' words
-- and options. The game's priest trainers offer training, unlearning talents and dual specialization
-- to priests alone (conditions: the priest's class), and greet everyone else with "I can't train
-- you". Each of those conditions gets a second way through (ElseGroup 7): an undead paladin, with
-- the rest of the first way's conditions; and the "not a priest" greetings leave undead paladins
-- out. Written to be applied again: its own rows are taken out first (by their comment).

SET @COMMENT := 'mod-allied-races: undead paladins at the priest trainers';
SET @CLASS := 15, @RACE := 16;        -- condition types
SET @PRIEST := 16, @PALADIN := 2;     -- class masks
SET @UNDEAD := 16;                    -- race mask
SET @OR := 7;                         -- the ElseGroup added

DELETE FROM `conditions` WHERE `Comment` = @COMMENT;

-- The priest class trainers' gossip menus.
DROP TEMPORARY TABLE IF EXISTS `app_priest_menus`;
CREATE TEMPORARY TABLE `app_priest_menus` (`MenuID` INT UNSIGNED PRIMARY KEY)
SELECT DISTINCT ct.`gossip_menu_id` AS `MenuID`
FROM `creature_template` ct
JOIN `creature_default_trainer` cdt ON cdt.`CreatureId` = ct.`entry`
JOIN `trainer` t ON t.`Id` = cdt.`TrainerId`
WHERE t.`Type` = 0 AND t.`Requirement` = 5 AND ct.`gossip_menu_id` <> 0;

-- Their greetings (14) and options (15) that need a priest, or anyone but a priest (a paladin
-- among them), with that class mask.
DROP TEMPORARY TABLE IF EXISTS `app_priest_sources`;
CREATE TEMPORARY TABLE `app_priest_sources`
SELECT DISTINCT c.`SourceTypeOrReferenceId` AS `Type`, c.`SourceGroup` AS `Grp`, c.`SourceEntry` AS `Entry`,
    c.`SourceId` AS `Id`, c.`ConditionValue1` AS `Classes`, (c.`ConditionValue1` & @PRIEST) <> 0 AS `ForPriests`
FROM `conditions` c
JOIN `app_priest_menus` m ON m.`MenuID` = c.`SourceGroup`
WHERE c.`SourceTypeOrReferenceId` IN (14, 15) AND c.`ElseGroup` = 0 AND c.`ConditionTypeOrReference` = @CLASS
    AND c.`NegativeCondition` = 0 AND c.`ConditionTarget` = 0
    AND ((c.`ConditionValue1` & @PRIEST) <> 0) <> ((c.`ConditionValue1` & @PALADIN) <> 0);

-- The "not a priest" ones: no longer for undead paladins (undead of the other classes come back
-- through the second way, below).
INSERT IGNORE INTO `conditions` (`SourceTypeOrReferenceId`, `SourceGroup`, `SourceEntry`, `SourceId`, `ElseGroup`,
    `ConditionTypeOrReference`, `ConditionTarget`, `ConditionValue1`, `ConditionValue2`, `ConditionValue3`,
    `NegativeCondition`, `ErrorType`, `ErrorTextId`, `ScriptName`, `Comment`)
SELECT `Type`, `Grp`, `Entry`, `Id`, 0, @RACE, 0, @UNDEAD, 0, 0, 1, 0, 0, '', @COMMENT
FROM `app_priest_sources` WHERE NOT `ForPriests`;

-- The second way: the first way's other conditions,
DROP TEMPORARY TABLE IF EXISTS `app_priest_rest`;
CREATE TEMPORARY TABLE `app_priest_rest`
SELECT c.* FROM `conditions` c
JOIN `app_priest_sources` s ON s.`Type` = c.`SourceTypeOrReferenceId` AND s.`Grp` = c.`SourceGroup`
    AND s.`Entry` = c.`SourceEntry` AND s.`Id` = c.`SourceId`
WHERE c.`ElseGroup` = 0 AND c.`ConditionTypeOrReference` <> @CLASS AND c.`Comment` <> @COMMENT;

INSERT IGNORE INTO `conditions` (`SourceTypeOrReferenceId`, `SourceGroup`, `SourceEntry`, `SourceId`, `ElseGroup`,
    `ConditionTypeOrReference`, `ConditionTarget`, `ConditionValue1`, `ConditionValue2`, `ConditionValue3`,
    `NegativeCondition`, `ErrorType`, `ErrorTextId`, `ScriptName`, `Comment`)
SELECT `SourceTypeOrReferenceId`, `SourceGroup`, `SourceEntry`, `SourceId`, @OR,
    `ConditionTypeOrReference`, `ConditionTarget`, `ConditionValue1`, `ConditionValue2`, `ConditionValue3`,
    `NegativeCondition`, `ErrorType`, `ErrorTextId`, `ScriptName`, @COMMENT
FROM `app_priest_rest`;

-- an undead,
INSERT IGNORE INTO `conditions` (`SourceTypeOrReferenceId`, `SourceGroup`, `SourceEntry`, `SourceId`, `ElseGroup`,
    `ConditionTypeOrReference`, `ConditionTarget`, `ConditionValue1`, `ConditionValue2`, `ConditionValue3`,
    `NegativeCondition`, `ErrorType`, `ErrorTextId`, `ScriptName`, `Comment`)
SELECT `Type`, `Grp`, `Entry`, `Id`, @OR, @RACE, 0, @UNDEAD, 0, 0, 0, 0, 0, '', @COMMENT
FROM `app_priest_sources`;

-- and a paladin (for the priests' greetings and options), or any of the "not a priest" classes but
-- the paladin.
INSERT IGNORE INTO `conditions` (`SourceTypeOrReferenceId`, `SourceGroup`, `SourceEntry`, `SourceId`, `ElseGroup`,
    `ConditionTypeOrReference`, `ConditionTarget`, `ConditionValue1`, `ConditionValue2`, `ConditionValue3`,
    `NegativeCondition`, `ErrorType`, `ErrorTextId`, `ScriptName`, `Comment`)
SELECT `Type`, `Grp`, `Entry`, `Id`, @OR, @CLASS, 0, IF(`ForPriests`, @PALADIN, `Classes` & ~@PALADIN), 0, 0, 0, 0, 0, '', @COMMENT
FROM `app_priest_sources`;

DROP TEMPORARY TABLE IF EXISTS `app_priest_menus`;
DROP TEMPORARY TABLE IF EXISTS `app_priest_sources`;
DROP TEMPORARY TABLE IF EXISTS `app_priest_rest`;
