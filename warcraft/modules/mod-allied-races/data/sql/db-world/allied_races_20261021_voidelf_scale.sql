-- Restore native Void Elf size for existing worlds; Nightborne displays are unchanged.
UPDATE `creaturedisplayinfo_dbc`
SET `CreatureModelScale` = 1.0
WHERE `ID` IN (33001, 33002);
