-- mod-brill-home (ChromieCraft's characters database alone: ctl.sh realm_sql): a character's house in
-- Brill, the furniture bought for it (its phase bits: src/BrillHome.cpp) and when its bed last gave
-- rested experience (Unix time), and its flags (1: slept in it; 2: the dog's quest done; 4: the
-- dog, Corn, is its pet).
CREATE TABLE IF NOT EXISTS `brill_home` (
    `guid` INT UNSIGNED NOT NULL,
    `upgrades` INT UNSIGNED NOT NULL DEFAULT 0,
    `last_rested` INT UNSIGNED NOT NULL DEFAULT 0,
    `flags` INT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (`guid`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- An adopted dog keeps its saved looks in character_pet, even in the stable: the mastiff's (39700 to
-- 39703) and the plain pug's (31174) made Corn's (39700, the game's pug in the user's Forsaken coat;
-- src/BrillHome.cpp makes a pet out the plain pug again where the module's patch isn't in the game), and
-- his name where it kept the mastiff's. Applied again, it changes nothing.
UPDATE `character_pet` SET `modelid` = 39700 WHERE `entry` = 970115 AND (`modelid` BETWEEN 39701 AND 39703 OR `modelid` = 31174);
UPDATE `character_pet` SET `name` = 'Corn' WHERE `entry` = 970115 AND `name` = 'Mastiff';
