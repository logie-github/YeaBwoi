-- Moratari's part in the war (STORY.md, "Moratari in the war"), written by tools/story.py: edit that
-- and run it again. Her words on the game's quests, where she walks with the Timerunner, and what
-- the Timerunner can say to her and to the war's own people (RemixMoratari.cpp).

DROP TABLE IF EXISTS `remix_quest_whisper`;
CREATE TABLE `remix_quest_whisper` (
  `quest` INT UNSIGNED NOT NULL,
  `moment` TINYINT UNSIGNED NOT NULL,             -- 1 taken, 2 its objectives done, 3 turned in
  `side` TINYINT UNSIGNED NOT NULL DEFAULT 0,     -- 0 both, 1 Alliance, 2 Horde
  `seq` TINYINT UNSIGNED NOT NULL DEFAULT 0,
  `speaker` INT UNSIGNED NOT NULL DEFAULT 770001,
  `delay_ms` INT UNSIGNED NOT NULL DEFAULT 3000,  -- after the line before it
  `after_quest` INT UNSIGNED NOT NULL DEFAULT 0,  -- said only once this is turned in
  `before_quest` INT UNSIGNED NOT NULL DEFAULT 0, -- said only until this is turned in
  `races` INT UNSIGNED NOT NULL DEFAULT 0,        -- a race mask (0: any)
  `text` TEXT NOT NULL,
  PRIMARY KEY (`quest`, `moment`, `side`, `seq`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS `remix_companion`;
CREATE TABLE `remix_companion` (
  `id` INT UNSIGNED NOT NULL,
  `side` TINYINT UNSIGNED NOT NULL DEFAULT 0,
  `zone` INT UNSIGNED NOT NULL DEFAULT 0,          -- 0: anywhere
  `from_quest` INT UNSIGNED NOT NULL DEFAULT 0,    -- turned in (0: from the start)
  `until_quest` INT UNSIGNED NOT NULL DEFAULT 0,   -- turned in ends it (0: never)
  `during_quest` INT UNSIGNED NOT NULL DEFAULT 0,  -- in the log (0: any time)
  `comment` VARCHAR(255) NOT NULL DEFAULT '',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS `remix_place`;
CREATE TABLE `remix_place` (
  `quest` INT UNSIGNED NOT NULL,
  `credit` INT UNSIGNED NOT NULL,                  -- given as they come within the radius
  `map` INT UNSIGNED NOT NULL,
  `x` FLOAT NOT NULL,
  `y` FLOAT NOT NULL,
  `radius` FLOAT NOT NULL DEFAULT 20,
  `scene` INT UNSIGNED NOT NULL DEFAULT 0,         -- played there instead (its end gives the credit)
  `text` TEXT NOT NULL,                            -- Moratari's words there ('' for none)
  PRIMARY KEY (`quest`, `credit`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS `remix_outing`;
CREATE TABLE `remix_outing` (
  `id` INT UNSIGNED NOT NULL,
  `map` INT UNSIGNED NOT NULL,
  `x` FLOAT NOT NULL, `y` FLOAT NOT NULL, `z` FLOAT NOT NULL, `o` FLOAT NOT NULL,
  `scene` INT UNSIGNED NOT NULL DEFAULT 0,          -- played there (remix_scene)
  `private` TINYINT UNSIGNED NOT NULL DEFAULT 0,    -- in a phase of its own
  `undress` TINYINT UNSIGNED NOT NULL DEFAULT 0,    -- the Timerunner's gear unseen while it lasts
  `back` TINYINT UNSIGNED NOT NULL DEFAULT 1,       -- back where they were after
  `flight_from` INT UNSIGNED NOT NULL DEFAULT 0,    -- a flight on her: from this flight point (at x y z)
  `flight_to` INT UNSIGNED NOT NULL DEFAULT 0,
  `mount` INT UNSIGNED NOT NULL DEFAULT 0,          -- her shape under them
  `name` VARCHAR(120) NOT NULL DEFAULT '',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS `remix_outing_line`;
CREATE TABLE `remix_outing_line` (
  `outing` INT UNSIGNED NOT NULL,
  `at_ms` INT UNSIGNED NOT NULL,                    -- after the flight starts
  `text` TEXT NOT NULL,
  PRIMARY KEY (`outing`, `at_ms`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS `remix_look`;
CREATE TABLE `remix_look` (
  `display` INT UNSIGNED NOT NULL,                  -- the new look (CreatureDisplayInfo)
  `from_display` INT UNSIGNED NOT NULL,             -- copied from
  `extra` INT UNSIGNED NOT NULL,                    -- its clothes (CreatureDisplayInfoExtra)
  `from_extra` INT UNSIGNED NOT NULL,               -- copied from
  `bake` TINYINT UNSIGNED NOT NULL DEFAULT 0,       -- keep the copied look's baked texture (0: dressed from its items)
  `item1` INT UNSIGNED NOT NULL DEFAULT 0, `item2` INT UNSIGNED NOT NULL DEFAULT 0, `item3` INT UNSIGNED NOT NULL DEFAULT 0,
  `item4` INT UNSIGNED NOT NULL DEFAULT 0, `item5` INT UNSIGNED NOT NULL DEFAULT 0, `item6` INT UNSIGNED NOT NULL DEFAULT 0,
  `item7` INT UNSIGNED NOT NULL DEFAULT 0, `item8` INT UNSIGNED NOT NULL DEFAULT 0, `item9` INT UNSIGNED NOT NULL DEFAULT 0,
  `item10` INT UNSIGNED NOT NULL DEFAULT 0, `item11` INT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`display`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS `remix_talk`;
CREATE TABLE `remix_talk` (
  `id` INT UNSIGNED NOT NULL,
  `npc` INT UNSIGNED NOT NULL,                     -- the creature it's said to
  `parent` INT UNSIGNED NOT NULL DEFAULT 0,        -- 0: in its first menu; else after that entry's reply
  `ord` TINYINT UNSIGNED NOT NULL DEFAULT 0,
  `side` TINYINT UNSIGNED NOT NULL DEFAULT 0,
  `races` INT UNSIGNED NOT NULL DEFAULT 0,
  `gender` TINYINT UNSIGNED NOT NULL DEFAULT 0,    -- 0 any, 1 male, 2 female
  `quest` INT UNSIGNED NOT NULL DEFAULT 0,         -- only while this is in the log, unfinished
  `after_quest` INT UNSIGNED NOT NULL DEFAULT 0,
  `before_quest` INT UNSIGNED NOT NULL DEFAULT 0,
  `icon` TINYINT UNSIGNED NOT NULL DEFAULT 0,
  `option_text` VARCHAR(255) NOT NULL,
  `reply` INT UNSIGNED NOT NULL DEFAULT 0,         -- npc_text (0: the menu closes)
  `credit` INT UNSIGNED NOT NULL DEFAULT 0,        -- a quest's credit, given as it's said
  `action` TINYINT UNSIGNED NOT NULL DEFAULT 0,    -- 1 scene [param], 2 close, 3 she waits, 4 she comes
  `param` INT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO `remix_companion` (`id`, `side`, `zone`, `from_quest`, `until_quest`, `during_quest`, `comment`) VALUES
(1, 1, 3537, 77014, 77121, 0, 'Chapter I: the Borean Tundra, while the Timerunner finds their feet'),
(2, 2, 3537, 77015, 77122, 0, 'Chapter I: the Borean Tundra, while the Timerunner finds their feet');

INSERT INTO `remix_quest_whisper` (`quest`, `moment`, `side`, `seq`, `speaker`, `delay_ms`, `after_quest`, `before_quest`, `races`, `text`) VALUES
(77014, 3, 1, 1, 770001, 3000, 0, 0, 0, 'Valiance Keep. The fleet came in three days ago, and the Scourge has tested the walls every night since. You remember the smell of this place, I imagine: tar, salt, and far too many soldiers in one harbor.'),
(77014, 3, 1, 2, 770001, 7000, 0, 0, 0, 'I will walk with you while you find your feet. If you would rather I waited somewhere, say so.'),
(77015, 3, 2, 1, 770001, 3000, 0, 0, 0, 'Warsong Hold. Garrosh''s fortress, dug into a cliff that is already full of Nerubians, though no one inside knows it yet. Well. Almost no one.'),
(77015, 3, 2, 2, 770001, 7000, 0, 0, 0, 'I will walk with you while you find your feet. If you would rather I waited somewhere, say so.');

INSERT INTO `remix_talk` (`id`, `npc`, `parent`, `ord`, `side`, `races`, `gender`, `quest`, `after_quest`, `before_quest`, `icon`, `option_text`, `reply`, `credit`, `action`, `param`) VALUES
(77001, 770001, 0, 1, 0, 0, 0, 0, 0, 0, 0, 'Wait for me here. I''d like to walk alone for a while.', 7702600, 0, 3, 0),
(77002, 770001, 0, 2, 0, 0, 0, 0, 0, 0, 0, 'Walk with me again.', 7702601, 0, 4, 0);

DELETE FROM `npc_text` WHERE `ID` BETWEEN 7702600 AND 7702999;
INSERT INTO `npc_text` (`ID`, `text0_0`, `text0_1`, `Probability0`) VALUES
(7702600, 'If you like. I will be where I always am: wherever you next need me. Call me back when you want me.', 'If you like. I will be where I always am: wherever you next need me. Call me back when you want me.', 1),
(7702601, 'I was hoping you would ask. Lead on, $N.', 'I was hoping you would ask. Lead on, $N.', 1);

-- Never offered: "rewarded" while the Timerunner has asked Moratari to wait (RemixMoratari.cpp).
DELETE FROM `quest_template` WHERE `ID` = 77040;
INSERT INTO `quest_template` (`ID`, `QuestType`, `QuestLevel`, `MinLevel`, `QuestSortID`, `Flags`, `LogTitle`, `LogDescription`, `QuestDescription`, `VerifiedBuild`) VALUES
(77040, 2, 1, 1, 0, 0, 'Walking Alone', '', 'Moratari waits at her posts while you walk alone.', 0);
