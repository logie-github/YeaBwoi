-- The voices Remix's people speak with as they're clicked on: a look's NPCSoundID in the game's
-- CreatureDisplayInfo.dbc, which Wrath Remix's patch for the game (src/ClientPatch.cpp) sets for
-- the looks named here. Moratari wears Lady Alistra's look (25458), a blood elf woman in dark
-- plate, which speaks as a death knight (NPCSounds 292); she speaks as a blood elf woman of
-- Silvermoon (193, BloodElfFemaleStandard). Lady Alistra, at Acherus, speaks so too on this realm.
CREATE TABLE IF NOT EXISTS `remix_display_sound` (
  `display` INT UNSIGNED NOT NULL,
  `npc_sound` INT UNSIGNED NOT NULL,
  PRIMARY KEY (`display`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DELETE FROM `remix_display_sound`;
INSERT INTO `remix_display_sound` (`display`, `npc_sound`) VALUES
(25458, 193);
