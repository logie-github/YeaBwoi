-- mod-brill-home (ChromieCraft's characters database alone: ctl.sh realm_sql): a character's house in
-- Brill, the furniture bought for it (its phase bits: src/BrillHome.cpp) and when its bed last gave
-- rested experience (Unix time), and its flags (1: slept in it; 2: the mastiff's quest done; 4: the
-- mastiff is its pet).
CREATE TABLE IF NOT EXISTS `brill_home` (
    `guid` INT UNSIGNED NOT NULL,
    `upgrades` INT UNSIGNED NOT NULL DEFAULT 0,
    `last_rested` INT UNSIGNED NOT NULL DEFAULT 0,
    `flags` INT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (`guid`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
