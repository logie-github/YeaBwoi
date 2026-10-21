-- Earlier modules may already have extended the faction masks. Do not match the old masks exactly.
UPDATE `playercreateinfo_skills` SET `raceMask` = `raceMask` | 16384 WHERE `skill` = 98 AND `classMask` = 0;
UPDATE `playercreateinfo_skills` SET `raceMask` = `raceMask` | 32768 WHERE `skill` = 109 AND `classMask` = 0;
INSERT IGNORE INTO `playercreateinfo_spell_custom` (`racemask`, `classmask`, `Spell`, `Note`) VALUES
(16384, 0, 668, 'Void Elf - Common'),
(32768, 0, 669, 'Nightborne - Orcish');
