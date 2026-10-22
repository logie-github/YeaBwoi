-- Apply the smaller allied-race displays to existing worlds as well as fresh installs.
-- Only the four player displays owned by mod-allied-races are affected.
UPDATE `creaturedisplayinfo_dbc`
SET `CreatureModelScale` = 0.9
WHERE `ID` IN (33001, 33002, 33003, 33004);
