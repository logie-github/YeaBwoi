-- Turtle WoW's databases on the phone, after Tortoise-WoW's own sql/create_databases.sql and
-- sql/base (scripts/package-tortoise.sh runs all three into the package's seed database folder).
-- Its own MariaDB, user and databases (tw_logon, tw_char, tw_world, tw_logs): nothing of the
-- ChromieCraft realm's (acore_*). The world server applies sql/database_updates as it starts.

CREATE USER IF NOT EXISTS 'tortoise'@'127.0.0.1' IDENTIFIED BY 'tortoise';
CREATE USER IF NOT EXISTS 'tortoise'@'localhost' IDENTIFIED BY 'tortoise';
GRANT ALL PRIVILEGES ON `tw_logon`.* TO 'tortoise'@'127.0.0.1', 'tortoise'@'localhost';
GRANT ALL PRIVILEGES ON `tw_char`.* TO 'tortoise'@'127.0.0.1', 'tortoise'@'localhost';
GRANT ALL PRIVILEGES ON `tw_world`.* TO 'tortoise'@'127.0.0.1', 'tortoise'@'localhost';
GRANT ALL PRIVILEGES ON `tw_logs`.* TO 'tortoise'@'127.0.0.1', 'tortoise'@'localhost';
-- mariadb-admin's shutdown, which the app uses to stop it cleanly.
GRANT SHUTDOWN ON *.* TO 'tortoise'@'127.0.0.1', 'tortoise'@'localhost';
FLUSH PRIVILEGES;

-- The core copies character_inventory into this with INSERT ... SELECT * (a backup it makes as it
-- starts, and .character diffitems), but create_databases.sql doesn't make it.
CREATE TABLE IF NOT EXISTS `tw_char`.`character_inventory_copy` LIKE `tw_char`.`character_inventory`;

-- The realm, for the client on this phone; the app sets the address again before each start (the
-- phone's Wi-Fi address when PCs are let in). 7272 is the 1.18.1 client's build, the only one the
-- login server accepts.
REPLACE INTO `tw_logon`.`realmlist`
  (`id`, `name`, `address`, `port`, `icon`, `realmflags`, `timezone`, `allowedSecurityLevel`, `population`, `realmbuilds`)
VALUES
  (1, 'Tortoise WoW', '127.0.0.1', 8090, 0, 0, 1, 0, 0, '7272');
