-- Felsong's realm on the phone: what the app adds to LegionCore's databases as the package makes
-- them (scripts/package-legioncore.sh).

-- The account the realm's programs and the app use, on 127.0.0.1 only (ctl.sh's DB_USER).
CREATE USER IF NOT EXISTS 'felsong'@'127.0.0.1' IDENTIFIED BY 'felsong';
CREATE USER IF NOT EXISTS 'felsong'@'localhost' IDENTIFIED BY 'felsong';
GRANT ALL PRIVILEGES ON `auth`.* TO 'felsong'@'127.0.0.1', 'felsong'@'localhost';
GRANT ALL PRIVILEGES ON `characters`.* TO 'felsong'@'127.0.0.1', 'felsong'@'localhost';
GRANT ALL PRIVILEGES ON `world`.* TO 'felsong'@'127.0.0.1', 'felsong'@'localhost';
GRANT ALL PRIVILEGES ON `hotfixes`.* TO 'felsong'@'127.0.0.1', 'felsong'@'localhost';
FLUSH PRIVILEGES;

-- The realm: Felsong, on this phone, on its own world and instance ports (ctl.sh's WORLD_PORT,
-- INSTANCE_PORT), for the 7.3.5 (26972) client.
UPDATE `auth`.`realmlist`
SET `name` = 'Felsong', `address` = '127.0.0.1', `localAddress` = '127.0.0.1',
    `port` = 8095, `gamePort` = 8096, `gamebuild` = 26972, `flag` = 0
WHERE `id` = 1;
