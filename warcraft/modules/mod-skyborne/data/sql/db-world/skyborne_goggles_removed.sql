-- The goggles (item and spell 98708) are taken out of the module; a world database that had them loses them here.
-- A character's goggles, their item gone, are deleted by the core as the character loads.
DELETE FROM `spell_script_names` WHERE `spell_id` = 98708;
DELETE FROM `item_template` WHERE `entry` = 98708;
DELETE FROM `spell_dbc` WHERE `ID` = 98708;
DELETE FROM `playercreateinfo_spell_custom` WHERE `Spell` = 98708;
